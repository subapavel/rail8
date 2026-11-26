package dev.rail8.bench;

import dev.rail8.api.Timer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.config.Actor;
import dev.rail8.runtime.config.Change;
import dev.rail8.runtime.config.ConfigObjects;
import dev.rail8.runtime.config.ConfigRefusal;
import dev.rail8.runtime.config.ConfigStore;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.Environment;
import dev.rail8.runtime.config.Ref;
import dev.rail8.runtime.config.StoredEnvironment;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * How long a change of the configuration of an environment takes to reach every node, and how long a write takes, with the
 * store at its limits.
 *
 * <p>The run fills an empty store through one node's writes with {@code systems} systems, each with an instance name, and
 * {@code connections} connections, each with an http-like url in force. It then starts a {@link StoredEnvironment} on each of
 * {@code nodes} nodes, all over one database, and saves and activates {@code changes} connections one by one through the first
 * node's store, each with a new url, twice: once with each node told of every change at once, as a notice on
 * {@code rail8_config} does, and once with every notice dropped, so each node learns only at its own tick.
 *
 * <p>Its record holds, in milliseconds, the time of each save and activation while filling and while changing; the time of
 * each node's first read of the whole store; and per change the time from the activation's commit until every node's
 * snapshot holds the change, as percentiles and the maximum. {@code unreached} counts the changes some node did not hold
 * within {@code within}.
 */
final class ConfigReach {

    /** What a run measures with: the store's objects, the nodes, the changes and how long the nodes may take to hold them. */
    record Sizes(int connections, int systems, int nodes, int changes, Duration within) {
        Sizes {
            if (systems < 1 || connections < 1 || nodes < 1 || changes < 1 || changes > connections) {
                throw new IllegalArgumentException("at least one system, connection, node and change, and no more changes than connections");
            }
        }
    }

    /** The adapter every connection uses: its url names the server. */
    static final String ADAPTER = "http";
    static final ConfigObjects.Adapter HTTP = new ConfigObjects.Adapter(new Forms(Settings.of(
            Field.text("url", "URL", "Where requests are sent.").required().server().out(),
            Field.integer("timeoutMs", "Timeout (ms)", "How long a request may take.").byDefault(30_000).out()), Settings.NONE,
            Map.of(Direction.OUT, Forms.Reach.CONNECTION)), Set.of());

    /** The one who writes: a connection administrator. */
    static final Actor WRITER = new Actor(new dev.rail8.core.audit.Actor("bench", OptionalLong.empty(), "token", Optional.of("bench"), "127.0.0.1"), false);

    private ConfigReach() {}

    static String system(int i) {
        return String.format("s%04d", i);
    }

    static String connection(int i, Sizes sizes) {
        return system(i % sizes.systems()) + "/c" + (i / sizes.systems());
    }

    /** A run over {@code pool}'s migrated database, whose configuration store must be empty. */
    static JsonObject run(Pool pool, StoreKey key, Sizes sizes, Clock clock, Timer timer) {
        long objects = pool.query("select count(*) from config_objects").execute().await().iterator().next().getLong(0);
        if (objects != 0) throw new IllegalStateException("the configuration store holds " + objects + " objects: the run needs an empty one");
        List<ConfigStore> stores = new ArrayList<>();
        List<StoredEnvironment> environments = new ArrayList<>();
        List<SecretStore> secrets = new ArrayList<>();
        try {
            for (int n = 0; n < sizes.nodes(); n++) {
                String node = "config-bench-" + (n + 1);
                long epoch = pool.preparedQuery("insert into nodes (node, epoch, lease_until) values ($1, 1, now() + interval '1 day')"
                        + " on conflict (node) do update set epoch = nodes.epoch + 1, lease_until = excluded.lease_until returning epoch")
                        .execute(Tuple.of(node)).await().iterator().next().getLong(0);
                SecretStore secret = SecretStore.postgres(pool, key, node, clock, timer);
                secret.start(epoch, SecretStore.Import.none()).await();
                secrets.add(secret);
                ConfigStore store = ConfigObjects.postgres(pool, secret, a -> ADAPTER.equals(a) ? Optional.of(HTTP) : Optional.empty(), clock, new SecureRandom());
                stores.add(store);
                environments.add(StoredEnvironment.of("bench", store, node, () -> epoch, clock, timer));
            }
            ConfigStore writer = stores.getFirst();

            LatencyStats fillSave = new LatencyStats(sizes.systems() + sizes.connections());
            LatencyStats fillActivate = new LatencyStats(sizes.systems() + sizes.connections());
            for (int s = 0; s < sizes.systems(); s++) {
                Ref ref = Ref.system(system(s));
                String name = "INST" + s;
                String etag = timed(fillSave, () -> writer.write(WRITER, ref, new Change.SaveSystem(etag(writer, ref), name))).etag();
                timed(fillActivate, () -> writer.write(WRITER, ref, new Change.Activate(etag, Optional.empty(), Optional.empty())));
            }
            for (int c = 0; c < sizes.connections(); c++) {
                change(writer, Ref.connection(connection(c, sizes)), "https://h" + c + ".bench.example/api", fillSave, fillActivate);
            }

            JsonObject starts = new JsonObject();
            for (int n = 0; n < environments.size(); n++) {
                long start = System.nanoTime();
                environments.get(n).start();
                starts.put("config-bench-" + (n + 1), (System.nanoTime() - start) / 1e6);
            }

            JsonObject delivered = changes(writer, environments, sizes, true, 1);
            JsonObject dropped = changes(writer, environments, sizes, false, 2);
            return new JsonObject()
                    .put("connections", sizes.connections()).put("systems", sizes.systems()).put("nodes", sizes.nodes()).put("changes", sizes.changes())
                    .put("fill", new JsonObject().put("save", stats(fillSave)).put("activate", stats(fillActivate)))
                    .put("startReadMs", starts)
                    .put("noticesDelivered", delivered)
                    .put("noticesDropped", dropped)
                    .put("unreached", delivered.getInteger("unreached") + dropped.getInteger("unreached"));
        } finally {
            environments.forEach(StoredEnvironment::close);
            secrets.forEach(SecretStore::close);
        }
    }

