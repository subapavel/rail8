package dev.rail8.runtime.secret;

import dev.rail8.api.Health;
import dev.rail8.api.Secret;
import dev.rail8.api.Timer;
import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The node's secret store: the secrets sealed in Postgres, opened and held in the node's memory, and the writes, the list and
 * the record of uses.
 *
 * <p><b>Reading.</b> A read ({@link #held}) never goes to Postgres: it answers from what the node holds. It looks at the store's
 * generation and stamp at each {@link #notice()}, after each {@code LISTEN} acknowledged, and at each tick of
 * {@value #TICK_SECONDS} s on the injected timer; looks run one at a time, and calls during one share the next. When the
 * generation or the stamp differs from what it holds, it reads every row in one repeatable-read transaction, opens every row and
 * drops the names that are gone. A row that does not open, that is under a key the node does not hold, or whose sealed value is
 * longer than {@value #MAX_ROW_BYTES} bytes is held unreadable: it gives no value, it never falls back to a variable, it makes
 * {@link #health()} {@code DEGRADED}, and one WARNING names its name and key id. A generation behind the one held, or the same
 * generation with another stamp, is a WARNING: the database went back to an earlier state. After {@value #FAILED_LOOKS} looks in
 * a row that could not read the store, the node is {@code DEGRADED}, naming the time of its last good read, with one WARNING.
 *
 * <p><b>Writing.</b> {@link #create}, {@link #overwrite} and {@link #delete} each check the body, the name and the value outside
 * any transaction, then in one transaction: the name's lock (a create or a delete), the store's row lock, the key check, the
 * row checks, the seal and the upsert or the delete, the audit row, the generation and stamp, and the notice. A write that waits
 * longer than 5 s for its locks is 503 {@code busy}. After the commit this node looks again, so a write is in force here when it
 * is answered; when that look fails, the write is 503 {@code written-unread}: it is in force within one tick. A refusal is a
 * {@link SecretRefused}.
 *
 * <p><b>The key.</b> A node seals under its own current key only. A write is 409 {@code keys-differ} unless every live node
 * holds that key, current or previous, by its current join. Once every live node names the same current key, each tick seals
 * again up to {@value #BATCH} rows under another key and asks each {@link BoundRows} to bind again, in the store's transactions; the node that seals the last
 * writes the audit row {@code secret.key-changed}. A node drops its previous key once every live node names its current key and
 * no row, nor any bound row, is under the previous one.
 *
 * <p>No text of this class, no answer and no audit row holds a value.
 */
public final class SecretStore {

    /** The scope of every secret now. */
    public static final String SCOPE = "installation";
    /** The channel a write notifies on. */
    public static final String CHANNEL = "rail8_secrets";
    /** The platform's git credentials, the one {@code rail8.} name that may be created. */
    public static final String GIT = "rail8.git";
    /** What every name the platform owns starts with. */
    public static final String RESERVED = "rail8.";

    static final int MAX_SECRETS = 1_000;
    static final long MAX_SEALED_BYTES = 16L * 1024 * 1024;
    static final int MAX_VALUE_BYTES = 32 * 1024;
    static final int MAX_USER = 256;
    static final int MAX_PASSWORD = 4_096;
    static final int MAX_NAME = 128;
    /** The longest sealed value a read opens; a longer row is unreadable without being read. */
    static final int MAX_ROW_BYTES = 256 * 1024;
    /** The users an {@code in-use} answer and a delete's audit name; beyond them, their count. */
    static final int MAX_USERS_NAMED = 100;
    /** The looks in a row that failed before the node is {@code DEGRADED}. */
    static final int FAILED_LOOKS = 3;
    /** Rows sealed again per tick. */
    static final int BATCH = 100;
    static final long TICK_SECONDS = 5;
    static final Duration TICK = Duration.ofSeconds(TICK_SECONDS);

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final String VARIABLE = "RAIL8_SECRET_";
    private static final byte[] BIND_INFO = "rail8 settings v1 bind".getBytes(StandardCharsets.US_ASCII);
    private static final HexFormat HEX = HexFormat.of();
    private static final System.Logger LOG = System.getLogger(SecretStore.class.getName());

    /** What a read of a stored secret gives: its kind, the id of the key it is under, and its value, null when unreadable. */
    public record Held(Secret.Kind kind, String keyId, Object value) {

        /** Whether the node opened it. */
        public boolean readable() {
            return value != null;
        }

        /** The kind and the key's id, never the value. */
        @Override
        public String toString() {
            return "Held[" + kind.id() + ", key " + keyId + (value == null ? ", unreadable" : "") + "]";
        }
    }

    /** A binding of the configuration's bytes: the id of the key it is under, and its HMAC-SHA256 tag. */
    public record Binding(byte[] keyId, byte[] tag) {
        public Binding {
            keyId = keyId.clone();
            tag = tag.clone();
        }

        @Override public byte[] keyId() { return keyId.clone(); }

        @Override public byte[] tag() { return tag.clone(); }

        @Override public boolean equals(Object other) {
            return other instanceof Binding b && Arrays.equals(keyId, b.keyId) && Arrays.equals(tag, b.tag);
        }

        @Override public int hashCode() { return Arrays.hashCode(keyId) * 31 + Arrays.hashCode(tag); }

        @Override public String toString() { return "Binding[key " + HEX.formatHex(keyId) + "]"; }
    }

    /**
     * A table whose rows are bound under the store's key: the key is kept while any of them is under it. Each call runs in the
     * store's transaction {@code tx}, under its row lock.
     */
    public interface BoundRows {
        /** The rows bound under the key {@code keyId}. */
        Future<Long> countUnder(SqlConnection tx, byte[] keyId);

        /** Binds again under the current key up to {@code max} rows bound under another; answers how many. */
        Future<Integer> bindAgain(SqlConnection tx, int max);
    }

    /** The uses of one user, {@code (userKind, userId)}: by name, the kinds each use takes; empty when it uses none. */
    public record Uses(String userKind, String userId, Map<String, Set<Secret.Kind>> uses) {
        public Uses {
            Objects.requireNonNull(userKind, "userKind");
            Objects.requireNonNull(userId, "userId");
            uses = Map.copyOf(uses);
        }
    }

    /** A row as the node opened it: its kind, key id, nonce and value, null when unreadable. */
    private record Opened(Secret.Kind kind, byte[] keyId, byte[] nonce, SecretValue value) { }

    private record State(SecretRows.Head head, Map<String, Opened> held) { }

    private final SecretRows rows;
    private final String node;
    private final List<String> variables;
    private final Clock clock;
    private final Timer timer;
    private final Consumer<String> warn;

    private volatile StoreKey key;
    private volatile State state = new State(null, Map.of());
    /** The previous key was dropped while its variable is still set. */
    private volatile boolean previousSet;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
    private final List<BoundRows> bound = new CopyOnWriteArrayList<>();
    private final AtomicBoolean maintaining = new AtomicBoolean();
    private volatile Future<Void> ticked = Future.succeededFuture();
    /** The looks in a row that failed, and the time of the last good one. */
    private volatile int failedLooks;
    private volatile Instant lastGood;
    /** A tick's work on the key failed, and its WARNING was logged. */
    private volatile boolean maintainFailed;

    private final Object lock = new Object();
    private boolean looking;               // guarded by lock: a look runs
    private Promise<Void> queued;          // guarded by lock: the look that runs after it, null: none
    private Timer.Cancellable tick;        // guarded by lock
    private boolean closed;                // guarded by lock

    SecretStore(SecretRows rows, StoreKey key, String node, Collection<String> variables, Clock clock, Timer timer, Consumer<String> warn) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.key = Objects.requireNonNull(key, "key");
        this.node = Objects.requireNonNull(node, "node");
        this.variables = List.copyOf(new TreeSet<>(variables));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Node {@code node}'s store over Postgres's {@code pool}, under {@code key}, its {@code RAIL8_SECRET_*} variables named by
     * {@code variables} (names only, as the node reads them after their file forms), its ticks on {@code timer}. Reads nothing
     * before {@link #start}.
     */
    public static SecretStore postgres(Pool pool, StoreKey key, String node, Collection<String> variables, Clock clock, Timer timer) {
        return new SecretStore(new PgSecretRows(pool, PgSecretRows.LOCK_WAIT), key, node, variables, clock, timer,
                text -> LOG.log(System.Logger.Level.WARNING, text));
    }

    /** Where the placeholder key pair of {@code rail8 validate} and {@code rail8 test} lies on the class path: the testkit's. */
    static final String PLACEHOLDER_PAIR = "/dev/rail8/testkit/invalid.pem";

    /**
     * The placeholder key pair, a fixed EC P-256 pair with a self-signed certificate named invalid, read from the testkit on the
     * class path.
     *
     * @throws IllegalStateException the testkit is not on the class path
     */
    public static Secret.KeyPair placeholderKeyPair() {
        try (var in = SecretStore.class.getResourceAsStream(PLACEHOLDER_PAIR)) {
            if (in == null) throw new IllegalStateException("the placeholder key pair " + PLACEHOLDER_PAIR + " is not on the class path: add rail8-testkit");
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            int certificate = pem.indexOf("-----BEGIN CERTIFICATE-----");
            return KeyPairs.parse(pem.substring(0, certificate), List.of(pem.substring(certificate))).keyPair();
        } catch (java.io.IOException | SecretRefused failure) {
            throw new IllegalStateException("the placeholder key pair " + PLACEHOLDER_PAIR + " does not read", failure);
        }
    }

    /** The variable of {@code name}: {@code RAIL8_SECRET_} and the name upper-cased, {@code .} and {@code -} as {@code _}. */
    public static String variable(String name) {
        return VARIABLE + name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    // ---------------------------------------------------------------------------------------------------------------- start

    /**
     * Joins the store with the epoch of this node's join to the pool, reads every row and arms the tick. The join is one
     * transaction under the store's row lock: it writes this node's key ids, its variables' names and {@code epoch}, then checks
     * every row's key id.
     *
     * @return fails with an {@link IllegalStateException} naming the rows' count and key ids when a row is under a key this node
     *         does not hold, or the store holds rows and this node no key
     */
    public Future<Void> start(long epoch) {
        StoreKey k = key;
        byte[] current = k.current().map(Seal::keyId).orElse(null);
        byte[] previous = k.previous().map(Seal::keyId).orElse(null);
        return rows.<Void>write(List.of(), tx -> tx.joined(node, epoch, current, previous, variables).compose(v -> tx.rows()).compose(all -> {
            Map<String, Integer> foreign = new TreeMap<>();
            for (SecretRows.Meta m : all) {
                if (!Arrays.equals(m.keyId(), current) && !Arrays.equals(m.keyId(), previous)) foreign.merge(HEX.formatHex(m.keyId()), 1, Integer::sum);
            }
            if (foreign.isEmpty()) return Future.succeededFuture();
            int count = foreign.values().stream().mapToInt(Integer::intValue).sum();
            String under = foreign.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
            return Future.failedFuture(new IllegalStateException(current == null
                    ? "the secret store holds " + count + " rows, sealed under the keys " + under + ", and this node holds no key: start it with "
                      + StoreKey.VARIABLE + " (or " + StoreKey.VARIABLE + "_FILE)"
                    : count + " rows of the secret store are sealed under keys this node does not hold: " + under + "; it holds " + k
                      + ": give the key they are under as " + StoreKey.VARIABLE + ", or as " + StoreKey.PREVIOUS + " while the key is changed"));
        })).compose(v -> look()).onSuccess(v -> arm());
    }

    /** Arms no further tick. */
    public void close() {
        synchronized (lock) {
            closed = true;
            if (tick != null) tick.cancel();
            tick = null;
        }
    }

    private void arm() {
        synchronized (lock) {
            if (closed) return;
            tick = timer.schedule(TICK, this::tick);
        }
    }

    private void tick() {
        arm();
        Future<Void> looked = look();
        if (!maintaining.compareAndSet(false, true)) {
            ticked = looked;
            return;
        }
        Future<Void> maintained = maintain().onComplete(done -> {
            maintaining.set(false);
            if (done.succeeded()) {
                maintainFailed = false;
            } else if (!maintainFailed) {
                maintainFailed = true;
                warn.accept("the secret store's work on its key failed and is tried again at each tick: " + done.cause().getClass().getName());
            }
        });
        ticked = Future.join(looked, maintained).mapEmpty();
    }

    /** The work of the last tick: tests wait on it after they move the clock. */
    Future<Void> ticked() {
        return ticked;
    }

    // ---------------------------------------------------------------------------------------------------------------- refresh

    /** A change may have happened: a notice on {@value #CHANNEL}, or a {@code LISTEN} acknowledged. The node looks. */
    public void notice() {
        look();
    }

    /**
     * Looks at the store's generation and stamp, and reads every row when they differ from what is held; completes once a look
     * that started after this call has ended. Looks run one at a time; calls while one runs share the next.
     */
    Future<Void> look() {
        Promise<Void> mine;
        boolean start;
        synchronized (lock) {
            if (queued != null) return queued.future();
            queued = Promise.promise();
            mine = queued;
            start = !looking;
            looking = true;
        }
        if (start) drain();
        return mine.future();
    }

    private void drain() {
        Promise<Void> next;
        synchronized (lock) {
            next = queued;
            queued = null;
            if (next == null) {
                looking = false;
                return;
            }
        }
        Future<Void> run;
        try {
            run = once();
        } catch (RuntimeException failure) {
            run = Future.failedFuture(failure);
        }
        run.onComplete(done -> {
            if (done.succeeded()) {
                failedLooks = 0;
                lastGood = clock.instant();
            } else if (++failedLooks == FAILED_LOOKS) {
                warn.accept("the secret store could not be read " + FAILED_LOOKS + " times in a row: the node holds what it read "
                        + (lastGood == null ? "at its start" : "at " + lastGood) + "; " + done.cause().getClass().getName());
            }
            next.handle(done);
            drain();
        });
    }

    private Future<Void> once() {
        return rows.head().compose(head -> {
            if (head.equals(state.head())) return Future.succeededFuture();
            return rows.snapshot(MAX_ROW_BYTES).map(snapshot -> {
                apply(snapshot);
                return null;
            });
        });
    }

    private void apply(SecretRows.Snapshot snapshot) {
        State before = state;
        SecretRows.Head was = before.head();
        SecretRows.Head now = snapshot.head();
        if (was != null && (now.generation() < was.generation() || (now.generation() == was.generation() && now.stamp() != was.stamp()))) {
            warn.accept("the secret store went back to an earlier state: generation " + now.generation() + ", where this node held "
                    + was.generation() + "; the node holds what it read now");
        }
        StoreKey k = key;
        Map<String, Opened> next = new HashMap<>();
        Set<String> changed = new HashSet<>();
        for (SecretRows.Meta m : snapshot.rows()) {
            Opened old = before.held().get(m.name());
            Opened opened = open(k, m, snapshot.sealed().get(m.name()));
            next.put(m.name(), opened);
            if (old == null || !sameValue(old, opened)) changed.add(m.name());
        }
        for (String name : before.held().keySet()) if (!next.containsKey(name)) changed.add(name);
        state = new State(now, Map.copyOf(next));
        if (!changed.isEmpty()) {
            Set<String> names = Set.copyOf(changed);
            for (Consumer<Set<String>> listener : listeners) {
                try {
                    listener.accept(names);
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "a listener of the secrets' changes failed: " + failure.getClass().getName());
                }
            }
        }
    }

    private static boolean sameValue(Opened a, Opened b) {
        if (a.value() == null || b.value() == null) return a.value() == b.value();
        return a.kind() == b.kind() && MessageDigest.isEqual(a.value().plain(), b.value().plain());
    }

    /** {@code m} opened under the key its id names, or held unreadable with one WARNING. */
    private Opened open(StoreKey k, SecretRows.Meta m, byte[] sealed) {
        String id = HEX.formatHex(m.keyId());
        byte[] under = keyFor(k, m.keyId());
        if (under == null) {
            warnOnce(m, "the secret '" + m.name() + "' is sealed under the key " + id + ", which this node does not hold: it is unreadable");
            return new Opened(m.kind(), m.keyId(), m.nonce(), null);
        }
        if (sealed == null) {
            warnOnce(m, "the secret '" + m.name() + "' under the key " + id + " holds a sealed value of " + m.sealedBytes() + " bytes, more than "
                    + MAX_ROW_BYTES + ": it is unreadable; delete it and enter it again");
            return new Opened(m.kind(), m.keyId(), m.nonce(), null);
        }
        try {
            byte[] plain = Seal.open(under, m.format(), SCOPE, m.name(), m.kind(), new Seal.Sealed(m.keyId(), m.nonce(), sealed));
            return new Opened(m.kind(), m.keyId(), m.nonce(), SecretValue.fromPlain(m.kind(), plain));
        } catch (GeneralSecurityException | RuntimeException failure) {   // the failure's text is not shown: it may quote the value
            warnOnce(m, "the secret '" + m.name() + "' under the key " + id + " does not open: it is unreadable; delete it and enter it again");
            return new Opened(m.kind(), m.keyId(), m.nonce(), null);
        }
    }

    private void warnOnce(SecretRows.Meta m, String text) {
        if (warned.add(m.name() + "/" + HEX.formatHex(m.nonce()))) warn.accept(text);
    }

    private static byte[] keyFor(StoreKey k, byte[] keyId) {
        Optional<byte[]> current = k.current();
        if (current.isPresent() && Arrays.equals(Seal.keyId(current.get()), keyId)) return current.get();
        Optional<byte[]> previous = k.previous();
        if (previous.isPresent() && Arrays.equals(Seal.keyId(previous.get()), keyId)) return previous.get();
        return null;
    }

    /** Runs {@code listener} with the names whose value, kind or readability changed, or that appeared or went, at each change held. */
    public void onChange(Consumer<Set<String>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---------------------------------------------------------------------------------------------------------------- reads

    /**
     * What this node holds of {@code name}: empty when the store holds no row of it. It never asks Postgres.
     *
     * @throws IllegalArgumentException {@code name} is under {@code rail8.}: only {@link #platform} reads those
     */
    public Optional<Held> held(String name) {
        if (name.startsWith(RESERVED)) throw new IllegalArgumentException("'" + name + "' belongs to the platform: no flow, endpoint or setting reads it");
        return heldAny(name);
    }

    /**
     * The platform's own read of a {@code rail8.} name, for its own code only.
     *
     * @throws IllegalArgumentException {@code name} is not under {@code rail8.}
     */
    public Optional<Held> platform(String name) {
        if (!name.startsWith(RESERVED)) throw new IllegalArgumentException("'" + name + "' is not a name of the platform");
        return heldAny(name);
    }

    private Optional<Held> heldAny(String name) {
        Opened o = state.held().get(name);
        return o == null ? Optional.empty() : Optional.of(new Held(o.kind(), HEX.formatHex(o.keyId()), o.value() == null ? null : o.value().read()));
    }

    /** The names of this node's {@code RAIL8_SECRET_*} variables. */
    public List<String> variables() {
        return variables;
    }

    /**
     * {@code DEGRADED} while the last {@value #FAILED_LOOKS} looks failed, while a row is unreadable, or while the previous key's
     * variable is set though no row needs it.
     */
    public Health health() {
        if (failedLooks >= FAILED_LOOKS) {
            return Health.degraded("the secret store could not be read in the last " + failedLooks + " looks: this node holds what it read "
                    + (lastGood == null ? "at its start" : "at " + lastGood));
        }
        List<String> unreadable = new TreeSet<>(state.held().entrySet().stream().filter(e -> e.getValue().value() == null).map(Map.Entry::getKey).toList())
                .stream().toList();
        if (!unreadable.isEmpty()) {
            return Health.degraded("the secrets " + unreadable + " do not open on this node: the flows that use them fail; delete each and enter it again, or start the node with the key they are under");
        }
        if (previousSet) return Health.degraded(StoreKey.PREVIOUS + " is still set though no row needs it: remove it from this node's environment");
        return Health.up();
    }

    // ---------------------------------------------------------------------------------------------------------------- writes

    /**
     * Creates the secret of {@code body}: {@code {name, kind:"value", value}}, {@code {name, kind:"credentials", user, password}}
     * or {@code {name, kind:"key-pair", privateKey, certificates}}. Answers its entry of the list.
     *
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code reserved}, {@code no-key}, {@code keys-differ},
     *         {@code exists}, {@code kind-in-use}, {@code store-full}, {@code audit-unavailable}
     */
    public Future<JsonObject> create(Actor who, JsonObject body) {
        String name;
        SecretValue value;
        try {
            name = writable(body.getValue("name"));
            value = value(body);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        StoreKey k = key;
        return rows.<SecretRows.Head>write(List.of(name), tx -> keyCheck(tx, k).compose(v -> tx.rows()).compose(all -> {
            if (all.stream().anyMatch(m -> m.name().equals(name))) {
                return refuse(409, "exists", "the secret '" + name + "' exists: overwrite it, or delete it first", name);
            }
            if (all.size() >= MAX_SECRETS) return refuse(409, "store-full", "the store holds " + MAX_SECRETS + " secrets, its most", name);
            return tx.uses(name).compose(uses -> {
                for (SecretRows.Use use : uses) {
                    if (!use.kinds().contains(value.kind())) {
                        return Future.failedFuture(new SecretRefused(409, "kind-in-use", use.userKind() + " '" + use.userId() + "' uses '" + name
                                + "' as " + ids(use.kinds()) + ", not as " + value.kind().id(), new JsonObject().put("name", name)
                                .put("kind", value.kind().id()).put("user", user(use))));
                    }
                }
                Seal.Sealed sealed = Seal.seal(k.current().orElseThrow(), SCOPE, name, value.kind(), value.plain());
                Instant now = clock.instant();
                return full(all, null, sealed).compose(v -> tx.upsert(new SecretRows.Stored(new SecretRows.Meta(name, value.kind(), Seal.FORMAT,
                        sealed.keyId(), sealed.nonce(), sealed.sealed().length, now, now, who.name()), sealed.sealed())));
            }).compose(v -> audit(tx, who, "secret.create", name, detail(value))).compose(v -> tx.changed());
        })).compose(v -> readBack(name));
    }

    /**
     * Overwrites {@code name} with the value of {@code body}, of its kind; the last writer wins. Answers its entry of the list.
     *
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code reserved}, {@code no-key}, {@code keys-differ},
     *         {@code not-found}, {@code kind-differs}, {@code store-full}, {@code audit-unavailable}
     */
    public Future<JsonObject> overwrite(Actor who, String name, JsonObject body) {
        SecretValue value;
        try {
            writable(name);
            value = value(body);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        StoreKey k = key;
        return rows.<SecretRows.Head>write(List.of(), tx -> keyCheck(tx, k).compose(v -> tx.rows()).compose(all -> {
            SecretRows.Meta old = all.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
            if (old == null) return refuse(404, "not-found", "the store holds no secret '" + name + "'", name);
            if (old.kind() != value.kind()) {
                return Future.failedFuture(new SecretRefused(409, "kind-differs", "'" + name + "' is a secret of kind " + old.kind().id()
                        + ", not " + value.kind().id() + ": delete it and create it again", new JsonObject().put("name", name)
                        .put("kind", old.kind().id()).put("asked", value.kind().id())));
            }
            Seal.Sealed sealed = Seal.seal(k.current().orElseThrow(), SCOPE, name, value.kind(), value.plain());
            return full(all, old, sealed).compose(v -> tx.upsert(new SecretRows.Stored(new SecretRows.Meta(name, value.kind(), Seal.FORMAT,
                    sealed.keyId(), sealed.nonce(), sealed.sealed().length, old.createdAt(), clock.instant(), who.name()), sealed.sealed())))
                    .compose(v -> audit(tx, who, "secret.overwrite", name, detail(value))).compose(v -> tx.changed());
        })).compose(v -> readBack(name));
    }

    /**
     * Deletes {@code name}. A name with a use, or while a record's uses are not known, is deleted only when {@code confirm}
     * equals it.
     *
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code not-found}, {@code in-use}, {@code audit-unavailable}
     */
    public Future<Void> delete(Actor who, String name, Optional<String> confirm) {
        try {
            name(name);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        boolean confirmed = confirm.filter(name::equals).isPresent();
        return rows.<SecretRows.Head>write(List.of(name), tx -> tx.row(name).compose(row -> {
            if (row.isEmpty()) return refuse(404, "not-found", "the store holds no secret '" + name + "'", name);
            return tx.uses(name).compose(uses -> tx.unknownRecords().compose(unknown -> {
                JsonArray usedBy = users(uses);
                JsonArray mayUse = new JsonArray(unknown.subList(0, Math.min(MAX_USERS_NAMED, unknown.size())));
                if ((!uses.isEmpty() || !unknown.isEmpty()) && !confirmed) {
                    return Future.failedFuture(new SecretRefused(409, "in-use", "'" + name + "' is used"
                            + (uses.isEmpty() ? "" : " by " + uses.getFirst().userKind() + " '" + uses.getFirst().userId() + "'")
                            + (uses.size() > 1 ? " and " + (uses.size() - 1) + " more" : "")
                            + (unknown.isEmpty() ? "" : (uses.isEmpty() ? " perhaps" : ", and perhaps") + " by the package '" + unknown.getFirst() + "'")
                            + ": delete it with ?confirm=" + name, new JsonObject().put("name", name).put("usedBy", usedBy).put("users", uses.size())
                            .put("mayUse", mayUse).put("packages", unknown.size())));
                }
                JsonObject detail = new JsonObject().put("kind", row.get().meta().kind().id());
                Opened held = state.held().get(name);
                if (held != null && held.value() instanceof SecretValue.Pair pair) facts(detail, pair);
                detail.put("confirmed", confirmed).put("users", usedBy).put("userCount", uses.size());
                return tx.delete(name).compose(v -> audit(tx, who, "secret.delete", name, detail)).compose(v -> tx.changed());
            }));
        })).compose(v -> look().recover(SecretStore::writtenUnread));
    }

    /** After a write's commit: this node looks again, and answers the entry of {@code name}; 503 {@code written-unread} when it cannot. */
    private Future<JsonObject> readBack(String name) {
        return look().compose(v -> entry(name)).map(Optional::orElseThrow).recover(SecretStore::writtenUnread);
    }

    private static <T> Future<T> writtenUnread(Throwable failure) {
        return Future.failedFuture(new SecretRefused(503, "written-unread", "the change is written, but this node could not read it back: it is in force"
                + " within " + TICK_SECONDS + " s; do not send it again", new JsonObject()));
    }

    /** Refuses a write unless this node holds a key and every live node holds it, by its current join. */
    private Future<Void> keyCheck(SecretRows.Tx tx, StoreKey k) {
        if (!k.present()) return noKey();
        return tx.liveNodes().compose(nodes -> keyCheck(nodes, k));
    }

    private static <T> Future<T> noKey() {
        return Future.failedFuture(new SecretRefused(503, "no-key", "this node holds no key of the secret store: start it with " + StoreKey.VARIABLE));
    }

    private static Future<Void> keyCheck(List<SecretRows.NodeKeys> nodes, StoreKey k) {
        if (!k.present()) return noKey();
        byte[] id = Seal.keyId(k.current().orElseThrow());
        List<String> lacking = nodes.stream().filter(n -> !n.holds(id)).map(SecretRows.NodeKeys::node).sorted().toList();
        if (lacking.isEmpty()) return Future.succeededFuture();
        return Future.failedFuture(new SecretRefused(409, "keys-differ", "the nodes " + lacking + " do not hold the key " + HEX.formatHex(id)
                + " this node seals under: try again once the roll of the key has ended", new JsonObject().put("key", HEX.formatHex(id))
                .put("nodes", new JsonArray(lacking))));
    }

    /** Refuses a write whose sealed values together would take more than {@value #MAX_SEALED_BYTES} bytes. */
    private static Future<Void> full(List<SecretRows.Meta> all, SecretRows.Meta replaced, Seal.Sealed sealed) {
        long total = sealed.sealed().length;
        for (SecretRows.Meta m : all) if (m != replaced) total += m.sealedBytes();
        if (total <= MAX_SEALED_BYTES) return Future.succeededFuture();
        return Future.failedFuture(new SecretRefused(409, "store-full", "the sealed values would take more than " + MAX_SEALED_BYTES + " bytes, the store's most",
                new JsonObject().put("bytes", MAX_SEALED_BYTES)));
    }

    private static Future<Void> audit(SecretRows.Tx tx, Actor who, String action, String name, JsonObject detail) {
        return tx.audit(who, action, "secret/" + name, detail).recover(failure -> Future.failedFuture(new SecretRefused(503, "audit-unavailable",
                "the audit row could not be written, so nothing was changed: try again")));
    }

    private static <T> Future<T> refuse(int status, String code, String text, String name) {
        return Future.failedFuture(new SecretRefused(status, code, text, new JsonObject().put("name", name)));
    }

    private static JsonObject user(SecretRows.Use use) {
        return new JsonObject().put("kind", use.userKind()).put("id", use.userId()).put("kinds", new JsonArray(ids(use.kinds())));
    }

    private static List<String> ids(Set<Secret.Kind> kinds) {
        return kinds.stream().sorted().map(Secret.Kind::id).toList();
    }

    /** The audit's detail of a written value: its kind, and a key pair's fingerprint and leaf's end. */
    private static JsonObject detail(SecretValue value) {
        JsonObject detail = new JsonObject().put("kind", value.kind().id());
        if (value instanceof SecretValue.Pair pair) facts(detail, pair);
        return detail;
    }

    private static void facts(JsonObject detail, SecretValue.Pair pair) {
        JsonObject facts = pair.facts();
        detail.put("fingerprint", facts.getString("fingerprint"));
        if (facts.containsKey("notAfter")) detail.put("notAfter", facts.getString("notAfter"));
    }

    // ---------------------------------------------------------------------------------------------------------------- checks

    /**
     * {@code raw} as a name the store may write: well formed, and under {@code rail8.} only {@code rail8.git}.
     *
     * @throws SecretRefused 422 {@code invalid}, or 409 {@code reserved}
     */
    static String writable(Object raw) throws SecretRefused {
        String name = name(raw);
        if (name.startsWith(RESERVED) && !name.equals(GIT)) {
            throw new SecretRefused(409, "reserved", "names under " + RESERVED + " belong to the platform: of them, only " + GIT + " may be written",
                    new JsonObject().put("name", name));
        }
        return name;
    }

    /** {@code raw} as a name: {@code [a-z0-9][a-z0-9._-]*}, 1 to {@value #MAX_NAME} characters. */
    static String name(Object raw) throws SecretRefused {
        if (!(raw instanceof String name)) throw SecretRefused.invalid("name", "give the name as a text");
        if (name.length() > MAX_NAME) throw SecretRefused.invalid("name", "a name takes at most " + MAX_NAME + " characters");
        if (!NAME.matcher(name).matches()) throw SecretRefused.invalid("name", "a name is [a-z0-9][a-z0-9._-]*");
        return name;
    }

    /**
     * The value of {@code body}, of its {@code kind}, checked against its limits.
     *
     * @throws SecretRefused 422 {@code invalid}, naming the field and never echoing the value
     */
    static SecretValue value(JsonObject body) throws SecretRefused {
        Object kind = body.getValue("kind");
        if (!(kind instanceof String id)) throw SecretRefused.invalid("kind", "give the kind: value, credentials or key-pair");
        return switch (id) {
            case "value" -> {
                String value = text(body, "value");
                if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                    throw SecretRefused.invalid("value", "a value takes at most " + MAX_VALUE_BYTES + " bytes; a larger one stays in a variable");
                }
                yield new SecretValue.Plain(value);
            }
            case "credentials" -> {
                String user = text(body, "user");
                String password = text(body, "password");
                if (user.length() > MAX_USER) throw SecretRefused.invalid("user", "a user takes at most " + MAX_USER + " characters");
                if (password.length() > MAX_PASSWORD) throw SecretRefused.invalid("password", "a password takes at most " + MAX_PASSWORD + " characters");
                yield new SecretValue.Login(new Secret.Credentials(user, password));
            }
            case "key-pair" -> {
                String privateKey = text(body, "privateKey");
                Object raw = body.getValue("certificates");
                List<String> certificates = new ArrayList<>();
                if (raw instanceof String pem) {
                    certificates.add(pem);
                } else if (raw instanceof JsonArray list) {
                    for (Object c : list) {
                        if (!(c instanceof String pem)) throw SecretRefused.invalid("certificates", "give each certificate as a PEM text");
                        certificates.add(pem);
                    }
                } else if (raw != null) {
                    throw SecretRefused.invalid("certificates", "give the certificates as PEM texts, the leaf first");
                }
                yield KeyPairs.parse(privateKey, certificates);
            }
            default -> throw SecretRefused.invalid("kind", "the kind is value, credentials or key-pair");
        };
    }

    private static String text(JsonObject body, String field) throws SecretRefused {
        Object raw = body.getValue(field);
        if (!(raw instanceof String text) || text.isEmpty()) throw SecretRefused.invalid(field, "give it as a text of at least one character");
        return text;
    }

    // ---------------------------------------------------------------------------------------------------------------- the list

    /**
     * The answer of {@code GET /admin/secrets}: every secret, the missing names, the variables that hold a value no stored secret
     * takes, the key's state. A node's variables and keys count only while its join is its current one.
     */
    public Future<JsonObject> list() {
        return rows.listing().map(this::listed);
    }

    /** The entry of {@code name}: a stored secret's, or a name a use or a variable names; empty when none does. */
    public Future<Optional<JsonObject>> entry(String name) {
        return list().map(all -> {
            for (String part : List.of("secrets", "missing")) {
                for (Object o : all.getJsonArray(part)) {
                    JsonObject e = (JsonObject) o;
                    if (name.equals(e.getString("name"))) return Optional.of(e.put("node", node));
                }
            }
            String variable = variable(name);
            for (Object o : all.getJsonArray("variablesOnly")) {
                JsonObject e = (JsonObject) o;
                if (!name.startsWith(RESERVED) && variable.equals(e.getString("variable"))) {
                    return Optional.of(new JsonObject().put("name", name).put("stored", false).put("variable", variable)
                            .put("variableOn", e.getJsonArray("nodes")).put("node", node));
                }
            }
            return Optional.<JsonObject>empty();
        });
    }

    private JsonObject listed(SecretRows.Listing l) {
        Map<String, List<SecretRows.Use>> uses = new TreeMap<>();
        l.uses().forEach(u -> uses.computeIfAbsent(u.name(), n -> new ArrayList<>()).add(u));
        Map<String, List<String>> variableNodes = new TreeMap<>();
        l.nodes().stream().filter(SecretRows.NodeKeys::joined)
                .forEach(n -> n.variables().forEach(v -> variableNodes.computeIfAbsent(v, x -> new ArrayList<>()).add(n.node())));
        State held = state;
        Set<String> stored = new HashSet<>();
        Set<String> storedVariables = new HashSet<>();

        JsonArray secrets = new JsonArray();
        for (SecretRows.Meta m : l.rows().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList()) {
            stored.add(m.name());
            storedVariables.add(variable(m.name()));
            Opened o = held.held().get(m.name());
            boolean readable = o != null && o.value() != null && Arrays.equals(o.nonce(), m.nonce());
            JsonObject e = new JsonObject().put("name", m.name()).put("kind", m.kind().id()).put("scope", SCOPE).put("stored", true)
                    .put("changedAt", m.changedAt().toString()).put("changedBy", m.changedBy())
                    .put("usedBy", users(uses.get(m.name()))).put("users", uses.getOrDefault(m.name(), List.of()).size())
                    .put("shadowedOn", new JsonArray(m.name().startsWith(RESERVED) ? List.of() : variableNodes.getOrDefault(variable(m.name()), List.of())))
                    .put("unreadable", !readable).put("keyId", HEX.formatHex(m.keyId()));
            if (readable && o.value() instanceof SecretValue.Pair pair) e.put("keyPair", pair.facts());
            secrets.add(e);
        }
        JsonArray missing = new JsonArray();
        uses.forEach((name, list) -> {
            if (stored.contains(name)) return;
            List<String> on = name.startsWith(RESERVED) ? List.of() : variableNodes.getOrDefault(variable(name), List.of());
            boolean value = list.stream().anyMatch(u -> u.kinds().contains(Secret.Kind.VALUE));
            if (value && !on.isEmpty()) return;
            missing.add(new JsonObject().put("name", name).put("stored", false).put("usedBy", users(list)).put("users", list.size()));
        });
        JsonArray variablesOnly = new JsonArray();
        variableNodes.forEach((variable, on) -> {
            if (storedVariables.contains(variable)) return;
            variablesOnly.add(new JsonObject().put("variable", variable).put("nodes", new JsonArray(on)));
        });
        StoreKey k = key;
        byte[] mine = k.current().map(Seal::keyId).orElse(null);
        JsonArray nodes = new JsonArray();
        Set<String> currents = new TreeSet<>();
        boolean previous = false;
        for (SecretRows.NodeKeys n : l.nodes()) {
            String current = n.joined() && n.key() != null ? HEX.formatHex(n.key()) : null;
            String before = n.joined() && n.previous() != null ? HEX.formatHex(n.previous()) : null;
            currents.add(String.valueOf(current));
            previous |= before != null;
            nodes.add(new JsonObject().put("node", n.node()).put("joined", n.joined()).put("key", current).put("previous", before));
        }
        String common = currents.size() == 1 && !currents.contains("null") ? currents.iterator().next() : mine == null ? null : HEX.formatHex(mine);
        long other = common == null ? l.rows().size() : l.rows().stream().filter(m -> !HEX.formatHex(m.keyId()).equals(common)).count();
        JsonObject keyState = new JsonObject().put("nodes", nodes).put("common", currents.size() == 1 && !currents.contains("null") ? common : null)
                .put("rowsUnderAnotherKey", other).put("previousSet", previous);
        return new JsonObject().put("secrets", secrets).put("missing", missing).put("variablesOnly", variablesOnly).put("key", keyState).put("node", node);
    }

    /** The first {@value #MAX_USERS_NAMED} of {@code uses}, in the order of their kind and id. */
    private static JsonArray users(List<SecretRows.Use> uses) {
        JsonArray out = new JsonArray();
        if (uses != null) uses.stream().sorted((a, b) -> (a.userKind() + "/" + a.userId()).compareTo(b.userKind() + "/" + b.userId()))
                .limit(MAX_USERS_NAMED).forEach(u -> out.add(user(u)));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- uses

    /**
     * Replaces the uses of each user of {@code users} with its uses, in the caller's transaction {@code tx}; a user with none has
     * its uses deleted. Called at most once per transaction, with every user it changes: it takes the transaction lock of the
     * union of the names those users use now and used before, each lock key once, in ascending order of the key, then checks each
     * name against the rows in Postgres: set in the store, or, for a use that takes {@code value}, held by this node's variables,
     * and of a kind the use takes. With a problem it writes nothing.
     *
     * @return one text per problem, naming the user, the name and its source, never a value; empty when it wrote the uses
     */
    public Future<List<String>> replaceUses(SqlConnection tx, List<Uses> users) {
        return PgSecretRows.replaceUses(tx, users, Set.copyOf(variables));
    }

    /**
     * Replaces the uses of every flow of package {@code pkg}, by flow the names it uses: those of the flows {@code uses} names
     * with theirs, those of the package's other flows with none. {@code draining} names, by qualified id, every flow of the
     * package marked to drain now: one that has no {@code drain} rows takes its {@code flow} rows as its {@code drain} rows; a
     * flow of the package with {@code drain} rows that is not in it, revived or done, has them deleted. One call of
     * {@link #replaceUses}'s rules over all its users.
     */
    public Future<List<String>> replacePackageUses(SqlConnection tx, String pkg, Map<String, Map<String, Set<Secret.Kind>>> uses, Set<String> draining) {
        return PgSecretRows.packageUses(tx, pkg).compose(before -> {
            Map<String, Map<String, Set<Secret.Kind>>> flows = before.get("flow");
            Map<String, Map<String, Set<Secret.Kind>>> drains = before.get("drain");
            Map<String, Map<String, Set<Secret.Kind>>> all = new TreeMap<>();
            flows.keySet().forEach(id -> all.put(id, Map.of()));
            uses.forEach((flow, names) -> all.put(pkg + "/" + flow, names));
            List<Uses> users = new ArrayList<>();
            all.forEach((id, names) -> users.add(new Uses("flow", id, names)));
            for (String id : new TreeSet<>(draining)) {
                if (!drains.containsKey(id) && flows.containsKey(id)) users.add(new Uses("drain", id, flows.get(id)));
            }
            drains.keySet().stream().filter(id -> !draining.contains(id)).forEach(id -> users.add(new Uses("drain", id, Map.of())));
            return replaceUses(tx, users);
        });
    }

    /** Deletes the {@code drain} rows of {@code flow}, by qualified id, in {@code tx}: its mark has ended. */
    public Future<Void> endDrain(SqlConnection tx, String flow) {
        return replaceUses(tx, List.of(new Uses("drain", flow, Map.of()))).mapEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------- the key

    /** One tick's work on the key: seal again, bind again, and drop the previous key once nothing needs it. */
    private Future<Void> maintain() {
        StoreKey k = key;
        if (!k.present()) return Future.succeededFuture();
        byte[] current = Seal.keyId(k.current().orElseThrow());
        byte[] previous = k.previous().map(Seal::keyId).orElse(null);
        return rows.liveNodes().compose(nodes -> {
            if (!nodes.stream().allMatch(n -> n.names(current))) return Future.succeededFuture();   // the roll has not ended
            return rows.<long[]>write(List.of(), tx -> tx.liveNodes().compose(live -> {
                if (!live.stream().allMatch(n -> n.names(current))) return Future.succeededFuture(new long[]{0, 1});
                Future<long[]> chain = Future.succeededFuture(new long[]{0, 0});
                for (BoundRows b : bound) {
                    chain = chain.compose(sum -> b.bindAgain(tx.connection(), BATCH).compose(n -> boundUnder(tx, b, previous)
                            .map(left -> new long[]{sum[0] + n, sum[1] + left})));
                }
                return chain;
            })).compose(b -> sealAgain(k, current, b[0] > 0, b[1]).compose(remaining -> {
                if (remaining != 0 || b[1] != 0 || previous == null) return Future.succeededFuture();
                return dropPrevious(k, current, previous);
            }));
        });
    }

    private static Future<Long> boundUnder(SecretRows.Tx tx, BoundRows b, byte[] keyId) {
        return keyId == null ? Future.succeededFuture(0L) : b.countUnder(tx.connection(), keyId);
    }

    /** Seals again up to {@value #BATCH} rows under another key, in one transaction; answers the rows left under another key. */
    private Future<Integer> sealAgain(StoreKey k, byte[] current, boolean rebound, long boundLeft) {
        byte[] currentKey = k.current().orElseThrow();
        Future<int[]> done = rows.write(List.of(), tx -> tx.liveNodes().compose(nodes -> {
            if (!nodes.stream().allMatch(n -> n.names(current))) return Future.succeededFuture(new int[]{-1, 0});
            return tx.rows().compose(all -> {
                List<SecretRows.Meta> others = all.stream().filter(m -> !Arrays.equals(m.keyId(), current))
                        .sorted((a, b) -> a.name().compareTo(b.name())).toList();
                List<byte[]> from = new ArrayList<>();
                Future<Integer> chain = Future.succeededFuture(0);
                for (SecretRows.Meta m : others.subList(0, Math.min(BATCH, others.size()))) {
                    chain = chain.compose(n -> tx.row(m.name()).compose(row -> {
                        byte[] under = keyFor(k, m.keyId());
                        if (row.isEmpty() || under == null) return Future.succeededFuture(n);
                        byte[] plain;
                        try {
                            plain = Seal.open(under, m.format(), SCOPE, m.name(), m.kind(), new Seal.Sealed(m.keyId(), m.nonce(), row.get().sealed()));
                        } catch (GeneralSecurityException unreadable) {
                            return Future.succeededFuture(n);   // an unreadable row is not sealed again: it is deleted, and entered again
                        }
                        Seal.Sealed sealed = Seal.seal(currentKey, SCOPE, m.name(), m.kind(), plain);
                        from.add(m.keyId());
                        return tx.upsert(new SecretRows.Stored(new SecretRows.Meta(m.name(), m.kind(), Seal.FORMAT, sealed.keyId(), sealed.nonce(),
                                sealed.sealed().length, m.createdAt(), m.changedAt(), m.changedBy()), sealed.sealed())).map(v -> n + 1);
                    }));
                }
                return chain.compose(sealedAgain -> {
                    int remaining = others.size() - sealedAgain;
                    Future<Void> audited = Future.succeededFuture();
                    if ((sealedAgain > 0 || rebound) && remaining == 0 && boundLeft == 0) {
                        String old = HEX.formatHex(from.isEmpty() ? k.previous().map(Seal::keyId).orElse(current) : from.getFirst());
                        audited = tx.audit(new Actor(node, OptionalLong.empty(), "local", Optional.empty(), ""), "secret.key-changed", "secret-store",
                                new JsonObject().put("oldKey", old).put("newKey", HEX.formatHex(current)).put("rows", all.size()));
                    }
                    int[] result = {remaining, sealedAgain};
                    if (sealedAgain == 0) return audited.map(result);
                    return audited.compose(v -> tx.changed()).map(result);
                });
            });
        }));
        return done.compose(result -> result[1] == 0 ? Future.succeededFuture(result[0]) : look().map(result[0]));
    }

    /** Clears this node's previous key, in one transaction under the store's row lock, once no live node or row needs it. */
    private Future<Void> dropPrevious(StoreKey k, byte[] current, byte[] previous) {
        return rows.<Boolean>write(List.of(), tx -> tx.liveNodes().compose(nodes -> {
            if (!nodes.stream().allMatch(n -> n.names(current))) return Future.succeededFuture(false);
            return tx.rows().compose(all -> {
                if (all.stream().anyMatch(m -> Arrays.equals(m.keyId(), previous))) return Future.succeededFuture(false);
                Future<Long> left = Future.succeededFuture(0L);
                for (BoundRows b : bound) left = left.compose(sum -> b.countUnder(tx.connection(), previous).map(n -> sum + n));
                return left.compose(n -> n != 0 ? Future.succeededFuture(false) : tx.clearPrevious(node).map(true));
            });
        })).map(cleared -> {
            if (cleared && key == k) {
                key = k.withoutPrevious();
                previousSet = true;
                warn.accept("the secret store's previous key " + HEX.formatHex(previous) + " is no longer needed and no longer held: remove "
                        + StoreKey.PREVIOUS + " from this node's environment");
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------- binding

    /** Registers a table whose rows are bound under the store's key. */
    public void register(BoundRows rows) {
        bound.add(Objects.requireNonNull(rows, "rows"));
    }

    /**
     * {@code bytes} bound under this node's current key, in the caller's transaction {@code tx}: HMAC-SHA256 under HKDF-SHA256 of
     * the key, info {@code rail8 settings v1 bind}. It takes the store's row lock for share and makes a write's key check, so a
     * change of the key waits for {@code tx}, and a tag is persisted only in the transaction that made it.
     *
     * @return fails with a {@link SecretRefused}: 503 {@code no-key}, 409 {@code keys-differ}
     */
    public Future<Binding> bind(SqlConnection tx, byte[] bytes) {
        StoreKey k = key;
        return rows.sharedNodes(tx).compose(nodes -> keyCheck(nodes, k)).map(v -> {
            byte[] current = k.current().orElseThrow();
            return new Binding(Seal.keyId(current), tag(current, bytes));
        });
    }

    /** Whether {@code binding} binds {@code bytes} under the key its id names, current or previous. */
    public boolean verify(Binding binding, byte[] bytes) {
        byte[] under = keyFor(key, binding.keyId());
        return under != null && MessageDigest.isEqual(tag(under, bytes), binding.tag());
    }

    private static byte[] tag(byte[] key, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Seal.hkdf(key, BIND_INFO, 32), "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is not available", failure);
        }
    }

    /** The node and the key's ids, never a value. */
    @Override
    public String toString() {
        return "SecretStore[" + node + ", " + key + ", " + state.held().size() + " held]";
    }
}
