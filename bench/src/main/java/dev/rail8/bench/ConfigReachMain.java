package dev.rail8.bench;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.secret.StoreKey;
import dev.rail8.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Entry point of the measurement of how a change of the configuration of an environment reaches every node
 * ({@link ConfigReach}): {@code --connections 20000 --systems 4096 --nodes 3 --changes 1000 --within PT60S --host localhost
 * --port 5432 --db rail8_bench_config --user rail8 --password rail8 --out bench/out/config-reach.json --commit <sha>}.
 * The database is migrated first and its configuration store must be empty, so each run takes a database of its own, its
 * name holding {@code bench}. Prints the record and writes it to {@code --out}. Exit 0, or 3 when some node did not hold
 * some change within {@code --within}.
 *
 * <p>From the repository root, against a native Postgres:
 * <pre>{@code
 * ./mvnw -q -B -ntp -pl :rail8-bench -am package -DskipTests
 * createdb rail8_bench_config
 * java -cp "$(ls bench/target/rail8-bench-*.jar | grep -v -E 'sources|javadoc' | head -1):bench/target/lib/*" \
 *   dev.rail8.bench.ConfigReachMain --commit "$(git rev-parse --short HEAD)"
 * }</pre>
 */
public final class ConfigReachMain {

    private ConfigReachMain() {}

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    static int run(String[] args) throws IOException {
        Map<String, String> a = BenchMain.parse(args);
        ConfigReach.Sizes sizes = new ConfigReach.Sizes(Integer.parseInt(a.getOrDefault("connections", "20000")),
                Integer.parseInt(a.getOrDefault("systems", "4096")), Integer.parseInt(a.getOrDefault("nodes", "3")),
                Integer.parseInt(a.getOrDefault("changes", "1000")), Duration.parse(a.getOrDefault("within", "PT60S")));
        String db = a.getOrDefault("db", "rail8_bench_config");
        if (!db.contains("bench")) throw new IllegalArgumentException("--db '" + db + "' does not name a bench database (its name contains 'bench')");
        PgConnectOptions connect = new PgConnectOptions().setHost(a.getOrDefault("host", "localhost")).setPort(Integer.parseInt(a.getOrDefault("port", "5432")))
                .setDatabase(db).setUser(a.getOrDefault("user", "rail8")).setPassword(a.getOrDefault("password", "rail8"));
        Path out = Path.of(a.getOrDefault("out", "bench/out/config-reach.json"));
        Migrations.migrate("jdbc:postgresql://" + connect.getHost() + ":" + connect.getPort() + "/" + db, connect.getUser(), connect.getPassword());
        StoreKey key = StoreKey.fromEnv(Map.of(StoreKey.VARIABLE, NodeBenchMain.SECRETS_KEY)).orElseThrow();

        Vertx vertx = Vertx.vertx();
        try {
            Pool pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4 * sizes.nodes())).using(vertx).build();
            try {
                JsonObject json = ConfigReach.run(pool, key, sizes, Clock.systemUTC(), new VertxTimer(vertx))
                        .put("name", "config-reach")
                        .put("machine", BenchMain.machine())
                        .put("date", Instant.now().toString())
                        .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, json.encodePrettily() + "\n");
                System.out.println(json.encodePrettily());
                int unreached = json.getInteger("unreached");
                System.err.println(unreached + " changes some node did not hold within " + sizes.within() + " (0 expected)");
                return unreached == 0 ? 0 : 3;
            } finally {
                pool.close().await();
            }
        } finally {
            vertx.close().await();
        }
    }
}
