package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Headers;
import dev.rail8.api.Keys;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.access.Action;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routes of the users and of the caller's own, and the audit the door and the body route write first: the login and its
 * refusal, {@code /admin/me} for each kind of caller, the password change, the logout, an administrator's acts on users, a use
 * of the emergency key, a view of a body, and the refusals of a module's service as problems.
 */
class AdminServerUsersTest extends AdminAccessFixture {

    static final String SESSION_FORM = "rail8s_[0-9a-f]{16}_[A-Za-z0-9_-]{43}";

    Answer get(String path, String bearer) {
        return send(key, "GET", path, Optional.of(bearer), null);
    }

    Answer post(String path, String bearer, JsonObject body) {
        return send(key, "POST", path, Optional.of(bearer), body == null ? null : body.encode());
    }

    static void assertRefused(int status, String code, Answer answer) {
        assertEquals(status, answer.status(), answer.body());
        assertEquals(code, answer.code(), answer.body());
    }

    /** The audit's rows of {@code action}. */
    List<JsonObject> audit(String action) {
        return store.audit().stream().filter(row -> row.getString("action").equals(action)).toList();
    }

    @Test
    void aLoginAnswersItsSessionAndWhoItIsAndOneRefusalForEveryReason() {
        String first = person(root(), "alice", "operator");

        Answer login = send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", "Alice").put("password", first).encode());

        assertEquals(201, login.status(), login.body());
        JsonObject answer = login.json();
        assertTrue(answer.getString("session").matches(SESSION_FORM), answer.encode());
        assertEquals(rt.clock().instant().plus(Duration.ofHours(12)).toString(), answer.getString("expiresAt"));
        assertEquals(7200L, answer.getLong("idleSeconds"));
        JsonObject refused = (JsonObject) answer.getJsonObject("me").remove("refused");
        assertEquals(Action.values().length - 3, refused.size(), refused.encode());
        refused.forEach(entry -> assertEquals("password-change-required", ((JsonObject) entry.getValue()).getString("code"), entry.getKey()));
        assertEquals(new JsonObject().put("code", "password-change-required").put("needs", new JsonArray().add("operator")), refused.getJsonObject("message.read"));
        assertEquals(new JsonObject().put("name", "alice").put("kind", "person").put("roles", new JsonArray().add("operator"))
                .put("actions", new JsonArray().add("self.read").add("self.password").add("self.logout")).put("via", "session")
                .put("firstPassword", true).put("expiresAt", answer.getString("expiresAt")).put("emergencyKey", false), answer.getJsonObject("me"));

        Answer wrong = send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", "alice").put("password", "not hers at all").encode());
        Answer unknown = send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", "nobody").put("password", first).encode());
        assertRefused(401, "login-refused", wrong);
        assertRefused(401, "login-refused", unknown);
        assertEquals(wrong.json(), unknown.json(), "one answer for every reason");
        assertEquals(Optional.of("Bearer"), wrong.headers().firstValue("www-authenticate"));
        Answer shape = send(key, "POST", "/admin/sessions", Optional.empty(), "{\"name\":\"alice\"}");
        assertEquals(400, shape.status(), shape.body());
    }

    /** {@code device}: none asks for none; {@code ""} asks and is answered a new device; a device is presented; a non-string is 400. */
    @Test
    void aLoginWithADeviceAsksForOneAndPresentsIt() {
        String first = person(root(), "alice", "operator");
        assertEquals(204, post("/admin/me/password", login("alice", first), new JsonObject().put("current", first).put("next", PASSWORD)).status());

        Answer none = send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", "alice").put("password", PASSWORD).encode());
        assertEquals(201, none.status(), none.body());
        assertNull(none.json().getValue("device"), "a login that does not ask gets none");
        Answer asks = send(key, "POST", "/admin/sessions", Optional.empty(),
                new JsonObject().put("name", "alice").put("password", PASSWORD).put("device", "").encode());
        assertEquals(201, asks.status(), asks.body());
        String device = asks.json().getString("device");
        assertTrue(device.matches("rail8d_[0-9a-f]{16}_[A-Za-z0-9_-]{43}"), asks.body());
        Answer presents = send(key, "POST", "/admin/sessions", Optional.empty(),
                new JsonObject().put("name", "alice").put("password", PASSWORD).put("device", device).encode());
        assertEquals(201, presents.status(), presents.body());
        assertNotEquals(device, presents.json().getString("device"), "a new device in place of the one presented");
        assertEquals(device.substring(7, 23), audit("login").getLast().getJsonObject("detail").getString("device"));

        Answer number = send(key, "POST", "/admin/sessions", Optional.empty(),
                new JsonObject().put("name", "alice").put("password", PASSWORD).put("device", 5).encode());
        assertEquals(400, number.status(), number.body());
    }

