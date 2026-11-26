package dev.rail8.runtime.journal;

import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.rail8.runtime.journal.MasterKeySourceTest.base64;
import static dev.rail8.runtime.journal.MasterKeySourceTest.material;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node on Postgres and the configuration of the environment: at its start it reads the store and writes what it holds into
 * its row of {@code nodes} in its own epoch, {@code /health} reports the check {@code config}, and a change of the store that
 * notifies on {@code rail8_config} reaches the node's row. A node that cannot read the store at its start does not start.
 */
class NodeConfigurationIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final String NODE_ID = "node-config";
    static Vertx vertx;

    @TempDir Path data;
    CaseDatabase db;
    Node node;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void caseDatabase() throws Exception {
        db = CaseDatabase.create(pg, vertx, "node_config_");
    }

    @AfterEach
    void closeEverything() throws Exception {
        try {
            if (node != null) {
                Node n = node;
                OnVirtualThread.call(() -> {
                    n.close();
                    return null;
                });
            }
        } finally {
            db.close();
        }
    }

    Map<String, String> env() {
        return Map.ofEntries(Map.entry("RAIL8_ENV", "dev"), Map.entry("RAIL8_NODE_ID", NODE_ID), Map.entry("RAIL8_ADMIN_PORT", "0"),
                Map.entry("RAIL8_PEER_PORT", "0"), Map.entry("RAIL8_HTTP_PORT", "0"), Map.entry("RAIL8_DATA_DIR", data.toString()),
                Map.entry("RAIL8_PG_HOST", pg.getHost()), Map.entry("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432))),
                Map.entry("RAIL8_PG_DATABASE", db.name()), Map.entry("RAIL8_PG_USER", pg.getUsername()),
                Map.entry("RAIL8_PG_PASSWORD", pg.getPassword()), Map.entry("RAIL8_PG_POOL_SIZE", "4"),
                Map.entry("RAIL8_JOURNAL_KEY", base64(material(211, 32))), Map.entry("RAIL8_SECRETS_KEY", base64(material(212, 32))));
    }

    /** The node's row of {@code nodes}: its epoch and what it reported holding. */
    Row row() {
        return db.pool().query("select epoch, config_epoch, config_generation, config_stamp, config_read_at from nodes where node = '" + NODE_ID + "'")
                .execute().await().iterator().next();
    }

    @Test
    void aNodeReadsTheStoreAtItsStartAndANoticeBringsAChangeToItsRow() throws Exception {
        node = OnVirtualThread.call(() -> Node.start(NodeConfig.from(env()), vertx, ctx -> List.of(), List.of(), List.of()));

        Row started = row();
        assertEquals(started.getLong("epoch"), started.getLong("config_epoch"), "the node reported in its own epoch at its start");
        long generation = db.pool().query("select generation from config_store").execute().await().iterator().next().getLong(0);
        assertEquals(generation, started.getLong("config_generation"), "it holds the store's generation");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.adminPort() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("UP", new JsonObject(health.body()).getJsonObject("checks").getString("config"), health.body());
        }

        db.pool().query("update config_store set generation = generation + 1, stamp = stamp - 1").execute().await();
        db.pool().query("select pg_notify('rail8_config', 'x')").execute().await();
        JournalFixture.until(() -> row().getLong("config_generation") == generation + 1, () -> "the node's row after the notice: " + row().toJson());
    }

    @Test
    void aNodeThatCannotReadTheStoreAtItsStartRefusesToStart() throws Exception {
        Node first = OnVirtualThread.call(() -> Node.start(NodeConfig.from(env()), vertx, ctx -> List.of(), List.of(), List.of()));   // migrates the database
        OnVirtualThread.call(() -> {
            first.close();
            return null;
        });
        db.pool().query("alter table config_store rename to config_store_away").execute().await();

        Exception refused = assertThrows(Exception.class,
                () -> node = OnVirtualThread.call(() -> Node.start(NodeConfig.from(env()), vertx, ctx -> List.of(), List.of(), List.of())));
        assertTrue(String.valueOf(refused.getMessage()).contains("config_store"), "it names what it could not read: " + refused);
    }
}
