package dev.rail8.runtime.queue;

import dev.rail8.api.Timer;
import dev.rail8.core.flow.WorkEvents;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The node's {@link WorkSignals} over Postgres {@code LISTEN/NOTIFY} on channel {@value #CHANNEL}, the
 * payloads, windows and wakes of {@link WorkEvents}.
 *
 * <p>Receiving: one dedicated {@link PgConnection} to {@code listen} — never the pool's — runs {@code LISTEN} and hands every
 * notification's payload to {@link WorkEvents#receive}; a payload not of the {@code w1} form is logged at WARNING once per distinct
 * prefix. {@link #start()} completes once its first {@code LISTEN} is acknowledged and the store's look and the configuration's
 * read that acknowledgement started have ended, whatever their outcome. A connection lost (closed by the server, the
 * network, a terminated backend) is reopened on the timer after a backoff — {@value #FIRST_BACKOFF_MILLIS} ms, doubled after each
 * failed try up to {@value #MAX_BACKOFF_MILLIS} ms, tried until {@link #close()}, back to the first once a {@code LISTEN} is
 * acknowledged — and after every {@code LISTEN} acknowledged, the first included, every started worker of the node is woken once:
 * an event missed while nobody listened costs one claim.
 *
 * <p>Sending: an announce — made after the commit it tells of is acknowledged — is sent on the pool in autocommit, never in a
 * transaction: a leading edge as {@code select pg_notify('rail8_work', $1)}, a window's end as one {@code select
 * pg_notify('rail8_work', p) from unnest($1::text[]) p} (one payload alone as the first form). A send that fails logs one WARNING
 * and is dropped, never sent again: the reconciliation covers it. Nothing is sent after {@link #close()}.
 *
 * @since 0.1
 */
public final class PgWorkSignals implements WorkSignals {

    private static final System.Logger LOG = System.getLogger(PgWorkSignals.class.getName());

    /** The channel every node listens and notifies on. */
    public static final String CHANNEL = "rail8_work";
    /** The channel the secret store's writes notify on; this listens on it too and hands each notice on. */
    public static final String STORE_CHANNEL = "rail8_secrets";
    /** The channel the configuration store's writes notify on; this listens on it too and hands each notice on. */
    public static final String CONFIG_CHANNEL = "rail8_config";
    /** The one query the listening connection sends: a LISTEN on each channel. */
    public static final String LISTEN = "LISTEN " + CHANNEL + "; LISTEN " + STORE_CHANNEL + "; LISTEN " + CONFIG_CHANNEL;
    /** The first reconnect's delay. */
    static final long FIRST_BACKOFF_MILLIS = 100;
    /** The longest delay between two reconnect tries. */
    static final long MAX_BACKOFF_MILLIS = 5_000;

    private static final String NOTIFY = "select pg_notify('" + CHANNEL + "', $1)";
    private static final String NOTIFY_EACH = "select pg_notify('" + CHANNEL + "', p) from unnest($1::text[]) p";

    private final Vertx vertx;
    private final PgConnectOptions listen;
    private final Supplier<Future<Void>> storeNotice;
    private final Supplier<Future<Void>> configNotice;
    private final Pool pool;
    private final Workers workers;
    private final Timer timer;
    private final WorkEvents events;

    private final Object lock = new Object();
    private PgConnection listening;          // guarded by lock: the connection whose LISTEN was acknowledged, null: none now
    private Timer.Cancellable retry;         // guarded by lock: the reconnect armed, null: none
    private long backoff = FIRST_BACKOFF_MILLIS;   // guarded by lock: the next reconnect's delay
    private boolean closed;                  // guarded by lock

    /**
     * Node {@code node}'s signals: listening on a connection of its own to {@code listen}, sending on {@code pool}, waking
     * {@code workers}; windows and reconnects on {@code timer}. Connects nowhere before {@link #start()}.
     */
    public PgWorkSignals(Vertx vertx, PgConnectOptions listen, Pool pool, String node, Workers workers, Timer timer) {
        this(vertx, listen, pool, node, workers, timer, Future::succeededFuture);
    }

    /**
     * As the constructor above, and {@code storeNotice} called on each notification on {@value #STORE_CHANNEL} and after every
     * {@code LISTEN} acknowledged, the first included: a notice missed while nobody listened costs one look at the store. It
     * answers its look, which completes once the look ended.
     */
    public PgWorkSignals(Vertx vertx, PgConnectOptions listen, Pool pool, String node, Workers workers, Timer timer,
                         Supplier<Future<Void>> storeNotice) {
        this(vertx, listen, pool, node, workers, timer, storeNotice, Future::succeededFuture);
    }

    /**
     * As the constructor above, and {@code configNotice} called on each notification on {@value #CONFIG_CHANNEL} and after every
     * {@code LISTEN} acknowledged, the first included: a change of the configuration missed while nobody listened costs one read.
     * It answers its read, which completes once the read ended.
     */
    public PgWorkSignals(Vertx vertx, PgConnectOptions listen, Pool pool, String node, Workers workers, Timer timer,
                         Supplier<Future<Void>> storeNotice, Supplier<Future<Void>> configNotice) {
        this.storeNotice = Objects.requireNonNull(storeNotice, "storeNotice");
        this.configNotice = Objects.requireNonNull(configNotice, "configNotice");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.listen = Objects.requireNonNull(listen, "listen");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.events = new WorkEvents(node, workers, timer, this::send);
    }

    /**
     * Opens the dedicated connection and listens; completes once the {@code LISTEN} is acknowledged and the store's look and the
     * configuration's read it started have ended, a failed one included (each reports its own failure); fails when the
     * {@code LISTEN} could not be acknowledged. A start-up statement the caller sends after this does not meet those two in the
     * pool's queue.
     */
    public Future<Void> start() {
        return open().compose(this::listening);
    }

    /**
     * Sends nothing from now on (what a window kept is dropped), arms no reconnect and closes the dedicated connection; completes
     * once it closed. The pool is the caller's: close this first.
     */
    public Future<Void> close() {
        PgConnection conn;
        synchronized (lock) {
            closed = true;
            conn = listening;
            listening = null;
            if (retry != null) retry.cancel();
            retry = null;
        }
        events.close();
        return conn == null ? Future.succeededFuture() : conn.close();
    }

    @Override public void announce(String targetNode, String flow) {
        events.announce(targetNode, flow);
    }

    /** A connection of its own that listens: the notifications handed on; one whose LISTEN failed is closed and the failure answered. */
    private Future<PgConnection> open() {
        return PgConnection.connect(vertx, listen).compose(conn -> {
            conn.notificationHandler(n -> {
                if (STORE_CHANNEL.equals(n.getChannel())) {
                    var _ = storeNotice.get();   // the look reports its own failure
                } else if (CONFIG_CHANNEL.equals(n.getChannel())) {
                    var _ = configNotice.get();   // the read reports its own failure
                } else {
                    received(n.getPayload());
                }
            });
            return conn.query(LISTEN).execute().transform(ack -> ack.succeeded() ? Future.succeededFuture(conn)
                    : conn.close().transform(gone -> Future.failedFuture(ack.cause())));
        });
    }

    /**
     * {@code conn}'s LISTEN was acknowledged: its loss reconnects from now on, every worker is woken once, and the store looks and
     * the configuration is read once — unless this was closed meanwhile, then it is closed. Completes once that look and that read
     * have ended, whatever their outcome; at once when this was closed. The close handler is set in the task that saw the
     * acknowledgement: no close lands in between.
     */
    private Future<Void> listening(PgConnection conn) {
        boolean late;
        synchronized (lock) {
            late = closed;
            if (!late) {
                listening = conn;
                backoff = FIRST_BACKOFF_MILLIS;
            }
        }
        if (late) {
            conn.close();
            return Future.succeededFuture();
        }
        conn.closeHandler(gone -> lost());
        workers.wakeAll();   // what was announced while nobody listened
        Future<Void> looked = storeNotice.get();   // what the store was told while nobody listened
        Future<Void> read = configNotice.get();   // what the configuration's writers told while nobody listened
        return Future.join(looked, read).transform(ended -> Future.succeededFuture());
    }

    /** The dedicated connection closed: a reconnect armed, unless this is closed (its own close). */
    private void lost() {
        long delay;
        synchronized (lock) {
            if (closed) return;
            listening = null;
            delay = armRetry();
        }
        LOG.log(System.Logger.Level.WARNING, "the work events' listening connection was lost: reconnecting in " + delay + " ms");
    }

    /** Under the lock: the reconnect armed at the current backoff, the next one's doubled up to the bound; answers the delay. */
    private long armRetry() {
        long delay = backoff;
        retry = timer.schedule(Duration.ofMillis(delay), this::reconnect);
        backoff = Math.min(MAX_BACKOFF_MILLIS, delay * 2);
        return delay;
    }

    /** The armed reconnect fired; one firing as this closes opens a connection {@link #listening} closes again. */
    private void reconnect() {
        synchronized (lock) {
            retry = null;
        }
        open().onComplete(this::listening, failure -> {
            long delay;
            synchronized (lock) {
                if (closed) return;
                delay = armRetry();
            }
            LOG.log(System.Logger.Level.WARNING, "the work events' listening connection could not be reopened: trying again in " + delay
                    + " ms: " + failure, failure);
        });
    }

    private void received(String payload) {
        events.receive(payload).ifPresent(prefix -> LOG.log(System.Logger.Level.WARNING,
                "a work event of an unknown form was ignored (prefix " + prefix + "): " + payload));
    }

    /** One send on the pool, autocommit; a failure logged once and dropped. */
    private void send(List<String> payloads) {
        Future<?> sent = payloads.size() == 1 ? pool.preparedQuery(NOTIFY).execute(Tuple.of(payloads.getFirst()))
                : pool.preparedQuery(NOTIFY_EACH).execute(Tuple.of(payloads.toArray(String[]::new)));
        sent.onFailure(failure -> LOG.log(System.Logger.Level.WARNING, "work events were not sent and are dropped (the reconciliation covers them): "
                + payloads + ": " + failure, failure));
    }
}
