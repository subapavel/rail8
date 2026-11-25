package dev.rail8.runtime.secret;

import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlConnection;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The secret store's rows in memory, for tests: one {@link Db} stands for the database, and each {@code MemorySecretRows} over
 * it for one node's connection. A write runs under the database's one lock on a copy of its state, committed when its future
 * succeeds; its work must complete before it returns, as every future of this backend does.
 */
final class MemorySecretRows implements SecretRows {

    /** What a database row of {@code nodes} holds of a node: its keys, and whether its lease holds. */
    record Node(SecretRows.NodeKeys keys, boolean live) { }

    /** The audit rows written. */
    /** An audit row: who acted ({@code name}), by which way ({@code via}), what, on what, and its detail. */
    record Audit(String name, String via, String action, String subject, JsonObject detail) { }

    /** The database: the store's head, the rows, the uses, the nodes, the records whose uses are not known, the audit. */
    static final class Db {
        private static final SecureRandom RANDOM = new SecureRandom();

        private Head head = new Head(0, RANDOM.nextLong());
        private Map<String, Stored> rows = new TreeMap<>();
        private List<Use> uses = new ArrayList<>(List.of(new Use(SecretStore.GIT, "platform", "git", Set.of(dev.rail8.api.Secret.Kind.CREDENTIALS))));
        private Map<String, Node> nodes = new TreeMap<>();
        private List<String> unknown = new ArrayList<>();
        private final List<Audit> audit = new CopyOnWriteArrayList<>();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean dropNotices;
        private volatile boolean auditFails;
        private volatile boolean readsFail;

        /** Runs {@code look} after each commit that changed the store, unless notices are dropped. */
        void listen(Runnable look) {
            listeners.add(look);
        }

        /** Whether notices are lost from now on. */
        void dropNotices(boolean drop) {
            dropNotices = drop;
        }

        /** Whether every audit row fails from now on. */
        void auditFails(boolean fails) {
            auditFails = fails;
        }

        /** Whether every read of the head and the rows outside a write fails from now on. */
        void readsFail(boolean fail) {
            readsFail = fail;
        }

        synchronized void node(String node, long epoch, boolean live) {
            Node old = nodes.get(node);
            SecretRows.NodeKeys k = old == null ? new NodeKeys(node, epoch, null, null, null)
                    : new NodeKeys(node, epoch, old.keys().secretEpoch(), old.keys().key(), old.keys().previous());
            nodes.put(node, new Node(k, live));
        }

        /** Writes {@code keys} as a node's row, as a forger would. */
        synchronized void forge(NodeKeys keys) {
            nodes.put(keys.node(), new Node(keys, true));
        }

        synchronized void use(Use use) {
            uses.add(use);
        }

        synchronized void unknownRecord(String pkg) {
            unknown.add(pkg);
        }

        synchronized Map<String, Stored> rows() {
            return Map.copyOf(rows);
        }

        /** Puts a row as a database writer would, with no change of the head. */
        synchronized void put(Stored row) {
            rows.put(row.meta().name(), row);
        }

        synchronized Head head() {
            return head;
        }

        /** Sets the store back to {@code head} and {@code rows}, as a restored backup would. */
        synchronized void restore(Head head, Map<String, Stored> rows) {
            this.head = head;
            this.rows = new TreeMap<>(rows);
        }

        List<Audit> audit() {
            return List.copyOf(audit);
        }

        synchronized NodeKeys keys(String node) {
            return nodes.get(node).keys();
        }

        private void notice(boolean changed) {
            if (!changed || dropNotices) return;
            for (Runnable look : listeners) look.run();
        }
    }

    private final Db db;

    MemorySecretRows(Db db) {
        this.db = db;
    }

    @Override
    public Future<Head> head() {
        if (db.readsFail) return Future.failedFuture(new IllegalStateException("the database cannot be read"));
        return Future.succeededFuture(db.head());
    }

    @Override
    public Future<Snapshot> snapshot(int maxRow) {
        if (db.readsFail) return Future.failedFuture(new IllegalStateException("the database cannot be read"));
        synchronized (db) {
            List<Meta> metas = new ArrayList<>();
            Map<String, byte[]> sealed = new HashMap<>();
            for (Stored row : db.rows.values()) {
                metas.add(row.meta());
                if (row.sealed().length <= maxRow) sealed.put(row.meta().name(), row.sealed().clone());
            }
            return Future.succeededFuture(new Snapshot(db.head, metas, sealed));
        }
    }

    @Override
    public Future<List<NodeKeys>> sharedNodes(SqlConnection tx) {
        return liveNodes();
    }

