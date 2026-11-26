package dev.rail8.runtime.config;

import dev.rail8.api.Secret;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link ConfigStore}, for the store over Postgres and the one in memory. Two nodes stand for two nodes of an
 * installation: each its own fake clock and timer, its own seeded randomness, its own secret store and configuration store,
 * over one database. A tamper hook plays the database's writer. Every answer, refusal, history row, audit row and warning a
 * test sees is searched, after it, for each secret value it wrote.
 */
abstract class ConfigStoreContract {

    // ------------------------------------------------------------------------------------------------- the backing

    /** The started secret store of node {@code node} with the key variables {@code env}, over the test's database. */
    abstract SecretStore secretStore(String node, Map<String, String> env, FakeClock clock, FakeTimer timer);

    /** The configuration store of a node over the test's database. */
    abstract ConfigObjects configStore(SecretStore secrets, Function<String, Optional<ConfigObjects.Adapter>> adapters, Clock clock,
                                       RandomGenerator random, Consumer<String> warn);

    /** Records the deployed definition of system {@code system}, as a deploy writes it. */
    abstract void deploySystem(String system, JsonObject definition);

    /** Records a deployed flow's use of a connection, as a deploy writes it. */
    abstract void use(String flow, String step, String connection, String adapter, String direction, boolean address);

    /** Whether every audit row fails from now on. */
    abstract void auditFails(boolean fails);

    /** The audit rows of {@code action}, in order: each {@code {actor, subject, detail}}. */
    abstract List<JsonObject> audit(String action);

    /** Writes {@code body} as the body in force of {@code ref}, its binding as it was. */
    abstract void tamperBody(Ref ref, JsonObject body);

    /** Clears the binding of the body in force of {@code ref}: no key id, no tag. */
    abstract void unbind(Ref ref);

    /** Writes {@code draft} as the draft of {@code ref}, its id and consent as they were. */
    abstract void tamperDraft(Ref ref, JsonObject draft);

    /** Writes {@code body} as the body of the activation of version {@code version} of {@code ref} in its history. */
    abstract void tamperHistoryBody(Ref ref, int version, JsonObject body);

    /** The consent columns of {@code ref}: {@code consent}, {@code keyId} and {@code tag} in base64, {@code at}, {@code by}. */
    abstract JsonObject consentOf(Ref ref);

    /** Writes the consent columns {@code consent} of {@link #consentOf} onto {@code ref}. */
    abstract void putConsent(Ref ref, JsonObject consent);

    /** Writes the incarnation, the draft id and the activation of {@code from} onto {@code to}, as a writer of the database could. */
    abstract void copyIds(Ref from, Ref to);

    /** Deletes the row of {@code ref} and its history. */
    abstract void deleteRow(Ref ref);

    /** Flips a bit of the sealed value of the secret {@code name}, so that no node opens it. */
    abstract void tamperSecret(String name);

    /** The configuration's tables as they are now. */
    abstract Object snapshot();

    /** Sets the configuration's tables back to {@code snapshot}, as a restore of the database does. */
    abstract void setBack(Object snapshot);

    /** The number of history rows of {@code ref}. */
    abstract int historyRows(Ref ref);

    /** The epoch of node {@code node} in its row of {@code nodes}, which its secret store's start wrote. */
    abstract long epoch(String node);

    /** Lets the lease of node {@code node} lapse. */
    abstract void lapse(String node);

    // ------------------------------------------------------------------------------------------------- nodes

    static final Instant START = Instant.parse("2026-11-26T10:00:00Z");
    static final byte[] K1 = filled(1);
    static final byte[] K2 = filled(2);

    static final dev.rail8.core.audit.Actor CONNECTIONS_WHO = new dev.rail8.core.audit.Actor("connections", OptionalLong.of(2), "session",
            Optional.empty(), "127.0.0.1");
    /** A connection administrator, who does not hold {@code config.consent}. */
    static final Actor CONNECTIONS = new Actor(CONNECTIONS_WHO, false);
    /** A secret administrator, who holds {@code config.consent}. */
    static final Actor SECRETS = new Actor(new dev.rail8.core.audit.Actor("secrets", OptionalLong.of(3), "session", Optional.empty(), "127.0.0.2"), true);
    /** A second secret administrator. */
    static final Actor SECRETS_TOO = new Actor(new dev.rail8.core.audit.Actor("secrets-too", OptionalLong.of(4), "token", Optional.of("ci"), "127.0.0.3"), true);
    /** One who holds both roles. */
    static final Actor BOTH = new Actor(new dev.rail8.core.audit.Actor("both", OptionalLong.of(5), "session", Optional.empty(), "127.0.0.4"), true);

