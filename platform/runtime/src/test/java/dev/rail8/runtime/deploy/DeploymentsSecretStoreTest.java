package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.audit.Actor;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.refusal.RefusalCode;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An endpoint option's {@code secret://} reference on nodes with a secret store. In an option the component declares a secret
 * field, the store's plain value wins over the variable, a name of another kind or under {@code rail8.} refuses the deploy, and in
 * the interim, while endpoint files hold references, a change of a name such a field resolved builds again on every node the flows
 * that hold it, and no other; one whose secret is gone is stopped and listed FAILED until a tick finds it again. Any other option
 * reads its variable only: the store never serves it, it is no use, and a change in the store rebuilds nothing.
 */
class DeploymentsSecretStoreTest {

    static final Map<String, String> KEY = Map.of("RAIL8_SECRETS_KEY", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create();
    final MemorySecretStores stores = new MemorySecretStores();
    final InMemoryDeploymentStore pool = new InMemoryDeploymentStore();
    final UsesKept records = new UsesKept(pool);
    /** Every line the node logged under {@code dev.rail8}, with its failure. */
    final List<String> logged = new CopyOnWriteArrayList<>();
    final Handler logs = new Handler() {
        @Override public void publish(LogRecord r) {
            StringWriter trace = new StringWriter();
            if (r.getThrown() != null) r.getThrown().printStackTrace(new PrintWriter(trace));
            logged.add(r.getMessage() + " " + trace);
        }
        @Override public void flush() { }
        @Override public void close() { }
    };
    final Logger rail8 = Logger.getLogger("dev.rail8");

    @BeforeEach
    void hook() {
        rail8.addHandler(logs);
        rail8.setLevel(Level.ALL);
    }
    final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void closeEveryNode() throws Exception {
        rail8.removeHandler(logs);
        rail8.setLevel(null);
        for (Node n : nodes) n.doors.releaseAll();
        for (Node n : nodes) OnVirtualThread.call(() -> {
            n.deployments.close();
            return null;
        });
    }

    /**
     * {@code inner}, keeping the uses each save of a package writes, by its name: the record of uses a pool of Postgres writes.
     * Once {@link #checkedBy} is set, a save refuses, writing nothing, a use whose name that store does not hold, as Postgres checks
     * the uses again in the record's transaction.
     */
    static final class UsesKept implements DeploymentStore {
        final DeploymentStore inner;
        final Map<String, Map<String, Map<String, Set<Secret.Kind>>>> uses = new ConcurrentHashMap<>();
        volatile SecretStore checkedBy;

        UsesKept(DeploymentStore inner) {
            this.inner = inner;
        }

        @Override public Future<Snapshot> read() { return inner.read(); }
        @Override public Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages) { return inner.writeListing(node, epoch, packages); }
        @Override public Future<Listings> listings() { return inner.listings(); }
        @Override public Future<Void> endMarks() { return inner.endMarks(); }
        @Override public Future<Void> audit(Actor by, String repository, JsonObject detail) { return inner.audit(by, repository, detail); }

        @Override
        public Future<java.util.Optional<PoolLock>> lock() {
            return inner.lock().map(held -> held.map(lock -> new PoolLock() {
                @Override public Snapshot snapshot() { return lock.snapshot(); }
                @Override public Future<Long> save(DeploymentRecord record, Actor by, JsonObject detail) { return lock.save(record, by, detail); }
                @Override public Future<Long> delete(String name, Actor by, JsonObject detail) { return lock.delete(name, by, detail); }
                @Override public Future<Void> close() { return lock.close(); }

                @Override
                public Future<Long> save(DeploymentRecord record, Map<String, Map<String, Set<Secret.Kind>>> written, Actor by, JsonObject detail) {
                    SecretStore check = checkedBy;
                    if (check != null) {
                        List<String> problems = new ArrayList<>();
                        written.forEach((flow, names) -> names.keySet().stream().filter(n -> check.held(n).isEmpty()).sorted()
                                .forEach(n -> problems.add("flow '" + flow + "': secret '" + n + "' is not set")));
                        if (!problems.isEmpty()) return Future.failedFuture(new SecretUsesRefused(problems));
                    }
                    uses.put(record.name(), written);
                    return lock.save(record, written, by, detail);
                }
            }));
        }
    }

