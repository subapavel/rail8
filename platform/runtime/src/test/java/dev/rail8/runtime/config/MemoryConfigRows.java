package dev.rail8.runtime.config;

import dev.rail8.api.Secret;
import dev.rail8.runtime.secret.SecretStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlConnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The configuration's rows in memory, for the tests: one {@link Db} shared by the stores of several nodes, as one database is.
 * A write works on a copy of the database and puts it back when it returns, so a refused write changes nothing.
 */
final class MemoryConfigRows implements ConfigRows {

    /** The database: the tables of the configuration, the deployed systems and uses, the connections' uses of secrets, the audit. */
    static final class Db {
        Head head = new Head(0, 1, false);
        TreeMap<Ref, ObjectRow> objects = new TreeMap<>(Comparator.comparing(Ref::kind).thenComparing(Ref::key));
        List<History> history = new ArrayList<>();
        long nextId = 1;
        Map<String, JsonObject> systems = new HashMap<>();
        List<Use> uses = new ArrayList<>();
        Map<String, Map<String, Set<Secret.Kind>>> secretUses = new TreeMap<>();
        List<JsonObject> audit = new ArrayList<>();
        List<Long> notices = new ArrayList<>();
        /** The rows of {@code nodes}: each node's report by node, and the nodes whose lease no longer holds. */
        Map<String, ConfigStore.NodeReport> nodes = new TreeMap<>();
        Set<String> lapsed = new TreeSet<>();
        /** The database's time, which a node's report takes as the time of its read. */
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        boolean auditFails;
        boolean holdsFails;

        /** The row of {@code node} in its epoch {@code epoch}, its lease holding and nothing reported, as a node's start writes it. */
        void lease(String node, long epoch) {
            nodes.put(node, new ConfigStore.NodeReport(node, epoch, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                    Optional.empty(), List.of(), 0));
            lapsed.remove(node);
        }

        Db copy() {
            Db d = new Db();
            d.head = head;
            objects.forEach((k, v) -> d.objects.put(k, v.copy()));
            for (History h : history) d.history.add(copyOf(h));
            d.nextId = nextId;
            d.systems = new HashMap<>(systems);
            d.uses = new ArrayList<>(uses);
            d.secretUses = new TreeMap<>(secretUses);
            d.audit = new ArrayList<>(audit);
            d.notices = new ArrayList<>(notices);
            d.nodes = new TreeMap<>(nodes);
            d.lapsed = new TreeSet<>(lapsed);
            d.now = now;
            d.auditFails = auditFails;
            d.holdsFails = holdsFails;
            return d;
        }

        void take(Db d) {
            head = d.head;
            objects = d.objects;
            history = d.history;
            nextId = d.nextId;
            systems = d.systems;
            uses = d.uses;
            secretUses = d.secretUses;
            audit = d.audit;
            notices = d.notices;
            nodes = d.nodes;
            lapsed = d.lapsed;
        }
    }

    static History copyOf(History h) {
        History c = new History();
        c.id = h.id;
        c.ref = h.ref;
        c.at = h.at;
        c.actor = h.actor;
        c.userId = h.userId;
        c.via = h.via;
        c.credential = h.credential;
        c.change = h.change;
        c.revision = h.revision;
        c.generation = h.generation;
        c.version = h.version;
        c.format = h.format;
        c.body = h.body == null ? null : h.body.copy();
        c.incarnation = h.incarnation;
        c.activation = h.activation;
        c.keyId = h.keyId == null ? null : h.keyId.clone();
        c.tag = h.tag == null ? null : h.tag.clone();
        c.diff = h.diff.copy();
        c.note = h.note;
        return c;
    }

    private final Db db;
    private final SecretStore secrets;

