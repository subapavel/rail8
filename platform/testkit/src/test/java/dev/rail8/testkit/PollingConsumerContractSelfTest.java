package dev.rail8.testkit;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hasher;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Secret;
import dev.rail8.api.Timer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The polling contract is only worth its name if every case fails for the consumer that breaks exactly that property
 * (D-076). One in-memory polling component, correct by default, broken one way per variant, run through the JUnit engine;
 * each variant's exact set of failing cases is pinned, and the check that caught it where a later check could mask it.
 */
class PollingConsumerContractSelfTest {

    static final Set<String> CASES = Set.of("theSchemeIsNamedAndNotThePlatforms", "theFormsAreTheSameOnEveryCallAndServeTheInDirection",
            "theConnectionFormOnlyAddsToTheRecordedOne",
            "everyFieldChoiceAndTabHasALabelAndItsTextKeys", "theInboundPassesTheForms", "noSecretHandleIsReadWhileAConsumerIsBuilt",
            "aSuccessorStartedOnceTheConsumerStoppedDeliversWhatIsLeftAndNothingAcknowledged",
            "aDroppedFileIsDeliveredIntactAndRemovedAfterAccepted", "theFileStaysWhileTheAnswerIsPending",
            "aRetryableNackKeepsTheFileAndTheNextRoundDeliversItAgain", "aRetryableNackEndsTheRound", "aHandlerThatThrowsOrAnswersNullKeepsTheFile",
            "aFinalNackIsNotDeliveredAgain", "filesAreDeliveredOldestFirst", "aReplyFinishesTheFileAsAcceptedDoes",
            "aBodyAboveTheTreeLimitStreamsThroughIntact", "eachDeliveryRunsOnAVirtualThreadOfItsOwn",
            "stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval", "aStoppedConsumerDeliversNothing", "healthIsUpWhileRunningAndDownAfterStop",
            "aPartnerOutOfReachBreaksTheConsumerAndAFreshOneDeliversTheFile");

    static final String DROPPED = "aDroppedFileIsDeliveredIntactAndRemovedAfterAccepted";
    static final String PENDING = "theFileStaysWhileTheAnswerIsPending";
    static final String NACK_KEEPS = "aRetryableNackKeepsTheFileAndTheNextRoundDeliversItAgain";
    static final String NACK_ENDS = "aRetryableNackEndsTheRound";
    static final String BROKEN_HANDLER = "aHandlerThatThrowsOrAnswersNullKeepsTheFile";
    static final String FINAL_NACK = "aFinalNackIsNotDeliveredAgain";
    static final String OLDEST = "filesAreDeliveredOldestFirst";
    static final String REPLY = "aReplyFinishesTheFileAsAcceptedDoes";
    static final String BIG = "aBodyAboveTheTreeLimitStreamsThroughIntact";
    static final String OWN_THREAD = "eachDeliveryRunsOnAVirtualThreadOfItsOwn";
    static final String STOP_WAITS = "stopWaitsForTheDeliveryInFlightAndCompletesItsRemoval";
    static final String STOPPED = "aStoppedConsumerDeliversNothing";
    static final String HEALTH = "healthIsUpWhileRunningAndDownAfterStop";
    static final String PARTNER_DOWN = "aPartnerOutOfReachBreaksTheConsumerAndAFreshOneDeliversTheFile";
    static final String SUCCESSOR = "aSuccessorStartedOnceTheConsumerStoppedDeliversWhatIsLeftAndNothingAcknowledged";
    static final String SCHEME = "theSchemeIsNamedAndNotThePlatforms";
    static final String SAME_FORMS = "theFormsAreTheSameOnEveryCallAndServeTheInDirection";
    static final String INBOUND = "theInboundPassesTheForms";
    static final String RECORDED = "theConnectionFormOnlyAddsToTheRecordedOne";
    static final String SECRET = "noSecretHandleIsReadWhileAConsumerIsBuilt";

