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

    /**
     * The started store of node {@code node}: its key and variables from {@code env}, as {@link Secrets#fromEnv} reads them, its
     * ticks on {@code timer}.
     */
    public SecretStore start(String node, Map<String, String> env, Clock clock, Timer timer) {
        Secrets secrets = Secrets.fromEnv(env);
        long epoch = ++epochs;
        db.node(node, epoch, true);
        SecretStore store = new SecretStore(new MemorySecretRows(db), secrets.key(), node, secrets.variableNames(), clock, timer, warnings::add);
        db.listen(store::notice);
        done(store.start(epoch));
        return store;
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

    private static <T> T done(Future<T> f) {
        if (!f.isComplete()) throw new AssertionError("an in-memory store's future completes at once");
        if (f.failed()) throw new AssertionError("the store refused: " + f.cause(), f.cause());
        return f.result();
    }
}
