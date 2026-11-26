package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.cache.InMemoryCache;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.SequentialIntake;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.InMemoryEnvironment;
import dev.rail8.runtime.deploy.DeploymentStore.ConnectionUse;
import dev.rail8.runtime.deploy.DeploymentStore.DeployedSystem;
import dev.rail8.runtime.lease.InMemoryPollerLeases;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.refusal.RefusalCode;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deploy of system files and of flows of {@code schemaVersion: 2}: the checks against the pool's systems as they would be after
 * it and against the environment, the record it writes, an undeploy of a system still named, the sends and the sender through
 * the environment's values, and a sender started again when its connection changes.
 */
class DeploymentsConnectionsTest {

    /** The connection form of this test's adapter: a required {@code url} where it is, and a {@code token} secret. */
    static final Settings CONNECTION = Settings.of(Field.text("url", "URL", "Where it is.").required().server(),
            Field.secret("token", "Token", "Its login."));

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryDeploymentStore pool = new InMemoryDeploymentStore();
    final InMemoryEnvironment env = new InMemoryEnvironment("dev");
    final MemorySecretStores stores = new MemorySecretStores();
    final Doors doors = new Doors().describe(CONNECTION);
    /** The adapter the node runs: {@link #doors} unless a case set another. */
    Component adapter = doors;
    Deployments deployments;
    SecretStore store;

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        if (deployments != null) OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
    }

    /** The node of this test, over {@link #env} and a secret store of its own. */
    Deployments node() {
        repos = new GitRepos(tmp.resolve("repos"));
        Map<String, String> variables = new HashMap<>(DeploymentsSecretStoreTest.KEY);
        variables.put("RAIL8_ENV", "dev");
        variables.put("RAIL8_NODE_ID", "node-1");
        variables.put("RAIL8_DATA_DIR", tmp.resolve("node-1").toString());
        variables.put("RAIL8_GIT_BASE", repos.base().toString());
        store = stores.start("node-1", variables, rt.clock(), rt.timer());
        NodeConfig config = NodeConfig.from(variables);
        config = config.withSecrets(config.secrets().withStore(store));
        Map<String, Component> components = Map.of(Doors.SCHEME, adapter);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        CountingQueue queue = new CountingQueue(rt.clock());
        Workers workers = new Workers();
        deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, new InMemoryDlq(), queue,
                () -> 0L, pool, new SequentialIntake(messages, queue, workers, "node-1"), new InMemoryPollerLeases(rt.clock()),
                new Targets(config.targets().defaults(), config.targets().overrides(), rt.timer()), () -> true, workers, WorkSignals.NONE,
                new InMemoryCache(rt.clock(), InMemoryCache.MAX_ENTRIES, InMemoryCache.MAX_BYTES), 0, env);
        return deployments;
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

    DeployRefusedException undeployRefused(String name) {
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.undeploy(name, TestActors.named("test"))));
    }

    /** The system {@code acme/erp} with the connections {@code connections}, each of {@code adapter}, in {@code systems/acme-erp.yaml}. */
    static PackageDraft withSystem(PackageDraft p, String key, String adapter, String... connections) {
        JsonObject named = new JsonObject();
        for (String c : connections) named.put(c, new JsonObject().put("adapter", adapter));
        JsonObject doc = new JsonObject().put("schemaVersion", 1).put("kind", "system").put("connections", named);
        int slash = key.indexOf('/');
        if (slash < 0) doc.put("name", key);
        else doc.put("partner", key.substring(0, slash)).put("name", key.substring(slash + 1));
        return p.file("systems/" + key.replace('/', '-') + ".yaml", doc.encodePrettily());
    }

    /** A flow {@code id} of {@code schemaVersion: 2}: from {@code from}, delivery {@code mode}, one send step to {@code to}. */
    static PackageDraft withFlow(PackageDraft p, String id, String mode, String from, String to) {
        JsonObject doc = new JsonObject().put("schemaVersion", 2).put("kind", "flow").put("id", id).put("version", "1.0.0")
                .put("from", new JsonObject().put("adapter", Doors.SCHEME).put("connection", from))
                .put("delivery", new JsonObject().put("mode", mode)).put("limits", new JsonObject().put("maxBodyBytes", 4096))
                .put("steps", new JsonArray().add(new JsonObject().put("type", "to").put("name", "send").put("adapter", Doors.SCHEME)
                        .put("connection", to).put("settings", new JsonObject().put("level", "high"))));
        return p.file("flows/" + id + ".yaml", doc.encodePrettily());
    }

    /** The package {@code erp}: the system {@code acme/erp}, its connections {@code inbox} and {@code rest}. */
    static PackageDraft erp() {
        return withSystem(PackageDraft.of("erp", "1.0.0"), "acme/erp", Doors.SCHEME, "inbox", "rest");
    }

    /** The package {@code shop}: the flow {@code orders}, from {@code acme/erp/inbox} to {@code acme/erp/rest}. */
    static PackageDraft shop() {
        return withFlow(PackageDraft.of("shop", "1.0.0"), "orders", "sync", "acme/erp/inbox", "acme/erp/rest");
    }

    /** The values of a connection of this test's adapter, at {@code version}. */
    static ConnectionState values(long version, String url) {
        return new ConnectionState.Values(Doors.SCHEME, version, Map.of("url", url), Optional.empty());
    }

    /** {@link #env} serving both connections of {@code acme/erp}, with its instance name. */
    void serving() {
        env.connection("acme/erp/inbox", values(1, "rec://inbox")).connection("acme/erp/rest", values(2, "rec://rest"))
                .instanceName("acme/erp", "ACME-ERP-DEV");
    }

    /** A message the sync flow {@code flow} answers with its partner's reply. */
    void accept(String flow) throws Exception {
        Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
        Doors.Door door = doors.door(flow);
        assertInstanceOf(Ack.Reply.class, within(door.deliver(e), door + "'s answer"), "fixture: " + door + " answers");
    }

    /**
     * A deploy writes, with its record, the systems its package defines, every connection its flows reach and the generation it
     * checked; its sender and its send step get the environment's values.
     */
    @Test
    void aDeployRecordsItsSystemsItsUsesAndTheGenerationAndItsFlowGetsTheValues() throws Exception {
        node();
        serving();
        deploy(withFlow(erp(), "orders", "sync", "acme/erp/inbox", "acme/erp/rest"));

        List<DeployedSystem> systems = within(pool.systems(), "systems");
        assertEquals(List.of("acme/erp"), systems.stream().map(DeployedSystem::key).toList());
        assertEquals("erp", systems.getFirst().deployment());
        assertEquals(Optional.of(Doors.SCHEME), systems.getFirst().adapter("rest"));
        assertEquals(List.of(new ConnectionUse("erp", "erp/orders", "from", "acme/erp/inbox", Doors.SCHEME, "in", false, Optional.empty()),
                new ConnectionUse("erp", "erp/orders", "send", "acme/erp/rest", Doors.SCHEME, "out", false, Optional.empty())),
                within(pool.connectionUses(), "uses"));
        assertEquals(Optional.of(env.current().generation()), pool.configGeneration("erp"));

        assertEquals("rec://inbox", doors.door("erp/orders").cfg().options().get("url"), "the sender gets the connection's values");
        accept("erp/orders");
        assertEquals(List.of("rec://rest"), doors.producerConfigs("acme/erp/rest").stream().map(c -> c.options().get("url")).toList(),
                "the send step's producer is built from the connection's values");
    }

    /**
     * The systems the pool's records define, each connection with its state in the environment and the flows that reach it,
     * never a value.
     */
    @Test
    void theSystemsListEachConnectionsStateAndTheFlowsThatReachIt() throws Exception {
        node();
        serving();
        deploy(withSystem(erp(), "billing", Doors.SCHEME, "inbox"));
        deploy(shop());
        env.connection("acme/erp/inbox", new ConnectionState.Values(Doors.SCHEME, 1, Map.of("url", "rec://inbox"),
                Optional.of(new ConnectionState.Stop("ops", Instant.parse("2026-11-26T10:00:00Z")))));

        JsonObject listed = OnVirtualThread.call(deployments::systems);
        assertEquals(new JsonObject("""
                {"environment": "dev", "systems": [
                  {"key": "acme/erp", "package": "erp", "partner": "acme", "name": "erp", "description": null, "instanceName": "ACME-ERP-DEV",
                   "connections": [
                     {"name": "inbox", "key": "acme/erp/inbox", "adapter": "rec", "description": null, "state": "stopped",
                      "flows": [{"flow": "shop/orders", "step": "from"}]},
                     {"name": "rest", "key": "acme/erp/rest", "adapter": "rec", "description": null, "state": "set",
                      "flows": [{"flow": "shop/orders", "step": "send"}]}]},
                  {"key": "billing", "package": "erp", "partner": null, "name": "billing", "description": null, "instanceName": null,
                   "connections": [
                     {"name": "inbox", "key": "billing/inbox", "adapter": "rec", "description": null, "state": "missing", "flows": []}]}]}
                """), listed);
        assertTrue(!listed.encode().contains("rec://"), "no value of a connection");
    }

    /**
     * Each connection a flow reaches that the environment cannot serve is one line naming the flow, the step, the key and the
     * environment, never a value; so is a system without an instance name. A stopped connection is not refused.
     */
    @Test
    void eachConnectionTheEnvironmentCannotServeIsALineOfTheRefusal() throws Exception {
        node();
        PackageDraft p = withSystem(erp(), "globex/erp", Doors.SCHEME, "rest");
        p = withFlow(p, "orders", "sync", "acme/erp/inbox", "acme/erp/rest");
        p = withFlow(p, "invoices", "sync", "acme/erp/inbox", "globex/erp/rest");
        env.connection("acme/erp/inbox", new ConnectionState.Values(Doors.SCHEME, 1, Map.of("url", "rec://inbox"),
                        Optional.of(new ConnectionState.Stop("ops", Instant.parse("2026-11-26T10:00:00Z")))))
                .connection("acme/erp/rest", new ConnectionState.Values(Doors.SCHEME, 2, Map.of(), Optional.empty()))
                .connection("globex/erp/rest", new ConnectionState.Values(Doors.SCHEME, 3, Map.of("url", "rec://globex", "token", "secret://globex-token"),
                        Optional.empty()))
                .instanceName("acme/erp", "ACME-ERP-DEV");

        DeployRefusedException refused = refused(p);
        assertEquals(RefusalCode.PACKAGE_INVALID, refused.code());
        assertEquals(List.of(
                "flow 'invoices' step 'send' connection 'globex/erp/rest' in the environment 'dev': not set: its field 'token': secret "
                        + "'secret://globex-token' is not set: enter it in the secret store",
                "flow 'invoices' step 'send' system 'globex/erp' has no instance name in the environment 'dev'",
                "flow 'orders' step 'send' connection 'acme/erp/rest' in the environment 'dev': incomplete for the out direction: url (required)"),
                refused.problems().stream().sorted().toList());
        assertEquals(List.of(), within(pool.systems(), "systems"), "a refused deploy writes nothing");
    }

    /** A connection key no deployed package and not this one defines, and one whose system names another adapter, are refused. */
    @Test
    void aKeyNoPackageDefinesAndAnotherAdapterAreRefused() throws Exception {
        node();
        serving();
        PackageDraft p = withSystem(PackageDraft.of("shop", "1.0.0"), "acme/erp", "web", "inbox");
        p = withFlow(p, "orders", "sync", "acme/erp/inbox", "globex/erp/rest");

        DeployRefusedException refused = refused(p);
        assertTrue(refused.problems().contains("system 'acme/erp' connection 'inbox': no adapter 'web' on this node"), refused.problems().toString());
        assertTrue(refused.problems().contains("flow 'orders' from connection 'acme/erp/inbox': its system file names the adapter 'web', the step 'rec'"),
                refused.problems().toString());
        assertTrue(refused.problems().contains("flow 'orders' step 'send' connection 'globex/erp/rest': no deployed package and not this one defines "
                + "it: deploy the package of its system first"), refused.problems().toString());
    }

    /**
     * A system is defined by one deployed package: another package's flows name it without a dependency; while they do, its package
     * neither drops a connection they name nor goes, and no other package defines it again.
     */
    @Test
    void aSystemOfAnotherPackageIsNamedAndKeptWhileItsConnectionsAreUsed() throws Exception {
        node();
        serving();
        deploy(erp());
        deploy(shop());
        assertEquals(DeployedFlow.State.RUNNING, deployments.flows().getFirst().state());

        DeployRefusedException dropped = refused(withSystem(PackageDraft.of("erp", "1.0.1"), "acme/erp", Doors.SCHEME, "inbox"));
        assertEquals(List.of("system 'acme/erp' drops its connection 'rest' while the flow 'shop/orders' step 'send' names it"), dropped.problems());

        DeployRefusedException again = refused(withSystem(PackageDraft.of("crm", "1.0.0"), "acme/erp", Doors.SCHEME, "inbox", "rest"));
        assertEquals(List.of("system 'acme/erp' is defined by the package 'erp': a system key is defined by one deployed package, and this package "
                + "'crm' defines it too"), again.problems());

        DeployRefusedException undeploy = undeployRefused("erp");
        assertEquals(RefusalCode.IN_USE, undeploy.code());
        assertEquals(List.of("system 'acme/erp' of the package 'erp' cannot go while the flow 'shop/orders' from names it",
                "system 'acme/erp' of the package 'erp' cannot go while the flow 'shop/orders' step 'send' names it"), undeploy.problems());

        OnVirtualThread.call(() -> deployments.undeploy("shop", TestActors.named("test")));
        OnVirtualThread.call(() -> deployments.undeploy("erp", TestActors.named("test")));
        assertEquals(List.of(), within(pool.systems(), "systems"), "an undeploy takes its systems");
    }

    /** The checks run against the pool as the deploy leaves it: a package drops a flow and the connection it named together. */
    @Test
    void aPackageDropsAFlowAndItsConnectionTogether() throws Exception {
        node();
        serving();
        deploy(withFlow(erp(), "orders", "sync", "acme/erp/inbox", "acme/erp/rest"));
        deploy(withSystem(PackageDraft.of("erp", "1.0.1"), "acme/erp", Doors.SCHEME, "inbox"));
        assertEquals(List.of(), within(pool.connectionUses(), "uses"));
        assertEquals(Optional.empty(), within(pool.systems(), "systems").getFirst().adapter("rest"));
    }

    /** {@link Doors} as a polling adapter: a sender polls its connection at the place its {@code address} names, {@code *} without one. */
    record Polling(Doors inner) implements Component {
        @Override public String scheme() { return inner.scheme(); }
        @Override public Forms forms() { return inner.forms(); }
        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { return inner.consumer(in, handler); }
        @Override public Producer producer(Outbound out) { return inner.producer(out); }
        @Override public Future<Health> health() { return inner.health(); }
        @Override public Optional<String> target(Outbound out) { return inner.target(out); }
        @Override public Optional<String> polledPlace(Inbound in) {
            return Optional.of(in.connection().orElseThrow().key() + " " + in.settings().getOrDefault("address", "*"));
        }
    }

    /** Two senders never poll one place of a connection: two flows of one package, nor a flow of another package. */
    @Test
    void twoSendersNeverPollOnePlaceOfAConnection() throws Exception {
        adapter = new Polling(doors);
        node();
        serving();
        deploy(erp());
        DeployRefusedException twice = refused(withFlow(shop(), "returns", "sync", "acme/erp/inbox", "acme/erp/rest"));
        assertEquals(1, twice.problems().size(), "" + twice.problems());
        assertTrue(twice.problems().getFirst().endsWith("polls: two senders never poll one place of a connection"), "" + twice.problems());

        deploy(shop());
        DeployRefusedException other = refused(withFlow(PackageDraft.of("billing", "1.0.0"), "orders", "sync", "acme/erp/inbox", "acme/erp/rest"));
        assertEquals(List.of("flow 'billing/orders' from connection 'acme/erp/inbox': it polls the place the flow 'shop/orders' polls: two senders "
                + "never poll one place of a connection"), other.problems());
    }

    /** A first segment of a system key is a partner or a system without a partner across the installation, never both. */
    @Test
    void aFirstSegmentIsAPartnerOrASystemNeverBoth() throws Exception {
        node();
        serving();
        deploy(erp());
        DeployRefusedException refused = refused(withSystem(PackageDraft.of("acme", "1.0.0"), "acme", Doors.SCHEME, "inbox"));
        assertEquals(List.of("system 'acme': it is the partner of the system 'acme/erp' too: a first segment is a partner or a system, never both"),
                refused.problems());
    }

    /** A flow that retires keeps its connections until its drain ends: its own package drops none of them meanwhile. */
    @Test
    void aRetiringFlowKeepsTheConnectionsItNames() throws Exception {
        node();
        serving();
        deploy(withFlow(erp(), "orders", "at-least-once", "acme/erp/inbox", "acme/erp/rest"));
        deploy(withSystem(PackageDraft.of("erp", "1.0.1"), "acme/erp", Doors.SCHEME, "inbox", "rest"));   // the flow retires
        assertEquals(List.of("erp/orders"), within(pool.read(), "the pool").marked(), "fixture: the flow is marked");

        DeployRefusedException refused = refused(withSystem(PackageDraft.of("erp", "1.0.2"), "acme/erp", Doors.SCHEME, "inbox"));
        assertEquals(List.of("system 'acme/erp' drops its connection 'rest' while the flow 'erp/orders' step 'send' names it"), refused.problems());
    }

    /**
     * A sender whose connection is stopped does not start and is listed RESTARTING, a deploy all the same; the activation that
     * starts it again restarts it at once, with no delay, and it runs on the connection's new values.
     */
    @Test
    void aSenderStartsOnceItsConnectionServesAndAgainWhenItChanges() throws Exception {
        node();
        serving();
        deploy(erp());
        ConnectionState.Stop stop = new ConnectionState.Stop("ops", Instant.parse("2026-11-26T10:00:00Z"));
        env.connection("acme/erp/inbox", new ConnectionState.Values(Doors.SCHEME, 1, Map.of("url", "rec://inbox"), Optional.of(stop)));
        deploy(shop());
        assertEquals(DeployedFlow.State.RESTARTING, deployments.flows().getFirst().state(), "its start failed: it is tried again");
        assertEquals(0, doors.doors().size());

        env.connection("acme/erp/inbox", values(1, "rec://inbox"));
        rt.clock().advance(Duration.ZERO);
        DeploymentsCustomCodeTest.until(() -> doors.doors().size() == 1, "the restart");
        assertEquals(DeployedFlow.State.RUNNING, deployments.flows().getFirst().state());
        accept("shop/orders");

        env.connection("acme/erp/inbox", values(4, "rec://moved"));
        rt.clock().advance(Duration.ZERO);
        DeploymentsCustomCodeTest.until(() -> doors.doors().size() == 2, "the restart");
        assertEquals("rec://moved", doors.door("shop/orders").cfg().options().get("url"), "the new values start");

        env.instanceName("acme/erp", "ACME-ERP-DEV-2");   // an activation that leaves its connection as it was restarts nothing
        rt.clock().advance(Duration.ZERO);
        assertEquals(2, doors.doors().size());
    }

    /** A secret a connection names is read as a live handle: set once the deploy refused, the deploy goes through. */
    @Test
    void aSecretOfAConnectionIsCheckedAndHandedLive() throws Exception {
        node();
        serving();
        env.connection("acme/erp/rest", new ConnectionState.Values(Doors.SCHEME, 2, Map.of("url", "rec://rest", "token", "secret://rest-token"),
                Optional.empty()));
        deploy(erp());
        assertEquals(1, refused(shop()).problems().size());
        MemorySecretStores.createValue(store, "rest-token", "t-1");
        deploy(shop());
        accept("shop/orders");
        Object token = doors.producerConfigs("acme/erp/rest").getFirst().options().get("token");
        assertEquals("t-1", token instanceof Secret s ? s.value() : token);
    }
}
