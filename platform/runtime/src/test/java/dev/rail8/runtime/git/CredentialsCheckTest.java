package dev.rail8.runtime.git;

import dev.rail8.runtime.git.CredentialsCheck.Outcome;
import dev.rail8.runtime.git.CredentialsCheck.State;
import dev.rail8.runtime.git.GitFailure.Reason;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check of the git credentials on a fake clock and a fake git host: the three outcomes with the formatter's text and their
 * time, a check never closer to the one before than its interval, no check while the node runs no package from git, a change
 * of state logged once, and a change of the credentials bringing one coalesced extra check no sooner than a minute after the last.
 */
class CredentialsCheckTest {

    private static final Duration EVERY = Duration.ofMinutes(15);

    private final FakeClock clock = FakeClock.at(Instant.parse("2026-11-24T08:00:00Z"));
    private final FakeTimer timer = new FakeTimer(clock);
    /** When the fake git host was asked, and about which repository. */
    private final List<Instant> asked = new ArrayList<>();
    private final List<String> repositories = new ArrayList<>();
    /** What the fake git host answers next: null for the refs, else the failure. */
    private final AtomicReference<Reason> answer = new AtomicReference<>();
    private final AtomicReference<Optional<String>> runs = new AtomicReference<>(Optional.of("orders"));
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(CredentialsCheck.class.getName());
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    /** A check over the fake git host whose checks run at once on the timer's thread. */
    private final CredentialsCheck check = new CredentialsCheck(repository -> {
        asked.add(clock.instant());
        repositories.add(repository);
        Reason failure = answer.get();
        if (failure != null) throw new GitFailure(failure, repository, "refs/heads/release");
    }, runs::get, EVERY, clock, timer, Runnable::run);

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void stop() {
        check.close();
        logger.removeHandler(capture);
    }

    @Test
    void keepsEachOfTheThreeOutcomesWithTheFormattersTextAndItsTime() {
        check.start();
        assertEquals(Optional.of(new Outcome(State.WORKING, "orders", "", clock.instant())), check.last(), "the first check runs at the start");

        answer.set(Reason.CREDENTIALS_REFUSED);
        clock.advance(EVERY);
        assertEquals(Optional.of(new Outcome(State.REFUSED, "orders", "orders at refs/heads/release: credentials refused", clock.instant())), check.last());

        answer.set(Reason.NOT_FOUND);
        clock.advance(EVERY);
        assertEquals(new Outcome(State.REFUSED, "orders", "orders at refs/heads/release: not found", clock.instant()), check.last().orElseThrow(),
                "a repository the node runs that the host no longer shows: the token no longer reaches it");

        answer.set(Reason.CREDENTIALS_UNREADABLE);
        clock.advance(EVERY);
        assertEquals(new Outcome(State.REFUSED, "orders", "orders at refs/heads/release: the secret store's rail8.git is unreadable", clock.instant()),
                check.last().orElseThrow(), "no credentials can be sent: the same as refused, not a host out of reach");

        answer.set(Reason.TRANSPORT_FAILED);
        clock.advance(EVERY);
        assertEquals(new Outcome(State.UNREACHABLE, "orders", "orders at refs/heads/release: the transport failed", clock.instant()), check.last().orElseThrow());

        answer.set(Reason.TIMED_OUT);
        clock.advance(EVERY);
        assertEquals(new Outcome(State.UNREACHABLE, "orders", "orders at refs/heads/release: timed out (RAIL8_GIT_CHECK_TIMEOUT)", clock.instant()),
                check.last().orElseThrow(), "the check names its own timeout, not the deploy's");

        answer.set(null);
        clock.advance(EVERY);
        assertEquals(new Outcome(State.WORKING, "orders", "", clock.instant()), check.last().orElseThrow(), "working again");
    }

    @Test
    void neverChecksMoreOftenThanItsInterval() {
        check.start();
        for (int i = 0; i < 4 * 15; i++) clock.advance(Duration.ofMinutes(1));
        clock.advance(Duration.ofSeconds(59));
        assertEquals(5, asked.size(), "one at the start and one each 15 minutes after: " + asked);
        for (int i = 1; i < asked.size(); i++) {
            assertTrue(!Duration.between(asked.get(i - 1), asked.get(i)).minus(EVERY).isNegative(), "two checks closer than the interval: " + asked);
        }
        assertEquals(1, timer.pending(), "one next check is scheduled, never two");
    }