    /**
     * {@code sizes.changes()} connections saved and activated one by one with a new url of round {@code round}, each node told
     * of each change when {@code notices}; then the wait until every node holds every change.
     */
    private static JsonObject changes(ConfigStore writer, List<StoredEnvironment> environments, Sizes sizes, boolean notices, int round) {
        LatencyStats save = new LatencyStats(sizes.changes());
        LatencyStats activate = new LatencyStats(sizes.changes());
        LatencyStats reach = new LatencyStats(sizes.changes());
        Tracker tracker = new Tracker(environments.size());
        for (int e = 0; e < environments.size(); e++) {
            int node = e;
            environments.get(e).onActivation(snapshot -> tracker.taken(node, snapshot));
        }
        for (int j = 0; j < sizes.changes(); j++) {
            int c = (int) ((long) j * 7919 % sizes.connections());   // spread over the store, each connection at most once a round
            String key = connection(c, sizes);
            String url = "https://h" + c + ".r" + round + ".bench.example/api";
            change(writer, Ref.connection(key), url, save, activate);
            tracker.add(new Pending(key, url, System.nanoTime(), environments.size()));
            if (notices) environments.forEach(StoredEnvironment::notice);
        }
        tracker.await(sizes.within());
        int unreached = 0;
        for (long took : tracker.took()) {
            if (took < 0) unreached++;
            else reach.record(took);
        }
        return new JsonObject().put("save", stats(save)).put("activate", stats(activate)).put("reach", stats(reach))
                .put("unreached", unreached);
    }

    /** A save of {@code url} and its activation. */
    private static void change(ConfigStore writer, Ref ref, String url, LatencyStats save, LatencyStats activate) {
        String etag = timed(save, () -> writer.write(WRITER, ref, new Change.Save(etag(writer, ref), ADAPTER,
                Map.of("url", url, "timeoutMs", 5_000), Optional.empty()))).etag();
        timed(activate, () -> writer.write(WRITER, ref, new Change.Activate(etag, Optional.empty(), Optional.empty())));
    }

    /** The etag of {@code ref}, that of a key no row holds included. */
    private static String etag(ConfigStore store, Ref ref) {
        try {
            return store.read(ref).etag();
        } catch (ConfigRefusal notFound) {
            return notFound.values().getString("etag");
        }
    }

    private static <T> T timed(LatencyStats stats, java.util.function.Supplier<T> write) {
        long start = System.nanoTime();
        T result = write.get();
        stats.record(System.nanoTime() - start);
        return result;
    }

    static JsonObject stats(LatencyStats s) {
        return new JsonObject().put("count", s.count()).put("p50", s.percentileMillis(50)).put("p95", s.percentileMillis(95))
                .put("p99", s.percentileMillis(99)).put("max", s.maxMillis());
    }

    /** A change of a connection's url activated at {@code committed} (nanos), and when each node's snapshot first held it. */
    static final class Pending {
        final String key;
        final String url;
        final long committed;
        final long[] reached;

        Pending(String key, String url, long committed, int nodes) {
            this.key = key;
            this.url = url;
            this.committed = committed;
            this.reached = new long[nodes];
            Arrays.fill(reached, -1);
        }

        /** When the last node first held it, or -1 while one does not. */
        long reachedAll() {
            long last = -1;
            for (long r : reached) {
                if (r < 0) return -1;
                last = Math.max(last, r);
            }
            return last;
        }
    }

    /** The changes of a round and the nodes' snapshots that hold them. */
    static final class Tracker {
        private final List<Pending> pending = new ArrayList<>();
        private final Environment.Snapshot[] latest;
        private int open;

        Tracker(int nodes) {
            this.latest = new Environment.Snapshot[nodes];
        }

        /** A change just committed, checked against each node's latest snapshot. */
        synchronized void add(Pending p) {
            pending.add(p);
            open++;
            for (int n = 0; n < latest.length; n++) {
                if (latest[n] != null) check(n, p, latest[n], System.nanoTime());
            }
        }

        /** Per change, the nanos from its commit until every node held it, or -1 while one does not. */
        synchronized long[] took() {
            return pending.stream().mapToLong(p -> p.reachedAll() < 0 ? -1 : p.reachedAll() - p.committed).toArray();
        }

        synchronized void taken(int node, Environment.Snapshot snapshot) {
            long now = System.nanoTime();
            latest[node] = snapshot;
            for (Pending p : pending) check(node, p, snapshot, now);
        }

        private void check(int node, Pending p, Environment.Snapshot snapshot, long now) {
            if (p.reached[node] >= 0) return;
            boolean held = snapshot.connection(p.key).filter(ConnectionState.Values.class::isInstance)
                    .map(c -> p.url.equals(((ConnectionState.Values) c).values().get("url"))).orElse(false);
            if (!held) return;
            p.reached[node] = now;
            if (p.reachedAll() >= 0 && --open == 0) notifyAll();
        }

        /** Waits at most {@code within} until every node holds every change. */
        synchronized void await(Duration within) {
            long deadline = System.nanoTime() + within.toNanos();
            try {
                for (long left = within.toNanos(); open > 0 && left > 0; left = deadline - System.nanoTime()) {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
