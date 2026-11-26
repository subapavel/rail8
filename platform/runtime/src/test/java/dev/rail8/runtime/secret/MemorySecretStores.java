package dev.rail8.runtime.secret;

import dev.rail8.api.Timer;
import dev.rail8.core.audit.Actor;
import dev.rail8.runtime.deploy.Secrets;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Secret stores of several nodes over one in-memory database, for tests outside this package: each node's store started, and
 * told of every write at once, as its notice tells a node in Postgres.
 */
public final class MemorySecretStores {

    /** Who writes in these tests. */
    public static final Actor ADMIN = new Actor("admin", OptionalLong.of(1), "session", Optional.empty(), "127.0.0.1");

    private final MemorySecretRows.Db db = new MemorySecretRows.Db();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private long epochs;

    /** The key of every store whose env gives none. */
    public static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    /**
     * The started store of node {@code node}: its key and the variables its join imports into a store never written from
     * {@code env}, as {@link Secrets#fromEnv} reads them, the key {@link #KEY} when {@code env} gives none; its ticks on
     * {@code timer}.
     */
    public SecretStore start(String node, Map<String, String> env, Clock clock, Timer timer) {
        return start(node, Secrets.fromEnv(env), clock, timer);
    }

    /** The started store of node {@code node}: the key and the import of {@code secrets}, the key {@link #KEY} when it has none. */
    public SecretStore start(String node, Secrets secrets, Clock clock, Timer timer) {
        long epoch = ++epochs;
        db.node(node, epoch, true);
        StoreKey key = secrets.key().orElseGet(() -> StoreKey.fromEnv(Map.of(StoreKey.VARIABLE, KEY)).orElseThrow());
        SecretStore store = new SecretStore(new MemorySecretRows(db), key, node, clock, timer, warnings::add, text -> { });
        db.listen(store::notice);
        done(store.start(epoch, secrets.imports()));
        return store;
    }

    /**
     * {@code config} with the resolver of a node whose store, alone in its database, imported {@code env}'s variables at its first
     * join: what a node started with {@code env} reads.
     */
    public static dev.rail8.runtime.node.NodeConfig imported(dev.rail8.runtime.node.NodeConfig config, Map<String, String> env, Clock clock, Timer timer) {
        return config.withSecrets(config.secrets().withStore(new MemorySecretStores().start(config.nodeId(), env, clock, timer)));
    }

    /** Records that flow {@code flow}, by qualified id, uses {@code name} as {@code kinds}, as a deploy's save writes it in Postgres. */
    public void use(String flow, String name, java.util.Set<dev.rail8.api.Secret.Kind> kinds) {
        db.use(new SecretRows.Use(name, "flow", flow, kinds));
    }

    /** The warnings every store logged, in order. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** The audit rows written, in order: each {@code {actor, via, action, subject, detail}}. */
    public List<JsonObject> audit() {
        return db.audit().stream().map(a -> new JsonObject().put("actor", a.name()).put("via", a.via()).put("action", a.action())
                .put("subject", a.subject()).put("detail", a.detail())).toList();
    }

    /** Flips a bit of the sealed value of {@code name}, as a writer of the database other than the store would. */
    public void tamper(String name) {
        SecretRows.Stored row = db.rows().get(name);
        byte[] sealed = row.sealed().clone();
        sealed[0] ^= 1;
        db.put(new SecretRows.Stored(row.meta(), sealed));
    }

    /** Whether every audit row fails from now on. */
    public void auditFails(boolean fails) {
        db.auditFails(fails);
    }

    /** {@code store} creates the plain value {@code name}. */
    public static void createValue(SecretStore store, String name, String value) {
        done(store.create(ADMIN, new JsonObject().put("name", name).put("kind", "value").put("value", value)));
    }

    /** {@code store} creates the credentials {@code name}. */
    public static void createCredentials(SecretStore store, String name, String user, String password) {
        done(store.create(ADMIN, new JsonObject().put("name", name).put("kind", "credentials").put("user", user).put("password", password)));
    }

    /** {@code store} creates the key pair {@code name}: the placeholder's private key, with no certificate. */
    public static void createKeyPair(SecretStore store, String name) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(SecretStore.placeholderKeyPair().privateKey().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        done(store.create(ADMIN, new JsonObject().put("name", name).put("kind", "key-pair").put("privateKey", pem)));
    }

    /** {@code store} overwrites the credentials {@code name}. */
    public static void overwriteCredentials(SecretStore store, String name, String user, String password) {
        done(store.overwrite(ADMIN, name, new JsonObject().put("kind", "credentials").put("user", user).put("password", password)));
    }

    /** {@code store} overwrites the plain value {@code name}. */
    public static void overwriteValue(SecretStore store, String name, String value) {
        done(store.overwrite(ADMIN, name, new JsonObject().put("kind", "value").put("value", value)));
    }

    /** {@code store} deletes {@code name}, confirmed. */
    public static void delete(SecretStore store, String name) {
        done(store.delete(ADMIN, name, Optional.of(name)));
    }

    /** The work of {@code store}'s last tick, for a test that moved its clock past {@link #TICK}. */
    public static Future<Void> ticked(SecretStore store) {
        return store.ticked();
    }

    /** A look of {@code store} at its rows, as a notice starts one. */
    public static Future<Void> look(SecretStore store) {
        return store.look();
    }

    /** How long a store waits between its ticks. */
    public static final java.time.Duration TICK = SecretStore.TICK;

    private static <T> T done(Future<T> f) {
        if (!f.isComplete()) throw new AssertionError("an in-memory store's future completes at once");
        if (f.failed()) throw new AssertionError("the store refused: " + f.cause(), f.cause());
        return f.result();
    }
}
