package dev.rail8.runtime.admin;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Choice;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.api.settings.Tab;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.SequentialIntake;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.InMemoryDeploymentStore;
import dev.rail8.runtime.lease.InMemoryPollerLeases;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static dev.rail8.runtime.admin.AdminServerDeployTest.Answer;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertError;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertUnauthenticated;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /admin/components} lists every component the node serves, sorted by scheme, each with its HTTP prefix and its
 * forms as {@code settings} (format 2: the reach of each direction, both forms, their text keys), after the node's environment and the origin senders call its HTTP port at: 200
 * {@code {environment, httpOrigin, items:[{scheme, httpPrefix, settings}]}}. It needs the token like every {@code /admin/*} route.
 */
class AdminServerComponentsTest {

    static final String TOKEN = "s3cret-token";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    Deployments deployments;
    AdminServer server;

    /**
     * A component of the scheme {@code drop} whose in direction reaches a connection: the connection form a host, a port and
     * two alternatives, the flow form a polling tab; it is never started.
     */
    static final class Drop implements Component {
        static final Tab POLLING = Tab.of("polling", "Polling");

        @Override public String scheme() { return "drop"; }
        @Override public Forms forms() {
            return new Forms(Settings.of(
                            Field.text("host", "Host", "The server.").in().server(),
                            Field.integer("port", "Port", "The port.").range(1, 65535).byDefault(22).in().server(),
                            Field.secret("password", "Password", "The password.").tab(Tab.SECURITY).in(),
                            Field.secret("privateKey", "Private key", "The key.").tab(Tab.SECURITY).in())
                    .exactlyOne("password", "privateKey"),
                    Settings.of(
                            Field.choice("afterSuccess", "After success", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move"))
                                    .byDefault("delete").tab(POLLING).in(),
                            Field.text("moveTo", "Move to", "Where to.").required().visibleWhen("afterSuccess", "move").tab(POLLING).in()),
                    Map.of(Direction.IN, Forms.Reach.CONNECTION));
        }
        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(Outbound out) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** A component of the scheme {@code web} that takes requests on the node's HTTP port under {@code /web}; it is never started. */
    static final class Web implements Component {
        static final Forms FORMS = new Forms(Settings.NONE, Settings.of(HttpIngress.ADDRESS), Map.of(Direction.IN, Forms.Reach.NO_CONNECTION));

        @Override public String scheme() { return "web"; }
        @Override public Optional<String> httpPrefix() { return Optional.of("/web"); }
        @Override public Forms forms() { return FORMS; }
        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(Outbound out) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    @BeforeAll
    static void up() {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        vertx.close().await();
    }

    @BeforeEach
    void wire() throws Exception {
        wire(Map.of());
    }

    /** The node of environment {@code dev}, its env with {@code extra} too; a node wired before, never started, is closed. */
    void wire(Map<String, String> extra) throws Exception {
        if (deployments != null) {
            Deployments before = deployments;
            OnVirtualThread.call(() -> { before.close(); return null; });
        }
        Map<String, String> e = new HashMap<>(extra);
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        Doors doors = new Doors();
        Map<String, Component> components = Map.of(Doors.SCHEME, doors, "drop", new Drop(), "web", new Web());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        deployments = new Deployments(NodeConfig.from(e), runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                queue, () -> 0L, new InMemoryDeploymentStore(), new SequentialIntake(messages, queue),
                new InMemoryPollerLeases(rt.clock()), new Targets(new Targets.Limit(3, Duration.ofMillis(750)), Map.of(), rt.timer()));
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(checks, () -> 0L), Optional.of(deployments), new InMemoryWorkQueue(rt.clock()), Optional.empty());
    }

    @AfterEach
    void stop() throws Exception {
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    Answer get(int port, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/components"))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    req.response().onSuccess(HttpClientResponse::pause);   // the body may arrive before the compose below attaches to it
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> all = res.body();
                    res.resume();
                    return all.map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b));
                })
                .await(20, TimeUnit.SECONDS);
    }

    /**
     * {@code drop} before {@code rec} before {@code web}: sorted by scheme, only {@code web} with an HTTP prefix. Each lists its
     * forms in format 2: the reach of each direction it serves, whether each form describes its fields, the tabs (its own
     * between general and retry), the fields of each form with their text keys, and the rules; the undescribed {@code rec}
     * the common tabs and no field.
     */
    @Test
    void theComponentsAreListedBySchemeWithTheirForms() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        Answer listed = get(port, "Bearer " + TOKEN);
        assertJson(200, listed, "GET /admin/components");
        JsonObject expected = new JsonObject("""
                {"environment": "dev", "httpOrigin": null, "items": [
                  {"scheme": "drop", "httpPrefix": null, "settings": {"format": 2, "reach": {"in": "connection"},
                    "described": {"connection": true, "flow": true},
                    "tabs": [{"id": "general", "label": "General", "labelKey": "rail8.tab.general", "common": true},
                             {"id": "polling", "label": "Polling", "labelKey": "drop.tab.polling", "common": false},
                             {"id": "retry", "label": "Retry", "labelKey": "rail8.tab.retry", "common": true},
                             {"id": "recording", "label": "Recording", "labelKey": "rail8.tab.recording", "common": true},
                             {"id": "security", "label": "Security", "labelKey": "rail8.tab.security", "common": true}],
                    "connection": [
                      {"name": "host", "type": "text", "label": "Host", "labelKey": "drop.field.host.label", "help": "The server.",
                       "helpKey": "drop.field.host.help", "tab": "general", "required": false, "directions": ["in"], "server": true},
                      {"name": "port", "type": "integer", "label": "Port", "labelKey": "drop.field.port.label", "help": "The port.",
                       "helpKey": "drop.field.port.help", "tab": "general", "required": false, "directions": ["in"], "default": 22, "min": 1, "max": 65535, "server": true},
                      {"name": "password", "type": "secret", "label": "Password", "labelKey": "drop.field.password.label", "help": "The password.",
                       "helpKey": "drop.field.password.help", "tab": "security", "required": false, "directions": ["in"], "kinds": ["value"]},
                      {"name": "privateKey", "type": "secret", "label": "Private key", "labelKey": "drop.field.privateKey.label", "help": "The key.",
                       "helpKey": "drop.field.privateKey.help", "tab": "security", "required": false, "directions": ["in"], "kinds": ["value"]}],
                    "flow": [
                      {"name": "afterSuccess", "type": "choice", "label": "After success", "labelKey": "drop.field.afterSuccess.label",
                       "help": "What then.", "helpKey": "drop.field.afterSuccess.help", "tab": "polling", "required": false, "directions": ["in"],
                       "default": "delete", "choices": [{"value": "delete", "label": "Delete", "labelKey": "drop.field.afterSuccess.choice.delete"},
                                                        {"value": "move", "label": "Move", "labelKey": "drop.field.afterSuccess.choice.move"}]},
                      {"name": "moveTo", "type": "text", "label": "Move to", "labelKey": "drop.field.moveTo.label", "help": "Where to.",
                       "helpKey": "drop.field.moveTo.help", "tab": "polling", "required": true, "directions": ["in"],
                       "visibleWhen": {"field": "afterSuccess", "values": ["move"]}}],
                    "rules": [{"kind": "exactlyOne", "fields": ["password", "privateKey"]}]}},
                  {"scheme": "rec", "httpPrefix": null, "settings": {"format": 2, "reach": {"in": "connection", "out": "connection"},
                    "described": {"connection": false, "flow": true},
                    "tabs": [{"id": "general", "label": "General", "labelKey": "rail8.tab.general", "common": true},
                             {"id": "retry", "label": "Retry", "labelKey": "rail8.tab.retry", "common": true},
                             {"id": "recording", "label": "Recording", "labelKey": "rail8.tab.recording", "common": true},
                             {"id": "security", "label": "Security", "labelKey": "rail8.tab.security", "common": true}],
                    "connection": []}}]}
                """);
        JsonObject rec = expected.getJsonArray("items").getJsonObject(1).getJsonObject("settings");
        JsonObject recForms = new JsonObject(Doors.FORMS.toJson(Doors.SCHEME));
        rec.put("flow", recForms.getJsonArray("flow")).put("rules", recForms.getJsonArray("rules"));
        expected.getJsonArray("items").add(new JsonObject().put("scheme", "web").put("httpPrefix", "/web")
                .put("settings", new JsonObject(Web.FORMS.toJson("web"))));
        assertEquals(expected, listed.json(), "sorted by scheme, each with its prefix and its forms");
        assertEquals("web.field.address.label", listed.json().getJsonArray("items").getJsonObject(2).getJsonObject("settings")
                .getJsonArray("flow").getJsonObject(0).getString("labelKey"), "a text key names the adapter's scheme");
    }

    /** An advertised origin, {@code RAIL8_HTTP_ORIGIN}, is told as set; a component's prefix does not depend on it. */
    @Test
    void theAdvertisedOriginIsTold() throws Exception {
        wire(Map.of("RAIL8_HTTP_ORIGIN", "https://rail8.example.com"));
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        JsonObject listed = get(port, "Bearer " + TOKEN).json();
        assertEquals("dev", listed.getString("environment"));
        assertEquals("https://rail8.example.com", listed.getString("httpOrigin"));
        assertEquals("/web", listed.getJsonArray("items").getJsonObject(2).getString("httpPrefix"));
    }

    @Test
    void theRouteNeedsTheToken() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        assertUnauthenticated(get(port, null), null, "GET /admin/components without the token");
    }
}
