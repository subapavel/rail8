package dev.rail8.runtime.admin;

import dev.rail8.api.BlobStore;
import dev.rail8.api.Runtime;
import dev.rail8.api.plugin.AdminReply;
import dev.rail8.api.plugin.AdminRequest;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.access.Access;
import dev.rail8.runtime.access.AccessRefused;
import dev.rail8.runtime.access.FakeHash;
import dev.rail8.runtime.access.HashPlaces;
import dev.rail8.runtime.access.MemoryAccess;
import dev.rail8.runtime.config.ConfigStore;
import dev.rail8.runtime.config.MemoryConfigStores;
import dev.rail8.runtime.config.StoredEnvironment;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.GitRepos;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Three servers over one {@link MemoryAccess.Store}, as three nodes of one pool, each with every route of {@code /admin}: one
 * whose RAIL8_ADMIN_TOKEN is the emergency key {@link #KEY}, one whose token is the shared token {@link #SHARED}, and one on
 * loopback without a token. The password hash is a {@link FakeHash}, the clock {@link #rt}'s; the node's modules are one plugin,
 * {@code demo}, with {@code GET} and {@code POST ping}. While {@link #auditFails} is set, the audit rows of a use of the key and
 * of a view of a body are not written; while {@link #revokeFails} is set, a revoke of a token throws as a failed store would; {@link #viewsAtOpen} holds, for each stream of a body opened, how many views the audit
 * held at that moment. Its deployments read the secret store {@link #secrets}, whose routes the servers mount. The routes of
 * {@code /admin/config} serve the configuration store {@link #configStore}, in memory over {@link #configs}, whose calls through the
 * routes {@link #configCalls} names, and the environment {@link #environment} of node {@code node-1} over it.
 */
abstract class AdminAccessFixture {

    static final String KEY = "rail8k_" + "k".repeat(43);
    static final String SHARED = "s3cret-token";
    static final String PASSWORD = "a password of my own";
    /** The secret store's key: 32 bytes of 7, in base64. */
    static final String STORE_KEY = java.util.Base64.getEncoder().encodeToString("7".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    static Vertx vertx;
    static HttpClient http;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final FakeHash hash = new FakeHash();
    final MemoryAccess.Store store = new MemoryAccess.Store();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final AtomicBoolean auditFails = new AtomicBoolean();
    final AtomicBoolean revokeFails = new AtomicBoolean();
    final List<Long> viewsAtOpen = new CopyOnWriteArrayList<>();
    final List<AdminServer> started = new ArrayList<>();
    /** The node's secret store, under {@link #STORE_KEY}, over a database of its own. */
    SecretStore secrets;
    /** The database {@link #secrets} reads, where a test starts the stores of other nodes. */
    final MemorySecretStores secretDb = new MemorySecretStores();
    Deployments deployments;
    /** The database of the configuration's stores. */
    final MemoryConfigStores configs = new MemoryConfigStores();
    /** The configuration store of node {@code node-1}, as its environment reads it. */
    ConfigStore configStore;
    /** The names of the methods of {@link #configStore} the routes called, in order. */
    final List<String> configCalls = new CopyOnWriteArrayList<>();
    StoredEnvironment environment;
    AdminServer keyServer;
    int key;
    int shared;
    int open;

    @BeforeAll
    static void up() {
        vertx = Vertx.vertx();
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void down() {
        http.close();
        vertx.close().await();
    }

    @BeforeEach
    void serve() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        GitRepos repos = new GitRepos(tmp.resolve("repos"));
        env.put("RAIL8_GIT_BASE", repos.base().toString());
        Doors doors = new Doors();
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        NodeConfig config = NodeConfig.from(env);
        secrets = secretDb.start("node-1", Map.of(StoreKey.VARIABLE, STORE_KEY), rt.clock(), rt.timer());
        deployments = new Deployments(config.withSecrets(config.secrets().withStore(secrets)), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                messages, new InMemoryDlq(), rows, () -> 0L);
        configStore = configs.start("node-1", 1, secrets, rt.clock());
        environment = new StoredEnvironment("test", configStore, "node-1", () -> 1, rt.clock(), rt.timer(), text -> { });
        environment.start();
        keyServer = server(Optional.of(KEY));
        key = keyServer.start(vertx).await(10, TimeUnit.SECONDS);
        shared = server(Optional.of(SHARED)).start(vertx).await(10, TimeUnit.SECONDS);
        open = server(Optional.empty()).start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        for (AdminServer s : started) s.stop().await(10, TimeUnit.SECONDS);
        environment.close();
        secrets.close();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    private AdminServer server(Optional<String> token) {
        return server(new AdminServer.Config("127.0.0.1", 0, token));
    }

    /** A server of {@code config} over the fixture's store, with every route of {@code /admin}; stopped after the test. */
    AdminServer server(AdminServer.Config config) {
        Optional<String> token = config.token();
        Access access = around(Access.class, new MemoryAccess(store, rt.clock(), hash, new HashPlaces(), new SecureRandom(), token), m -> {
            if (auditFails.get() && (m.getName().equals("keyUsed") || m.getName().equals("bodyViewed"))) throw AccessRefused.unaudited("the audit is down");
            if (revokeFails.get() && m.getName().equals("revokeToken")) throw new IllegalStateException("the store is down");
        });
        BlobStore blobs = around(BlobStore.class, rt.blobs(), m -> {
            if (m.getName().equals("open")) viewsAtOpen.add(views());
        });
        AdminServer.Modules modules = new AdminServer.Modules(List.of(), Map.of("demo", List.of(ping("GET"), ping("POST"))), rt.clock());
        AdminServer server = new AdminServer(config, messages, new InMemoryDlq(), blobs,
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), Optional.of(rows), Optional.of(new InMemoryFailedMessages(rows)),
                Optional.empty(), Optional.empty(), new Workers(), WorkSignals.NONE, modules, access);
        server.configuration(new AdminServer.Configuration(around(ConfigStore.class, configStore, m -> configCalls.add(m.getName())),
                environment::notice, Optional.of(secrets)));
        started.add(server);
        return server;
    }

    /** The views of a body in the audit. */
    long views() {
        return store.audit().stream().filter(row -> row.getString("action").equals("message.body.view")).count();
    }

    /** {@code target} as a {@code type}, {@code before} run with each method called before the call goes on. */
    @SuppressWarnings("unchecked")
    static <T> T around(Class<T> type, T target, Consumer<Method> before) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            before.accept(method);
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    private static AdminService ping(String method) {
        return new AdminService() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public String path() {
                return "ping";
            }

            @Override
            public AdminReply handle(AdminRequest request) {
                return new AdminReply(200, "{\"pong\":true}");
            }
        };
    }

    // --- the wire

    /** An answer: its status, headers and body as text. */
    record Answer(int status, HttpHeaders headers, String body) {
        JsonObject json() {
            return new JsonObject(body);
        }

        /** The {@code code} of a refusal, or null. */
        String code() {
            return body.startsWith("{") ? json().getString("code") : null;
        }
    }

    /** {@code method path} to {@code port} with {@code bearer} when given and {@code body} as JSON when not null. */
    static Answer send(int port, String method, String path, Optional<String> bearer, String body) {
        return send(port, method, path, bearer, body, Map.of());
    }

    /** {@link #send(int, String, String, Optional, String)} with {@code headers} too. */
    static Answer send(int port, String method, String path, Optional<String> bearer, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        bearer.ifPresent(b -> request.header("Authorization", "Bearer " + b));
        headers.forEach(request::header);
        try {
            HttpResponse<String> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(answer.statusCode(), answer.headers(), answer.body());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- users

    /** The first administrator, {@code root}, made with the key, logged in with its own password: its session. */
    String root() {
        Answer made = send(key, "POST", "/admin/users", Optional.of(KEY), personBody("root", "administrator").encode());
        assertEquals(201, made.status(), made.body());
        return changed("root", made.json().getString("firstPassword"));
    }

    /** A person of {@code roles} made by the administrator of {@code admin}: their first password. */
    String person(String admin, String name, String... roles) {
        Answer made = send(key, "POST", "/admin/users", Optional.of(admin), personBody(name, roles).encode());
        assertEquals(201, made.status(), made.body());
        return made.json().getString("firstPassword");
    }

    /** The body of {@code POST /admin/users} for a person of {@code roles}. */
    static JsonObject personBody(String name, String... roles) {
        return new JsonObject().put("name", name).put("kind", "person").put("roles", new JsonArray(List.of((Object[]) roles)));
    }

    /** A login of {@code name}: the session. */
    String login(String name, String password) {
        Answer login = send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", name).put("password", password).encode());
        assertEquals(201, login.status(), login.body());
        return login.json().getString("session");
    }

    /** A token {@code name} of the person of {@code session}, whose password is {@link #PASSWORD}: the token. */
    String token(String session, String name) {
        Answer issued = send(key, "POST", "/admin/me/tokens", Optional.of(session),
                new JsonObject().put("name", name).put("password", PASSWORD).encode());
        assertEquals(201, issued.status(), issued.body());
        return issued.json().getString("token");
    }

    /** A technical account {@code name} of {@code roles}, made by the administrator of {@code admin}, and its token {@code ci}: the token. */
    String technical(String admin, String name, String... roles) {
        Answer made = send(key, "POST", "/admin/users", Optional.of(admin), new JsonObject().put("name", name).put("kind", "technical")
                .put("roles", new JsonArray(List.of((Object[]) roles))).encode());
        assertEquals(201, made.status(), made.body());
        Answer issued = send(key, "POST", "/admin/users/" + name + "/tokens", Optional.of(admin), new JsonObject().put("name", "ci").encode());
        assertEquals(201, issued.status(), issued.body());
        return issued.json().getString("token");
    }

    /** A login with {@code first}, then the password changed to {@link #PASSWORD}: the session, which goes on. */
    String changed(String name, String first) {
        String session = login(name, first);
        Answer change = send(key, "POST", "/admin/me/password", Optional.of(session),
                new JsonObject().put("current", first).put("next", PASSWORD).encode());
        assertEquals(204, change.status(), change.body());
        return session;
    }
}
