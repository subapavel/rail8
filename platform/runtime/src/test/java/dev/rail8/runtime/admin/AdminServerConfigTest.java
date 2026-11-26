package dev.rail8.runtime.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routes of {@code /admin/config} over a store in memory and the environment of node {@code node-1}: a connection saved,
 * consented, activated, stopped and started through them; an activation by an account that holds both roles; the action at the
 * door before the body; a key the parser refuses, answered before any call of the store and with no audit row; the list's
 * narrowing; the switch of mocks; and no answer that holds a secret's value.
 */
class AdminServerConfigTest extends AdminAccessFixture {

    static final String TOKEN_VALUE = "the-token-value";
    static final String PIN_VALUE = "the-pin-value";
    static final String API = "/admin/config/connections/acme/api";
    /** The values of a partner reached over HTTPS with two secrets, which its server's consent covers. */
    static final JsonObject PARTNER = new JsonObject().put("url", "https://partner.example/api").put("token", "secret://partner.token")
            .put("pin", "secret://partner.pin").put("inbox", "/in");

    /** Every answer a test saw, searched for the secrets' values. */
    final List<String> seen = new ArrayList<>();
    String connections;
    String secretAdmin;
    String both;
    String developer;

    @BeforeEach
    void people() {
        String root = root();
        connections = changed("connections", person(root, "connections", "connection-administrator"));
        secretAdmin = changed("secrets", person(root, "secrets", "secret-administrator"));
        both = changed("both", person(root, "both", "connection-administrator", "secret-administrator"));
        developer = changed("dev", person(root, "dev", "developer"));
        for (Map.Entry<String, String> s : Map.of("partner.token", TOKEN_VALUE, "partner.pin", PIN_VALUE).entrySet()) {
            Answer made = call(secretAdmin, "POST", "/admin/secrets", new JsonObject().put("name", s.getKey()).put("kind", "value").put("value", s.getValue()));
            assertEquals(201, made.status(), made.body());
        }
        configCalls.clear();
    }

    Answer call(String bearer, String method, String path, JsonObject body) {
        return raw(bearer, method, path, body == null ? null : body.encode());
    }

    Answer raw(String bearer, String method, String path, String body) {
        Answer answer = send(key, method, path, Optional.of(bearer), body);
        seen.add(answer.body());
        return answer;
    }

    /** {@code path} as {@code bearer}: 200, its JSON. */
    JsonObject ok(String bearer, String method, String path, JsonObject body) {
        Answer answer = call(bearer, method, path, body);
        assertEquals(200, answer.status(), method + " " + path + ": " + answer.body());
        return answer.json();
    }

    /** {@code path} as {@code bearer}: refused with {@code status} and {@code code}; its JSON. */
    JsonObject refused(int status, String code, String bearer, String method, String path, JsonObject body) {
        Answer answer = call(bearer, method, path, body);
        assertEquals(status, answer.status(), method + " " + path + ": " + answer.body());
        assertEquals(code, answer.code(), answer.body());
        return answer.json();
    }

    /** The etag of a key no row holds, which its 404 carries. */
    String emptyEtag(String path) {
        return refused(404, "not-found", connections, "GET", path, null).getJsonObject("values").getString("etag");
    }