    /** One node: its own data directory and Doors, the pool's records, and its store over the shared secret database. */
    /** The connection form of this test's Doors: {@code token} and {@code refuse} secret fields, the rest text. */
    static final Settings DESCRIBED = Settings.of(Field.text("path", "Path", "Where it listens.").in(), Field.text("url", "URL", "Where it sends."),
            Field.secret("token", "Token", "What it presents."), Field.secret("refuse", "Refusal", "A text its consumer's build throws with."));

    final class Node {
        final Doors doors = new Doors().describe(DESCRIBED);
        final SecretStore store;
        final CountingQueue queue = new CountingQueue(rt.clock());
        final Deployments deployments;

        Node(String id, Map<String, String> variables) {
            if (repos == null) repos = new GitRepos(tmp.resolve("repos"));
            Map<String, String> env = new HashMap<>(KEY);
            env.putAll(variables);
            env.put("RAIL8_ENV", "dev");
            env.put("RAIL8_NODE_ID", id);
            env.put("RAIL8_DATA_DIR", tmp.resolve(id).toString());
            env.put("RAIL8_STOP_DEADLINE", "PT37S");
            env.put("RAIL8_GIT_BASE", repos.base().toString());
            store = stores.start(id, env, rt.clock(), rt.timer());
            NodeConfig config = NodeConfig.from(env);
            config = config.withSecrets(config.secrets().withStore(store));
            Map<String, Component> components = Map.of(Doors.SCHEME, doors);
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
            deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), new InMemoryMessageStore(),
                    new InMemoryDlq(), queue, () -> 0L, records);
            nodes.add(this);
        }

        void deploy(PackageDraft p) throws Exception {
            String commit = repos.commit(p);
            OnVirtualThread.call(() -> deployments.deploy(p.name(), commit, Deployments.ANY, TestActors.named("test")));
        }

        DeployRefusedException refused(PackageDraft p) {
            String commit = repos.commit(p);
            return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy(p.name(), commit, Deployments.ANY,
                    TestActors.named("test"))));
        }

        void restore() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.restore();
                return null;
            });
        }

        void follow() throws Exception {
            OnVirtualThread.call(() -> {
                deployments.follow();
                return null;
            });
        }

        void undeploy(String name) throws Exception {
            OnVirtualThread.call(() -> deployments.undeploy(name, TestActors.named("test")));
        }

        /** A message accepted by the consumer of {@code flow}. */
        void accept(String flow) throws Exception {
            Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
            Doors.Door door = doors.door(flow);
            assertInstanceOf(Ack.Accepted.class, within(door.deliver(e), door + "'s answer"), "fixture: " + door + " accepts");
        }

        /** The token each producer of the partner endpoint was built with, in the order they were built. */
        List<Object> tokens() {
            return doors.producerConfigs("partner").stream().map(c -> c.options().get("token")).toList();
        }

        /** How many consumers this node built of {@code flow}. */
        long built(String flow) {
            return doors.doors().stream().filter(d -> flow.equals(d.flow())).count();
        }

        /** The value of {@code option} the newest consumer of {@code flow} got. */
        Object option(String flow, String option) {
            return doors.door(flow).cfg().options().get(option);
        }


        DeployedFlow.State state(String flow) {
            return deployments.flows().stream().filter(f -> f.id().equals(flow)).findFirst().orElseThrow().state();
        }
    }

    /** {@code orders}, whose in endpoint's token is {@code secret://in-token}, and {@code ping}, whose in endpoint holds no secret. */
    static PackageDraft shop() {
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders").put("token", "secret://in-token"))
                .endpoint("ping-in", "in", new JsonObject().put("path", "/ping"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "1.0.0", "sync", "orders-in", 4096, PackageDraft.toPartner())
                .flow("ping", "1.0.0", "at-most-once", "ping-in", 2048, PackageDraft.toPartner());
    }

    @Test
    void theStoresValueWinsOverTheVariable() throws Exception {
        Node a = new Node("node-1", Map.of("RAIL8_SECRET_IN_TOKEN", "from-the-variable"));
        MemorySecretStores.createValue(a.store, "in-token", "from-the-store");
        a.deploy(shop());
        assertEquals("from-the-store", a.option("shop/orders", "token"));
    }

    @Test
    void anOverwriteRebuildsTheFlowThatResolvedItOnEveryNodeAndNoOther() throws Exception {
        Node a = new Node("node-1", Map.of());
        Node b = new Node("node-2", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.deploy(shop());
        b.restore();
        assertEquals("one", b.option("shop/orders", "token"));

        MemorySecretStores.overwriteValue(b.store, "in-token", "two");
        for (Node n : List.of(a, b)) {
            n.follow();
            assertEquals("two", n.option("shop/orders", "token"), "the flow is built again with the new value");
            assertEquals(2, n.built("shop/orders"));
            assertEquals(1, n.built("shop/ping"), "no other flow is built again");
            assertEquals(DeployedFlow.State.RUNNING, n.state("shop/orders"));
            n.follow();
            assertEquals(2, n.built("shop/orders"), "a tick with no change builds nothing");
        }
    }

    @Test
    void aDeleteStopsTheFlowAndListsItFailedUntilATickFindsTheSecretAgain() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.deploy(shop());

        MemorySecretStores.delete(a.store, "in-token");
        a.follow();
        assertEquals(DeployedFlow.State.FAILED, a.state("shop/orders"), "never left running on its old value");
        assertTrue(a.doors.door("shop/orders").stopped(), "its consumer stopped");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/ping"));
        a.follow();
        assertEquals(DeployedFlow.State.FAILED, a.state("shop/orders"), "each tick tries again");

        MemorySecretStores.createValue(a.store, "in-token", "three");
        rt.clock().advance(Duration.ofSeconds(5));
        a.follow();
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"));
        assertEquals("three", a.option("shop/orders", "token"));
    }

    @Test
    void aNameOfAnotherKindOrOfThePlatformRefusesTheDeploy() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-token", "app", "pw-0815");
        DeployRefusedException kind = a.refused(shop());
        assertEquals(List.of("endpoint 'orders-in': secret 'secret://in-token' is a secret of kind credentials in the secret store, taken here as [value]"),
                kind.problems());

        PackageDraft platform = shop();
        platform.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://rail8.git");
        DeployRefusedException reserved = a.refused(platform);
        assertEquals(1, reserved.problems().size(), reserved.problems().toString());
        assertTrue(reserved.problems().getFirst().startsWith("endpoint 'orders-in': 'secret://rail8.git' names a secret of the platform"),
                reserved.problems().toString());
    }

    /** {@code orders} again, its in endpoint's url {@code secret://k}, an option that is no secret field, and its token {@code secret://p}. */
    static PackageDraft pointed() {
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("url", "secret://k").put("token", "secret://p");
        return p;
    }

    @Test
    void aReferenceOutsideASecretFieldReadsItsVariableOnlyAndAStoreChangeRebuildsNothing() throws Exception {
        Node a = new Node("node-1", Map.of("RAIL8_SECRET_K", "rec://from-the-variable"));
        MemorySecretStores.createValue(a.store, "k", "rec://from-the-store");
        MemorySecretStores.createValue(a.store, "p", "p-1");
        a.deploy(pointed());
        assertEquals("rec://from-the-variable", a.option("shop/orders", "url"), "the store never serves an option that is no secret field");
        assertEquals("p-1", a.option("shop/orders", "token"));
        assertEquals(Map.of("orders", Map.of("p", Set.of(Secret.Kind.VALUE)), "ping", Map.of()), records.uses.get("shop"),
                "a reference outside a secret field is no use");

        a.follow();   // the tick after the deploy takes the creates of k and p that came before it
        long built = a.built("shop/orders");

        MemorySecretStores.overwriteValue(a.store, "k", "rec://elsewhere");
        a.follow();
        assertEquals(built, a.built("shop/orders"), "a change of k rebuilds nothing");
        assertEquals("rec://from-the-variable", a.option("shop/orders", "url"));

        MemorySecretStores.overwriteValue(a.store, "p", "p-2");
        a.follow();
        assertEquals(built + 1, a.built("shop/orders"), "a change of p rebuilds its flow");
        assertEquals("rec://from-the-variable", a.option("shop/orders", "url"));
        assertEquals("p-2", a.option("shop/orders", "token"));
    }

    @Test
    void aReferenceOutsideASecretFieldWithoutItsVariableRefusesTheDeployThoughTheStoreHoldsIt() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "k", "rec://from-the-store");
        MemorySecretStores.createValue(a.store, "p", "p-1");
        DeployRefusedException refused = a.refused(pointed());
        assertEquals(List.of("endpoint 'orders-in': secret 'secret://k' is not set (RAIL8_SECRET_K): the option 'url' is not a secret field,"
                + " so it reads its variable only"), refused.problems());
        assertFalse(refused.getMessage().contains("rec://from-the-store"));
    }

    @Test
    void aBuildThatThrowsWithAValueLeavesItOutOfTheAnswerTheProblemsTheAuditAndTheLog() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok").put("refuse", "secret://tok");
        DeployRefusedException refused = a.refused(p);
        String answer = refused.getMessage() + refused.error() + refused.problems();
        assertTrue(answer.contains("secret://tok"), answer);
        assertFalse(answer.contains("v4lue-tok"), answer);
        assertFalse(pool.audited().toString().contains("v4lue-tok"), pool.audited().toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("v4lue-tok")), logged.toString());
    }

    @Test
    void aStartThatFailsWithAValueLeavesItOutOfTheAnswerTheListingAndTheLog() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok");
        a.doors.failStartOf("shop/orders", "cannot log in with v4lue-tok");
        DeployRefusedException refused = a.refused(p);
        String answer = refused.getMessage() + refused.error() + refused.problems();
        assertTrue(answer.contains("cannot log in with secret://tok"), answer);
        assertFalse(answer.contains("v4lue-tok"), answer);

        a.deploy(p);
        Node b = new Node("node-2", Map.of());
        b.doors.failStartOf("shop/orders", "cannot log in with v4lue-tok");
        b.restore();
        String listing = b.deployments.packages() + " " + b.deployments.flows() + " " + b.deployments.restarting();
        assertFalse(listing.contains("v4lue-tok"), listing);
        assertFalse(pool.audited().toString().contains("v4lue-tok"), pool.audited().toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("cannot log in with secret://tok")), logged.toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("v4lue-tok")), logged.toString());
    }

    @Test
    void aRestoreWhoseBuildThrowsWithAValueListsAndLogsItScrubbed() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok");
        a.deploy(p);
        Node b = new Node("node-2", Map.of());
        b.doors.onConsumer(cfg -> {
            if ("v4lue-tok".equals(cfg.options().get("token"))) throw new IllegalStateException("no login with " + cfg.options().get("token"));
        });
        b.restore();
        DeployedPackage listed = b.deployments.packages().stream().filter(x -> x.name().equals("shop")).findFirst().orElseThrow();
        assertEquals(DeployedPackage.State.FAILED, listed.state());
        assertTrue(listed.problems().toString().contains("no login with secret://tok"), listed.problems().toString());
        assertFalse(listed.problems().toString().contains("v4lue-tok"), listed.problems().toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("no login with secret://tok")), logged.toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("v4lue-tok")), logged.toString());
    }

    @Test
    void aStopThatFailsWithTheValueBeforeAnOverwriteLogsItScrubbed() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok");
        a.deploy(p);
        a.follow();
        a.doors.failStop("shop/orders", "no logout of v4lue-tok");
        MemorySecretStores.overwriteValue(a.store, "tok", "n3w-tok");
        a.follow();
        a.doors.stopCleanly("shop/orders");
        assertEquals("n3w-tok", a.option("shop/orders", "token"));
        assertTrue(logged.stream().anyMatch(l -> l.contains("no logout of secret://tok")), logged.toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("v4lue-tok")), logged.toString());
    }

    /** {@code relay}, at-least-once and one message at a time, whose partner endpoint's token is {@code secret://out-token}. */
    static PackageDraft relay() {
        PackageDraft p = PackageDraft.of("relay", "1.0.0")
                .endpoint("relay-in", "in", new JsonObject().put("path", "/relay"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner").put("token", "secret://out-token"))
                .flow("relay", "1.0.0", "at-least-once", "relay-in", 4096, PackageDraft.toPartner());
        p.flowDoc("relay").getJsonObject("limits").put("maxInFlight", 1);
        return p;
    }

    /**
     * relay's partner holds every send, so its two messages stay in the work queue when it is undeployed: its drain is built with
     * the store's value. An overwrite builds the drain again with the new value; a delete stops it, and it is not built again on its
     * old value: the node is UNDRAINED until a tick finds the secret again and builds the drain with it.
     */
    @Test
    void aDrainingFlowFollowsAChangeOfItsSecretAndIsNotRunOnAnOldValue() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "out-token", "one");
        a.deploy(relay());
        a.doors.partner().holdNext(100);
        a.accept("relay/relay");
        a.doors.partner().awaitSent(1);
        a.accept("relay/relay");
        assertEquals(2, a.queue.size(), "fixture: the first is held at the partner, the second waits behind it");

        a.undeploy("relay");
        assertEquals(List.of("one", "one"), a.tokens(), "the drain is built with the store's value");

        MemorySecretStores.overwriteValue(a.store, "out-token", "two");
        a.follow();
        assertEquals(List.of("one", "one", "two"), a.tokens(), "the drain is built again with the new value");
        assertTrue(a.deployments.health().detail() == null || !a.deployments.health().detail().contains("UNDRAINED"), "it drains");

        MemorySecretStores.delete(a.store, "out-token");
        a.follow();
        a.follow();
        assertEquals(List.of("one", "one", "two"), a.tokens(), "never built again on its old value");
        assertTrue(a.deployments.health().detail().contains("UNDRAINED: flow relay/relay"), a.deployments.health().detail());

        MemorySecretStores.createValue(a.store, "out-token", "three");
        rt.clock().advance(Duration.ofSeconds(5));
        a.follow();
        assertEquals(List.of("one", "one", "two", "three"), a.tokens(), "a tick that finds the secret again builds the drain with it");
        assertEquals(2, a.queue.size(), "every message is still to drain");
    }

    /** A drain whose stop for an overwrite fails with the value it was built with logs the failure with the reference in its place. */
    @Test
    void aDrainsStopThatFailsWithTheValueBeforeAnOverwriteLogsItScrubbed() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "out-token", "v4lue-one");
        a.deploy(relay());
        a.doors.partner().holdNext(100);
        a.accept("relay/relay");
        a.doors.partner().awaitSent(1);
        a.undeploy("relay");
        assertEquals(List.of("v4lue-one", "v4lue-one"), a.tokens(), "fixture: the drain runs on the first value");
        a.doors.failProducerClose(2, "no logout of v4lue-one");

        MemorySecretStores.overwriteValue(a.store, "out-token", "n3w-two");
        a.follow();

        assertEquals(List.of("v4lue-one", "v4lue-one", "n3w-two"), a.tokens());
        assertTrue(logged.stream().anyMatch(l -> l.contains("no logout of secret://out-token")), logged.toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("v4lue-one")), logged.toString());
    }

    /**
     * An overwrite of in-token lands while a deploy of shop holds the node's deploy lock, after the deploy resolved it: the tick
     * that meets the lock does nothing, and the first tick after the deploy builds orders again with the new value.
     */
    /** {@code p}, deployed while the start of {@code flow} is held, with {@code name} deleted before the start goes on. */
    DeployRefusedException refusedAfterADeleteDuringTheDeploy(Node node, PackageDraft p, String flow, String name) throws Exception {
        records.checkedBy = node.store;
        Doors.Gate held = node.doors.holdStartOf(flow);
        CompletableFuture<DeployRefusedException> deployed = CompletableFuture.supplyAsync(() -> node.refused(p));
        assertTrue(held.reached().await(30, TimeUnit.SECONDS), "fixture: the deploy built and starts " + flow);
        MemorySecretStores.delete(node.store, name);
        held.release();
        return deployed.get(30, TimeUnit.SECONDS);
    }

    @Test
    void aDeleteBetweenAFirstDeploysBuildAndItsSaveRefusesItAndLeavesNoFlowRunning() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");

        DeployRefusedException refused = refusedAfterADeleteDuringTheDeploy(a, shop(), "shop/orders", "in-token");
        assertEquals(DeployRefusedException.Kind.INVALID, refused.kind(), "422");
        assertEquals(RefusalCode.PACKAGE_INVALID, refused.code());
        assertEquals(List.of("flow 'orders': secret 'in-token' is not set"), refused.problems());
        assertTrue(a.deployments.packages().isEmpty(), "nothing is recorded: " + a.deployments.packages());
        assertTrue(a.deployments.flows().isEmpty(), "no flow runs, on the deleted value or any: " + a.deployments.flows());
        assertTrue(a.doors.door("shop/orders").stopped(), "the consumer that started is stopped");
    }

    @Test
    void aDeleteBetweenARedeploysBuildAndItsSaveRollsBackToThePreviousVersionUnrecorded() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.deploy(shop());
        PackageDraft next = shop();
        next.manifest().put("version", "1.1.0");
        next.endpointDoc("orders-in").getJsonObject("config").put("path", "/orders/v2");   // so orders is built and started anew

        DeployRefusedException refused = refusedAfterADeleteDuringTheDeploy(a, next, "shop/orders", "in-token");
        assertEquals(DeployRefusedException.Kind.INVALID, refused.kind(), "422");
        assertEquals(RefusalCode.ROLLED_BACK_UNRECORDED, refused.code());
        assertEquals(List.of("flow 'orders': secret 'in-token' is not set"), refused.problems());
        assertEquals(List.of("1.0.0"), a.deployments.packages().stream().map(DeployedPackage::version).toList(), "the previous version stays");

        a.follow();
        assertEquals(DeployedFlow.State.FAILED, a.state("shop/orders"), "the first tick stops the flow that held the deleted value");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/ping"));
    }

    @Test
    void aChangeThatLandsDuringADeployReachesTheFlowItStartedAtTheFirstTickAfterIt() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.follow();
        Doors.Gate held = a.doors.holdStartOf("shop/orders");
        CompletableFuture<Void> deployed = CompletableFuture.runAsync(() -> {
            try {
                a.deploy(shop());
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertTrue(held.reached().await(30, TimeUnit.SECONDS), "fixture: the deploy starts orders");

        MemorySecretStores.overwriteValue(a.store, "in-token", "two");
        a.follow();
        held.release();
        deployed.get(30, TimeUnit.SECONDS);
        assertEquals("one", a.option("shop/orders", "token"), "fixture: the deploy started orders with the value it resolved");

        a.follow();
        assertEquals("two", a.option("shop/orders", "token"), "the first tick after the deploy builds orders again with the new value");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"));
        assertEquals(1, a.built("shop/ping"), "and no other flow");
    }
}
