package dev.rail8.testkit;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.Deadline;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hash;
import dev.rail8.api.Hasher;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Timer;
import dev.rail8.api.Props;
import dev.rail8.api.Secret;
import dev.rail8.api.Views;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * The contract suite is only worth its name if every case fails for the component that breaks exactly that property.
 * One broken variant per property, run through the JUnit engine, asserting the exact set of failing cases.
 */
class ComponentContractSelfTest {

    static final Inbound LOOP = new Inbound(ComponentContract.FLOW, Optional.empty(), Map.of(), ComponentContract.MAX_BYTES);

    /** The loopback's forms: nothing described, no direction reaching a connection. */
    static final Forms LOOP_FORMS = forms(Forms.Reach.NO_CONNECTION, Settings.NONE);

    /** Forms over an undescribed connection form and {@code flow}, both directions reaching as {@code reach} says. */
    static Forms forms(Forms.Reach reach, Settings flow) {
        return new Forms(Settings.NONE, flow, Map.of(Direction.IN, reach, Direction.OUT, reach));
    }

    /**
     * A loopback component with one thing wrong, chosen by the variant. The correct one's {@code stop()} unregisters the
     * handler at once and completes when the handler answers still outstanding have completed (D-056); a Nack fails the
     * send with a {@link NackException} carrying the reason and the retryable flag; each delivery calls the handler on a
     * virtual thread of its own (D-057).
     */
    static final class Variant implements Component {
        final String scheme;
        final boolean handlerSurvivesStop;
        final boolean healthAlwaysUp;
        final boolean ackBeforeHandler;
        final boolean nackIsSuccess;
        final UnaryOperator<Envelope> deliveredAs;
        final UnaryOperator<Envelope> repliedAs;
        /** Holds the handler run of the message {@code first} until the run of {@code second} has returned (its delivery is recorded by then). */
        static final class Reversal {
            private final String first;
            private final String second;
            private final java.util.concurrent.CountDownLatch secondReturned = new java.util.concurrent.CountDownLatch(1);
            private final java.util.concurrent.atomic.AtomicBoolean heldUntilTheSecond = new java.util.concurrent.atomic.AtomicBoolean();

            Reversal(String first, String second) { this.first = first; this.second = second; }

            boolean concerns(Envelope e) { return e.id().equals(first) || e.id().equals(second); }

            /** True once the first run was held and then released by the second's return — the order was forced, not left to chance. */
            boolean forced() { return heldUntilTheSecond.get(); }

            void beforeTheRun(Envelope e) throws InterruptedException {
                if (!e.id().equals(first)) return;
                if (!secondReturned.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("the run of " + second + " never returned: " + first + " not held");
                heldUntilTheSecond.set(true);
            }

            void afterTheRun(Envelope e) { if (e.id().equals(second)) secondReturned.countDown(); }
        }
        private record Running(Function<Envelope, Future<Ack>> handler, ConsumerDrain drain) {}
        /** The running consumers by the {@code address} of their inbound's settings ({@code ""} without one). */
        private final Map<String, Running> handlers = new ConcurrentHashMap<>();
        /** The consumer started last and still running. */
        private volatile Running last;
        /** Broken: a send reaches the consumer started last, whatever the {@code address} of its call. */
        boolean ignoresTheCall;
        /** Broken: building a consumer or a producer reads every secret of its connection. */
        boolean readsSecretsAtBuild;
        /** Broken: a producer answers every reply send with the first reply it ever got. */
        boolean firstReplyForEverySend;
        /** Broken: closing a producer ends every producer built before the close. */
        boolean closeEndsEveryProducer;
        private final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        /** Broken stop: completes at once and fails every send still waiting for its answer (the answer is dropped). */
        boolean stopDropsPendingAnswers;
        /** How a Nack fails the send; the correct variant reports a {@link NackException} with reason and flag. */
        Function<Ack.Nack, Throwable> nackAs = n -> new NackException(n.reason(), n.retryable());
        /** Where the handler runs; {@code null} (the correct variant) is a virtual thread of its own per delivery. */
        Executor handlerThread;
        /** Broken: the handler runs on the sender's thread, inside {@code send()}. */
        boolean handlerOnTheSendersThread;
        /** FIX-221: when set, the handler run of the delivery with {@link Reversal#first} waits until the one of {@link Reversal#second} has returned. */
        Reversal reversal;
        /** Broken: a handler that throws or answers null leaves the answer pending (its own thread dies with the exception). */
        boolean handlerFailureLost;
        /**
         * Broken (D-066): the sender's side re-stores each reply here — under the plain next-step key, (id, step + 1, body),
         * whatever the attempt (the reply writer before run-scoped keys), or under the envelope's next key but without the
         * sender's props ({@link #restoredWithoutProps}); {@code null} (the correct variant) returns the reply as is.
         */
        BlobStore restoredReplies;
        /** With {@link #restoredReplies} (FIX-53): the key each reply is re-stored under; {@code null} = as {@link #restoredWithoutProps} says. */
        Function<Envelope, BlobKey> restoredKeyOf;
        /** Broken (FIX-53): given the envelope sent and the handler's reply, a non-null answer fails the send with it. */
        BiFunction<Envelope, Envelope, Throwable> replyRefused;
        /** With {@link #restoredReplies}: keyed by {@link Envelope#nextKey()}, the attempt kept, but the sender's props dropped. */
        boolean restoredWithoutProps;
        private final Set<Promise<Envelope>> waiting = ConcurrentHashMap.newKeySet();
        /** P2-15b: the timer its sends are cut on — the case's, handed over by {@link VariantContractTest#component(Timer)}. */
        volatile Timer timer;
        /** Broken (P2-15b): the partner receives no {@link Envelope#H_MESSAGE_ID}. */
        boolean messageIdNotCarried;
        /** Broken (P2-15b): the id is set only when no {@link Envelope#H_MESSAGE_ID} was forwarded — a forwarded one goes as it is. */
        boolean forwardedMessageIdKept;
        /** Broken (P2-15b): a send with no time left is delivered all the same. */
        boolean startsWithNoTimeLeft;
        /** Broken (P2-15b): a send is never cut, however long its partner takes. */
        boolean neverCut;
        /** Broken (P2-15b): cut at the deadline the send started with, never re-read — a renewal is ignored. */
        boolean cutWithoutRereading;
        /** Broken: {@code broken()} fails on {@code stop()}. */
        boolean stopBreaks;
        /** Broken: {@code broken()} fails once the handler refused a message. */
        boolean nackBreaks;
        /** Broken: a consumer built with the handler of an earlier one starts but takes nothing. */
        boolean handlerServedOnce;
        private final Set<Function<Envelope, Future<Ack>>> served = ConcurrentHashMap.newKeySet();
        /** How a cut fails the send; the correct variant: a retryable {@link NackException}. */
        java.util.function.Supplier<Throwable> cutAs = () -> new NackException("deadline passed: the send was cut", true);