    @Override
    public <T> Future<T> write(Collection<String> names, Function<Tx, Future<T>> work) {
        boolean[] changed = new boolean[1];
        Future<T> done;
        synchronized (db) {
            MemTx tx = new MemTx(db.head, new TreeMap<>(db.rows), new TreeMap<>(db.nodes), new ArrayList<>(), changed);
            done = work.apply(tx);
            if (!done.isComplete()) awaited(done);   // the import's reads of its files complete on a thread of their own
            if (done.succeeded()) {
                db.head = tx.head;
                db.rows = tx.rows;
                db.nodes = tx.nodes;
                db.audit.addAll(tx.audit);
            }
        }
        if (done.succeeded()) db.notice(changed[0]);
        return done;
    }

    /** Blocks until {@code done} completes. */
    private static void awaited(Future<?> done) {
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        done.onComplete(r -> completed.countDown());
        try {
            completed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a write of the memory store was interrupted", interrupted);
        }
    }

    @Override
    public Future<List<NodeKeys>> liveNodes() {
        synchronized (db) {
            return Future.succeededFuture(live(db.nodes));
        }
    }

    @Override
    public Future<Listing> listing() {
        synchronized (db) {
            return Future.succeededFuture(new Listing(db.rows.values().stream().map(Stored::meta).toList(), List.copyOf(db.uses), live(db.nodes),
                    List.copyOf(db.unknown)));
        }
    }

    private static List<NodeKeys> live(Map<String, Node> nodes) {
        return nodes.values().stream().filter(Node::live).map(Node::keys).toList();
    }

    private final class MemTx implements Tx {
        private Head head;
        private final Map<String, Stored> rows;
        private final Map<String, Node> nodes;
        private final List<Audit> audit;
        private final boolean[] changed;

        MemTx(Head head, Map<String, Stored> rows, Map<String, Node> nodes, List<Audit> audit, boolean[] changed) {
            this.head = head;
            this.rows = rows;
            this.nodes = nodes;
            this.audit = audit;
            this.changed = changed;
        }

        @Override public Head head() { return head; }

        @Override public SqlConnection connection() { return null; }

        @Override public Future<List<NodeKeys>> liveNodes() { return Future.succeededFuture(live(nodes)); }

        @Override public Future<List<Meta>> rows() { return Future.succeededFuture(rows.values().stream().map(Stored::meta).toList()); }

        @Override public Future<Optional<Stored>> row(String name) { return Future.succeededFuture(Optional.ofNullable(rows.get(name))); }

        @Override public Future<Void> upsert(Stored row) {
            rows.put(row.meta().name(), row);
            return Future.succeededFuture();
        }

        @Override public Future<Void> delete(String name) {
            rows.remove(name);
            return Future.succeededFuture();
        }

        @Override public Future<List<Use>> uses(String name) {
            return Future.succeededFuture(db.uses.stream().filter(u -> u.name().equals(name)).toList());
        }

        @Override public Future<List<String>> unknownRecords() { return Future.succeededFuture(List.copyOf(db.unknown)); }

        @Override public Future<Boolean> joined(String node, long epoch, byte[] key, byte[] previous) {
            Node old = nodes.get(node);
            if (old == null || old.keys().epoch() != epoch || !old.live()) return Future.succeededFuture(false);
            nodes.put(node, new Node(new NodeKeys(node, old.keys().epoch(), epoch, key, previous), old.live()));
            return Future.succeededFuture(true);
        }

        @Override public Future<Void> clearPrevious(String node) {
            NodeKeys k = nodes.get(node).keys();
            nodes.put(node, new Node(new NodeKeys(node, k.epoch(), k.secretEpoch(), k.key(), null), nodes.get(node).live()));
            return Future.succeededFuture();
        }

        @Override public Future<Void> audit(Actor who, String action, String subject, JsonObject detail) {
            if (db.auditFails) return Future.failedFuture(new IllegalStateException("the audit is not available"));
            audit.add(new Audit(who.name(), who.via(), action, subject, detail.copy()));
            return Future.succeededFuture();
        }

        @Override public Future<Void> auditByNode(String node, String action, String subject, JsonObject detail) {
            if (db.auditFails) return Future.failedFuture(new IllegalStateException("the audit is not available"));
            audit.add(new Audit(node, PgSecretRows.LOCAL, action, subject, detail.copy()));
            return Future.succeededFuture();
        }

        @Override public Future<Void> auditLocal(String actor, String action, String subject, JsonObject detail) {
            if (db.auditFails) return Future.failedFuture(new IllegalStateException("the audit is not available"));
            audit.add(new Audit(actor, PgSecretRows.LOCAL, action, subject, detail.copy()));
            return Future.succeededFuture();
        }

        @Override public Future<Head> changed() {
            head = new Head(head.generation() + 1, Db.RANDOM.nextLong());
            changed[0] = true;
            return Future.succeededFuture(head);
        }
    }
}