    @Test
    void aConnectionIsSavedConsentedActivatedStoppedAndStartedThroughItsRoutes() {
        String etag = emptyEtag(API);
        JsonObject saved = ok(connections, "POST", API + "/-/save", new JsonObject().put("expect", etag).put("adapter", "web").put("values", PARTNER));
        JsonObject consent = saved.getJsonObject("draft").getJsonObject("consent");
        assertNull(consent.getValue("given"), "no consent yet: " + saved);
        JsonObject token = saved.getJsonArray("secrets").stream().map(JsonObject.class::cast).filter(s -> s.getString("name").equals("partner.token"))
                .findFirst().orElseThrow();
        assertEquals(true, token.getBoolean("set"), "the secret store's list says the secret is set: " + token);
        assertEquals("value", token.getString("kind"));
        assertEquals("secrets", token.getString("changedBy"));
        assertEquals(false, token.getBoolean("unreadable"));

        refused(409, "consent-needed", connections, "POST", API + "/-/activate", new JsonObject().put("expect", saved.getString("etag")));
        JsonArray waiting = ok(developer, "GET", "/admin/config/connections?needs=consent", null).getJsonArray("items");
        assertEquals(1, waiting.size(), waiting.encode());
        assertEquals("acme/api", waiting.getJsonObject(0).getString("key"));
        assertEquals(true, waiting.getJsonObject(0).getBoolean("needsConsent"));
        assertEquals("missing", waiting.getJsonObject(0).getString("state"));

        refused(403, "not-allowed", connections, "POST", API + "/-/consent", new JsonObject().put("expect", saved.getString("etag"))
                .put("digest", consent.getString("digest")));
        JsonObject consented = ok(secretAdmin, "POST", API + "/-/consent", new JsonObject().put("expect", saved.getString("etag"))
                .put("digest", consent.getString("digest")));
        assertEquals("secrets", consented.getJsonObject("draft").getJsonObject("consent").getJsonObject("given").getString("by"));

        JsonObject active = ok(connections, "POST", API + "/-/activate", new JsonObject().put("expect", consented.getString("etag")).put("note", "ticket 7"));
        assertEquals(1, active.getJsonObject("inForce").getInteger("version"));
        assertEquals("ok", active.getJsonObject("inForce").getString("binding"));
        assertEquals(new JsonArray().add(new JsonObject().put("node", "node-1").put("holds", "yes")), active.getJsonArray("nodes"),
                "the node that wrote holds the change when it answers");
        assertTrue(environment.current().connection("acme/api").isPresent(), "and its snapshot holds the connection");
        JsonObject env = ok(developer, "GET", "/admin/config", null);
        assertEquals(true, env.getJsonArray("nodes").getJsonObject(0).getBoolean("current"), env.encode());
        assertEquals(false, env.getBoolean("mocksAllowed"));
        assertEquals("set", ok(developer, "GET", "/admin/config/connections", null).getJsonArray("items").getJsonObject(0).getString("state"));

        JsonObject stopped = ok(connections, "POST", API + "/-/stop", new JsonObject().put("expect", active.getString("run")));
        assertEquals("connections", stopped.getJsonObject("stopped").getString("by"));
        assertEquals("connections", environment.current().connection("acme/api").orElseThrow().stopped().orElseThrow().by(),
                "the stop holds on this node when it is answered");
        refused(412, "changed", connections, "POST", API + "/-/stop", new JsonObject().put("expect", active.getString("run")));
        JsonObject started = ok(connections, "POST", API + "/-/start", new JsonObject().put("expect", stopped.getString("run")));
        assertNull(started.getValue("stopped"));
        assertTrue(environment.current().connection("acme/api").orElseThrow().stopped().isEmpty());

        JsonObject history = ok(developer, "GET", API + "/-/history?limit=2", null);
        assertEquals(2, history.getJsonArray("items").size(), history.encode());
        assertTrue(history.getString("next") != null, "a second page: " + history);
        refused(400, "bad-request", developer, "GET", API + "/-/history?after=x", null);
        refused(400, "bad-request", developer, "GET", API + "/-/history?limit=501", null);

        for (String text : seen) {
            assertFalse(text.contains(TOKEN_VALUE) || text.contains(PIN_VALUE), "no answer holds a secret's value: " + text);
        }
    }

    @Test
    void anAccountThatHoldsBothRolesConsentsByItsActivation() {
        String etag = emptyEtag(API);
        JsonObject saved = ok(both, "POST", API + "/-/save", new JsonObject().put("expect", etag).put("adapter", "web").put("values", PARTNER));
        JsonObject active = ok(both, "POST", API + "/-/activate", new JsonObject().put("expect", saved.getString("etag")));
        assertEquals(1, active.getJsonObject("inForce").getInteger("version"));
    }

    @Test
    void theActionIsCheckedBeforeTheBodyIsRead() {
        String big = "{\"expect\": \"" + "x".repeat(200 * 1024) + "\"}";
        for (String body : List.of(big, "not json")) {
            Answer answer = raw(developer, "POST", API + "/-/save", body);
            assertEquals(403, answer.status(), answer.body());
            assertEquals("not-allowed", answer.code());
        }
        Answer large = raw(connections, "POST", API + "/-/save", big);
        assertEquals(413, large.status(), large.body());
        assertEquals("too-large", large.code());
        assertEquals(AdminServer.CONFIG_BODY_MAX, large.json().getJsonObject("values").getInteger("max"));
        refused(400, "bad-request", connections, "POST", API + "/-/save", new JsonObject().put("expect", "e").put("adapter", "web"));
        assertEquals(List.of(), configCalls, "nothing refused at the door or for its body reached the store");
    }