        /** What {@link #target} answers (D-099); the correct variant names no target. */
        Function<Outbound, java.util.Optional<String>> targetOf = out -> java.util.Optional.empty();

        @Override public java.util.Optional<String> target(Outbound out) { return targetOf.apply(out); }

        /** What {@link #forms} answers on each call; the correct variant's are {@link #LOOP_FORMS}. */
        java.util.function.Supplier<Forms> forms = () -> LOOP_FORMS;

        @Override public Forms forms() { return forms.get(); }
        /** What {@link #httpPrefix} answers on each call; the correct variant declares none. */
        java.util.function.Supplier<java.util.Optional<String>> prefix = java.util.Optional::empty;
        @Override public java.util.Optional<String> httpPrefix() { return prefix.get(); }

        /** {@link #forms} answers {@code forms} on every call. */
        static Variant formed(Forms forms) { Variant v = correct(); v.forms = () -> forms; return v; }

        /** {@link #target} answers {@code targetOf}. */
        static Variant targeting(Function<Outbound, java.util.Optional<String>> targetOf) { Variant v = correct(); v.targetOf = targetOf; return v; }

        static Variant ignoresTheCall() { Variant v = correct(); v.ignoresTheCall = true; return v; }
        static Variant firstReplyForEverySend() { Variant v = correct(); v.firstReplyForEverySend = true; return v; }
        static Variant closeEndsEveryProducer() { Variant v = correct(); v.closeEndsEveryProducer = true; return v; }

        /** The correct variant whose directions reach a connection, as {@link ConnectedContractTest} hands one over. */
        static Variant connected() { return formed(ComponentContractSelfTest.forms(Forms.Reach.CONNECTION, Settings.NONE)); }

        /** {@link #connected} that reads the connection's secrets while it builds a consumer or a producer. */
        static Variant readsSecretsAtBuild() { Variant v = connected(); v.readsSecretsAtBuild = true; return v; }

        static Variant stopBreaks() { Variant v = correct(); v.stopBreaks = true; return v; }
        static Variant nackBreaks() { Variant v = correct(); v.nackBreaks = true; return v; }
        static Variant handlerServedOnce() { Variant v = correct(); v.handlerServedOnce = true; return v; }

        static Variant messageIdNotCarried() { Variant v = correct(); v.messageIdNotCarried = true; return v; }
        static Variant forwardedMessageIdKept() { Variant v = correct(); v.forwardedMessageIdKept = true; return v; }
        static Variant startsWithNoTimeLeft() { Variant v = correct(); v.startsWithNoTimeLeft = true; return v; }
        static Variant neverCut() { Variant v = correct(); v.neverCut = true; return v; }
        static Variant cutWithoutRereading() { Variant v = correct(); v.cutWithoutRereading = true; return v; }
        static Variant cutAs(java.util.function.Supplier<Throwable> failure) { Variant v = correct(); v.cutAs = failure; return v; }

        /** The envelope as the partner receives it: its message id carried, unless the variant breaks that. */
        private Envelope carried(Envelope e) {
            if (messageIdNotCarried) return e;
            if (forwardedMessageIdKept && e.headers().asMap().keySet().stream().anyMatch(k -> k.equalsIgnoreCase(Envelope.H_MESSAGE_ID))) return e;
            return BoundedSends.carryingItsId(e);
        }

        /** Broken (D-099): the target depends on whether a consumer runs — not answered from the config alone. */
        static Variant targetWhileRunning() {
            Variant v = correct();
            v.targetOf = out -> java.util.Optional.of(v.handlers.isEmpty() ? "loopback://x:1" : "loopback://running:1");
            return v;
        }

        Variant(String scheme, boolean handlerSurvivesStop, boolean healthAlwaysUp, boolean ackBeforeHandler, boolean nackIsSuccess,
                UnaryOperator<Envelope> deliveredAs, UnaryOperator<Envelope> repliedAs) {
            this.scheme = scheme; this.handlerSurvivesStop = handlerSurvivesStop; this.healthAlwaysUp = healthAlwaysUp;
            this.ackBeforeHandler = ackBeforeHandler; this.nackIsSuccess = nackIsSuccess; this.deliveredAs = deliveredAs; this.repliedAs = repliedAs;
        }

        static Variant correct() { return new Variant("loopback", false, false, false, false, e -> e, e -> e); }

        /** {@code stop()} completes at once and drops the answers still pending: their sends fail. */
        static Variant stopDropsPendingAnswers() { Variant v = correct(); v.stopDropsPendingAnswers = true; return v; }

        /** A Nack reaches the sender with {@code nackAs}. */
        static Variant nackReportedAs(Function<Ack.Nack, Throwable> nackAs) { Variant v = correct(); v.nackAs = nackAs; return v; }

        /** The handler runs inside {@code send()}, on the sender's thread (the mock and RecordingComponent before D-057). */
        static Variant handlerOnTheSendersThread() { Variant v = correct(); v.handlerOnTheSendersThread = true; return v; }

        /**
         * Acknowledges before the handler answered (as {@code ackBeforeHandler}) and forces the two handler runs of
         * {@code aSendCarriesTheMessageIdToThePartner} into the opposite order of its sends: the plain send's run is held
         * until the bounded send's handler has returned, i.e. has recorded its delivery.
         */
        static Variant ackFirstWithTheHandlersReversed() {
            Variant v = new Variant("loopback", false, false, true, false, e -> e, e -> e);
            v.reversal = new Reversal("contract-plain-1", "contract-bounded-2");
            return v;
        }

