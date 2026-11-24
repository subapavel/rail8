package dev.rail8.runtime.admin;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminPgFixture.BOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit at a message ({@code GET /admin/messages/{id}/audit}) over Postgres, through {@link AdminPgFixture}: a parked message
 * that an operator retries and whose body they view, and one they cancel, each list their own rows newest first, with who acted
 * and how, a page at a time. The search by step names each message's qualified flow.
 */
class MessageAuditIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    @TempDir Path tmp;
    AdminPgFixture node;

    @BeforeAll
    static void up() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() throws Exception {
        node = new AdminPgFixture(vertx, pool, client, tmp);
    }

    @AfterEach
    void stop() throws Exception {
        node.close();
    }

    @Test
    void aMessagesRetryCancelAndBodyViewsAreListedNewestFirstWithWhoAndHow() throws Exception {
        String retried = node.parked();
        assertEquals(202, node.retry(retried).status(), "fixture: the operator retries it");
        assertEquals(1, node.worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: its attempt 2 runs and completes");
        node.messages.flush().await();
        assertEquals(200, node.send(HttpMethod.GET, "/admin/messages/" + retried + "/steps/0/body").status(), "fixture: the operator views its body");
        String cancelled = node.parked();
        AdminPgFixture.Answer cancel = node.send(HttpMethod.POST, "/admin/dlq/" + cancelled + "/cancel");
        assertEquals(200, cancel.status(), "fixture: the operator cancels it: " + cancel.body());

        JsonObject audit = get("/admin/messages/" + retried + "/audit");
        assertEquals(List.of("message.body.view", "message.retry"), field(audit, "action"), "newest first: " + audit);
        JsonObject view = audit.getJsonArray("items").getJsonObject(0);
        assertEquals(Set.of("id", "at", "action", "actor", "via", "credential", "detail"), view.fieldNames());
        assertEquals(List.of("unknown", "shared-token"), List.of(view.getString("actor"), view.getString("via")),
                "the bridge's shared token, with no declared name");
        assertNull(view.getValue("credential"), "the shared token has no id of its own");
        assertEquals(0, view.getJsonObject("detail").getInteger("step"));
        assertNull(audit.getValue("next"), "one page");

        JsonObject ended = get("/admin/messages/" + cancelled + "/audit");
        assertEquals(List.of("message.cancel"), field(ended, "action"), "only its own rows: " + ended);
        assertEquals(cancel.json().getLong("auditId"), ended.getJsonArray("items").getJsonObject(0).getLong("id"), "the row the cancel named");

        List<String> paged = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            JsonObject page = get("/admin/messages/" + retried + "/audit?limit=1" + (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8)));
            paged.addAll(field(page, "action"));
            after = page.getString("next");
            pages++;
        } while (after != null && pages < 5);
        assertEquals(field(audit, "action"), paged, "the pages, one after the other, are the whole list");

        assertEquals(new JsonArray(), get("/admin/messages/m-none/audit").getJsonArray("items"), "a message with no rows: an empty page");
        assertEquals(400, node.send(HttpMethod.GET, "/admin/messages/" + retried + "/audit?limit=0").status(), "a limit below 1");
        assertEquals(400, node.send(HttpMethod.GET, "/admin/messages/" + retried + "/audit?after=bm9wZQ").status(), "a cursor not handed out");

        JsonObject byStep = get("/admin/messages?flow=" + AdminPgFixture.FLOW + "&step=ingress");
        assertEquals(Set.of(cancelled, retried), Set.copyOf(field(byStep, "id")), "both arrived: " + byStep);
        assertTrue(field(byStep, "flow").stream().allMatch(AdminPgFixture.FLOW::equals), "each with its qualified flow: " + byStep);
    }

    JsonObject get(String path) throws Exception {
        AdminPgFixture.Answer a = node.send(HttpMethod.GET, path);
        assertEquals(200, a.status(), path + ": " + a.body());
        return a.json();
    }

    static List<String> field(JsonObject page, String name) {
        JsonArray items = page.getJsonArray("items");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) values.add(items.getJsonObject(i).getString(name));
        return values;
    }
}