    @Test
    void meNamesEveryKindOfCaller() {
        String root = root();
        JsonObject admin = get("/admin/me", root).json();
        assertEquals("root", admin.getString("name"));
        assertEquals("session", admin.getString("via"));
        assertEquals(false, admin.getBoolean("firstPassword"));
        assertEquals(true, admin.getBoolean("emergencyKey"), "an administrator is told the node holds the key");
        assertTrue(admin.getJsonArray("actions").contains("user.create"), admin.encode());

        JsonObject theKey = get("/admin/me", KEY).json();
        JsonObject refused = (JsonObject) theKey.remove("refused");
        assertEquals(new JsonObject().put("code", "not-allowed").put("needs", new JsonArray().add("operator")), refused.getJsonObject("message.read"));
        assertEquals(Action.values().length - 9, refused.size(), refused.encode());
        assertEquals(new JsonObject().put("name", "emergency-key").put("kind", null).put("roles", new JsonArray())
                .put("actions", new JsonArray(List.of("user.read", "user.create", "administrators.change", "user.block", "user.password-reset",
                        "session.end", "token.read", "token.revoke", "self.read")))
                .put("via", "emergency-key").put("firstPassword", false).put("expiresAt", null).put("emergencyKey", false), theKey);

        JsonObject token = send(shared, "GET", "/admin/me", Optional.of(SHARED), null, Map.of("X-Rail8-Actor", "ci")).json();
        assertEquals("ci", token.getString("name"));
        assertEquals("shared-token", token.getString("via"));
        JsonObject local = send(open, "GET", "/admin/me", Optional.empty(), null).json();
        assertEquals("unknown", local.getString("name"), "no X-Rail8-Actor");
        assertEquals("local", local.getString("via"));
        assertTrue(local.getJsonArray("actions").contains("message.read") && !local.getJsonArray("actions").contains("user.read"), local.encode());
    }

    @Test
    void aPasswordChangeLiftsTheFirstPasswordAndEndsTheOtherSessions() {
        String first = person(root(), "alice", "operator");
        String other = login("alice", first);
        String session = login("alice", first);
        assertRefused(403, "password-change-required", get("/admin/dlq", session));

        assertRefused(403, "wrong-password", post("/admin/me/password", session, new JsonObject().put("current", "not hers at all").put("next", PASSWORD)));
        assertEquals(400, post("/admin/me/password", session, new JsonObject().put("current", first).put("next", "short")).status(), "below 12 characters");
        Answer changed = post("/admin/me/password", session, new JsonObject().put("current", first).put("next", PASSWORD));

        assertEquals(204, changed.status(), changed.body());
        assertEquals(200, get("/admin/dlq", session).status(), "the same session goes on");
        assertRefused(401, "unknown-credential", get("/admin/me", other));   // an ended session is deleted
        assertEquals(201, send(key, "POST", "/admin/sessions", Optional.empty(), new JsonObject().put("name", "alice").put("password", PASSWORD).encode()).status());
    }

    @Test
    void aLogoutEndsItsSession() {
        String root = root();

        assertEquals(204, post("/admin/me/logout", root, null).status());

        Answer after = get("/admin/me", root);
        assertRefused(401, "unknown-credential", after);   // an ended session is deleted
        assertEquals(Optional.of("Bearer"), after.headers().firstValue("www-authenticate"));
        assertRefused(403, "not-allowed", post("/admin/me/logout", KEY, null));
    }

