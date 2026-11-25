package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres store passes the {@link SecretStoreContract}: two stores over one database stand for two nodes, and each hears
 * the {@code pg_notify} of the writes on a {@code LISTEN} connection of the test. Beside the contract: a join that races a write,
 * and the record of uses with its names' locks.
 */
class PgSecretStoreIT extends SecretStoreContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static PgConnectOptions connect;

    PgConnection listen;
    final List<SecretStore> hearing = new CopyOnWriteArrayList<>();
    final List<Future<Void>> looks = new CopyOnWriteArrayList<>();
    final Map<String, Promise<Void>> syncs = new java.util.concurrent.ConcurrentHashMap<>();
    final AtomicInteger sync = new AtomicInteger();
    volatile boolean drop;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        sql("truncate secrets, secret_uses, audit, deployments cascade");
        sql("delete from nodes");
        sql("update secret_store set generation = 0, stamp = 1");
        sql("insert into secret_uses values ('installation', 'rail8.git', 'platform', 'git', '{credentials}')");
        listen = PgConnection.connect(vertx, connect).await();
        listen.notificationHandler(n -> {
            if (!n.getChannel().equals(SecretStore.CHANNEL)) return;
            Promise<Void> synced = syncs.remove(n.getPayload());
            if (synced != null) {
                synced.complete();
                return;
            }
            if (drop) return;
            for (SecretStore store : hearing) looks.add(store.look());
        });
        listen.query("LISTEN " + SecretStore.CHANNEL).execute().await();
    }

    @AfterEach
    void close() {
        opened.forEach(c -> c.close().await());
        hearing.forEach(SecretStore::close);
        listen.close().await();
    }

    static List<Row> sql(String sql, Object... args) {
        List<Row> rows = new ArrayList<>();
        pool.preparedQuery(sql).execute(Tuple.from(args)).await().forEach(rows::add);
        return rows;
    }

    /** Short, so the test of a write that waits on a lock is quick. */
    static final java.time.Duration LOCK_WAIT = java.time.Duration.ofSeconds(1);

    @Override SecretRows rows() { return new PgSecretRows(pool, LOCK_WAIT); }

    @Override void put(SecretRows.Stored row) { insert(row); }

    @Override <T> T inTx(java.util.function.Function<SqlConnection, Future<T>> work) {
        return await(pool.withTransaction(work::apply));
    }

    @Override long join(String node) {
        return sql("insert into nodes (node, epoch, lease_until) values ($1, 1, now() + interval '1 hour') on conflict (node) do update"
                + " set epoch = nodes.epoch + 1, lease_until = excluded.lease_until returning epoch", node).getFirst().getLong(0);
    }

    @Override void leave(String node) { sql("update nodes set lease_until = now() - interval '1 hour' where node = $1", node); }

    @Override void hear(SecretStore store) { hearing.add(store); }

    @Override void awaitNotices() {
        String payload = "sync-" + sync.incrementAndGet();
        Promise<Void> synced = Promise.promise();
        syncs.put(payload, synced);
        sql("select pg_notify('" + SecretStore.CHANNEL + "', $1)", payload);
        await(synced.future());   // every notice committed before it has been handed on
        for (Future<Void> look : List.copyOf(looks)) await(look);
    }

    @Override void dropNotices(boolean drop) { this.drop = drop; }

    @Override Map<String, SecretRows.Stored> stored() {
        Map<String, SecretRows.Stored> rows = new TreeMap<>();
        for (Row r : sql("select name, kind, format, key_id, nonce, length(sealed), created_at, changed_at, changed_by, sealed from secrets")) {
            rows.put(r.getString(0), new SecretRows.Stored(new SecretRows.Meta(r.getString(0), PgSecretRows.kind(r.getString(1)), r.getShort(2),
                    r.getBuffer(3).getBytes(), r.getBuffer(4).getBytes(), r.getInteger(5), r.getOffsetDateTime(6).toInstant(),
                    r.getOffsetDateTime(7).toInstant(), r.getString(8)), r.getBuffer(9).getBytes()));
        }
        return rows;
    }

    @Override SecretRows.Head head() {
        Row r = sql("select generation, stamp from secret_store").getFirst();
        return new SecretRows.Head(r.getLong(0), r.getLong(1));
    }

    @Override void restore(SecretRows.Head head, Map<String, SecretRows.Stored> rows) {
        sql("update secret_store set generation = $1, stamp = $2", head.generation(), head.stamp());
        sql("delete from secrets");
        rows.values().forEach(PgSecretStoreIT::insert);
    }

    static void insert(SecretRows.Stored row) {
        SecretRows.Meta m = row.meta();
        sql("insert into secrets values ('installation', $1, $2, $3, $4, $5, $6, $7, $8, $9)", m.name(), m.kind().id(), (short) m.format(),
                Buffer.buffer(m.keyId()), Buffer.buffer(m.nonce()), Buffer.buffer(row.sealed()), m.createdAt().atOffset(ZoneOffset.UTC),
                m.changedAt().atOffset(ZoneOffset.UTC), m.changedBy());
    }

    @Override void tamper(String name) { sql("update secrets set sealed = set_byte(sealed, 0, get_byte(sealed, 0) # 1) where name = $1", name); }

    @Override void forge(String node, byte[] keyId) {
        sql("insert into nodes (node, epoch, lease_until, secret_key, secret_epoch) values ($1, 1, now() + interval '1 hour', $2, 1)"
                + " on conflict (node) do update set secret_key = excluded.secret_key, secret_key_previous = null, secret_epoch = nodes.epoch,"
                + " lease_until = excluded.lease_until", node, Buffer.buffer(keyId));
    }

    @Override void use(String name, String userKind, String userId, Set<Secret.Kind> kinds) {
        sql("insert into secret_uses values ('installation', $1, $2, $3, $4)", name, userKind, userId, kinds.stream().map(Secret.Kind::id).toArray(String[]::new));
    }

    @Override void unknownRecord(String pkg) {
        sql("insert into deployments (package, version, repository, commit, content, deployed_at, at_least_once)"
                + " values ($1, '1.0.0', $1, $2, $3, now(), '{}')", pkg, "0".repeat(40), "sha256:" + "0".repeat(64));
    }

    @Override void auditFails(boolean fails) {
        sql(fails ? "alter table audit add constraint no_audit_now check (false) not valid" : "alter table audit drop constraint no_audit_now");
    }

    @Override List<JsonObject> audit(String action) {
        return sql("select detail from audit where action = $1 order by id", action).stream().map(r -> (JsonObject) r.getValue(0)).toList();
    }

    // ------------------------------------------------------------------------------------------------- only in Postgres

    @Test
    void aJoinThatRacesAWriteSeesTheRow() {
        SqlConnection writer = pool.getConnection().await();
        Transaction tx = writer.begin().await();
        writer.query("select generation from secret_store for update").execute().await();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        writer.preparedQuery("insert into secrets values ('installation', 'raced', 'value', 1, $1, $2, $3, now(), now(), 'alice')")
                .execute(Tuple.of(Buffer.buffer(Seal.keyId(K9)), Buffer.buffer(nonce), Buffer.buffer(new byte[272]))).await();
        java.util.concurrent.CompletableFuture<IllegalStateException> refused =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> refusedStart("c", K1, null));
        tx.commit().await();
        writer.close().await();
        assertTrue(refused.join().getMessage().contains(id(K9)), "the join waits on the store's lock and sees the row");
    }

    /** {@code replaceUses} of {@code uses} for {@code user} in a transaction of its own, left open. */
    record Open(SqlConnection conn, Transaction tx, Future<List<String>> problems) { }

    final List<SqlConnection> opened = new CopyOnWriteArrayList<>();

    Open replace(SecretStore store, String user, Map<String, Set<Secret.Kind>> uses) {
        SqlConnection conn = pool.getConnection().await();
        opened.add(conn);
        Transaction tx = conn.begin().await();
        return new Open(conn, tx, store.replaceUses(conn, List.of(new SecretStore.Uses("flow", user, uses))));
    }

    static void end(Open open) {
        open.tx().commit().await();
    }

    @Test
    void aWriteThatWaitsOnABindingsOpenTransactionIsBusy() {
        Node a = start("a", K1);
        SqlConnection binder = pool.getConnection().await();
        opened.add(binder);
        Transaction tx = binder.begin().await();
        await(a.store.bind(binder, "a setting".getBytes()));
        SecretRefused r = refused(a.store.create(ALICE, value("db", "the-value")));
        assertEquals(503, r.status());
        assertEquals("busy", r.code(), "the binding holds the store's row for share until its transaction ends");
        tx.commit().await();
        create(a, value("db", "the-value"));
        assertNoValueCaptured();
    }

    @Test
    void thePreviousKeyIsNotDroppedWhileABindingsTransactionIsOpen() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        a = restart(a, K2, K1);
        SqlConnection binder = pool.getConnection().await();
        opened.add(binder);
        Transaction tx = binder.begin().await();
        SecretStore.Binding open = await(a.store.bind(binder, "a setting".getBytes()));
        a.clock.advance(SecretStore.TICK);
        assertTrue(awaitFailure(a.store.ticked()) instanceof SecretRefused busy && busy.code().equals("busy"), "the tick's work waits on the binding");
        assertEquals(1L, sql("select count(*) from nodes where node = 'a' and secret_key_previous is not null").getFirst().getLong(0),
                "the previous key is still held while the binding's transaction is open");
        tx.commit().await();
        a.tick();
        assertEquals(0L, sql("select count(*) from nodes where node = 'a' and secret_key_previous is not null").getFirst().getLong(0));
        assertTrue(a.store.verify(open, "a setting".getBytes()), "the binding made in the open transaction is under the current key");
    }

    @Test
    void aDeleteThatRacesADeployEndsConsistent() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        Open deploy = replace(a.store, "pkg/orders", Map.of("db", Set.of(Secret.Kind.VALUE)));
        assertEquals(List.of(), await(deploy.problems()));
        Future<Void> deleted = a.store.delete(ALICE, "db", java.util.Optional.empty());
        end(deploy);
        SecretRefused r = refused(deleted);
        assertEquals("in-use", r.code(), "the delete waited on the name's lock and saw the use");
        assertEquals(List.of("pkg/orders"), sql("select user_id from secret_uses where name = 'db'").stream().map(x -> x.getString(0)).toList());
    }

    @Test
    void aDeployThatRacesADeleteEndsConsistent() {
        Node a = start("a", K1);
        create(a, value("db", "the-value"));
        SqlConnection deleter = pool.getConnection().await();
        Transaction tx = deleter.begin().await();
        int key = deleter.query("select hashtext('installation' || chr(31) || 'db')").execute().await().iterator().next().getInteger(0);
        deleter.query("select pg_advisory_xact_lock(297, " + key + ")").execute().await();
        deleter.query("delete from secrets where name = 'db'").execute().await();
        Open deploy = replace(a.store, "pkg/orders", Map.of("db", Set.of(Secret.Kind.VALUE)));
        assertFalse(deploy.problems().isComplete(), "the deploy waits on the name's lock");
        tx.commit().await();
        deleter.close().await();
        List<String> problems = await(deploy.problems());
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("secret://db") && problems.getFirst().contains("not set"), problems.getFirst());
        end(deploy);
        assertEquals(0, sql("select count(*) from secret_uses where name = 'db'").getFirst().getLong(0), "no use of a name that is gone");
    }

    @Test
    void twoWritersWhoseNamesShareALockKeyDoNotDeadlock() {
        Node a = start("a", K1);
        Row pair = sql("select min(n), max(n) from (select 'n' || g n, hashtext('installation' || chr(31) || 'n' || g) k from generate_series(1, 400000) g) s"
                + " group by k having count(*) = 2 limit 1").getFirst();
        String p = pair.getString(0);
        String q = pair.getString(1);
        String r = p + "0";
        assertTrue(p.compareTo(r) < 0 && r.compareTo(q) < 0, "the names' order differs from their keys' order: " + p + " " + r + " " + q);
        for (String name : List.of(p, q, r)) create(a, value(name, "value-of-" + name));
        int rKey = sql("select hashtext('installation' || chr(31) || $1)", r).getFirst().getInteger(0);

        SqlConnection holder = pool.getConnection().await();
        holder.query("select pg_advisory_lock(297, " + rKey + ")").execute().await();
        Open one = replace(a.store, "pkg/one", Map.of(p, Set.of(Secret.Kind.VALUE), r, Set.of(Secret.Kind.VALUE)));
        Open two = replace(a.store, "pkg/two", Map.of(q, Set.of(Secret.Kind.VALUE), r, Set.of(Secret.Kind.VALUE)));
        holder.query("select pg_advisory_unlock(297, " + rKey + ")").execute().await();
        holder.close().await();
        assertEquals(List.of(), await(one.problems()));
        end(one);
        assertEquals(List.of(), await(two.problems()), "the second waited and went on: no deadlock");
        end(two);
        assertEquals(4L, sql("select count(*) from secret_uses where user_kind = 'flow'").getFirst().getLong(0));
    }

    @Test
    void replaceUsesChecksKindsAndVariablesAndDeletesWithAnEmptyMap() {
        Node a = start("a", Map.of(StoreKey.VARIABLE, b64(K1), "RAIL8_SECRET_FROM_ENV", "env-value"));
        written.add("env-value");
        create(a, credentials("login", "user", "the-password"));
        Open first = replace(a.store, "pkg/orders", Map.of("login", Set.of(Secret.Kind.VALUE), "from-env", Set.of(Secret.Kind.VALUE),
                "from-env-typed", Set.of(Secret.Kind.CREDENTIALS)));
        List<String> problems = await(first.problems());
        end(first);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(t -> t.contains("secret://login") && t.contains("credentials")), problems.toString());
        assertTrue(problems.stream().anyMatch(t -> t.contains("secret://from-env-typed") && t.contains("not set")), problems.toString());
        assertEquals(0L, sql("select count(*) from secret_uses where user_kind = 'flow'").getFirst().getLong(0), "nothing written with a problem");

        Open second = replace(a.store, "pkg/orders", Map.of("login", Set.of(Secret.Kind.CREDENTIALS), "from-env", Set.of(Secret.Kind.VALUE)));
        assertEquals(List.of(), await(second.problems()));
        end(second);
        assertEquals(2L, sql("select count(*) from secret_uses where user_kind = 'flow'").getFirst().getLong(0));
        Open gone = replace(a.store, "pkg/orders", Map.of());
        assertEquals(List.of(), await(gone.problems()));
        end(gone);
        assertEquals(0L, sql("select count(*) from secret_uses where user_kind = 'flow'").getFirst().getLong(0), "an empty map deletes them");
        assertNoValueCaptured();
    }
}
