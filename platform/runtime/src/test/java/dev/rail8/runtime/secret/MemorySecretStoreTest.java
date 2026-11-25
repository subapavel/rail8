package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rail8.runtime.deploy.Secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        db.forge(new SecretRows.NodeKeys(node, 1, 1L, keyId, null));
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

    @Override List<String> actors(String action) {
        return db.audit().stream().filter(a -> a.action().equals(action)).map(a -> a.name() + "/" + a.via()).toList();
    }

    @Override void put(SecretRows.Stored row) { db.put(row); }

    @Override <T> T inTx(java.util.function.Function<io.vertx.sqlclient.SqlConnection, io.vertx.core.Future<T>> work) {
        return await(work.apply(null));
    }

    @Test
    void aWriteThatItsNodeCannotReadBackIsWrittenUnread() {
        Node a = start("a", K1);
        db.readsFail(true);
        SecretRefused r = refused(a.store.create(ALICE, value("db", "the-value")));
        assertEquals(503, r.status());
        assertEquals("written-unread", r.code());
        assertEquals(1, db.rows().size(), "the change is written");
        db.readsFail(false);
        a.tick();
        assertEquals(java.util.Optional.of("the-value"), read(a, "db"), "in force within one tick");
        assertNoValueCaptured();
    }

    @Test
    void threeFailedLooksMakeTheNodeDegradedWithOneWarning() {
        Node a = start("a", K1);
        db.readsFail(true);
        failingTick(a);
        failingTick(a);
        assertEquals(dev.rail8.api.Health.Status.UP, a.store.health().status(), "not after two");
        failingTick(a);
        assertEquals(dev.rail8.api.Health.Status.DEGRADED, a.store.health().status());
        assertTrue(a.store.health().toString().contains("2026-11-25T10:00:00Z"), "it names its last good read: " + a.store.health());
        failingTick(a);
        assertEquals(1, a.warnings.stream().filter(w -> w.contains("could not be read")).count(), "one warning, not one a tick");
        db.readsFail(false);
        a.tick();
        assertEquals(dev.rail8.api.Health.Status.UP, a.store.health().status());
    }

    @Test
    void aLookThatEndsAfterAWriteNeverPublishesOverIt() {
        MemorySecretRows inner = new MemorySecretRows(db);
        io.vertx.core.Promise<Void> gate = io.vertx.core.Promise.promise();
        boolean[] armed = {false};
        SecretRows slow = new SecretRows() {
            @Override public io.vertx.core.Future<Head> head() { return inner.head(); }
            @Override public io.vertx.core.Future<Snapshot> snapshot(int maxRow) {
                io.vertx.core.Future<Snapshot> read = inner.snapshot(maxRow);
                if (!armed[0]) return read;
                armed[0] = false;
                return read.compose(taken -> gate.future().map(taken));   // read now, handed on later
            }
            @Override public <T> io.vertx.core.Future<T> write(java.util.Collection<String> names, java.util.function.Function<Tx, io.vertx.core.Future<T>> work) {
                return inner.write(names, work);
            }
            @Override public io.vertx.core.Future<List<NodeKeys>> liveNodes() { return inner.liveNodes(); }
            @Override public io.vertx.core.Future<List<NodeKeys>> sharedNodes(io.vertx.sqlclient.SqlConnection tx) { return inner.sharedNodes(tx); }
            @Override public io.vertx.core.Future<Listing> listing() { return inner.listing(); }
        };
        Node b = start("b", K1);
        dev.rail8.testkit.FakeClock clock = dev.rail8.testkit.FakeClock.at(java.time.Instant.parse("2026-11-25T10:00:00Z"));
        SecretStore a = new SecretStore(slow, Secrets.fromEnv(Map.of(StoreKey.VARIABLE, b64(K1))).key().orElseThrow(), "a", clock,
                new dev.rail8.testkit.FakeTimer(clock), captured::add, captured::add);
        await(a.start(join("a"), SecretStore.Import.none()));
        db.listen(a::notice);
        armed[0] = true;
        create(b, value("db", "first-value"));
        overwrite(b, "db", value("db", "second-value"));
        gate.complete();
        assertEquals("second-value", ((String) a.held("db").orElseThrow().value()), "the look that read the first value ran before the next");
        assertNoValueCaptured();
    }

    /** A tick whose look fails: the clock moved, and the failure of its work seen. */
    static void failingTick(Node n) {
        n.clock.advance(SecretStore.TICK);
        assertTrue(n.store.ticked().failed(), "the look failed");
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