    @Test
    void anAdministratorMakesAndChangesUsers() {
        String root = root();
        Answer made = post("/admin/users", root, personBody("alice", "operator"));
        assertEquals(201, made.status(), made.body());
        assertEquals("alice", made.json().getString("name"));
        String alice = changed("alice", made.json().getString("firstPassword"));
        assertEquals(409, post("/admin/users", root, personBody("alice")).status(), "a name taken");
        Answer technical = post("/admin/users", root, new JsonObject().put("name", "ci-bot").put("kind", "technical").put("roles", new JsonArray()));
        assertEquals(new JsonObject().put("name", "ci-bot"), technical.json(), "no password for a technical account");

        JsonObject listed = get("/admin/users", root).json();
        assertEquals(List.of("alice", "ci-bot", "root"), listed.getJsonArray("items").stream().map(u -> ((JsonObject) u).getString("name")).sorted().toList());
        JsonObject user = get("/admin/users/alice", root).json();
        JsonObject grant = user.getJsonArray("roles").getJsonObject(0);
        assertEquals("operator", grant.getString("role"));
        assertEquals("root", grant.getString("grantedBy"));
        assertEquals("session", grant.getString("grantedVia"));
        assertEquals(404, get("/admin/users/nobody", root).status());

        Answer roles = post("/admin/users/alice/roles", root, new JsonObject().put("add", new JsonArray().add("developer").add("operator")).put("remove", new JsonArray()));
        assertEquals(new JsonObject().put("name", "alice").put("roles", new JsonArray().add("developer").add("operator"))
                .put("added", new JsonArray().add("developer")).put("removed", new JsonArray()), roles.json(), "a role already held is no change");
        assertEquals(200, get("/admin/packages", alice).status(), "the new role holds at the next request");
        assertEquals(400, post("/admin/users/alice/roles", root, new JsonObject()).status(), "no role named");

        assertEquals(new JsonObject().put("name", "alice"), post("/admin/users/alice/block", root, null).json());
        assertRefused(401, "unknown-credential", get("/admin/me", alice));
        assertRefused(401, "login-refused", send(key, "POST", "/admin/sessions", Optional.empty(),
                new JsonObject().put("name", "alice").put("password", PASSWORD).encode()));
        assertEquals(200, post("/admin/users/alice/unblock", root, null).status());
        String again = login("alice", PASSWORD);

        Answer reset = post("/admin/users/alice/password-reset", root, null);
        assertEquals(200, reset.status(), reset.body());
        assertRefused(401, "unknown-credential", get("/admin/me", again));
        String fresh = login("alice", reset.json().getString("firstPassword"));
        assertEquals(true, get("/admin/me", fresh).json().getBoolean("firstPassword"));

        assertEquals(new JsonObject().put("name", "alice"), post("/admin/users/alice/sessions/end", root, null).json());
        assertRefused(401, "unknown-credential", get("/admin/me", fresh));
        assertEquals(409, post("/admin/users/root/block", root, null).status(), "nobody blocks themselves");
    }

    @Test
    void aUseOfTheKeyIsInTheAuditBeforeAnythingRuns() {
        assertRefused(403, "not-allowed", get("/admin/dlq", KEY));
        assertEquals(404, get("/admin/nothing", KEY).status());
        assertEquals(405, send(key, "DELETE", "/admin/users", Optional.of(KEY), null).status());
        assertEquals(201, post("/admin/users", KEY, personBody("root", "administrator")).status());

        List<JsonObject> uses = audit("emergency-key.use");
        assertEquals(List.of(use("GET", "/admin/dlq", false, "message.read"), use("GET", "/admin/nothing", false, null),
                use("DELETE", "/admin/users", false, null), use("POST", "/admin/users", true, "user.create")),
                uses.stream().map(row -> row.getJsonObject("detail")).toList());
        assertEquals("emergency-key", uses.getFirst().getString("actor"));
        assertEquals("emergency-key", uses.getFirst().getString("via"));

        auditFails.set(true);
        Answer unwritten = post("/admin/users", KEY, personBody("alice"));
        assertEquals(503, unwritten.status(), unwritten.body());
        auditFails.set(false);
        assertEquals(404, get("/admin/users/alice", KEY).status(), "nothing ran");

        assertEquals(201, send(shared, "POST", "/admin/users", Optional.of(SHARED), personBody("bob").encode(), Map.of("X-Rail8-Actor", "ci")).status());
        send(shared, "GET", "/admin/me", Optional.of(SHARED), null);
        send(shared, "GET", "/admin/dlq", Optional.of(SHARED), null);
        JsonObject last = audit("emergency-key.use").getLast();
        assertEquals("shared-token", last.getString("via"), "the shared token writes the key's actions as the key's");
        assertEquals("ci", last.getString("actor"));
        assertEquals(6, audit("emergency-key.use").size(), "the five of the key and the shared token's create, not its self.read nor message.read");
    }