    @Test
    void aKeyTheParserRefusesIs400NamingTheRuleBeforeAnyCallOfTheStoreAndWithNoAuditRow() {
        int audited = configs.audit().size();
        Map<String, String> keys = Map.of("connections/Acme/api", "has the segment 'Acme'", "connections/rail8/api", "starts with 'rail8'",
                "connections/acme/erp/rest/more", "has 4 segments", "systems/acme/erp/rest", "has 3 segments");
        for (Map.Entry<String, String> k : keys.entrySet()) {
            String base = "/admin/config/" + k.getKey();
            JsonObject save = k.getKey().startsWith("systems/") ? new JsonObject().put("expect", "e").put("instanceName", "ERP")
                    : new JsonObject().put("expect", "e").put("adapter", "web").put("values", new JsonObject());
            List<String[]> routes = new ArrayList<>(List.of(new String[] {"GET", base}, new String[] {"GET", base + "/-/history"},
                    new String[] {"POST", base + "/-/save"}, new String[] {"POST", base + "/-/discard"}, new String[] {"POST", base + "/-/activate"}));
            if (k.getKey().startsWith("connections/")) routes.add(new String[] {"POST", base + "/-/stop"});
            for (String[] route : routes) {
                JsonObject body = route[0].equals("GET") ? null : route[1].endsWith("/-/save") ? save : new JsonObject().put("expect", "e");
                JsonObject refused = refused(400, "bad-request", connections, route[0], route[1], body);
                assertTrue(refused.getString("error").contains(k.getValue()), "names the rule: " + refused);
            }
        }
        assertEquals(List.of(), configCalls, "no key the parser refuses reaches the store");
        assertEquals(audited, configs.audit().size(), "and none writes an audit row");
        refused(404, "not-found", developer, "GET", "/admin/config/systems/acme", null);
        refused(404, "not-found", developer, "GET", "/admin/config/connections/acme/erp/rest", null);
    }

    @Test
    void aListNarrowsToTheDraftsThatWaitForTheirActivationAndRefusesAnotherNeed() {
        ok(connections, "POST", "/admin/config/systems/acme/-/save", new JsonObject().put("expect", emptyEtag("/admin/config/systems/acme"))
                .put("instanceName", "ACME production"));
        JsonArray items = ok(developer, "GET", "/admin/config/systems?needs=activation", null).getJsonArray("items");
        assertEquals(new JsonObject().put("key", "acme").putNull("instanceName").put("state", "missing").put("version", 0).put("draft", true)
                .put("nodesBehind", new JsonArray()), items.getJsonObject(0));
        assertEquals(0, ok(developer, "GET", "/admin/config/connections?needs=activation", null).getJsonArray("items").size());
        refused(400, "bad-request", developer, "GET", "/admin/config/connections?needs=everything", null);
        refused(400, "bad-request", developer, "GET", "/admin/config/connections?limit=0", null);
    }

    @Test
    void theSwitchOfMocksIsWrittenAndAMockedDraftIsRefusedWhileMocksAreNot() {
        String etag = emptyEtag(API);
        JsonObject saved = ok(connections, "POST", API + "/-/save", new JsonObject().put("expect", etag).put("adapter", "web")
                .put("values", new JsonObject().put("url", "https://plain.example/api").put("inbox", "/in"))
                .put("mock", new JsonObject().put("reply", "echo")));
        refused(409, "mocks-refused", connections, "POST", API + "/-/activate", new JsonObject().put("expect", saved.getString("etag")));
        refused(403, "not-allowed", developer, "POST", "/admin/config/-/mocks", new JsonObject().put("expect", false).put("allowed", true));
        JsonObject env = ok(connections, "POST", "/admin/config/-/mocks", new JsonObject().put("expect", false).put("allowed", true));
        assertEquals(true, env.getBoolean("mocksAllowed"));
        assertTrue(environment.current().mocksAllowed(), "the switch holds on this node when it is answered");
        refused(412, "changed", connections, "POST", "/admin/config/-/mocks", new JsonObject().put("expect", false).put("allowed", true));
        refused(400, "bad-request", connections, "POST", "/admin/config/-/mocks", new JsonObject().put("expect", "no").put("allowed", true));
    }
}
