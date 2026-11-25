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
import java.nio.ByteBuffer;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The node's secret store: the secrets sealed in Postgres, opened and held in the node's memory, and the writes, the list and
 * the record of uses.
 *
 * <p><b>Reading.</b> A read ({@link #held}) never goes to Postgres: it answers from what the node holds. The node holds each row
 * with the nonce it opened. It looks at the store's generation and stamp at each {@link #notice()}, after each {@code LISTEN}
 * acknowledged, and at each tick of {@value #TICK_SECONDS} s on the injected timer; when they differ from what it holds, it reads
 * every row's name, kind, key id and nonce in one repeatable-read transaction, opens the rows whose nonce it does not hold and
 * drops the names that are gone. A row that does not open, or that is under a key the node does not hold, is held unreadable:
 * it gives no value, it never falls back to a variable, it makes {@link #health()} {@code DEGRADED}, and one WARNING names its
 * name and key id. A generation behind the one held, or the same generation with another stamp, is a WARNING: the database went
 * back to an earlier state.
 *
 * <p><b>Writing.</b> {@link #create}, {@link #overwrite} and {@link #delete} each check the body, the name and the value outside
 * any transaction, then in one transaction: the name's lock (a create or a delete), the store's row lock, the key check, the
 * row checks, the seal and the upsert or the delete, the audit row, the generation and stamp, and the notice. After the commit
 * this node looks again, so a write is in force here when it is answered. A refusal is a {@link SecretRefused}.
 *
 * <p><b>The key.</b> A node seals under its own current key only. A write is 409 {@code keys-differ} unless every live node
 * holds that key, current or previous, by its current join. Once every live node names the same current key, each tick seals
 * again up to {@value #BATCH} rows under another key and asks each {@link BoundRows} to bind again; the node that seals the last
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

    /** A table whose rows are bound under the store's key: the key is kept while any of them is under it. */
    public interface BoundRows {
        /** The rows bound under the key {@code keyId}. */
        long countUnder(byte[] keyId);

        /** Binds again under the current key up to {@code max} rows bound under another; answers how many. */
        int bindAgain(int max);
    }

    /** A row as the node opened it: its kind, key id, nonce and value, null when unreadable. */
    private record Opened(Secret.Kind kind, byte[] keyId, byte[] nonce, SecretValue value) { }

    private record State(SecretRows.Head head, Map<String, Opened> held) { }

    private final SecretRows rows;
    private final String node;
    private final List<String> variables;
    private final Clock clock;
    private final Timer timer;
    private final Executor executor;
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

    private final Object lock = new Object();
    private boolean looking;               // guarded by lock: a look runs
    private Promise<Void> queued;          // guarded by lock: the look that runs after it, null: none
    private Timer.Cancellable tick;        // guarded by lock
    private boolean closed;                // guarded by lock

    SecretStore(SecretRows rows, StoreKey key, String node, Collection<String> variables, Clock clock, Timer timer, Executor executor,
                Consumer<String> warn) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.key = Objects.requireNonNull(key, "key");
        this.node = Objects.requireNonNull(node, "node");
        this.variables = List.copyOf(new TreeSet<>(variables));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Node {@code node}'s store over Postgres's {@code pool}, under {@code key}, its {@code RAIL8_SECRET_*} variables named by
     * {@code variables} (names only), its ticks on {@code timer}; the configuration's bound rows are asked on virtual threads.
     * Reads nothing before {@link #start}.
     */
    public static SecretStore postgres(Pool pool, StoreKey key, String node, Collection<String> variables, Clock clock, Timer timer) {
        return new SecretStore(new PgSecretRows(pool), key, node, variables, clock, timer,
                command -> Thread.ofVirtual().name("rail8-secrets").start(command), text -> LOG.log(System.Logger.Level.WARNING, text));
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
            if (done.failed()) LOG.log(System.Logger.Level.WARNING, "the secret store's tick failed and is tried again at the next: " + done.cause());
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
            if (done.failed()) LOG.log(System.Logger.Level.WARNING, "the secret store could not be read, the node holds what it read before: " + done.cause());
            next.handle(done);
            drain();
        });
    }

    private Future<Void> once() {
        return rows.head().compose(head -> {
            State held = state;
            if (head.equals(held.head())) return Future.succeededFuture();
            Set<ByteBuffer> nonces = held.held().values().stream().map(o -> ByteBuffer.wrap(o.nonce())).collect(Collectors.toSet());
            return rows.snapshot(nonces).map(snapshot -> {
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
            if (old != null && old.kind() == m.kind() && Arrays.equals(old.nonce(), m.nonce()) && Arrays.equals(old.keyId(), m.keyId())) {
                next.put(m.name(), old);
                continue;
            }
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
        try {
            if (sealed == null) throw new GeneralSecurityException("no sealed value was read");
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

    /** {@code DEGRADED} while a row is unreadable, or while the previous key's variable is set though no row needs it. */
    public Health health() {
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
        })).compose(v -> look()).compose(v -> entry(name)).map(Optional::orElseThrow);
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
        })).compose(v -> look()).compose(v -> entry(name)).map(Optional::orElseThrow);
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
                JsonArray usedBy = new JsonArray();
                uses.forEach(u -> usedBy.add(user(u)));
                if ((!uses.isEmpty() || !unknown.isEmpty()) && !confirmed) {
                    return Future.failedFuture(new SecretRefused(409, "in-use", "'" + name + "' is used"
                            + (uses.isEmpty() ? "" : " by " + uses.getFirst().userKind() + " '" + uses.getFirst().userId() + "'")
                            + (unknown.isEmpty() ? "" : (uses.isEmpty() ? " perhaps" : ", and perhaps") + " by the package '" + unknown.getFirst() + "'")
                            + ": delete it with ?confirm=" + name, new JsonObject().put("name", name).put("usedBy", usedBy).put("mayUse", new JsonArray(unknown))));
                }
                JsonObject detail = new JsonObject().put("kind", row.get().meta().kind().id());
                Opened held = state.held().get(name);
                if (held != null && held.value() instanceof SecretValue.Pair pair) facts(detail, pair);
                detail.put("confirmed", confirmed).put("users", usedBy);
                return tx.delete(name).compose(v -> audit(tx, who, "secret.delete", name, detail)).compose(v -> tx.changed());
            }));
        })).compose(v -> look());
    }

    /** Refuses a write unless this node holds a key and every live node holds it, by its current join. */
    private Future<Void> keyCheck(SecretRows.Tx tx, StoreKey k) {
        if (!k.present()) {
            return Future.failedFuture(new SecretRefused(503, "no-key", "this node holds no key of the secret store: start it with " + StoreKey.VARIABLE));
        }
        byte[] id = Seal.keyId(k.current().orElseThrow());
        return tx.liveNodes().compose(nodes -> {
            List<String> lacking = nodes.stream().filter(n -> !n.holds(id)).map(SecretRows.NodeKeys::node).sorted().toList();
            if (lacking.isEmpty()) return Future.succeededFuture();
            return Future.failedFuture(new SecretRefused(409, "keys-differ", "the nodes " + lacking + " do not hold the key " + HEX.formatHex(id)
                    + " this node seals under: try again once the roll of the key has ended", new JsonObject().put("key", HEX.formatHex(id))
                    .put("nodes", new JsonArray(lacking))));
        });
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

    /** The answer of {@code GET /admin/secrets}: every secret, the missing names, the names only variables hold, the key's state. */
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
        l.nodes().forEach(n -> n.variables().forEach(v -> variableNodes.computeIfAbsent(v, x -> new ArrayList<>()).add(n.node())));
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
                    .put("usedBy", users(uses.get(m.name())))
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
            missing.add(new JsonObject().put("name", name).put("stored", false).put("usedBy", users(list)));
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
            nodes.add(new JsonObject().put("node", n.node()).put("key", current).put("previous", before));
        }
        String common = currents.size() == 1 && !currents.contains("null") ? currents.iterator().next() : mine == null ? null : HEX.formatHex(mine);
        long other = common == null ? l.rows().size() : l.rows().stream().filter(m -> !HEX.formatHex(m.keyId()).equals(common)).count();
        JsonObject keyState = new JsonObject().put("nodes", nodes).put("common", currents.size() == 1 && !currents.contains("null") ? common : null)
                .put("rowsUnderAnotherKey", other).put("previousSet", previous);
        return new JsonObject().put("secrets", secrets).put("missing", missing).put("variablesOnly", variablesOnly).put("key", keyState).put("node", node);
    }

    private static JsonArray users(List<SecretRows.Use> uses) {
        JsonArray out = new JsonArray();
        if (uses != null) uses.stream().sorted((a, b) -> (a.userKind() + "/" + a.userId()).compareTo(b.userKind() + "/" + b.userId()))
                .forEach(u -> out.add(user(u)));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------- uses

    /**
     * Replaces the uses of the user {@code (userKind, userId)} with {@code uses}, by name the kinds each use takes, in the caller's
     * transaction {@code tx}; an empty map deletes them. It takes the transaction lock of every name it writes and of every name
     * the user used before, in ascending order of the lock's key, then checks each name against the rows in Postgres: set in the
     * store, or, for a use that takes {@code value}, held by this node's variables, and of a kind the use takes. With a problem
     * it writes nothing.
     *
     * @return one text per problem, naming the user, the name and its source, never a value; empty when it wrote the uses
     */
    public Future<List<String>> replaceUses(SqlConnection tx, String userKind, String userId, Map<String, Set<Secret.Kind>> uses) {
        return PgSecretRows.replaceUses(tx, List.of(new PgSecretRows.UserUses(userKind, userId, uses)), Set.copyOf(variables));
    }

    /**
     * Replaces the uses of every flow of package {@code pkg}, by flow the names it uses: those of the flows {@code uses} names
     * with theirs, those of the package's other flows with none. One call of {@link #replaceUses}'s rules over all its users.
     */
    public Future<List<String>> replacePackageUses(SqlConnection tx, String pkg, Map<String, Map<String, Set<Secret.Kind>>> uses) {
        return PgSecretRows.packageUsers(tx, pkg).compose(before -> {
            Map<String, Map<String, Set<Secret.Kind>>> all = new TreeMap<>();
            before.forEach(flow -> all.put(flow, Map.of()));
            all.putAll(uses);
            List<PgSecretRows.UserUses> users = new ArrayList<>();
            all.forEach((flow, names) -> users.add(new PgSecretRows.UserUses("flow", pkg + "/" + flow, names)));
            return PgSecretRows.replaceUses(tx, users, Set.copyOf(variables));
        });
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
            return blocking(() -> {
                long rebound = 0;
                long left = 0;
                for (BoundRows b : bound) {
                    rebound += b.bindAgain(BATCH);
                    if (previous != null) left += b.countUnder(previous);
                }
                return new long[]{rebound, left};
            }).compose(b -> sealAgain(k, current, b[0] > 0, b[1]).compose(remaining -> {
                if (remaining != 0 || b[1] != 0 || previous == null) return Future.succeededFuture();
                return dropPrevious(k, current, previous);
            }));
        });
    }

    /** Seals again up to {@value #BATCH} rows under another key, in one transaction; answers the rows left under another key. */
    private Future<Integer> sealAgain(StoreKey k, byte[] current, boolean rebound, long boundLeft) {
        byte[] currentKey = k.current().orElseThrow();
        Future<Integer> done = rows.write(List.of(), tx -> tx.liveNodes().compose(nodes -> {
            if (!nodes.stream().allMatch(n -> n.names(current))) return Future.succeededFuture(-1);
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
                    if (sealedAgain == 0) return audited.map(remaining);
                    return audited.compose(v -> tx.changed()).map(remaining);
                });
            });
        }));
        return done.compose(remaining -> look().map(remaining));
    }

    /** Clears this node's previous key, in one transaction under the store's row lock, once no live node or row needs it. */
    private Future<Void> dropPrevious(StoreKey k, byte[] current, byte[] previous) {
        return rows.<Boolean>write(List.of(), tx -> tx.liveNodes().compose(nodes -> {
            if (!nodes.stream().allMatch(n -> n.names(current))) return Future.succeededFuture(false);
            return tx.rows().compose(all -> {
                if (all.stream().anyMatch(m -> Arrays.equals(m.keyId(), previous))) return Future.succeededFuture(false);
                return tx.clearPrevious(node).map(true);
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

    private <T> Future<T> blocking(Supplier<T> work) {
        Promise<T> promise = Promise.promise();
        executor.execute(() -> {
            try {
                promise.complete(work.get());
            } catch (RuntimeException failure) {
                promise.fail(failure);
            }
        });
        return promise.future();
    }

    // ---------------------------------------------------------------------------------------------------------------- binding

    /** Registers a table whose rows are bound under the store's key. */
    public void register(BoundRows rows) {
        bound.add(Objects.requireNonNull(rows, "rows"));
    }

    /**
     * {@code bytes} bound under this node's current key: HMAC-SHA256 under HKDF-SHA256 of the key, info {@code rail8 settings v1 bind}.
     *
     * @throws IllegalStateException this node holds no key
     */
    public Binding bind(byte[] bytes) {
        byte[] current = key.current().orElseThrow(() -> new IllegalStateException("this node holds no key of the secret store: nothing can be bound"));
        return new Binding(Seal.keyId(current), tag(current, bytes));
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
