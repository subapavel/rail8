package dev.rail8.runtime.store;

import dev.rail8.api.Timer;
import dev.rail8.runtime.pg.PgTx;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The retention job: deletes what is older than the max age, in keyset batches, in the background. A run fixes its
 * cutoffs once — the injected clock minus the max ages, never the server's {@code now()} — and runs seven phases, each in batches of
 * at most {@link Config#batch} scanned rows, one transaction per batch whose first statement tries the advisory lock
 * {@link #LOCK_KEY} without waiting:
 * <ol>
 *   <li>{@code messages} and {@code message_keys}: a message with a scanned row older than the cutoff goes whole — every row of it,
 *       in that batch's transaction, those beyond the scan included — when no row of it is at or after the cutoff and no work row
 *       (held or not) and no DLQ row has its id; the key rows of the deleted rows' {@code keys} go with them. A scanned row whose
 *       message stays is passed by the cursor and counted in {@link Run#kept}.</li>
 *   <li>{@code bodies} and {@code body_refs}, scanned as one keyset: a body or a kept ref older than the cutoff goes
 *       when no messages row and no work row has its message's id and no DLQ row's {@code body_key} names it (orphans included),
 *       and — a kept ref — no work row points at the same place in the journal (a callee's item copying the caller's kept ref);
 *       the bodies and their bytes are counted, the refs are not (the journal's bytes go later, with their segment). The
 *       batch holds its rows for update and passes a row a park holds ({@code skip locked}), and its delete re-checks these
 *       under a snapshot taken after the select.</li>
 *   <li>{@code audit}: a row whose {@code at} is older than the clock minus {@link Config#auditMaxAge}.</li>
 *   <li>{@code sessions}: a session that expired longer ago than the audit's max age (a session that ended otherwise has no
 *       row any more).</li>
 *   <li>{@code tokens}: a token that expired, or was revoked, longer ago than the audit's max age.</li>
 *   <li>{@code devices}: a device of the login limit that expired longer ago than the audit's max age (a device that ended
 *       otherwise has no row any more).</li>
 *   <li>{@code login_limits}: a row without a failure for {@link #LIMITS_KEPT}; its longest wait is far shorter.</li>
 * </ol>
 * A phase ends with the batch that scanned fewer rows than the batch size. The lock found taken ends the run {@link Outcome#SKIPPED}
 * with what the committed batches deleted; so does {@link #close} between two batches. A failed batch is rolled back (unless its
 * commit is what failed), logged as a WARNING with its cause and ends the run {@link Outcome#FAILED}. Every run logs one INFO line with its counts. DLQ rows are never
 * deleted. Correctness never depends on the lock: it only keeps two nodes from doing the same work at once.
 *
 * <p>{@link #start} begins the first run at once and schedules each next one {@link Config#interval} after a run ended, so runs
 * of the schedule never overlap; every run runs on a virtual thread named {@code rail8-retention}, the timer's task only starts
 * it. {@link #runOnce} starts one run outside the schedule.
 *
 * @since 0.1
 */
public final class Retention {

    private static final System.Logger LOG = System.getLogger(Retention.class.getName());

    /** The advisory lock every batch tries first: the ASCII bytes of "rail8" in the high five bytes, zero below. */
    static final long LOCK_KEY = 0x7261696C38000000L;

    private static final String LOCK = "select pg_try_advisory_xact_lock($1)";

    /**
     * The bodies batch's delete: of the rows the batch select holds — {@code bodies} keys {@code $1..$3}, {@code body_refs}
     * keys {@code $4..$6}, as arrays — those with no messages row and no work row for their message and no DLQ row whose
     * {@code body_key} names them, and a ref with no work row pointing at its place in the journal, re-checked under this
     * statement's snapshot — a DLQ row committed since the select is seen; answers the bodies deleted and their bytes.
     */
    private static final String DELETE_BODIES = """
            with gone as (
                delete from bodies b using unnest($1::text[], $2::int[], $3::text[]) as k(message_id, step, part)
                 where b.message_id = k.message_id and b.step = k.step and b.part = k.part
                   and not exists (select 1 from messages m where m.id = b.message_id)
                   and not exists (select 1 from work w where w.id = b.message_id)
                   and not exists (select 1 from dlq d where d.body_key = b.message_id || '/' || b.step || '/' || b.part)
                 returning octet_length(b.bytes) as size),
            refs as (
                delete from body_refs r using unnest($4::text[], $5::int[], $6::text[]) as k(message_id, step, part)
                 where r.message_id = k.message_id and r.step = k.step and r.part = k.part
                   and not exists (select 1 from messages m where m.id = r.message_id)
                   and not exists (select 1 from work w where w.id = r.message_id)
                   and not exists (select 1 from work w where w.body_node = r.node and w.body_segment = r.segment and w.body_position = r.position)
                   and not exists (select 1 from dlq d where d.body_key = r.message_id || '/' || r.step || '/' || r.part)
                 returning 1)
            select count(*), coalesce(sum(size), 0), (select count(*) from refs) from gone""";

    /** The most rows a batch scans: a batch is one transaction holding its row locks. */
    static final int MAX_BATCH = 10_000;

    /** How long a row of the login limit is kept after its last failure. */
    static final Duration LIMITS_KEPT = Duration.ofHours(24);

    /** How long the audit is kept when the config names no age of its own: a year, or the messages' max age when longer. */
    static final Duration AUDIT_DEFAULT = Duration.ofDays(365);

    /**
     * What the job is given: how old a message's row may get, how old an audit row may get, how long after a run ended the next
     * one starts, and how many rows a batch scans.
     *
     * @param maxAge more than zero
     * @param auditMaxAge at least {@code maxAge}: a message's status reads its cancel from the audit
     * @param interval more than zero
     * @param batch 1 to 10000 rows
     * @since 0.1
     */
    public record Config(Duration maxAge, Duration auditMaxAge, Duration interval, int batch) {
        /**
         * Refuses a missing or non-positive duration, an audit kept for less than the messages and a batch outside 1 to 10000
         * rows: nothing unlimited, nothing absent.
         */
        public Config {
            Objects.requireNonNull(maxAge, "maxAge");
            Objects.requireNonNull(auditMaxAge, "auditMaxAge");
            Objects.requireNonNull(interval, "interval");
            if (!maxAge.isPositive()) throw new IllegalArgumentException("the max age must be more than zero, was " + maxAge);
            if (auditMaxAge.compareTo(maxAge) < 0) {
                throw new IllegalArgumentException("the audit's max age may not be shorter than the messages', was " + auditMaxAge + " < " + maxAge);
            }
            if (!interval.isPositive()) throw new IllegalArgumentException("the interval must be more than zero, was " + interval);
            if (batch < 1 || batch > MAX_BATCH) throw new IllegalArgumentException("a batch must be 1 to " + MAX_BATCH + " rows, was " + batch);
        }

        /** The audit kept for {@link #AUDIT_DEFAULT}, or for {@code maxAge} when that is longer. */
        public Config(Duration maxAge, Duration interval, int batch) {
            this(maxAge, maxAge == null || maxAge.compareTo(AUDIT_DEFAULT) < 0 ? AUDIT_DEFAULT : maxAge, interval, batch);
        }
    }

    /** How a run ended. */
    public enum Outcome {
        /** Every phase ran to its last, short batch. */
        DONE,
        /** The lock was taken by another session, or the job closed: the run ended after its last committed batch. */
        SKIPPED,
        /** A batch failed and was rolled back: the run ended there. */
        FAILED
    }

    /**
     * One run.
     *
     * @param cutoff the clock at the run's start minus the max age: rows older than it are deleted
     * @param messages messages rows deleted
     * @param keys message_keys rows deleted
     * @param bodies bodies rows deleted
     * @param bodyBytes the bytes of the deleted bodies
     * @param kept messages rows older than the cutoff the run kept (a newer row of the message, a work or a DLQ row)
     * @param audit audit rows deleted
     * @param sessions expired sessions deleted
     * @param tokens expired or revoked tokens deleted
     * @param devices expired devices deleted
     * @param limits rows of the login limit deleted
     * @param batches transactions the run began, the last, short one of each phase included
     * @since 0.1
     */
    public record Run(Instant cutoff, Outcome outcome, long messages, long keys, long bodies, long bodyBytes, long kept,
                      long audit, long sessions, long tokens, long devices, long limits, int batches) {}

    /**
     * The sums over every run that ended since the job was built.
     *
     * @param runs the runs that ended
     * @param skipped the runs that ended {@link Outcome#SKIPPED}
     * @param failed the runs that ended {@link Outcome#FAILED}
     * @since 0.1
     */
    public record Totals(long runs, long skipped, long failed, long messages, long keys, long bodies, long bodyBytes) {
        private Totals plus(Run r) {
            return new Totals(runs + 1, skipped + (r.outcome() == Outcome.SKIPPED ? 1 : 0), failed + (r.outcome() == Outcome.FAILED ? 1 : 0),
                    messages + r.messages(), keys + r.keys(), bodies + r.bodies(), bodyBytes + r.bodyBytes());
        }
    }

    /**
     * One table's keyset scan: its statement ({@code $1} cutoff, {@code $2} batch, {@code $3..} the cursor; answering rows scanned,
     * rows deleted, the extra count, rows kept, then the cursor — {@link #BODIES} answers its batch's rows instead) and its first
     * cursor.
     */
    private enum Phase {
        MESSAGES("""
                with scan as (
                    select id, step, created_at from messages
                     where created_at < $1 and (created_at, id, step) > (coalesce($3, '-infinity'::timestamptz), $4, $5)
                     order by created_at, id, step limit $2),
                gone as (
                    delete from messages m using (select distinct id from scan) s
                     where m.id = s.id
                       and not exists (select 1 from messages y where y.id = s.id and y.created_at >= $1)
                       and not exists (select 1 from work w where w.id = s.id)
                       and not exists (select 1 from dlq d where d.id = s.id)
                     returning m.id, m.keys),
                keys as (
                    delete from message_keys k using (select distinct g.id, e.key, e.value from gone g, jsonb_each_text(g.keys) e) x
                     where k.name = x.key and k.value = x.value and k.message_id = x.id
                     returning 1)
                select (select count(*) from scan), (select count(*) from gone), (select count(*) from keys),
                       (select count(*) from scan s where not exists (select 1 from gone g where g.id = s.id)), last.created_at, last.id, last.step
                  from (select 1) one left join lateral
                       (select created_at, id, step from scan order by created_at desc, id desc, step desc limit 1) last on true""",
                "", Integer.MIN_VALUE),
        /**
         * Two statements: this one selects the batch from {@code bodies} and {@code body_refs} in one keyset order — the
         * first {@code batch} of both together — and holds only those rows for update (never {@code batch} of each
         * table), passing a row a park holds for key share ({@code skip locked}) — answering created_at, then the key, the
         * cursor's order, then whether the row is a ref; {@link #DELETE_BODIES} then deletes the selected rows that still have
         * no messages row, work row or DLQ row naming them (a ref: nor a work row pointing at its place), under its own, later
         * snapshot. A key in both tables with one
         * created_at is one cursor position: the batch limit may pass its second row to the next run.
         */
        BODIES("""
                with x as materialized (
                    select * from (
                        (select created_at, message_id, step, part, false as ref from bodies
                          where created_at < $1 and (created_at, message_id, step, part) > (coalesce($3, '-infinity'::timestamptz), $4, $5, $6)
                          order by created_at, message_id, step, part limit $2)
                        union all
                        (select created_at, message_id, step, part, true as ref from body_refs
                          where created_at < $1 and (created_at, message_id, step, part) > (coalesce($3, '-infinity'::timestamptz), $4, $5, $6)
                          order by created_at, message_id, step, part limit $2)) both_tables
                     order by created_at, message_id, step, part, ref limit $2),
                b as (
                    select y.created_at, y.message_id, y.step, y.part from bodies y
                      join x on not x.ref and (x.message_id, x.step, x.part) = (y.message_id, y.step, y.part)
                       for update of y skip locked),
                r as (
                    select y.created_at, y.message_id, y.step, y.part from body_refs y
                      join x on x.ref and (x.message_id, x.step, x.part) = (y.message_id, y.step, y.part)
                       for update of y skip locked)
                select created_at, message_id, step, part, ref from (select *, false as ref from b union all select *, true as ref from r) z
                 order by created_at, message_id, step, part, ref""",
                "", Integer.MIN_VALUE, "") {
            @Override
            Future<Batch> run(SqlConnection tx, Tuple params, int cursorColumns) {
                return tx.preparedQuery(sql).execute(params).compose(scan -> {
                    Keys bodies = new Keys();
                    Keys refs = new Keys();
                    Object[] last = new Object[cursorColumns];
                    for (Row r : scan) {
                        (r.getBoolean(4) ? refs : bodies).add(r);
                        for (int i = 0; i < cursorColumns; i++) last[i] = r.getValue(i);
                    }
                    Tuple keys = Tuple.tuple();
                    bodies.into(keys);
                    refs.into(keys);
                    return tx.preparedQuery(DELETE_BODIES).execute(keys).map(gone -> {
                        Row g = gone.iterator().next();
                        return new Batch(scan.size(), g.getLong(0), g.getLong(1), 0, Arrays.asList(last));
                    });
                });
            }
        };

        final String sql;
        /** The cursor before the first row: no created_at (read as -infinity in SQL), then the smallest value of each key column. */
        final List<Object> start;

        Phase(String sql, Object... keyStart) {
            this.sql = sql;
            List<Object> s = new ArrayList<>();
            s.add(null);
            s.addAll(List.of(keyStart));
            this.start = s;
        }

        /** One batch in {@code tx}, the lock already taken: the phase's one statement, its answer row read as a {@link Batch}. */
        Future<Batch> run(SqlConnection tx, Tuple params, int cursorColumns) {
            return tx.preparedQuery(sql).execute(params).map(rows -> Batch.of(rows.iterator().next(), cursorColumns));
        }
    }

    /**
     * A table whose rows go one by one by age: its statement deletes at most {@code $2} rows older than {@code $1} and answers how
     * many it deleted; the age is checked again on the row it deletes, as another transaction may have changed it since.
     */
    private enum Sweep {
        AUDIT("""
                with gone as (
                    delete from audit a using (select id from audit where at < $1 order by at limit $2) x
                     where a.id = x.id
                     returning 1)
                select count(*) from gone"""),
        SESSIONS("""
                with gone as (
                    delete from sessions s using (select id from sessions where expires_at < $1 limit $2) x
                     where s.id = x.id and s.expires_at < $1
                     returning 1)
                select count(*) from gone"""),
        TOKENS("""
                with gone as (
                    delete from tokens t using (select id from tokens where expires_at < $1 or revoked_at < $1 limit $2) x
                     where t.id = x.id and (t.expires_at < $1 or t.revoked_at < $1)
                     returning 1)
                select count(*) from gone"""),
        DEVICES("""
                with gone as (
                    delete from devices d using (select id from devices where expires_at < $1 limit $2) x
                     where d.id = x.id and d.expires_at < $1
                     returning 1)
                select count(*) from gone"""),
        LIMITS("""
                with gone as (
                    delete from login_limits l using (select name, device from login_limits where last_failure_at < $1 limit $2) x
                     where (l.name, l.device) = (x.name, x.device) and l.last_failure_at < $1
                     returning 1)
                select count(*) from gone""");

        final String sql;

        Sweep(String sql) {
            this.sql = sql;
        }

        /** The cutoff of this table in a run that started at {@code now}. */
        Instant cutoff(Instant now, Config config) {
            return this == LIMITS ? now.minus(LIMITS_KEPT) : now.minus(config.auditMaxAge());
        }
    }

    /** The keys of the bodies batch's rows of one table, as the arrays its delete takes. */
    private static final class Keys {
        private final List<String> ids = new ArrayList<>();
        private final List<Integer> steps = new ArrayList<>();
        private final List<String> parts = new ArrayList<>();

        void add(Row r) {
            ids.add(r.getString(1));
            steps.add(r.getInteger(2));
            parts.add(r.getString(3));
        }

        void into(Tuple t) {
            t.addArrayOfString(ids.toArray(String[]::new)).addArrayOfInteger(steps.toArray(Integer[]::new)).addArrayOfString(parts.toArray(String[]::new));
        }
    }

    /** What one batch scanned, deleted and kept, and the cursor after its last scanned row. */
    private record Batch(long scanned, long deleted, long extra, long kept, List<Object> cursor) {
        static Batch of(Row r, int cursorColumns) {
            List<Object> cursor = new ArrayList<>();
            for (int i = 0; i < cursorColumns; i++) cursor.add(r.getValue(4 + i));
            return new Batch(r.getLong(0), r.getLong(1), r.getLong(2), r.getLong(3), cursor);
        }
    }

    /** The lock found taken: the batch's transaction is failed with it so that it rolls back. */
    private static final class Taken extends RuntimeException {
        private Taken() {
            super("the retention lock is taken", null, false, false);
        }
    }

    private final Pool pool;
    private final Clock clock;
    private final Timer timer;
    private final Config config;

    // guarded by this
    private boolean started;
    private volatile boolean closed;
    private Timer.Cancellable next;
    private final Set<Future<Run>> inFlight = new HashSet<>();
    private Totals totals = new Totals(0, 0, 0, 0, 0, 0, 0);

    /** A job over {@code pool}: nothing runs before {@link #start} or {@link #runOnce}. */
    public Retention(Pool pool, Clock clock, Timer timer, Config config) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Begins the first run at once, on a virtual thread, and returns; each run of the schedule schedules the next one an interval
     * after it ended.
     *
     * @throws IllegalStateException started already, or closed
     */
    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("the retention job can start once, before it closed");
        started = true;
        begin(true);
    }

    /**
     * Starts one run on a virtual thread, outside the schedule; the future completes with the run, whatever its outcome. After
     * {@link #close} it fails with {@link IllegalStateException}.
     */
    public Future<Run> runOnce() {
        return begin(false);
    }

    /** The sums over the runs that ended so far. */
    public synchronized Totals totals() {
        return totals;
    }

    /**
     * Cancels the next run of the schedule and lets every run in flight end after its current batch; completes once they ended.
     * Starts nothing any more.
     */
    public Future<Void> close() {
        List<Future<Run>> running;
        synchronized (this) {
            closed = true;
            if (next != null) next.cancel();
            next = null;
            running = List.copyOf(inFlight);
        }
        return Future.join(running).transform(ended -> Future.succeededFuture());
    }

    /** The timer's task: starts the scheduled run, never runs it on the timer's thread. */
    private void scheduled() {
        begin(true);
    }

    private Future<Run> begin(boolean thenSchedule) {
        Promise<Run> done = Promise.promise();
        synchronized (this) {
            if (closed) return Future.failedFuture(new IllegalStateException("the retention job is closed"));
            inFlight.add(done.future());
        }
        Thread.ofVirtual().name("rail8-retention").start(() -> {
            try {
                done.complete(once());
            } finally {
                done.tryFail("the retention run ended without an outcome");   // nothing once completed: an Error escaped once()
                ended(done.future(), thenSchedule);
            }
        });
        return done.future();
    }

    /**
     * A run ended: it is no longer in flight, and a run of the schedule schedules the next one unless the job closed; a timer that
     * refuses the next run is logged as a WARNING — no run follows until the node restarts.
     */
    private synchronized void ended(Future<Run> run, boolean thenSchedule) {
        inFlight.remove(run);
        if (!thenSchedule || closed) return;
        try {
            next = timer.schedule(config.interval(), this::scheduled);
        } catch (RuntimeException refused) {
            LOG.log(System.Logger.Level.WARNING, "the timer refused the next retention run: no run follows until the node restarts", refused);
        }
    }

    /** One run, on the caller's (virtual) thread: its outcome, never a throw for a failed batch. */
    private Run once() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(config.maxAge());
        Tally tally = new Tally();
        Outcome outcome;
        try {
            outcome = phases(cutoff, tally);
            if (outcome == Outcome.DONE) outcome = sweeps(now, tally);
        } catch (Exception failed) {   // a failed batch: the transaction rolled it back, unless its commit is what failed
            LOG.log(System.Logger.Level.WARNING, "a retention batch failed; the run ends FAILED, the next comes on schedule (cutoff "
                    + cutoff + ")", failed);
            outcome = Outcome.FAILED;
        }
        Run run = tally.run(cutoff, outcome);
        synchronized (this) {
            totals = totals.plus(run);
        }
        LOG.log(System.Logger.Level.INFO, "retention run " + outcome + " (cutoff " + cutoff + "): deleted " + run.messages() + " messages rows, "
                + run.keys() + " key rows, " + run.bodies() + " bodies of " + run.bodyBytes() + " bytes, " + run.audit() + " audit rows, "
                + run.sessions() + " expired sessions, " + run.tokens() + " tokens, " + run.devices() + " expired devices, " + run.limits() + " login limit rows; kept " + run.kept() + " messages rows; "
                + run.batches() + " batches");
        return run;
    }

    /** The two phases in order; DONE, or SKIPPED when the lock was taken or the job closed between two batches. */
    private Outcome phases(Instant cutoff, Tally tally) {
        OffsetDateTime before = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        for (Phase phase : Phase.values()) {
            List<Object> cursor = phase.start;
            while (true) {
                if (closed) return Outcome.SKIPPED;
                tally.batches++;
                Batch b;
                try {
                    b = batch(phase, before, cursor);
                } catch (Taken taken) {
                    return Outcome.SKIPPED;
                }
                tally.add(phase, b);
                if (b.scanned() < config.batch()) break;
                cursor = b.cursor();
            }
        }
        return Outcome.DONE;
    }

    /** The tables that go row by row by age, in order; DONE, or SKIPPED when the lock was taken or the job closed between two batches. */
    private Outcome sweeps(Instant now, Tally tally) {
        for (Sweep sweep : Sweep.values()) {
            Tuple params = Tuple.of(OffsetDateTime.ofInstant(sweep.cutoff(now, config), ZoneOffset.UTC), (long) config.batch());
            while (true) {
                if (closed) return Outcome.SKIPPED;
                tally.batches++;
                long deleted;
                try {
                    deleted = PgTx.inTransaction(pool, (SqlConnection tx) -> tx.preparedQuery(LOCK).execute(Tuple.of(LOCK_KEY))
                            .compose(lock -> lock.iterator().next().getBoolean(0)
                                    ? tx.preparedQuery(sweep.sql).execute(params).map(rows -> rows.iterator().next().getLong(0))
                                    : Future.failedFuture(new Taken())))
                            .await();
                } catch (Taken taken) {
                    return Outcome.SKIPPED;
                }
                tally.swept[sweep.ordinal()] += deleted;
                if (deleted < config.batch()) break;
            }
        }
        return Outcome.DONE;
    }

    /** One batch of {@code phase} after {@code cursor}: one transaction, the lock tried first, rolled back when it is taken. */
    private Batch batch(Phase phase, OffsetDateTime cutoff, List<Object> cursor) {
        Tuple params = Tuple.tuple().addValue(cutoff).addValue((long) config.batch());
        cursor.forEach(params::addValue);
        return PgTx.inTransaction(pool, (SqlConnection tx) -> tx.preparedQuery(LOCK).execute(Tuple.of(LOCK_KEY))
                .compose(lock -> lock.iterator().next().getBoolean(0)
                        ? phase.run(tx, params, cursor.size())
                        : Future.failedFuture(new Taken())))
                .await();
    }

    /** A run's counts while it runs, per phase (by ordinal): rows scanned, deleted and kept, and the phase's extra count. */
    private static final class Tally {
        final long[] deleted = new long[Phase.values().length];
        /** Key rows deleted with the messages, the bodies' bytes. */
        final long[] extra = new long[Phase.values().length];
        /** Scanned rows whose message stayed: messages only. */
        final long[] kept = new long[Phase.values().length];
        /** Rows deleted by age, per table (by ordinal). */
        final long[] swept = new long[Sweep.values().length];
        int batches;

        void add(Phase phase, Batch b) {
            deleted[phase.ordinal()] += b.deleted();
            extra[phase.ordinal()] += b.extra();
            kept[phase.ordinal()] += b.kept();
        }

        Run run(Instant cutoff, Outcome outcome) {
            int m = Phase.MESSAGES.ordinal();
            int b = Phase.BODIES.ordinal();
            return new Run(cutoff, outcome, deleted[m], extra[m], deleted[b], extra[b], kept[m], swept[Sweep.AUDIT.ordinal()],
                    swept[Sweep.SESSIONS.ordinal()], swept[Sweep.TOKENS.ordinal()], swept[Sweep.DEVICES.ordinal()],
                    swept[Sweep.LIMITS.ordinal()], batches);
        }
    }
}
