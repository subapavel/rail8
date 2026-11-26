package dev.rail8.runtime.admin;

import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static dev.rail8.runtime.admin.AdminServerDeployTest.Answer;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertUnauthenticated;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /admin/systems} answers the systems the pool's records define in the node's environment; it needs the token like
 * every {@code /admin/*} route. What each system holds is the case of {@code Deployments.systems()}.
 */
class AdminServerSystemsTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<AdminServer> servers = new ArrayList<>();
    Deployments deployments;

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
    void wire() {
        Map<String, String> e = new HashMap<>();
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                queue, () -> 0L, new InMemoryDeploymentStore(), new SequentialIntake(messages, queue),
                new InMemoryPollerLeases(rt.clock()), new Targets(new Targets.Limit(3, Duration.ofMillis(750)), Map.of(), rt.timer()));
    }

    @AfterEach
    void stop() throws Exception {
        for (AdminServer s : servers) s.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    int serve() throws Exception {
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(checks, () -> 0L), Optional.of(deployments), new InMemoryWorkQueue(rt.clock()), Optional.empty());
        int bound = server.start(vertx).await(10, TimeUnit.SECONDS);
        servers.add(server);
        return bound;
    }

    Answer get(int port, String path, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> all = res.body();
                    res.resume();
                    return all.map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b));
                })
                .await(20, TimeUnit.SECONDS);
    }

    /** A pool whose records define no system answers none, naming its environment; without the token the route is 401. */
    @Test
    void systemsRouteAnswersTheEnvironmentAndNeedsTheToken() throws Exception {
        int port = serve();
        Answer none = get(port, "/admin/systems", AUTH);
        assertJson(200, none, "GET /admin/systems");
        assertEquals(new JsonObject("{\"environment\": \"dev\", \"systems\": []}"), none.json());
        for (String auth : Arrays.asList(null, "Bearer wrong", TOKEN)) {
            assertUnauthenticated(get(port, "/admin/systems", auth), auth, "GET /admin/systems with Authorization " + auth);
        }
    }
}