    /** The forms of the correct variant: nothing described, the in direction reaching a connection. */
    static final Forms FORMS = new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.CONNECTION));

    /** The inbound every case's consumer is built for: a connection holding a password. */
    static final Inbound INBOX = new Inbound("contract",
            Optional.of(new Connection("partner", Map.of(), Map.of("password", Secret.of("secret://partner.password", () -> "pw"),
                    "login", Secret.credentials("secret://partner.login", () -> new Secret.Credentials("u", "pw")),
                    "key", Secret.keyPair("secret://partner.key", FakeServices::placeholderKeyPair)))), Map.of(), 1L << 26);

    /**
     * A polling component over an in-memory directory, its own fake clock and timer and a BlobStore with a 4 KiB tree limit.
     * The correct one: {@code start()} schedules round 0; the timer only fires a round, which runs on a virtual thread; a
     * round takes the files by (drop order, name), one at a time, writes each body by streaming, calls the handler on a
     * virtual thread of its own and waits for the answer; Accepted or Reply removes the file; a retryable Nack or a failure
     * keeps it and ends the round; a final Nack keeps it and skips it from then on; the next round is scheduled when a
     * round ends. A round while the partner is down breaks the consumer, which schedules no further round. {@code stop()}
     * cancels the next round, takes no new file and completes when the round in flight is over. Each flag breaks one of these.
     */
    static final class Variant implements Component {
        static final Duration INTERVAL = Duration.ofSeconds(10);

        record File(String name, long mtime, Buffer bytes) {}

        final FakeClock clock = FakeClock.at(FakeRuntime.T0);
        final FakeTimer timer = new FakeTimer(clock);
        final InMemoryBlobStore blobs = new InMemoryBlobStore(Hasher.sha256(), 4096);
        private final Map<String, File> dir = new ConcurrentHashMap<>();
        private final AtomicLong mtimes = new AtomicLong();
        private final AtomicLong ids = new AtomicLong();
        private final Set<Poller> running = ConcurrentHashMap.newKeySet();

        String scheme = "polling";
        /** What {@link #forms} answers on each call; the correct variant's are {@link #FORMS}. */
        Supplier<Forms> forms = () -> FORMS;
        /** Broken: building a consumer reads every secret of this kind of its connection; null: none. */
        Secret.Kind readsSecretsAtBuild;
        /** Broken: health reports UP whether or not a consumer runs. */
        boolean healthAlwaysUp;
        /** Broken: files taken in name order, not oldest first. */
        boolean byName;
        /**
         * Broken: the file is removed before its answer is awaited — on the round's thread before it starts the handler's, so
         * the removal happens-before the handler records its call and every look a case takes once it saw that call. Removed
         * after the start, the removal raced the case's look: a slow round thread let the pending-answer checks see the file.
         */
        boolean removedBeforeTheAnswer;
        /** Broken: the file is removed after any answer, a Nack and a failure included. */
        boolean removedOnAnyAnswer;
        /** Broken: a retryable Nack keeps the file but the round goes on to the next one. */
        boolean retryableNackGoesOn;
        /** Broken: a handler that throws, answers null or fails keeps the file but the round goes on. */
        boolean failureGoesOn;
        /** Broken: a finally refused file is delivered again by the next round (the refused set is never read). */
        boolean refusedDeliveredAgain;
        /** Broken: the handler runs on the round's own thread. */
        boolean handlerOnTheRoundsThread;
        /** Broken: the round runs on the thread the timer fires it on. */
        boolean roundOnTheTimersThread;
        /** Broken: stop() completes at once, whatever is in flight, so before the removal of the file in flight (which waits for the stop). */
        boolean stopAtOnce;
        /** Broken: stop() completes once the answer in flight is in, before that file's removal (which waits for the stop). */
        boolean stopBeforeTheRemoval;
        /** Broken: stop() leaves the rounds running: the timer is not cancelled and the rounds deliver on. */
        boolean stopLeavesTheRounds;
        /** Broken: once stop() began, the round in flight still takes its next file. */
        boolean stopTakesTheNextFile;
        /** Broken: a Reply is taken for a retryable Nack: the file stays and the round ends. */
        boolean replyKeepsTheFile;
        /** Whether the partner is down: a round then cannot list the directory. */
        volatile boolean partnerDown;
        /** Broken: a round that cannot reach the partner ends as any other does; the consumer never breaks and polls on. */
        boolean neverBreaks;
        /** Broken: stop() breaks the consumer. */
        boolean stopBreaks;
        /** What the handler sees of a file's bytes; the correct one sees them all. */
        UnaryOperator<Buffer> delivered = b -> b;

        static Supplier<Variant> broken(java.util.function.Consumer<Variant> defect) {
            return () -> {
                Variant v = new Variant();
                defect.accept(v);
                return v;
            };
        }

        void drop(String name, Buffer bytes) { dir.put(name, new File(name, mtimes.incrementAndGet(), bytes.copy())); }

        /** With {@link #stopAtOnce} or {@link #stopBeforeTheRemoval}: counted down by the first look at the directory once a stop has completed. */
        private final CountDownLatch lookedAfterTheStop = new CountDownLatch(1);
        private volatile Poller stoppedOne;

        Set<String> remaining() {
            Set<String> now = Set.copyOf(dir.keySet());
            Poller p = stoppedOne;
            if (p != null && p.stopped.future().isComplete()) lookedAfterTheStop.countDown();
            return now;
        }

        @Override public String scheme() { return scheme; }

        @Override public Forms forms() { return forms.get(); }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
            if (readsSecretsAtBuild != null) in.connection().ifPresent(c -> ComponentContractSelfTest.read(c, readsSecretsAtBuild));
            return new Poller(handler);
        }

        @Override public Producer producer(Outbound out) { throw new IllegalArgumentException("a poller has no producer"); }

        @Override public Future<Health> health() {
            return Future.succeededFuture(healthAlwaysUp || !running.isEmpty() ? Health.up() : Health.down("no consumer polls"));
        }

        final class Poller implements Consumer {
            private final Function<Envelope, Future<Ack>> handler;
            private final Set<String> refused = ConcurrentHashMap.newKeySet();
            private final Promise<Void> stopped = Promise.promise();
            private final Promise<Void> broken = Promise.promise();
            private Timer.Cancellable next;        // guarded by this
            private boolean stopping;              // guarded by this
            private Promise<Void> round;           // the round in flight, guarded by this
            private volatile Future<Ack> answerInFlight;

            Poller(Function<Envelope, Future<Ack>> handler) { this.handler = handler; }

            @Override public Future<Void> start() {
                running.add(this);
                schedule(Duration.ZERO);
                return Future.succeededFuture();
            }

            private synchronized void schedule(Duration delay) {
                if (!stopping && !broken.future().isComplete()) next = timer.schedule(delay, this::fire);
            }

            @Override public Future<Void> broken() { return broken.future(); }

            private void fire() {
                if (roundOnTheTimersThread) round();
                else Thread.ofVirtual().name("variant-round").start(this::round);
            }

            private synchronized boolean stopping() { return stopping; }

            private void round() {
                Promise<Void> mine = Promise.promise();
                synchronized (this) {
                    if (stopping) return;
                    round = mine;
                }
                try {
                    if (partnerDown) {
                        if (!neverBreaks && !stopping()) broken.tryFail(new IllegalStateException("the partner is down"));
                        return;
                    }
                    Comparator<File> order = byName ? Comparator.comparing(File::name)
                            : Comparator.comparingLong(File::mtime).thenComparing(File::name);
                    for (File f : dir.values().stream().sorted(order).toList()) {
                        if (stopping() && !stopTakesTheNextFile) break;
                        String key = f.name() + "@" + f.mtime();
                        if (refused.contains(key) && !refusedDeliveredAgain) continue;
                        if (!take(f, key)) break;
                    }
                } finally {
                    synchronized (this) { round = null; }
                    mine.complete();
                    schedule(INTERVAL);
                }
            }

            /** Delivers {@code f}; whether the round goes on. */
            private boolean take(File f, String key) {
                String id = "v-" + ids.incrementAndGet();
                Body body = blobs.write(BlobKey.body(id, 0), new ChunkedReadStream(List.of(delivered.apply(f.bytes()))), Long.MAX_VALUE).await();
                Promise<Ack> promised = Promise.promise();
                // Published before the handler can run: a stop() issued once the case saw the handler called reads this answer,
                // never null nor the previous file's (the handler's thread may record the call before this thread runs on).
                answerInFlight = promised.future();
                Envelope in = Envelope.ingress(id, "contract", Headers.empty(), body);
                if (removedBeforeTheAnswer) dir.remove(f.name());   // before the handler's thread starts: see the flag
                deliver(in, promised);
                Future<Ack> answer = promised.future();
                Ack ack;
                try {
                    ack = answer.await();
                } catch (Throwable failed) {
                    ack = null;
                }
                if (removedOnAnyAnswer) dir.remove(f.name());
                return switch (ack) {
                    case Ack.Accepted a -> remove(f);
                    case Ack.Reply r when !replyKeepsTheFile -> remove(f);
                    case Ack.Reply r -> false;
                    case Ack.Nack n when !n.retryable() -> {
                        refused.add(key);
                        yield true;
                    }
                    case Ack.Nack n -> retryableNackGoesOn;
                    case null -> failureGoesOn;
                };
            }

            private boolean remove(File f) {
                if ((stopAtOnce || stopBeforeTheRemoval) && stopping()) {   // the stop has completed: the removal waits until the case looked
                    try {
                        if (!lookedAfterTheStop.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("nobody looked after the stop");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                dir.remove(f.name());
                return true;
            }

            /** The handler on a virtual thread of its own, its answer completing {@code answer}; a throw or a null answer fails it. */
            private void deliver(Envelope e, Promise<Ack> answer) {
                Runnable call = () -> {
                    try {
                        Future<Ack> f = handler.apply(e);
                        if (f == null) answer.tryFail(new IllegalStateException("the handler answered null"));
                        else f.onComplete(answer);
                    } catch (Throwable thrown) {
                        answer.tryFail(thrown);
                    }
                };
                if (handlerOnTheRoundsThread) call.run();
                else Thread.ofVirtual().name("variant-handler").start(call);
            }

            @Override public Future<Void> stop() {
                Promise<Void> inFlight;
                if (stopBreaks) broken.tryFail(new IllegalStateException("stopped"));
                synchronized (this) {
                    running.remove(this);
                    if (stopLeavesTheRounds) return Future.succeededFuture();
                    stopping = true;
                    if (next != null) next.cancel();
                    inFlight = round;
                }
                if (inFlight == null) {
                    stopped.tryComplete();
                } else if (stopAtOnce) {
                    stoppedOne = this;
                    stopped.tryComplete();
                } else if (stopBeforeTheRemoval) {
                    stoppedOne = this;
                    answerInFlight.onComplete(done -> stopped.tryComplete());
                } else {
                    inFlight.future().onComplete(done -> stopped.tryComplete());
                }
                return stopped.future();
            }
        }
    }

    /** The engine instantiates the suite itself; the variant under test is handed over through this slot. */
    static volatile Supplier<Variant> current = Variant::new;

    static class VariantPollingContractTest extends PollingConsumerContract {
        private Variant variant;   // one per case: JUnit makes an instance per case

        private Variant variant() {
            if (variant == null) variant = current.get();
            return variant;
        }

        @Override protected Component component() { return variant(); }
        @Override protected Inbound inbound() { return INBOX; }
        @Override protected void drop(String name, ReadStream<Buffer> bytes) { variant().drop(name, bytesOf(bytes)); }
        @Override protected Set<String> remaining() { return variant().remaining(); }
        @Override protected void tick() { advanceToNextRound(variant().clock, variant().timer); }
        @Override protected void partnerDown(boolean down) { variant().partnerDown = down; }
        @Override protected Duration patience() { return Duration.ofSeconds(2); }
    }

    static Map<String, Throwable> failures(Supplier<Variant> variant) {
        current = variant;
        try {
            return ContractRun.failures(VariantPollingContractTest.class);
        } finally {
            current = Variant::new;
        }
    }

    static Set<String> failing(java.util.function.Consumer<Variant> defect) {
        return failures(Variant.broken(defect)).keySet();
    }

    @Test
    void theContractHasExactlyTheseCases() {
        Set<String> declared = new TreeSet<>();
        for (Method m : PollingConsumerContract.class.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) declared.add(m.getName());
        assertEquals(new TreeSet<>(CASES), declared, "a case added or removed changes what the self-test pins");
    }

    @Test
    void theCorrectVariantPassesEveryCase() {
        assertEquals(Map.of(), failures(Variant::new));
    }

    @Test
    void aBlankOrThePlatformsSchemeFailsOnlyItsCase() {
        assertEquals(Set.of(), failing(v -> v.scheme = "other"), "another scheme");
        assertEquals(Set.of(SCHEME), failing(v -> v.scheme = " "));
        assertEquals(Set.of(SCHEME), failing(v -> v.scheme = Forms.PLATFORM));
    }

    @Test
    void aConnectionFormThatDoesNotOnlyAddToTheRecordedOneFailsOnlyItsCase() {
        assertEquals(Set.of(), failing(v -> {
            v.scheme = "partner";
            v.forms = () -> new Forms(RecordedFormTest.partner(true, 22), Settings.NONE, FORMS.reach());
        }), "described as forms/partner.json records it");
        assertEquals(Set.of(RECORDED), failing(v -> {
            v.scheme = "partner";
            v.forms = () -> new Forms(RecordedFormTest.partner(true, 2222), Settings.NONE, FORMS.reach());
        }), "the port's default changed");
        assertEquals(Set.of(RECORDED), failing(v -> v.forms = () -> new Forms(RecordedFormTest.partner(true, 22), Settings.NONE, FORMS.reach())),
                "described, and no form recorded for its scheme");
    }

    @Test
    void formsThatAreWrongFailOnlyTheirCase() {
        assertEquals(Set.of(), failing(v -> v.forms = () -> new Forms(Settings.NONE, Settings.of(Field.text("glob", "Glob", "Which files.").in()), FORMS.reach())),
                "described, and the inbound passes");
        AtomicLong calls = new AtomicLong();
        assertEquals(Set.of(SAME_FORMS),
                failing(v -> v.forms = () -> new Forms(Settings.NONE, Settings.of(Field.text("f" + calls.incrementAndGet(), "Field", "Another field on every call.").in()), FORMS.reach())));
        assertEquals(Set.of(SAME_FORMS, INBOUND), failing(v -> v.forms = () -> new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.OUT, Forms.Reach.CONNECTION))),
                "the in direction is not served, so it reaches no connection either");
        assertEquals(Set.of(INBOUND), failing(v -> v.forms = () -> new Forms(Settings.NONE, Settings.of(Field.text("dir", "Directory", "Where files are.").required().in()), FORMS.reach())),
                "the inbound lacks a required in setting");
        assertEquals(Set.of(INBOUND), failing(v -> v.forms = () -> new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.NO_CONNECTION))),
                "the in direction reaches no connection, but the inbound holds one");
        assertEquals(Set.of(), failing(v -> v.forms = () -> new Forms(Settings.NONE,
                        Settings.of(Field.text("glob", "Glob", "Which files.").in(), Field.text("url", "Address", "Where to send.").required().out()),
                        Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION))),
                "the inbound is an in one: a required out setting is not its business");
    }

    @Test
    void aConsumerThatReadsASecretWhileItIsBuiltFailsOnlyTheSecretCase() {
        for (Secret.Kind kind : Secret.Kind.values()) {
            assertEquals(Set.of(SECRET), failing(v -> v.readsSecretsAtBuild = kind), "the secret of kind " + kind + " is read while the consumer is built");
        }
    }

    @Test
    void healthUpWithoutAConsumerFailsOnlyTheHealthCase() {
        assertEquals(Set.of(HEALTH), failing(v -> v.healthAlwaysUp = true));
    }

    @Test
    void filesTakenInNameOrderFailOnlyTheOldestFirstCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.byName = true));
        assertEquals(Set.of(OLDEST), f.keySet());
        ContractRun.caughtBy(f, OLDEST, "oldest first, whatever the names or the listing order say");
    }

    @Test
    void aRemovalBeforeTheAnswerIsAwaitedFailsEveryCaseThatKeepsAFile() {
        assertEquals(Set.of(PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, STOP_WAITS, STOPPED),
                failing(v -> v.removedBeforeTheAnswer = true));
    }

    @Test
    void aRemovalOnAnyAnswerFailsEveryCaseWhoseAnswerKeepsTheFile() {
        assertEquals(Set.of(PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, STOPPED), failing(v -> v.removedOnAnyAnswer = true));
    }

    @Test
    void aRoundThatGoesOnAfterARetryableNackFailsOnlyTheEndOfRoundCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.retryableNackGoesOn = true));
        assertEquals(Set.of(NACK_ENDS), f.keySet());
        ContractRun.caughtBy(f, NACK_ENDS, "the retryable Nack ended the first round");
    }

    @Test
    void aRoundThatGoesOnAfterAFailedAnswerFailsOnlyTheBrokenHandlerCase() {
        assertEquals(Set.of(BROKEN_HANDLER), failing(v -> v.failureGoesOn = true));
    }

    @Test
    void aRefusedFileDeliveredAgainFailsOnlyTheFinalNackCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.refusedDeliveredAgain = true));
        assertEquals(Set.of(FINAL_NACK), f.keySet());
        ContractRun.caughtBy(f, FINAL_NACK, "it was not delivered again");
        // the round may or may not have reached the younger file when the case looks: the second delivery is the refused one either way
        ContractRun.caughtBy(f, FINAL_NACK, "expected: <[refused, later]> but was: <[refused, refused");
    }

    @Test
    void aHandlerOnTheRoundsThreadFailsOnlyTheOwnThreadCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.handlerOnTheRoundsThread = true));
        assertEquals(Set.of(OWN_THREAD), f.keySet());
        ContractRun.caughtBy(f, OWN_THREAD, "not on one thread for the round");
    }

    @Test
    void aRoundOnTheTimersThreadFailsTheCasesThatHoldAnAnswer() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.roundOnTheTimersThread = true));
        assertEquals(Set.of(PENDING, STOP_WAITS, SUCCESSOR), f.keySet());
        ContractRun.caughtBy(f, PENDING, "the timer's thread runs the round instead of firing it");
        ContractRun.caughtBy(f, STOP_WAITS, "the timer's thread runs the round instead of firing it");
    }

    @Test
    void aStopThatCompletesAtOnceFailsTheStopAndTheSuccessorCases() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopAtOnce = true));
        assertEquals(Set.of(STOP_WAITS, SUCCESSOR), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop waits for the delivery in flight");
    }

    @Test
    void aStopThatCompletesBeforeTheRemovalFailsTheStopAndTheSuccessorCases() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopBeforeTheRemoval = true));
        assertEquals(Set.of(STOP_WAITS, SUCCESSOR), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop completed only after the accepted file was removed");
    }

    @Test
    void aStopThatTakesTheNextFileFailsTheStopAndTheSuccessorCases() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopTakesTheNextFile = true));
        assertEquals(Set.of(STOP_WAITS, SUCCESSOR), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "took no new file once stop began");
    }

    @Test
    void aStopThatLeavesTheRoundsRunningFailsTheStoppedTheStopAndTheSuccessorCases() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopLeavesTheRounds = true));
        assertEquals(Set.of(STOPPED, STOP_WAITS, SUCCESSOR), f.keySet());
        ContractRun.caughtBy(f, STOP_WAITS, "stop waits for the delivery in flight");
    }

    @Test
    void aConsumerThatNeverBreaksFailsOnlyThePartnerCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.neverBreaks = true));
        assertEquals(Set.of(PARTNER_DOWN), f.keySet());
        ContractRun.caughtBy(f, PARTNER_DOWN, "a round that cannot reach the partner breaks the consumer");
    }

    @Test
    void aStopThatBreaksTheConsumerFailsOnlyThePartnerCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.stopBreaks = true));
        assertEquals(Set.of(PARTNER_DOWN), f.keySet());
        ContractRun.caughtBy(f, PARTNER_DOWN, "a consumer that delivered and stopped has not broken");
    }

    @Test
    void aReplyThatKeepsTheFileFailsOnlyTheReplyCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.replyKeepsTheFile = true));
        assertEquals(Set.of(REPLY), f.keySet());
        ContractRun.caughtBy(f, REPLY, "a sync flow's Reply finishes the file as an Accepted does");
    }

    @Test
    void aLargeBodyCutAtTheTreeLimitFailsOnlyTheLargeBodyCase() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> b.length() > 4096 ? b.slice(0, 4096).copy() : b));
        assertEquals(Set.of(BIG), f.keySet());
        ContractRun.caughtBy(f, BIG, "every byte arrived");
    }

    @Test
    void aLargeBodyWithOneByteAlteredFailsOnlyTheLargeBodyCaseAtTheByteCheck() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> {
            if (b.length() <= 4096) return b;
            Buffer altered = b.copy();
            altered.setByte(150_000, (byte) (altered.getByte(150_000) + 1));
            return altered;
        }));
        assertEquals(Set.of(BIG), f.keySet());
        ContractRun.caughtBy(f, BIG, "byte 150000 differs");
    }

    @Test
    void aSmallBodyThatLosesItsLastByteFailsTheIntactCaseAtItsBodyCheck() {
        Map<String, Throwable> f = failures(Variant.broken(v -> v.delivered = b -> b.length() > 4096 ? b : b.slice(0, Math.max(0, b.length() - 1)).copy()));
        assertEquals(Set.of(DROPPED, PENDING, NACK_KEEPS, NACK_ENDS, BROKEN_HANDLER, FINAL_NACK, OLDEST, STOP_WAITS, SUCCESSOR), f.keySet(),
                "every case that tells the files apart by their text");
        ContractRun.caughtBy(f, DROPPED, "the body arrives intact");
    }
}