    MemoryConfigRows(Db db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    @Override
    public <T> T read(Function<Tx, T> work) {
        synchronized (db) {
            return work.apply(new MemTx(db.copy()));
        }
    }

    @Override
    public <T> T write(Function<Tx, T> work) {
        synchronized (db) {
            Db copy = db.copy();
            T result = work.apply(new MemTx(copy));
            db.take(copy);
            return result;
        }
    }

    @Override
    public <T> Future<T> within(SqlConnection tx, Function<Tx, T> work) {
        try {
            return Future.succeededFuture(write(work));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public void holds(String node, long epoch, long generation, long stamp, ConfigStore.Refused refused) {
        synchronized (db) {
            if (db.holdsFails) throw new IllegalStateException("the database is away");
            ConfigStore.NodeReport row = db.nodes.get(node);
            if (row == null || row.epoch() != epoch) return;
            db.nodes.put(node, new ConfigStore.NodeReport(node, epoch, OptionalLong.of(epoch), OptionalLong.of(generation), OptionalLong.of(stamp),
                    Optional.of(db.now), refused.objects(), refused.count()));
        }
    }

    private final class MemTx implements Tx {
        private final Db d;

        MemTx(Db d) {
            this.d = d;
        }

        @Override public SqlConnection connection() { return null; }

        @Override public Head head(boolean lock) { return d.head; }

        @Override public void head(Head head) { d.head = head; }

        @Override public void notifyGeneration(long generation) { d.notices.add(generation); }

        @Override public List<ConfigStore.NodeReport> nodes() {
            return d.nodes.values().stream().filter(n -> !d.lapsed.contains(n.node())).toList();
        }

        @Override public Optional<ObjectRow> row(Ref ref, boolean lock) {
            ObjectRow r = d.objects.get(ref);
            return r == null ? Optional.empty() : Optional.of(r.copy());
        }

        @Override public void put(ObjectRow row) { d.objects.put(row.ref, row.copy()); }

        @Override public List<ObjectRow> rows(Ref.Kind kind, String prefix, String after, int limit) {
            return d.objects.values().stream().filter(r -> r.ref.kind() == kind && r.ref.key().startsWith(prefix) && r.ref.key().compareTo(after) > 0)
                    .sorted(Comparator.comparing(r -> r.ref.key())).limit(limit).map(ObjectRow::copy).toList();
        }

        @Override public List<ObjectRow> inForce() {
            return d.objects.values().stream().filter(r -> r.body != null).map(ObjectRow::copy).toList();
        }

        @Override public long count(Ref.Kind kind) {
            return d.objects.keySet().stream().filter(r -> r.kind() == kind).count();
        }

        @Override public long bodyBytes(Ref except) {
            return d.objects.values().stream().filter(r -> r.body != null && !r.ref.equals(except)).mapToLong(r -> r.body.encode().length()).sum();
        }

        @Override public boolean used(long id) {
            for (ObjectRow r : d.objects.values()) {
                if (r.incarnation == id || Long.valueOf(id).equals(r.activation) || Long.valueOf(id).equals(r.draftId)) return true;
            }
            for (History h : d.history) if (Long.valueOf(id).equals(h.incarnation) || Long.valueOf(id).equals(h.activation)) return true;
            return false;
        }

        @Override public long add(History row) {
            History h = copyOf(row);
            h.id = d.nextId++;
            d.history.add(h);
            return h.id;
        }

        private List<History> of(Ref ref) {
            List<History> list = new ArrayList<>();
            for (History h : d.history) if (h.ref.equals(ref)) list.add(copyOf(h));
            list.sort(Comparator.comparingLong((History h) -> h.id).reversed());
            return list;
        }

        @Override public List<History> activations(Ref ref) {
            return of(ref).stream().filter(h -> h.change.equals("activate")).toList();
        }

        @Override public Optional<History> version(Ref ref, int version) {
            return activations(ref).stream().filter(h -> h.version == version).findFirst();
        }

        @Override public List<History> history(Ref ref, OptionalLong before, int limit) {
            return of(ref).stream().filter(h -> h.id < before.orElse(Long.MAX_VALUE)).limit(limit).toList();
        }

        @Override public void trim(Ref ref, int max, int keep) {
            List<History> all = of(ref);
            Set<Long> kept = new TreeSet<>();
            all.stream().filter(h -> h.change.equals("activate")).limit(keep).forEach(h -> kept.add(h.id));
            int over = all.size() - max;
            if (over <= 0) return;
            List<History> oldest = new ArrayList<>(all);
            oldest.sort(Comparator.comparingLong(h -> h.id));
            Set<Long> gone = new TreeSet<>();
            for (History h : oldest) {
                if (gone.size() == over) break;
                if (!kept.contains(h.id)) gone.add(h.id);
            }
            d.history.removeIf(h -> gone.contains(h.id));
        }

        @Override public Optional<String> deployedAdapter(String connectionKey) {
            String system = connectionKey.substring(0, connectionKey.lastIndexOf('/'));
            String name = connectionKey.substring(connectionKey.lastIndexOf('/') + 1);
            JsonObject definition = d.systems.get(system);
            if (definition == null || definition.getJsonObject("connections") == null) return Optional.empty();
            JsonObject connection = definition.getJsonObject("connections").getJsonObject(name);
            return connection == null ? Optional.empty() : Optional.ofNullable(connection.getString("adapter"));
        }

        @Override public boolean deployed(String systemKey) { return d.systems.containsKey(systemKey); }

        @Override public List<Use> uses(String connectionKey) {
            return d.uses.stream().filter(u -> u.connection().equals(connectionKey))
                    .sorted(Comparator.comparing(Use::flow).thenComparing(Use::step).thenComparing(Use::direction)).toList();
        }

        @Override public List<Use> usesOfSystem(String systemKey) {
            return d.uses.stream().filter(u -> u.connection().startsWith(systemKey + "/") && u.connection().indexOf('/', systemKey.length() + 1) < 0)
                    .toList();
        }

        @Override public List<String> replaceUses(Ref ref, Map<String, Set<Secret.Kind>> uses) {
            List<String> problems = new ArrayList<>();
            new TreeMap<>(uses).forEach((name, takes) -> {
                Optional<Secret.Kind> kind = secrets.held(name).map(SecretStore.Held::kind);
                if (name.startsWith(SecretStore.RESERVED)) problems.add(ref + " uses secret://" + name + ": names under rail8. belong to the platform");
                else if (kind.isEmpty()) problems.add(ref + " uses secret://" + name + ", which is not set");
                else if (!takes.contains(kind.get())) problems.add(ref + " takes secret://" + name + " as another kind");
            });
            if (problems.isEmpty()) d.secretUses.put(ref.key(), Map.copyOf(uses));
            return problems;
        }

        @Override public Map<String, Secret.Kind> kinds(Set<String> names) {
            Map<String, Secret.Kind> kinds = new HashMap<>();
            for (String name : names) secrets.held(name).ifPresent(h -> kinds.put(name, h.kind()));
            return kinds;
        }

        @Override public void audit(dev.rail8.core.audit.Actor who, Instant at, String action, String subject, JsonObject detail) {
            if (d.auditFails) {
                throw new ConfigRefusal(503, dev.rail8.runtime.refusal.RefusalCode.AUDIT_UNAVAILABLE,
                        "the audit row could not be written, so nothing was changed: try again", new JsonObject());
            }
            d.audit.add(new JsonObject().put("at", at.toString()).put("actor", who.name()).put("action", action).put("subject", subject)
                    .put("detail", detail.copy()));
        }

        @Override public List<SecretAct> secretActs(Set<String> names, Optional<Instant> from, Optional<Instant> until) {
            return List.of();
        }

        @Override public List<ObjectRow> boundElsewhere(byte[] keyId, int max) {
            return d.objects.values().stream().filter(r -> (r.tag != null && !java.util.Arrays.equals(r.keyId, keyId))
                    || (r.consentTag != null && !java.util.Arrays.equals(r.consentKeyId, keyId))).limit(max).map(ObjectRow::copy).toList();
        }

        @Override public List<History> historyBoundElsewhere(byte[] keyId, int max) {
            return d.history.stream().filter(h -> h.change.equals("activate") && h.tag != null && !java.util.Arrays.equals(h.keyId, keyId))
                    .limit(max).map(MemoryConfigRows::copyOf).toList();
        }

        @Override public void bindHistory(long id, byte[] keyId, byte[] tag) {
            for (History h : d.history) {
                if (h.id == id) {
                    h.keyId = keyId == null ? null : keyId.clone();
                    h.tag = tag == null ? null : tag.clone();
                }
            }
        }

        @Override public long countUnder(byte[] keyId) {
            long n = 0;
            for (ObjectRow r : d.objects.values()) {
                if (r.tag != null && java.util.Arrays.equals(r.keyId, keyId)) n++;
                if (r.consentTag != null && java.util.Arrays.equals(r.consentKeyId, keyId)) n++;
            }
            for (History h : d.history) if (h.tag != null && java.util.Arrays.equals(h.keyId, keyId)) n++;
            return n;
        }
    }
}
