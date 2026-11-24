package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.core.audit.Actor;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.deploy.DeploymentStore.Listings;
import dev.rail8.runtime.deploy.DeploymentStore.NodePackage;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.testkit.FakeRuntime;
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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.reachedWhile;
import static dev.rail8.runtime.deploy.DeploymentsRedeployTest.untilWhile;
import static dev.rail8.runtime.deploy.DeploymentsUndeployTest.children;
import static dev.rail8.runtime.deploy.DeploymentsTest.joinRefused;
import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two nodes of one pool over one Postgres, on a fake clock: each a {@code Deployments} with its own data directory, git cache and
 * {@link Doors}, both over the pool's {@code deployments}, {@code deploy_pool}, {@code retiring} and {@code work} tables
 * ({@link PgDeploymentStore}, its lock on a connection of its own per deploy; a {@link PgWorkQueue} of each node). A deploy or an undeploy on one node holds the pool's lock, and a second one on the other node
 * is refused while it runs; a node behind the pool's generation installs what the records name before it plans its own deploy; the
 * other node installs and undeploys at its next tick ({@code follow}), and a package it could not install, its repository not
 * answering, at a later tick once it does. An at-least-once flow the pool no longer runs stops taking messages on every node, and
 * the items it left run on a drain of the node that accepted them, which ends at a tick once none is left. Each node joins the
 * pool's {@code nodes} as it starts and writes what it runs into {@code node_packages}, which either node answers for the whole
 * pool. The ticks run as the node runs them, on its timer, or are called one at a time.
 */
class DeploymentsPoolIT {

