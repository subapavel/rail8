package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The in-memory store passes the {@link SecretStoreContract}, and refuses a store beyond its limits. */
class MemorySecretStoreTest extends SecretStoreContract {

    final MemorySecretRows.Db db = new MemorySecretRows.Db();
    final Map<String, Long> epochs = new HashMap<>();

    @Override SecretRows rows() { return new MemorySecretRows(db); }

    @Override long join(String node) {
        long epoch = epochs.merge(node, 1L, Long::sum);
        db.node(node, epoch, true);
        return epoch;
    }

    @Override void leave(String node) { db.node(node, epochs.getOrDefault(node, 0L), false); }

    @Override void hear(SecretStore store) { db.listen(store::notice); }

    @Override void awaitNotices() { }

    @Override void dropNotices(boolean drop) { db.dropNotices(drop); }

    @Override Map<String, SecretRows.Stored> stored() { return db.rows(); }

    @Override SecretRows.Head head() { return db.head(); }

    @Override void restore(SecretRows.Head head, Map<String, SecretRows.Stored> rows) { db.restore(head, rows); }

    @Override void tamper(String name) {
        SecretRows.Stored row = db.rows().get(name);
        byte[] sealed = row.sealed().clone();
        sealed[0] ^= 1;
        db.put(new SecretRows.Stored(row.meta(), sealed));
    }

    @Override void forge(String node, byte[] keyId) {
        db.forge(new SecretRows.NodeKeys(node, 1, 1L, keyId, null, List.of()));
        epochs.put(node, 1L);
    }

    @Override void use(String name, String userKind, String userId, Set<Secret.Kind> kinds) {
        db.use(new SecretRows.Use(name, userKind, userId, kinds));
    }

    @Override void unknownRecord(String pkg) { db.unknownRecord(pkg); }

    @Override void auditFails(boolean fails) { db.auditFails(fails); }

    @Override List<JsonObject> audit(String action) {
        return db.audit().stream().filter(a -> a.action().equals(action)).map(MemorySecretRows.Audit::detail).toList();
    }

    @Test
    void aStoreHoldsAtMostAThousandSecrets() {
        Node a = start("a", K1);
        for (int i = 0; i < SecretStore.MAX_SECRETS; i++) create(a, value("s" + i, "value-" + i));
        SecretRefused full = refused(a.store.create(ALICE, value("one-more", "value-more")));
        assertEquals("store-full", full.code());
        assertEquals(409, full.status());
    }

    @Test
    void theSealedValuesTakeAtMostSixteenMebibytes() {
        Node a = start("a", K1);
        String big = "v".repeat(SecretStore.MAX_VALUE_BYTES - 64);
        int i = 0;
        while (true) {
            try {
                await(a.store.create(ALICE, new JsonObject().put("name", "s" + i).put("kind", "value").put("value", big)));
            } catch (AssertionError refused) {
                break;
            }
            i++;
        }
        SecretRefused full = refused(a.store.create(ALICE, new JsonObject().put("name", "s" + i).put("kind", "value").put("value", big)));
        assertEquals("store-full", full.code());
        long total = db.rows().values().stream().mapToLong(r -> r.sealed().length).sum();
        assertEquals(true, total <= SecretStore.MAX_SEALED_BYTES && total + big.length() > SecretStore.MAX_SEALED_BYTES, "refused at the limit: " + total);
    }
}
