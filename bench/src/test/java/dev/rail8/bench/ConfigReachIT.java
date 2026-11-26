package dev.rail8.bench;

import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The measurement of how a change of the configuration reaches every node, small, on a container: {@link ConfigReachMain#run}
 * fills the store, starts the nodes, and every change reaches every node, with notices and with every notice dropped. The
 * record counts what it measured and its file is written. Correctness only: no timing thresholds.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConfigReachIT {

    static final SharedPostgres pg = SharedPostgres.database();

    @TempDir Path dir;

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void everyChangeReachesEveryNodeWithNoticesAndWithEveryNoticeDropped() throws Exception {
        String db = "config_reach_bench";
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        Path out = dir.resolve("config-reach.json");

        int code = ConfigReachMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--connections", "40", "--systems", "8", "--nodes", "2",
                "--changes", "12", "--within", "PT30S", "--out", out.toString(), "--commit", "test"});

        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(0, code, json.encodePrettily());
        assertEquals(0, json.getInteger("unreached"), json.encodePrettily());
        assertEquals(48, json.getJsonObject("fill").getJsonObject("activate").getInteger("count"), "every system and connection put in force");
        assertEquals(2, json.getJsonObject("startReadMs").size(), "each node's first read");
        for (String round : new String[] {"noticesDelivered", "noticesDropped"}) {
            JsonObject r = json.getJsonObject(round);
            assertEquals(12, r.getJsonObject("activate").getInteger("count"), round);
            assertEquals(12, r.getJsonObject("reach").getInteger("count"), round + ": every change held by every node");
        }
        try (Connection c = connect(db); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select count(*) from nodes where config_generation = (select generation from config_store)")) {
            assertTrue(r.next());
            assertEquals(2, r.getInt(1), "both nodes report the store's generation");
        }
    }

    static Connection connect(String db) throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }
}
