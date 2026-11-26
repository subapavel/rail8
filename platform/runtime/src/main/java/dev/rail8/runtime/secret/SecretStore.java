package dev.rail8.runtime.secret;

import dev.rail8.runtime.refusal.RefusalCode;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * longer than {@value #MAX_ROW_BYTES} bytes is held unreadable: it gives no value, it makes
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
 * <p><b>The import.</b> The store is the only source of a secret. The join of a store of generation 0, one that was never
 * written, imports the node's variables ({@link Import}) as the creates a secret administrator would make, all or none, and
 * raises the generation, also with nothing imported; after it no variable is read.
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
    /** Who writes an imported secret, as its row and its audit row name it. */
    static final String ENVIRONMENT = "environment";
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
    private final Clock clock;
    private final Timer timer;
    private final Consumer<String> warn;
    private final Consumer<String> info;

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

    SecretStore(SecretRows rows, StoreKey key, String node, Clock clock, Timer timer, Consumer<String> warn, Consumer<String> info) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.key = Objects.requireNonNull(key, "key");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.info = Objects.requireNonNull(info, "info");
    }

    /** Node {@code node}'s store over Postgres's {@code pool}, under {@code key}, its ticks on {@code timer}. Reads nothing before {@link #start}. */
    public static SecretStore postgres(Pool pool, StoreKey key, String node, Clock clock, Timer timer) {
        return new SecretStore(new PgSecretRows(pool, PgSecretRows.LOCK_WAIT), key, node, clock, timer,
                text -> LOG.log(System.Logger.Level.WARNING, text), text -> LOG.log(System.Logger.Level.INFO, text));
    }

    /** Where the placeholder key pair of {@code rail8 validate} and {@code rail8 test} lies on the class path: the testkit's. */
    static final String PLACEHOLDER_PAIR = "/dev/rail8/testkit/invalid.pem";

    /**
     * The placeholder key pair, a fixed EC secp256r1 pair with a self-signed certificate named invalid, read from the testkit on the
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

    // ---------------------------------------------------------------------------------------------------------------- start

    /**
     * The node's variables the join of a store of generation 0 imports, as given: every {@code RAIL8_SECRET_<NAME>},
     * {@code RAIL8_GIT_USER} and {@code RAIL8_GIT_TOKEN}, each also in its file form ({@code _FILE}), set to a non-empty value.
     * A {@code RAIL8_SECRET_<NAME>} is imported as the plain value {@code <NAME>} in lower case with each {@code _} as {@code -};
     * its file form ends in {@code _FILE} below the prefix ({@code RAIL8_SECRET_FILE} is the plain variable of {@code file},
     * {@code RAIL8_SECRET_DB_FILE_FILE} the file form of {@code db-file}), and {@code RAIL8_SECRET_} with an empty name is
     * ignored. The git pair is imported as the credentials {@code rail8.git}. It holds each variable's value or path, reads no
     * file and checks nothing; its {@code toString}, {@code equals} and {@code hashCode} hold the variables' names only.
     */
    public static final class Import {
        private static final String SECRET = VARIABLE;
        private static final String FILE = "_FILE";
        static final String GIT_USER = "RAIL8_GIT_USER";
        static final String GIT_TOKEN = "RAIL8_GIT_TOKEN";

        /** One variable as given: the name it maps to, whether it is a file form, and its value or its path. */
        record Given(String variable, String name, boolean file, String raw) {
            @Override public String toString() {
                return variable;
            }
        }

        private final Map<String, Given> given;   // by variable, sorted

        private Import(Map<String, Given> given) {
            this.given = given;
        }

        /** None: a join with it imports nothing. */
        public static Import none() {
            return new Import(Map.of());
        }

        /** The variables of {@code env} the import reads; every other one is left out. */
        public static Import fromEnv(Map<String, String> env) {
            Map<String, Given> given = new TreeMap<>();
            env.forEach((variable, raw) -> {
                if (raw == null || raw.isEmpty()) return;
                Given g = given(variable, raw);
                if (g != null) given.put(variable, g);
            });
            return new Import(Collections.unmodifiableMap(given));
        }

        private static Given given(String variable, String raw) {
            if (variable.equals(GIT_USER) || variable.equals(GIT_TOKEN)) return new Given(variable, GIT, false, raw);
            if (variable.equals(GIT_USER + FILE) || variable.equals(GIT_TOKEN + FILE)) return new Given(variable, GIT, true, raw);
            if (!variable.startsWith(SECRET)) return null;
            String rest = variable.substring(SECRET.length());
            boolean file = rest.endsWith(FILE) && rest.length() > FILE.length();
            if (file) rest = rest.substring(0, rest.length() - FILE.length());
            if (rest.isEmpty()) return null;
            return new Given(variable, rest.toLowerCase(Locale.ROOT).replace('_', '-'), file, raw);
        }

        /** The variables as given, sorted. */
        public List<String> names() {
            return List.copyOf(given.keySet());
        }

        Collection<Given> given() {
            return given.values();
        }

        /** Whether it holds no variable. */
        boolean isEmpty() {
            return given.isEmpty();
        }

        @Override public boolean equals(Object other) {
            return other instanceof Import i && given.keySet().equals(i.given.keySet());
        }

        @Override public int hashCode() {
            return given.keySet().hashCode();
        }

        /** The variables' names, never a value or a path. */
        @Override public String toString() {
            return "Import" + names();
        }
    }

    /** What a join did: whether it imported, and the names it imported. */
    private record Joined(boolean imported, List<String> names) { }

    /**
     * Joins the store with the epoch of this node's join to the pool, reads every row and arms the tick. The join is one
     * transaction under the store's row lock, and no name's lock: it writes this node's key ids and {@code epoch} while the
     * node's row still holds that epoch and a lease, checks every row's key id, and at generation 0 imports {@code imports}
     * ({@link #imported}) and raises the generation, also when it imports nothing. After the commit it reads every row; then
     * the importing node logs one INFO naming the imported names and the key they are sealed under, and a node that finds a
     * variable of {@code imports} set on a store already written logs one WARNING naming each {@code RAIL8_SECRET_*} variable as
     * given, in two lists: those whose name the store holds, and those whose name it does not hold.
     *
     * @return fails with an {@link IllegalStateException}: the node's row no longer holds {@code epoch}; a row is under a key
     *         this node does not hold (naming the rows' count and key ids); a refusal of the import, naming the variable and the
     *         check, never a value, a file's content or its path
     */
    public Future<Void> start(long epoch, Import imports) {
        Objects.requireNonNull(imports, "imports");
        StoreKey k = key;
        byte[] current = Seal.keyId(k.current());
        byte[] previous = k.previous().map(Seal::keyId).orElse(null);
        return rows.<Joined>write(List.of(), tx -> tx.joined(node, epoch, current, previous).compose(wrote -> {
            if (!wrote) {
                return Future.failedFuture(new IllegalStateException("the node " + node + " no longer holds the epoch " + epoch
                        + " of its join: a newer start of it took its row, so this one does not join the secret store"));
            }
            return tx.rows();
        }).compose(all -> {
            Map<String, Integer> foreign = new TreeMap<>();
            for (SecretRows.Meta m : all) {
                if (!Arrays.equals(m.keyId(), current) && !Arrays.equals(m.keyId(), previous)) foreign.merge(HEX.formatHex(m.keyId()), 1, Integer::sum);
            }
            if (!foreign.isEmpty()) {
                int count = foreign.values().stream().mapToInt(Integer::intValue).sum();
                String under = foreign.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
                return Future.failedFuture(new IllegalStateException(count + " rows of the secret store are sealed under keys this node does not hold: "
                        + under + "; it holds " + k + ": give the key they are under as " + StoreKey.VARIABLE + ", or as " + StoreKey.PREVIOUS
                        + " while the key is changed"));
            }
            if (tx.head().generation() != 0) return Future.succeededFuture(new Joined(false, List.of()));
            return imported(tx, k, all, imports).compose(names -> tx.changed().map(new Joined(true, names)));
        })).compose(joined -> look().map(joined)).onSuccess(joined -> {
            if (joined.imported()) {
                info.accept(joined.names().isEmpty() ? "the secret store was never written: its import of this node's environment is closed with nothing imported"
                        : "the secret store was never written: it imported " + joined.names() + " from this node's environment, sealed under the key "
                          + HEX.formatHex(current));
            } else {
                unread(imports);
            }
            arm();
        }).mapEmpty();
    }

    /**
     * The WARNING of the variables a store already written does not read, in lists: the {@code RAIL8_SECRET_*} variables whose
     * names it holds, those whose names it does not hold, and the git variables, which only {@code rail8.git} replaces.
     */
    private void unread(Import imports) {
        Map<String, Opened> held = state.held();
        List<String> heldNames = new ArrayList<>();
        List<String> notHeld = new ArrayList<>();
        List<String> git = new ArrayList<>();
        for (Import.Given g : imports.given()) {
            if (g.name().equals(GIT)) git.add(g.variable());
            else (held.containsKey(g.name()) ? heldNames : notHeld).add(g.variable());
        }
        List<String> clauses = new ArrayList<>();
        if (!heldNames.isEmpty()) clauses.add(heldNames + " not read: remove the variable");
        if (!notHeld.isEmpty()) clauses.add(notHeld + " not read: enter it in the store, or a flow that names it fails");
        if (!git.isEmpty()) {
            clauses.add(git + " not read: the git cache sends the store's " + GIT + (held.containsKey(GIT) ? "; remove the variables"
                    : ", which is not set: enter it in the store, or no git credentials are sent"));
        }
        if (clauses.isEmpty()) return;
        warn.accept("the secret store is the only source of a secret, and this node's variables are not read: " + String.join("; ", clauses));
    }

    /**
     * The import of {@code imports} into a store of generation 0, in the join's transaction {@code tx}, whose rows are
     * {@code all}: the creates a secret administrator would make, all or none. First every check that needs no file, over the
     * variables in sorted order; then the files, in sorted order, read by {@link SecretFile#read} on a virtual thread of their
     * own; then the creates, in name order, each with the checks of its kind, the store's limits and the uses recorded for its
     * name, sealed under this node's current key, written by {@code environment} with one audit row {@code secret.create}.
     *
     * @return the names imported, sorted; fails with an {@link IllegalStateException} naming the variable as given and the
     *         check, never a value, a file's content or its path
     */
    private Future<List<String>> imported(SecretRows.Tx tx, StoreKey k, List<SecretRows.Meta> all, Import imports) {
        if (imports.isEmpty()) return Future.succeededFuture(List.of());
        Map<String, List<Import.Given>> byName = new TreeMap<>();
        try {
            for (Import.Given g : imports.given()) {   // sorted by variable: the first refusal is always the same
                if (!g.name().equals(GIT)) {
                    if (g.name().length() > MAX_NAME || !NAME.matcher(g.name()).matches()) {
                        throw unimported(g.variable() + " maps to a name that is not a secret's name: [a-z0-9][a-z0-9._-]*, 1 to " + MAX_NAME
                                + " characters (its name takes " + g.name().length() + ")");
                    }
                    if (g.name().startsWith(RESERVED)) throw unimported(g.variable() + " maps to a name under " + RESERVED + ", which belongs to the platform");
                }
                List<Import.Given> same = byName.computeIfAbsent(g.name(), n -> new ArrayList<>());
                if (!same.isEmpty() && !g.name().equals(GIT)) {
                    throw unimported(same.getFirst().variable() + " and " + g.variable() + " both give the secret '" + g.name() + "': set one of them");
                }
                same.add(g);
            }
            List<Import.Given> git = byName.get(GIT);
            if (git != null) {
                Set<String> users = new TreeSet<>();
                Set<String> tokens = new TreeSet<>();
                for (Import.Given g : git) (g.variable().startsWith(Import.GIT_USER) ? users : tokens).add(g.variable());
                if (users.size() > 1 || tokens.size() > 1) {
                    List<String> both = users.size() > 1 ? List.copyOf(users) : List.copyOf(tokens);
                    throw unimported(both.get(0) + " and " + both.get(1) + " both give the secret '" + GIT + "': set one of them");
                }
                if (users.isEmpty() || tokens.isEmpty()) {
                    throw unimported((users.isEmpty() ? tokens : users).iterator().next() + " is set without " + (users.isEmpty() ? Import.GIT_USER : Import.GIT_TOKEN)
                            + ": the git credentials are imported as " + GIT + " from both or neither");
                }
            }
            if (all.size() + byName.size() > MAX_SECRETS) {
                throw unimported("the variables give " + byName.size() + " secrets, more than the store's " + MAX_SECRETS + " secrets");
            }
        } catch (IllegalStateException refused) {
            return Future.failedFuture(refused);
        }
        Promise<Map<String, String>> files = Promise.promise();
        Thread.ofVirtual().name("rail8-secret-import").start(() -> {
            try {
                Map<String, String> read = new HashMap<>();
                for (Import.Given g : imports.given()) {
                    if (!g.file()) continue;
                    try {
                        read.put(g.variable(), SecretFile.read(g.variable(), g.raw()));
                    } catch (SecretFile.Unread unread) {
                        throw unimported(g.variable() + " " + unread.check());
                    }
                }
                files.complete(read);
            } catch (RuntimeException refused) {
                files.fail(refused);
            }
        });
        return files.future().compose(read -> {
            Map<String, String> values = new HashMap<>();
            for (Import.Given g : imports.given()) values.put(g.variable(), g.file() ? read.get(g.variable()) : g.raw());
            Future<List<String>> chain = Future.succeededFuture(new ArrayList<>());
            long[] total = {0};
            for (SecretRows.Meta m : all) total[0] += m.sealedBytes();
            for (Map.Entry<String, List<Import.Given>> each : byName.entrySet()) {
                String name = each.getKey();
                List<Import.Given> given = each.getValue();
                chain = chain.compose(done -> importOne(tx, k, name, given, values, total).map(v -> {
                    done.add(name);
                    return done;
                }));
            }
            return chain.<List<String>>map(List::copyOf);
        }).recover(failure -> Future.failedFuture(failure instanceof IllegalStateException refused && refused.getMessage().startsWith(IMPORT) ? refused
                : unimported("the database refused the import's write (" + failure.getClass().getSimpleName() + ")")));
    }

    /** One create of the import: {@code name} from the variables {@code given}, whose values {@code values} holds by variable. */
    private Future<Void> importOne(SecretRows.Tx tx, StoreKey k, String name, List<Import.Given> given, Map<String, String> values, long[] total) {
        SecretValue value;
        List<String> variables = given.stream().map(Import.Given::variable).sorted().toList();
        if (name.equals(GIT)) {
            String userVariable = variables.stream().filter(v -> v.startsWith(Import.GIT_USER)).findFirst().orElseThrow();
            String tokenVariable = variables.stream().filter(v -> v.startsWith(Import.GIT_TOKEN)).findFirst().orElseThrow();
            String user = values.get(userVariable);
            String token = values.get(tokenVariable);
            if (user.isBlank()) return Future.failedFuture(unimported(userVariable + " is blank: a user of " + GIT + " is not blank"));
            if (token.isBlank()) return Future.failedFuture(unimported(tokenVariable + " is blank: a token of " + GIT + " is not blank"));
            if (user.length() > MAX_USER) {
                return Future.failedFuture(unimported(userVariable + " takes " + user.length() + " characters, more than a user's " + MAX_USER));
            }
            if (token.length() > MAX_PASSWORD) {
                return Future.failedFuture(unimported(tokenVariable + " takes " + token.length() + " characters, more than a password's " + MAX_PASSWORD));
            }
            value = new SecretValue.Login(new Secret.Credentials(user, token));
        } else {
            String plain = values.get(variables.getFirst());
            int bytes = plain.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                return Future.failedFuture(unimported(variables.getFirst() + " takes " + bytes + " bytes, more than a value's " + MAX_VALUE_BYTES));
            }
            value = new SecretValue.Plain(plain);
        }
        String first = variables.getFirst();
        return tx.uses(name).compose(uses -> {
            for (SecretRows.Use use : uses) {
                if (!use.kinds().contains(value.kind())) {
                    return Future.failedFuture(unimported(first + " gives the secret '" + name + "' of kind " + value.kind().id() + ", but " + use.userKind()
                            + " '" + use.userId() + "' uses it as " + ids(use.kinds())));
                }
            }
            Seal.Sealed sealed = Seal.seal(k.current(), SCOPE, name, value.kind(), value.plain());
            total[0] += sealed.sealed().length;
            if (total[0] > MAX_SEALED_BYTES) {
                return Future.failedFuture(unimported(first + ": the sealed values would take more than " + MAX_SEALED_BYTES + " bytes, the store's most"));
            }
            Instant now = clock.instant();
            JsonObject detail = detail(value).put("variables", new JsonArray(variables)).put("node", node);
            return tx.upsert(new SecretRows.Stored(new SecretRows.Meta(name, value.kind(), Seal.FORMAT, sealed.keyId(), sealed.nonce(), sealed.sealed().length,
                    now, now, ENVIRONMENT), sealed.sealed())).compose(v -> tx.auditLocal(ENVIRONMENT, "secret.create", "secret/" + name, detail));
        });
    }

    /** What every refusal of the import starts with. */
    private static final String IMPORT = "the import of this node's environment into the secret store is refused: ";

    private static IllegalStateException unimported(String text) {
        return new IllegalStateException(IMPORT + text);
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
        byte[] current = k.current();
        if (Arrays.equals(Seal.keyId(current), keyId)) return current;
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
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code reserved}, {@code keys-differ},
     *         {@code exists}, {@code kind-in-use}, {@code store-full}, {@code unaudited}
     */
    public Future<JsonObject> create(Actor who, JsonObject body) {
        String name;
        SecretValue value;
        try {
            name = writable(body.getValue("name"));
            value = value(name, body);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        StoreKey k = key;
        return rows.<SecretRows.Head>write(List.of(name), tx -> keyCheck(tx, k).compose(v -> tx.rows()).compose(all -> {
            if (all.stream().anyMatch(m -> m.name().equals(name))) {
                return refuse(409, RefusalCode.EXISTS, "the secret '" + name + "' exists: overwrite it, or delete it first", name);
            }
            if (all.size() >= MAX_SECRETS) return refuse(409, RefusalCode.STORE_FULL, "the store holds " + MAX_SECRETS + " secrets, its most", name);
            return tx.uses(name).compose(uses -> {
                for (SecretRows.Use use : uses) {
                    if (!use.kinds().contains(value.kind())) {
                        return Future.failedFuture(new SecretRefused(409, RefusalCode.KIND_IN_USE, use.userKind() + " '" + use.userId() + "' uses '" + name
                                + "' as " + ids(use.kinds()) + ", not as " + value.kind().id(), new JsonObject().put("name", name)
                                .put("kind", value.kind().id()).put("user", user(use))));
                    }
                }
                Seal.Sealed sealed = Seal.seal(k.current(), SCOPE, name, value.kind(), value.plain());
                Instant now = clock.instant();
                return full(all, null, sealed).compose(v -> tx.upsert(new SecretRows.Stored(new SecretRows.Meta(name, value.kind(), Seal.FORMAT,
                        sealed.keyId(), sealed.nonce(), sealed.sealed().length, now, now, who.name()), sealed.sealed())));
            }).compose(v -> audit(tx, who, "secret.create", name, detail(value))).compose(v -> tx.changed());
        })).compose(v -> readBack(name));
    }

    /**
     * Overwrites {@code name} with the value of {@code body}, of its kind; the last writer wins. Answers its entry of the list.
     *
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code reserved}, {@code keys-differ},
     *         {@code no-secret}, {@code kind-differs}, {@code store-full}, {@code unaudited}
     */
    public Future<JsonObject> overwrite(Actor who, String name, JsonObject body) {
        SecretValue value;
        try {
            writable(name);
            value = value(name, body);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        StoreKey k = key;
        return rows.<SecretRows.Head>write(List.of(), tx -> keyCheck(tx, k).compose(v -> tx.rows()).compose(all -> {
            SecretRows.Meta old = all.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
            if (old == null) return refuse(404, RefusalCode.NO_SECRET, "the store holds no secret '" + name + "'", name);
            if (old.kind() != value.kind()) {
                return Future.failedFuture(new SecretRefused(409, RefusalCode.KIND_DIFFERS, "'" + name + "' is a secret of kind " + old.kind().id()
                        + ", not " + value.kind().id() + ": delete it and create it again", new JsonObject().put("name", name)
                        .put("kind", old.kind().id()).put("asked", value.kind().id())));
            }
            Seal.Sealed sealed = Seal.seal(k.current(), SCOPE, name, value.kind(), value.plain());
            return full(all, old, sealed).compose(v -> tx.upsert(new SecretRows.Stored(new SecretRows.Meta(name, value.kind(), Seal.FORMAT,
                    sealed.keyId(), sealed.nonce(), sealed.sealed().length, old.createdAt(), clock.instant(), who.name()), sealed.sealed())))
                    .compose(v -> audit(tx, who, "secret.overwrite", name, detail(value))).compose(v -> tx.changed());
        })).compose(v -> readBack(name));
    }

    /**
     * Deletes {@code name}. A name with a use, or while a record's uses are not known, is deleted only when {@code confirm}
     * equals it.
     *
     * @return fails with a {@link SecretRefused}: {@code invalid}, {@code no-secret}, {@code in-use}, {@code unaudited}
     */
    public Future<Void> delete(Actor who, String name, Optional<String> confirm) {
        try {
            name(name);
        } catch (SecretRefused refused) {
            return Future.failedFuture(refused);
        }
        boolean confirmed = confirm.filter(name::equals).isPresent();
        return rows.<SecretRows.Head>write(List.of(name), tx -> tx.row(name).compose(row -> {
            if (row.isEmpty()) return refuse(404, RefusalCode.NO_SECRET, "the store holds no secret '" + name + "'", name);
            return tx.uses(name).compose(uses -> tx.unknownRecords().compose(unknown -> {
                JsonArray usedBy = users(uses);
                JsonArray mayUse = new JsonArray(unknown.subList(0, Math.min(MAX_USERS_NAMED, unknown.size())));
                if ((!uses.isEmpty() || !unknown.isEmpty()) && !confirmed) {
                    return Future.failedFuture(new SecretRefused(409, RefusalCode.IN_USE, "'" + name + "' is used"
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
        return Future.failedFuture(new SecretRefused(503, RefusalCode.WRITTEN_UNREAD, "the change is written, but this node could not read it back: it is in force"
                + " within " + TICK_SECONDS + " s; do not send it again", new JsonObject()));
    }

    /** Refuses a write unless every live node holds this node's current key, by its current join. */
    private Future<Void> keyCheck(SecretRows.Tx tx, StoreKey k) {
        return tx.liveNodes().compose(nodes -> keyCheck(nodes, k));
    }

    private static Future<Void> keyCheck(List<SecretRows.NodeKeys> nodes, StoreKey k) {
        byte[] id = Seal.keyId(k.current());
        List<String> lacking = nodes.stream().filter(n -> !n.holds(id)).map(SecretRows.NodeKeys::node).sorted().toList();
        if (lacking.isEmpty()) return Future.succeededFuture();
        return Future.failedFuture(new SecretRefused(409, RefusalCode.KEYS_DIFFER, "the nodes " + lacking + " do not hold the key " + HEX.formatHex(id)
                + " this node seals under: try again once the roll of the key has ended", new JsonObject().put("key", HEX.formatHex(id))
                .put("nodes", new JsonArray(lacking))));
    }

    /** Refuses a write whose sealed values together would take more than {@value #MAX_SEALED_BYTES} bytes. */
    private static Future<Void> full(List<SecretRows.Meta> all, SecretRows.Meta replaced, Seal.Sealed sealed) {
        long total = sealed.sealed().length;
        for (SecretRows.Meta m : all) if (m != replaced) total += m.sealedBytes();
        if (total <= MAX_SEALED_BYTES) return Future.succeededFuture();
        return Future.failedFuture(new SecretRefused(409, RefusalCode.STORE_FULL, "the sealed values would take more than " + MAX_SEALED_BYTES + " bytes, the store's most",
                new JsonObject().put("bytes", MAX_SEALED_BYTES)));
    }

    private static Future<Void> audit(SecretRows.Tx tx, Actor who, String action, String name, JsonObject detail) {
        return tx.audit(who, action, "secret/" + name, detail).recover(failure -> Future.failedFuture(new SecretRefused(503, RefusalCode.UNAUDITED,
                "the audit row could not be written, so nothing was changed: try again")));
    }

    private static <T> Future<T> refuse(int status, RefusalCode code, String text, String name) {
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
            throw new SecretRefused(409, RefusalCode.RESERVED, "names under " + RESERVED + " belong to the platform: of them, only " + GIT + " may be written",
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
     * The value of {@code body} for the secret {@code name}, of its {@code kind}, checked against its limits; the user and the
     * token of {@code rail8.git} are not blank.
     *
     * @throws SecretRefused 422 {@code invalid}, naming the field and never echoing the value
     */
    static SecretValue value(String name, JsonObject body) throws SecretRefused {
        SecretValue value = value(body);
        if (name.equals(GIT) && value instanceof SecretValue.Login login) {
            if (login.credentials().user().isBlank()) throw SecretRefused.invalid("user", "a user of " + GIT + " is not blank");
            if (login.credentials().password().isBlank()) throw SecretRefused.invalid("password", "a token of " + GIT + " is not blank");
        }
        return value;
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
                    throw SecretRefused.invalid("value", "a value takes at most " + MAX_VALUE_BYTES + " bytes");
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
     * The answer of {@code GET /admin/secrets}: every secret, the names a use names that the store does not hold, the key's
     * state. A node's keys count only while its join is its current one.
     */
    public Future<JsonObject> list() {
        return rows.listing().map(this::listed);
    }

    /** The entry of {@code name}: a stored secret's, or a missing name a use names; empty when neither is. */
    public Future<Optional<JsonObject>> entry(String name) {
        return list().map(all -> {
            for (String part : List.of("secrets", "missing")) {
                for (Object o : all.getJsonArray(part)) {
                    JsonObject e = (JsonObject) o;
                    if (name.equals(e.getString("name"))) return Optional.of(e.put("node", node));
                }
            }
            return Optional.<JsonObject>empty();
        });
    }

    private JsonObject listed(SecretRows.Listing l) {
        Map<String, List<SecretRows.Use>> uses = new TreeMap<>();
        l.uses().forEach(u -> uses.computeIfAbsent(u.name(), n -> new ArrayList<>()).add(u));
        State held = state;
        Set<String> stored = new HashSet<>();

        JsonArray secrets = new JsonArray();
        for (SecretRows.Meta m : l.rows().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList()) {
            stored.add(m.name());
            Opened o = held.held().get(m.name());
            boolean readable = o != null && o.value() != null && Arrays.equals(o.nonce(), m.nonce());
            JsonObject e = new JsonObject().put("name", m.name()).put("kind", m.kind().id()).put("scope", SCOPE).put("stored", true)
                    .put("changedAt", m.changedAt().toString()).put("changedBy", m.changedBy())
                    .put("usedBy", users(uses.get(m.name()))).put("users", uses.getOrDefault(m.name(), List.of()).size())
                    .put("unreadable", !readable).put("keyId", HEX.formatHex(m.keyId()));
            if (readable && o.value() instanceof SecretValue.Pair pair) e.put("keyPair", pair.facts());
            secrets.add(e);
        }
        JsonArray missing = new JsonArray();
        uses.forEach((name, list) -> {
            if (stored.contains(name)) return;
            missing.add(new JsonObject().put("name", name).put("stored", false).put("usedBy", users(list)).put("users", list.size()));
        });
        StoreKey k = key;
        byte[] mine = Seal.keyId(k.current());
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
        String common = currents.size() == 1 && !currents.contains("null") ? currents.iterator().next() : HEX.formatHex(mine);
        long other = common == null ? l.rows().size() : l.rows().stream().filter(m -> !HEX.formatHex(m.keyId()).equals(common)).count();
        JsonObject keyState = new JsonObject().put("nodes", nodes).put("common", currents.size() == 1 && !currents.contains("null") ? common : null)
                .put("rowsUnderAnotherKey", other).put("previousSet", previous);
        return new JsonObject().put("secrets", secrets).put("missing", missing).put("key", keyState).put("node", node);
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
     * name against the rows in Postgres: set in the store, and of a kind the use takes. With a problem it writes nothing.
     *
     * @return one text per problem, naming the user, the name and its source, never a value; empty when it wrote the uses
     */
    public Future<List<String>> replaceUses(SqlConnection tx, List<Uses> users) {
        return PgSecretRows.replaceUses(tx, users);
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
        byte[] current = Seal.keyId(k.current());
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
        byte[] currentKey = k.current();
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
                        audited = tx.auditByNode(node, "secret.key-changed", "secret-store",
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
     * @return fails with a {@link SecretRefused}: 409 {@code keys-differ}
     */
    public Future<Binding> bind(SqlConnection tx, byte[] bytes) {
        StoreKey k = key;
        return rows.sharedNodes(tx).compose(nodes -> keyCheck(nodes, k)).map(v -> {
            byte[] current = k.current();
            return new Binding(Seal.keyId(current), tag(current, bytes));
        });
    }

    /**
     * The binding of this node's current key for the caller's transaction {@code tx}, to bind several byte arrays under one key:
     * it takes the store's row lock for share and makes a write's key check, as {@link #bind} does, but keeps a failed check
     * until {@link Binder#bind} is called, so the caller may take its own locks and make its own checks first.
     */
    public Future<Binder> binder(SqlConnection tx) {
        StoreKey k = key;
        return rows.sharedNodes(tx).compose(nodes -> keyCheck(nodes, k).transform(checked ->
                Future.succeededFuture(new Binder(k.current(), checked.failed() ? checked.cause() : null))));
    }

    /** Binds byte arrays under one key, the node's current key when its {@link SecretStore#binder} was made. */
    public static final class Binder {
        private final byte[] key;
        private final Throwable refused;

        private Binder(byte[] key, Throwable refused) {
            this.key = key;
            this.refused = refused;
        }

        /** The id of the key this binder binds under. */
        public byte[] keyId() {
            return Seal.keyId(key);
        }

        /**
         * {@code bytes} bound under this binder's key, as {@link SecretStore#bind} binds them.
         *
         * @throws SecretRefused 409 {@code keys-differ}, when a live node did not hold the key at the binder's check
         */
        public Binding bind(byte[] bytes) throws SecretRefused {
            if (refused instanceof SecretRefused r) throw r;
            if (refused != null) throw new IllegalStateException("the secret store's key check failed", refused);
            return new Binding(Seal.keyId(key), tag(key, bytes));
        }

        @Override
        public String toString() {
            return "Binder[key " + HEX.formatHex(Seal.keyId(key)) + (refused == null ? "]" : ", refused]");
        }
    }

    /**
     * The kind of each name of {@code names} that is set in Postgres, in the caller's transaction {@code tx}; a name not set is
     * absent. Read after {@link #replaceUses} in the same transaction, it holds while the names' locks are held.
     */
    public Future<Map<String, Secret.Kind>> kinds(SqlConnection tx, Set<String> names) {
        return PgSecretRows.kinds(tx, names);
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
