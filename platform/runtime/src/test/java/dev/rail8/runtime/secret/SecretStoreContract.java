package dev.rail8.runtime.secret;

import dev.rail8.api.Health;
import dev.rail8.api.Secret;
import dev.rail8.core.audit.Actor;
import dev.rail8.runtime.deploy.Secrets;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every backend of the secret store does: the Postgres one, two stores over one database standing for two nodes, and the
 * in-memory one. Each node has its own fake clock and timer; a tick is the clock moved by 5 s.
 */
abstract class SecretStoreContract {

    static final byte[] K1 = key(11);
    static final byte[] K2 = key(22);
    static final byte[] K9 = key(99);
    static final Actor ALICE = new Actor("alice", OptionalLong.of(7), "session", Optional.empty(), "127.0.0.1");

    /** Every value a test wrote: no captured text holds one, in any of its encodings. */
    final List<String> written = new CopyOnWriteArrayList<>();
    /** Every text a test captured: warnings, answers, refusals, audit rows. */
    final List<String> captured = new CopyOnWriteArrayList<>();

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return k;
    }

    static String b64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    static String id(byte[] key) {
        return HexFormat.of().formatHex(Seal.keyId(key));
    }

    // ------------------------------------------------------------------------------------------------- what each backend gives

    /** One node's connection to the database's rows. */
    abstract SecretRows rows();

    /** Joins {@code node} to the pool, its lease holding: its new epoch. */
    abstract long join(String node);

    /** {@code node}'s lease runs out. */
    abstract void leave(String node);

    /** {@code store} hears the notices of the database's changes. */
    abstract void hear(SecretStore store);

    /** Waits until each store that hears has looked after the notices of the changes made so far. */
    abstract void awaitNotices();

    /** Whether the notices are lost from now on. */
    abstract void dropNotices(boolean drop);

    /** The rows of {@code secrets} now. */
    abstract Map<String, SecretRows.Stored> stored();

    /** The store's head now. */
    abstract SecretRows.Head head();

    /** Sets the store back to {@code head} and {@code rows}, as a restored backup would; no notice. */
    abstract void restore(SecretRows.Head head, Map<String, SecretRows.Stored> rows);

    /** Changes one byte of the sealed value of {@code name}. */
    abstract void tamper(String name);

    /** Writes a live, joined row of {@code nodes} for {@code node} naming {@code keyId} as its key. */
    abstract void forge(String node, byte[] keyId);

    /** Adds a use of {@code name}. */
    abstract void use(String name, String userKind, String userId, Set<Secret.Kind> kinds);

    /** Marks a record of {@code pkg} whose uses are not known. */
    abstract void unknownRecord(String pkg);

    /** Whether every audit row fails from now on. */
    abstract void auditFails(boolean fails);

    /** The details of the audit rows of {@code action}, oldest first. */
    abstract List<JsonObject> audit(String action);

    /** Puts {@code row} as a database writer would, with no change of the head. */
    abstract void put(SecretRows.Stored row);

    /** {@code work} in a transaction of its own, committed when it succeeds: its result. */
    abstract <T> T inTx(java.util.function.Function<io.vertx.sqlclient.SqlConnection, Future<T>> work);

    // ------------------------------------------------------------------------------------------------- nodes

    final class Node {
        final String id;
        final FakeClock clock = FakeClock.at(Instant.parse("2026-11-25T10:00:00Z"));
        final FakeTimer timer = new FakeTimer(clock);
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final Map<String, String> env;
        SecretStore store;
        Secrets secrets;

        Node(String id, Map<String, String> env) {
            this.id = id;
            this.env = env;
        }

        void tick() {
            clock.advance(SecretStore.TICK);
            await(store.ticked());
        }
    }

    /** Starts {@code id} with {@code env}: the key's variables and its {@code RAIL8_SECRET_*}. */
    Node start(String id, Map<String, String> env) {
        Node n = new Node(id, env);
        Secrets fromEnv = Secrets.fromEnv(env);
        long epoch = join(id);
        n.store = new SecretStore(rows(), fromEnv.key(), id, fromEnv.variableNames(), n.clock, n.timer, text -> {
            n.warnings.add(text);
            captured.add(text);
        });
        hear(n.store);
        await(n.store.start(epoch));
        n.secrets = fromEnv.withStore(n.store);
        return n;
    }

    Node start(String id, byte[] key, byte[] previous) {
        Map<String, String> env = new HashMap<>();
        if (key != null) env.put(StoreKey.VARIABLE, b64(key));
        if (previous != null) env.put(StoreKey.PREVIOUS, b64(previous));
        return start(id, env);
    }

    Node start(String id, byte[] key) {
        return start(id, key, null);
    }

    Node restart(Node n, byte[] key, byte[] previous) {
        n.store.close();
        return start(n.id, key, previous);
    }

    IllegalStateException refusedStart(String id, byte[] key, byte[] previous) {
        Map<String, String> env = new HashMap<>();
        if (key != null) env.put(StoreKey.VARIABLE, b64(key));
        if (previous != null) env.put(StoreKey.PREVIOUS, b64(previous));
        Secrets fromEnv = Secrets.fromEnv(env);
        FakeClock clock = FakeClock.at(Instant.parse("2026-11-25T10:00:00Z"));
        SecretStore store = new SecretStore(rows(), fromEnv.key(), id, List.of(), clock, new FakeTimer(clock), captured::add);
        Throwable failure = awaitFailure(store.start(join(id)));
        if (!(failure instanceof IllegalStateException refused)) throw new AssertionError("the start was not refused as expected", failure);
        captured.add(refused.getMessage());
        return refused;
    }

    // ------------------------------------------------------------------------------------------------- calls

    static <T> T await(Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the call failed: " + e, e);
        }
    }

    static Throwable awaitFailure(Future<?> f) {
        try {
            f.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("the call succeeded");
    }

    JsonObject value(String name, String value) {
        written.add(value);
        return new JsonObject().put("name", name).put("kind", "value").put("value", value);
    }

    JsonObject credentials(String name, String user, String password) {
        written.add(password);
        return new JsonObject().put("name", name).put("kind", "credentials").put("user", user).put("password", password);
    }

    JsonObject create(Node n, JsonObject body) {
        JsonObject answer = await(n.store.create(ALICE, body));
        captured.add(answer.encode());
        return answer;
    }

    JsonObject overwrite(Node n, String name, JsonObject body) {
        JsonObject answer = await(n.store.overwrite(ALICE, name, body));
        captured.add(answer.encode());
        return answer;
    }

    SecretRefused refused(Future<?> call) {
        Throwable failure = awaitFailure(call);
        if (!(failure instanceof SecretRefused refused)) throw new AssertionError("not a refusal", failure);
        captured.add(refused.body().encode());
        return refused;
    }

    JsonObject list(Node n) {
        JsonObject answer = await(n.store.list());
        captured.add(answer.encode());
        return answer;
    }

    static Optional<String> read(Node n, String name) {
        return n.secrets.resolve("secret://" + name);
    }

    SecretStore.Binding bind(Node n, String bytes) {
        return inTx(tx -> n.store.bind(tx, bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------------------------------------- the notices

    @Test
    void aWriteOnOneIsReadOnTheOtherAfterTheNotice() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "first-value"));
        assertEquals(Optional.of("first-value"), read(a, "db"), "in force on the writing node when it is answered");
        awaitNotices();
        assertEquals(Optional.of("first-value"), read(b, "db"), "read on the other after the notice");
        overwrite(a, "db", value("db", "second-value"));
        awaitNotices();
        assertEquals(Optional.of("second-value"), read(b, "db"));
        assertNoValueCaptured();
    }

    @Test
    void withTheNoticeLostOneTickReadsIt() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        dropNotices(true);
        create(a, value("db", "unheard-value"));
        assertEquals(Optional.empty(), read(b, "db"), "not before the tick");
        b.tick();
        assertEquals(Optional.of("unheard-value"), read(b, "db"), "one tick reads it");
    }

    @Test
    void aDatabaseSetBackIsReadWhole() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "kept-value"));
        awaitNotices();
        SecretRows.Head then = head();
        Map<String, SecretRows.Stored> rows = stored();
        overwrite(a, "db", value("db", "lost-value"));
        create(a, value("other", "lost-too"));
        awaitNotices();

        restore(then, rows);
        a.tick();
        b.tick();
        for (Node n : List.of(a, b)) {
            assertEquals(Optional.of("kept-value"), read(n, "db"), n.id + " reads the earlier state whole");
            assertEquals(Optional.empty(), read(n, "other"), n.id);
            assertTrue(n.warnings.stream().anyMatch(w -> w.contains("earlier state")), n.id + " warns");
        }
    }

    @Test
    void aDatabaseSetBackOneChangeAndWrittenOnceIsReadWhole() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "kept-value"));
        awaitNotices();
        SecretRows.Head then = head();
        Map<String, SecretRows.Stored> rows = stored();
        overwrite(a, "db", value("db", "lost-value"));
        awaitNotices();

        restore(then, rows);
        create(a, value("other", "written-after"));   // the same generation as before the restore, another stamp
        awaitNotices();
        for (Node n : List.of(a, b)) {
            assertEquals(Optional.of("kept-value"), read(n, "db"), n.id + " reads the whole store, not only the new row");
            assertEquals(Optional.of("written-after"), read(n, "other"), n.id);
            assertTrue(n.warnings.stream().anyMatch(w -> w.contains("earlier state")), n.id + " warns");
        }
    }

    // ------------------------------------------------------------------------------------------------- the sources

    @Test
    void aDeleteBringsBackTheVariableAndFailsTheNextRead() {
        Node a = start("a", K1);
        Node b = start("b", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_DB", "variable-value"));
        written.add("variable-value");
        assertEquals(Optional.of("variable-value"), read(b, "db"));
        create(a, value("db", "store-value"));
        awaitNotices();
        Secret onA = a.secrets.handle("secret://db");
        Secret onB = b.secrets.handle("secret://db");
        assertEquals("store-value", onB.value(), "the store wins");
        assertEquals(List.of("b"), list(a).getJsonArray("secrets").getJsonObject(0).getJsonArray("shadowedOn").getList(), "the list names the shadowed");

        await(a.store.delete(ALICE, "db", Optional.empty()));
        awaitNotices();
        assertEquals("variable-value", onB.value(), "after the delete the variable takes over");
        IllegalStateException gone = assertThrows(IllegalStateException.class, onA::value, "a deleted secret fails the next read");
        assertTrue(gone.getMessage().contains("secret://db"));
        assertNoValueCaptured();
    }

    @Test
    void aTamperedRowIsUnreadableAndNeverFallsBack() {
        Node a = start("a", K1);
        create(a, value("db", "sealed-value"));
        tamper("db");
        Node c = start("c", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_DB", "variable-value"));
        written.add("variable-value");
        assertEquals(Optional.empty(), read(c, "db"), "no value, and not the variable's");
        assertEquals(Health.Status.DEGRADED, c.store.health().status());
        assertTrue(c.warnings.stream().anyMatch(w -> w.contains("'db'") && w.contains(id(K1))), "one warning names the name and the key");
        assertTrue(c.secrets.problem("secret://db", Set.of(Secret.Kind.VALUE)).orElseThrow().contains("does not open"));
        assertTrue(list(c).getJsonArray("secrets").getJsonObject(0).getBoolean("unreadable"));
        c.tick();
        assertEquals(1, c.warnings.stream().filter(w -> w.contains("'db'")).count(), "once");
        assertNoValueCaptured();
    }

    @Test
    void aRowDamagedWithoutAChangeIsUnreadableAtTheNextFullReadOfAWarmNode() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "sealed-value"));
        awaitNotices();
        assertEquals(Optional.of("sealed-value"), read(b, "db"));
        tamper("db");
        b.tick();
        assertEquals(Optional.of("sealed-value"), read(b, "db"), "no change of the generation: not read again");
        create(a, value("other", "other-value"));
        awaitNotices();
        b.tick();
        assertEquals(Optional.empty(), read(b, "db"), "the next full read opens every row");
        assertEquals(Health.Status.DEGRADED, b.store.health().status());
        assertNoValueCaptured();
    }

    @Test
    void aRowLongerThan256KiBIsUnreadableWithoutBeingRead() {
        Node a = start("a", K1);
        create(a, value("db", "sealed-value"));
        SecretRows.Meta m = stored().get("db").meta();
        byte[] sealed = new byte[SecretStore.MAX_ROW_BYTES + 1];
        put(new SecretRows.Stored(new SecretRows.Meta("long", m.kind(), m.format(), m.keyId(), new byte[12], sealed.length, m.createdAt(),
                m.changedAt(), m.changedBy()), sealed));
        Node c = start("c", K1);
        assertEquals(Optional.of("sealed-value"), read(c, "db"));
        assertEquals(Optional.empty(), read(c, "long"));
        assertTrue(c.warnings.stream().anyMatch(w -> w.contains("'long'") && w.contains(String.valueOf(SecretStore.MAX_ROW_BYTES))), c.warnings.toString());
        assertEquals(Health.Status.DEGRADED, c.store.health().status());
    }

    // ------------------------------------------------------------------------------------------------- the start

    @Test
    void aRowUnderAKeyTheNodeDoesNotHoldRefusesItsStart() {
        Node a = start("a", K1);
        create(a, value("db", "one-value"));
        IllegalStateException other = refusedStart("c", K2, null);
        assertTrue(other.getMessage().contains(id(K1)) && other.getMessage().contains("1 rows"), other.getMessage());
        IllegalStateException none = refusedStart("d", null, null);
        assertTrue(none.getMessage().contains("1 rows") && none.getMessage().contains(id(K1)), none.getMessage());
    }

    @Test
    void withNoKeyAnEmptyStoreStartsAndAWriteIsNoKey() {
        Node n = start("n", Map.of("RAIL8_SECRET_DB", "variable-value"));
        written.add("variable-value");
        assertEquals(Optional.of("variable-value"), read(n, "db"));
        SecretRefused r = refused(n.store.create(ALICE, value("db", "nope-value")));
        assertEquals(503, r.status());
        assertEquals("no-key", r.code());
    }

    // ------------------------------------------------------------------------------------------------- the change of the key

    @Test
    void duringTheRollWritesSucceedOnTheNodesNotYetRolled() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "before-value"));
        b = restart(b, K2, K1);
        assertEquals(Optional.of("before-value"), read(b, "db"), "the rolled node opens the rows under its previous key");
        create(a, value("on-a", "a-value"));
        SecretRefused r = refused(b.store.create(ALICE, value("on-b", "b-value")));
        assertEquals("keys-differ", r.code());
        assertEquals(409, r.status());
        assertEquals(List.of("a"), r.values().getJsonArray("nodes").getList());
        a = restart(a, K2, K1);
        create(b, value("on-b", "b-value"));
        awaitNotices();
        assertEquals(Optional.of("b-value"), read(a, "on-b"));
        assertNoValueCaptured();
    }

    @Test
    void onceEveryNodeNamesTheNewKeyTheTicksSealAgainInBatches() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        for (int i = 0; i <= SecretStore.BATCH; i++) create(a, value("s" + i, "value-" + i));
        Instant changed = stored().get("s0").meta().changedAt();
        b = restart(b, K2, K1);
        a.tick();
        assertTrue(stored().values().stream().allMatch(r -> Arrays.equals(r.meta().keyId(), Seal.keyId(K1))), "not while a node names the old key");

        a = restart(a, K2, K1);
        a.tick();
        long under = stored().values().stream().filter(r -> Arrays.equals(r.meta().keyId(), Seal.keyId(K2))).count();
        assertEquals(SecretStore.BATCH, under, "one batch a tick");
        assertEquals(List.of(), audit("secret.key-changed"), "not before the last row");
        a.tick();
        assertTrue(stored().values().stream().allMatch(r -> Arrays.equals(r.meta().keyId(), Seal.keyId(K2))));
        assertEquals(changed, stored().get("s0").meta().changedAt(), "sealing again keeps who changed it last and when");
        List<JsonObject> changedKey = audit("secret.key-changed");
        assertEquals(1, changedKey.size());
        assertEquals(new JsonObject().put("oldKey", id(K1)).put("newKey", id(K2)).put("rows", SecretStore.BATCH + 1), changedKey.getFirst());
        b.tick();
        awaitNotices();
        assertEquals(Optional.of("value-0"), read(b, "s0"));
        assertEquals(Optional.of("value-" + SecretStore.BATCH), read(a, "s" + SecretStore.BATCH));
        assertEquals(1, audit("secret.key-changed").size(), "written once");
        assertNoValueCaptured();
    }

    @Test
    void aNodeWithoutTheOldKeyRefusesToStartWhileRowsRemainUnderIt() {
        Node a = start("a", K1);
        create(a, value("db", "old-value"));
        a.store.close();
        IllegalStateException r = refusedStart("a", K2, null);
        assertTrue(r.getMessage().contains(id(K1)), r.getMessage());
    }

    @Test
    void thePreviousKeyIsDroppedOnceNoRowNeedsIt() {
        Node a = start("a", K1);
        Node b = start("b", K1);
        create(a, value("db", "the-value"));
        SecretStore.Binding old = bind(a, "a setting");
        a = restart(a, K2, K1);
        b = restart(b, K2, K1);
        long[] boundUnderOld = {1};
        a.store.register(new SecretStore.BoundRows() {
            @Override public Future<Long> countUnder(io.vertx.sqlclient.SqlConnection tx, byte[] keyId) {
                return Future.succeededFuture(Arrays.equals(keyId, Seal.keyId(K1)) ? boundUnderOld[0] : 0);
            }
            @Override public Future<Integer> bindAgain(io.vertx.sqlclient.SqlConnection tx, int max) { return Future.succeededFuture(0); }
        });
        a.tick();
        a.tick();
        assertTrue(a.store.verify(old, "a setting".getBytes()), "kept while a bound row is under it");
        assertEquals(Health.Status.UP, a.store.health().status());
        boundUnderOld[0] = 0;
        a.tick();
        assertFalse(a.store.verify(old, "a setting".getBytes()), "dropped once nothing is under it");
        assertEquals(Health.Status.DEGRADED, a.store.health().status(), "while its variable is still set");
        assertTrue(a.warnings.stream().anyMatch(w -> w.contains(StoreKey.PREVIOUS)));
        b.tick();
        assertFalse(b.store.verify(old, "a setting".getBytes()), "the other node drops it too");
        assertTrue(a.store.verify(bind(a, "a setting"), "a setting".getBytes()));
        assertFalse(a.store.verify(bind(a, "a setting"), "another setting".getBytes()));
    }

    @Test
    void aForgedNodesRowHoldsWritesBackAndChangesNoSeal() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        forge("forged", Seal.keyId(K9));
        assertEquals("keys-differ", refused(a.store.create(ALICE, value("other", "other-value"))).code());
        a.tick();
        assertTrue(stored().values().stream().allMatch(r -> Arrays.equals(r.meta().keyId(), Seal.keyId(K1))), "every row stays under the node's key");
        Throwable bound = assertThrows(AssertionError.class, () -> bind(a, "a setting")).getCause();
        assertTrue(bound instanceof SecretRefused, String.valueOf(bound));
        assertEquals("keys-differ", ((SecretRefused) bound).code(), "a binding makes a write's key check");
        leave("forged");
        create(a, value("other", "other-value"));
        assertTrue(Arrays.equals(stored().get("other").meta().keyId(), Seal.keyId(K1)), "a node seals under its own key");
    }

    @Test
    void aForgedNodesRowCanLetThroughAWriteARealNodeHoldsUnreadable() {
        Node a = start("a", K1);
        Node b = start("b", K2);
        assertEquals("keys-differ", refused(a.store.create(ALICE, value("db", "the-value"))).code(), "b does not hold a's key");
        forge("b", Seal.keyId(K1));
        create(a, value("db", "the-value"));
        awaitNotices();
        b.tick();
        assertEquals(Optional.empty(), read(b, "db"), "b cannot open it");
        assertEquals(Health.Status.DEGRADED, b.store.health().status());
        assertTrue(b.warnings.stream().anyMatch(w -> w.contains("'db'") && w.contains(id(K1))), b.warnings.toString());
        assertNoValueCaptured();
    }

    // ------------------------------------------------------------------------------------------------- the writes' codes

    @Test
    void eachRefusalGivesItsCode() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        assertEquals("exists", refused(a.store.create(ALICE, value("db", "again-value"))).code());
        assertEquals("not-found", refused(a.store.overwrite(ALICE, "nothing", value("nothing", "x-value"))).code());
        assertEquals(404, refused(a.store.delete(ALICE, "nothing", Optional.empty())).status());
        assertEquals("kind-differs", refused(a.store.overwrite(ALICE, "db", credentials("db", "user", "pass-word"))).code());
        use("login", "flow", "pkg/orders", Set.of(Secret.Kind.VALUE));
        SecretRefused kind = refused(a.store.create(ALICE, credentials("login", "user", "pass-word")));
        assertEquals("kind-in-use", kind.code());
        assertEquals("flow", kind.values().getJsonObject("user").getString("kind"));
        assertEquals("reserved", refused(a.store.create(ALICE, value("rail8.other", "x-value"))).code());
        assertEquals("invalid", refused(a.store.create(ALICE, value("Not A Name", "x-value"))).code());
        assertEquals("invalid", refused(a.store.create(ALICE, value("n".repeat(129), "x-value"))).code());
        assertEquals("invalid", refused(a.store.create(ALICE, value("big", "v".repeat(SecretStore.MAX_VALUE_BYTES + 1)))).code());
        SecretRefused user = refused(a.store.create(ALICE, credentials("c", "u".repeat(SecretStore.MAX_USER + 1), "pass-word")));
        assertEquals("user", user.values().getString("field"));
        assertEquals("kind", refused(a.store.create(ALICE, new JsonObject().put("name", "k").put("kind", "token"))).values().getString("field"));
        create(a, value("big", "v".repeat(SecretStore.MAX_VALUE_BYTES)));

        auditFails(true);
        SecretRefused audit = refused(a.store.overwrite(ALICE, "db", value("db", "unaudited-value")));
        assertEquals(503, audit.status());
        assertEquals("audit-unavailable", audit.code());
        auditFails(false);
        assertEquals(Optional.of("the-value"), read(a, "db"), "a change whose audit row fails does not happen");
        assertNoValueCaptured();
    }

    @Test
    void aDeleteOfANameADrainingFlowUsesIsInUse() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        use("db", "drain", "pkg/orders", Set.of(Secret.Kind.VALUE));
        SecretRefused drain = refused(a.store.delete(ALICE, "db", Optional.empty()));
        assertEquals(409, drain.status());
        assertEquals("in-use", drain.code());
        assertEquals(new JsonObject().put("kind", "drain").put("id", "pkg/orders").put("kinds", new JsonArray().add("value")),
                drain.values().getJsonArray("usedBy").getJsonObject(0));
        assertEquals(Optional.of("the-value"), read(a, "db"), "the secret stays");
    }

    @Test
    void aDeleteOfANameInUseNeedsTheConfirmation() {
        Node a = start("a", K1);
        create(a, credentials(SecretStore.GIT, "git-user", "git-token"));
        SecretRefused git = refused(a.store.delete(ALICE, SecretStore.GIT, Optional.empty()));
        assertEquals("in-use", git.code());
        assertEquals("platform", git.values().getJsonArray("usedBy").getJsonObject(0).getString("kind"));
        assertEquals("in-use", refused(a.store.delete(ALICE, SecretStore.GIT, Optional.of("rail8"))).code(), "the confirmation names it whole");
        await(a.store.delete(ALICE, SecretStore.GIT, Optional.of(SecretStore.GIT)));

        create(a, value("db", "the-value"));
        unknownRecord("old-package");
        SecretRefused may = refused(a.store.delete(ALICE, "db", Optional.empty()));
        assertEquals("in-use", may.code());
        assertEquals(List.of("old-package"), may.values().getJsonArray("mayUse").getList(), "a record whose uses are not known may use it");
        await(a.store.delete(ALICE, "db", Optional.of("db")));
        JsonObject deleted = audit("secret.delete").getLast();
        assertEquals(new JsonObject().put("kind", "value").put("confirmed", true).put("users", new JsonArray()).put("userCount", 0), deleted);
        assertEquals(new JsonObject().put("kind", "credentials"), audit("secret.create").getFirst(), "the detail names the kind, never the user");
        assertNoValueCaptured();
    }

    @Test
    void anInUseAnswerAndADeletesAuditNameAHundredUsersAndTheirCount() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        for (int i = 0; i <= SecretStore.MAX_USERS_NAMED; i++) use("db", "flow", "pkg/f" + String.format("%03d", i), Set.of(Secret.Kind.VALUE));
        SecretRefused r = refused(a.store.delete(ALICE, "db", Optional.empty()));
        assertEquals(SecretStore.MAX_USERS_NAMED, r.values().getJsonArray("usedBy").size());
        assertEquals(SecretStore.MAX_USERS_NAMED + 1, r.values().getInteger("users"));
        await(a.store.delete(ALICE, "db", Optional.of("db")));
        JsonObject deleted = audit("secret.delete").getLast();
        assertEquals(SecretStore.MAX_USERS_NAMED, deleted.getJsonArray("users").size());
        assertEquals(SecretStore.MAX_USERS_NAMED + 1, deleted.getInteger("userCount"));
    }

    @Test
    void aNameIsShadowedByTheVariableItShares() {
        Node a = start("a", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_A_B", "variable-value"));
        written.add("variable-value");
        create(a, value("a.b", "store-value"));
        JsonObject all = list(a);
        assertEquals(List.of("a"), all.getJsonArray("secrets").getJsonObject(0).getJsonArray("shadowedOn").getList());
        assertEquals(0, all.getJsonArray("variablesOnly").size(), "the variable a stored name takes is not listed apart");
        assertNoValueCaptured();
    }

    @Test
    void afterALostKeyTheRowsDeletedInSqlLeaveTheNamesMissingWithTheirUsers() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        use("db", "flow", "pkg/orders", Set.of(Secret.Kind.VALUE));
        a.store.close();
        refusedStart("a", K2, null);
        SecretRows.Head was = head();
        restore(new SecretRows.Head(was.generation() + 1, was.stamp() + 1), Map.of());   // delete from secrets, the generation and stamp raised
        Node again = start("a", K2);
        JsonObject missing = list(again).getJsonArray("missing").stream().map(JsonObject.class::cast)
                .filter(e -> e.getString("name").equals("db")).findFirst().orElseThrow();
        assertEquals("pkg/orders", missing.getJsonArray("usedBy").getJsonObject(0).getString("id"));
        create(again, value("db", "entered-again"));
        assertEquals(Optional.of("entered-again"), read(again, "db"));
    }

    @Test
    void theListCountsTheVariablesOfJoinedNodesOnly() {
        Node a = start("a", K1);
        start("b", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_ON_B", "variable-value"));
        written.add("variable-value");
        assertEquals("RAIL8_SECRET_ON_B", list(a).getJsonArray("variablesOnly").getJsonObject(0).getString("variable"));
        join("b");   // b's epoch moves on without its join to the store: a node that left and came back, not joined yet
        JsonObject all = list(a);
        assertEquals(0, all.getJsonArray("variablesOnly").size(), "a node not joined counts no variable");
        JsonObject b = all.getJsonObject("key").getJsonArray("nodes").stream().map(JsonObject.class::cast)
                .filter(n -> n.getString("node").equals("b")).findFirst().orElseThrow();
        assertFalse(b.getBoolean("joined"));
    }

    @Test
    void theListAnswersNamesKindsUsesAndTheKey() {
        Node a = start("a", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_ONLY_HERE", "variable-value"));
        written.add("variable-value");
        create(a, value("db", "the-value"));
        use("db", "flow", "pkg/orders", Set.of(Secret.Kind.VALUE));
        use("absent", "flow", "pkg/orders", Set.of(Secret.Kind.CREDENTIALS));
        JsonObject all = list(a);
        JsonObject db = all.getJsonArray("secrets").getJsonObject(0);
        assertEquals("db", db.getString("name"));
        assertEquals("value", db.getString("kind"));
        assertEquals("installation", db.getString("scope"));
        assertEquals("alice", db.getString("changedBy"));
        assertEquals(new JsonObject().put("kind", "flow").put("id", "pkg/orders").put("kinds", new JsonArray().add("value")),
                db.getJsonArray("usedBy").getJsonObject(0));
        assertFalse(db.getBoolean("unreadable"));
        List<String> missing = all.getJsonArray("missing").stream().map(o -> ((JsonObject) o).getString("name")).toList();
        assertTrue(missing.contains("absent") && missing.contains(SecretStore.GIT), missing.toString());
        assertEquals("RAIL8_SECRET_ONLY_HERE", all.getJsonArray("variablesOnly").getJsonObject(0).getString("variable"));
        JsonObject key = all.getJsonObject("key");
        assertEquals(id(K1), key.getString("common"));
        assertEquals(0L, key.getLong("rowsUnderAnotherKey"));
        assertFalse(key.getBoolean("previousSet"));
        assertEquals("a", all.getString("node"));
        assertEquals("db", await(a.store.entry("db")).orElseThrow().getString("name"));
        assertEquals("RAIL8_SECRET_ONLY_HERE", await(a.store.entry("only-here")).orElseThrow().getString("variable"));
        assertEquals(Optional.empty(), await(a.store.entry("nobody")));
        assertNoValueCaptured();
    }

    @Test
    void credentialsAndKeyPairsAreReadThroughTheirHandles() {
        Node a = start("a", K1);
        create(a, credentials("login", "the-user", "the-password"));
        Secret login = a.secrets.handle("secret://login", Set.of(Secret.Kind.CREDENTIALS));
        assertEquals(new Secret.Credentials("the-user", "the-password"), login.credentials());
        assertTrue(a.secrets.problem("secret://login", Set.of(Secret.Kind.VALUE)).orElseThrow().contains("kind credentials"));
        assertEquals(Optional.empty(), read(a, "login"), "a plain read of credentials gives nothing");

        Secret.KeyPair placeholder = SecretStore.placeholderKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(placeholder.privateKey().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        written.add(Base64.getEncoder().encodeToString(placeholder.privateKey().getEncoded()));
        JsonObject answer = create(a, new JsonObject().put("name", "tls").put("kind", "key-pair").put("privateKey", pem));
        assertEquals("EC", answer.getJsonObject("keyPair").getString("algorithm"));
        Secret tls = a.secrets.handle("secret://tls", Set.of(Secret.Kind.KEY_PAIR, Secret.Kind.VALUE));
        assertEquals(Secret.Kind.KEY_PAIR, tls.kind());
        assertTrue(Arrays.equals(placeholder.publicKey().getEncoded(), tls.keyPair().publicKey().getEncoded()));
        JsonObject detail = audit("secret.create").getLast();
        assertEquals(answer.getJsonObject("keyPair").getString("fingerprint"), detail.getString("fingerprint"));
        assertNoValueCaptured();
    }

    // ------------------------------------------------------------------------------------------------- no value anywhere

    /** No captured text holds a value written, as text, URL-encoded, JSON-escaped or in base64. */
    void assertNoValueCaptured() {
        for (JsonObject detail : allAudit()) captured.add(detail.encode());
        for (String value : written) {
            List<String> forms = new ArrayList<>(List.of(value, java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8),
                    new JsonArray().add(value).encode().substring(2).replaceAll("\"]$", ""),
                    Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            for (String text : captured) {
                for (String form : forms) {
                    if (form.length() >= 6 && text.contains(form)) fail("a captured text holds a value written: " + text.replace(form, "<the value>"));
                }
            }
        }
    }

    /** Every audit row's detail. */
    List<JsonObject> allAudit() {
        List<JsonObject> all = new ArrayList<>();
        for (String action : List.of("secret.create", "secret.overwrite", "secret.delete", "secret.key-changed")) all.addAll(audit(action));
        return all;
    }
}
