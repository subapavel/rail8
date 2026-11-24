package dev.rail8.runtime.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routes of the tokens, on three nodes of one store: a person's own token issued with the password asked again, a technical
 * account's issued by an administrator, the lists with the ceiling, a revoke that every node refuses at once, the ceiling
 * lowered and raised, and {@code /admin/me} and {@code /admin/users/{name}} of a token.
 */
class AdminServerTokensTest extends AdminAccessFixture {

    static final String TOKEN_FORM = "rail8t_[0-9a-f]{16}_[A-Za-z0-9_-]{43}";

    static Answer get(int port, String path, String bearer) {
        return send(port, "GET", path, Optional.of(bearer), null);
    }

    static Answer post(int port, String path, String bearer, JsonObject body) {
        return send(port, "POST", path, Optional.of(bearer), body == null ? null : body.encode());
    }

    static void assertRefused(int status, String code, Answer answer) {
        assertEquals(status, answer.status(), answer.body());
        assertEquals(code, answer.code(), answer.body());
    }

    static void assertStatus(int status, Answer answer) {
        assertEquals(status, answer.status(), answer.body());
    }

    List<JsonObject> audit(String action) {
        return store.audit().stream().filter(row -> row.getString("action").equals(action)).toList();
    }

    static List<Object> ids(Answer listed) {
        return listed.json().getJsonArray("tokens").stream().map(t -> ((JsonObject) t).getValue("id")).toList();
    }

    Instant now() {
        return rt.clock().instant();
    }

    @Test
    void aPersonIssuesListsAndRevokesTheirOwnTokens() {
        String session = changed("anna", person(root(), "anna", "operator"));
        Answer issued = post(key, "/admin/me/tokens", session, new JsonObject().put("name", "laptop").put("days", 30).put("password", PASSWORD));
        assertStatus(201, issued);
        String token = issued.json().getString("token");
        String id = issued.json().getString("id");
        assertTrue(token.matches(TOKEN_FORM), token);
        String expiresAt = now().plus(Duration.ofDays(30)).toString();
        assertEquals(new JsonObject().put("id", token.substring(7, 23)).put("name", "laptop").put("expiresAt", expiresAt).put("token", token),
                issued.json());

        JsonObject me = get(shared, "/admin/me", token).json();
        assertEquals(List.of("anna", "token", expiresAt, false), List.of(me.getString("name"), me.getString("via"), me.getString("expiresAt"),
                me.getBoolean("firstPassword")), "on any node: " + me.encode());
        JsonObject listed = new JsonObject().put("id", id).put("user", "anna").put("name", "laptop").put("createdAt", now().toString())
                .put("createdBy", "anna").put("expiresAt", expiresAt).put("lastUsedAt", now().toString());
        assertEquals(new JsonObject().put("tokens", new JsonArray().add(listed)).put("maxDays", 365).put("defaultDays", 90),
                get(open, "/admin/me/tokens", token).json());

        assertRefused(403, "session-required", post(key, "/admin/me/tokens", token,
                new JsonObject().put("name", "phone").put("password", PASSWORD)));
        assertRefused(403, "wrong-password", post(key, "/admin/me/tokens", session,
                new JsonObject().put("name", "phone").put("password", "not my password")));
        assertStatus(200, get(key, "/admin/me", session));
        assertStatus(400, post(key, "/admin/me/tokens", session, new JsonObject().put("name", "phone").put("days", 1.5).put("password", PASSWORD)));
        assertStatus(400, post(key, "/admin/me/tokens", session, new JsonObject().put("name", "phone").put("days", "30").put("password", PASSWORD)));
        assertStatus(400, post(key, "/admin/me/tokens", session, new JsonObject().put("name", "phone")));
        assertStatus(409, post(key, "/admin/me/tokens", session, new JsonObject().put("name", "laptop").put("password", PASSWORD)));

        assertStatus(204, post(shared, "/admin/me/tokens/" + id + "/revoke", token, null));
        for (int port : List.of(key, shared, open)) assertRefused(401, "token-revoked", get(port, "/admin/me", token));
        assertStatus(404, post(key, "/admin/me/tokens/0123456789abcdef/revoke", session, null));
        assertEquals(List.of(), ids(get(key, "/admin/me/tokens", session)));
        assertEquals(List.of(1, 1), List.of(audit("token.issue").size(), audit("token.revoke").size()));
    }