        /** The handler runs on {@code where} instead of a virtual thread of its own. */
        static Variant handlerOn(Executor where) { Variant v = correct(); v.handlerThread = where; return v; }

        /** The handler runs on a virtual thread of its own, but what it throws — a null answer included — never reaches the answer. */
        static Variant handlerFailureLost() { Variant v = correct(); v.handlerFailureLost = true; return v; }

        /** Every reply re-stored under the plain next-step key of the envelope sent, the reply's headers kept. */
        static Variant replyStoredUnderThePlainKey() { Variant v = correct(); v.restoredReplies = new InMemoryBlobStore(dev.rail8.api.Hasher.sha256(), 1 << 20); return v; }

        /** Every reply re-stored under the key {@code keyOf} gives the envelope sent, the sender's props carried on (FIX-53). */
        static Variant replyStoredUnder(Function<Envelope, BlobKey> keyOf) { Variant v = replyStoredUnderThePlainKey(); v.restoredKeyOf = keyOf; return v; }

        /** Every reply re-stored under the envelope's next key, but in {@code store} (FIX-53). */
        static Variant replyStoredIn(BlobStore store) { Variant v = correct(); v.restoredReplies = store; v.restoredKeyOf = Envelope::nextKey; return v; }

        /** The handler's reply fails the send with what {@code refused} answers for it (FIX-53). */
        static Variant replyRefused(BiFunction<Envelope, Envelope, Throwable> refused) { Variant v = correct(); v.replyRefused = refused; return v; }

        /** The reply comes back as {@code repliedAs} makes it (FIX-53). */
        static Variant repliedAs(UnaryOperator<Envelope> repliedAs) {
            return new Variant("loopback", false, false, false, false, e -> e, repliedAs);
        }

        /** Every reply re-stored under the next key of the envelope sent — its attempt's — but carrying none of the sender's props. */
        static Variant replyStoredWithoutTheProps() { Variant v = replyStoredUnderThePlainKey(); v.restoredWithoutProps = true; return v; }

        /** The handler sees the envelope without its props — the attempt included — and its reply comes back as it keyed it. */
        static Variant propsLost() {
            return new Variant("loopback", false, false, false, false,
                    e -> new Envelope(e.id(), e.flow(), e.step(), e.headers(), Props.empty(), e.keys(), e.body(), e.attachments(), e.views()), e -> e);
        }

        /** The reply's bytes stored for {@code sent}'s next step, the reply's headers kept: what the sender gets back. */
        private Future<Envelope> restored(Envelope sent, Envelope reply) {
            BlobKey key = restoredKeyOf != null ? restoredKeyOf.apply(sent)
                    : restoredWithoutProps ? sent.nextKey() : BlobKey.body(sent.id(), sent.step() + 1);
            Props props = restoredWithoutProps ? Props.empty() : sent.props();
            return restoredReplies.write(key, reply.body().whole(), 1 << 20)
                    .map(body -> new Envelope(sent.id(), sent.flow(), sent.step() + 1, reply.headers(), props, sent.keys(), body, Map.of(), new Views()));
        }

        /** Calls the handler the way the variant does; the drain counts the answer before the handler runs. */
        private Future<Ack> dispatch(Running at, Envelope e) {
            if (reversal != null && reversal.concerns(e)) {
                Promise<Ack> answer = Promise.promise();
                at.drain().track(answer.future());
                Thread.ofVirtual().name("variant-reversed-handler-" + e.id()).start(() -> {
                    try {
                        reversal.beforeTheRun(e);
                        at.handler().apply(e).onComplete(answer);
                        reversal.afterTheRun(e);
                    } catch (Throwable failed) {
                        answer.tryFail(failed);
                    }
                });
                return answer.future();
            }
            if (handlerOnTheSendersThread) return at.drain().track(Future.future(p -> at.handler().apply(e).onComplete(p)));   // a throw fails the answer
            if (handlerFailureLost) {
                Promise<Ack> answer = Promise.promise();
                at.drain().track(answer.future());
                Thread.ofVirtual().name("variant-handler-failure-lost").start(() -> at.handler().apply(e).onComplete(answer));
                return answer.future();
            }
            if (handlerThread == null) return at.drain().deliver(at.handler(), e);
            Promise<Ack> answer = Promise.promise();
            at.drain().track(answer.future());
            handlerThread.execute(() -> {
                try {
                    at.handler().apply(e).onComplete(answer);
                } catch (Throwable failed) {
                    answer.tryFail(failed);
                }
            });
            return answer.future();
        }

        @Override public String scheme() { return scheme; }
        private static String addressOf(Map<String, Object> settings) {
            return String.valueOf(settings.getOrDefault("address", ""));
        }

        private void readSecrets(Optional<Connection> connection) {
            if (readsSecretsAtBuild) connection.ifPresent(c -> c.secrets().values().forEach(ComponentContractSelfTest::read));
        }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> h) {
            readSecrets(in.connection());
            String address = addressOf(in.settings());
            Promise<Void> broken = Promise.promise();
            Function<Envelope, Future<Ack>> told = !nackBreaks ? h : e -> {
                Future<Ack> answer = h.apply(e);
                if (answer != null) answer.onSuccess(a -> { if (a instanceof Ack.Nack) broken.tryFail(new IllegalStateException("a message was refused")); });
                return answer;
            };
            Running mine = new Running(told, new ConsumerDrain());
            return new Consumer() {
                @Override public Future<Void> start() {
                    if (handlerServedOnce && !served.add(h)) return Future.succeededFuture();   // registers nothing
                    handlers.put(address, mine);
                    last = mine;
                    return Future.succeededFuture();
                }
                @Override public Future<Void> broken() { return broken.future(); }
                @Override public Future<Void> stop() {
                    if (stopBreaks) broken.tryFail(new IllegalStateException("stopped"));
                    if (!handlerSurvivesStop) {
                        handlers.remove(address, mine);
                        if (last == mine) last = null;
                    }
                    if (!stopDropsPendingAnswers) return mine.drain().stop();
                    waiting.forEach(p -> p.tryFail(new IllegalStateException("consumer stopped: answer dropped")));
                    return Future.succeededFuture();
                }
            };
        }
        @Override public Producer producer(Outbound out) {
            readSecrets(out.connection());
            int built = closes.get();
            AtomicReference<Envelope> firstReply = new AtomicReference<>();
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e, Call call, Deadline d) {
                    if (startsWithNoTimeLeft && !d.remaining().isPositive()) return send(e, call);
                    if (neverCut) return BoundedSends.within(d, null, () -> send(e, call));
                    return BoundedSends.within(d, timer, () -> send(e, call), !cutWithoutRereading, cutAs);
                }

