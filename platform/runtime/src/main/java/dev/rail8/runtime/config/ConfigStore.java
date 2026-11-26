package dev.rail8.runtime.config;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The configuration of an environment: per object of a kind and a key, one version in force, one draft with the secret
 * administrator's consent to it, a connection's stop, and a history. In Postgres ({@link ConfigObjects#postgres}), or in
 * memory in the tests; one contract suite for both. Every method blocks: it runs on a virtual thread, never on an event loop.
 *
 * <p>Every refusal is a {@link ConfigRefusal}. Every write runs in one transaction, which takes its locks in one order: the
 * names' locks of the secrets an activation names, the secret store's row for share (an activation, a consent, binding again),
 * {@code config_store} for update (an activation, a stop, a start, the switch of mocks, binding again, a save that creates a
 * row), then the object's row for update.
 *
 * @since 0.4
 */
public interface ConfigStore {

    /** The most objects a page of a list or a history holds. */
    int MAX_PAGE = 500;

    /**
     * The object as the screen shows it.
     *
     * @throws ConfigRefusal 404 {@code not-found}, carrying the {@code etag} of a key no row holds, when no row holds the key
     *                       and no deployed system names it
     */
    ObjectView read(Ref ref);

    /** A page of the objects of a kind, by key. */
    Page<ObjectView> list(Query query);

    /** One write of the object {@code ref}; answers the object as it is after it. */
    ObjectView write(Actor actor, Ref ref, Change change);

    /** The switch of mocks, which has no key. */
    void writeMocks(Actor actor, Change.Mocks change);

    /** What a node that holds {@code held} lacks: the objects in force it does not hold as they are, and those gone. */
    State state(Held held);

    /**
     * Records what node {@code node} in its epoch {@code epoch} holds: the generation and stamp it read, and what it left out,
     * into its row of {@code nodes}, only while that row carries {@code epoch}; the time of the read is the database's.
     */
    void holds(String node, long epoch, long generation, long stamp, Refused refused);

    /** The environment: the store's generation, stamp and switch of mocks, and what each live node reported it holds. */
    Overview overview();

    /** A page of the object's history, newest first: the rows before {@code after}, with the secret store's acts merged in. */
    Page<HistoryRow> history(Ref ref, OptionalLong after, int limit);

    /** An object as the screen shows it, as JSON; immutable. */
    record ObjectView(JsonObject json) {
        public ObjectView {
            json = json.copy();
        }

        @Override
        public JsonObject json() {
            return json.copy();
        }

        /** The object's {@code etag}, the {@code expect} of its next write. */
        public String etag() {
            return json.getString("etag");
        }

        /** The connection's {@code run} token, the {@code expect} of its next stop or start. */
        public String run() {
            return json.getString("run");
        }
    }

    /** One row of an object's history as the screen shows it, as JSON; immutable. */
    record HistoryRow(JsonObject json) {
        public HistoryRow {
            json = json.copy();
        }

        @Override
        public JsonObject json() {
            return json.copy();
        }
    }

    /** A page: its items, and the cursor of the next page when there is one. */
    record Page<T>(List<T> items, Optional<String> next) {
        public Page {
            items = List.copyOf(items);
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * A list's question: the objects of {@code kind} whose key starts with {@code prefix}, after the key {@code after}, at most
     * {@code limit} (1 to {@value #MAX_PAGE}); with {@code needs}, only the objects whose draft waits for that.
     */
    record Query(Ref.Kind kind, Optional<String> prefix, Optional<String> after, int limit, Optional<Needs> needs) {
        public Query {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(needs, "needs");
        }
    }

    /** What a list may narrow to: the drafts that wait for the secret administrator's consent, or every draft that waits for its activation. */
    enum Needs {
        CONSENT, ACTIVATION
    }

    /** What a node holds of one object: the activation in force it holds, and who stopped it and when while it is stopped. */
    record Holding(long activation, Optional<ConnectionState.Stop> stopped) {
        public Holding {
            Objects.requireNonNull(stopped, "stopped");
        }
    }

    /** What a node holds: the generation and stamp it read, and what it holds of each object. */
    record Held(long generation, long stamp, Map<Ref, Holding> objects) {
        public Held {
            objects = Map.copyOf(objects);
        }

        /** What a node that holds nothing holds. */
        public static Held nothing() {
            return new Held(-1, 0, Map.of());
        }
    }

    /** One object in force as a node takes it: its activation, format, body, and who stopped it and when while it is stopped. */
    record InForce(Ref ref, long activation, int format, JsonObject body, Optional<ConnectionState.Stop> stopped) {
        public InForce {
            body = body.copy();
            Objects.requireNonNull(stopped, "stopped");
        }

        @Override
        public JsonObject body() {
            return body.copy();
        }
    }

    /**
     * What a node lacks: the store's generation, stamp and switch of mocks; the objects in force whose binding verifies that
     * it does not hold as they are; the objects it holds that are no longer in force or whose binding no longer verifies; and
     * the objects whose binding fails, at most 100 of them, with their count.
     */
    record State(long generation, long stamp, boolean mocksAllowed, List<InForce> changed, Set<Ref> gone, Refused refused) {
        public State {
            changed = List.copyOf(changed);
            gone = Set.copyOf(gone);
            Objects.requireNonNull(refused, "refused");
        }

        /** Whether the node holds the store as it is. */
        public boolean current() {
            return changed.isEmpty() && gone.isEmpty();
        }
    }

    /** The objects a node left out, at most 100, and how many it left out. */
    record Refused(List<Ref> objects, int count) {
        public Refused {
            objects = List.copyOf(objects);
        }

        /** Nothing left out. */
        public static Refused none() {
            return new Refused(List.of(), 0);
        }
    }

    /** The store's generation, stamp and switch of mocks, and the reports of the nodes whose lease holds, by node. */
    record Overview(long generation, long stamp, boolean mocksAllowed, List<NodeReport> nodes) {
        public Overview {
            nodes = List.copyOf(nodes);
        }

        /**
         * Whether {@code report}'s node holds {@code ref} as it is in force: {@code yes} when it read in its epoch the store's
         * generation and stamp and did not leave the object out, {@code unknown} when it left out more objects than it names,
         * and {@code no} otherwise.
         */
        public String holds(NodeReport report, Ref ref) {
            boolean current = report.configEpoch().isPresent() && report.configEpoch().getAsLong() == report.epoch()
                    && report.generation().isPresent() && report.generation().getAsLong() == generation
                    && report.stamp().isPresent() && report.stamp().getAsLong() == stamp;
            if (!current || report.refused().contains(ref)) return "no";
            return report.refusedCount() > report.refused().size() ? "unknown" : "yes";
        }
    }

    /**
     * What a node whose lease holds reported: its epoch, and, once it has read the store, the epoch it read in, the generation
     * and stamp it read, when it last read, and the objects it left out, at most 100 named, with their count.
     */
    record NodeReport(String node, long epoch, OptionalLong configEpoch, OptionalLong generation, OptionalLong stamp, Optional<Instant> readAt,
                      List<Ref> refused, int refusedCount) {
        public NodeReport {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(configEpoch, "configEpoch");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(readAt, "readAt");
            refused = List.copyOf(refused);
        }
    }
}
