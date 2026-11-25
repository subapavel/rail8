package dev.rail8.runtime.admin;

import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The routes of the secret store over HTTP: a secret administrator creates each kind (201), overwrites (200) and deletes (204)
 * through a session and a token, a developer lists; each code of the routes' table where it should be, as
 * {@code {error, code, values}}; each write's audit row names its actor. No answer, log line or audit row holds a value written,
 * in any of its encodings.
 */
class AdminServerSecretsTest extends AdminAccessFixture {

    /** Every value a test wrote. */
    final List<String> written = new ArrayList<>();
    /** Every text a test captured: the answers and the log lines. */
    final List<String> captured = new CopyOnWriteArrayList<>();
    final Handler logs = new Handler() {
        @Override public void publish(LogRecord r) {
            captured.add(String.valueOf(r.getMessage()) + (r.getThrown() == null ? "" : " " + r.getThrown()));
        }
        @Override public void flush() { }
        @Override public void close() { }
    };
    final Logger rail8 = Logger.getLogger("dev.rail8");

    String admin;
    String developer;

    @BeforeEach
    void users() {
        rail8.addHandler(logs);
        rail8.setLevel(Level.ALL);
        String root = root();
        admin = changed("keeper", person(root, "keeper", "secret-administrator"));
        developer = changed("dev", person(root, "dev", "developer"));
    }

    @AfterEach
    void unhook() {
        rail8.removeHandler(logs);
        rail8.setLevel(null);
    }

    Answer call(String method, String path, Optional<String> bearer, String body) {
        Answer answer = send(key, method, path, bearer, body);
        captured.add(answer.body());
        return answer;
    }

    Answer create(Optional<String> bearer, JsonObject body) {
        return call("POST", "/admin/secrets", bearer, body.encode());
    }

    static JsonObject value(String name, String value) {
        return new JsonObject().put("name", name).put("kind", "value").put("value", value);
    }

    /** A refusal of {@code status} and {@code code}, in the shape {@code {error, code, values}}. */
    static void refused(Answer answer, int status, String code) {
        assertEquals(status, answer.status(), answer.body());
        JsonObject body = answer.json();
        assertEquals(code, body.getString("code"), answer.body());
        assertTrue(body.getValue("error") instanceof String && body.getValue("values") instanceof JsonObject, answer.body());
    }

