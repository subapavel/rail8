package dev.rail8.runtime.deploy;

import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Promise;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * A {@link DeploymentStore} at the SPI boundary of {@code Deployments} (P1-09c3a test fixture): an {@link InMemoryDeploymentStore}
 * whose lock records every save call, traces it ({@code "save <name> <version>"} at the call, on the calling thread), and can be told to
 * fail or to hold the next save. A failed save writes nothing; a held one writes once it is released.
 *
 * <p>P2-02c: deletes likewise — traced {@code "delete <name>"} at the call, recorded, the next one failed ({@link #failNextDelete},
 * deletes nothing) or held ({@link #holdNextDelete}, deletes once released).
 */
final class Ledger implements DeploymentStore {

    private final InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
    private final List<DeploymentRecord> saves = new CopyOnWriteArrayList<>();
    private final List<Hold> holds = new CopyOnWriteArrayList<>();
    private final Consumer<String> trace;
    private volatile Throwable failNext;
    private volatile Hold holdNext;
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private volatile Throwable failNextDelete;
    private volatile Hold holdNextDelete;

    Ledger(Consumer<String> trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** A save held at its call until {@link #release} or {@link #fail}; {@link #reached} counts down at the call. */
    static final class Hold {
        private final Promise<Void> gate = Promise.promise();
        private final CountDownLatch reached = new CountDownLatch(1);

        CountDownLatch reached() { return reached; }

        void release() { gate.tryComplete(); }

        void fail(String message) { gate.tryFail(new IllegalStateException(message)); }
    }

    /** The next save fails with an {@link IllegalStateException} carrying {@code message}, and writes nothing. */
    void failNextSave(String message) {
        failNextSave(new IllegalStateException(Objects.requireNonNull(message, "message")));
    }

    /** The next save fails with {@code failure} itself (FIX-172b: a pool's refusal), and writes nothing. */
    void failNextSave(Throwable failure) {
        failNext = Objects.requireNonNull(failure, "failure");
    }

    /** The next save waits for the hold; released, it writes and succeeds. */
    Hold holdNextSave() {
        Hold h = new Hold();
        holds.add(h);
        holdNext = h;
        return h;
    }

    /** The next delete fails with an {@link IllegalStateException} carrying {@code message}, and deletes nothing. */
    void failNextDelete(String message) {
        failNextDelete(new IllegalStateException(Objects.requireNonNull(message, "message")));
    }

    /** The next delete fails with {@code failure} itself (FIX-172b F1: a pool's refusal), and deletes nothing. */
    void failNextDelete(Throwable failure) {
        failNextDelete = Objects.requireNonNull(failure, "failure");
    }

    /** The next delete waits for the hold; released, it deletes and succeeds. */
    Hold holdNextDelete() {
        Hold h = new Hold();
        holds.add(h);
        holdNextDelete = h;
        return h;
    }

    /** The name of every delete call, in order, failed or held ones included. */
    List<String> deletes() {
        return List.copyOf(deletes);
    }

    /** Every save call, in order, failed or held ones included. */
    List<DeploymentRecord> saves() {
        return List.copyOf(saves);
    }

    /** What the store holds now, as {@link #list()} answers it, awaited. */
    List<DeploymentRecord> rows() {
        return list().await();
    }

    /** The audit rows written, oldest first. */
    List<InMemoryDeploymentStore.Audited> audited() {
        return rows.audited();
    }

    /** Writes every record of {@code from} into {@code to} through its lock: a record the pool would refuse, written by hand. */
    static void copy(DeploymentStore from, DeploymentStore to) {
        PoolLock lock = to.lock().await().orElseThrow(() -> new IllegalStateException("the pool's lock is held"));
        try {
            for (DeploymentRecord r : from.read().await().records()) lock.save(r, TestActors.named("test"), new JsonObject()).await();
        } finally {
            lock.close().await();
        }
    }

    /** Releases every hold: nothing a case held outlives it. */
    void releaseAll() {
        holds.forEach(Hold::release);
    }

    /** Saves {@code record} through the pool's lock, as a deploy saves it (traced, held or failed as told), and frees the lock. */
    Future<Void> save(DeploymentRecord record) {
        return lock().compose(held -> {
            PoolLock lock = held.orElseThrow(() -> new IllegalStateException("the pool's lock is held"));
            return lock.save(record, TestActors.named("test"), new JsonObject()).eventually(lock::close).mapEmpty();
        });
    }

    /** Every record, as {@link #read()} answers them. */
    Future<List<DeploymentRecord>> list() {
        return rows.read().map(Snapshot::records);
    }

    @Override
    public Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages) {
        return rows.writeListing(node, epoch, packages);
    }

    @Override
    public Future<Listings> listings() {
        return rows.listings();
    }

    @Override
    public Future<Snapshot> read() {
        return rows.read();
    }

    @Override
    public Future<Optional<PoolLock>> lock() {
        return rows.lock().map(held -> held.map(Traced::new));
    }

    @Override
    public Future<Void> audit(Actor by, String repository, JsonObject detail) {
        return rows.audit(by, repository, detail);
    }

    @Override
    public Future<Void> endMarks() {
        return rows.endMarks();
    }

    /** The lock of the wrapped store, its saves and deletes recorded, traced, and failed or held as told. */
    private final class Traced implements PoolLock {
        private final PoolLock lock;

        Traced(PoolLock lock) {
            this.lock = lock;
        }

        @Override
        public Snapshot snapshot() {
            return lock.snapshot();
        }

        @Override
        public Future<Long> save(DeploymentRecord record, Actor by, JsonObject detail) {
            saves.add(record);
            trace.accept("save " + record.name() + " " + record.version());
            Throwable failure = failNext;
            failNext = null;
            if (failure != null) return Future.failedFuture(failure);
            Hold held = holdNext;
            holdNext = null;
            if (held == null) return lock.save(record, by, detail);
            held.reached.countDown();
            return held.gate.future().compose(v -> lock.save(record, by, detail));
        }

        @Override
        public Future<Long> delete(String name, Actor by, JsonObject detail) {
            deletes.add(name);
            trace.accept("delete " + name);
            Throwable failure = failNextDelete;
            failNextDelete = null;
            if (failure != null) return Future.failedFuture(failure);
            Hold held = holdNextDelete;
            holdNextDelete = null;
            if (held == null) return lock.delete(name, by, detail);
            held.reached.countDown();
            return held.gate.future().compose(v -> lock.delete(name, by, detail));
        }

        @Override
        public Future<Void> close() {
            return lock.close();
        }
    }
}
