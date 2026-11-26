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

    /** The most files an import takes in one call. */
    int MAX_IMPORT_FILES = 1_000;

    /**
     * Every version in force in {@code scope} whose binding verifies, one file per object, sorted by kind and key: no draft, no
     * state, no history, no consent and no secret's value. An object whose binding fails is left out and named.
     */
    Export export(Scope scope);

    /**
     * The dry run of an import of {@code files} over {@code scope} by a caller who may write the objects of the kinds
     * {@code writes}: what each file changes, the blockers of a later activation, the notes, the objects of the scope no file
     * names, the problems, and the {@code expect} its write states. It changes nothing.
     *
     * @throws ConfigRefusal 422 {@code problems}, or {@code format} when a file is of a kind or a format this node does not read,
     *                       answering the dry run; 413 {@code too-large} beyond {@value #MAX_IMPORT_FILES} files
     */
    Plan importCheck(Set<Ref.Kind> writes, Scope scope, List<File> files);

    /**
     * The write of an import: under the locks of the objects its files name, the dry run again, then a draft of each object a
     * file creates or changes, all in one transaction, with a history row {@code import} each and one audit row
     * {@code config.import}. It never deletes, discards, activates or replaces a draft that differs from its file. Answers each
     * object its file names with its {@code etag} and whether its file is the version in force.
     *
     * @throws ConfigRefusal 412 {@code changed} when the dry run differs from {@code expect}, answering the new dry run; 422 as
     *                       {@link #importCheck}; 409 {@code config-full}; 422 {@code invalid} for a note over 200 characters
     */
    Plan importWrite(Actor actor, Set<Ref.Kind> writes, Scope scope, List<File> files, String expect, Optional<String> note);

    /**
     * What an export or an import covers: the objects of {@code kind}, or of every kind, whose key starts with {@code prefix},
     * or every object.
     */
    record Scope(Optional<Ref.Kind> kind, Optional<String> prefix) {
        public Scope {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(prefix, "prefix");
        }

        /** Every object. */
        public static Scope everything() {
            return new Scope(Optional.empty(), Optional.empty());
        }

        /** Whether the scope covers {@code ref}. */
        public boolean covers(Ref ref) {
            return kind.map(k -> k == ref.kind()).orElse(true) && prefix.map(p -> ref.key().startsWith(p)).orElse(true);
        }

        /** The scope as a request states it: {@code {kind?, prefix?}}. */
        public JsonObject json() {
            JsonObject json = new JsonObject();
            kind.ifPresent(k -> json.put("kind", k.id()));
            prefix.ifPresent(p -> json.put("prefix", p));
            return json;
        }
    }

    /** A file of an export or an import: its path in the folder, such as {@code connection/acme/erp/rest.yaml}, and its text. */
    record File(String path, String text) {
        public File {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(text, "text");
        }
    }

    /** An export: its files, and the objects in force left out because their binding fails. */
    record Export(List<File> files, List<Ref> refused) {
        public Export {
            files = List.copyOf(files);
            refused = List.copyOf(refused);
        }
    }

    /** A dry run or a write of an import, as JSON; immutable. */
    record Plan(JsonObject json) {
        public Plan {
            json = json.copy();
        }

        @Override
        public JsonObject json() {
            return json.copy();
        }

        /** The {@code expect} the write states. */
        public String expect() {
            return json.getString("expect");
        }
    }

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
     * the objects whose binding fails, at most 100 of them, with their count, and the id of the key each named one is bound
     * under, in hex, or {@code none} when it is unbound.
     */
    record State(long generation, long stamp, boolean mocksAllowed, List<InForce> changed, Set<Ref> gone, Refused refused,
                 Map<Ref, String> refusedKeys) {
        public State {
            changed = List.copyOf(changed);
            gone = Set.copyOf(gone);
            Objects.requireNonNull(refused, "refused");
            refusedKeys = Map.copyOf(refusedKeys);
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
