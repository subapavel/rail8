package dev.rail8.runtime.config;

import dev.rail8.runtime.secret.SecretStore;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.util.Optional;
import java.util.Random;

/**
 * Configuration stores in memory over one shared database, for the tests outside this package: each node's store binds under
 * its secret store's key and knows the adapters {@code web} and {@code sftp} of {@link ConfigStoreContract}.
 */
public final class MemoryConfigStores {

    private final MemoryConfigRows.Db db = new MemoryConfigRows.Db();

    /** The store of node {@code node}, whose lease holds in epoch {@code epoch}, binding under {@code secrets}' key. */
    public ConfigStore start(String node, long epoch, SecretStore secrets, Clock clock) {
        synchronized (db) {
            db.lease(node, epoch);
        }
        return new ConfigObjects(new MemoryConfigRows(db, secrets), secrets, a -> Optional.ofNullable(ConfigStoreContract.ADAPTERS.get(a)),
                clock, new Random(7), text -> { });
    }

    /** A deployed system definition {@code definition} of the system {@code system}, as the deploy records it. */
    public void deploySystem(String system, JsonObject definition) {
        synchronized (db) {
            db.systems.put(system, definition.copy());
        }
    }

    /** The audit rows the stores wrote, oldest first. */
    public java.util.List<JsonObject> audit() {
        synchronized (db) {
            return db.audit.stream().map(JsonObject::copy).toList();
        }
    }

    /** A deployed flow's use of the connection {@code connection}, as the deploy records it. */
    public void use(String flow, String step, String connection, String adapter, String direction, boolean address) {
        synchronized (db) {
            db.uses.add(new ConfigRows.Use(flow, step, connection, adapter, direction, address));
        }
    }
}