                @Override public Future<Envelope> send(Envelope e, Call call) {
                    if (closeEndsEveryProducer && closes.get() != built) return Future.failedFuture(new IllegalStateException("closed by another producer's close"));
                    Running at = ignoresTheCall ? last : handlers.get(addressOf(call.settings()));
                    if (at == null) return Future.failedFuture(new IllegalStateException("no consumer running"));
                    Future<Ack> ack = dispatch(at, deliveredAs.apply(carried(e)));
                    if (ackBeforeHandler) return Future.succeededFuture(e);
                    Promise<Envelope> answer = Promise.promise();
                    waiting.add(answer);
                    answer.future().onComplete(done -> waiting.remove(answer));
                    ack.<Envelope>compose(a -> switch (a) {
                        case Ack.Reply r when replyRefused != null && replyRefused.apply(e, r.reply()) != null ->
                                Future.failedFuture(replyRefused.apply(e, r.reply()));
                        case Ack.Reply r when firstReplyForEverySend -> {
                            firstReply.compareAndSet(null, r.reply());
                            yield Future.succeededFuture(firstReply.get());
                        }
                        case Ack.Reply r -> restoredReplies == null ? Future.succeededFuture(repliedAs.apply(r.reply()))
                                : restored(e, repliedAs.apply(r.reply()));
                        case Ack.Accepted ok -> Future.succeededFuture(e);
                        case Ack.Nack n -> nackIsSuccess ? Future.succeededFuture(e) : Future.failedFuture(nackAs.apply(n));
                    }).onComplete(done -> { if (done.succeeded()) answer.tryComplete(done.result()); else answer.tryFail(done.cause()); });
                    return answer.future();
                }
                @Override public Future<Void> close() {
                    closes.incrementAndGet();
                    return Future.succeededFuture();
                }
            };
        }
        @Override public Future<Health> health() { return Future.succeededFuture(healthAlwaysUp || !handlers.isEmpty() ? Health.up() : Health.down("no consumer")); }
    }

    /** The engine instantiates the suite itself; the variant under test is handed over through this slot. */
    static volatile Component current = Variant.correct();

    static class VariantContractTest extends ComponentContract {
        @Override protected Component component() { return current; }
        @Override protected Component component(Timer timer) {
            if (current instanceof Variant v) v.timer = timer;
            return current;
        }
        @Override protected Inbound inbound() { return LOOP; }
    }

    /** The suite with two receivers, told apart by the {@code address} of the inbound's settings and of the call's. */
    static class TwoReceiversContractTest extends VariantContractTest {
        @Override protected Inbound inbound() { return inbound(0); }
        @Override protected Inbound inbound(int n) { return new Inbound(FLOW, Optional.empty(), Map.of("address", "r" + n), MAX_BYTES); }
        @Override protected Call call(Consumer started) { return call(0, started); }
        @Override protected Call call(int n, Consumer started) { return new Call(FLOW, "send", Map.of("address", "r" + n), MAX_BYTES, Optional.empty()); }
    }

    /** The suite over a connection that holds a secret: for {@link Variant#connected} and its broken kin. */
    static class ConnectedContractTest extends VariantContractTest {
        @Override protected Inbound inbound() {
            Connection loop = new Connection("loop", Map.of(), Map.of("password", Secret.of("secret://loop.password", () -> "pw"),
                    "login", Secret.credentials("secret://loop.login", () -> new Secret.Credentials("u", "pw")),
                    "key", Secret.keyPair("secret://loop.key", FakeServices::placeholderKeyPair)));
            return new Inbound(FLOW, Optional.of(loop), Map.of(), MAX_BYTES);
        }
    }

    /** The same suite with a second of patience: a handler that waits in vain for the other delivery gives up sooner. */
    static class ImpatientVariantContractTest extends VariantContractTest {
        @Override protected Duration patience() { return Duration.ofSeconds(1); }
    }

    static final Inbound EPHEMERAL = new Inbound(ComponentContract.FLOW, Optional.empty(), Map.of(), ComponentContract.MAX_BYTES);   // no address before start

    /** An address assigned at start, but the suite never asks the started consumer for it: every producer is built for no connection. */
    static class EphemeralWithoutOverrideContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(); }
        @Override protected Component component(Timer timer) { return new EphemeralAddressComponent(timer); }
        @Override protected Inbound inbound() { return EPHEMERAL; }
        @Override protected Duration patience() { return Duration.ofMillis(200); }   // a handler never invoked is waited out
    }

    /** The producer reaches the started consumer's address, but {@link Consumer#stop()} leaves the handler registered there. */
    static class EphemeralStopKeepsTheAddressContractTest extends ComponentContract {
        @Override protected Component component() { return new EphemeralAddressComponent(true); }
        @Override protected Component component(Timer timer) { return new EphemeralAddressComponent(true, timer); }
        @Override protected Inbound inbound() { return EPHEMERAL; }
        @Override protected Outbound outbound(Consumer started) { return EphemeralAddressContractTest.reaching(started); }
    }

    static Set<String> failing(Component variant) {
        current = variant;
        return failing(VariantContractTest.class);
    }

    static Set<String> failingImpatiently(Component variant) {
        current = variant;
        return failing(ImpatientVariantContractTest.class);
    }

    static Set<String> failing(Class<? extends ComponentContract> suite) {
        Events events = EngineTestKit.engine("junit-jupiter").selectors(selectClass(suite)).execute().testEvents();
        assertEquals(0, events.skipped().count(), "nothing skipped");
        Set<String> failed = new TreeSet<>();
        events.failed().stream().forEach(e -> failed.add(e.getTestDescriptor().getDisplayName().replace("()", "")));
        return failed;
    }

    @Test
    void theCorrectLoopbackPassesEveryCase() {
        assertEquals(Set.of(), failing(Variant.correct()));
    }

    /** The cases a component that acknowledges before its handler answered fails — wherever its handler runs land. */
    static Set<String> ackFirstFails() {
        return Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged", "aNackFromTheHandlerFailsTheSendWithItsReason",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable", THROWS, NULL_ANSWER, ATTEMPT_REPLY, CUT,
                        FRESH, TWO_CALLS, TWO_PRODUCERS);
    }

    static final String SAME_FORMS = "theFormsAreTheSameOnEveryCallAndServeBothDirections";
    static final String USES = "theUsesPassTheForms";

    @Test
    void formsThatAreWrongFailOnlyTheirCase() {
        assertEquals(Set.of(), failing(Variant.formed(forms(Forms.Reach.NO_CONNECTION, Settings.of(Field.text("dir", "Directory", "Where files go."))))),
                "described, and the uses pass");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Variant unstable = Variant.correct();
        unstable.forms = () -> forms(Forms.Reach.NO_CONNECTION, Settings.of(Field.text("f" + calls.incrementAndGet(), "Field", "Another field on every call.")));
        assertEquals(Set.of(SAME_FORMS), failing(unstable), "other forms on every call");
        assertEquals(Set.of(SAME_FORMS), failing(Variant.formed(new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.NO_CONNECTION)))),
                "the out direction is not served");
        assertEquals(Set.of(USES), failing(Variant.formed(forms(Forms.Reach.NO_CONNECTION, Settings.of(Field.text("dir", "Directory", "Where files go.").required().in())))),
                "the inbound lacks a required setting");
        assertEquals(Set.of(USES), failing(Variant.formed(forms(Forms.Reach.NO_CONNECTION, Settings.of(Field.text("path", "Path", "Where to send.").required().out())))),
                "the call lacks a required setting");
        assertEquals(Set.of(USES), failing(Variant.formed(forms(Forms.Reach.CONNECTION, Settings.NONE))),
                "both directions reach a connection, but the suite hands none over");
        current = Variant.correct();
    }

    static final String SECRET = "noSecretHandleIsReadWhileAConsumerOrAProducerIsBuilt";

    /** Reads {@code handle} as its kind when it is credentials or a key pair: a typed read counts as any other. */
    static void read(Secret handle) {
        switch (handle.kind()) {
            case VALUE -> { }
            case CREDENTIALS -> handle.credentials();
            case KEY_PAIR -> handle.keyPair();
        }
    }

    @Test
    void aComponentThatReadsASecretWhileItBuildsFailsOnlyTheSecretCase() {
        current = Variant.connected();
        assertEquals(Set.of(), failing(ConnectedContractTest.class), "over a connection with a secret, the correct variant passes");
        current = Variant.readsSecretsAtBuild();
        assertEquals(Set.of(SECRET), failing(ConnectedContractTest.class), "the password is read while the consumer and the producer are built");
        current = Variant.correct();
    }

    static final String TWO_CALLS = "twoCallsAtOnceThroughOneProducerEachReachTheirReceiverAndGetTheirOwnAnswer";
    static final String TWO_PRODUCERS = "twoProducersOfOneConnectionWorkSideBySideAndClosingTheFirstLeavesTheSecond";

    @Test
    void aProducerThatIgnoresTheCallFailsOnlyTheTwoCallsCaseOfTwoReceivers() {
        current = Variant.correct();
        assertEquals(Set.of(), failing(TwoReceiversContractTest.class), "the correct variant reaches each receiver by the call's address");
        current = Variant.ignoresTheCall();
        assertEquals(Set.of(), failing(VariantContractTest.class), "with one receiver the call decides nothing");
        current = Variant.ignoresTheCall();
        assertEquals(Set.of(TWO_CALLS), failing(TwoReceiversContractTest.class), "both calls reach the receiver started last");
        current = Variant.correct();
    }

    @Test
    void aProducerThatAnswersEverySendWithItsFirstReplyFailsTheTwoCallsCase() {
        assertTrue(failing(Variant.firstReplyForEverySend()).contains(TWO_CALLS), "the second call gets the first call's answer");
        current = Variant.correct();
    }

    @Test
    void aCloseThatEndsTheOtherProducersFailsOnlyTheTwoProducersCase() {
        assertEquals(Set.of(TWO_PRODUCERS), failing(Variant.closeEndsEveryProducer()), "closing the first producer ends the second");
        current = Variant.correct();
    }

    @Test
    void aPrefixOfTheWrongFormUnstableOrWithoutTheAddressFailsOnlyItsCase() {
        String prefixCase = "anHttpPrefixIsOfItsFormTheSameOnEveryCallAndItsFlowFormListsTheAddress";
        for (String bad : java.util.List.of("loop", "/Loop", "/a/b", "/rail8")) {
            Variant v = Variant.correct();
            v.prefix = () -> java.util.Optional.of(bad);
            assertEquals(Set.of(prefixCase), failing(v), bad);
        }
        Variant unstable = Variant.correct();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        unstable.prefix = () -> java.util.Optional.of("/p" + calls.incrementAndGet());
        assertEquals(Set.of(prefixCase), failing(unstable), "another prefix on every call");
        Variant answersNull = Variant.correct();
        answersNull.prefix = () -> null;
        assertEquals(Set.of(prefixCase), failing(answersNull), "null instead of an Optional");
        Variant noAddress = Variant.formed(forms(Forms.Reach.NO_CONNECTION, Settings.of(Field.text("dir", "Directory", "Where files go."))));
        noAddress.prefix = () -> java.util.Optional.of("/loop");
        assertEquals(Set.of(prefixCase), failing(noAddress), "a prefix, and no address in the flow form");
        Variant withAddress = Variant.formed(forms(Forms.Reach.NO_CONNECTION, Settings.of(dev.rail8.api.plugin.HttpIngress.ADDRESS)));
        withAddress.prefix = () -> java.util.Optional.of("/loop");
        assertEquals(Set.of(USES), failing(withAddress), "a prefix and the address: only the inbound lacks the address it requires");
    }

    @Test
    void eachBrokenPropertyFailsExactlyItsCases() {
        Variant ok = Variant.correct();
        assertEquals(Set.of(), failing(new Variant("other", false, false, false, false, e -> e, e -> e)), "another scheme");
        assertEquals(Set.of(SCHEME), failing(new Variant(" ", false, false, false, false, e -> e, e -> e)), "blank scheme");
        assertEquals(Set.of(SCHEME), failing(new Variant(Forms.PLATFORM, false, false, false, false, e -> e, e -> e)), "the platform's scheme");
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped", "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender",
                        "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(new Variant("loopback", true, false, false, false, e -> e, e -> e)), "the handler survives stop: delivers after stop and reports up");
        assertEquals(Set.of("healthIsUpWhileAConsumerRunsAndDownAfterItStopped"), failing(new Variant("loopback", false, true, false, false, e -> e, e -> e)), "health always up");
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", FRESH),
                failing(new Variant("loopback", false, false, false, false, e -> e,
                        e -> e.withHeader("x-seen", "dropped").withHeader("x-acked", "dropped").withHeader("x-answer", "dropped"))), "reply dropped (headers replaced)");
        assertEquals(ackFirstFails(),
                failing(new Variant("loopback", false, false, true, false, e -> e, e -> e)), "acknowledged before the handler answered (the envelope sent comes back)");
        assertEquals(Set.of(), failing(new Variant("loopback", false, false, false, false, e -> e.withId("receiver-minted"), e -> e)),
                "a receiver that mints its own id (every real transport does) passes: the id is not what crosses");
        assertEquals(Set.of("aNackFromTheHandlerFailsTheSendWithItsReason", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(new Variant("loopback", false, false, false, true, e -> e, e -> e)), "a NACK treated as success");
        Body truncated = Body.of(FakeRuntime.create().blobs(), dev.rail8.api.BlobKey.body("t", 0), new Hash("sha256-64", 0), 0);
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain",
                        FRESH, TWO_CALLS, TWO_PRODUCERS),
                failing(new Variant("loopback", false, false, false, false, e -> e.next(truncated), e -> e)), "the body does not arrive intact");
        current = Variant.correct();
    }

    @Test
    void aStopThatDropsTheAnswersInFlightFailsBothStopCases() {
        assertEquals(Set.of("stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.stopDropsPendingAnswers()), "stop completes at once and the pending sends fail: neither waits for the delivery in flight");
        current = Variant.correct();
    }

    @Test
    void aCancelThatReachesTheSenderAsAnythingButARetryableNackExceptionWithItsReasonFailsTheCancelCase() {
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new NackException(n.reason(), false))), "the retryable flag is lost on the way");
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new IllegalStateException("nack: " + n.reason()))), "not reported as a NackException");
        assertEquals(Set.of("aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(Variant.nackReportedAs(n -> new NackException("nack: " + n.reason().replace("cancelled", "canceled"), n.retryable()))),
                "the reason does not travel intact (the existing NACK case only looks for its own marker)");
        current = Variant.correct();
    }

    static final String FRESH = "aFreshConsumerOfTheSameEndpointServesOnceTheLastStopped";
    static final String SCHEME = "theSchemeIsNamedAndNotThePlatforms";
    static final String BROKEN = "brokenStaysPendingWhileTheConsumerWorksAndThroughItsStop";

    @Test
    void aConsumerThatBreaksOnItsStopOrOnARefusalFailsOnlyTheBrokenCase() {
        assertEquals(Set.of(BROKEN), failing(Variant.stopBreaks()), "a stop is not a break");
        assertEquals(Set.of(BROKEN), failing(Variant.nackBreaks()), "a refused message is that message's failure, not a break");
        current = Variant.correct();
    }

    @Test
    void aHandlerServedOnlyOnceFailsOnlyTheFreshConsumerCase() {
        assertEquals(Set.of(FRESH), failing(Variant.handlerServedOnce()), "the node builds a fresh consumer with the same handler after a break");
        current = Variant.correct();
    }

    static final String OWN_THREAD = "theHandlerRunsOnAVirtualThreadOfItsOwnPerDelivery";
    static final String THROWS = "aHandlerThatThrowsFailsTheSendAndStopStillCompletes";
    static final String NULL_ANSWER = "aHandlerThatAnswersNullFailsTheSendAndStopStillCompletes";
    static final String ATTEMPT_REPLY = "aReplyOfASecondAttemptIsKeyedByItsAttempt";

    @Test
    void aReplyStoredUnderThePlainKeyWhateverTheAttemptFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.replyStoredUnderThePlainKey()),
                "every attempt's reply under (id, step + 1, body): the second is not keyed by its attempt and overwrites the first's bytes");
        current = Variant.correct();
    }

    @Test
    void aReplyKeyedByItsAttemptThatDropsTheSendersPropsFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.replyStoredWithoutTheProps()),
                "the keys and the bytes are right, but the reply does not carry the attempt on: the steps after it would write under plain keys");
        current = Variant.correct();
    }

    @Test
    void aDeliveryThatLosesThePropsOfAReplyReturnedAsItIsFailsOnlyTheAttemptCase() {
        assertEquals(Set.of(ATTEMPT_REPLY), failing(Variant.propsLost()),
                "the handler never sees the attempt and the reply comes back as the handler keyed it: the plain key for both attempts");
        current = Variant.correct();
    }

    @Test
    void aHandlerCalledOnTheSendersThreadFailsOnlyTheOwnThreadCase() {
        assertEquals(Set.of(OWN_THREAD), failing(Variant.handlerOnTheSendersThread()),
                "the handler runs inside send(): virtual (the senders are), one per delivery, but the sender's own thread");
        current = Variant.correct();
    }

    @Test
    void aHandlerCalledOnAPlatformThreadPerDeliveryFailsOnlyTheOwnThreadCase() {
        assertEquals(Set.of(OWN_THREAD), failing(Variant.handlerOn(r -> Thread.ofPlatform().daemon().name("variant-platform-handler").start(r))),
                "a thread of its own per delivery, but a platform thread");
        current = Variant.correct();
    }

    @Test
    void handlersSharingOneVirtualThreadFailOnlyTheOwnThreadCase() {
        try (ExecutorService one = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("variant-shared-handler").factory())) {
            assertEquals(Set.of(OWN_THREAD), failingImpatiently(Variant.handlerOn(one)),
                    "virtual and never the sender's, but one thread for every delivery: the second waits until the first has answered");
        }
        current = Variant.correct();
    }

    @Test
    void aHandlerFailureThatNeverReachesTheAnswerFailsExactlyTheBrokenHandlerCases() {
        assertEquals(Set.of(THROWS, NULL_ANSWER), failingImpatiently(Variant.handlerFailureLost()),
                "a thrown exception or a null answer kills the handler's thread and leaves the send and the stop pending");
        current = Variant.correct();
    }

    @Test
    void anAddressAssignedAtStartIsReachedOnlyThroughTheStartedConsumersOutbound() {
        assertEquals(Set.of("producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact", "theSendCompletesOnlyAfterTheHandlerAcknowledged",
                        "aNackFromTheHandlerFailsTheSendWithItsReason", "aBodyAboveTheTreeLimitStreamsThroughIntact", "aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain",
                        "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender", "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable", OWN_THREAD,
                        THROWS, NULL_ANSWER, ATTEMPT_REPLY, MESSAGE_ID, NO_TIME_LEFT, CUT, FRESH, BROKEN, USES, TWO_CALLS, TWO_PRODUCERS),
                failing(EphemeralWithoutOverrideContractTest.class),
                "without outbound(started) the producer has no connection and so no address: the uses fail the forms, and every case that needs a delivery fails");
    }

    @Test
    void aStoppedConsumerWhoseAddressStaysRegisteredFailsTheStoppedHealthAndBothStopCases() {
        assertEquals(Set.of("aStoppedConsumerReceivesNothing", "healthIsUpWhileAConsumerRunsAndDownAfterItStopped", "stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender",
                        "aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable"),
                failing(EphemeralStopKeepsTheAddressContractTest.class),
                "stop() leaves the address registered: the producer built from where the consumer was still delivers, also while a delivery is in flight; health stays up");
    }

    // ---- FIX-53: the attempt case, check by check — each defect is caught by the check that names it ------------------------

    static Map<String, Throwable> failures(Component variant) {
        current = variant;
        try {
            return ContractRun.failures(VariantContractTest.class);
        } finally {
            current = Variant.correct();
        }
    }

    /**
     * A reply store whose object key drops the part: every attempt's reply of a step lands in one slot, while the body handed
     * back still names the key asked for. {@code firstWins}: a slot already written keeps its bytes.
     */
    static final class PartBlindStore implements BlobStore {
        private final InMemoryBlobStore slots = new InMemoryBlobStore(Hasher.sha256(), 1 << 20);
        private final boolean firstWins;

        PartBlindStore(boolean firstWins) { this.firstWins = firstWins; }

        private static BlobKey slot(BlobKey key) { return BlobKey.body(key.messageId(), key.step()); }

        @Override public long treeLimit() { return slots.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            if (firstWins && slots.contains(slot(key))) {
                return slots.read(slot(key)).map(kept -> Body.of(this, key, Hasher.sha256().hashOf(kept), kept.length()));
            }
            return slots.write(slot(key), whole, maxSize).map(b -> Body.of(this, key, b.hash(), b.size()));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            return Future.failedFuture(new UnsupportedOperationException("replies are re-stored whole"));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return slots.open(slot(key)); }

        @Override public Future<Buffer> read(BlobKey key) { return slots.read(slot(key)); }

        @Override public Future<Void> persist(BlobKey key) { return slots.persist(slot(key)); }
    }

    @Test
    void aReplyWithABodyOfItsOwnThatFailsTheSendIsCaughtByTheFirstAttemptsAnswerCheck() {
        Map<String, Throwable> f = failures(Variant.replyRefused((sent, reply) -> reply.body().hash().equals(sent.body().hash()) ? null
                : new IllegalStateException("only echoes pass")));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "the first attempt's send is answered: java.lang.IllegalStateException: only echoes pass");
    }

    @Test
    void aRerunsSendThatFailsIsCaughtByTheSecondAttemptsAnswerCheckOnly() {
        Map<String, Throwable> f = failures(Variant.replyRefused((sent, reply) -> sent.get(Envelope.ATTEMPT).isPresent()
                ? new IllegalStateException("a rerun is refused") : null));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "the second attempt's send is answered: java.lang.IllegalStateException: a rerun is refused");
    }

    @Test
    void aFirstAttemptsReplyUnderAnAttemptSuffixIsCaughtByThePlainPartCheckOnly() {
        Map<String, Throwable> f = failures(Variant.replyStoredUnder(sent ->
                new BlobKey(sent.id(), sent.step() + 1, BlobKey.BODY + "~" + sent.get(Envelope.ATTEMPT).orElse(1))));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "a first attempt's reply under the plain part, as before D-066");
    }

    @Test
    void aSecondAttemptsReplyUnderThePlainKeyIsCaughtByTheAttemptPartCheck() {
        Map<String, Throwable> f = failures(Variant.replyStoredUnderThePlainKey());
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "the second attempt's reply under its attempt");
    }

    @Test
    void aSecondAttemptsReplyOverwritingTheFirstsBytesIsCaughtByTheFirstsBytesCheck() {
        Map<String, Throwable> f = failures(Variant.replyStoredIn(new PartBlindStore(false)));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "the second attempt's reply left the first's bytes in place ==> expected: <reply-1> but was: <reply-2>");
    }

    @Test
    void aSecondAttemptsReplyLosingItsBytesToTheFirstsIsCaughtByTheSecondsBytesCheck() {
        Map<String, Throwable> f = failures(Variant.replyStoredIn(new PartBlindStore(true)));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtExactlyBy(f, ATTEMPT_REPLY, "org.opentest4j.AssertionFailedError: expected: <reply-2> but was: <reply-1>");
    }

    @Test
    void aFirstAttemptsReplyThatGainsAnAttemptIsCaughtByTheNoAttemptCheck() {
        Map<String, Throwable> f = failures(Variant.repliedAs(e -> e.get(Envelope.ATTEMPT).isPresent() ? e : e.with(Envelope.ATTEMPT, 1)));
        assertEquals(Set.of(ATTEMPT_REPLY), f.keySet());
        ContractRun.caughtBy(f, ATTEMPT_REPLY, "the first attempt's reply runs on as no attempt in particular");
    }

    static final String MESSAGE_ID = "aSendCarriesTheMessageIdToThePartner";

    /** FIX-221: the contract case reads each delivery by what it is, not by its place — the ids it carries do not depend on which handler ran first. */
    @Test
    void anAckFirstPartnerWhoseSecondHandlerRunsFirstFailsOnlyWhatAnyAckFirstPartnerFails() {
        Variant reversed = Variant.ackFirstWithTheHandlersReversed();
        assertEquals(ackFirstFails(), failing(reversed), "the ids are carried correctly: the order of the handler runs decides nothing");
        assertTrue(reversed.reversal.forced(),
                "the plain send's handler run was held until the bounded send's had returned: the deliveries were recorded in the opposite order");
        current = Variant.correct();
    }
    static final String NO_TIME_LEFT = "aSendWithNoTimeLeftIsNotStarted";
    static final String CUT = "aSendIsCutWhenItsDeadlineRunsOutButNotWhileItIsRenewed";

    // ---- P2-15b: the message id on the wire and the send's deadline — each defect fails exactly its case ----------------------

    @Test
    void aPartnerThatReceivesNoMessageIdFailsOnlyTheMessageIdCase() {
        Map<String, Throwable> f = failures(Variant.messageIdNotCarried());
        assertEquals(Set.of(MESSAGE_ID), f.keySet());
        ContractRun.caughtBy(f, MESSAGE_ID, "the plain send carries its message id, the forwarded one replaced");
    }

    @Test
    void aForwardedMessageIdSentAsItIsFailsOnlyTheMessageIdCase() {
        Map<String, Throwable> f = failures(Variant.forwardedMessageIdKept());
        assertEquals(Set.of(MESSAGE_ID), f.keySet());
        ContractRun.caughtBy(f, MESSAGE_ID, "expected: <[contract-plain-1]> but was: <[forwarded-from-upstream]>");
    }

    @Test
    void aSendStartedWithNoTimeLeftFailsOnlyTheNoTimeCase() {
        Map<String, Throwable> f = failures(Variant.startsWithNoTimeLeft());
        assertEquals(Set.of(NO_TIME_LEFT), f.keySet());
        ContractRun.caughtBy(f, NO_TIME_LEFT, "PT0S left: the send fails");
    }

    @Test
    void aSendNeverCutFailsOnlyTheCutCase() {
        Map<String, Throwable> f = failures(Variant.neverCut());
        assertEquals(Set.of(CUT), f.keySet());
        ContractRun.caughtBy(f, CUT, "still pending after PT10S");   // the cut's check waits a patience for the cut that never comes
    }

    @Test
    void aCutThatIgnoresARenewalFailsOnlyTheCutCase() {
        Map<String, Throwable> f = failures(Variant.cutWithoutRereading());
        assertEquals(Set.of(CUT), f.keySet());
        ContractRun.caughtBy(f, CUT, "renewed: not cut at the deadline it was sent with");
    }

    @Test
    void aCutThatFailsWithAnythingButARetryableNackFailsOnlyTheCutCase() {
        Map<String, Throwable> f = failures(Variant.cutAs(() -> new IllegalStateException("cut")));
        assertEquals(Set.of(CUT), f.keySet());
        ContractRun.caughtBy(f, CUT, "a cut send fails with a NackException");
        Map<String, Throwable> g = failures(Variant.cutAs(() -> new NackException("cut", false)));
        assertEquals(Set.of(CUT), g.keySet());
        ContractRun.caughtBy(g, CUT, "retryable: the message may be tried again");
    }

    static final String TARGET = "targetIsAnsweredFromTheOutboundAloneAndStable";

    /** A well-formed target answered from the outbound alone passes the target case; each malformed or unstable answer fails exactly it. */
    @Test
    void theTargetCaseFailsAMalformedOrUnstableTarget() {
        assertEquals(Set.of(), failing(Variant.targeting(out -> java.util.Optional.of("loopback://x:1"))), "scheme://host:port passes");
        assertEquals(Set.of(), failing(Variant.targeting(out -> java.util.Optional.of("loopback://[::1]:7"))), "a bracketed IPv6 literal passes");
        Variant derived = Variant.connected();
        derived.targetOf = out -> out.connection().map(c -> "loopback://" + c.key() + ":0");
        current = derived;
        assertEquals(Set.of(), failing(ConnectedContractTest.class), "derived from the connection passes");

        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(Set.of(TARGET), failing(Variant.targeting(out -> java.util.Optional.of("loopback://x:" + asked.incrementAndGet()))), "another answer each time");
        AtomicReference<Outbound> first = new AtomicReference<>();
        assertEquals(Set.of(TARGET), failing(Variant.targeting(out -> java.util.Optional.of(first.compareAndSet(null, out) || first.get() == out ? "loopback://x:1" : "loopback://copy:1"))),
                "answered by the outbound object's identity, not its value");
        assertEquals(Set.of(TARGET), failing(Variant.targetWhileRunning()), "depends on a running consumer");
        assertEquals(Set.of(TARGET), failing(Variant.targeting(out -> null)), "null instead of an Optional");
        for (String malformed : java.util.List.of("loopback://x:1/in", "loopback://x:1?q=1", "loopback://x:1#f", "loopback://u@x:1", "loopback://u:pw@x:1",
                "loopback://X:1", "LOOPBACK://x:1", "loopback://x", "loopback://x:", "x:1", "loopback://:1", " loopback://x:1")) {
            assertEquals(Set.of(TARGET), failing(Variant.targeting(out -> java.util.Optional.of(malformed))), "malformed: '" + malformed + "'");
        }
    }
}