    static String pkcs8Pem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(g.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void aSecretAdministratorWritesEachKindAndADeveloperLists() throws Exception {
        Optional<String> session = Optional.of(admin);
        Optional<String> token = Optional.of(token(admin, "cli"));
        String pem = pkcs8Pem();
        written.addAll(List.of("plain-value-1", "plain-value-2", "the-password-1", pem));

        Answer db = create(session, value("db", "plain-value-1"));
        assertEquals(201, db.status(), db.body());
        assertEquals("value", db.json().getString("kind"));
        assertEquals("keeper", db.json().getString("changedBy"));
        assertEquals(201, create(token, new JsonObject().put("name", "login").put("kind", "credentials").put("user", "svc")
                .put("password", "the-password-1")).status(), "through a token too");
        Answer pair = create(session, new JsonObject().put("name", "tls").put("kind", "key-pair").put("privateKey", pem).put("certificates", new JsonArray()));
        assertEquals(201, pair.status(), pair.body());
        assertEquals("EC", pair.json().getJsonObject("keyPair").getString("algorithm"), pair.body());

        Answer listed = call("GET", "/admin/secrets", Optional.of(developer), null);
        assertEquals(200, listed.status(), listed.body());
        assertEquals(List.of("db", "login", "tls"), listed.json().getJsonArray("secrets").stream().map(o -> ((JsonObject) o).getString("name")).toList());
        assertEquals("node-1", listed.json().getString("node"));
        assertTrue(listed.json().getValue("key") instanceof JsonObject, listed.body());
        Answer one = call("GET", "/admin/secrets/login", Optional.of(token(developer, "cli")), null);
        assertEquals(200, one.status(), one.body());
        assertEquals("credentials", one.json().getString("kind"));
        refused(call("GET", "/admin/secrets/nobody", Optional.of(developer), null), 404, "no-secret");

        Answer overwritten = call("POST", "/admin/secrets/db", token, new JsonObject().put("kind", "value").put("value", "plain-value-2").encode());
        assertEquals(200, overwritten.status(), overwritten.body());
        assertEquals(204, call("DELETE", "/admin/secrets/db", session, null).status());
        assertEquals(List.of("login", "tls"), call("GET", "/admin/secrets", session, null).json().getJsonArray("secrets").stream()
                .map(o -> ((JsonObject) o).getString("name")).toList());

        List<JsonObject> audit = secretDb.audit();
        assertEquals(List.of("secret.create", "secret.create", "secret.create", "secret.overwrite", "secret.delete"),
                audit.stream().map(a -> a.getString("action")).toList());
        assertEquals(List.of("secret/db", "secret/login", "secret/tls", "secret/db", "secret/db"), audit.stream().map(a -> a.getString("subject")).toList());
        assertEquals(List.of("session", "token", "session", "token", "session"), audit.stream().map(a -> a.getString("via")).toList(),
                "the actor's way, from the permission check");
        assertTrue(audit.stream().allMatch(a -> a.getString("actor").equals("keeper")));
        assertNoValueCaptured();
    }

    @Test
    void eachCodeIsGivenWhereItShouldBe() {
        Optional<String> session = Optional.of(admin);
        written.addAll(List.of("plain-value-3", "the-password-2", "rejected-value"));
        assertEquals(201, create(session, value("db", "plain-value-3")).status());

        refused(create(session, value("db", "plain-value-3")), 409, "exists");
        refused(call("POST", "/admin/secrets/nobody", session, new JsonObject().put("kind", "value").put("value", "plain-value-3").encode()), 404, "no-secret");
        refused(call("DELETE", "/admin/secrets/nobody", session, null), 404, "no-secret");
        refused(call("POST", "/admin/secrets/db", session, new JsonObject().put("kind", "credentials").put("user", "u").put("password", "the-password-2")
                .encode()), 409, "kind-differs");
        refused(create(session, value("rail8.git", "rejected-value")), 409, "kind-in-use");   // the platform takes it as credentials
        refused(create(session, value("rail8.other", "rejected-value")), 409, "reserved");
        Answer invalid = create(session, value("empty", ""));
        refused(invalid, 422, "invalid");
        assertEquals("value", invalid.json().getJsonObject("values").getString("field"), "it names the field");

        assertEquals(201, create(session, new JsonObject().put("name", "rail8.git").put("kind", "credentials").put("user", "git")
                .put("password", "the-password-2")).status());
        Answer inUse = call("DELETE", "/admin/secrets/rail8.git", session, null);
        refused(inUse, 409, "in-use");
        assertEquals("platform", inUse.json().getJsonObject("values").getJsonArray("usedBy").getJsonObject(0).getString("kind"), inUse.body());
        refused(call("DELETE", "/admin/secrets/rail8.git?confirm=rail8.gi", session, null), 409, "in-use");
        assertEquals(204, call("DELETE", "/admin/secrets/rail8.git?confirm=rail8.git", session, null).status(), "confirmed");

        for (int i = 0; i < 999; i++) MemorySecretStores.createValue(secrets, "s" + i, "v");
        refused(create(session, value("one-more", "rejected-value")), 409, "store-full");
        for (int i = 0; i < 999; i++) MemorySecretStores.delete(secrets, "s" + i);

        secretDb.auditFails(true);
        refused(create(session, value("unaudited", "rejected-value")), 503, "unaudited");
        secretDb.auditFails(false);
        refused(call("GET", "/admin/secrets/unaudited", session, null), 404, "no-secret");

        assertNoValueCaptured();
    }

    /** A write is 409 {@code keys-differ} while a live node does not hold the key this node seals under. */
    @Test
    void aWriteWhileAnotherNodeHoldsAnotherKeyIs409() {
        SecretStore other = secretDb.start("node-2", Map.of(StoreKey.VARIABLE, Base64.getEncoder().encodeToString(new byte[32])), rt.clock(), rt.timer());
        try {
            written.add("rejected-value");
            refused(create(Optional.of(admin), value("rolled", "rejected-value")), 409, "keys-differ");
        } finally {
            other.close();
        }
        assertNoValueCaptured();
    }

    @Test
    void aBodyAbove64KiBIs413AndOneThatIsNotJsonIs400() {
        Optional<String> session = Optional.of(admin);
        Answer big = call("POST", "/admin/secrets", session, value("big", "x".repeat(AdminServer.SECRET_BODY_MAX)).encode());
        assertEquals(413, big.status(), big.body());
        Answer notJson = call("POST", "/admin/secrets", session, "{not json");
        assertEquals(400, notJson.status(), notJson.body());
        assertEquals(400, call("POST", "/admin/secrets/db", session, "[]").status());
        assertEquals(403, call("POST", "/admin/secrets", Optional.of(developer), value("db", "x").encode()).status(), "the door before the body");
        assertEquals(403, call("DELETE", "/admin/secrets/db", Optional.of(developer), null).status());
    }

    /** No captured text and no audit row holds a value written: as text, URL-encoded, JSON-escaped, or in base64. */
    void assertNoValueCaptured() {
        List<String> texts = new ArrayList<>(captured);
        secretDb.audit().forEach(a -> texts.add(a.encode()));
        texts.addAll(secretDb.warnings());
        for (String value : written) {
            List<String> forms = new ArrayList<>(List.of(value, URLEncoder.encode(value, StandardCharsets.UTF_8),
                    new JsonArray().add(value).encode().substring(2).replaceAll("\"]$", ""),
                    Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
            for (String text : texts) {
                for (String form : forms) {
                    if (text.contains(form)) fail("a captured text holds a value written: " + text.replace(form, "<the value>"));
                }
            }
        }
        assertFalse(texts.isEmpty(), "fixture: something was captured");
    }
}