    static final Instant NOW = Instant.parse("2026-11-20T09:30:00Z");
    static final Duration STOP_DEADLINE = Duration.ofSeconds(37);
    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static PgConnectOptions connect;

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create().withClockAt(NOW);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<Peer> peers = new ArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName()).setUser(pg.getUsername())
                .setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() {
        pool.query("truncate deployments, retiring, node_packages").execute().await();
        pool.query("delete from work").execute().await();
        pool.query("delete from nodes").execute().await();
        repos = new GitRepos(tmp.resolve("repos"));
        rail8.addHandler(capture);
    }

    @AfterEach
    void closeEveryPeer() throws Exception {
        rail8.removeHandler(capture);
        for (Peer p : peers) p.doors.releaseAll();
        for (Peer p : peers) p.close();
    }

    /**
     * One node of the pool: its data directory {@code <tmp>/<id>}, its Doors, its Deployments over the pool's Postgres, joined to the
     * pool's {@code nodes} as it is built, as a node joins before it restores: a node built again with the same id is its restart.
     */
    final class Peer {
        final String id;
        final long epoch;
        final Doors doors = new Doors();
        final Deployments deployments;

        /** A node in dev reading the pool's repositories. */
        Peer(String id) {
            this(id, repos.base().toString());
        }

        /** A node in dev reading the pool's repositories, its records through {@code records}. */
        Peer(String id, DeploymentStore records) {
            this(id, repos.base().toString(), records);
        }

        /** A node in dev whose {@code RAIL8_GIT_BASE} is {@code base}. */
        Peer(String id, String base) {
            this(id, base, new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> c)));
        }

        Peer(String id, String base, DeploymentStore records) {
            this.id = id;
            this.epoch = join(id);
            Map<String, String> e = new HashMap<>();
            e.put("RAIL8_ENV", "dev");
            e.put("RAIL8_NODE_ID", id);
            e.put("RAIL8_DATA_DIR", tmp.resolve(id).toString());
            e.put("RAIL8_STOP_DEADLINE", STOP_DEADLINE.toString());
            e.put("RAIL8_BLOB_DURABLE_MAX", "4096");
            e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(2L * 1024 * 1024));
            e.put("RAIL8_GIT_BASE", base);
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
            deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                    new PgWorkQueue(pool, id), () -> 0L, records, epoch);
            peers.add(this);
        }

        /** Restores, as the node does at its start. */
        Peer restored() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.restore();
                return null;
            });
            return this;
        }

        OnVirtualThread.Call<DeployResult> deploying(String repository, String commit) {
            return OnVirtualThread.start(() -> deployments.deploy(repository, commit, Deployments.ANY, TestActors.named("test")));
        }

        DeployResult deploy(String repository, String commit) throws Exception {
            return OnVirtualThread.join(deploying(repository, commit));
        }

        UndeployResult undeploy(String name) throws Exception {
            return OnVirtualThread.call(() -> deployments.undeploy(name, TestActors.named("test")));
        }

        /** One tick, as the node's timer runs it. */
        void follow() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.follow();
                return null;
            });
        }

        void close() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.close();
                return null;
            });
        }

        /** Each package listed, {@code <name>@<version> <state>}, by name. */
        List<String> listed() {
            return deployments.packages().stream().map(p -> p.name() + "@" + p.version() + " " + p.state()).toList();
        }

        /**
         * This node's answer of what the pool runs: {@code record <name>@<version>} per record, then per node {@code <node>} when it
         * has no package, else {@code <node> <name>@<version> <state>} per package, with its {@code behind} lines when it has any.
         */
        List<String> answer() throws Exception {
            Listings answer = OnVirtualThread.call(deployments::listings);
            List<String> lines = new ArrayList<>();
            answer.records().forEach(r -> lines.add("record " + r.name() + "@" + r.version()));
            answer.nodes().forEach((node, packages) -> {
                if (packages.isEmpty()) lines.add(node);
                for (NodePackage p : packages) lines.add(node + " " + p.name() + "@" + p.version() + " " + p.state() + (p.behind().isEmpty() ? "" : " " + p.behind()));
            });
            return lines;
        }

        /** The doors of {@code flow} that started and were not stopped since. */
        List<Doors.Door> live(String flow) {
            return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
        }

        /** Delivers a message to the live door of {@code flow}, which accepts it into the work queue. */
        Envelope accept(String flow) throws Exception {
            List<Doors.Door> running = live(flow);
            assertEquals(1, running.size(), id + ": one live door of " + flow + ": " + doors.events());
            Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
            assertInstanceOf(Ack.Accepted.class, within(running.getFirst().deliver(e), "the answer of " + flow + " on " + id), "fixture: " + flow + " accepts");
            return e;
        }

        /** What {@code packages/<name>/} of this node's data directory holds. */
        List<String> packageDirs(String name) throws Exception {
            return children(tmp.resolve(id).resolve("packages").resolve(name));
        }

        Ack send(String flow) throws Exception {
            List<Doors.Door> running = live(flow);
            assertEquals(1, running.size(), id + ": one live door of " + flow + ": " + doors.events());
            return within(running.getFirst().deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), "the answer of " + flow + " on " + id);
        }
    }

    /** {@code name} at {@code version} with a sync flow per id, each from its own in endpoint {@code <id>-in} to {@code partner}. */
    static PackageDraft pkg(String name, String version, String... flows) {
        PackageDraft p = PackageDraft.of(name, version).endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    /**
     * The pool's records through the Postgres store, whose reads fail while {@code failing} is set. Its writes of what the node runs
     * are counted in {@code writes}; while {@code holding} is set, each waits for it before it reaches Postgres, counted in
     * {@code held}, and {@code ended} counts those that ended since.
     */
    static final class Unreadable implements DeploymentStore {
        final DeploymentStore records = new PgDeploymentStore(pool, () -> PgConnection.connect(vertx, connect).map(c -> c));
        volatile boolean failing;
        volatile Promise<Void> holding;
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger held = new AtomicInteger();
        final AtomicInteger ended = new AtomicInteger();

        @Override
        public Future<Snapshot> read() {
            return failing ? Future.failedFuture(new IllegalStateException("the pool's database does not answer")) : records.read();
        }

        @Override
        public Future<Optional<PoolLock>> lock() {
            return records.lock();
        }

        @Override
        public Future<Void> audit(Actor actor, String repository, JsonObject detail) {
            return records.audit(actor, repository, detail);
        }

        @Override
        public Future<Void> endMarks() {
            return records.endMarks();
        }

        @Override
        public Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages) {
            writes.incrementAndGet();
            Promise<Void> hold = holding;
            if (hold == null) return records.writeListing(node, epoch, packages);
            held.incrementAndGet();
            return hold.future().compose(go -> records.writeListing(node, epoch, packages)).onComplete(done -> ended.incrementAndGet());
        }

        @Override
        public Future<Listings> listings() {
            return records.listings();
        }
    }

    /** Joins {@code node} to the pool's {@code nodes}: epoch 1, or the next one, with a lease of 30 s by the database's clock. */
    static long join(String node) {
        return pool.preparedQuery("insert into nodes (node, epoch, lease_until) values ($1, 1, now() + interval '30 seconds') "
                + "on conflict (node) do update set epoch = nodes.epoch + 1, lease_until = excluded.lease_until returning epoch")
                .execute(Tuple.of(node)).await().iterator().next().getLong(0);
    }

    /** Ends {@code node}'s lease by the database's clock, as a node that stopped renewing it. */
    static void expire(String node) {
        pool.preparedQuery("update nodes set lease_until = now() - interval '1 second' where node = $1").execute(Tuple.of(node)).await();
    }

    /** Releases {@code node}'s lease, as a peer releases an ended one. */
    static void release(String node) {
        pool.preparedQuery("update nodes set lease_until = null where node = $1").execute(Tuple.of(node)).await();
    }

    /** Every row of {@code node_packages}, {@code <node>/<epoch>/<package>@<version>}, sorted. */
    static List<String> nodeRows() {
        List<String> rows = new ArrayList<>();
        pool.query("select r from (select node || '/' || epoch || '/' || package || '@' || version as r from node_packages) t order by r collate \"C\"").execute().await()
                .forEach(r -> rows.add(r.getString(0)));
        return rows;
    }

    /** The messages of each WARNING logged that contains {@code text}, in order. */
    List<String> warned(String text) {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains(text)).map(LogRecord::getMessage).toList();
    }

    /** The pool's generation, read with plain SQL. */
    static long generation() {
        return pool.query("select generation from deploy_pool").execute().await().iterator().next().getLong(0);
    }

    /** Each deploy and undeploy row of the audit, oldest first: {@code <actor> <action> <subject> <author>}. */
    static List<String> audited() {
        List<String> rows = new ArrayList<>();
        pool.query("select actor, action, subject, detail->>'author' from audit where action in ('deploy', 'undeploy') order by id").execute().await()
                .forEach(r -> rows.add(r.getString(0) + " " + r.getString(1) + " " + r.getString(2) + " " + r.getString(3)));
        return rows;
    }

    /**
     * A redeploy whose audit row Postgres refuses is not recorded: it rolls back, and the node runs what ran before; an undeploy
     * whose row is refused deletes nothing, and the package runs on. Once the row can be written, both go through, each row in
     * the transaction of its record, naming who acted and the commit's author.
     */
    @Test
    void aDeployOrAnUndeployWhoseAuditRowIsRefusedChangesNothing() throws Exception {
        pool.query("delete from audit where action in ('deploy', 'undeploy')").execute().await();
        Peer a = new Peer("node-a").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        long before = generation();
        String v2 = repos.commit(pkg("shop", "2.0.0", "a", "c"));
        pool.query("alter table audit add constraint no_deploy_rows check (action not in ('deploy', 'undeploy')) not valid").execute().await();
        try {
            DeployRefusedException refused = joinRefused(a.deploying("shop", v2), "the redeploy whose row is refused");
            assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
            assertTrue(refused.error().contains("was not recorded"), refused.error());
            assertEquals(List.of("shop@1.0.0 RUNNING"), a.listed(), "the node runs what ran before");
            assertEquals(List.of(), a.live("shop/c"), "nothing of the redeploy runs");
            assertInstanceOf(Ack.Reply.class, a.send("shop/a"), "shop 1.0.0 serves");
            IOException undeleted = assertThrows(IOException.class, () -> a.undeploy("shop"), "the undeploy whose row is refused");
            assertTrue(undeleted.getMessage().contains("was not deleted"), undeleted.getMessage());
            assertEquals(List.of("shop@1.0.0 RUNNING"), a.listed(), "the package runs on");
        } finally {
            pool.query("alter table audit drop constraint no_deploy_rows").execute().await();
        }
        assertEquals(before, generation(), "neither wrote the pool");
        assertEquals(List.of("test deploy repository/shop Rail8 test <test@rail8.invalid>"), audited(), "only the first deploy's row");

        a.deploy("shop", v2);
        a.undeploy("shop");
        assertEquals(List.of("test deploy repository/shop Rail8 test <test@rail8.invalid>", "test deploy repository/shop Rail8 test <test@rail8.invalid>",
                "test undeploy package/shop null"), audited(), "the redeploy's and the undeploy's rows, once they can be written");
    }

    @Test
    void aSecondDeployIsRefusedOnAnyNodeWhileOneRuns() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        String shop = repos.commit(pkg("shop", "1.0.0", "a"));
        String billing = repos.commit(pkg("billing", "1.0.0", "b"));
        Doors.Gate held = a.doors.holdStartOf("shop/a");
        OnVirtualThread.Call<DeployResult> first = a.deploying("shop", shop);
        reachedWhile(first, held.reached(), "shop's consumer starts on node a, the deploy holding the pool's lock");

        DeployRefusedException second = joinRefused(b.deploying("billing", billing), "a deploy on node b while node a's runs");
        assertEquals(Kind.CONFLICT, second.kind(), second.getMessage());
        assertTrue(second.error().contains("another deploy or undeploy of the pool"), second.error());
        DeployRefusedException undeploy = joinRefused(OnVirtualThread.start(() -> b.deployments.undeploy("shop", TestActors.named("test"))), "an undeploy on node b meanwhile");
        assertEquals(Kind.CONFLICT, undeploy.kind(), "an undeploy waits for no deploy either: " + undeploy.getMessage());

        held.release();
        OnVirtualThread.join(first);
        long before = generation();
        b.deploy("billing", billing);
        assertEquals(before + 1, generation(), "once node a's deploy is done, node b's takes the lock and raises the generation");
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed(), "node b installed shop first, then deployed billing");

        Doors.Door shopOnA = a.live("shop/a").getFirst();
        a.follow();
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), a.listed(), "node a installs billing at its next tick");
        assertEquals(List.of(shopOnA), a.live("shop/a"), "and leaves shop, as its own deploy recorded it, running as it runs");
    }

    @Test
    void aNodeBehindTheGenerationInstallsWhatTheRecordsNameBeforeItPlans() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        assertEquals(List.of(), b.listed(), "fixture: node b has not ticked since");

        b.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));

        List<String> starts = b.doors.events().stream().filter(e -> e.startsWith("start ")).toList();
        assertEquals(List.of("start shop/a", "start billing/b"), starts, "shop runs on node b before billing's deploy built anything");
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed());
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"), "shop serves on node b");
    }

    @Test
    void theOtherNodeInstallsAndUndeploysAtItsNextTick() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();

        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed(), "node b installs the record at its next tick");
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"), "shop serves on node b");
        Doors.Door first = b.live("shop/a").getFirst();
        b.follow();
        assertEquals(List.of(first), b.live("shop/a"), "a tick with nothing new changes nothing: the same door runs");

        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        b.follow();
        assertEquals(List.of("shop@2.0.0 RUNNING"), b.listed(), "node b installs the record whose content differs");
        assertEquals(1, b.live("shop/c").size(), "the added flow runs on node b: " + b.doors.events());

        long before = generation();
        a.undeploy("shop");
        assertEquals(before + 1, generation(), "the undeploy raised the generation");
        b.follow();
        assertEquals(List.of(), b.listed(), "node b undeploys the package that has no record");
        assertEquals(List.of(), b.live("shop/a"), "nothing of it runs on node b");
        assertEquals(List.of(), b.live("shop/c"));
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void aFailedPackageIsInstalledAtALaterTickOnceItsRepositoryAnswers() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Path aside = tmp.resolve("aside");
        Files.move(repos.dir("shop"), aside);   // shop's repository does not answer node b's fetch

        b.follow();
        assertEquals(List.of("shop@1.0.0 FAILED"), b.listed(), "node b cannot read shop's commit");
        assertEquals(Health.Status.DEGRADED, b.deployments.health().status(), b.deployments.health().detail());
        b.follow();
        assertEquals(List.of("shop@1.0.0 FAILED"), b.listed(), "every tick tries again; the repository still does not answer");

        Files.move(aside, repos.dir("shop"));
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed(), "the tick after the repository answers installs it");
        assertEquals(Health.up(), b.deployments.health());
        assertInstanceOf(Ack.Reply.class, b.send("shop/a"));
    }

    @Test
    void theNodesTimerTicksEveryFiveSeconds() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        b.deployments.startFollowing();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        rt.clock().advance(Duration.ofSeconds(5).minusMillis(1));
        assertEquals(Duration.ofMillis(1), rt.timer().nextDelay(), "no tick before its time: the first one is due 1 ms later");
        assertEquals(List.of(), b.listed());

        rt.clock().advance(Duration.ofMillis(1));
        until(() -> b.listed().equals(List.of("shop@1.0.0 RUNNING")), "node b's tick installs shop");

        a.undeploy("shop");
        until(() -> !b.deployments.packages().isEmpty() && b.live("shop/a").size() == 1, "fixture: shop still runs on node b");
        until(() -> rt.timer().pending() > 0 && rt.timer().nextDelay().equals(Duration.ofSeconds(5)), "the next tick is due 5 s after the first ended");
        rt.clock().advance(Duration.ofSeconds(5));
        until(() -> b.listed().isEmpty(), "the next tick, scheduled once the first ended, undeploys it");
    }

    @Test
    void aDeployWaitsForTheTickThatHoldsTheNodesLock() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Doors.Gate held = b.doors.holdStartOf("shop/a");
        OnVirtualThread.Call<Void> tick = OnVirtualThread.start(() -> {
            b.deployments.follow();
            return null;
        });
        reachedWhile(tick, held.reached(), "node b's tick installs shop");

        OnVirtualThread.Call<DeployResult> deploy = b.deploying("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        assertFalse(deploy.outcome().isDone(), "the deploy waits for the tick, it is not refused");
        held.release();
        OnVirtualThread.join(tick);
        OnVirtualThread.join(deploy);
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed());
    }

    /**
     * A node that cannot install a newer record of a package it runs runs the old version on and is DEGRADED, BEHIND; a deploy on it
     * is refused, BUSY, while it is behind, and an undeploy is not; the tick after the repository answers installs the record.
     */
    @Test
    void aNodeThatCannotInstallANewerRecordRunsTheOldVersionOnAndRefusesDeploysUntilItCatchesUp() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        a.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        b.follow();
        Doors.Door oldShop = b.live("shop/a").getFirst();
        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        Path aside = tmp.resolve("aside");
        Files.move(repos.dir("shop"), aside);   // shop's repository does not answer node b's fetch of 2.0.0

        b.follow();
        assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed(), "node b runs shop 1.0.0 on");
        assertEquals(List.of(oldShop), b.live("shop/a"), "its old door serves");
        assertEquals(List.of(), b.live("shop/c"));
        Health behind = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, behind.status(), behind.detail());
        assertTrue(behind.detail().contains("BEHIND: package shop runs version 1.0.0: "), behind.detail());

        DeployRefusedException busy = joinRefused(b.deploying("orders", repos.commit(pkg("orders", "1.0.0", "o"))), "a deploy on node b while shop is behind");
        assertEquals(Kind.BUSY, busy.kind(), busy.getMessage());
        assertEquals(List.of(), b.live("orders/o"), "nothing of it runs");
        long before = generation();
        assertEquals(new UndeployResult("billing", "1.0.0", List.of("billing/b")), b.undeploy("billing"), "an undeploy is not refused for it");
        assertEquals(before + 1, generation());
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed());

        Files.move(aside, repos.dir("shop"));
        b.follow();
        assertEquals(List.of("shop@2.0.0 RUNNING"), b.listed(), "the tick after the repository answers installs the record");
        assertEquals(1, b.live("shop/c").size(), b.doors.events().toString());
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void healthIsDegradedWhileThePoolsRecordsCannotBeReadAndUpOnceATickReadsThem() throws Exception {
        Unreadable records = new Unreadable();
        Peer b = new Peer("node-b", records).restored();
        records.failing = true;
        b.follow();
        Health unread = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, unread.status(), unread.detail());
        assertTrue(unread.detail().contains("BEHIND: the pool's records could not be read: "), unread.detail());

        records.failing = false;
        b.follow();
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void healthIsDegradedWhileATickFailsAndUpOnceOneBringsTheNodeToTheRecords() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Path incoming = b.deployments.incomingDir();
        Files.delete(incoming);
        Files.writeString(incoming, "not a folder");   // node b cannot write its data directory

        assertThrows(java.io.IOException.class, b::follow, "the tick fails");
        Health failed = b.deployments.health();
        assertEquals(Health.Status.DEGRADED, failed.status(), failed.detail());
        assertTrue(failed.detail().contains("BEHIND: the last tick could not bring this node to the pool's records: "), failed.detail());

        Files.delete(incoming);
        b.follow();
        assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed());
        assertEquals(Health.up(), b.deployments.health());
    }

    @Test
    void aDeployRecordsTheQualifiedIdsOfItsAtLeastOnceFlows() throws Exception {
        Peer a = new Peer("node-a").restored();
        PackageDraft shop = pkg("shop", "1.0.0", "a").endpoint("keep-in", "in", new JsonObject().put("path", "/keep"))
                .flow("keep", "1.0.0", "at-least-once", "keep-in", 1024, PackageDraft.toPartner());
        a.deploy("shop", repos.commit(shop));

        List<String> recorded = List.of(pool.query("select at_least_once from deployments where package = 'shop'").execute().await().iterator().next()
                .getArrayOfStrings(0));
        assertEquals(List.of("shop/keep"), recorded, "the at-least-once flow, not the sync one");
    }

    /**
     * An undeploy on node a of a package with an at-least-once flow, while node a holds one of its messages at the partner and has
     * the next queued in the pool's work table. No door of the flow runs on node a from the answer, nor on node b from its next
     * tick. The pool marks the flow, and the mark outlives both nodes' ticks while a work row of it is left. The queued message
     * runs on node a's drain; once the held one completes, node a's tick ends the mark and its drain, and node b's tick ends its own.
     * Each node keeps the package's directory until its drain ends.
     */
    @Test
    void aRemovedFlowTakesNoNewMessageOnAnyNodeAndDrainsBeforeItStops() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        PackageDraft shop = pkg("shop", "1.0.0", "a").endpoint("keep-in", "in", new JsonObject().put("path", "/keep"))
                .flow("keep", "1.0.0", "at-least-once", "keep-in", 1024, PackageDraft.toPartner());
        shop.flowDoc("keep").getJsonObject("limits").put("maxInFlight", 1);
        a.deploy("shop", repos.commit(shop));
        b.follow();
        assertEquals(1, b.live("shop/keep").size(), "fixture: shop runs on node b too");
        List<String> dirs = a.packageDirs("shop");
        CountDownLatch release = a.doors.partner().holdNext(1);
        Envelope queued;
        try {
            a.accept("shop/keep");
            a.doors.partner().awaitSent(1);
            queued = a.accept("shop/keep");
            assertEquals(2L, rows("shop/keep"), "fixture: the first runs held at the partner, the second waits behind it");

            assertEquals("shop", a.undeploy("shop").name());

            assertEquals(List.of(), a.live("shop/keep"), "no door of the flow runs on node a: " + a.doors.events());
            assertEquals(List.of("shop/keep"), marks(), "the pool marks the flow it retires");
            b.follow();
            assertEquals(List.of(), b.listed(), "node b undeploys shop at its next tick");
            assertEquals(List.of(), b.live("shop/keep"), "and no door of the flow runs on node b: " + b.doors.events());
            assertEquals(List.of("shop/keep"), marks(), "a work row of the flow is left: the mark stays");
            assertEquals(dirs, b.packageDirs("shop"), "node b's drain waits for the mark: its directory stays");
            a.follow();
            assertEquals(List.of("shop/keep"), marks(), "still: node a's tick keeps the mark");
            assertEquals(dirs, a.packageDirs("shop"), "and node a's drain runs: shop's directory stays");
        } finally {
            release.countDown();
        }
        until(() -> rows("shop/keep") == 0, "the held message completes and the queued one runs on a drain");
        assertTrue(a.doors.partner().sent().stream().anyMatch(e -> e.id().equals(queued.id())), "the queued message reached node a's partner");

        a.follow();
        assertEquals(List.of(), marks(), "no row is left: node a's tick ends the mark");
        assertEquals(List.of(), a.packageDirs("shop"), "and node a's drain: shop's directory is pruned");
        b.follow();
        assertEquals(List.of(), b.packageDirs("shop"), "node b's tick ends its drain and prunes the directory");
        assertEquals(Health.up(), a.deployments.health());
        assertEquals(Health.up(), b.deployments.health());
    }

    // --- what each node runs

    /**
     * Both nodes answer the same: the pool's records and what each node runs. After a deploy on node a, node b runs the older
     * version until its next tick; after an undeploy, it runs the package until then.
     */
    @Test
    void eachNodeAnswersWhatEveryNodeRunsAFollowerBehindUntilItsTick() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        assertEquals(List.of("node-a", "node-b"), a.answer(), "both nodes joined and restored nothing");

        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        b.follow();
        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        List<String> behind = List.of("record shop@2.0.0", "node-a shop@2.0.0 RUNNING", "node-b shop@1.0.0 RUNNING");
        assertEquals(behind, a.answer(), "node b runs 1.0.0 until its next tick");
        assertEquals(behind, b.answer(), "and says so itself");

        b.follow();
        assertEquals(List.of("record shop@2.0.0", "node-a shop@2.0.0 RUNNING", "node-b shop@2.0.0 RUNNING"), b.answer(), "node b's tick wrote 2.0.0");

        a.undeploy("shop");
        assertEquals(List.of("node-a", "node-b shop@2.0.0 RUNNING"), b.answer(), "no record, and node b runs shop until its next tick");
        b.follow();
        assertEquals(List.of("node-a", "node-b"), a.answer());
        assertEquals(List.of(), warned("what this node runs could not be written"), "every write was written");
    }

    /**
     * A tick that cannot install a newer record writes why into the row of the version the node runs on; the tick that installs the
     * record writes the row with nothing behind it.
     */
    @Test
    void aTickThatCannotInstallARecordWritesWhy() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        b.follow();
        a.deploy("shop", repos.commit(pkg("shop", "2.0.0", "a", "c")));
        Path aside = tmp.resolve("aside");
        Files.move(repos.dir("shop"), aside);   // shop's repository does not answer node b's fetch of 2.0.0

        b.follow();
        String health = b.deployments.health().detail();
        String prefix = "BEHIND: package shop runs version 1.0.0: ";
        assertTrue(health.contains(prefix), health);
        NodePackage row = OnVirtualThread.call(a.deployments::listings).nodes().get("node-b").getFirst();
        assertEquals("1.0.0", row.version(), "node b runs 1.0.0 on");
        assertFalse(row.behind().isEmpty(), "its row says why it runs behind: " + row);
        assertTrue(health.contains(prefix + row.behind()), "the lines its health names: " + row.behind() + " in " + health);

        Files.move(aside, repos.dir("shop"));
        b.follow();
        assertEquals(List.of("record shop@2.0.0", "node-a shop@2.0.0 RUNNING", "node-b shop@2.0.0 RUNNING"), a.answer(), "nothing behind it once installed");
    }

    /**
     * A node whose lease ended drops out of the answer, its rows kept unread; once a peer released the lease, the next write of any
     * node deletes them.
     */
    @Test
    void aNodeWhoseLeaseEndedDropsOutOfTheAnswer() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        b.follow();

        expire("node-b");
        assertEquals(List.of("record shop@1.0.0", "node-a shop@1.0.0 RUNNING"), a.answer(), "node b's lease ended: it is not in the answer");
        assertEquals(List.of("node-a/1/shop@1.0.0", "node-b/1/shop@1.0.0"), nodeRows(), "its rows stay");
        release("node-b");
        assertEquals(List.of("node-a/1/shop@1.0.0", "node-b/1/shop@1.0.0"), nodeRows(), "until the next write of any node");

        a.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        assertEquals(List.of("node-a/1/billing@1.0.0", "node-a/1/shop@1.0.0"), nodeRows(), "node a's write deleted the released node's rows");
        assertEquals(List.of("record billing@1.0.0", "record shop@1.0.0", "node-a billing@1.0.0 RUNNING", "node-a shop@1.0.0 RUNNING"), b.answer());
    }

    /** A restarted node joins as a new epoch: it is listed with no packages until its restore writes what it runs. */
    @Test
    void aRestartedNodeIsListedWithNoPackagesUntilItsRestoreWrites() throws Exception {
        Peer a = new Peer("node-a").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        a.close();

        Peer restarted = new Peer("node-a");
        assertEquals(2, restarted.epoch, "fixture: the restart joined as epoch 2");
        assertEquals(List.of("record shop@1.0.0", "node-a"), restarted.answer(), "the previous incarnation's rows dropped out at once");
        restarted.restored();
        assertEquals(List.of("record shop@1.0.0", "node-a shop@1.0.0 RUNNING"), restarted.answer(), "its restore wrote what it runs");
        assertEquals(List.of("node-a/2/shop@1.0.0"), nodeRows(), "the write replaced the rows of every epoch of the node");
    }

    /**
     * An old incarnation of a node, still running after a newer one joined, writes nothing: its deploy answers with its record
     * written, and its rows are refused, a WARNING once per reason.
     */
    @Test
    void anOldIncarnationsWriteChangesNothing() throws Exception {
        Peer a = new Peer("node-a").restored();
        a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        assertEquals(2, join("node-a"), "fixture: a newer incarnation of node a joined, and wrote nothing yet");

        DeployResult billing = a.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        assertEquals("1.0.0", billing.pkg().version(), "the deploy answers");
        assertEquals(List.of("record billing@1.0.0", "record shop@1.0.0", "node-a"), a.answer(), "the newer incarnation is listed with nothing yet");
        assertEquals(List.of("node-a/1/shop@1.0.0"), nodeRows(), "the old incarnation's write changed nothing");
        a.undeploy("billing");
        assertEquals(List.of("what this node runs could not be written; its next deploy, undeploy or tick writes again: "
                + "the pool's membership holds no lease of this node at epoch 1"), warned("what this node runs could not be written"), "one WARNING for both refusals");
    }

    /**
     * With {@code node_packages} gone, a deploy answers with its record written and the other node's tick installs it; the answer of
     * what each node runs is FAILED, and each node's failed write a WARNING once. Once the table is back, the next tick of each node
     * writes what it runs.
     */
    @Test
    void theTableGoneLeavesTheTicksAndTheDeploysGoingOn() throws Exception {
        Peer a = new Peer("node-a").restored();
        Peer b = new Peer("node-b").restored();
        pool.query("alter table node_packages rename to node_packages_gone").execute().await();
        try {
            a.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
            b.follow();
            assertEquals(List.of("shop@1.0.0 RUNNING"), b.listed(), "node b's tick installed shop");
            a.deploy("billing", repos.commit(pkg("billing", "1.0.0", "b")));
            b.follow();
            assertEquals(List.of("billing@1.0.0 RUNNING", "shop@1.0.0 RUNNING"), b.listed());
            assertEquals(Health.up(), a.deployments.health(), "what a node runs is not its health");
            assertEquals(Health.up(), b.deployments.health());
            DeployRefusedException unread = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(a.deployments::listings));
            assertEquals(Kind.FAILED, unread.kind(), unread.getMessage());
            assertTrue(unread.error().startsWith("what each node runs could not be read: "), unread.error());
            assertEquals(2, warned("what this node runs could not be written").size(), "one WARNING per node: " + warned("what this node runs could not be written"));
        } finally {
            pool.query("alter table node_packages_gone rename to node_packages").execute().await();
        }
        a.follow();
        b.follow();
        assertEquals(List.of("record billing@1.0.0", "record shop@1.0.0", "node-a billing@1.0.0 RUNNING", "node-a shop@1.0.0 RUNNING",
                "node-b billing@1.0.0 RUNNING", "node-b shop@1.0.0 RUNNING"), a.answer(), "each node's next tick wrote what it runs");
    }

    /**
     * A write of what the node runs that does not answer: the deploy answers within the stop deadline with its record written, and a
     * WARNING says why. No other write starts while it runs, so it never lands after a later one: an undeploy meanwhile writes
     * nothing, and once the held write landed the deploy's rows, the next tick writes what the node runs.
     */
    @Test
    void aWriteThatHangsLeavesTheDeployAnsweringWithItsRecordWritten() throws Exception {
        Unreadable records = new Unreadable();
        Peer a = new Peer("node-a", records).restored();
        assertEquals(1, records.writes.get(), "fixture: the restore wrote what the node runs, nothing");
        Promise<Void> hold = Promise.promise();
        records.holding = hold;
        long before = generation();

        OnVirtualThread.Call<DeployResult> deploying = a.deploying("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        untilWhile(deploying, () -> records.held.get() == 1, "the deploy writes what the node runs");
        untilWhile(deploying, () -> rt.timer().pending() > 0 && rt.timer().nextDelay().equals(STOP_DEADLINE), "the write is bounded on the node's timer");
        rt.clock().advance(STOP_DEADLINE);
        assertEquals("1.0.0", OnVirtualThread.join(deploying).pkg().version(), "the deploy answers");
        assertEquals(before + 1, generation(), "its record is written");
        assertEquals(List.of("record shop@1.0.0", "node-a"), a.answer(), "node a's rows are those before the deploy");
        List<String> timedOut = warned("what this node runs could not be written");
        assertEquals(1, timedOut.size(), timedOut.toString());
        assertTrue(timedOut.getFirst().contains("the write of what this node runs did not complete within the stop deadline"), timedOut.getFirst());

        records.holding = null;
        a.undeploy("shop");
        assertEquals(2, records.writes.get(), "the undeploy started no write while the deploy's runs");
        assertTrue(warned("what this node runs could not be written").getLast().endsWith("the write before it, which did not answer within the stop deadline, has not ended"),
                warned("what this node runs could not be written").toString());

        hold.complete();
        until(() -> records.ended.get() == 1, "the deploy's write ends");
        assertEquals(List.of("node-a/1/shop@1.0.0"), nodeRows(), "it landed the rows it carried");
        a.follow();
        assertEquals(List.of("node-a"), a.answer(), "the next tick wrote what the node runs: nothing");
        assertEquals(3, records.writes.get());
        a.follow();
        assertEquals(3, records.writes.get(), "a tick that changed nothing writes nothing");
    }

    /**
     * A deploy that comes while a tick holds the node's lock waits for the tick, not for the tick's write of what the node runs: its
     * record is written while that write has not answered, and once it lands the node's rows name both packages.
     */
    @Test
    void aDeployThatWaitsForATickGoesOnWhileTheTicksWriteHasNotAnswered() throws Exception {
        Unreadable records = new Unreadable();
        Peer a = new Peer("node-a", records).restored();
        Peer b = new Peer("node-b").restored();
        b.deploy("shop", repos.commit(pkg("shop", "1.0.0", "a")));
        Promise<Void> hold = Promise.promise();
        records.holding = hold;
        Doors.Gate started = a.doors.holdStartOf("shop/a");
        OnVirtualThread.Call<Void> tick = OnVirtualThread.start(() -> {
            a.deployments.follow();
            return null;
        });
        reachedWhile(tick, started.reached(), "node a's tick installs shop");

        long before = generation();
        int pending = rt.timer().pending();
        OnVirtualThread.Call<DeployResult> deploying = a.deploying("billing", repos.commit(pkg("billing", "1.0.0", "b")));
        untilWhile(deploying, () -> rt.timer().pending() > pending, "the deploy waits for the tick, bounded on the node's timer");
        started.release();
        untilWhile(tick, () -> records.held.get() > 0, "the tick writes what node a runs");
        untilWhile(deploying, () -> generation() == before + 1, "the deploy's record is written while the tick's write has not answered");

        records.holding = null;
        hold.complete();
        assertEquals("1.0.0", OnVirtualThread.join(deploying).pkg().version(), "the deploy answers");
        OnVirtualThread.join(tick);
        assertEquals(List.of("record billing@1.0.0", "record shop@1.0.0", "node-a billing@1.0.0 RUNNING", "node-a shop@1.0.0 RUNNING",
                "node-b shop@1.0.0 RUNNING"), b.answer(), "node a's rows name both packages");
        assertEquals(List.of(), warned("what this node runs could not be written"));
    }

    /** The work rows of {@code flow} in the pool's work table. */
    static long rows(String flow) {
        return pool.preparedQuery("select count(*) from work where flow = $1").execute(io.vertx.sqlclient.Tuple.of(flow)).await().iterator().next().getLong(0);
    }

    /** The flows the pool marks retired, by id. */
    static List<String> marks() {
        List<String> marked = new ArrayList<>();
        pool.query("select flow from retiring order by flow").execute().await().forEach(r -> marked.add(r.getString(0)));
        return marked;
    }
}
