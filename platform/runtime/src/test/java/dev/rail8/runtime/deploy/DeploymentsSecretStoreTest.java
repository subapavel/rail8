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
 * An endpoint option's {@code secret://} reference on nodes with a secret store. In any option it is resolved from the store at
 * the flow's build, once, and is a use of kind value; a name of another kind or under {@code rail8.} refuses the deploy. A
 * changed value reaches a flow at its next build: no flow is rebuilt for a secret, and a drain keeps the values of its build. A
 * deploy or a restore keeps a running flow only when its hash and the values its endpoints resolved equal the candidate's. No
 * text about a build holds a value it read.
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
        /** Once set, each use a save writes is recorded in these stores too, as the record and the store share one database. */
        volatile MemorySecretStores linked;

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
                    MemorySecretStores into = linked;
                    if (into != null) written.forEach((flow, names) -> names.forEach((n, kinds) -> into.use(record.name() + "/" + flow, n, kinds)));
                    return lock.save(record, written, by, detail);
                }
            }));
        }
    }

    /** One node: its own data directory and Doors, the pool's records, and its store over the shared secret database. */
    /** The connection form of this test's Doors: {@code token} and {@code refuse} secret fields, the rest text. */
    static final Settings DESCRIBED = Settings.of(Field.text("path", "Path", "Where it listens.").in(), Field.text("url", "URL", "Where it sends."),
            Field.secret("token", "Token", "What it presents."), Field.secret("refuse", "Refusal", "A text its consumer's build throws with."),
            Field.secret("login", "Login", "A password or credentials.", Secret.Kind.VALUE, Secret.Kind.CREDENTIALS));

    final class Node {
        final Doors doors = new Doors().describe(DESCRIBED);
        final SecretStore store;
        final CountingQueue queue = new CountingQueue(rt.clock());
        final InMemoryMessageStore messages = new InMemoryMessageStore();
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
            deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages,
                    new InMemoryDlq(), queue, () -> 0L, records);
            nodes.add(this);
        }

        DeployResult deploy(PackageDraft p) throws Exception {
            String commit = repos.commit(p);
            return OnVirtualThread.call(() -> deployments.deploy(p.name(), commit, Deployments.ANY, TestActors.named("test")));
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

    /**
     * A reference in an option that is no secret field ({@code url}) and one in a secret field ({@code token}) both read the store,
     * are both uses of kind value, and a delete of either name is 409 {@code in-use}; a variable of the name, set after the
     * store's first write, is never read.
     */
    @Test
    void aReferenceInAnyOptionReadsTheStoreAndIsAUse() throws Exception {
        new Node("node-0", Map.of());   // the store's first join: nothing imported
        Node a = new Node("node-1", Map.of("RAIL8_SECRET_K", "rec://from-the-variable"));
        MemorySecretStores.createValue(a.store, "k", "rec://from-the-store");
        MemorySecretStores.createValue(a.store, "p", "p-1");
        records.linked = stores;
        a.deploy(pointed());
        assertEquals("rec://from-the-store", a.option("shop/orders", "url"), "the store serves any option");
        assertEquals("p-1", a.option("shop/orders", "token"));
        assertEquals(Map.of("orders", Map.of("k", Set.of(Secret.Kind.VALUE), "p", Set.of(Secret.Kind.VALUE)), "ping", Map.of()), records.uses.get("shop"),
                "every reference is a use");
        for (String name : List.of("k", "p")) {
            Future<Void> deleted = a.store.delete(MemorySecretStores.ADMIN, name, java.util.Optional.empty());
            assertTrue(deleted.failed(), name + " is in use");
            assertEquals("in-use", ((dev.rail8.runtime.secret.SecretRefused) deleted.cause()).code(), deleted.cause().toString());
        }
    }

    /**
     * An overwrite builds no flow on any node: each runs on the value of its build, and the next build of the flow, a restore on
     * a node that starts, takes the new value.
     */
    @Test
    void aChangedValueReachesAFlowAtItsNextBuildAndNoFlowIsRebuilt() throws Exception {
        Node a = new Node("node-1", Map.of());
        Node b = new Node("node-2", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.deploy(shop());
        b.restore();

        MemorySecretStores.overwriteValue(b.store, "in-token", "two");
        for (Node n : List.of(a, b)) {
            n.follow();
            n.follow();
            assertEquals("one", n.option("shop/orders", "token"), "the flow runs on the value of its build");
            assertEquals(1, n.built("shop/orders"), "no flow is built again for a secret");
            assertEquals(DeployedFlow.State.RUNNING, n.state("shop/orders"));
        }
        Node c = new Node("node-3", Map.of());
        c.restore();
        assertEquals("two", c.option("shop/orders", "token"), "the next build takes the new value");
    }

    /** A delete stops no flow: the built one runs on, and the next build of it fails, naming the reference and the store. */
    @Test
    void aDeleteLeavesTheBuiltFlowRunningAndFailsItsNextBuild() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "in-token", "one");
        a.deploy(shop());

        MemorySecretStores.delete(a.store, "in-token");
        a.follow();
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"), "a delete stops nothing");
        assertEquals(1, a.built("shop/orders"));

        Node b = new Node("node-2", Map.of());
        b.restore();
        DeployedPackage listed = b.deployments.packages().stream().filter(x -> x.name().equals("shop")).findFirst().orElseThrow();
        assertEquals(DeployedPackage.State.FAILED, listed.state(), listed.toString());
        assertTrue(listed.problems().contains("endpoint 'orders-in': secret 'secret://in-token' is not set: enter it in the secret store"),
                listed.problems().toString());
    }

    @Test
    void aNameOfAnotherKindOrOfThePlatformRefusesTheDeploy() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-token", "app", "pw-0815");
        DeployRefusedException kind = a.refused(shop());
        assertEquals(List.of("endpoint 'orders-in': the option 'token': secret 'secret://in-token' is a secret of kind credentials in the secret store, taken here as [value]"),
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

    /**
     * A refresh of the store between the resolution of an endpoint's value and the component's refusal, whose failure quotes the
     * option: the refusal, its problems, the audit and the log hold neither the value the build read nor the new one.
     */
    @Test
    void aRefreshBetweenTheResolutionAndTheComponentsRefusalLeavesNeitherValueInTheTexts() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok");
        a.doors.onConsumer(cfg -> {
            if (!"v4lue-tok".equals(cfg.options().get("token"))) return;
            MemorySecretStores.overwriteValue(a.store, "tok", "n3w-tok");
            throw new IllegalStateException("no login with " + cfg.options().get("token"));
        });
        DeployRefusedException refused = a.refused(p);
        String answer = refused.getMessage() + refused.error() + refused.problems();
        assertTrue(answer.contains("no login with secret://tok"), answer);
        for (String value : List.of("v4lue-tok", "n3w-tok")) {
            assertFalse(answer.contains(value), answer);
            assertFalse(pool.audited().toString().contains(value), pool.audited().toString());
            assertTrue(logged.stream().noneMatch(l -> l.contains(value)), logged.toString());
        }
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

    /** The stop of a flow a deploy swaps for a changed value, failing with the old value, logs the failure with the reference. */
    @Test
    void aStopForASwapThatFailsWithTheOldValueLogsItScrubbed() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "tok", "v4lue-tok");
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://tok");
        a.deploy(p);
        a.doors.failStop("shop/orders", "no logout of v4lue-tok");
        MemorySecretStores.overwriteValue(a.store, "tok", "n3w-tok");
        a.deploy(p);
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
     * the store's value, and keeps it after an overwrite: no drain is built again for a secret.
     */
    @Test
    void aDrainKeepsTheValuesOfItsBuild() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createValue(a.store, "out-token", "one");
        a.deploy(relay());
        java.util.concurrent.CountDownLatch held = a.doors.partner().holdNext(100);
        a.accept("relay/relay");
        a.doors.partner().awaitSent(1);
        a.accept("relay/relay");
        assertEquals(2, a.queue.size(), "fixture: the first is held at the partner, the second waits behind it");

        a.undeploy("relay");
        assertEquals(List.of("one", "one"), a.tokens(), "the drain is built with the store's value");

        MemorySecretStores.overwriteValue(a.store, "out-token", "two");
        a.follow();
        a.follow();
        assertEquals(List.of("one", "one"), a.tokens(), "the drain is not built again");
        assertEquals(2, a.queue.size(), "every message is still to drain");
        held.countDown();
    }

    /**
     * A deploy of unchanged files after an overwrite of a value an endpoint resolved swaps the flow: the partner gets a producer
     * with the new value; a second deploy without a change keeps it. The other node takes the new value through its reconcile.
     */
    @Test
    void anUnchangedFlowAfterAnOverwriteIsSwappedOnDeployAndKeptOnTheNext() throws Exception {
        Node a = new Node("node-1", Map.of());
        Node b = new Node("node-2", Map.of());
        MemorySecretStores.createValue(a.store, "out-token", "one");
        assertEquals(List.of("relay/relay"), a.deploy(relay()).added());
        b.follow();
        assertEquals(List.of("one"), b.tokens(), "fixture: the other node runs relay");

        MemorySecretStores.overwriteValue(a.store, "out-token", "two");
        DeployResult changed = a.deploy(relay());
        assertEquals(List.of("relay/relay"), changed.swapped(), "a changed value swaps the flow: " + changed);
        assertEquals("two", a.tokens().getLast(), "the partner's new producer has the new value");
        DeployResult again = a.deploy(relay());
        assertEquals(List.of(), again.swapped(), "nothing changed: the flow is kept: " + again);
        assertEquals(List.of(), again.added());

        b.follow();
        assertEquals("two", b.tokens().getLast(), "the other node's reconcile builds it with the new value");
    }

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
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"), "the previous version runs on the values of its build: a delete stops nothing");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/ping"));
    }

    /** An overwrite that lands while a deploy starts the flow it resolved: the flow keeps the value it was built with, and no tick builds it again. */
    @Test
    void aChangeThatLandsDuringADeployBuildsNothingAfterIt() throws Exception {
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
        a.follow();
        assertEquals("one", a.option("shop/orders", "token"), "the deploy started orders with the value it resolved");
        assertEquals(1, a.built("shop/orders"), "and no tick built it again");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"));
    }

    /** {@code shop}, the in endpoint of {@code orders} logging in with {@code secret://in-login}. */
    static PackageDraft logins() {
        PackageDraft p = shop();
        p.endpointDoc("orders-in").getJsonObject("config").remove("token");
        p.endpointDoc("orders-in").getJsonObject("config").put("login", "secret://in-login");
        return p;
    }

    /** The handle of the secret field {@code field} the newest consumer of {@code flow} was built with. */
    static Secret handle(Node node, String flow, String field) {
        return node.doors.inbound(flow).connection().orElseThrow().secret(field).orElseThrow();
    }

    @Test
    void aSecretFieldTakesAReferenceOfEachOfItsKindsAndItsUseHoldsThem() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-login", "app", "pw-0815");
        a.deploy(logins());
        Secret login = handle(a, "shop/orders", "login");
        assertEquals(Secret.Kind.CREDENTIALS, login.kind());
        assertEquals(new Secret.Credentials("app", "pw-0815"), login.credentials());
        assertEquals(Map.of("orders", Map.of("in-login", Set.of(Secret.Kind.VALUE, Secret.Kind.CREDENTIALS)), "ping", Map.of()), records.uses.get("shop"),
                "the use holds the kinds the field takes");

        MemorySecretStores.overwriteCredentials(a.store, "in-login", "app", "pw-4711");
        a.follow();
        assertEquals("pw-4711", login.credentials().password(), "an overwrite reaches the next read");
        assertEquals(1, a.built("shop/orders"), "no flow is built again for a typed secret");
    }

    @Test
    void aReferenceOfAKindTheFieldDoesNotTakeOrATypedOneOutsideASecretFieldRefusesTheDeploy() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-token", "app", "pw-0815");
        PackageDraft text = shop();
        text.endpointDoc("orders-in").getJsonObject("config").put("token", "secret://t").put("url", "secret://in-token");
        MemorySecretStores.createValue(a.store, "t", "one");
        assertEquals(List.of("endpoint 'orders-in': secret 'secret://in-token' is a secret of kind credentials in the secret store: the option 'url' is not a "
                + "secret field, so it takes a plain value only"), a.refused(text).problems());
        assertFalse(String.join(" ", a.refused(text).problems()).contains("pw-0815"));

        MemorySecretStores.createKeyPair(a.store, "in-key");
        PackageDraft key = logins();
        key.endpointDoc("orders-in").getJsonObject("config").put("login", "secret://in-key");
        assertEquals(List.of("endpoint 'orders-in': the option 'login': secret 'secret://in-key' is a secret of kind key-pair in the secret store, "
                + "taken here as [value, credentials]"), a.refused(key).problems(), "the field takes a plain value or credentials, not a key pair");
    }

    @Test
    void aDeleteAndACreateOfAnotherKindFailTheRunningFlowsNextReadNamingBothKindsUntilItsNextBuild() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-login", "app", "pw-0815");
        a.deploy(logins());
        Secret login = handle(a, "shop/orders", "login");

        MemorySecretStores.delete(a.store, "in-login");
        MemorySecretStores.createValue(a.store, "in-login", "plain-1");
        a.follow();
        IllegalStateException other = assertThrows(IllegalStateException.class, login::credentials);
        assertEquals("secret://in-login is now a secret of kind value in the secret store, and this flow took it as credentials: the flow's next build "
                + "takes it as its new kind", other.getMessage());
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"), "no flow is built again for it");

        PackageDraft next = logins();
        next.manifest().put("version", "1.1.0");
        next.endpointDoc("orders-in").getJsonObject("config").put("path", "/orders/v2");   // so orders is built anew
        a.deploy(next);
        Secret fresh = handle(a, "shop/orders", "login");
        assertEquals(Secret.Kind.VALUE, fresh.kind(), "the next build takes the new kind");
        assertEquals("plain-1", fresh.value());
    }

    @Test
    void aFailureQuotingAPasswordReadBeforeAnOverwriteIsLoggedScrubbed() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-login", "app", "pw-first-0815");
        a.deploy(logins());
        Secret login = handle(a, "shop/orders", "login");
        a.doors.failStop("shop/orders", "no logout of " + login.credentials().user() + " with " + login.credentials().password());
        MemorySecretStores.overwriteCredentials(a.store, "in-login", "app", "pw-second-4711");
        a.follow();
        assertEquals("pw-second-4711", login.credentials().password(), "the consumer reads the new password at its next session");
        a.undeploy("shop");
        assertTrue(logged.stream().anyMatch(l -> l.contains("no logout of app with secret://in-login")), logged.toString());
        assertTrue(logged.stream().noneMatch(l -> l.contains("pw-first-0815")), logged.toString());
    }

    @Test
    void aStepFailureQuotingThePasswordItsConsumerReadBeforeAnOverwriteIsRecordedWithItsReference() throws Exception {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-login", "app", "pw-first-0815");
        a.deploy(logins());
        Secret login = handle(a, "shop/orders", "login");
        String password = login.credentials().password();   // the consumer logs in
        MemorySecretStores.overwriteCredentials(a.store, "in-login", "app", "pw-second-4711");
        a.follow();
        a.doors.partner().failNext(1, () -> new IllegalStateException("the partner refused the order of app, logged in with " + password));
        Envelope e = rt.ingress("shop/orders", Headers.empty(), Buffer.buffer("<order/>"));
        Doors.Door door = a.doors.door("shop/orders");
        door.deliver(e).otherwiseEmpty().await(10, TimeUnit.SECONDS);
        String timeline = String.valueOf(within(a.messages.timeline(e.id()), "the timeline"));
        assertTrue(timeline.contains("the partner refused the order of app, logged in with secret://in-login"), timeline);
        assertFalse(timeline.contains("pw-first-0815"), timeline);
    }
}
