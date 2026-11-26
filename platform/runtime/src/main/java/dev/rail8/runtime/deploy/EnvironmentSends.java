package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Timer;
import dev.rail8.core.flow.ConnectionSends;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.Environment;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sends through connections over the node's {@link Environment}: one producer per connection and version, shared by every
 * flow and step that sends through it. Each attempt reads one snapshot. When the connection's version there differs from its
 * producer's, higher or lower, the attempt builds a new producer from the snapshot's values and the old one closes once every
 * attempt that took it settled; at most {@value #MAX_CLOSING} old producers of one connection close at once, and an attempt
 * that would need one more waits for one to close, at most its own time. An activation that changes or removes a connection
 * retires its producer at once, within that limit. A connection that cannot serve fails the attempt retryably, naming the
 * connection and the reason ({@link Serving#problem}).
 */
final class EnvironmentSends implements ConnectionSends {

    /** The most old producers of one connection that close at once. */
    static final int MAX_CLOSING = 4;
    private static final System.Logger LOG = System.getLogger(EnvironmentSends.class.getName());

    private final Environment environment;
    private final Map<String, Component> components;
    private final Secrets secrets;
    private final Timer timer;
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final java.util.function.Consumer<String> handed;

    EnvironmentSends(Environment environment, Map<String, Component> components, Secrets secrets, Timer timer) {
        this(environment, components, secrets, timer, reference -> { });
    }

    /** As above, {@code handed} told each secret reference a producer's connection holds a handle of. */
    EnvironmentSends(Environment environment, Map<String, Component> components, Secrets secrets, Timer timer,
                     java.util.function.Consumer<String> handed) {
        this.handed = Objects.requireNonNull(handed, "handed");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.components = Map.copyOf(components);
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.timer = Objects.requireNonNull(timer, "timer");
        environment.onActivation(this::activated);
    }

    @Override
    public Attempt attempt(String key, String adapter, Duration within) {
        Environment.Snapshot snapshot = environment.current();
        Optional<String> problem = Serving.problem(snapshot, key, adapter, Direction.OUT, components, secrets, true);
        if (problem.isPresent()) throw cannot(key, problem.get());
        ConnectionState.Values values = (ConnectionState.Values) snapshot.connection(key).orElseThrow();
        Pool pool = pools.computeIfAbsent(key, k -> new Pool());
        Promise<Void> expired = null;
        Timer.Cancellable limit = null;
        try {
            while (true) {
                Promise<Void> closed;
                synchronized (pool) {
                    Held current = pool.current;
                    if (current != null && current.version == values.version()) return current.take();
                    if (current == null || pool.closing.size() < MAX_CLOSING) {
                        Held built = build(key, adapter, values, pool);
                        if (current != null) pool.retire(current);
                        pool.current = built;
                        return built.take();
                    }
                    closed = Promise.promise();
                    pool.waiting.add(closed);
                }
                if (expired == null) {
                    Promise<Void> e = Promise.promise();
                    expired = e;
                    limit = timer.schedule(within, e::tryComplete);
                }
                Future.any(closed.future(), expired.future()).otherwiseEmpty().await();
                if (expired.future().isComplete()) {
                    throw cannot(key, "its " + MAX_CLOSING + " old producers are still closing after " + within);
                }
            }
        } finally {
            if (limit != null) limit.cancel();
        }
    }

    /** Closes every producer this holds; once closed, no attempt may follow. */
    Future<Void> close() {
        List<Future<Void>> closing = new ArrayList<>();
        for (Pool pool : pools.values()) {
            synchronized (pool) {
                if (pool.current != null) pool.retire(pool.current);
                pool.current = null;
                pool.closing.forEach(h -> closing.add(h.closed.future()));
            }
        }
        return Future.join(closing).mapEmpty();
    }

    /** The producer of {@code key} at the version of {@code values}, built now. */
    private Held build(String key, String adapter, ConnectionState.Values values, Pool pool) {
        Component component = components.get(adapter);
        Connection connection;
        Outbound out;
        Producer producer;
        Optional<String> target;
        try {
            connection = Serving.connection(key, adapter, component.forms(), Direction.OUT, values.values(), secrets, handed);
            out = new Outbound(Optional.of(connection));
            producer = Objects.requireNonNull(component.producer(out), "the adapter built no producer");
            target = Objects.requireNonNull(component.target(out), "the adapter's target answered null");
        } catch (RuntimeException refused) {
            throw cannot(key, "left out by this node: its producer could not be built (" + refused.getClass().getSimpleName() + ")");
        }
        return new Held(pool, key, values.version(), producer, target);
    }

    /** Retires the producer of each connection that {@code snapshot} removes or holds at another version, within the limit. */
    private void activated(Environment.Snapshot snapshot) {
        pools.forEach((key, pool) -> {
            synchronized (pool) {
                Held current = pool.current;
                if (current == null || pool.closing.size() >= MAX_CLOSING) return;
                Optional<ConnectionState> state = snapshot.connection(key);
                if (state.isPresent() && state.get().version() == current.version) return;
                pool.retire(current);
                pool.current = null;
            }
        });
    }

    private static NackException cannot(String key, String reason) {
        return new NackException("connection '" + key + "' cannot serve the send: " + reason, true);
    }

    /** The producers of one connection: the one in use, and the old ones closing. Guarded by itself. */
    private static final class Pool {
        Held current;
        final Deque<Held> closing = new ArrayDeque<>();
        final List<Promise<Void>> waiting = new ArrayList<>();

        /** Moves {@code held} among those closing; it closes once its last attempt settled. Under the lock. */
        void retire(Held held) {
            if (held.retired) return;
            held.retired = true;
            closing.add(held);
            if (held.open == 0) held.close();
        }

        /** {@code held} closed: it leaves those closing, and every attempt waiting tries again. */
        void closed(Held held) {
            List<Promise<Void>> woken;
            synchronized (this) {
                closing.remove(held);
                woken = new ArrayList<>(waiting);
                waiting.clear();
            }
            woken.forEach(Promise::tryComplete);
        }
    }

    /** One producer of a connection at one version, with the attempts that hold it. Guarded by its pool. */
    private static final class Held {
        final String key;
        final long version;
        final Producer producer;
        final Optional<String> target;
        final Promise<Void> closed = Promise.promise();
        int open;
        boolean retired;
        boolean closing;
        final Pool pool;

        Held(Pool pool, String key, long version, Producer producer, Optional<String> target) {
            this.pool = pool;
            this.key = key;
            this.version = version;
            this.producer = producer;
            this.target = target;
        }

        /** One more attempt holds it. Under the pool's lock. */
        Attempt take() {
            open++;
            AtomicBoolean once = new AtomicBoolean();
            Held self = this;
            return new Attempt() {
                @Override
                public Producer producer() {
                    return producer;
                }

                @Override
                public Optional<String> target() {
                    return target;
                }

                @Override
                public void settled() {
                    if (!once.compareAndSet(false, true)) return;
                    synchronized (pool) {
                        self.release();
                    }
                }
            };
        }

        /** One attempt settled: once retired and settled by all, it closes. */
        void release() {
            open--;
            if (retired && open == 0) close();
        }

        /** Starts the close, once. Under the pool's lock. */
        void close() {
            if (closing) return;
            closing = true;
            Future<Void> done;
            try {
                done = Objects.requireNonNullElse(producer.close(), Future.succeededFuture());
            } catch (RuntimeException thrown) {
                done = Future.failedFuture(thrown);
            }
            done.onComplete(r -> {
                if (r.failed()) LOG.log(System.Logger.Level.WARNING, "connection '" + key + "': an old producer did not close cleanly ("
                        + r.cause().getClass().getSimpleName() + ")");
                closed.tryComplete();
                pool.closed(this);
            });
        }
    }
}