    static JsonObject use(String method, String path, boolean allowed, String action) {
        return new JsonObject().put("method", method).put("path", path).put("remote", "127.0.0.1").put("allowed", allowed).put("action", action);
    }

    @Test
    void aViewOfABodyIsInTheAuditBeforeAnyByte() {
        Body body = rt.blobs().write(BlobKey.body("m-1", 0), Buffer.buffer("<invoice/>"), 1 << 20).await();
        messages.record(AdminServerTest.snapshot("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), 1L, 1L, Headers.empty(), Keys.empty(), body, true))
                .await();
        String root = root();
        String bodies = changed("reader", person(root, "reader", "message-bodies"));
        String operator = changed("operator", person(root, "operator", "operator"));

        Answer refused = get("/admin/messages/m-1/steps/0/body", operator);
        assertRefused(403, "not-allowed", refused);
        assertEquals(new JsonArray().add("message-bodies"), refused.json().getJsonArray("needs"));
        Answer viewed = get("/admin/messages/m-1/steps/0/body", bodies);

        assertEquals(200, viewed.status(), viewed.body());
        assertEquals("<invoice/>", viewed.body());
        assertEquals(List.of(1L), viewsAtOpen, "the view was in the audit when its stream was opened");
        JsonObject view = audit("message.body.view").getFirst();
        assertEquals("reader", view.getString("actor"));
        assertEquals("session", view.getString("via"));
        assertEquals("message/m-1", view.getString("subject"));
        assertEquals(bodies.substring(7, 23), view.getString("credential"), "the session's id, never its secret");
        assertNotEquals(null, view.getValue("user"));
        assertEquals(0, view.getJsonObject("detail").getInteger("step"));

        auditFails.set(true);
        Answer unwritten = get("/admin/messages/m-1/steps/0/body", bodies);
        assertEquals(503, unwritten.status(), unwritten.body());
        assertEquals(1, viewsAtOpen.size(), "nothing opened");
        auditFails.set(false);

        assertEquals(200, send(shared, "GET", "/admin/messages/m-1/steps/0/body", Optional.of(SHARED), null).status());
        assertEquals(200, send(open, "GET", "/admin/messages/m-1/steps/0/body", Optional.empty(), null).status());
        assertEquals(List.of("session", "shared-token", "local"), audit("message.body.view").stream().map(row -> row.getString("via")).toList(),
                "during the bridge the shared token's and the open API's views are written too");
    }

    @Test
    void aModulesServiceIsRefusedWithProblems() {
        String root = root();
        String operator = changed("operator", person(root, "operator", "operator"));
        String developer = changed("developer", person(root, "developer", "developer"));

        Answer none = send(key, "GET", "/admin/modules/demo/ping", Optional.empty(), null);
        assertEquals(401, none.status());
        assertEquals(Optional.of("application/problem+json"), none.headers().firstValue("content-type"));
        assertEquals("urn:rail8:admin:problem:unauthenticated", none.json().getString("type"));
        assertEquals("no-credential", none.code());
        assertEquals(Optional.of("Bearer"), none.headers().firstValue("www-authenticate"));
        Answer forbidden = get("/admin/modules/demo/ping", operator);
        assertEquals(403, forbidden.status());
        assertEquals("urn:rail8:admin:problem:forbidden", forbidden.json().getString("type"));
        assertEquals("not-allowed", forbidden.code());
        assertEquals(new JsonArray().add("developer"), forbidden.json().getJsonArray("needs"));
        assertEquals(new JsonObject().put("pong", true), get("/admin/modules/demo/ping", developer).json());
        assertNull(send(key, "GET", "/admin/dlq", Optional.empty(), null).json().getString("type"), "the node's own routes answer {error, code}");
    }
}
