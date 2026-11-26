package dev.rail8.runtime.git;

import dev.rail8.api.Timer;
import dev.rail8.runtime.git.GitFailure.Reason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The node's own check of the pool's git credentials, so that a token that stopped working is seen before a deploy, or a restart
 * with an empty git cache, fails on it. Each check asks the git host for the pool's ref of one repository the node runs, with the
 * pool's credentials, and keeps the outcome: {@link State#WORKING}, {@link State#REFUSED} or {@link State#UNREACHABLE}, with the
 * formatter's text and the time of the check.
 *
 * <p>The first check runs at {@link #start()}, each next one {@code every} after the one before ended, on the node's timer, so two
 * checks never run at once. A change of the credentials ({@link #changed()}) brings the next check forward to the later of now and
 * {@link #AFTER_CHANGE} after the last one ended; further changes before it runs add no other check, and a check that runs is not
 * interrupted: a change while it runs brings the next one to {@link #AFTER_CHANGE} after it ends. So no two checks are closer than
 * {@link #AFTER_CHANGE}, and without a change none closer than {@code every}. A check runs on {@code runner}; it never fails: a
 * failure it did not expect is kept as {@link State#UNREACHABLE}. When the node runs no package from git, a check asks nothing and keeps the
 * outcome it had. A change of state is logged once, with the formatter's text only: a WARNING when the credentials are refused or
 * the host is not reached, INFO when they work again.
 *
 * @since 0.1
 */
public final class CredentialsCheck implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CredentialsCheck.class.getName());

    /** The least time between the end of a check and the extra check a change of the credentials brings. */
    public static final Duration AFTER_CHANGE = Duration.ofMinutes(1);

    /** What the last check found. */
    public enum State {
        /** The host answered the pool's ref with the pool's credentials. */
        WORKING,
        /**
         * The host refused the credentials, or answered that the repository or the pool's ref is not there: a host such as GitHub
         * answers so for a private repository the token no longer reaches.
         */
        REFUSED,
        /** The host was not reached within the check's timeout, or the transport failed. */
        UNREACHABLE
    }

    /**
     * One check's outcome.
     *
     * @param state what it found
     * @param repository the repository it asked about
     * @param reason the formatter's text of the failure; empty when the credentials work
     * @param checkedAt when the check ended, by the node's clock
     */
    public record Outcome(State state, String repository, String reason, Instant checkedAt) {
        public Outcome {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(checkedAt, "checkedAt");
        }
    }

    /** Asks the git host for the pool's ref of a repository, with the pool's credentials, within the check's timeout. */
    @FunctionalInterface
    public interface Probe {
        /** @throws GitFailure when the host refuses, lacks the repository or its ref, or is not reached */
        void check(String repository) throws GitFailure;
    }

    private final Probe probe;
    private final Supplier<Optional<String>> repository;
    private final Duration every;
    private final Clock clock;
    private final Timer timer;
    private final Executor runner;
    private volatile Outcome last;
    // guarded by this
    private boolean closed;
    private boolean started;
    private boolean running;
    /** A change came after the last check started, and no check has started since. */
    private boolean changed;
    /** When the last check ended. */
    private Instant ended;
    private Timer.Cancellable next;
    /** Counts the scheduled checks: one the timer fires runs only while it is still the latest. */
    private long scheduled;

    /**
     * @param probe asks the git host, such as {@link GitCache#check}
     * @param repository the repository to ask about: one the node runs now; empty when it runs none from git
     * @param every {@code RAIL8_GIT_CHECK_EVERY}: the least time between the end of one check and the start of the next
     * @param clock the node's clock, which dates each outcome
     * @param timer the node's timer, on which the checks are scheduled
     * @param runner where a check runs: a virtual thread of its own in a node, since a check blocks
     */
    public CredentialsCheck(Probe probe, Supplier<Optional<String>> repository, Duration every, Clock clock, Timer timer, Executor runner) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.every = Objects.requireNonNull(every, "every");
        if (!every.isPositive()) throw new IllegalArgumentException("every must be positive, was " + every);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Runs the first check now, on the runner, and schedules each next one {@code every} after the one before ended. */
    public void start() {
        synchronized (this) {
            if (closed || started) return;
            started = true;
            running = true;
        }
        runner.execute(this::run);
    }

    /**
     * The credentials changed: the next check comes at the later of now and {@link #AFTER_CHANGE} after the last one ended, or
     * {@link #AFTER_CHANGE} after the one that runs now ends. Changes before that check starts are coalesced into it. Before
     * {@link #start()} and after {@link #close()} it does nothing: the first check reads the credentials set at its start.
     */
    public void changed() {
        synchronized (this) {
            if (closed || !started || changed) return;
            changed = true;
            if (running) return;   // its end schedules the next check
            Instant at = ended.plus(AFTER_CHANGE);
            Instant now = clock.instant();
            if (at.isAfter(now)) {
                schedule(Duration.between(now, at));
                return;
            }
            if (next != null) next.cancel();
            scheduled++;
            begin();
        }
        runner.execute(this::run);
    }

    /** The last check's outcome; empty before the first check that asked the host. */
    public Optional<Outcome> last() {
        return Optional.ofNullable(last);
    }

    /** No further check is scheduled; one running ends on its own timeout. */
    @Override
    public synchronized void close() {
        closed = true;
        if (next != null) next.cancel();
    }

    private void run() {
        try {
            repository.get().ifPresent(this::check);
        } catch (RuntimeException unexpected) {
            LOG.log(System.Logger.Level.WARNING, "the check of the git credentials failed; the next one tries again", unexpected);
        }
        synchronized (this) {
            running = false;
            ended = clock.instant();
            if (!closed) schedule(changed ? AFTER_CHANGE : every);
        }
    }

    /** Schedules the next check {@code delay} from now, in place of the one scheduled. Called holding this. */
    private void schedule(Duration delay) {
        if (next != null) next.cancel();
        long mine = ++scheduled;
        next = timer.schedule(delay, () -> {
            synchronized (this) {
                // a check the timer fired while it was being replaced, or after the close, does not run
                if (closed || mine != scheduled || running) return;
                begin();
            }
            runner.execute(this::run);
        });
    }

    /** A check starts: a change from now on brings another one after it. Called holding this. */
    private void begin() {
        running = true;
        changed = false;
    }

    private void check(String name) {
        Outcome outcome;
        try {
            probe.check(name);
            outcome = new Outcome(State.WORKING, name, "", clock.instant());
        } catch (GitFailure failure) {
            State state = failure.reason() == Reason.CREDENTIALS_REFUSED || failure.reason() == Reason.NOT_FOUND ? State.REFUSED : State.UNREACHABLE;
            // the formatter's timeout names the deploy's setting; the check has a timeout of its own
            String reason = failure.reason() == Reason.TIMED_OUT
                    ? failure.repository() + " at " + failure.target() + ": timed out (RAIL8_GIT_CHECK_TIMEOUT)" : failure.getMessage();
            outcome = new Outcome(state, name, reason, clock.instant());
        } catch (RuntimeException unexpected) {
            outcome = new Outcome(State.UNREACHABLE, name, name + ": the check failed", clock.instant());
        }
        Outcome before = last;
        last = outcome;
        if (before == null ? outcome.state() != State.WORKING : before.state() != outcome.state()) {
            if (outcome.state() == State.WORKING) {
                LOG.log(System.Logger.Level.INFO, "the git credentials work again: " + name + " answered the pool's ref");
            } else if (outcome.state() == State.REFUSED) {
                LOG.log(System.Logger.Level.WARNING, "the git credentials are refused (" + outcome.reason() + "): a deploy, and a node that restarts"
                        + " with an empty git cache, cannot fetch until the secret store's rail8.git works again");
            } else {
                LOG.log(System.Logger.Level.WARNING, "the check of the git credentials did not reach the git host (" + outcome.reason() + "): a deploy,"
                        + " and a node that restarts with an empty git cache, cannot fetch while it is not reached");
            }
        }
    }
}
