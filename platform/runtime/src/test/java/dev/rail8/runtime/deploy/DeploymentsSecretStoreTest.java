package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An endpoint option's {@code secret://} reference on nodes with a secret store: the store's plain value wins over the variable,
 * a name of another kind or under {@code rail8.} refuses the deploy, and in the interim, while endpoint files hold references, a
 * change of a name an endpoint resolved builds again on every node the flows that hold it, and no other; one whose secret is gone
 * is stopped and listed FAILED until a tick finds it again.
 */
class DeploymentsSecretStoreTest {

    static final Map<String, String> KEY = Map.of("RAIL8_SECRETS_KEY", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");

    @TempDir Path tmp;
    GitRepos repos;
    final FakeRuntime rt = FakeRuntime.create();
    final MemorySecretStores stores = new MemorySecretStores();
    final InMemoryDeploymentStore records = new InMemoryDeploymentStore();
    final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void closeEveryNode() throws Exception {
        for (Node n : nodes) n.doors.releaseAll();
        for (Node n : nodes) OnVirtualThread.call(() -> {
            n.deployments.close();
            return null;
        });
    }

    /** One node: its own data directory and Doors, the pool's records, and its store over the shared secret database. */
    final class Node {
        final Doors doors = new Doors();
        final SecretStore store;
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
                    new InMemoryDlq(), new CountingQueue(rt.clock()), () -> 0L, records);
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

        /** How many consumers this node built of {@code flow}. */
        long built(String flow) {
            return doors.doors().stream().filter(d -> flow.equals(d.flow())).count();
        }

        /** The url the newest consumer of {@code flow} got. */
        Object url(String flow) {
            return doors.door(flow).cfg().options().get("url");
        }

        DeployedFlow.State state(String flow) {
            return deployments.flows().stream().filter(f -> f.id().equals(flow)).findFirst().orElseThrow().state();
        }
    }

    /** {@code orders}, whose in endpoint's url is {@code secret://in-url}, and {@code ping}, whose in endpoint holds no secret. */
    static PackageDraft shop() {
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders").put("url", "secret://in-url"))
                .endpoint("ping-in", "in", new JsonObject().put("path", "/ping").put("url", "rec://ping"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("orders", "1.0.0", "sync", "orders-in", 4096, PackageDraft.toPartner())
                .flow("ping", "1.0.0", "at-most-once", "ping-in", 2048, PackageDraft.toPartner());
    }

    @Test
    void theStoresValueWinsOverTheVariable() throws Exception {
        Node a = new Node("node-1", Map.of("RAIL8_SECRET_IN_URL", "rec://from-the-variable"));
        MemorySecretStores.createValue(a.store, "in-url", "rec://from-the-store");
        a.deploy(shop());
        assertEquals("rec://from-the-store", a.url("shop/orders"));
    }

    @Test
    void anOverwriteRebuildsTheFlowThatResolvedItOnEveryNodeAndNoOther() throws Exception {
        Node a = new Node("node-1", Map.of());
        Node b = new Node("node-2", Map.of());
        MemorySecretStores.createValue(a.store, "in-url", "rec://one");
        a.deploy(shop());
        b.restore();
        assertEquals("rec://one", b.url("shop/orders"));

        MemorySecretStores.overwriteValue(b.store, "in-url", "rec://two");
        for (Node n : List.of(a, b)) {
            n.follow();
            assertEquals("rec://two", n.url("shop/orders"), "the flow is built again with the new value");
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
        MemorySecretStores.createValue(a.store, "in-url", "rec://one");
        a.deploy(shop());

        MemorySecretStores.delete(a.store, "in-url");
        a.follow();
        assertEquals(DeployedFlow.State.FAILED, a.state("shop/orders"), "never left running on its old value");
        assertTrue(a.doors.door("shop/orders").stopped(), "its consumer stopped");
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/ping"));
        a.follow();
        assertEquals(DeployedFlow.State.FAILED, a.state("shop/orders"), "each tick tries again");

        MemorySecretStores.createValue(a.store, "in-url", "rec://three");
        rt.clock().advance(Duration.ofSeconds(5));
        a.follow();
        assertEquals(DeployedFlow.State.RUNNING, a.state("shop/orders"));
        assertEquals("rec://three", a.url("shop/orders"));
    }

    @Test
    void aNameOfAnotherKindOrOfThePlatformRefusesTheDeploy() {
        Node a = new Node("node-1", Map.of());
        MemorySecretStores.createCredentials(a.store, "in-url", "app", "pw-0815");
        DeployRefusedException kind = a.refused(shop());
        assertEquals(List.of("endpoint 'orders-in': secret 'secret://in-url' is a secret of kind credentials in the secret store, taken here as [value]"),
                kind.problems());

        PackageDraft platform = shop();
        platform.endpointDoc("orders-in").getJsonObject("config").put("url", "secret://rail8.git");
        DeployRefusedException reserved = a.refused(platform);
        assertEquals(1, reserved.problems().size(), reserved.problems().toString());
        assertTrue(reserved.problems().getFirst().startsWith("endpoint 'orders-in': 'secret://rail8.git' names a secret of the platform"),
                reserved.problems().toString());
    }
}