    @Test
    void asksNothingWhileTheNodeRunsNoPackageFromGitAndKeepsTheLastOutcome() {
        runs.set(Optional.empty());
        check.start();
        assertEquals(List.of(), asked);
        assertEquals(Optional.empty(), check.last(), "no outcome before a check asked the host");

        runs.set(Optional.of("billing"));
        answer.set(Reason.CREDENTIALS_REFUSED);
        clock.advance(EVERY);
        assertEquals(List.of("billing"), repositories, "it asks about a repository the node runs now");
        Outcome refused = check.last().orElseThrow();

        runs.set(Optional.empty());
        clock.advance(EVERY);
        assertEquals(1, asked.size());
        assertEquals(Optional.of(refused), check.last(), "the outcome stays what the last check found");
    }

    @Test
    void logsAChangeOfStateOnceWithTheFormattersTextOnly() {
        answer.set(Reason.CREDENTIALS_REFUSED);
        check.start();
        clock.advance(EVERY);
        clock.advance(EVERY);
        List<String> warnings = logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
        assertEquals(1, warnings.size(), "three refusals, one WARNING: " + warnings);
        assertTrue(warnings.getFirst().contains("orders at refs/heads/release: credentials refused"), warnings.getFirst());

        answer.set(null);
        clock.advance(EVERY);
        assertEquals(1, logged.stream().filter(r -> r.getLevel() == Level.INFO && r.getMessage().contains("work again")).count());
    }

    @Test
    void tenChangesWithinAMinuteGiveOneExtraCheckNoSoonerThanAMinuteAfterTheLastEnded() {
        check.start();
        Instant first = clock.instant();
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(5));
            check.changed();
        }
        assertEquals(List.of(first), asked, "no change brings a check sooner than a minute after the last one ended");
        assertEquals(1, timer.pending(), "the changes are coalesced into one next check");
        clock.advance(Duration.ofSeconds(9));
        assertEquals(List.of(first), asked);
        clock.advance(Duration.ofSeconds(1));
        assertEquals(List.of(first, first.plus(CredentialsCheck.AFTER_CHANGE)), asked, "one extra check, a minute after the last one ended");

        clock.advance(EVERY.minus(Duration.ofSeconds(1)));
        assertEquals(2, asked.size(), "the regular checks go on from the end of the extra one");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(3, asked.size());
        assertEquals(1, timer.pending(), "one next check is scheduled, never two");
    }

    @Test
    void aChangeLongAfterTheLastCheckBringsAnExtraCheckAtOnce() {
        check.start();
        clock.advance(Duration.ofMinutes(5));
        check.changed();
        assertEquals(List.of(clock.instant().minus(Duration.ofMinutes(5)), clock.instant()), asked, "the last check ended more than a minute ago");
        for (int i = 0; i < 10; i++) {
            check.changed();
            clock.advance(Duration.ofSeconds(10));
        }
        assertEquals(3, asked.size(), "overwrites in a loop for 100 s give at most one extra check a minute: " + asked);
        for (int i = 1; i < asked.size(); i++) {
            assertTrue(!Duration.between(asked.get(i - 1), asked.get(i)).minus(CredentialsCheck.AFTER_CHANGE).isNegative(), "two checks closer than a minute: " + asked);
        }
    }

    @Test
    void aChangeWhileACheckRunsBringsOneExtraCheckAMinuteAfterItEnds() {
        List<Runnable> queued = new ArrayList<>();
        CredentialsCheck held = new CredentialsCheck(repository -> asked.add(clock.instant()), runs::get, EVERY, clock, timer, queued::add);
        try {
            held.start();
            held.changed();
            held.changed();
            assertEquals(0, timer.pending(), "a check that runs is not interrupted, and no second one starts beside it");
            clock.advance(Duration.ofSeconds(30));
            queued.removeFirst().run();   // the first check runs and ends now
            assertEquals(1, timer.pending());
            clock.advance(CredentialsCheck.AFTER_CHANGE.minus(Duration.ofSeconds(1)));
            assertEquals(List.of(), queued);
            clock.advance(Duration.ofSeconds(1));
            assertEquals(1, queued.size(), "one extra check, a minute after the running one ended");
        } finally {
            held.close();
        }
    }

    @Test
    void aChangeBeforeTheStartOrAfterTheCloseSchedulesNothing() {
        check.changed();
        assertEquals(0, timer.pending(), "the first check, at the start, reads what is set then");
        check.start();
        check.close();
        check.changed();
        assertEquals(0, timer.pending());
        clock.advance(EVERY);
        assertEquals(1, asked.size());
    }

    @Test
    void schedulesNothingOnceClosed() {
        check.start();
        check.close();
        assertEquals(0, timer.pending());
        clock.advance(EVERY);
        assertEquals(1, asked.size());
    }
}
