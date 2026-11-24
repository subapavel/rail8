package dev.rail8.runtime.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /admin/audit}, the whole audit as an administrator reads it: newest first, narrowed by user, action and time, a
 * page at a time of 50 by default and at most 500, through a session or an administrator's token, and refused to an operator.
 */
class AdminServerAuditTest extends AdminAccessFixture {

    Answer get(String path, String bearer) {
        return send(key, "GET", path, Optional.of(bearer), null);
    }

    JsonObject read(String path, String bearer) {
        Answer a = get(path, bearer);
        assertEquals(200, a.status(), path + ": " + a.body());
        return a.json();
    }

    static List<String> field(JsonObject page, String name) {
        JsonArray items = page.getJsonArray("items");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) values.add(String.valueOf(items.getJsonObject(i).getValue(name)));
        return values;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void anAdministratorReadsTheWholeAuditNarrowedByUserActionAndTime() {
        String root = root();
        rt.clock().advance(Duration.ofMinutes(1));
        Instant annaMade = rt.clock().instant();
        String anna = changed("anna", person(root, "anna", "operator"));
        rt.clock().advance(Duration.ofMinutes(1));
        Instant dirkMade = rt.clock().instant();
        person(root, "dirk", "operator");
        String annaToken = token(anna, "laptop");

        JsonObject all = read("/admin/audit", root);
        assertEquals(Set.of("items", "next"), all.fieldNames(), "{items, next|null}");
        JsonObject newest = all.getJsonArray("items").getJsonObject(0);
        assertEquals(Set.of("id", "at", "action", "subject", "actor", "via", "credential", "detail"), newest.fieldNames());
        assertEquals(List.of("token.issue", "user/anna", "anna", "session", anna.substring(7, 23)), List.of(newest.getString("action"),
                newest.getString("subject"), newest.getString("actor"), newest.getString("via"), newest.getString("credential")),
                "the newest row: anna's token, issued in her session");
        assertNull(all.getValue("next"), "everything fits one page of 50");

        assertEquals(List.of("user/dirk", "user/anna", "user/root"), field(read("/admin/audit?action=user.create", root), "subject"), "newest first");
        assertEquals(List.of("user/dirk", "user/anna"), field(read("/admin/audit?action=user.create&user=root", root), "subject"),
                "the users root made; root itself was made by the emergency key");
        List<String> annas = field(read("/admin/audit?user=anna", root), "actor");
        assertTrue(!annas.isEmpty() && annas.stream().allMatch("anna"::equals), "only what anna did: " + annas);
        assertEquals(List.of(), field(read("/admin/audit?user=nobody", root), "id"), "a name no user has: no rows");
        assertEquals(List.of("user/dirk", "user/anna"), field(read("/admin/audit?action=user.create&from=" + enc(annaMade.toString()), root), "subject"),
                "from included");
        assertEquals(List.of("user/anna", "user/root"), field(read("/admin/audit?action=user.create&to=" + enc(dirkMade.toString()), root), "subject"),
                "to left out");
        assertEquals(List.of("user/anna"), field(read("/admin/audit?action=user.create&from=" + enc(annaMade.toString()) + "&to="
                + enc(dirkMade.toString()), root), "subject"));

        List<String> walked = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            JsonObject page = read("/admin/audit?limit=2" + (after == null ? "" : "&after=" + enc(after)), root);
            assertTrue(page.getJsonArray("items").size() <= 2, page.encode());
            walked.addAll(field(page, "id"));
            after = page.getString("next");
            pages++;
        } while (after != null && pages < 50);
        assertEquals(field(all, "id"), walked, "the pages, one after the other, are the whole list");
        assertTrue(pages > 2, "several pages: " + pages);

        List<String> byToken = field(read("/admin/audit", token(root, "cli")), "id");
        assertEquals(field(all, "id"), byToken.subList(1, byToken.size()), "an administrator's token reads it too; its own issue is the newest row now");
        assertEquals(403, get("/admin/audit", anna).status(), "an operator may not");
        assertEquals(403, get("/admin/audit", annaToken).status(), "nor an operator's token");
        for (String wrong : List.of("limit=0", "limit=501", "limit=many", "from=yesterday", "to=" + enc(annaMade.toString()) + "&from="
                + enc(annaMade.toString()), "after=" + enc("!!!"))) {
            assertEquals(400, get("/admin/audit?" + wrong, root).status(), wrong);
        }
    }

    @Test
    void aPageHoldsFiftyRowsByDefaultAndFiveHundredAtMost() {
        String root = root();
        for (int i = 0; i < 520; i++) assertEquals(200, send(key, "GET", "/admin/me", Optional.of(KEY), null).status());
        JsonObject page = read("/admin/audit?action=emergency-key.use", root);
        assertEquals(50, page.getJsonArray("items").size());
        assertTrue(page.getString("next") != null, "more to come");
        JsonObject most = read("/admin/audit?action=emergency-key.use&limit=500", root);
        assertEquals(500, most.getJsonArray("items").size());
        assertEquals(field(page, "id"), field(most, "id").subList(0, 50), "the first 50 of the same list");
    }
}