    /** An http-like form: the url names the server, the inbox serves the in direction only. */
    static final Settings WEB_FORM = Settings.of(
            Field.text("url", "URL", "Where the partner is.").required().server(),
            Field.text("path", "Path", "Below the url."),
            Field.text("proxy", "Proxy", "Through which proxy."),
            Field.bool("verifyTls", "Verify TLS", "Whether the certificate is checked.").byDefault(true),
            Field.integer("timeoutMs", "Timeout (ms)", "How long.").byDefault(30_000),
            Field.secret("token", "Token", "The login."),
            Field.secret("pin", "Pinned certificate", "The partner's certificate."),
            Field.text("inbox", "Inbox", "Where the partner's messages arrive.").required().in());
    static final ConfigObjects.Adapter WEB = new ConfigObjects.Adapter(new Forms(WEB_FORM, Settings.NONE,
            Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION)), Set.of("https"));

    /** An sftp-like form, out only: the host and port name the server, the password is credentials. */
    static final Settings SFTP_FORM = Settings.of(
            Field.text("host", "Host", "The server.").required().server().out(),
            Field.integer("port", "Port", "The server's port.").range(1, 65535).byDefault(22).server().out(),
            Field.secret("password", "Password", "The login.", Secret.Kind.CREDENTIALS).out(),
            Field.secret("hostKey", "Host key", "The server key's fingerprint.").out());
    static final ConfigObjects.Adapter SFTP = new ConfigObjects.Adapter(new Forms(SFTP_FORM, Settings.NONE,
            Map.of(Direction.OUT, Forms.Reach.CONNECTION)), Set.of());

    /** A client of TLS, out only: the host names the server, the key is a key pair. */
    static final Settings TLS_FORM = Settings.of(
            Field.text("host", "Host", "The server.").required().server().out(),
            Field.secret("key", "Client key", "The key the client proves itself with.", Secret.Kind.KEY_PAIR).out());
    static final ConfigObjects.Adapter TLS = new ConfigObjects.Adapter(new Forms(TLS_FORM, Settings.NONE,
            Map.of(Direction.OUT, Forms.Reach.CONNECTION)), Set.of());

    static final Map<String, ConfigObjects.Adapter> ADAPTERS = Map.of("web", WEB, "sftp", SFTP, "tls", TLS);

    static final Ref API = Ref.connection("acme/api");
    static final Ref FILES = Ref.connection("acme/files");
    static final Ref ACME = Ref.system("acme");

    /** Every text a test saw, searched for each secret value. */
    final List<String> captured = new CopyOnWriteArrayList<>();
    /** Every secret value a test wrote. */
    final List<String> values = new CopyOnWriteArrayList<>();
    final List<Node> nodes = new ArrayList<>();

    /** One node: its clock, timer, randomness, secret store and configuration store. */
    final class Node {
        final String id;
        final FakeClock clock = FakeClock.at(START);
        final FakeTimer timer = new FakeTimer(clock);
        final Scripted random;
        final List<String> warnings = new CopyOnWriteArrayList<>();
        SecretStore secrets;
        ConfigObjects store;
        StoredEnvironment env;

        Node(String id, long seed) {
            this.id = id;
            this.random = new Scripted(seed);
        }

        /** Moves the clock one tick of the secret store on and waits for its work, binding again among it. */
        void tick() {
            clock.advance(MemorySecretStores.TICK);
            await(MemorySecretStores.ticked(secrets));
        }
    }

    /** A seeded generator that first answers the longs given to {@link #script}, in their order. */
    static final class Scripted implements RandomGenerator {
        private final Random seeded;
        private final ArrayDeque<Long> next = new ArrayDeque<>();

        Scripted(long seed) {
            seeded = new Random(seed);
        }

        synchronized void script(long... values) {
            for (long v : values) next.add(v);
        }

        @Override
        public synchronized long nextLong() {
            Long v = next.poll();
            return v != null ? v : seeded.nextLong();
        }
    }

    Node node(String id, byte[] key, byte[] previous) {
        Node n = new Node(id, id.hashCode() * 7919L + nodes.size());
        start(n, key, previous);
        nodes.add(n);
        return n;
    }

    Node node(String id) {
        return node(id, K1, null);
    }

    void start(Node n, byte[] key, byte[] previous) {
        Map<String, String> env = new HashMap<>();
        env.put(StoreKey.VARIABLE, Base64.getEncoder().encodeToString(key));
        if (previous != null) env.put(StoreKey.PREVIOUS, Base64.getEncoder().encodeToString(previous));
        n.secrets = secretStore(n.id, env, n.clock, n.timer);
        n.store = configStore(n.secrets, name -> Optional.ofNullable(ADAPTERS.get(name)), n.clock, n.random, text -> {
            n.warnings.add(text);
            captured.add(text);
        });
    }

    /** Starts {@code n} again with {@code key} and {@code previous}, as a node restarted with new variables. */
    void restart(Node n, byte[] key, byte[] previous) {
        n.secrets.close();
        start(n, key, previous);
    }

    /** Starts the environment of {@code n} over its store: its first read and its report. */
    StoredEnvironment environment(Node n) {
        n.env = new StoredEnvironment("test", n.store, n.id, () -> epoch(n.id), n.clock, n.timer, text -> {
            n.warnings.add(text);
            captured.add(text);
        });
        n.env.start();
        return n.env;
    }

    /** Moves the clock of {@code n} one tick of its environment on and waits for the read it starts. */
    void envTick(Node n) {
        n.clock.advance(StoredEnvironment.TICK);
        await(n.env.ticked());
    }

    /** What {@code n}'s read of {@code ref} says of whether node {@code node} holds it. */
    String holdsOf(Node n, Ref ref, String node) {
        for (Object o : read(n, ref).json().getJsonArray("nodes")) {
            JsonObject j = (JsonObject) o;
            if (j.getString("node").equals(node)) return j.getString("holds");
        }
        throw new AssertionError(node + " is not a live node");
    }

    /** The version of the connection {@code ref} in {@code env}'s snapshot. */
    static long versionIn(StoredEnvironment env, Ref ref) {
        return env.current().connection(ref.key()).orElseThrow(() -> new AssertionError(ref + " is not in the snapshot")).version();
    }

    static long activationOf(ConfigStore.ObjectView view) {
        return Long.parseLong(view.json().getString("activation"));
    }

    @AfterEach
    void noSecretValueIsInAnyText() {
        for (Node n : nodes) {
            if (n.env != null) n.env.close();
            n.secrets.close();
        }
        for (String value : values) {
            for (String text : captured) assertFalse(text.contains(value), "a captured text holds the value of a secret: " + text);
        }
    }

    // ------------------------------------------------------------------------------------------------- calls

    static <T> T await(Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the call failed: " + e, e);
        }
    }

    void secret(Node n, String name, String value) {
        values.add(value);
        await(n.secrets.create(MemorySecretStores.ADMIN, new JsonObject().put("name", name).put("kind", "value").put("value", value)));
    }

    void credentials(Node n, String name, String user, String password) {
        values.add(password);
        await(n.secrets.create(MemorySecretStores.ADMIN, new JsonObject().put("name", name).put("kind", "credentials").put("user", user)
                .put("password", password)));
    }

    ConfigStore.ObjectView seen(ConfigStore.ObjectView view) {
        captured.add(view.json().encode());
        return view;
    }

    ConfigStore.ObjectView read(Node n, Ref ref) {
        return seen(n.store.read(ref));
    }

    /** The etag of {@code ref} as {@code n} reads it, that of a key no row holds included. */
    String etag(Node n, Ref ref) {
        try {
            return n.store.read(ref).etag();
        } catch (ConfigRefusal notFound) {
            return notFound.values().getString("etag");
        }
    }

    ConfigStore.ObjectView write(Node n, Actor actor, Ref ref, Change change) {
        return seen(n.store.write(actor, ref, change));
    }

    ConfigStore.ObjectView save(Node n, Ref ref, String adapter, Map<String, Object> values) {
        return write(n, CONNECTIONS, ref, new Change.Save(etag(n, ref), adapter, values, Optional.empty()));
    }

    ConfigStore.ObjectView save(Node n, Ref ref, Map<String, Object> values) {
        return save(n, ref, "web", values);
    }

    ConfigStore.ObjectView activate(Node n, Actor actor, Ref ref) {
        return write(n, actor, ref, new Change.Activate(etag(n, ref), Optional.empty(), Optional.empty()));
    }

    ConfigStore.ObjectView activate(Node n, Ref ref) {
        return activate(n, CONNECTIONS, ref);
    }

    ConfigStore.ObjectView consent(Node n, Actor actor, Ref ref) {
        String digest = read(n, ref).json().getJsonObject("draft").getJsonObject("consent").getString("digest");
        return write(n, actor, ref, new Change.Consent(etag(n, ref), Optional.of(digest)));
    }

    /** Saves {@code values}, consents when the draft needs it and activates. */
    ConfigStore.ObjectView inForce(Node n, Ref ref, Map<String, Object> values) {
        ConfigStore.ObjectView saved = save(n, ref, values);
        if (saved.json().getJsonObject("draft") != null && saved.json().getJsonObject("draft").getValue("consent") != null) consent(n, SECRETS, ref);
        return activate(n, ref);
    }

    ConfigRefusal refused(Executable call) {
        ConfigRefusal r = assertThrows(ConfigRefusal.class, call);
        captured.add(r.getMessage());
        captured.add(r.body().encode());
        return r;
    }

    void refused(int status, String code, Executable call) {
        ConfigRefusal r = refused(call);
        assertEquals(code, r.code(), r.getMessage());
        assertEquals(status, r.status(), r.getMessage());
    }

    List<JsonObject> history(Node n, Ref ref) {
        List<JsonObject> rows = new ArrayList<>();
        for (ConfigStore.HistoryRow h : n.store.history(ref, OptionalLong.empty(), ConfigStore.MAX_PAGE).items()) {
            captured.add(h.json().encode());
            rows.add(h.json());
        }
        return rows;
    }

    List<JsonObject> audited(String action) {
        List<JsonObject> rows = audit(action);
        rows.forEach(r -> captured.add(r.encode()));
        return rows;
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    /** {@code base} with {@code key} set to {@code value}, or removed when it is null. */
    static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) m.remove((String) pairs[i]);
            else m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** A partner over https with a token and a pinned certificate, complete for both directions. */
    static final Map<String, Object> PARTNER = values("url", "https://partner.example/api", "token", "secret://partner.token",
            "pin", "secret://partner.pin", "inbox", "/in");
    /** A partner with no secret, complete for both directions. */
    static final Map<String, Object> PLAIN = values("url", "https://plain.example/api", "inbox", "/in");

    void partnerSecrets(Node n) {
        secret(n, "partner.token", "the-token-value");
        secret(n, "partner.pin", "the-pin-value");
    }

    static JsonObject body(Map<String, Object> values) {
        return new JsonObject().put("adapter", "web").put("values", new JsonObject(new LinkedHashMap<>(values)));
    }

    static ConfigStore.Held holding(ConfigStore.State state) {
        Map<Ref, ConfigStore.Holding> objects = new HashMap<>();
        for (ConfigStore.InForce f : state.changed()) objects.put(f.ref(), new ConfigStore.Holding(f.activation(), f.stopped()));
        return new ConfigStore.Held(state.generation(), state.stamp(), objects);
    }

    static byte[] filled(int b) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) b);
        return key;
    }

    // ------------------------------------------------------------------------------------------------- reads

    @Test
    void aKeyNoRowHoldsIsNotFoundWithTheEtagOfNoRowAndADeployedNameIsAnEmptyObject() {
        Node a = node("a");
        ConfigRefusal r = refused(() -> a.store.read(API));
        assertEquals(404, r.status());
        assertEquals("not-found", r.code());
        String none = r.values().getString("etag");
        assertNotNull(none);
        deploySystem("acme", new JsonObject().put("connections", new JsonObject().put("api", new JsonObject().put("adapter", "web"))));
        ConfigStore.ObjectView view = read(a, API);
        assertEquals(none, view.etag());
        assertEquals("web", view.json().getString("deployedAdapter"));
        assertEquals(0, view.json().getInteger("version"));
        assertNull(view.json().getValue("inForce"));
        assertEquals(0, read(a, ACME).json().getInteger("version"), "a deployed system is an empty object");
    }

    @Test
    void aSavedAndActivatedConnectionIsInForceBoundAndInTheHistoryAndAudit() {
        Node a = node("a");
        ConfigStore.ObjectView saved = save(a, API, PLAIN);
        assertEquals(0, saved.json().getInteger("version"));
        assertNotNull(saved.json().getJsonObject("draft"));
        assertNull(saved.json().getJsonObject("draft").getValue("consent"), "it sends no secret");
        ConfigStore.ObjectView active = activate(a, API);
        JsonObject inForce = active.json().getJsonObject("inForce");
        assertEquals(1, inForce.getInteger("version"));
        assertEquals("web", inForce.getString("adapter"));
        assertEquals(body(PLAIN).getJsonObject("values"), inForce.getJsonObject("values"));
        assertEquals("ok", inForce.getString("binding"));
        assertEquals(16, inForce.getString("keyId").length(), "the key id in hex");
        assertEquals("connections", inForce.getString("activatedBy"));
        assertNull(active.json().getValue("draft"));
        assertNotNull(active.json().getString("activation"));
        List<String> changes = history(a, API).stream().map(h -> h.getString("change")).toList();
        assertEquals(List.of("activate", "save"), changes, "newest first");
        JsonObject audit = audited("config.activate").getFirst();
        assertEquals("connections", audit.getString("actor"));
        assertEquals("config/connection/acme/api", audit.getString("subject"));
        assertEquals(1, audit.getJsonObject("detail").getInteger("version"));
        assertTrue(audit.getJsonObject("detail").getJsonArray("changed").contains("values.url"));
    }

    @Test
    void aNodeSeesAChangeInForceInItsStateAndHoldsTheStoreOnceItTookIt() {
        Node a = node("a");
        Node b = node("b");
        ConfigStore.State empty = b.store.state(ConfigStore.Held.nothing());
        assertTrue(empty.changed().isEmpty());
        ConfigStore.Held held = holding(empty);
        assertTrue(b.store.state(held).current());
        ConfigStore.ObjectView active = inForce(a, API, PLAIN);
        ConfigStore.State state = b.store.state(held);
        assertFalse(state.current(), "a node one change behind does not hold the store");
        assertEquals(List.of(API), state.changed().stream().map(ConfigStore.InForce::ref).toList());
        assertEquals(Long.parseLong(active.json().getString("activation")), state.changed().getFirst().activation());
        assertEquals(body(PLAIN), state.changed().getFirst().body());
        assertTrue(b.store.state(holding(state)).current());
        assertTrue(state.generation() > empty.generation());
    }

    @Test
    void aListPagesByKeyAndFiltersTheDraftsThatWaitForConsentOrForTheirActivation() {
        Node a = node("a");
        partnerSecrets(a);
        save(a, Ref.connection("acme/a"), PLAIN);
        save(a, Ref.connection("acme/b"), PARTNER);
        save(a, Ref.connection("acme/c"), PLAIN);
        save(a, Ref.connection("other/d"), PLAIN);
        ConfigStore.Page<ConfigStore.ObjectView> first = a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.of("acme/"),
                Optional.empty(), 2, Optional.empty()));
        assertEquals(List.of("acme/a", "acme/b"), first.items().stream().map(v -> v.json().getString("key")).toList());
        assertEquals(Optional.of("acme/b"), first.next());
        ConfigStore.Page<ConfigStore.ObjectView> second = a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.of("acme/"),
                first.next(), 2, Optional.empty()));
        assertEquals(List.of("acme/c"), second.items().stream().map(v -> v.json().getString("key")).toList());
        assertEquals(Optional.empty(), second.next());
        ConfigStore.Page<ConfigStore.ObjectView> waiting = a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.empty(),
                Optional.empty(), 10, Optional.of(ConfigStore.Needs.CONSENT)));
        assertEquals(List.of("acme/b"), waiting.items().stream().map(v -> v.json().getString("key")).toList());
        consent(a, SECRETS, Ref.connection("acme/b"));
        assertTrue(a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.empty(), Optional.empty(), 10,
                Optional.of(ConfigStore.Needs.CONSENT))).items().isEmpty());
        activate(a, Ref.connection("acme/a"));
        assertEquals(List.of("acme/b", "acme/c", "other/d"), a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.empty(),
                Optional.empty(), 10, Optional.of(ConfigStore.Needs.ACTIVATION))).items().stream().map(v -> v.json().getString("key")).toList());
        refused(400, "bad-request", () -> a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.empty(), Optional.empty(), 0,
                Optional.empty())));
        refused(400, "bad-request", () -> a.store.list(new ConfigStore.Query(Ref.Kind.CONNECTION, Optional.empty(), Optional.empty(),
                ConfigStore.MAX_PAGE + 1, Optional.empty())));
    }

    // ------------------------------------------------------------------------------------------------- expect

    @Test
    void aSaveOverAnotherNodesSaveIsChangedAndAnswersTheObjectAsItIsNow() {
        Node a = node("a");
        Node b = node("b");
        save(a, API, PLAIN);
        String read = etag(b, API);
        save(a, API, with(PLAIN, "path", "/v2"));
        ConfigRefusal r = refused(() -> b.store.write(CONNECTIONS, API, new Change.Save(read, "web", PLAIN, Optional.empty())));
        assertEquals(412, r.status());
        assertEquals("changed", r.code());
        assertEquals(etag(a, API), r.object().orElseThrow().etag());
        assertEquals("/v2", r.object().orElseThrow().json().getJsonObject("draft").getJsonObject("values").getString("path"));
    }

    @Test
    void everyVerbStatesTheEtagItReadAndAWrongOneIsChanged() {
        Node a = node("a");
        partnerSecrets(a);
        save(a, API, PARTNER);
        String old = etag(a, API);
        save(a, API, with(PARTNER, "path", "/v2"));
        refused(412, "changed", () -> a.store.write(CONNECTIONS, API, new Change.Discard(old)));
        refused(412, "changed", () -> a.store.write(SECRETS, API, new Change.Consent(old, Optional.of("00"))));
        refused(412, "changed", () -> a.store.write(BOTH, API, new Change.Activate(old, Optional.empty(), Optional.empty())));
        refused(412, "changed", () -> a.store.write(CONNECTIONS, API, new Change.Remove(old)));
        refused(400, "bad-request", () -> a.store.write(CONNECTIONS, API, new Change.Save(null, "web", PLAIN, Optional.empty())));
        refused(400, "bad-request", () -> a.store.write(CONNECTIONS, API, new Change.Discard(null)));
        refused(400, "bad-request", () -> a.store.write(CONNECTIONS, ACME, new Change.Save(etag(a, ACME), "web", PLAIN, Optional.empty())));
        refused(400, "bad-request", () -> a.store.write(CONNECTIONS, API, new Change.SaveSystem(etag(a, API), "Acme")));
        refused(400, "bad-request", () -> a.store.write(CONNECTIONS, ACME, new Change.Stop("1.0")));
        assertEquals("/v2", read(a, API).json().getJsonObject("draft").getJsonObject("values").getString("path"),
                "no refused write changed the object");
    }

    @Test
    void aStopBesideAPendingDraftRefusesNeitherAndAStopDelayedPastAStopAndAStartIsChanged() {
        Node a = node("a");
        inForce(a, API, PLAIN);
        save(a, API, with(PLAIN, "path", "/v2"));
        String etag = etag(a, API);
        String run = read(a, API).run();
        ConfigStore.ObjectView stopped = write(a, CONNECTIONS, API, new Change.Stop(run));
        assertEquals("connections", stopped.json().getJsonObject("stopped").getString("by"));
        assertEquals(etag, stopped.etag(), "a stop leaves the draft's etag");
        write(a, CONNECTIONS, API, new Change.Activate(etag, Optional.empty(), Optional.empty()));
        String afterStop = read(a, API).run();
        write(a, CONNECTIONS, API, new Change.Start(afterStop));
        refused(412, "changed", () -> a.store.write(CONNECTIONS, API, new Change.Stop(afterStop)));
        assertNull(read(a, API).json().getValue("stopped"));
        assertEquals(List.of("start", "activate", "stop"), history(a, API).stream().limit(3).map(h -> h.getString("change")).toList());
        assertEquals(1, audited("config.stop").size());
        assertEquals(1, audited("config.start").size());
    }

    @Test
    void aStopIsInEveryNodesStateAndAStartTakesItBack() {
        Node a = node("a");
        Node b = node("b");
        inForce(a, API, PLAIN);
        ConfigStore.Held held = holding(b.store.state(ConfigStore.Held.nothing()));
        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        ConfigStore.State state = b.store.state(held);
        assertEquals(Optional.of(new ConnectionState.Stop("connections", a.clock.instant())), state.changed().getFirst().stopped());
        write(a, CONNECTIONS, API, new Change.Start(read(a, API).run()));
        assertEquals(Optional.empty(), b.store.state(holding(state)).changed().getFirst().stopped());
    }

    @Test
    void aNodeReportsWhatItHoldsInItsOwnEpochAndEachObjectShowsWhetherEveryLiveNodeHoldsIt() {
        Node a = node("a");
        Node b = node("b");
        inForce(a, API, PLAIN);
        inForce(a, FILES, PLAIN);
        ConfigStore.State state = a.store.state(ConfigStore.Held.nothing());
        ConfigStore.Overview before = a.store.overview();
        assertEquals(List.of("a", "b"), before.nodes().stream().map(ConfigStore.NodeReport::node).toList(), "the live nodes");
        assertTrue(before.nodes().stream().allMatch(n -> n.generation().isEmpty()), "a node reports once it has read");
        assertEquals("no", before.holds(before.nodes().getFirst(), API), "a node that has not read holds nothing");

        a.store.holds("a", epoch("a"), state.generation(), state.stamp(), ConfigStore.Refused.none());
        b.store.holds("b", epoch("b") - 1, state.generation(), state.stamp(), ConfigStore.Refused.none());
        ConfigStore.Overview read = a.store.overview();
        assertEquals(OptionalLong.of(state.generation()), read.nodes().getFirst().generation());
        assertTrue(read.nodes().getFirst().readAt().isPresent());
        assertEquals(OptionalLong.empty(), read.nodes().get(1).generation(), "a report of another epoch is not written");
        assertEquals(List.of(new JsonObject().put("node", "a").put("holds", "yes"), new JsonObject().put("node", "b").put("holds", "no")),
                read(a, API).json().getJsonArray("nodes").getList());

        b.store.holds("b", epoch("b"), state.generation(), state.stamp(), new ConfigStore.Refused(List.of(FILES), 1));
        ConfigStore.Overview leftOut = a.store.overview();
        ConfigStore.NodeReport nodeB = leftOut.nodes().get(1);
        assertEquals(List.of(FILES), nodeB.refused());
        assertEquals("yes", leftOut.holds(nodeB, API));
        assertEquals("no", leftOut.holds(nodeB, FILES), "an object the node left out");
        b.store.holds("b", epoch("b"), state.generation(), state.stamp(), new ConfigStore.Refused(List.of(FILES), 101));
        assertEquals("unknown", a.store.overview().holds(a.store.overview().nodes().get(1), API), "more left out than named");

        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        ConfigStore.Overview behind = a.store.overview();
        assertEquals("no", behind.holds(behind.nodes().getFirst(), FILES), "a node one change behind holds no object as it is");
        JsonObject nodeView = read(a, FILES).json().getJsonArray("nodes").getJsonObject(0);
        assertEquals(state.generation(), nodeView.getLong("generation"), "a node that does not hold it shows what it read");
        assertNotNull(nodeView.getString("readAt"));

        lapse("b");
        assertEquals(List.of("a"), a.store.overview().nodes().stream().map(ConfigStore.NodeReport::node).toList(), "a node whose lease lapsed");
    }

    // ------------------------------------------------------------------------------------------------- two nodes' environments

    @Test
    void aChangeInForceReachesTheOtherNodeAtTheNoticeAndAtTheNextTickWhenTheNoticeIsLost() {
        Node a = node("a");
        Node b = node("b");
        StoredEnvironment ea = environment(a);
        StoredEnvironment eb = environment(b);
        ConfigStore.ObjectView first = inForce(a, API, PLAIN);
        await(ea.notice());
        assertEquals(activationOf(first), versionIn(ea, API), "the node that wrote holds it when it answers");
        assertEquals(Optional.empty(), eb.current().connection(API.key()), "the other node before the notice");
        assertEquals("yes", holdsOf(a, API, "a"));
        assertEquals("no", holdsOf(a, API, "b"), "a node one change behind");

        await(eb.notice());
        ConnectionState.Values values = (ConnectionState.Values) eb.current().connection(API.key()).orElseThrow();
        assertEquals(activationOf(first), values.version(), "the connection's version is its activation");
        assertEquals("https://plain.example/api", values.values().get("url"));
        assertEquals("yes", holdsOf(a, API, "b"));

        ConfigStore.ObjectView second = inForce(a, API, with(PLAIN, "path", "/v2"));
        assertEquals(activationOf(first), versionIn(eb, API), "the notice is lost");
        envTick(b);
        assertEquals(activationOf(second), versionIn(eb, API), "the next tick reads it");
        assertEquals("/v2", ((ConnectionState.Values) eb.current().connection(API.key()).orElseThrow()).values().get("path"));
        assertEquals(eb.current().generation(), a.store.overview().generation());
    }

    @Test
    void afterASetBackASetBackActivatedToTheSameVersionNumberOrADeletedRowTheOtherNodeHoldsTheVersionInForce() {
        Node a = node("a");
        Node b = node("b");
        StoredEnvironment eb = environment(b);
        ConfigStore.ObjectView v1 = inForce(a, API, PLAIN);
        inForce(a, FILES, PLAIN);
        Object before = snapshot();
        ConfigStore.ObjectView v2 = inForce(a, API, with(PLAIN, "path", "/v2"));
        await(eb.notice());
        assertEquals(activationOf(v2), versionIn(eb, API));

        setBack(before);
        assertEquals("no", holdsOf(a, API, "b"), "never yes on the other side of a set back");
        await(eb.notice());
        assertEquals(activationOf(v1), versionIn(eb, API), "the version in force after the set back");
        assertTrue(b.warnings.stream().anyMatch(w -> w.contains("went back")), b.warnings.toString());
        assertEquals("yes", holdsOf(a, API, "b"), "once it read the store as it is");

        ConfigStore.ObjectView v2held = inForce(a, API, with(PLAIN, "path", "/v2"));
        await(eb.notice());
        setBack(before);
        ConfigStore.ObjectView again = inForce(a, API, with(PLAIN, "path", "/v3"));
        assertEquals(2, again.json().getInteger("version"), "the same version number again");
        assertNotEquals(activationOf(v2held), activationOf(again), "an activation of its own");
        await(eb.notice());
        assertEquals(activationOf(again), versionIn(eb, API));
        assertEquals("/v3", ((ConnectionState.Values) eb.current().connection(API.key()).orElseThrow()).values().get("path"));

        deleteRow(FILES);
        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        await(eb.notice());
        assertEquals(Optional.empty(), eb.current().connection(FILES.key()), "a row deleted");
        assertTrue(eb.current().connection(API.key()).isPresent());
    }

    @Test
    void aBodyChangedInTheDatabaseIsLeftOutOnTheOtherNodeWithNobodyActivatingIt() {
        Node a = node("a");
        Node b = node("b");
        StoredEnvironment eb = environment(b);
        inForce(a, API, PLAIN);
        inForce(a, FILES, PLAIN);
        await(eb.notice());
        tamperBody(FILES, body(with(PLAIN, "url", "https://forged.example/api")));
        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        await(eb.notice());

        assertEquals(Optional.empty(), eb.current().connection(FILES.key()));
        assertEquals("its binding does not verify: save and activate it again", eb.current().leftOut().get(FILES));
        assertEquals(dev.rail8.api.Health.Status.DEGRADED, eb.health().status());
        assertEquals("no", holdsOf(a, FILES, "b"), "the node left it out");
        assertEquals("yes", holdsOf(a, API, "b"));
    }

    @Test
    void aStopShowsInTheOtherNodesSnapshotWithWhoAndWhenAndAStartClearsIt() {
        Node a = node("a");
        Node b = node("b");
        StoredEnvironment eb = environment(b);
        ConfigStore.ObjectView active = inForce(a, API, PLAIN);
        await(eb.notice());
        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        await(eb.notice());
        ConnectionState api = eb.current().connection(API.key()).orElseThrow();
        assertEquals(Optional.of(new ConnectionState.Stop("connections", a.clock.instant())), api.stopped());
        assertEquals(activationOf(active), api.version(), "a stop changes no version");

        write(a, CONNECTIONS, API, new Change.Start(read(a, API).run()));
        await(eb.notice());
        assertEquals(Optional.empty(), eb.current().connection(API.key()).orElseThrow().stopped());
    }

    @Test
    void anOldRequestAgainstARowCreatedAgainIsChanged() {
        Node a = node("a");
        save(a, API, PLAIN);
        String old = etag(a, API);
        deleteRow(API);
        save(a, API, PLAIN);
        refused(412, "changed", () -> a.store.write(CONNECTIONS, API, new Change.Discard(old)));
        String oldRun = read(a, API).run();
        activate(a, API);
        deleteRow(API);
        inForce(a, API, PLAIN);
        refused(412, "changed", () -> a.store.write(CONNECTIONS, API, new Change.Stop(oldRun)));
    }

    // ------------------------------------------------------------------------------------------------- refusals of a save

    @Test
    void aSaveRefusesValuesAgainstTheirFields() {
        Node a = node("a");
        Map<String, Map<String, Object>> invalid = new LinkedHashMap<>();
        invalid.put("values.timeoutMs", with(PLAIN, "timeoutMs", "slow"));
        invalid.put("values.timeoutMs ", with(PLAIN, "timeoutMs", 9_007_199_254_740_993L));
        invalid.put("values.timeoutMs  ", with(PLAIN, "timeoutMs", 1.5));
        invalid.put("values.token", with(PLAIN, "token", "the-token-itself"));
        invalid.put("values.path", with(PLAIN, "path", "secret://partner.token"));
        invalid.put("values.url", with(PLAIN, "url", "https://user:pw@plain.example/api"));
        invalid.put("values.unknown", with(PLAIN, "unknown", "x"));
        invalid.put("values.allowedHosts", with(PLAIN, "allowedHosts", List.of("secret://partner.token")));
        invalid.forEach((field, values) -> {
            ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web", values, Optional.empty())));
            assertEquals(422, r.status(), field + ": " + r.getMessage());
            assertEquals("invalid", r.code(), field);
            assertEquals(field.strip(), r.values().getString("field"), r.getMessage());
        });
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, FILES, new Change.Save(etag(a, FILES), "sftp",
                values("host", "files.example", "allowedHosts", List.of("files.example")), Optional.empty())));
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "gopher", PLAIN, Optional.empty())));
        refused(422, "reserved", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web",
                with(PLAIN, "token", "secret://rail8.git"), Optional.empty())));
        Map<String, Object> many = new LinkedHashMap<>(PLAIN);
        for (int i = 0; i < 130; i++) many.put("f" + i, "x");
        refused(413, "too-large", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web", many, Optional.empty())));
        refused(413, "too-large", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web",
                with(PLAIN, "path", "/" + "p".repeat(70_000)), Optional.empty())));
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), " ")));
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), "x".repeat(129))));
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web", PLAIN,
                Optional.of(new Change.Mock("echo", Optional.of("61s"), Optional.empty())))));
        refused(404, "not-found", () -> a.store.read(API));
    }

    @Test
    void aSaveEqualToTheVersionInForceDropsTheDraftAndOneOverADraftKeepsItsId() {
        Node a = node("a");
        inForce(a, API, PLAIN);
        save(a, API, with(PLAIN, "path", "/v2"));
        ConfigStore.ObjectView back = save(a, API, PLAIN);
        assertNull(back.json().getValue("draft"), "the draft equals the version in force");
        refused(409, "no-draft", () -> a.store.write(CONNECTIONS, API, new Change.Discard(etag(a, API))));
        save(a, API, with(PLAIN, "path", "/v2"));
        ConfigStore.ObjectView discarded = write(a, CONNECTIONS, API, new Change.Discard(etag(a, API)));
        assertNull(discarded.json().getValue("draft"));
        assertEquals(1, discarded.json().getJsonObject("inForce").getInteger("version"));
        assertEquals("discard", history(a, API).getFirst().getString("change"));
    }

    @Test
    void theRefusalsOfWritesWithNothingToWorkOn() {
        Node a = node("a");
        refused(404, "not-found", () -> a.store.write(CONNECTIONS, API, new Change.Remove(etag(a, API))));
        refused(409, "no-draft", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        refused(409, "nothing-to-consent", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API), Optional.of("00"))));
        refused(409, "not-set", () -> a.store.write(CONNECTIONS, API, new Change.Stop("0.0")));
        save(a, API, PLAIN);
        refused(409, "nothing-to-consent", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API), Optional.of("00"))));
        refused(409, "nothing-to-consent", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API), Optional.empty())));
        refused(409, "not-set", () -> a.store.write(CONNECTIONS, API, new Change.Stop(read(a, API).run())));
        refused(409, "not-set", () -> a.store.write(CONNECTIONS, API, new Change.Start(read(a, API).run())));
        refused(404, "not-found", () -> a.store.write(CONNECTIONS, API, new Change.GoBack(etag(a, API), 1)));
    }

    @Test
    void twoFirstSavesAtTheLimitOneRefused() {
        Node a = node("a");
        Node b = node("b");
        a.store.limits(new ConfigObjects.Limits(1, 1, ConfigObjects.MAX_HISTORY, ConfigObjects.KEPT_ACTIVATIONS));
        b.store.limits(new ConfigObjects.Limits(1, 1, ConfigObjects.MAX_HISTORY, ConfigObjects.KEPT_ACTIVATIONS));
        save(a, API, PLAIN);
        ConfigRefusal r = refused(() -> b.store.write(CONNECTIONS, FILES, new Change.Save(etag(b, FILES), "web", PLAIN, Optional.empty())));
        assertEquals(409, r.status());
        assertEquals("config-full", r.code());
        save(b, API, with(PLAIN, "path", "/v2"));
        write(a, CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), "Acme"));
        refused(409, "config-full", () -> b.store.write(CONNECTIONS, Ref.system("other"), new Change.SaveSystem(etag(b, Ref.system("other")), "Other")));
    }

    // ------------------------------------------------------------------------------------------------- consent

    @Test
    void anActivationThatSendsASecretToANewServerNeedsTheSecretAdministratorsConsent() {
        Node a = node("a");
        partnerSecrets(a);
        ConfigStore.ObjectView saved = save(a, API, PARTNER);
        JsonObject needs = saved.json().getJsonObject("draft").getJsonObject("consent");
        assertEquals(2, needs.getJsonArray("sends").size());
        ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals("consent-needed", r.code());
        assertEquals(409, r.status());
        assertEquals(needs.getString("digest"), r.detail().getString("digest"));
        assertEquals(new JsonObject().put("secret", "partner.token").put("server", "partner.example:443"),
                r.detail().getJsonArray("sends").getJsonObject(1));
        refused(412, "changed", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API), Optional.of("ab".repeat(32)))));
        ConfigStore.ObjectView consented = consent(a, SECRETS, API);
        JsonObject consent = consented.json().getJsonObject("draft").getJsonObject("consent");
        assertEquals(needs.getString("digest"), consent.getString("digest"));
        assertEquals("secrets", consent.getJsonObject("given").getString("by"));
        JsonObject audit = audited("config.consent").getFirst();
        assertEquals("secrets", audit.getString("actor"));
        assertEquals(needs.getString("digest"), audit.getJsonObject("detail").getString("digest"));
        ConfigStore.ObjectView active = activate(a, API);
        assertEquals(1, active.json().getJsonObject("inForce").getInteger("version"));
        assertEquals(needs.getJsonArray("sends"), audited("config.activate").getFirst().getJsonObject("detail").getJsonArray("sends"));
        assertFalse(audited("config.activate").getFirst().getJsonObject("detail").containsKey("consentedByThisAct"));
        save(a, API, with(PARTNER, "path", "/v2"));
        assertNull(read(a, API).json().getJsonObject("draft").getValue("consent"), "the version in force sent these pairs");
    }

    @Test
    void anActivationByAHolderOfBothRolesConsentsAndNamesThePairsInItsAuditRow() {
        Node a = node("a");
        partnerSecrets(a);
        save(a, API, PARTNER);
        activate(a, BOTH, API);
        JsonObject detail = audited("config.activate").getFirst().getJsonObject("detail");
        assertTrue(detail.getBoolean("consentedByThisAct"));
        assertEquals(2, detail.getJsonArray("sends").size());
        assertEquals("both", audited("config.activate").getFirst().getString("actor"));
    }

    @Test
    void whichEditsNeedConsent() {
        Node a = node("a");
        partnerSecrets(a);
        secret(a, "partner.other", "the-other-value");
        inForce(a, API, with(PARTNER, "allowedHosts", List.of("cdn.example:443", "b.example:443")));
        Map<String, Object> base = with(PARTNER, "allowedHosts", List.of("cdn.example:443", "b.example:443"));
        Map<String, Map<String, Object>> need = new LinkedHashMap<>();
        need.put("a new host", with(base, "url", "https://elsewhere.example/api"));
        need.put("a new port", with(base, "url", "https://partner.example:8443/api"));
        need.put("a new secret", with(base, "token", "secret://partner.other"));
        need.put("a new trust secret", with(base, "pin", "secret://partner.other"));
        need.put("an added allowed host", with(base, "allowedHosts", List.of("cdn.example:443", "b.example:443", "new.example:443")));
        Map<String, Map<String, Object>> none = new LinkedHashMap<>();
        none.put("a path", with(base, "path", "/v2"));
        none.put("a proxy", with(base, "proxy", "proxy.example:3128"));
        none.put("a trust removed", with(base, "pin", null));
        none.put("a TLS switch", with(base, "verifyTls", false));
        none.put("a field set to its default", with(base, "timeoutMs", 30_000));
        none.put("a host's case", with(base, "url", "https://PARTNER.example/api"));
        none.put("hosts reordered", with(base, "allowedHosts", List.of("b.example:443", "cdn.example:443")));
        none.put("a host removed", with(base, "allowedHosts", List.of("b.example:443")));
        none.put("a sent secret taken away", with(base, "token", null));
        none.put("two secret fields swapped", with(base, "token", "secret://partner.pin", "pin", "secret://partner.token"));
        need.forEach((what, values) -> {
            assertNotNull(save(a, API, values).json().getJsonObject("draft").getValue("consent"), what);
        });
        none.forEach((what, values) -> {
            ConfigStore.ObjectView v = save(a, API, values);
            assertNull(v.json().getJsonObject("draft").getValue("consent"), what);
        });
    }

    @Test
    void goingBackToAServerOfAKeptVersionNeedsNoConsentAndOfOneBeyondRetentionNeedsIt() {
        Node a = node("a");
        partnerSecrets(a);
        a.store.limits(new ConfigObjects.Limits(ConfigObjects.MAX_CONNECTIONS, ConfigObjects.MAX_SYSTEMS, 8, 2));
        inForce(a, API, PARTNER);
        inForce(a, API, with(PARTNER, "url", "https://second.example/api"));
        ConfigStore.ObjectView back = write(a, CONNECTIONS, API, new Change.GoBack(etag(a, API), 1));
        assertEquals(body(PARTNER).getJsonObject("values"), back.json().getJsonObject("draft").getJsonObject("values"));
        assertNull(back.json().getJsonObject("draft").getValue("consent"), "version 1 is kept and sent these pairs");
        write(a, CONNECTIONS, API, new Change.Discard(etag(a, API)));
        inForce(a, API, with(PARTNER, "url", "https://third.example/api"));
        for (int i = 0; i < 4; i++) {
            write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
            write(a, CONNECTIONS, API, new Change.Start(read(a, API).run()));
        }
        assertEquals(8, historyRows(API), "the history is trimmed to its limit");
        List<Integer> kept = history(a, API).stream().filter(h -> h.getString("change").equals("activate")).map(h -> h.getInteger("version")).toList();
        assertEquals(List.of(3, 2), kept, "the newest activations are kept");
        refused(404, "not-found", () -> a.store.write(CONNECTIONS, API, new Change.GoBack(etag(a, API), 1)));
        assertNotNull(save(a, API, PARTNER).json().getJsonObject("draft").getValue("consent"), "version 1 is beyond retention");
        assertNull(save(a, API, with(PARTNER, "url", "https://second.example/api")).json().getJsonObject("draft").getValue("consent"),
                "version 2 is kept");
    }

    @Test
    void aHistoryRowChangedInTheDatabaseConsentsToNothing() {
        Node a = node("a");
        partnerSecrets(a);
        inForce(a, API, PARTNER);
        inForce(a, API, with(PARTNER, "url", "https://second.example/api"));
        tamperHistoryBody(API, 1, body(with(PARTNER, "url", "https://forged.example/api")));
        assertNotNull(save(a, API, with(PARTNER, "url", "https://forged.example/api")).json().getJsonObject("draft").getValue("consent"));
        assertNotNull(save(a, API, PARTNER).json().getJsonObject("draft").getValue("consent"), "the changed row no longer verifies");
    }

    @Test
    void aDraftChangedInTheDatabaseIsRefusedAtTheConsentAndAfterTheConsentAtTheActivation() {
        Node a = node("a");
        partnerSecrets(a);
        save(a, API, PARTNER);
        String digest = read(a, API).json().getJsonObject("draft").getJsonObject("consent").getString("digest");
        tamperDraft(API, body(with(PARTNER, "url", "https://forged.example/api")));
        refused(412, "changed", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API), Optional.of(digest))));
        save(a, API, PARTNER);
        consent(a, SECRETS, API);
        tamperDraft(API, body(with(PARTNER, "url", "https://forged.example/api")));
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals(0, read(a, API).json().getInteger("version"));
    }

    @Test
    void aConsentMovedToAnotherRowOrReplayedForALaterDraftConsentsToNothing() {
        Node a = node("a");
        partnerSecrets(a);
        Ref other = Ref.connection("acme/other");
        save(a, API, PARTNER);
        consent(a, SECRETS, API);
        JsonObject given = consentOf(API);
        save(a, other, PARTNER);
        putConsent(other, given);
        assertNull(read(a, other).json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, other, new Change.Activate(etag(a, other), Optional.empty(), Optional.empty())));
        copyIds(API, other);   // the row then differs from the one consented to by its key alone
        assertNull(read(a, other).json().getJsonObject("draft").getJsonObject("consent").getValue("given"), "a consent names its key");
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, other, new Change.Activate(etag(a, other), Optional.empty(), Optional.empty())));
        write(a, CONNECTIONS, API, new Change.Discard(etag(a, API)));
        save(a, API, PARTNER);
        putConsent(API, given);
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals(1, activate(a, BOTH, API).json().getJsonObject("inForce").getInteger("version"), "a holder of both roles consents again");
    }

    @Test
    void twoSecretAdministratorsGrantAndWithdraw() {
        Node a = node("a");
        Node b = node("b");
        partnerSecrets(a);
        save(a, API, PARTNER);
        String before = etag(a, API);
        consent(a, SECRETS, API);
        ConfigStore.ObjectView withdrawn = write(b, SECRETS_TOO, API, new Change.Consent(etag(b, API), Optional.empty()));
        assertNull(withdrawn.json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
        assertNotEquals(before, withdrawn.etag(), "a withdrawal is a change of its own");
        refused(409, "nothing-to-consent", () -> b.store.write(SECRETS_TOO, API, new Change.Consent(etag(b, API), Optional.empty())));
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        consent(b, SECRETS_TOO, API);
        assertEquals(List.of("secrets", "secrets-too", "secrets-too"), audited("config.consent").stream().map(r -> r.getString("actor")).toList());
        assertTrue(audited("config.consent").get(1).getJsonObject("detail").getBoolean("withdrawn"));
        activate(a, API);
    }

    @Test
    void aSaveThatChangesWhatTheDraftSendsDropsItsConsentAndOneThatDoesNotKeepsIt() {
        Node a = node("a");
        partnerSecrets(a);
        save(a, API, PARTNER);
        consent(a, SECRETS, API);
        assertNotNull(save(a, API, with(PARTNER, "path", "/v2")).json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
        assertNull(save(a, API, with(PARTNER, "url", "https://elsewhere.example/api")).json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
    }

    // ------------------------------------------------------------------------------------------------- the binding

    @Test
    void aTamperedOrUnboundBodyIsLeftOutOfEveryNodesStateAndNeverUsed() {
        Node a = node("a");
        Node b = node("b");
        inForce(a, API, PLAIN);
        inForce(a, FILES, PLAIN);
        ConfigStore.Held held = holding(b.store.state(ConfigStore.Held.nothing()));
        assertEquals(2, held.objects().size());
        String keyId = read(b, API).json().getJsonObject("inForce").getString("keyId");
        tamperBody(API, body(with(PLAIN, "url", "https://forged.example/api")));
        unbind(FILES);
        assertEquals("fails", read(b, API).json().getJsonObject("inForce").getString("binding"));
        assertEquals("fails", read(b, FILES).json().getJsonObject("inForce").getString("binding"));
        ConfigStore.State fresh = b.store.state(ConfigStore.Held.nothing());
        assertTrue(fresh.changed().isEmpty(), "no node takes them");
        assertEquals(Set.of(API, FILES), Set.copyOf(fresh.refused().objects()));
        assertEquals(2, fresh.refused().count());
        assertEquals(Map.of(API, keyId, FILES, "none"), fresh.refusedKeys(), "each with the key it is bound under, none when unbound");
        Ref later = Ref.connection("acme/later");
        inForce(a, later, PLAIN);
        ConfigStore.State state = b.store.state(held);
        assertEquals(Set.of(API, FILES), state.gone(), "a node that holds them lets them go");
        assertEquals(List.of(later), state.changed().stream().map(ConfigStore.InForce::ref).toList());
    }

    @Test
    void anActivationOverATamperedVersionInForceAsksConsentForEverySend() {
        Node a = node("a");
        partnerSecrets(a);
        inForce(a, API, PARTNER);
        save(a, API, with(PARTNER, "path", "/v2"));
        assertNull(read(a, API).json().getJsonObject("draft").getValue("consent"));
        tamperBody(API, body(with(PARTNER, "proxy", "forged.example:1")));
        tamperHistoryBody(API, 1, body(with(PARTNER, "proxy", "forged.example:1")));
        JsonObject needs = read(a, API).json().getJsonObject("draft").getJsonObject("consent");
        assertEquals(2, needs.getJsonArray("sends").size(), "nothing verified sent a pair");
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
    }

    @Test
    void anActivationOrAConsentWhileALiveNodeLacksTheKeyIsKeysDiffer() {
        Node a = node("a");
        node("b", K2, null);
        save(a, API, PARTNER);
        save(a, FILES, PLAIN);
        refused(409, "keys-differ", () -> a.store.write(SECRETS, API, new Change.Consent(etag(a, API),
                Optional.of(read(a, API).json().getJsonObject("draft").getJsonObject("consent").getString("digest")))));
        refused(409, "keys-differ", () -> a.store.write(BOTH, FILES, new Change.Activate(etag(a, FILES), Optional.empty(), Optional.empty())));
        assertEquals(0, read(a, FILES).json().getInteger("version"));
        assertNull(read(a, API).json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
        assertTrue(audited("config.activate").isEmpty());
        assertTrue(audited("config.consent").isEmpty());
    }

    @Test
    void bindingAgainUnderANewKeyKeepsWhatVerifiesAndClearsWhatDoesNot() {
        Node a = node("a");
        Node b = node("b");
        partnerSecrets(a);
        inForce(a, API, PLAIN);
        inForce(a, FILES, PLAIN);
        Ref waiting = Ref.connection("acme/waiting");
        save(a, waiting, PARTNER);
        consent(a, SECRETS, waiting);
        tamperBody(FILES, body(with(PLAIN, "url", "https://forged.example/api")));
        Ref forged = Ref.connection("acme/forged");
        save(a, forged, PARTNER);
        consent(a, SECRETS, forged);
        JsonObject forgedConsent = consentOf(forged);
        putConsent(forged, forgedConsent.copy().put("tag", Base64.getEncoder().encodeToString(new byte[32])));
        Ref kept = Ref.connection("acme/kept");
        inForce(a, kept, PARTNER);
        inForce(a, kept, with(PARTNER, "url", "https://second.example/api"));
        tamperHistoryBody(kept, 1, body(with(PARTNER, "url", "https://forged.example/api")));
        String underK1 = read(a, API).json().getJsonObject("inForce").getString("keyId");
        restart(a, K2, K1);
        restart(b, K2, K1);
        a.tick();
        b.tick();
        for (int i = 0; i < 3; i++) {
            a.tick();
            b.tick();
        }
        JsonObject api = read(b, API).json().getJsonObject("inForce");
        assertNotEquals(underK1, api.getString("keyId"), "bound under the new key");
        assertEquals("ok", api.getString("binding"));
        JsonObject files = read(b, FILES).json().getJsonObject("inForce");
        assertEquals("fails", files.getString("binding"), "a body that did not verify is not blessed");
        assertEquals(underK1, files.getString("keyId"), "its key id is kept");
        assertNotNull(read(b, waiting).json().getJsonObject("draft").getJsonObject("consent").getValue("given"));
        assertTrue(captured.stream().anyMatch(w -> w.contains("acme/files") && w.contains("does not verify")), captured.toString());
        assertNull(read(b, forged).json().getJsonObject("draft").getJsonObject("consent").getValue("given"), "a consent that did not verify is not blessed");
        assertEquals(forgedConsent.getString("keyId"), consentOf(forged).getString("keyId"), "its key id is kept");
        assertTrue(captured.stream().anyMatch(w -> w.contains("acme/forged") && w.contains("does not verify")), captured.toString());
        assertTrue(captured.stream().anyMatch(w -> w.contains("acme/kept") && w.contains("does not verify")), captured.toString());
        restart(a, K2, null);
        restart(b, K2, null);
        ConfigStore.State state = b.store.state(ConfigStore.Held.nothing());
        assertEquals(List.of(API, kept), state.changed().stream().map(ConfigStore.InForce::ref).toList());
        assertEquals(List.of(FILES), state.refused().objects());
        save(a, API, with(PLAIN, "path", "/v2"));
        assertNull(read(a, API).json().getJsonObject("draft").getValue("consent"));
        activate(a, waiting);
        refused(409, "consent-needed", () -> a.store.write(CONNECTIONS, forged, new Change.Activate(etag(a, forged), Optional.empty(), Optional.empty())));
        assertNotNull(save(a, kept, with(PARTNER, "url", "https://forged.example/api")).json().getJsonObject("draft").getValue("consent"),
                "an activation of the history that did not verify sent nothing, under any key");
    }

    @Test
    void idsNearTheEndsOfTheRangeBindExactlyAndADrawAnotherRowHoldsIsDrawnAgain() {
        Node a = node("a");
        a.random.script(Long.MAX_VALUE, Long.MIN_VALUE);   // the incarnation and the draft id of the first save
        save(a, API, PLAIN);
        a.random.script(Long.MIN_VALUE + 1);               // the activation
        activate(a, API);
        assertTrue(read(a, API).run().startsWith(Long.toHexString(Long.MAX_VALUE) + "."), read(a, API).run());
        assertEquals("ok", read(a, API).json().getJsonObject("inForce").getString("binding"));
        String underK1 = read(a, API).json().getJsonObject("inForce").getString("keyId");
        restart(a, K2, K1);
        for (int i = 0; i < 4; i++) a.tick();
        restart(a, K2, null);
        JsonObject api = read(a, API).json().getJsonObject("inForce");
        assertNotEquals(underK1, api.getString("keyId"), "bound again under the new key");
        assertEquals("ok", api.getString("binding"), "ids at the ends of the range are bound and verified as they are");
        assertEquals(List.of(API), a.store.state(ConfigStore.Held.nothing()).changed().stream().map(ConfigStore.InForce::ref).toList());
        a.random.script(Long.MAX_VALUE, Long.MIN_VALUE + 1);   // the incarnation and the activation acme/api holds
        save(a, FILES, PLAIN);
        String incarnation = read(a, FILES).run().split("\\.")[0];
        assertNotEquals(Long.toHexString(Long.MAX_VALUE), incarnation, "an incarnation another row holds is drawn again");
        assertNotEquals(Long.toHexString(Long.MIN_VALUE + 1), incarnation, "an activation another row holds is drawn again");
    }

    @Test
    void aBindingTellsVersion1WithFormat12FromVersion11WithFormat2() {
        JsonObject body = body(PLAIN);
        assertNotEquals(Jcs.write(ConfigObjects.bodyArray(API, 5L, 1, 7L, 12, body)), Jcs.write(ConfigObjects.bodyArray(API, 5L, 11, 7L, 2, body)));
        assertNotEquals(Jcs.write(ConfigObjects.bodyArray(Ref.connection("a/bc"), 5L, 1, 7L, 1, body)),
                Jcs.write(ConfigObjects.bodyArray(Ref.connection("a/b"), 5L, 1, 7L, 1, body)));
    }

    // ------------------------------------------------------------------------------------------------- activation

    @Test
    void anActivationIncompleteForEveryDirectionIsRefusedNamingEachFieldAndNoValue() {
        Node a = node("a");
        secret(a, "plain.value", "the-plain-value");
        save(a, API, values("path", "/x", "token", "secret://not.set"));
        ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals(422, r.status());
        assertEquals("incomplete", r.code());
        JsonArray lines = r.detail().getJsonArray("lines");
        assertTrue(lines.contains(new JsonObject().put("field", "url").put("code", "required").put("direction", "out")), lines.encode());
        assertTrue(lines.contains(new JsonObject().put("field", "url").put("code", "required").put("direction", "in")), lines.encode());
        assertTrue(lines.contains(new JsonObject().put("field", "token").put("code", "not-set").put("direction", "out")), lines.encode());
        for (Object line : lines) assertEquals(Set.of("field", "code", "direction"), ((JsonObject) line).fieldNames(), line.toString());
    }

    @Test
    void aSecretOfAKindItsFieldDoesNotTakeIsIncomplete() {
        Node a = node("a");
        secret(a, "files.password", "a-plain-value");
        save(a, FILES, "sftp", values("host", "files.example", "password", "secret://files.password"));
        ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, FILES, new Change.Activate(etag(a, FILES), Optional.empty(), Optional.empty())));
        assertEquals("incomplete", r.code());
        assertTrue(r.detail().getJsonArray("lines").contains(new JsonObject().put("field", "password").put("code", "kind").put("direction", "out")),
                r.detail().encode());
        credentials(a, "files.login", "files", "the-password");
        save(a, FILES, "sftp", values("host", "files.example", "password", "secret://files.login"));
        activate(a, BOTH, FILES);
    }

    @Test
    void anActivationThatLeavesAUseUnservedNeedsTheDigestOfTheUsesAsTheyAre() {
        Node a = node("a");
        inForce(a, API, PLAIN);
        use("pkg/inbound", "receive", "acme/api", "web", "in", false);
        save(a, API, with(PLAIN, "inbox", null));
        ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals(409, r.status());
        assertEquals("in-use", r.code());
        String digest = r.detail().getString("digest");
        JsonObject unserved = r.detail().getJsonArray("uses").getJsonObject(0);
        assertEquals("pkg/inbound", unserved.getString("flow"));
        assertEquals("incomplete", unserved.getJsonArray("lacks").getJsonObject(0).getString("lacks"));
        use("pkg/second", "receive", "acme/api", "web", "in", false);
        ConfigRefusal again = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.of(digest), Optional.empty())));
        assertEquals("in-use", again.code(), "a deploy changed the uses after the refusal");
        String confirmed = again.detail().getString("digest");
        assertNotEquals(digest, confirmed);
        write(a, CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.of(confirmed), Optional.empty()));
        assertEquals(confirmed, audited("config.activate").getLast().getJsonObject("detail").getString("confirm"));
    }

    @Test
    void aRemovalOrAnEmptiedAllowedHostsOfAUsedConnectionNeedsTheDigest() {
        Node a = node("a");
        inForce(a, API, with(PLAIN, "allowedHosts", List.of("cdn.example")));
        use("pkg/outbound", "send", "acme/api", "web", "out", true);
        save(a, API, with(PLAIN, "allowedHosts", List.of()));
        ConfigRefusal hosts = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals("in-use", hosts.code());
        assertEquals("allowedHosts", hosts.detail().getJsonArray("uses").getJsonObject(0).getJsonArray("lacks").getJsonObject(0).getString("lacks"));
        write(a, CONNECTIONS, API, new Change.Discard(etag(a, API)));
        write(a, CONNECTIONS, API, new Change.Remove(etag(a, API)));
        ConfigRefusal removal = refused(() -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        assertEquals("in-use", removal.code());
        assertEquals("removed", removal.detail().getJsonArray("uses").getJsonObject(0).getJsonArray("lacks").getJsonObject(0).getString("lacks"));
        String run = read(a, API).run();
        ConfigStore.ObjectView removed = write(a, CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.of(removal.detail().getString("digest")),
                Optional.empty()));
        assertTrue(removed.json().getJsonObject("inForce").getBoolean("removed"));
        assertNotEquals(run, removed.run(), "a removal ends its stops and starts");
        refused(409, "not-set", () -> a.store.write(CONNECTIONS, API, new Change.Stop(removed.run())));
        assertTrue(a.store.state(ConfigStore.Held.nothing()).changed().isEmpty());
    }

    @Test
    void anAdapterOtherThanTheDeployedSystemNamesIsRefusedAtTheSaveAndAtTheActivation() {
        Node a = node("a");
        save(a, API, PLAIN);
        deploySystem("acme", new JsonObject().put("connections", new JsonObject().put("api", new JsonObject().put("adapter", "sftp"))));
        refused(409, "adapter-differs", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        refused(409, "adapter-differs", () -> a.store.write(CONNECTIONS, API, new Change.Save(etag(a, API), "web", with(PLAIN, "path", "/v2"),
                Optional.empty())));
        save(a, API, "sftp", values("host", "files.example"));
        activate(a, API);
    }

    @Test
    void aMockedBodyIsRefusedWhereMocksAreNotAllowedAndNotYetActivatedWhereTheyAre() {
        Node a = node("a");
        write(a, CONNECTIONS, API, new Change.Save(etag(a, API), "web", PLAIN, Optional.of(new Change.Mock("echo", Optional.of("50ms"),
                Optional.empty()))));
        refused(409, "mocks-refused", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
        a.store.writeMocks(CONNECTIONS, new Change.Mocks(false, true, Optional.empty()));
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.empty())));
    }

    @Test
    void mocksAreTurnedOffOnlyWithTheMockedUsedConnectionsConfirmedAndOnWithout() {
        Node a = node("a");
        Node b = node("b");
        ConfigStore.Held held = holding(b.store.state(ConfigStore.Held.nothing()));
        a.store.writeMocks(CONNECTIONS, new Change.Mocks(false, true, Optional.empty()));
        assertTrue(b.store.state(held).mocksAllowed());
        assertFalse(b.store.state(held).generation() == held.generation(), "the switch is a change every node reads");
        refused(412, "changed", () -> b.store.writeMocks(CONNECTIONS, new Change.Mocks(false, true, Optional.empty())));
        inForce(a, API, PLAIN);
        tamperBody(API, body(PLAIN).put("mock", new JsonObject().put("reply", "echo")));
        use("pkg/outbound", "send", "acme/api", "web", "out", false);
        ConfigRefusal r = refused(() -> a.store.writeMocks(CONNECTIONS, new Change.Mocks(true, false, Optional.empty())));
        assertEquals("in-use", r.code());
        assertEquals("acme/api", r.detail().getJsonArray("uses").getJsonObject(0).getString("connection"));
        a.store.writeMocks(CONNECTIONS, new Change.Mocks(true, false, Optional.of(r.detail().getString("digest"))));
        assertFalse(b.store.state(held).mocksAllowed());
        List<JsonObject> audit = audited("config.mocks");
        assertEquals(List.of(true, false), audit.stream().map(x -> x.getJsonObject("detail").getBoolean("allowed")).toList());
        assertEquals("config/mocks", audit.getFirst().getString("subject"));
    }

    @Test
    void anInstanceNameInUseIsChangedAndItsRemovalRefused() {
        Node a = node("a");
        write(a, CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), "Acme Corp"));
        activate(a, ACME);
        use("pkg/outbound", "send", "acme/api", "web", "out", false);
        write(a, CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), "Acme Corporation"));
        assertEquals("Acme Corporation", activate(a, ACME).json().getJsonObject("inForce").getString("instanceName"));
        write(a, CONNECTIONS, ACME, new Change.Remove(etag(a, ACME)));
        ConfigRefusal r = refused(() -> a.store.write(CONNECTIONS, ACME, new Change.Activate(etag(a, ACME), Optional.empty(), Optional.empty())));
        assertEquals(409, r.status());
        assertEquals("name-in-use", r.code());
        assertEquals(new JsonArray().add("pkg/outbound"), r.detail().getJsonArray("flows"));
    }

    @Test
    void anAuditRowNotWrittenChangesNothing() {
        Node a = node("a");
        save(a, API, PLAIN);
        String etag = etag(a, API);
        auditFails(true);
        try {
            refused(503, "audit-unavailable", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag, Optional.empty(), Optional.empty())));
        } finally {
            auditFails(false);
        }
        assertEquals(etag, etag(a, API));
        assertEquals(0, read(a, API).json().getInteger("version"));
        assertTrue(a.store.state(ConfigStore.Held.nothing()).changed().isEmpty());
        activate(a, API);
    }

    // ------------------------------------------------------------------------------------------------- the history

    @Test
    void aNoteIsKeptInTheHistoryAndTheAuditAndOneOver200CharactersRefused() {
        Node a = node("a");
        save(a, API, PLAIN);
        refused(422, "invalid", () -> a.store.write(CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.of("n".repeat(201)))));
        write(a, CONNECTIONS, API, new Change.Activate(etag(a, API), Optional.empty(), Optional.of("commit 1a2b3c")));
        JsonObject row = history(a, API).getFirst();
        assertEquals("commit 1a2b3c", row.getString("note"));
        assertEquals(1, row.getInteger("version"));
        assertEquals("session", row.getString("via"));
        assertEquals("commit 1a2b3c", audited("config.activate").getFirst().getJsonObject("detail").getString("note"));
    }

    @Test
    void theHistoryNamesASecretFieldByItsReferenceAndPagesNewestFirst() {
        Node a = node("a");
        partnerSecrets(a);
        inForce(a, API, PARTNER);
        save(a, API, with(PARTNER, "path", "/v2"));
        save(a, API, with(PARTNER, "path", "/v3"));
        List<JsonObject> all = history(a, API);
        assertEquals(List.of("save", "save", "activate", "save"), all.stream().map(h -> h.getString("change")).toList());
        JsonArray diff = all.get(3).getJsonArray("diff");
        assertTrue(diff.contains(new JsonObject().put("field", "values.token").putNull("from").put("to", "secret://partner.token")), diff.encode());
        ConfigStore.Page<ConfigStore.HistoryRow> first = a.store.history(API, OptionalLong.empty(), 2);
        assertEquals(2, first.items().size());
        ConfigStore.Page<ConfigStore.HistoryRow> second = a.store.history(API, OptionalLong.of(Long.parseLong(first.next().orElseThrow())), 2);
        assertEquals(List.of("activate", "save"), second.items().stream().map(h -> h.json().getString("change")).toList());
        assertEquals(Optional.empty(), second.next());
        refused(400, "bad-request", () -> a.store.history(API, OptionalLong.empty(), 0));
    }

    @Test
    void aBusyObjectKeepsItsNewestActivationsThroughItsStopsAndStarts() {
        Node a = node("a");
        a.store.limits(new ConfigObjects.Limits(ConfigObjects.MAX_CONNECTIONS, ConfigObjects.MAX_SYSTEMS, 10, 3));
        inForce(a, API, PLAIN);
        for (int i = 0; i < 12; i++) {
            write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
            write(a, CONNECTIONS, API, new Change.Start(read(a, API).run()));
        }
        assertEquals(10, historyRows(API));
        assertTrue(history(a, API).stream().anyMatch(h -> h.getString("change").equals("activate")), "the activation is kept");
        ConfigStore.ObjectView back = write(a, CONNECTIONS, API, new Change.GoBack(etag(a, API), 1));
        assertNull(back.json().getValue("draft"), "going back to the version in force is no draft");
    }

    // ------------------------------------------------------------------------------------------------- the database set back

    @Test
    void aDatabaseSetBackIsSeenByEveryNodeAndAnActivationToTheSameVersionNumberIsANewActivation() {
        Node a = node("a");
        Node b = node("b");
        inForce(a, API, PLAIN);
        Object before = snapshot();
        ConfigStore.ObjectView second = inForce(a, API, with(PLAIN, "path", "/v2"));
        ConfigStore.Held held = holding(b.store.state(ConfigStore.Held.nothing()));
        setBack(before);
        ConfigStore.State back = b.store.state(held);
        assertFalse(back.current(), "a node on the other side of a set back does not hold the store");
        assertEquals(1, read(b, API).json().getJsonObject("inForce").getInteger("version"));
        assertEquals(body(PLAIN), back.changed().getFirst().body());
        ConfigStore.Held afterSetBack = holding(back);
        ConfigStore.ObjectView again = inForce(a, API, with(PLAIN, "path", "/v2"));
        assertEquals(2, again.json().getJsonObject("inForce").getInteger("version"));
        assertNotEquals(second.json().getString("activation"), again.json().getString("activation"), "an activation's id is never drawn again");
        assertFalse(b.store.state(held).current(), "the node that held the version 2 before the set back takes the new one");
        assertNotEquals(held.objects().get(API).activation(), b.store.state(held).changed().getFirst().activation());
        assertFalse(b.store.state(afterSetBack).current());
    }

    @Test
    void aRowDeletedInTheDatabaseIsGoneFromEveryNodesState() {
        Node a = node("a");
        Node b = node("b");
        inForce(a, API, PLAIN);
        ConfigStore.Held held = holding(b.store.state(ConfigStore.Held.nothing()));
        deleteRow(API);
        inForce(a, FILES, PLAIN);
        ConfigStore.State state = b.store.state(held);
        assertEquals(Set.of(API), state.gone());
        refused(404, "not-found", () -> b.store.read(API));
    }

    // ------------------------------------------------------------------------------------------------- export and import

    static final Set<Ref.Kind> EVERY_KIND = EnumSet.allOf(Ref.Kind.class);
    static final ConfigStore.Scope EVERYTHING = ConfigStore.Scope.everything();

    /** The text of the file of the connection {@code ref}: the envelope of format 1, then {@code rest}. */
    static String connectionFile(Ref ref, String rest) {
        return "schemaVersion: 1\nkind: environment.connection\nkey: " + ref.key() + "\n" + rest;
    }

    static ConfigStore.File file(Ref ref, String rest) {
        return new ConfigStore.File(ConfigFiles.path(ref), connectionFile(ref, rest));
    }

    /** A web connection's file with the url and inbox of {@link #PLAIN} and {@code more} lines of values. */
    static ConfigStore.File plainFile(Ref ref, String... more) {
        StringBuilder values = new StringBuilder("adapter: web\nvalues:\n  url: https://plain.example/api\n  inbox: /in\n");
        for (String line : more) values.append("  ").append(line).append('\n');
        return file(ref, values.toString());
    }

    ConfigStore.Export exported(Node n, ConfigStore.Scope scope) {
        ConfigStore.Export e = n.store.export(scope);
        e.files().forEach(f -> captured.add(f.text()));
        return e;
    }

    JsonObject dryRun(Node n, ConfigStore.Scope scope, List<ConfigStore.File> files) {
        JsonObject plan = n.store.importCheck(EVERY_KIND, scope, files).json();
        captured.add(plan.encode());
        return plan;
    }

    /** The dry run of {@code files} that {@code n} refuses with 422 {@code code}. */
    JsonObject problems(Node n, ConfigStore.Scope scope, List<ConfigStore.File> files, String code) {
        ConfigRefusal r = refused(() -> n.store.importCheck(EVERY_KIND, scope, files));
        assertEquals(422, r.status(), r.getMessage());
        assertEquals(code, r.code(), r.getMessage());
        return r.detail();
    }

    JsonObject imported(Node n, ConfigStore.Scope scope, List<ConfigStore.File> files, Optional<String> note) {
        String expect = dryRun(n, scope, files).getString("expect");
        JsonObject answer = n.store.importWrite(CONNECTIONS, EVERY_KIND, scope, files, expect, note).json();
        captured.add(answer.encode());
        return answer;
    }

    static List<String> codes(JsonObject plan) {
        return plan.getJsonArray("problems").stream().map(p -> ((JsonObject) p).getString("code")).toList();
    }

    static JsonObject object(JsonObject plan, Ref ref) {
        for (Object o : plan.getJsonArray("objects")) {
            JsonObject j = (JsonObject) o;
            if (j.getString("kind").equals(ref.kind().id()) && j.getString("key").equals(ref.key())) return j;
        }
        throw new AssertionError(ref + " is not among the objects: " + plan.encode());
    }

    static List<String> keys(JsonArray refs) {
        return refs.stream().map(o -> ((JsonObject) o).getString("kind") + "/" + ((JsonObject) o).getString("key")).toList();
    }

    @Test
    void anExportImportsBackUnchangedAndItsFilesHoldNoSecretValueNorADefault() {
        Node a = node("a");
        partnerSecrets(a);
        MemorySecretStores.createKeyPair(a.secrets, "client.key");
        inForce(a, API, with(PARTNER, "timeoutMs", 30_000));
        Ref tls = Ref.connection("acme/tls");
        ConfigStore.ObjectView saved = save(a, tls, "tls", values("host", "tls.example", "key", "secret://client.key"));
        if (saved.json().getJsonObject("draft").getValue("consent") != null) consent(a, SECRETS, tls);
        activate(a, tls);
        write(a, CONNECTIONS, ACME, new Change.SaveSystem(etag(a, ACME), "ACME production"));
        activate(a, ACME);
        save(a, FILES, "sftp", values("host", "sftp.example"));

        ConfigStore.Export export = exported(a, EVERYTHING);
        assertEquals(List.of("connection/acme/api.yaml", "connection/acme/tls.yaml", "system/acme.yaml"),
                export.files().stream().map(ConfigStore.File::path).toList(), "only what is in force, a draft never");
        assertEquals(List.of(), export.refused());
        String api = export.files().get(0).text();
        assertFalse(api.contains("timeoutMs"), "a value at its default is left out: " + api);
        assertTrue(api.contains("  token: secret://partner.token\n"), api);
        assertTrue(api.contains("secrets:\n  partner.pin:\n    kind: value\n  partner.token:\n    kind: value\n"), api);
        String key = export.files().get(1).text();
        assertTrue(key.contains("  client.key:\n    kind: key-pair\n    algorithm: "), key);
        assertTrue(key.contains("    fingerprint: "), key);
        assertEquals("schemaVersion: 1\nkind: environment.system\nkey: acme\ninstanceName: ACME production\n", export.files().get(2).text());

        Map<Ref, String> etags = Map.of(API, etag(a, API), tls, etag(a, tls), ACME, etag(a, ACME));
        int apiHistory = historyRows(API);
        JsonObject plan = dryRun(a, EVERYTHING, export.files());
        assertEquals(List.of(), codes(plan));
        for (Ref ref : etags.keySet()) {
            assertEquals("unchanged", object(plan, ref).getString("change"), plan.encode());
            assertTrue(object(plan, ref).getBoolean("inForce"), plan.encode());
            assertEquals(new JsonArray(), object(plan, ref).getJsonArray("fields"));
        }
        assertEquals(List.of("connection/acme/files"), keys(plan.getJsonArray("unnamed")), "a draft no file names");
        JsonObject answer = imported(a, EVERYTHING, export.files(), Optional.empty());
        for (Ref ref : etags.keySet()) {
            assertEquals(etags.get(ref), etag(a, ref), "an unchanged object is not written: " + ref);
            assertEquals(etags.get(ref), object(answer, ref).getString("etag"));
        }
        assertEquals(apiHistory, historyRows(API));

        assertEquals(List.of("system/acme.yaml"), exported(a, new ConfigStore.Scope(Optional.of(Ref.Kind.SYSTEM), Optional.empty()))
                .files().stream().map(ConfigStore.File::path).toList());
        assertEquals(List.of("connection/acme/tls.yaml"), exported(a, new ConfigStore.Scope(Optional.empty(), Optional.of("acme/t")))
                .files().stream().map(ConfigStore.File::path).toList());

        tamperBody(API, body(with(PLAIN, "url", "https://forged.example/api")));
        ConfigStore.Export tampered = exported(a, EVERYTHING);
        assertEquals(List.of(API), tampered.refused(), "a body that does not verify is never exported");
        assertEquals(List.of("connection/acme/tls.yaml", "system/acme.yaml"), tampered.files().stream().map(ConfigStore.File::path).toList());
    }

    @Test
    void aBodyAtTheMostASaveTakesFitsItsFileAndImportsBackUnchanged() {
        Node a = node("a");
        String piece = "a\"é\\ é/";
        StringBuilder path = new StringBuilder();
        while (Jcs.bytes(body(with(PLAIN, "path", path.toString() + piece))).length <= ConfigObjects.MAX_BODY_BYTES) path.append(piece);
        while (Jcs.bytes(body(with(PLAIN, "path", path.toString() + "a"))).length <= ConfigObjects.MAX_BODY_BYTES) path.append('a');
        assertEquals(ConfigObjects.MAX_BODY_BYTES, Jcs.bytes(body(with(PLAIN, "path", path.toString()))).length);
        inForce(a, API, with(PLAIN, "path", path.toString()));
        ConfigStore.Export export = exported(a, EVERYTHING);
        String text = export.files().getFirst().text();
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ConfigFiles.MAX_FILE_BYTES, "the file takes "
                + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes");
        assertEquals("unchanged", object(dryRun(a, EVERYTHING, export.files()), API).getString("change"));
    }

    @Test
    void aDryRunListsCreatedChangedAndUnchangedObjectsWithTheirFieldsNotesAndUnnamedRows() {
        Node a = node("a");
        deploySystem("acme", new JsonObject().put("connections", new JsonObject().put("api", new JsonObject().put("adapter", "web"))));
        inForce(a, API, PLAIN);
        Ref same = Ref.connection("acme/same");
        inForce(a, same, PLAIN);
        Ref other = Ref.connection("acme/other");
        save(a, other, PLAIN);
        Ref created = Ref.connection("acme/new");
        Ref mocked = Ref.connection("acme/mocked");
        Ref outside = Ref.connection("zeta/kept");
        inForce(a, outside, PLAIN);
        List<ConfigStore.File> files = List.of(plainFile(API, "path: /v2"), plainFile(same, "verifyTls: true"), plainFile(created),
                file(mocked, "adapter: web\nmock:\n  reply: echo\nvalues:\n  url: https://mocked.example/api\n  inbox: /in\n"),
                new ConfigStore.File("system/acme.yaml", "schemaVersion: 1\nkind: environment.system\nkey: acme\ninstanceName: ACME\n"));
        ConfigStore.Scope acme = new ConfigStore.Scope(Optional.empty(), Optional.of("acme"));
        JsonObject plan = dryRun(a, acme, files);
        assertEquals(List.of(), codes(plan));
        assertEquals(List.of("connection/acme/api", "connection/acme/mocked", "connection/acme/new", "connection/acme/same", "system/acme"),
                keys(plan.getJsonArray("objects")), "by kind and key");
        assertEquals("changed", object(plan, API).getString("change"));
        assertEquals(new JsonArray().add("values.path"), object(plan, API).getJsonArray("fields"));
        assertFalse(object(plan, API).getBoolean("inForce"));
        assertEquals("unchanged", object(plan, same).getString("change"), "a value at its default is a value not set");
        assertTrue(object(plan, same).getBoolean("inForce"));
        assertEquals("created", object(plan, created).getString("change"));
        assertEquals("created", object(plan, ACME).getString("change"));
        assertEquals(List.of(), keys(plan.getJsonArray("blockers")));
        List<String> notes = plan.getJsonArray("notes").stream().map(o -> ((JsonObject) o).getString("key") + " " + ((JsonObject) o).getString("code")).toList();
        assertEquals(List.of("acme/mocked not-deployed", "acme/mocked mocks-not-allowed", "acme/new not-deployed", "acme/same not-deployed"), notes);
        assertEquals(List.of("connection/acme/other"), keys(plan.getJsonArray("unnamed")), "a row outside the scope is not unnamed");
        assertTrue(plan.getString("expect").matches("[0-9a-f]{64}"), plan.getString("expect"));
        assertEquals(plan.getString("expect"), dryRun(a, acme, files.reversed()).getString("expect"), "the order of the files is no change");
        assertNotEquals(plan.getString("expect"), dryRun(a, EVERYTHING, files).getString("expect"), "nor is the scope the same");
    }

    @Test
    void aSecretALaterActivationWouldMissIsABlockerThatDoesNotRefuseTheImport() {
        Node a = node("a");
        partnerSecrets(a);
        secret(a, "sftp.value", "a-plain-value");
        tamperSecret("partner.token");
        restart(a, K1, null);
        List<ConfigStore.File> files = List.of(
                file(API, "adapter: web\nvalues:\n  url: https://partner.example/api\n  token: secret://partner.token\n  inbox: /in\n"),
                file(FILES, "adapter: sftp\nvalues:\n  host: sftp.example\n  password: secret://sftp.value\n  hostKey: secret://sftp.missing\n"),
                file(Ref.connection("acme/mocked"), "adapter: web\nmock:\n  reply: echo\nvalues:\n  url: https://m.example/api\n  token: secret://none.here\n  inbox: /in\n"));
        JsonObject plan = dryRun(a, EVERYTHING, files);
        List<String> blockers = plan.getJsonArray("blockers").stream().map(o -> {
            JsonObject b = (JsonObject) o;
            return b.getString("key") + " " + b.getString("field") + " " + b.getString("secret") + " " + b.getString("code");
        }).toList();
        assertEquals(List.of("acme/api token partner.token unreadable", "acme/files password sftp.value kind", "acme/files hostKey sftp.missing not-set"),
                blockers, "a mocked connection sends nothing, so it has none");
        JsonObject answer = imported(a, EVERYTHING, files, Optional.empty());
        assertEquals(plan.getJsonArray("blockers"), answer.getJsonArray("blockers"));
        assertEquals(plan.getJsonArray("blockers"), audited("config.import").getFirst().getJsonObject("detail").getJsonArray("blockers"));
        assertNotNull(read(a, FILES).json().getJsonObject("draft"));
    }

    @Test
    void everyProblemOfEveryFileIsListedAndNothingIsWrittenWhileAnyIsLeft() {
        Node a = node("a");
        save(a, FILES, "sftp", values("host", "sftp.example"));
        Ref good = Ref.connection("acme/new");
        List<ConfigStore.File> files = new ArrayList<>(List.of(
                new ConfigStore.File("connection/acme/broken.yaml", "key: [unclosed\n"),
                file(API, "adapter: web\nvalues:\n  url: https://plain.example/api\n  inbox: /in\nrevision: 3\n"),
                new ConfigStore.File("connection/Bad.yaml", "schemaVersion: 1\nkind: environment.connection\nkey: Bad\nadapter: web\nvalues: {}\n"),
                plainFile(good),
                plainFile(good, "path: /second"),
                plainFile(Ref.connection("zeta/x")),
                plainFile(Ref.connection("acme/odd"), "colour: red"),
                plainFile(Ref.connection("acme/slow"), "timeoutMs: soon"),
                file(FILES, "adapter: sftp\nvalues:\n  host: other.example\n"),
                new ConfigStore.File("connection/acme/future.yaml", "schemaVersion: 2\nkind: environment.connection\nkey: acme/future\n")));
        ConfigStore.Scope acme = new ConfigStore.Scope(Optional.empty(), Optional.of("acme/"));
        JsonObject plan = problems(a, acme, files, "format");
        assertEquals(new TreeSet<>(List.of("yaml", "schema", "key", "duplicate", "outside-scope", "unknown-field", "invalid", "draft-waits", "format")),
                new TreeSet<>(codes(plan)), plan.encode());
        assertEquals("created", object(plan, good).getString("change"), "a good file is still checked");
        JsonObject waits = plan.getJsonArray("problems").stream().map(o -> (JsonObject) o).filter(p -> p.getString("code").equals("draft-waits")).findFirst().orElseThrow();
        assertEquals("connections", waits.getString("savedBy"));
        JsonObject unknown = plan.getJsonArray("problems").stream().map(o -> (JsonObject) o).filter(p -> p.getString("code").equals("unknown-field")).findFirst().orElseThrow();
        assertEquals("colour", unknown.getString("field"));
        assertEquals("web", unknown.getString("adapter"));

        files.removeLast();
        assertTrue(problems(a, acme, files, "problems").getJsonArray("problems").size() > 0);
        refused(422, "problems", () -> a.store.importWrite(CONNECTIONS, EVERY_KIND, acme, files, dryRunExpect(a, acme, files), Optional.empty()));
        refused(404, "not-found", () -> a.store.read(good));

        ConfigRefusal notAllowed = refused(() -> a.store.importCheck(EnumSet.of(Ref.Kind.SYSTEM), EVERYTHING, List.of(plainFile(good))));
        assertEquals(List.of("not-allowed"), codes(notAllowed.detail()));
        List<ConfigStore.File> many = new ArrayList<>();
        for (int i = 0; i <= ConfigStore.MAX_IMPORT_FILES; i++) many.add(plainFile(Ref.connection("acme/c" + i)));
        refused(413, "too-large", () -> a.store.importCheck(EVERY_KIND, EVERYTHING, many));
    }

    /** The expect of the dry run of {@code files}, whether it has problems or not. */
    String dryRunExpect(Node n, ConfigStore.Scope scope, List<ConfigStore.File> files) {
        try {
            return n.store.importCheck(EVERY_KIND, scope, files).expect();
        } catch (ConfigRefusal r) {
            captured.add(r.body().encode());
            return r.detail().getString("expect");
        }
    }

    @Test
    void aWriteIsAllOrNoneAndRefusedWithTheDryRunAsItIsNowAfterAChangeOfAFileOrAnObject() {
        Node a = node("a");
        inForce(a, API, PLAIN);
        Ref created = Ref.connection("acme/new");
        List<ConfigStore.File> files = List.of(plainFile(API, "path: /v2"), plainFile(created));
        String expect = dryRun(a, EVERYTHING, files).getString("expect");

        List<ConfigStore.File> edited = List.of(plainFile(API, "path: /v3"), plainFile(created));
        ConfigRefusal fileChanged = refused(() -> a.store.importWrite(CONNECTIONS, EVERY_KIND, EVERYTHING, edited, expect, Optional.empty()));
        assertEquals(412, fileChanged.status());
        assertEquals("changed", fileChanged.code());
        assertEquals(dryRun(a, EVERYTHING, edited).getString("expect"), fileChanged.detail().getString("expect"), "the dry run as it is now");

        save(a, API, with(PLAIN, "path", "/elsewhere"));
        write(a, CONNECTIONS, API, new Change.Discard(etag(a, API)));
        refused(412, "changed", () -> a.store.importWrite(CONNECTIONS, EVERY_KIND, EVERYTHING, files, expect, Optional.empty()));
        save(a, created, PLAIN);
        write(a, CONNECTIONS, created, new Change.Discard(etag(a, created)));
        refused(412, "changed", () -> a.store.importWrite(CONNECTIONS, EVERY_KIND, EVERYTHING, files, dryRunExpect(a, EVERYTHING, List.of(plainFile(API, "path: /v2"))),
                Optional.empty()));

        a.store.limits(new ConfigObjects.Limits(2, 1, ConfigObjects.MAX_HISTORY, ConfigObjects.KEPT_ACTIVATIONS));
        List<ConfigStore.File> three = List.of(plainFile(API, "path: /v2"), plainFile(Ref.connection("acme/new2")), plainFile(Ref.connection("acme/new3")));
        refused(409, "config-full", () -> a.store.importWrite(CONNECTIONS, EVERY_KIND, EVERYTHING, three, dryRunExpect(a, EVERYTHING, three), Optional.empty()));
        assertNull(read(a, API).json().getValue("draft"), "all or none");
        refused(404, "not-found", () -> a.store.read(Ref.connection("acme/new2")));

        int revision = read(a, created).json().getInteger("revision");
        JsonObject answer = imported(a, EVERYTHING, List.of(plainFile(API, "path: /v2")), Optional.empty());
        assertEquals("changed", object(answer, API).getString("change"));
        assertEquals(etag(a, API), object(answer, API).getString("etag"));
        assertEquals("/v2", read(a, API).json().getJsonObject("draft").getJsonObject("values").getString("path"));
        assertEquals(revision, read(a, created).json().getInteger("revision"), "an object no file names is not touched");
        refused(422, "invalid", () -> a.store.importWrite(CONNECTIONS, EVERY_KIND, EVERYTHING, files, expect, Optional.of("x".repeat(ConfigObjects.MAX_NOTE + 1))));
    }

    @Test
    void anImportWritesDraftsWithHistoryAndOneAuditRowAndADraftEqualToItsFileKeepsItsConsent() {
        Node a = node("a");
        partnerSecrets(a);
        inForce(a, API, PLAIN);
        save(a, API, PARTNER);
        consent(a, SECRETS, API);
        int apiHistory = historyRows(API);
        List<ConfigStore.File> files = List.of(
                file(API, "adapter: web\nvalues:\n  url: https://partner.example/api\n  token: secret://partner.token\n  pin: secret://partner.pin\n  inbox: /in\n"),
                file(FILES, "adapter: sftp\nvalues:\n  host: sftp.example\n"));
        JsonObject plan = dryRun(a, EVERYTHING, files);
        assertEquals("unchanged", object(plan, API).getString("change"), "the file is the draft");
        assertFalse(object(plan, API).getBoolean("inForce"));
        JsonObject answer = imported(a, EVERYTHING, files, Optional.of("from staging"));
        assertEquals("created", object(answer, FILES).getString("change"));
        assertEquals(apiHistory, historyRows(API));
        assertNotNull(read(a, API).json().getJsonObject("draft").getJsonObject("consent").getValue("given"), "its consent is kept");

        JsonObject h = history(a, FILES).getFirst();
        assertEquals("import", h.getString("change"));
        assertEquals("from staging", h.getString("note"));
        assertEquals("connections", h.getString("actor"));
        List<JsonObject> audit = audited("config.import");
        assertEquals(1, audit.size());
        assertEquals("config/import", audit.getFirst().getString("subject"));
        JsonObject detail = audit.getFirst().getJsonObject("detail");
        assertEquals("from staging", detail.getString("note"));
        assertEquals(EVERYTHING.json(), detail.getJsonObject("scope"));
        assertEquals(List.of("connection/acme/api", "connection/acme/files"), keys(detail.getJsonArray("objects")));
        assertEquals(new JsonArray().add("adapter").add("values.host"), object(detail, FILES).getJsonArray("fields"));

        activate(a, API);
        assertEquals("https://partner.example/api", read(a, API).json().getJsonObject("inForce").getJsonObject("values").getString("url"));
        activate(a, FILES);
        assertEquals("unchanged", object(dryRun(a, EVERYTHING, exported(a, EVERYTHING).files()), FILES).getString("change"));
    }
}