    @Test
    void anAdministratorIssuesATechnicalAccountsTokensAndListsEveryToken() {
        String root = root();
        String ci = technical(root, "ci-deploy", "developer");
        Answer nightly = post(key, "/admin/users/ci-deploy/tokens", root, new JsonObject().put("name", "nightly").put("days", 7));
        assertStatus(201, nightly);
        String ciId = ci.substring(7, 23);
        String nightlyId = nightly.json().getString("id");
        assertEquals(now().plus(Duration.ofDays(7)).toString(), nightly.json().getString("expiresAt"));
        assertEquals("ci-deploy", get(open, "/admin/me", ci).json().getString("name"));

        assertStatus(409, post(key, "/admin/users/root/tokens", root, new JsonObject().put("name", "laptop")));
        assertStatus(404, post(key, "/admin/users/nobody/tokens", root, new JsonObject().put("name", "pipeline")));
        rt.clock().advance(Duration.ofSeconds(1));
        String admins = token(root, "cli");
        assertRefused(403, "session-required", post(key, "/admin/users/ci-deploy/tokens", admins, new JsonObject().put("name", "other")));

        assertEquals(List.of(nightlyId), ids(get(key, "/admin/tokens?expiringWithin=P14D", admins)));
        Answer all = get(shared, "/admin/tokens", root);
        assertEquals(List.of(nightlyId, ciId, admins.substring(7, 23)), ids(all), "soonest expiry first");
        assertEquals(List.of(365, 90), List.of(all.json().getInteger("maxDays"), all.json().getInteger("defaultDays")));
        assertStatus(400, get(key, "/admin/tokens?expiringWithin=fortnight", root));
        assertStatus(400, get(key, "/admin/tokens?expiringWithin=P366D", root));
        assertEquals(List.of(nightlyId, ciId), get(key, "/admin/users/ci-deploy", root).json().getJsonArray("tokens").stream()
                .map(t -> ((JsonObject) t).getValue("id")).toList());

        assertStatus(200, get(key, "/admin/tokens", KEY));
        assertStatus(204, post(key, "/admin/tokens/" + ciId + "/revoke", KEY, null));
        assertRefused(401, "token-revoked", get(shared, "/admin/me", ci));
        assertStatus(404, post(key, "/admin/tokens/not-an-id/revoke", root, null));
        assertEquals("emergency-key", audit("token.revoke").getFirst().getString("actor"));
    }

    @Test
    void theCeilingIsLoweredAndRaisedByAnAdministratorLoggedIn() {
        String ci = technical(root(), "ci-deploy");
        Instant issuedAt = now();
        rt.clock().advance(Duration.ofDays(10));
        String root = login("root", PASSWORD);
        assertEquals(new JsonObject().put("maxDays", 30).put("defaultDays", 30),
                post(key, "/admin/tokens/ceiling", root, new JsonObject().put("maxDays", 30)).json());
        assertEquals(issuedAt.plus(Duration.ofDays(30)).toString(), get(shared, "/admin/me", ci).json().getString("expiresAt"),
                "its issue plus the ceiling");
        assertStatus(400, post(key, "/admin/users/ci-deploy/tokens", root, new JsonObject().put("name", "long").put("days", 31)));
        rt.clock().advance(Duration.ofDays(20));
        assertRefused(401, "token-expired", get(open, "/admin/me", ci));
        root = login("root", PASSWORD);

        assertEquals(new JsonObject().put("maxDays", 365).put("defaultDays", 90),
                post(key, "/admin/tokens/ceiling", root, new JsonObject().put("maxDays", 365)).json());
        assertRefused(401, "token-expired", get(open, "/admin/me", ci));
        for (Object wrong : List.of(0, 366, "30", 30.5)) {
            assertStatus(400, post(key, "/admin/tokens/ceiling", root, new JsonObject().put("maxDays", wrong)));
        }
        assertRefused(403, "session-required", post(key, "/admin/tokens/ceiling", token(root, "cli"), new JsonObject().put("maxDays", 30)));
        assertEquals(List.of(new JsonObject().put("from", 365).put("to", 30).put("shortened", 1).put("remote", "127.0.0.1"),
                new JsonObject().put("from", 30).put("to", 365).put("shortened", 0).put("remote", "127.0.0.1")),
                audit("token.ceiling").stream().map(r -> r.getJsonObject("detail")).toList());
    }
}
