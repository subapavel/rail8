package dev.rail8.runtime.deploy;

import dev.rail8.api.Secret;
import dev.rail8.core.audit.Actor;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.queue.NotAdmittedException;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres record store passes the store contract over the {@code deployments} table, on a container whose database collates
 * en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too. {@link #aDeleteRemovesTheTableRow} reads the
 * table itself, so a store that only hides the row from its own {@code list} shows; {@link #aLockIsFreedWithItsConnection} closes a
 * holder's connection under it. A work queue built with admission admits a write for a flow a record runs at-least-once, a mark
 * retires or the node names, and refuses the rest; a mark ends only once no work row of its flow is left and no write admitted for
 * it is open.
 */
class PgDeploymentStoreIT extends DeploymentStoreContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static PgConnectOptions connect;
    /** The connections the stores opened for their locks, latest last. */
    static final List<SqlConnection> opened = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        // fixture: the database's own order is not String order, so listIsSortedByName is not passed by a plain 'order by'
        String collated = pool.query("select string_agg(n, ',' order by n) from (values ('shop-eu'), ('shopa')) v(n)").execute().await()
                .iterator().next().getString(0);
        assertEquals("shopa,shop-eu", collated, "the container's collation ignores the dash, unlike String.compareTo");
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @Override
    protected DeploymentStore emptyStore() {
        pool.query("truncate deployments, retiring, node_packages, deployment_systems, deployment_connection_uses").execute().await();
        pool.query("delete from nodes").execute().await();
        pool.query("delete from work").execute().await();
        pool.query("delete from audit where action in ('deploy', 'undeploy')").execute().await();
        return store(pool, connect);
    }

    @Override
    protected List<InMemoryDeploymentStore.Audited> audited() {
        List<InMemoryDeploymentStore.Audited> rows = new java.util.ArrayList<>();
        pool.query("select actor, action, subject, detail from audit where action in ('deploy', 'undeploy') order by id").execute().await()
                .forEach(r -> rows.add(new InMemoryDeploymentStore.Audited(r.getString(0), r.getString(1), r.getString(2), new JsonObject(r.getValue(3).toString()))));
        return rows;
    }

    /**
     * A save and a delete whose audit row the database refuses fail and write nothing: the record, the marks and the generation
     * stay as they were, and the lock writes on once the row can be written.
     */
    @Test
    void aWriteWhoseAuditRowIsRefusedWritesNothing() {
        DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
        save(shop);
        DeploymentStore.Snapshot before = store.read().await();
        DeploymentStore.PoolLock lock = lock();
        try {
            pool.query("alter table audit add constraint no_deploy_rows check (action not in ('deploy', 'undeploy')) not valid").execute().await();
            try {
                assertTrue(failsLater(lock.save(record("shop", "2.0.0", C_2, SHA_2, AT), BY, new JsonObject())), "the save fails with its row");
                assertTrue(failsLater(lock.delete("shop", BY, new JsonObject())), "the delete fails with its row");
            } finally {
                pool.query("alter table audit drop constraint no_deploy_rows").execute().await();
            }
            assertEquals(before, store.read().await(), "nothing written: the record, its marks and the generation as they were");
            assertEquals(before.generation() + 1, lock.delete("shop", BY, new JsonObject()).await(), "the lock writes on once the row can be written");
        } finally {
            lock.close().await();
        }
    }

    @Override
    protected long join(String node) {
        return pool.preparedQuery("insert into nodes (node, epoch, lease_until) values ($1, 1, now() + interval '30 seconds') "
                + "on conflict (node) do update set epoch = nodes.epoch + 1, lease_until = excluded.lease_until returning epoch")
                .execute(io.vertx.sqlclient.Tuple.of(node)).await().iterator().next().getLong(0);
    }

    @Override
    protected void expire(String node) {
        pool.preparedQuery("update nodes set lease_until = now() - interval '1 second' where node = $1").execute(io.vertx.sqlclient.Tuple.of(node)).await();
    }

    @Override
    protected void release(String node) {
        pool.preparedQuery("update nodes set lease_until = null where node = $1").execute(io.vertx.sqlclient.Tuple.of(node)).await();
    }

    /** The rows of {@code node_packages} themselves, {@code <node>/<epoch>/<package>}, sorted. */
    static List<String> nodeRows() {
        List<String> rows = new java.util.ArrayList<>();
        pool.query("select r from (select node || '/' || epoch || '/' || package as r from node_packages) t order by r collate \"C\"").execute().await()
                .forEach(r -> rows.add(r.getString(0)));
        return rows;
    }

    /** A node's write deletes the rows of every node whose epoch moved on or whose lease was released, and nobody else's. */
    @Test
    void aWriteSweepsTheRowsOfReleasedNodesAndOfOldEpochs() {
        long a = join("node-a");
        long b = join("node-b");
        long c = join("node-c");
        assertTrue(write("node-a", a, running("shop", C_1)));
        assertTrue(write("node-b", b, running("shop", C_1)));
        assertTrue(write("node-c", c, running("shop", C_1)));
        expire("node-a");
        release("node-a");
        join("node-b");   // restarted, not written yet
        expire("node-c");   // ended, released by nobody: still its node's
        assertEquals(List.of("node-a/1/shop", "node-b/1/shop", "node-c/1/shop"), nodeRows(), "fixture: rows stay until a write");

        long d = join("node-d");
        assertTrue(write("node-d", d, running("orders", C_2)));
        assertEquals(List.of("node-c/1/shop", "node-d/1/orders"), nodeRows(), "the released node's rows and the old epoch's went; the ended lease's stayed");
    }

    /**
     * A write holds its node's row of {@code nodes} so that a join of the node waits for it, and never so that the node's renew or a
     * peer's release of its lease waits: held back mid-write by a lock on {@code node_packages}, the renew, the lease's end and its
     * release each answer at once, and the join is held until the write ends.
     */
    @Test
    void aWriteHoldsBackAJoinOfItsNodeButNeverARenewOrARelease() {
        long a = join("node-a");
        SqlConnection holder = PgConnection.connect(vertx, connect).await();
        SqlConnection other = PgConnection.connect(vertx, connect).await();
        try {
            Transaction locked = holder.begin().await();
            holder.query("lock table node_packages in exclusive mode").execute().await();
            io.vertx.core.Future<Boolean> writing = store.writeListing("node-a", a, List.of(running("shop", C_1)));
            for (int i = 0; i < 10_000 && waiting() == 0; i++) {
                assertTrue(!writing.isComplete(), "fixture: the write waits for the table lock: " + writing.cause());
            }
            assertEquals(1, waiting(), "fixture: the write waits for the table lock, its node's row held");

            other.query("set statement_timeout = '5s'").execute().await();
            dev.rail8.runtime.node.PgMembership members = new dev.rail8.runtime.node.PgMembership(other);
            assertTrue(members.renew("node-a", a, java.time.Duration.ofSeconds(30)).await().renewed(), "the renew answers while the write holds the row");
            other.query("update nodes set lease_until = now() - interval '1 second' where node = 'node-a'").execute().await();
            assertEquals(List.of("node-a"), members.release(List.of("node-a")).await(), "and so does a peer's release of the ended lease");

            other.query("set statement_timeout = '1s'").execute().await();
            Throwable join = members.join("node-a", java.time.Duration.ofSeconds(30)).transform(done -> io.vertx.core.Future.succeededFuture(done.cause())).await();
            assertTrue(join != null && join.getMessage().contains("statement timeout"), "the join waits for the write: " + join);

            locked.commit().await();
            assertTrue(writing.await(), "the write ends once the table is free: it read the row before the release");
            assertEquals(List.of("node-a/" + a + "/shop"), nodeRows());
        } finally {
            other.close().await();
            holder.close().await();
        }
    }

    /** How many locks of {@code node_packages} are waited for. */
    static int waiting() {
        return pool.query("select count(*)::int from pg_locks where relation = 'node_packages'::regclass and not granted").execute().await()
                .iterator().next().getInteger(0);
    }

    /** A store over {@code on}, its lock on a connection of its own opened with {@code options}, kept in {@link #opened}. */
    static PgDeploymentStore store(Pool on, PgConnectOptions options) {
        return new PgDeploymentStore(on, () -> PgConnection.connect(vertx, options).map(c -> {
            opened.add(c);
            return (SqlConnection) c;
        }));
    }

    /** A lock whose connection closes without freeing it — its node vanished — is freed with the connection: another holder takes it. */
    @Test
    void aLockIsFreedWithItsConnection() {
        DeploymentStore s = emptyStore();
        DeploymentStore.PoolLock held = s.lock().await().orElseThrow();
        assertTrue(s.lock().await().isEmpty(), "fixture: the lock is held");

        opened.get(opened.size() - 2).close().await();   // the holder's connection; the refused one was opened after it

        DeploymentStore.PoolLock next = s.lock().await().orElseThrow(() -> new AssertionError("the lock went with its connection"));
        next.close().await();
        assertTrue(held.save(record("shop", "1.0.0", C_1, SHA_1, AT), BY, new JsonObject()).transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the lost lock writes nothing");
    }

    /**
     * A save and a delete under a lock whose generation another writer raised, by plain SQL, both fail and write nothing: the
     * generation is compared and set in the transaction that writes the row.
     */
    @Test
    void aWriteUnderAGenerationRaisedMeanwhileFailsAndWritesNothing() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        DeploymentStore.PoolLock held = lock();
        long read = held.snapshot().generation();
        pool.query("update deploy_pool set generation = generation + 1").execute().await();

        assertTrue(held.save(record("billing", "1.0.0", C_2, SHA_2, AT), BY, new JsonObject()).transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the save under the stale generation fails");
        assertTrue(held.delete("shop", BY, new JsonObject()).transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the delete under it too");
        held.close().await();

        List<String> rows = new java.util.ArrayList<>();
        pool.query("select package from deployments order by package").execute().await().forEach(row -> rows.add(row.getString(0)));
        assertEquals(List.of("shop"), rows, "no row was written or deleted");
        long now = pool.query("select generation from deploy_pool").execute().await().iterator().next().getLong(0);
        assertEquals(read + 1, now, "only the plain SQL raised the generation");
    }

    /**
     * A save whose configuration was checked at a generation the configuration has since left compares each reached connection and
     * system again: one that is not what the check read fails the save, naming it, and nothing is written. At the generation it
     * checked, the save writes the generation on the record.
     */
    @Test
    void aSaveWhoseReachedConnectionChangedSinceItsCheckWritesNothing() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        DeploymentStore.Snapshot before = store.read().await();
        pool.query("update config_store set generation = 1").execute().await();
        try {
            DeploymentStore.Configuration checked = new DeploymentStore.Configuration(0, List.of(system("shop", "erp")),
                    List.of(use("shop", "shop/orders", "post", "erp/api", "out", java.util.Optional.empty())),
                    Map.of("erp/api", new DeploymentStore.Reached(1, false)), Map.of("erp", true));
            DeploymentStore.PoolLock lock = lock();
            Throwable failed;
            try {
                failed = lock.save(record("shop", "2.0.0", C_2, SHA_2, AT), Map.of(), checked, BY, new JsonObject())
                        .transform(done -> io.vertx.core.Future.succeededFuture(done.cause())).await();
            } finally {
                lock.close().await();
            }
            DeploymentStore.ConfigurationChanged changed = assertInstanceOf(DeploymentStore.ConfigurationChanged.class, failed);
            assertEquals(List.of("connection 'erp/api' changed while the deploy checked it: deploy again",
                    "system 'erp' changed while the deploy checked it: deploy again"), changed.problems());
            assertEquals(before, store.read().await(), "nothing written: the record and the generation as they were");
            assertEquals(List.of(), store.systems().await(), "no system written");
            assertEquals(List.of(), store.connectionUses().await(), "no use written");

            save(record("shop", "2.0.0", C_2, SHA_2, AT), new DeploymentStore.Configuration(1, List.of(), List.of(),
                    Map.of("erp/api", new DeploymentStore.Reached(1, false)), Map.of()));
            long generation = pool.query("select config_generation from deployments where package = 'shop'").execute().await().iterator().next().getLong(0);
            assertEquals(1, generation, "the record carries the generation its deploy checked");
        } finally {
            pool.query("update config_store set generation = 0").execute().await();
        }
    }

    /** delete removes the row from the {@code deployments} table, read with plain SQL: the other package's row stays. */
    @Test
    void aDeleteRemovesTheTableRow() {
        save(record("shop", "1.0.0", SHA_1, List.of(pin("common", "1.2.0", SHA_2, C_2))));
        save(record("common", "1.2.0", C_2, SHA_2, AT));

        delete("shop");

        List<String> left = new java.util.ArrayList<>();
        pool.query("select package from deployments order by package").execute().await().forEach(row -> left.add(row.getString(0)));
        assertEquals(List.of("common"), left, "shop's row is deleted from the table, common's stays");
    }

    /**
     * A write is admitted for the flows a record runs at-least-once, a mark retires or the node runs without a record yet; one that
     * names any other flow is refused with the flows refused. A queue built without admission admits every flow.
     */
    @Test
    void aWriteIsAdmittedOnlyForAFlowThePoolRunsRetiresOrTheNodeNames() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        save(new DeploymentRecord("shop", "2.0.0", "shop", C_2, SHA_2, AT, List.of(), List.of("shop/orders")));
        assertEquals(List.of("shop/audit"), store.read().await().marked(), "fixture: shop/audit is retired");
        PgWorkQueue admitting = new PgWorkQueue(pool, "node-1", null, timer(), () -> Set.of("new/flow"));

        admit(admitting, List.of("shop/orders", "shop/audit", "new/flow"));   // run by a record, retired by a mark, named by the node

        NotAdmittedException refused = assertThrows(NotAdmittedException.class, () -> admit(admitting, List.of("other/x", "shop/orders", "gone/flow")));
        assertEquals(List.of("gone/flow", "other/x"), refused.flows(), "the flows refused, sorted");
        admit(new PgWorkQueue(pool, "node-1", null, timer()), List.of("gone/flow"));   // built without admission
    }

    /**
     * A mark ends only once no work row of its flow is left and no write admitted for it is open: a row keeps it, an open
     * transaction that admitted a message for the flow keeps it, and once that commits the next {@code endMarks} ends it. A write
     * for the flow is then refused.
     */
    @Test
    void aMarkEndsOnceItsRowsAreGoneAndNoAdmittedWriteIsOpen() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        delete("shop");
        PgWorkQueue admitting = new PgWorkQueue(pool, "node-1", null, timer(), Set::of);
        pool.query("insert into work(id, flow, step) values ('m-1', 'shop/audit', 1)").execute().await();

        store.endMarks().await();
        assertEquals(List.of("shop/audit"), store.read().await().marked(), "shop/orders had no row and ends; the row keeps shop/audit");

        pool.query("delete from work where id = 'm-1'").execute().await();
        SqlConnection writer = pool.getConnection().await();
        try {
            Transaction open = writer.begin().await();
            admitting.admit(writer, List.of("shop/audit")).await();   // admitted: it holds the flow's admission lock until it ends

            store.endMarks().await();
            assertEquals(List.of("shop/audit"), store.read().await().marked(), "the open write keeps the mark");

            open.commit().await();
        } finally {
            writer.close().await();
        }
        store.endMarks().await();
        assertEquals(List.of(), store.read().await().marked(), "no row and no open write: the mark ends");
        assertThrows(NotAdmittedException.class, () -> admit(admitting, List.of("shop/audit")), "the flow is no longer admitted");
    }

    /** {@code queue} admits {@code flows} in a transaction of its own, which then commits. */
    static void admit(PgWorkQueue queue, Collection<String> flows) {
        pool.withTransaction(tx -> queue.admit(tx, flows)).await();
    }

    static FakeTimer timer() {
        return new FakeTimer(FakeClock.at(Instant.parse("2026-10-27T09:30:00Z")));
    }

    /**
     * An audit row of a refused deploy: the actor's name, user, via and credential, the action {@code deploy}, the subject
     * {@code repository/<name>} and the detail as given. A save's and a delete's rows carry the user, the via and the credential
     * too.
     */
    @Test
    void anAuditRowIsWritten() {
        DeploymentStore s = emptyStore();
        JsonObject detail = new JsonObject().put("repository", "shop").put("commit", C_1).put("outcome", "refused");
        Actor alice = new Actor("alice", OptionalLong.of(7), "session", Optional.of("0123456789abcdef"), "10.0.0.7");
        s.audit(alice, "shop", detail).await();

        Row row = pool.query("select actor, action, subject, detail, user_id, via, credential from audit where action = 'deploy'").execute()
                .await().iterator().next();
        assertEquals(List.of("alice", "deploy", "repository/shop"), List.of(row.getString(0), row.getString(1), row.getString(2)));
        assertEquals(detail, new JsonObject(row.getValue(3).toString()), "the detail as given");
        assertEquals(List.of(7L, "session", "0123456789abcdef"), List.of(row.getLong(4), row.getString(5), row.getString(6)),
                "the user, how they proved who they are, and the session's id");

        DeploymentStore.PoolLock lock = lock();
        try {
            lock.save(record("shop", "1.0.0", C_1, SHA_1, AT), alice, new JsonObject()).await();
            lock.delete("shop", alice, new JsonObject()).await();
        } finally {
            lock.close().await();
        }
        List<List<Object>> written = new java.util.ArrayList<>();
        pool.query("select action, user_id, via, credential from audit where action in ('deploy', 'undeploy') order by id").execute().await()
                .forEach(r -> written.add(List.of(r.getString(0), r.getLong(1), r.getString(2), r.getString(3))));
        assertEquals(List.of(List.of("deploy", 7L, "session", "0123456789abcdef"), List.of("deploy", 7L, "session", "0123456789abcdef"),
                List.of("undeploy", 7L, "session", "0123456789abcdef")), written, "the save's and the delete's rows name the user, the via and the credential");
    }

    /**
     * A row written at V26 names no at-least-once flows: V27 deletes it and starts the pool's generation at 0, and the store saves
     * and reads rows that name them.
     */
    @Test
    void aRowWrittenBeforeV27IsDeleted() throws Exception {
        String database = "v26_row";
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String url = path.substring(0, path.lastIndexOf('/') + 1) + database + (query < 0 ? "" : base.substring(query));
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("26").load().migrate();
        PgConnectOptions options = new PgConnectOptions(connect).setDatabase(database);
        Pool own = PgBuilder.pool().connectingTo(options).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        try {
            own.query("insert into deployments(package, version, repository, commit, content, deployed_at, dependencies) values ('shop', '1.0.0', 'shop', '"
                    + C_1 + "', '" + SHA_1 + "', timestamptz '2026-10-27 09:30:00.123456+00', '[]'::jsonb)").execute().await();

            Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

            assertEquals("36", r.schemaVersion(), "the migrations after V26 applied over the V26 database");
            PgDeploymentStore store = store(own, options);
            assertEquals(new DeploymentStore.Snapshot(0, List.of()), store.read().await(), "the row is deleted; the generation starts at 0");
            DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
            DeploymentStore.PoolLock lock = store.lock().await().orElseThrow();
            assertEquals(1L, lock.save(shop, BY, new JsonObject()).await());
            lock.close().await();
            assertEquals(new DeploymentStore.Snapshot(1, List.of(shop)), store.read().await(), "a row naming its at-least-once flows saves and reads");
        } finally {
            own.close().await();
        }
    }

    /** The flows' uses of secret names in {@code secret_uses}, {@code <user>:<name>:<kinds>}, sorted. */
    static List<String> uses() {
        List<String> rows = new java.util.ArrayList<>();
        pool.query("select r from (select user_id || ':' || name || ':' || array_to_string(kinds, ',') as r from secret_uses"
                + " where user_kind = 'flow') t order by r collate \"C\"").execute().await().forEach(r -> rows.add(r.getString(0)));
        return rows;
    }

    /** Whether the record of {@code name} has its secret uses written. */
    static boolean known(String name) {
        return pool.preparedQuery("select secret_uses_known from deployments where package = $1").execute(io.vertx.sqlclient.Tuple.of(name)).await()
                .iterator().next().getBoolean(0);
    }

    /**
     * A store whose saves and deletes write the record of uses, checked against a secret store that holds the plain value
     * {@code from-env} and nothing else.
     */
    static PgDeploymentStore storeWithUses() {
        pool.query("delete from secret_uses where user_kind in ('flow', 'drain')").execute().await();
        pool.query("delete from secrets").execute().await();
        pool.query("insert into secrets values ('installation', 'from-env', 'value', 1, '\\x0000000000000000', '\\x000000000000000000000000',"
                + " '\\x00', now(), now(), 'test')").execute().await();
        return storeOn();
    }

    /** A store whose saves and deletes write the record of uses, checked against the secret store's rows. */
    static PgDeploymentStore storeOn() {
        FakeClock clock = FakeClock.at(Instant.parse("2026-10-27T09:30:00Z"));
        StoreKey key = StoreKey.fromEnv(Map.of(StoreKey.VARIABLE, java.util.Base64.getEncoder().encodeToString(new byte[32]))).orElseThrow();
        SecretStore secrets = SecretStore.postgres(pool, key, "node-1", clock, new FakeTimer(clock));
        return new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> (SqlConnection) c), secrets);
    }

    /** Every flow's and draining flow's uses in {@code secret_uses}, {@code <kind> <user>:<name>:<kinds>}, sorted. */
    static List<String> flowAndDrainUses() {
        List<String> rows = new java.util.ArrayList<>();
        pool.query("select r from (select user_kind || ' ' || user_id || ':' || name || ':' || array_to_string(kinds, ',') as r from secret_uses"
                + " where user_kind in ('flow', 'drain')) t order by r collate \"C\"").execute().await().forEach(r -> rows.add(r.getString(0)));
        return rows;
    }

    /**
     * A flow left draining keeps its uses as {@code drain} rows: the save that marks it rewrites its flow rows, a save that runs it
     * at-least-once again deletes them, and so does the end of its mark. A delete marks it once the store no longer holds the
     * name: a drain row carries a use checked at its deploy, and is not checked again.
     */
    @Test
    void aFlowLeftDrainingKeepsItsUsesAsDrainUntilItsMarkEnds() {
        emptyStore();
        PgDeploymentStore s = storeWithUses();
        DeploymentStore.PoolLock lock = s.lock().await().orElseThrow();
        try {
            lock.save(record("shop", "1.0.0", C_1, SHA_1, AT), FROM_ENV, BY, new JsonObject()).await();
            DeploymentRecord auditOnly = new DeploymentRecord("shop", "2.0.0", "shop", C_2, SHA_2, AT, List.of(), List.of("shop/audit"));
            lock.save(auditOnly, Map.of(), BY, new JsonObject()).await();
            assertEquals(List.of("drain shop/orders:from-env:value"), flowAndDrainUses(), "orders leaves draining with its uses");

            lock.save(record("shop", "3.0.0", C_1, SHA_1, AT), FROM_ENV, BY, new JsonObject()).await();
            assertEquals(List.of("flow shop/orders:from-env:value"), flowAndDrainUses(), "orders runs at-least-once again: its drain rows go");
        } finally {
            lock.close().await();
        }
        pool.query("delete from secrets").execute().await();
        DeploymentStore.PoolLock elsewhere = storeOn().lock().await().orElseThrow();
        try {
            elsewhere.delete("shop", BY, new JsonObject()).await();
            assertEquals(List.of("drain shop/orders:from-env:value"), flowAndDrainUses(), "the undeploy leaves orders draining with its uses");
        } finally {
            elsewhere.close().await();
        }
        s.endMarks().await();
        assertEquals(List.of(), flowAndDrainUses(), "the end of its mark deletes its drain rows");
    }

    static final Map<String, Map<String, java.util.Set<Secret.Kind>>> FROM_ENV = Map.of("orders", Map.of("from-env", Set.of(Secret.Kind.VALUE)));

    /**
     * A save with uses writes them and marks its record's uses known in its transaction; a save whose uses name a secret the
     * node does not hold fails with the problem and writes nothing, the record and the uses as they were; a delete removes them.
     */
    @Test
    void aSaveWritesItsUsesOrNothingAndADeleteRemovesThem() {
        emptyStore();
        PgDeploymentStore s = storeWithUses();
        DeploymentStore.PoolLock lock = s.lock().await().orElseThrow();
        try {
            lock.save(record("shop", "1.0.0", C_1, SHA_1, AT), FROM_ENV, BY, new JsonObject()).await();
            assertEquals(List.of("shop/orders:from-env:value"), uses());
            assertTrue(known("shop"), "the record's uses are known");
            DeploymentStore.Snapshot before = s.read().await();

            Throwable refused = lock.save(record("shop", "2.0.0", C_2, SHA_2, AT), Map.of("orders", Map.of("gone", Set.of(Secret.Kind.VALUE))), BY,
                    new JsonObject()).transform(done -> io.vertx.core.Future.succeededFuture(done.cause())).await();
            assertTrue(refused instanceof SecretUsesRefused r && r.problems().size() == 1 && r.problems().getFirst().contains("secret://gone"),
                    "the save fails with the problem: " + refused);
            assertEquals(before, s.read().await(), "nothing written: the record as it was");
            assertEquals(List.of("shop/orders:from-env:value"), uses(), "and its uses");

            lock.delete("shop", BY, new JsonObject()).await();
            assertEquals(List.of(), uses(), "the delete removes the uses");
        } finally {
            lock.close().await();
        }
    }

    /**
     * A restore writes the uses of a record that had none and marks them known; while the pool's lock is held elsewhere it answers
     * false and writes nothing, and it writes nothing for a record whose content is no longer the one restored.
     */
    @Test
    void aRestoreWritesTheUsesOfARecordThatHadNone() {
        DeploymentStore plain = emptyStore();
        save(record("shop", "1.0.0", C_1, SHA_1, AT));   // a record written before the store kept uses
        assertTrue(!known("shop"), "fixture: its uses are not known");
        PgDeploymentStore s = storeWithUses();

        DeploymentStore.PoolLock held = plain.lock().await().orElseThrow();
        try {
            assertEquals(false, s.backfillUses("shop", SHA_1, FROM_ENV).await(), "the lock is held: nothing written");
        } finally {
            held.close().await();
        }
        assertEquals(List.of(), uses());

        assertEquals(true, s.backfillUses("shop", SHA_2, FROM_ENV).await(), "another content: no longer the record restored");
        assertEquals(List.of(), uses(), "nothing written for it");
        assertTrue(!known("shop"));

        assertEquals(true, s.backfillUses("shop", SHA_1, FROM_ENV).await());
        assertEquals(List.of("shop/orders:from-env:value"), uses());
        assertTrue(known("shop"), "the record's uses are known");
        assertTrue(s.lock().await().map(l -> { l.close().await(); return true; }).orElse(false), "the backfill freed the lock");
    }
}
