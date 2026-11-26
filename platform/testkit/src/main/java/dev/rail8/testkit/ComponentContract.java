package dev.rail8.testkit;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.CredentialKind;
import dev.rail8.api.Deadline;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Presented;
import dev.rail8.api.Producer;
import dev.rail8.api.Refused;
import dev.rail8.api.Secret;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The cases every component passes, real or mock: both must pass the same suite. A component's test extends this class
 * and supplies the component, the {@link Inbound} its consumer is built for and, where the defaults do not fit, the
 * {@link Outbound} and {@link Call} that reach a started consumer. Every case asserts an observable: the scheme (named,
 * never {@link Forms#PLATFORM}); the forms (the same on every call, serving both directions, every field, choice and tab
 * labelled and every field with a help text, their text keys in {@link Forms#toJson}, the connection form only adding to
 * the one recorded in the test resources' {@code forms/<scheme>.json}); the uses passing the forms (a
 * connection handed over exactly where a direction reaches one); no secret handle read while a consumer or a producer is
 * built; delivery to the running consumer's handler with the body intact (the receiver mints its own message id — a
 * transport carries bytes and headers); two calls at once through one producer each reaching their own receiver and
 * getting their own answer; two producers of one connection side by side, the second unharmed while the first closes with
 * a send out; a stopped consumer receiving nothing; health; the send completing only after the handler acknowledged; a
 * Nack failing the send; a body above the tree limit streaming through; a second send of the same envelope delivering the
 * same body again (a retry re-opens the body, nothing is consumed); and a consumer stopped mid-delivery:
 * {@link Consumer#stop()} accepts nothing new, waits for the delivery in flight and lets its answer reach the sender — a
 * cancel reaches the component as a retryable {@link Ack.Nack} the sender sees as a retryable {@link NackException} — and
 * each delivery calling the handler on a virtual thread of its own, never the sender's; a handler that throws or answers
 * null fails the send, and a stop after it completes; a reply to a second attempt is keyed by that attempt, carries it on
 * and leaves the first attempt's reply bytes in place. Every send carries the message id to the partner in
 * {@link Envelope#H_MESSAGE_ID} — one value, the envelope's id, whatever was forwarded under that name in any case —
 * through {@code send(e, call)} and {@code send(e, call, d)} alike; a send with no time left ({@code d.remaining() <= 0})
 * fails with a retryable {@link NackException} and reaches no partner; a send still unanswered when its {@link Deadline}
 * runs out is cut — failed with a retryable {@link NackException} on the {@link Timer} the component was built with
 * ({@link #component(Timer)}), the deadline re-read there, so a deadline renewed meanwhile is waited for. The cookies of
 * the node's UI a consumer names ({@link #platformCookies()}) never reach its handler nor leave in a reply's {@code set-cookie},
 * and other cookies pass. A fresh consumer
 * of an inbound whose last consumer stopped serves it, as the node starts one after a break; {@link Consumer#broken()}
 * stays pending while a consumer delivers or refuses messages, and through its stop. A component's prefix on the node's
 * HTTP port ({@link Component#httpPrefix()}) is none, or one of {@link HttpIngress#PREFIX_PATTERN} other than
 * {@link HttpIngress#RESERVED_PREFIX}, the same on every call, with {@link HttpIngress#ADDRESS} in its flow form.
 *
 * <p>A component that reads credentials ({@link Component#reads()} not empty), and its mock, enter every message through
 * the {@link dev.rail8.api.Entry} they were built with, the suite's {@link FakeEntry}: a message reaches the flow only
 * through the handler {@code enter} gave, once per message; {@code enter} comes before any byte is stored, as the suite's
 * blob store ({@link #component(BlobStore)}) fails a write that comes before its request's {@code enter}; a refused caller
 * gets the refusal's answer, its code in the send's failure, and nothing is stored or delivered; each declared kind reaches
 * {@code enter} as that kind ({@link #presenting}); and no delivered envelope holds the presented password, key or token in
 * its headers, props, keys or body.
 *
 * @since 0.1
 */
@Timeout(60)
public abstract class ComponentContract {

    /** The flow every default {@link Call} names. */
    protected static final String FLOW = "contract";

    /** A body and reply limit large enough for every case's message: the default {@link Call}'s reply limit. */
    protected static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final Function<Envelope, Future<Ack>> ACCEPT = e -> Future.succeededFuture(new Ack.Accepted(e.id()));

    /** The user the suite presents as a name and a password. */
    protected static final String PRESENTED_USER = "contract-user";
    /** The password the suite presents; no delivered envelope holds it. */
    protected static final String PRESENTED_PASSWORD = "contract-password-7f3a9c41";
    /** The API key the suite presents; no delivered envelope holds it. */
    protected static final String PRESENTED_KEY = "contract-key-4b2e8d17";
    /** The token the suite presents; no delivered envelope holds it. */
    protected static final String PRESENTED_TOKEN = "contract-token-9d1c6a52";

    /** A fresh component under test; called once per test case. */
    protected abstract Component component();

    /**
     * A fresh component under test whose consumers store what they take in into {@code blobs}: the suite's store, which fails
     * a write that comes before its request's {@code enter}. A component whose {@link Component#reads()} is not empty
     * overrides it, and a mock that stores nothing answers {@link #component()}; the cases of the entry fail without it.
     */
    protected Component component(BlobStore blobs) {
        throw new UnsupportedOperationException("a component that reads credentials overrides component(BlobStore), with the store its consumers write to");
    }

    /**
     * {@code e} as it carries the credential of {@code kind} when a producer of {@link #outbound(Consumer)} sends it, so the
     * started consumer's caller presents that kind; empty when the suite cannot present it so. By default a header:
     * {@code Authorization: Basic} of {@link #PRESENTED_USER} and {@link #PRESENTED_PASSWORD}, {@code X-API-Key:}
     * {@link #PRESENTED_KEY}, {@code Authorization: Bearer} {@link #PRESENTED_TOKEN}; a client certificate none, which the
     * node's HTTP port reads for an adapter on it (one with a prefix, or one that reads {@link HttpIngress#READS}, as its
     * mock does). An adapter with a listener of its own supplies a presenter for each kind it reads.
     */
    protected Optional<Envelope> presenting(CredentialKind kind, Envelope e) {
        return switch (kind) {
            case BASIC -> Optional.of(e.withHeader("authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (PRESENTED_USER + ":" + PRESENTED_PASSWORD).getBytes(StandardCharsets.UTF_8))));
            case API_KEY -> Optional.of(e.withHeader("x-api-key", PRESENTED_KEY));
            case OAUTH2 -> Optional.of(e.withHeader("authorization", "Bearer " + PRESENTED_TOKEN));
            case CERTIFICATE -> Optional.empty();
        };
    }

    /**
     * A fresh component under test whose producers time their sends on {@code timer} — the case's fake timer, on the case's
     * fake clock, which also drives the {@link Deadline}s it hands over. By default {@link #component()}: a component that takes
     * a timer overrides this, one that does not cannot pass {@code aSendIsCutWhenItsDeadlineRunsOutButNotWhileItIsRenewed}.
     */
    protected Component component(Timer timer) { return component(); }

    /**
     * What the consumer under test is built for, as the node hands it over: the flow, the connection when the in
     * direction reaches one, the flow's settings and the body limit. The producer reaches its consumer through
     * {@link #outbound(Consumer)} and {@link #call(Consumer)}.
     */
    protected abstract Inbound inbound();

    /**
     * The receiver {@code n} (0 or 1) of the case of two calls at once; by default {@link #inbound()} for both. An adapter
     * whose one producer reaches several receivers by a setting of the call overrides this and {@link #call(int, Consumer)}
     * with two different receivers. Two inbounds of the same flow, settings, body limit and connection key and values are
     * one receiver, started once.
     */
    protected Inbound inbound(int n) { return inbound(); }

    /**
     * What a producer is built for in order to reach {@code started}, a consumer of {@link #inbound()} that has started; by
     * default the inbound's own connection. A transport that assigns the consumer's address only at start (an ephemeral
     * port) overrides it and reads the address from the started consumer; one whose directions connect differently builds
     * the connection its out direction takes.
     */
    protected Outbound outbound(Consumer started) { return new Outbound(inbound().connection()); }

    /**
     * The call that reaches {@code started} through a producer of {@link #outbound(Consumer)}: by default one of
     * {@link #FLOW}, step {@code send}, no settings and a reply limit of {@link #MAX_BYTES}.
     */
    protected Call call(Consumer started) { return new Call(FLOW, "send", Map.of(), MAX_BYTES, Optional.empty()); }

    /**
     * The call that reaches {@code started}, the started consumer of {@link #inbound(int) inbound(n)}, through the producer
     * of {@link #outbound(Consumer)} built for receiver 0; by default {@link #call(Consumer)}.
     */
    protected Call call(int n, Consumer started) { return call(started); }
    /**
     * The cookies of the node's UI that the consumer under test keeps out of a flow: cut from a delivery's {@code cookie}
     * before its handler sees it, and never sent back from a reply's {@code set-cookie}. None by default, and the case that
     * checks them has nothing to check: a consumer that takes requests where the UI's cookies can arrive, such as {@code http}, and the
     * mock that stands in for it name them.
     */
    protected Set<String> platformCookies() { return Set.of(); }

    /** How long a case waits for an asynchronous delivery before it fails (a real transport needs a moment). */
    protected Duration patience() { return Duration.ofSeconds(10); }

    /**
     * Waits until {@code f} has completed, either way, for at most {@link #patience()}; a future still pending then fails
     * the case (a transport that never answers must not hang the suite).
     */
    private <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + patience() + ": " + f);
        }
        return f;
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds or {@link #patience()} is spent. */
    protected final boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + patience().toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /** {@link #soon}: until {@code actual} gives exactly {@code expected}; a value past it never satisfies. */
    protected final boolean soonEquals(IntSupplier actual, int expected) { return soon(() -> actual.getAsInt() == expected); }

    /** {@link #soon}: until {@code actual} gives {@code least} or more. */
    protected final boolean soonAtLeast(IntSupplier actual, int least) { return soon(() -> actual.getAsInt() >= least); }

    /** {@link #soon}: until {@code value} gives a non-null value. */
    protected final boolean soonPresent(Supplier<?> value) { return soon(() -> value.get() != null); }

    @Test
    public void theSchemeIsNamedAndNotThePlatforms() {
        String scheme = component().scheme();
        assertNotNull(scheme, "scheme");
        assertFalse(scheme.isBlank(), "scheme is blank");
        assertNotEquals(Forms.PLATFORM, scheme, "the platform's own texts use the scheme " + Forms.PLATFORM + " in their keys");
    }

    @Test
    public void theFormsAreTheSameOnEveryCallAndServeBothDirections() {
        Component component = component();
        Forms forms = component.forms();
        assertNotNull(forms, "forms() answers forms, never null");
        assertEquals(forms.toJson(component.scheme()), component.forms().toJson(component.scheme()),
                "the node asks for the forms whenever it checks a use or serves them, and gets the same ones every time");
        assertTrue(forms.serves(Direction.IN) && forms.serves(Direction.OUT), "a component of this suite receives and sends: " + forms.reach());
    }

    @Test
    public void everyFieldChoiceAndTabHasALabelAndItsTextKeys() {
        Component component = component();
        assertLabelled(component.scheme(), component.forms());
    }

    /**
     * The connection form only adds to the one recorded in the test resources, {@code forms/<scheme>.json}: no field gone,
     * retyped, marked or unmarked {@code server()}, with its default changed, become required or narrowed, and no new field
     * required. A connection form that describes nothing needs no recorded form.
     */
    @Test
    public void theConnectionFormOnlyAddsToTheRecordedOne() {
        Component component = component();
        RecordedForm.assertOnlyAdds(getClass(), component.scheme(), component.forms());
    }

    @Test
    public void theUsesPassTheForms() {
        Component component = component();
        Forms forms = component.forms();
        Inbound in = inbound();
        assertPasses(forms, Direction.IN, in.connection(), in.settings());
        Consumer consumer = component.consumer(in, ACCEPT);
        consumer.start().await();
        try {
            assertPasses(forms, Direction.OUT, outbound(consumer).connection(), call(consumer).settings());
        } finally {
            consumer.stop().await();
        }
    }

    @Test
    public void anHttpPrefixIsOfItsFormTheSameOnEveryCallAndItsFlowFormListsTheAddress() {
        Component component = component();
        Optional<String> prefix = component.httpPrefix();
        assertNotNull(prefix, "httpPrefix() answers an Optional, never null");
        assertEquals(prefix, component.httpPrefix(), "the node reads the prefix once and takes it as the same on every call");
        if (prefix.isEmpty()) return;
        assertTrue(prefix.get().matches(HttpIngress.PREFIX_PATTERN), "a prefix is / and one segment of " + HttpIngress.PREFIX_PATTERN + ": " + prefix.get());
        assertFalse(HttpIngress.RESERVED_PREFIX.equals(prefix.get()), "the prefix " + HttpIngress.RESERVED_PREFIX + " is the platform's own");
        assertListsTheAddress(component.forms());
    }

    /**
     * Building a consumer or a producer opens nothing, so it reads no secret: the consumer and the producer are built with
     * a connection whose secret handles count their reads, and no handle has been read once they are built.
     */
    @Test
    public void noSecretHandleIsReadWhileAConsumerOrAProducerIsBuilt() {
        Component component = component();
        AtomicInteger reads = new AtomicInteger();
        Inbound in = inbound();
        Consumer built = component.consumer(new Inbound(in.flow(), in.connection().map(c -> counted(c, reads)), in.settings(), in.maxBodyBytes()), ACCEPT);
        assertNotNull(built, "consumer() answers a consumer");
        assertEquals(0, reads.get(), "a consumer that was only built read a secret");
        Consumer consumer = component.consumer(in, ACCEPT);
        consumer.start().await();
        try {
            Producer producer = component.producer(new Outbound(outbound(consumer).connection().map(c -> counted(c, reads))));
            try {
                assertEquals(0, reads.get(), "a producer that was only built read a secret");
            } finally {
                producer.close().await();
            }
        } finally {
            consumer.stop().await();
        }
    }

    /**
     * Two calls in flight at once through one producer each reach their own receiver — the two receivers of
     * {@link #inbound(int)}, or the one receiver when both are the same — and each answer is the one its own call got.
     */
    @Test
    public void twoCallsAtOnceThroughOneProducerEachReachTheirReceiverAndGetTheirOwnAnswer() {
        Component component = component();
        List<Inbound> receivers = sameReceiver(inbound(0), inbound(1)) ? List.of(inbound(0)) : List.of(inbound(0), inbound(1));
        List<Consumer> consumers = new ArrayList<>();
        try {
            for (int n = 0; n < receivers.size(); n++) {
                String receiver = String.valueOf(n);
                Consumer consumer = component.consumer(receivers.get(n), e -> Future.succeededFuture(
                        new Ack.Reply(e.withHeader("x-receiver", receiver).withHeader("x-echo", e.body().whole().toString()))));
                consumer.start().await();
                consumers.add(consumer);
            }
            Producer producer = component.producer(outbound(consumers.getFirst()));
            try {
                FakeRuntime rt = FakeRuntime.create();
                List<Future<Envelope>> sends = new ArrayList<>();
                for (int n = 0; n < 2; n++) {
                    Consumer reached = consumers.get(Math.min(n, consumers.size() - 1));
                    sends.add(producer.send(rt.ingress(FLOW, Headers.empty(), Buffer.buffer("call-" + n)), call(n, reached)));
                }
                for (int n = 0; n < 2; n++) {
                    Future<Envelope> sent = settled(sends.get(n));
                    assertTrue(sent.succeeded(), "call " + n + " is answered: " + sent.cause());
                    assertEquals("call-" + n, sent.result().header("x-echo").orElse(null), "call " + n + "'s answer is its own, not the other call's");
                    assertEquals(String.valueOf(Math.min(n, consumers.size() - 1)), sent.result().header("x-receiver").orElse(null),
                            "call " + n + " reached its own receiver");
                }
            } finally {
                producer.close().await();
            }
        } finally {
            for (Consumer consumer : consumers) consumer.stop().await();
        }
    }

    /**
     * Two producers of one connection work side by side, as the node builds a fresh producer while the old one still
     * sends; closing the first while a send of it is out leaves the second working, during the close and after it.
     */
    @Test
    public void twoProducersOfOneConnectionWorkSideBySideAndClosingTheFirstLeavesTheSecond() {
        Component component = component();
        Promise<Ack> answer = Promise.promise();
        AtomicReference<Envelope> held = new AtomicReference<>();
        Consumer consumer = component.consumer(inbound(), e -> {
            String body = e.body().whole().toString();
            if (body.equals("held")) {
                held.set(e);
                return answer.future();
            }
            return Future.succeededFuture(new Ack.Reply(e.withHeader("x-echo", body)));
        });
        consumer.start().await();
        Call call = call(consumer);
        Producer first = component.producer(outbound(consumer));
        Producer second = component.producer(outbound(consumer));
        Future<Void> closed = null;
        try {
            FakeRuntime rt = FakeRuntime.create();
            assertEchoed(first, call, rt, "first-works");
            assertEchoed(second, call, rt, "second-works");
            Future<Envelope> out = first.send(rt.ingress(FLOW, Headers.empty(), Buffer.buffer("held")), call);
            assertTrue(soonPresent(held::get), "the first producer's send reached the handler");
            closed = first.close();
            assertEchoed(second, call, rt, "while-the-first-closes");
            answer.complete(new Ack.Reply(held.get()));
            settled(out);   // answered or failed by the close: either is the first producer's business
            assertTrue(settled(closed).succeeded(), "the first producer closes once its send settled: " + closed.cause());
            assertEchoed(second, call, rt, "after-the-first-closed");
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            if (closed == null) first.close();
            second.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aMessageReachesTheFlowOnlyThroughTheHandlerEnterGaveOncePerMessage() {
        Component component = component();
        if (component.reads().isEmpty()) return;
        FakeEntry fake = new FakeEntry();
        Consumer consumer = component.consumer(inbound(), fake.entry(inbound().flow(), ACCEPT));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        try {
            FakeRuntime rt = FakeRuntime.create();
            for (String body : List.of("first", "second")) {
                Future<Envelope> sent = settled(producer.send(presented(component, rt.ingress(FLOW, Headers.empty(), Buffer.buffer(body))), call(consumer)));
                assertTrue(sent.succeeded(), body + ": delivered: " + sent.cause());
            }
            assertEquals(2, fake.presented().size(), "each message entered once: " + fake.presented());
            assertEquals(List.of("first", "second"), fake.delivered().stream().map(e -> e.body().whole().toString()).toList(),
                    "each message reached the flow through the handler its enter gave, with its body intact");
            assertEquals(0, fake.secondApplies(), "no handler is applied twice");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void enterComesBeforeAnyByteIsStored() {
        if (component().reads().isEmpty()) return;
        FakeEntry fake = new FakeEntry().thenAdmit(FakeEntry.SYSTEM).thenRefuse(Refused.Reason.UNKNOWN).thenAdmit(FakeEntry.SYSTEM);
        EnteredFirst blobs = new EnteredFirst(fake);
        Component component = component(blobs);
        Consumer consumer = component.consumer(inbound(), fake.entry(inbound().flow(), ACCEPT));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        try {
            FakeRuntime rt = FakeRuntime.create();
            for (String body : List.of("admitted", "refused", "admitted-again")) {
                settled(producer.send(presented(component, rt.ingress(FLOW, Headers.empty(), Buffer.buffer(body))), call(consumer)));
            }
            assertEquals(List.of(), blobs.early(), "no byte is stored before its request entered");
            assertEquals(List.of("admitted", "admitted-again"), fake.delivered().stream().map(e -> e.body().whole().toString()).toList(),
                    "the admitted messages reach the flow");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aRefusedCallerGetsTheRefusalsAnswerAndNothingIsStoredOrDelivered() {
        if (component().reads().isEmpty()) return;
        for (Refused.Reason reason : List.of(Refused.Reason.UNKNOWN, Refused.Reason.NOT_ALLOWED, Refused.Reason.BUSY)) {
            FakeEntry fake = new FakeEntry().refusing(reason);
            EnteredFirst blobs = new EnteredFirst(fake);
            Component component = component(blobs);
            Consumer consumer = component.consumer(inbound(), fake.entry(inbound().flow(), ACCEPT));
            consumer.start().await();
            Producer producer = component.producer(outbound(consumer));
            try {
                Envelope e = presented(component, FakeRuntime.create().ingress(FLOW, Headers.empty(), Buffer.buffer("refused-" + reason)));
                Future<Envelope> sent = settled(producer.send(e, call(consumer)));
                Refused refusal = new Refused(reason, reason == Refused.Reason.BUSY ? Optional.of(Duration.ofSeconds(1)) : Optional.empty());
                assertTrue(sent.failed(), reason + ": the send fails");
                assertTrue(String.valueOf(sent.cause().getMessage()).contains(refusal.code()),
                        reason + ": the caller gets the refusal's answer, which carries its code " + refusal.code() + ": " + sent.cause());
                assertEquals(List.of(), fake.delivered(), reason + ": nothing is delivered");
                assertEquals(0, blobs.writes(), reason + ": nothing is stored");
            } finally {
                producer.close().await();
                consumer.stop().await();
            }
        }
    }

    @Test
    public void eachDeclaredKindReachesEnterAsThatKind() {
        Component component = component();
        for (CredentialKind kind : component.reads().stream().sorted().toList()) {
            Optional<Envelope> carrying = presenting(kind, FakeRuntime.create().ingress(FLOW, Headers.empty(), Buffer.buffer("kind-" + kind)));
            if (carrying.isEmpty()) {
                // an adapter on the node's HTTP port, and its mock, read what the port reads: the port's own tests check a certificate
                if (component.httpPrefix().isPresent() || component.reads().equals(HttpIngress.READS)) continue;
                fail("the component reads " + kind + ": its test supplies a presenter of it, presenting(" + kind + ", e)");
            }
            FakeEntry fake = new FakeEntry();
            Consumer consumer = component.consumer(inbound(), fake.entry(inbound().flow(), ACCEPT));
            consumer.start().await();
            Producer producer = component.producer(outbound(consumer));
            try {
                Future<Envelope> sent = settled(producer.send(carrying.get(), call(consumer)));
                assertTrue(sent.succeeded(), kind + ": delivered: " + sent.cause());
                assertEquals(1, fake.presented().size(), kind + ": entered once");
                Presented p = fake.presented().getFirst();
                switch (kind) {
                    case BASIC -> assertTrue(p instanceof Presented.Basic b && (!byHeaders() || b.user().equals(PRESENTED_USER) && b.password().equals(PRESENTED_PASSWORD)),
                            "BASIC reaches enter as a name and a password: " + p);
                    case API_KEY -> assertTrue(p instanceof Presented.ApiKey k && (!byHeaders() || k.key().equals(PRESENTED_KEY)), "API_KEY reaches enter as a key: " + p);
                    case OAUTH2 -> assertTrue(p instanceof Presented.Bearer t && (!byHeaders() || t.token().equals(PRESENTED_TOKEN)), "OAUTH2 reaches enter as a token: " + p);
                    case CERTIFICATE -> assertInstanceOf(Presented.Certificate.class, p, "CERTIFICATE reaches enter as a chain");
                }
            } finally {
                producer.close().await();
                consumer.stop().await();
            }
        }
    }

    @Test
    public void noDeliveredEnvelopeHoldsThePresentedPasswordKeyOrToken() {
        Component component = component();
        List<CredentialKind> kinds = component.reads().stream().sorted().toList();
        if (kinds.isEmpty()) return;
        FakeEntry fake = new FakeEntry();
        Consumer consumer = component.consumer(inbound(), fake.entry(inbound().flow(), ACCEPT));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        try {
            for (CredentialKind kind : kinds) {
                Optional<Envelope> carrying = presenting(kind, FakeRuntime.create().ingress(FLOW, Headers.empty(), Buffer.buffer("secret-" + kind)));
                if (carrying.isEmpty()) continue;
                assertTrue(settled(producer.send(carrying.get(), call(consumer))).succeeded(), kind + ": delivered");
            }
            List<String> secrets = List.of(PRESENTED_PASSWORD, PRESENTED_KEY, PRESENTED_TOKEN,
                    Base64.getEncoder().encodeToString((PRESENTED_USER + ":" + PRESENTED_PASSWORD).getBytes(StandardCharsets.UTF_8)));
            for (Envelope e : fake.delivered()) {
                String held = e.headers().asMap() + " " + e.props() + " " + e.keys() + " " + e.body().whole();
                for (String secret : secrets) assertFalse(held.contains(secret), "a delivered envelope holds what the caller presented: " + held);
            }
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /** {@code e} as it carries the first kind {@code component} reads that the suite presents itself; {@code e} when none. */
    private Envelope presented(Component component, Envelope e) {
        for (CredentialKind kind : component.reads().stream().sorted().toList()) {
            Optional<Envelope> carrying = presenting(kind, e);
            if (carrying.isPresent()) return carrying.get();
        }
        return e;
    }

    /** Whether the suite's own headers present the kinds: the test does not override {@link #presenting}. */
    private boolean byHeaders() {
        for (Class<?> c = getClass(); c != ComponentContract.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("presenting") && List.of(m.getParameterTypes()).equals(List.of(CredentialKind.class, Envelope.class))) return false;
            }
        }
        return true;
    }

    /**
     * The suite's blob store for the cases of the entry: an {@link InMemoryBlobStore} that fails a write that comes before
     * its request's {@code enter}, a write of a message taken in (its step 0) beyond the callers {@code fake} admitted so far,
     * and names each such write.
     */
    private static final class EnteredFirst implements BlobStore {
        private final FakeEntry fake;
        private final InMemoryBlobStore store = FakeRuntime.create().withTreeLimit(4096).blobs();
        private final AtomicInteger writes = new AtomicInteger();
        private final List<String> early = new CopyOnWriteArrayList<>();

        EnteredFirst(FakeEntry fake) { this.fake = fake; }

        int writes() { return writes.get(); }

        List<String> early() { return List.copyOf(early); }

        /** Whether a write of {@code key} may go ahead: a reply's (a step after 0), or a message's taken in once its request entered. */
        private boolean entered(BlobKey key) {
            if (key.step() != 0) return true;
            if (writes.incrementAndGet() <= fake.admitted().size()) return true;
            early.add(key.toString());
            return false;
        }

        @Override public long treeLimit() { return store.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            return entered(key) ? store.write(key, whole, maxSize) : Future.failedFuture(new IllegalStateException("a write before its request's enter"));
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            return entered(key) ? store.write(key, in, maxSize) : Future.failedFuture(new IllegalStateException("a write before its request's enter"));
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return store.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return store.read(key); }

        @Override public Future<Boolean> exists(BlobKey key) { return store.exists(key); }

        @Override public Future<Void> persist(BlobKey key) { return store.persist(key); }
    }

    /**
     * Whether {@code a} and {@code b} name one receiver: the same flow, settings and body limit, and connections of the same
     * key and values. Secret handles are left out, as a fresh handle of the same secret is a different object.
     */
    private static boolean sameReceiver(Inbound a, Inbound b) {
        return a.flow().equals(b.flow()) && a.settings().equals(b.settings()) && a.maxBodyBytes() == b.maxBodyBytes()
                && a.connection().map(c -> c.key() + c.values()).equals(b.connection().map(c -> c.key() + c.values()));
    }

    /** Sends {@code body} through {@code producer} and requires the answer the handler gave that very send. */
    private void assertEchoed(Producer producer, Call call, FakeRuntime rt, String body) {
        Future<Envelope> sent = settled(producer.send(rt.ingress(FLOW, Headers.empty(), Buffer.buffer(body)), call));
        assertTrue(sent.succeeded(), body + ": answered: " + sent.cause());
        assertEquals(body, sent.result().header("x-echo").orElse(null), body + ": the answer is this send's own");
    }

    /**
     * Requires every field, choice and tab of {@code forms} to have a label and every field a help text, with the text
     * keys of {@code scheme} in {@link Forms#toJson}: a field's {@code <scheme>.field.<name>.label} and {@code .help}, a
     * choice's {@code <scheme>.field.<name>.choice.<value>}, a tab's {@code <scheme>.tab.<id>}, or {@code rail8.tab.<id>}
     * for the platform's own tabs.
     */
    static void assertLabelled(String scheme, Forms forms) {
        Map<String, Object> json = forms.toJson(scheme);
        for (Object each : (List<?>) json.get("tabs")) {
            Map<?, ?> tab = (Map<?, ?>) each;
            assertText(tab.get("label"), "tab '" + tab.get("id") + "': its label");
            assertEquals((Boolean.TRUE.equals(tab.get("common")) ? Forms.PLATFORM : scheme) + ".tab." + tab.get("id"), tab.get("labelKey"),
                    "tab '" + tab.get("id") + "': its label's key");
        }
        for (String form : List.of("connection", "flow")) {
            for (Object each : (List<?>) json.get(form)) {
                Map<?, ?> field = (Map<?, ?>) each;
                String at = scheme + ".field." + field.get("name");
                String what = form + " field '" + field.get("name") + "': ";
                assertText(field.get("label"), what + "its label");
                assertText(field.get("help"), what + "its help text");
                assertEquals(at + ".label", field.get("labelKey"), what + "its label's key");
                assertEquals(at + ".help", field.get("helpKey"), what + "its help text's key");
                if (field.get("choices") instanceof List<?> choices) {
                    for (Object c : choices) {
                        Map<?, ?> choice = (Map<?, ?>) c;
                        assertText(choice.get("label"), what + "choice '" + choice.get("value") + "': its label");
                        assertEquals(at + ".choice." + choice.get("value"), choice.get("labelKey"), what + "choice '" + choice.get("value") + "': its label's key");
                    }
                }
            }
        }
    }

    private static void assertText(Object text, String what) {
        assertTrue(text instanceof String s && !s.isBlank(), what + " is missing: " + text);
    }

    /**
     * Requires a use of {@code direction} to pass {@code forms}: a connection exactly where the direction reaches one, its
     * values and secret handles passing the connection form, and {@code settings} passing the flow form. A secret stands
     * in by its reference, so the check reads no secret.
     */
    static void assertPasses(Forms forms, Direction direction, Optional<Connection> connection, Map<String, Object> settings) {
        String wire = direction.name().toLowerCase(Locale.ROOT);
        assertEquals(forms.connects(direction), connection.isPresent(), "the " + wire + " direction "
                + (forms.connects(direction) ? "reaches a connection: the node hands one over" : "reaches no connection: the node hands none over") + ": " + connection);
        connection.ifPresent(c -> {
            Map<String, Object> values = new HashMap<>(c.values());
            c.secrets().forEach((field, handle) -> values.put(field, handle.reference()));
            assertEquals(List.of(), forms.connection().check(values, direction), "the " + wire + " connection is one the node hands over: " + c);
        });
        assertEquals(List.of(), forms.flow().check(settings, direction), "the " + wire + " settings are ones the node hands over: " + settings.keySet());
    }

    /** Requires {@link HttpIngress#ADDRESS}, as it is declared, among the fields of {@code forms}' flow form. */
    static void assertListsTheAddress(Forms forms) {
        Object address = ((List<?>) Settings.of(HttpIngress.ADDRESS).toJson().get("fields")).getFirst();
        assertTrue(((List<?>) forms.flow().toJson().get("fields")).contains(address),
                "a component with a prefix lists HttpIngress.ADDRESS in its flow form, so the node finds the path each in use serves");
    }

    /**
     * {@code c} with each secret handle counting its reads in {@code reads}: a handle of the same reference and kind; its
     * {@code reference()} and {@code kind()} are no read.
     */
    static Connection counted(Connection c, AtomicInteger reads) {
        Map<String, Secret> secrets = new HashMap<>();
        c.secrets().forEach((field, handle) -> secrets.put(field, switch (handle.kind()) {
            case VALUE -> Secret.of(handle.reference(), () -> {
                reads.incrementAndGet();
                return handle.value();
            });
            case CREDENTIALS -> Secret.credentials(handle.reference(), () -> {
                reads.incrementAndGet();
                return handle.credentials();
            });
            case KEY_PAIR -> Secret.keyPair(handle.reference(), () -> {
                reads.incrementAndGet();
                return handle.keyPair();
            });
        }));
        return new Connection(c.key(), c.values(), secrets);
    }

    @Test
    public void healthIsUpWhileAConsumerRunsAndDownAfterItStopped() {
        Component component = component();
        Consumer consumer = component.consumer(inbound(), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        try {
            assertEquals(Health.Status.UP, component.health().await().status());
        } finally {
            consumer.stop().await();
        }
        assertEquals(Health.Status.DOWN, component.health().await().status(), "nothing listens any more");
    }

    @Test
    public void aStoppedConsumerReceivesNothing() {
        Component component = component();
        AtomicInteger delivered = new AtomicInteger();
        Consumer consumer = component.consumer(inbound(), e -> { delivered.incrementAndGet(); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Outbound where = outbound(consumer);   // where the consumer was: the producer targets a stopped address
        Call call = call(consumer);
        consumer.stop().await();
        Producer producer = component.producer(where);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late"));
            Future<Envelope> outcome = producer.send(sent, call);
            outcome.otherwiseEmpty().await();   // a failed send is the expected answer of a stopped transport; what matters is below
            assertEquals(0, delivered.get(), "the stopped consumer's handler must not see the message");
        } finally {
            producer.close().await();
        }
    }

    @Test
    public void aFreshConsumerOfTheSameEndpointServesOnceTheLastStopped() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Function<Envelope, Future<Ack>> handler = e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Reply(e.withHeader("x-seen", "1")));
        };
        Consumer first = component.consumer(inbound(), handler);
        first.start().await();
        first.stop().await();
        Consumer fresh = component.consumer(inbound(), handler);   // what the node does after a break or a failed start
        fresh.start().await();
        Producer producer = component.producer(outbound(fresh));
        Call call = call(fresh);
        try {
            Envelope reply = producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("again")), call).await();
            assertEquals("1", reply.header("x-seen").orElse(null), "the fresh consumer answered");
            assertEquals(1, seen.size(), "delivered once, by the fresh consumer");
            assertEquals("again", seen.get(0).body().whole().toString());
        } finally {
            producer.close().await();
            fresh.stop().await();
        }
    }

    @Test
    public void brokenStaysPendingWhileTheConsumerWorksAndThroughItsStop() {
        Component component = component();
        Consumer consumer = component.consumer(inbound(), e -> e.header("x-refuse").isPresent()
                ? Future.succeededFuture(new Ack.Nack("refused", false)) : Future.succeededFuture(new Ack.Reply(e)));
        consumer.start().await();
        Future<Void> broken = consumer.broken();
        assertNotNull(broken, "broken() answers a future, never null");
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("works")), call).await();
            assertFalse(broken.isComplete(), "a consumer that delivers has not broken: " + broken);
            settled(producer.send(FakeRuntime.create().ingress("contract", Headers.empty().with("x-refuse", "1"), Buffer.buffer("refused")), call));
            assertFalse(broken.isComplete(), "a refused message is that message's failure, never a break: " + broken);
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
        assertFalse(broken.isComplete(), "a stop is not a break: " + broken);
        assertFalse(consumer.broken().isComplete(), "nor read again after the stop: " + consumer.broken());
    }

    @Test
    public void producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Reply(e.withHeader("x-seen", "1")));
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty().with("content-type", "text/plain"), Buffer.buffer("ping"));
            Envelope reply = producer.send(sent, call).await();
            assertEquals("1", reply.header("x-seen").orElse(null), "the reply is what the handler answered");
            assertEquals(1, seen.size(), "delivered exactly once");
            assertFalse(seen.get(0).id().isBlank(), "the receiver's message has an id of its own (a transport carries bytes and headers, never the sender's id)");
            assertEquals(sent.body().hash(), seen.get(0).body().hash(), "the body arrives intact");
            assertEquals(sent.body().size(), seen.get(0).body().size());
            assertEquals("ping", seen.get(0).body().whole().toString());
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void thePlatformsCookiesNeitherReachTheHandlerNorLeaveInAReplyAndOtherCookiesPass() {
        List<String> names = platformCookies().stream().sorted().toList();
        if (names.isEmpty()) return;   // the consumer keeps no cookies out of a flow: nothing to check
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Reply(e.withHeader("set-cookie", e.headers().get("x-set"))));
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            List<String> pairs = new ArrayList<>(names.stream().map(name -> name + "=platform").toList());
            pairs.add(1, "theme=dark");
            for (String name : Stream.concat(names.stream(), Stream.of("theme")).toList()) {
                String set = name + "=v; Path=/";
                Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty().with("cookie", String.join("; ", pairs)).with("x-set", set), Buffer.buffer("cookies"));
                Future<Envelope> reply = settled(producer.send(sent, call));
                assertTrue(reply.succeeded(), name + ": the send is answered: " + reply.cause());
                assertEquals("theme=dark", seen.getLast().headers().get("cookie"), name + ": the handler sees only the other cookie: " + seen.getLast().headers());
                assertEquals(name.equals("theme") ? set : null, reply.result().headers().get("set-cookie"),
                        name + ": a reply never sets one of the platform's cookies, and sets any other");
            }
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void theSendCompletesOnlyAfterTheHandlerAcknowledged() {
        Component component = component();
        AtomicReference<Promise<Ack>> pending = new AtomicReference<>();
        Consumer consumer = component.consumer(inbound(), e -> {
            Promise<Ack> p = Promise.promise();
            pending.set(p);
            return p.future();
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("slow"));
            Future<Envelope> outcome = producer.send(sent, call);
            assertTrue(soonPresent(pending::get), "the handler was invoked");
            assertFalse(outcome.isComplete(), "ACK only after the handler acknowledged: nothing completes before");
            pending.get().complete(new Ack.Reply(sent.withHeader("x-acked", "yes")));
            assertEquals("yes", outcome.await().header("x-acked").orElse(null));
        } finally {
            if (pending.get() != null) pending.get().tryComplete(new Ack.Nack("contract cleanup", true));   // a stop drains the answers in flight
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aNackFromTheHandlerFailsTheSendWithItsReason() {
        Component component = component();
        Consumer consumer = component.consumer(inbound(), e -> Future.succeededFuture(new Ack.Nack("busy-right-now", true)));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("x"));
            Future<Envelope> outcome = producer.send(sent, call);
            outcome.otherwiseEmpty().await();
            assertTrue(outcome.failed(), "a NACK is not a delivery");
            assertTrue(String.valueOf(outcome.cause().getMessage()).contains("busy-right-now"), "the reason travels: " + outcome.cause());
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aHandlerThatThrowsFailsTheSendAndStopStillCompletes() {
        aBrokenHandlerFailsTheSendAndStopStillCompletes("throws", e -> { throw new IllegalStateException("handler-threw"); });
    }

    @Test
    public void aHandlerThatAnswersNullFailsTheSendAndStopStillCompletes() {
        aBrokenHandlerFailsTheSendAndStopStillCompletes("answers null", e -> null);
    }

    /**
     * A handler that {@code what} acknowledged nothing: the send fails — it neither succeeds nor hangs — and the answer the
     * delivery counted counts as answered, so a stop that follows completes.
     */
    private void aBrokenHandlerFailsTheSendAndStopStillCompletes(String what, Function<Envelope, Future<Ack>> broken) {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Consumer consumer = component.consumer(inbound(), e -> { invoked.incrementAndGet(); return broken.apply(e); });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        Future<Void> stopped = null;
        try {
            Future<Envelope> sent = producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("broken-handler")), call);
            assertTrue(soonEquals(invoked::get, 1), "the handler was invoked");
            assertTrue(settled(sent).failed(), "a handler that " + what + " acknowledged nothing: the send fails: " + sent);
            stopped = consumer.stop();
            assertTrue(settled(stopped).succeeded(), "the failed answer counts as answered: stop completes: " + stopped.cause());
        } finally {
            if (stopped == null) consumer.stop();   // the case has failed already; a send that never settled may hold this stop
            producer.close().await();
        }
    }

    @Test
    public void aBodyAboveTheTreeLimitStreamsThroughIntact() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            FakeRuntime small = FakeRuntime.create().withTreeLimit(4096);
            long size = 300_000;
            Body big = small.blobs().write(BlobKey.body("big", 0), new GeneratedReadStream(size, 8192), size).await();
            Envelope sent = Envelope.ingress("big", "contract", Headers.empty(), big);
            producer.send(sent, call).await();
            assertTrue(soonEquals(seen::size, 1), "delivered");
            assertEquals(size, seen.get(0).body().size(), "every byte arrived");
            assertEquals(big.hash(), seen.get(0).body().hash(), "and the same bytes: a body above the tree limit is streamed, never loaded whole");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("retry-me"));
            producer.send(sent, call).await();
            producer.send(sent, call).await();
            assertTrue(soonEquals(seen::size, 2), "a retry is a second delivery");
            for (Envelope e : seen) {
                assertEquals(sent.body().hash(), e.body().hash(), "the body was re-opened, not consumed by the first send");
                assertEquals("retry-me", e.body().whole().toString());
            }
            assertEquals("retry-me", sent.body().whole().toString(), "and the original is still readable");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /**
     * A reply is the next step's body of the envelope sent, keyed by that envelope's run — a first attempt's under the
     * plain part, the second attempt's under the part suffixed {@code ~2} — so a rerun's reply never overwrites the
     * bytes the first run's reply left. The handler answers each delivery with bytes of its own, written the way a step
     * writes the body it hands on ({@link FakeRuntime#store}): a component that re-stores the reply on the sender's side keys
     * it by the attempt itself; one that returns the handler's answer as it is relies on the handler having seen the attempt.
     * Either way the reply carries the sender's props on, the attempt with them, so the steps after it key by it too.
     */
    @Test
    public void aReplyOfASecondAttemptIsKeyedByItsAttempt() {
        Component component = component();
        FakeRuntime receiver = FakeRuntime.create();
        AtomicInteger delivered = new AtomicInteger();
        Consumer consumer = component.consumer(inbound(), e -> {
            Buffer bytes = Buffer.buffer("reply-" + delivered.incrementAndGet());
            return Future.succeededFuture(new Ack.Reply(e.next(receiver.store(e, bytes))));
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            Envelope first = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("send-me")).advance();
            Future<Envelope> one = settled(producer.send(first, call));
            assertTrue(one.succeeded(), "the first attempt's send is answered: " + one.cause());
            Future<Envelope> two = settled(producer.send(first.with(Envelope.ATTEMPT, 2), call));
            assertTrue(two.succeeded(), "the second attempt's send is answered: " + two.cause());
            assertEquals(BlobKey.BODY, one.result().body().key().part(), "a first attempt's reply under the plain part, as before D-066: " + one.result().body());
            assertEquals(BlobKey.BODY + "~2", two.result().body().key().part(), "the second attempt's reply under its attempt: " + two.result().body());
            assertEquals("reply-1", one.result().body().whole().toString(), "the second attempt's reply left the first's bytes in place");
            assertEquals("reply-2", two.result().body().whole().toString());
            assertEquals(Optional.empty(), one.result().get(Envelope.ATTEMPT), "the first attempt's reply runs on as no attempt in particular");
            assertEquals(Optional.of(2), two.result().get(Envelope.ATTEMPT),
                    "the second attempt's reply carries the sender's props on, the attempt with them: the steps after it key by it too");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /**
     * Each delivery calls the handler on a virtual thread of its own: never on the sender's thread — a stop deadline's
     * interrupt aimed at the handler's thread then never reaches a sender — and never on one thread shared by the deliveries:
     * two deliveries in flight at once are in their handlers at the same time, on two threads. The senders are virtual
     * threads, the way a flow sends, so a component that calls the handler on the caller's thread fails on the sender check,
     * not on the thread kind. A handler blocks only on a virtual thread: a broken component's event loop is never held.
     */
    @Test
    public void theHandlerRunsOnAVirtualThreadOfItsOwnPerDelivery() throws Exception {
        Component component = component();
        List<Thread> handlers = new CopyOnWriteArrayList<>();
        List<Boolean> together = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(2);
        Consumer consumer = component.consumer(inbound(), e -> {
            Thread self = Thread.currentThread();
            handlers.add(self);
            entered.countDown();
            if (self.isVirtual()) together.add(bothEntered(entered));   // waits until the other delivery is in its handler too
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        List<Thread> senders = new CopyOnWriteArrayList<>();
        List<Future<Envelope>> sends = new CopyOnWriteArrayList<>();
        try {
            List<Thread> sending = new ArrayList<>();
            for (String body : List.of("first", "second")) {
                sending.add(Thread.ofVirtual().name("contract-sender-" + body).start(() -> {
                    senders.add(Thread.currentThread());
                    sends.add(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer(body)), call));
                }));
            }
            for (Thread t : sending) {
                assertTrue(t.join(patience().multipliedBy(2)), "the sender " + t + " is still inside send(), at " + java.util.Arrays.toString(t.getStackTrace()));
            }
            assertEquals(2, sends.size(), "both sends were issued");
            for (Future<Envelope> s : sends) {   // a handler that waited for the other one in vain answers after a patience: wait for both
                try {
                    s.otherwiseEmpty().await(patience().toMillis() * 3, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    fail("still pending after three times " + patience() + ": " + s);
                }
                assertTrue(s.succeeded(), "delivered: " + s.cause());
            }
            assertTrue(soonAtLeast(handlers::size, 2), "both deliveries reached the handler: " + handlers);   // a send acknowledged early must not race the check
            assertEquals(2, handlers.size(), "one handler call per delivery: " + handlers);
            for (Thread h : handlers) assertTrue(h.isVirtual(), "the handler runs on a virtual thread, not on " + h);
            for (Thread h : handlers) assertFalse(senders.contains(h), "the handler runs on a thread of its own, not on the sender's " + h + " (senders " + senders + ")");
            assertNotSame(handlers.get(0), handlers.get(1), "two deliveries in flight at once run on two threads, not both on " + handlers.get(0));
            assertTrue(soonEquals(together::size, 2), "both handlers finished waiting for each other: " + together);
            assertEquals(List.of(true, true), together, "both deliveries were in their handlers at the same time (false: one waited " + patience() + " for the other)");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /** Waits, at most {@link #patience()}, until both deliveries entered the handler; an interrupt is an answer of {@code false}, the flag kept. */
    private boolean bothEntered(CountDownLatch entered) {
        try {
            return entered.await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The reason a delivery cancelled by its flow's stop deadline answers with; core's {@code Delivery} decides it. */
    static final String CANCELLED = "cancelled: flow contract stopping after PT30S";

    @Test
    public void stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender() throws Exception {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = component.consumer(inbound(), e -> invoked.incrementAndGet() == 1
                ? answer.future()
                : Future.succeededFuture(new Ack.Reply(e.withHeader("x-answer", "reached-after-stop"))));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        Future<Void> stopped = null;
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("in-flight"));
            Future<Envelope> first = producer.send(sent, call);
            assertTrue(soonEquals(invoked::get, 1), "the handler was invoked");
            stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "stop waits for the delivery in flight: " + stopped);
            Future<Envelope> second = settled(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late")), call));
            assertEquals(1, invoked.get(), "a send after stop began never reaches the handler (it was answered " + second + ")");
            assertFalse(first.isComplete(), "the delivery in flight is answered by its handler, not by the stop: " + first);
            assertFalse(stopped.isComplete(), "stop still waits: the delivery in flight has no answer yet");
            answer.complete(new Ack.Reply(sent.withHeader("x-answer", "given-while-stopping")));
            assertTrue(settled(first).succeeded(), "the answer given while the consumer stops reaches the sender: " + first.cause());
            assertEquals("given-while-stopping", first.result().header("x-answer").orElse(null), "the reply is what the handler answered");
            assertTrue(settled(stopped).succeeded(), "stop completes once the delivery in flight was answered: " + stopped.cause());
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            (stopped == null ? consumer.stop() : stopped).otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
            producer.close().await();
        }
    }

    @Test
    public void aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable() throws Exception {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = component.consumer(inbound(), e -> invoked.incrementAndGet() == 1
                ? answer.future()
                : Future.succeededFuture(new Ack.Reply(e.withHeader("x-answer", "reached-after-stop"))));
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        Future<Void> stopped = null;
        try {
            Future<Envelope> sent = producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("cancel-me")), call);
            assertTrue(soonEquals(invoked::get, 1), "the handler was invoked");
            stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "the consumer is stopping, not stopped, while the delivery is in flight: " + stopped);
            // the stop has acted by the time a later send is answered (a refused connection, a refusal, a behaviour)
            Future<Envelope> late = settled(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late")), call));
            assertEquals(1, invoked.get(), "a send after stop began never reaches the handler (it was answered " + late + ")");
            assertFalse(sent.isComplete(), "the delivery in flight waits for its answer while the consumer stops: " + sent);
            answer.complete(new Ack.Nack(CANCELLED, true));
            assertTrue(settled(sent).failed(), "a cancelled delivery is not a delivery: " + sent);
            NackException nack = assertInstanceOf(NackException.class, sent.cause(), "a producer reports a NACK as NackException");
            assertTrue(nack.retryable(), "the sender may try again once the flow runs again: " + nack);
            assertTrue(nack.reason().contains(CANCELLED), "the reason travels: " + nack.reason());
            assertTrue(settled(stopped).succeeded(), "stop completes once the cancelled delivery was answered: " + stopped.cause());
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            (stopped == null ? consumer.stop() : stopped).otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
            producer.close().await();
        }
    }

    /** The values of {@link Envelope#H_MESSAGE_ID} on {@code e}, whatever the case of the header's name. */
    private static List<String> messageIds(Envelope e) {
        return e.headers().asMap().entrySet().stream()
                .filter(h -> h.getKey().toLowerCase(Locale.ROOT).equals(Envelope.H_MESSAGE_ID)).map(java.util.Map.Entry::getValue).toList();
    }

    /**
     * The partner can dedupe on the message id — the envelope its consumer receives carries the sender's id under
     * {@link Envelope#H_MESSAGE_ID}, once, replacing one forwarded under that name (in any case), for a plain send and a
     * bounded one. The receiver's own id stays its own business.
     */
    @Test
    public void aSendCarriesTheMessageIdToThePartner() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            FakeRuntime rt = FakeRuntime.create();
            Envelope plain = rt.ingress("contract", Headers.empty().with("X-Message-Id", "forwarded-from-upstream"), Buffer.buffer("id-plain"))
                    .withId("contract-plain-1");
            assertTrue(settled(producer.send(plain, call)).succeeded(), "the plain send is answered");
            Envelope bounded = rt.ingress("contract", Headers.empty(), Buffer.buffer("id-bounded")).withId("contract-bounded-2");
            Future<Envelope> second = settled(producer.send(bounded, call, () -> Duration.ofMinutes(1)));
            assertTrue(second.succeeded(), "the bounded send is answered: " + second.cause());
            assertTrue(soonEquals(seen::size, 2), "both delivered: " + seen);
            // Each delivery is told by its body (its hash: what was sent), not by its place in `seen`: a partner that acknowledges before its handler answered
            // runs the two handlers on threads of their own, and the order they record in is not what this case is about.
            List<Envelope> plainOnes = seen.stream().filter(e -> e.body().hash().equals(plain.body().hash())).toList();
            List<Envelope> boundedOnes = seen.stream().filter(e -> e.body().hash().equals(bounded.body().hash())).toList();
            if (plainOnes.size() == 1 && boundedOnes.size() == 1) {
                assertEquals(List.of("contract-plain-1"), messageIds(plainOnes.get(0)),
                        "the plain send carries its message id, the forwarded one replaced: " + plainOnes.get(0).headers());
                assertEquals(List.of("contract-bounded-2"), messageIds(boundedOnes.get(0)),
                        "the bounded send carries its message id: " + boundedOnes.get(0).headers());
            } else {
                // The bodies did not arrive as sent (the body cases fail such a component): the two deliveries cannot be told
                // apart, so what is left to say is that the two ids crossed, once each, and nothing else.
                assertEquals(List.of("[contract-bounded-2]", "[contract-plain-1]"), seen.stream().map(e -> messageIds(e).toString()).sorted().toList(),
                        "the two sends carry their message ids, the forwarded one replaced: " + seen);
            }
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /** A send with no time left — none, or less than none — starts nothing: it fails retryable and no partner sees it. */
    @Test
    public void aSendWithNoTimeLeftIsNotStarted() {
        Component component = component();
        AtomicInteger delivered = new AtomicInteger();
        Consumer consumer = component.consumer(inbound(), e -> { delivered.incrementAndGet(); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        try {
            FakeRuntime rt = FakeRuntime.create();
            for (Duration left : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
                Future<Envelope> refused = settled(producer.send(rt.ingress("contract", Headers.empty(), Buffer.buffer("too-late")), call, () -> left));
                assertTrue(refused.failed(), left + " left: the send fails: " + refused);
                NackException nack = assertInstanceOf(NackException.class, refused.cause(), left + " left: a NackException: " + refused.cause());
                assertTrue(nack.retryable(), left + " left: retryable, a rerun gets fresh time: " + nack);
            }
            Future<Envelope> inTime = settled(producer.send(rt.ingress("contract", Headers.empty(), Buffer.buffer("in-time")), call, () -> Duration.ofMinutes(1)));
            assertTrue(inTime.succeeded(), "witness: with time left the send is delivered: " + inTime.cause());
            assertTrue(soonAtLeast(delivered::get, 1), "the send with time left reached the handler");
            assertEquals(1, delivered.get(), "only the send with time left reached the partner");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /**
     * A send still unanswered when its deadline runs out is cut — a retryable {@link NackException} though the
     * partner never answered — on the component's timer; the deadline is live and re-read when the timer fires, so a send whose
     * deadline was renewed meanwhile is not cut at the instant it was sent with, and its answer comes through.
     */
    @Test
    public void aSendIsCutWhenItsDeadlineRunsOutButNotWhileItIsRenewed() {
        FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
        FakeTimer timer = new FakeTimer(clock);
        Component component = component(timer);
        List<Promise<Ack>> answers = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(inbound(), e -> {
            Promise<Ack> answer = Promise.promise();
            answers.add(answer);
            return answer.future();
        });
        consumer.start().await();
        Producer producer = component.producer(outbound(consumer));
        Call call = call(consumer);
        AtomicReference<Instant> until = new AtomicReference<>(clock.instant().plusSeconds(5));
        Deadline deadline = () -> Duration.between(clock.instant(), until.get());
        try {
            FakeRuntime rt = FakeRuntime.create();
            Envelope renewed = rt.ingress("contract", Headers.empty(), Buffer.buffer("renewed"));
            Future<Envelope> first = producer.send(renewed, call, deadline);
            assertTrue(soonEquals(answers::size, 1), "the handler was invoked");
            clock.advance(Duration.ofSeconds(4));
            assertFalse(first.isComplete(), "1 s left: not cut: " + first);
            until.set(clock.instant().plusSeconds(6));   // renewed: 6 s left, 5 s past the deadline it was sent with
            clock.advance(Duration.ofSeconds(2));
            assertFalse(first.isComplete(), "renewed: not cut at the deadline it was sent with: " + first);
            answers.get(0).complete(new Ack.Reply(renewed.withHeader("x-renewed", "yes")));
            assertTrue(settled(first).succeeded(), "the renewed send's answer comes through: " + first.cause());
            assertEquals("yes", first.result().header("x-renewed").orElse(null), "the reply is what the handler answered");

            until.set(clock.instant().plusSeconds(3));
            Future<Envelope> second = producer.send(rt.ingress("contract", Headers.empty(), Buffer.buffer("cut")), call, deadline);
            assertTrue(soonEquals(answers::size, 2), "the second send reached the handler");
            clock.advance(Duration.ofMillis(2_999));
            assertFalse(second.isComplete(), "1 ms left: not cut yet: " + second);
            clock.advance(Duration.ofMillis(1));
            assertTrue(settled(second).failed(), "cut at its deadline, the partner silent: " + second);
            NackException nack = assertInstanceOf(NackException.class, second.cause(), "a cut send fails with a NackException: " + second.cause());
            assertTrue(nack.retryable(), "retryable: the message may be tried again: " + nack);
        } finally {
            answers.forEach(a -> a.tryComplete(new Ack.Nack("contract cleanup", true)));   // a stop drains the answers in flight
            producer.close().await();
            consumer.stop().await();
        }
    }

    /**
     * The target system a producer calls: {@code scheme://host:port}, lower-cased, the port filled in, no userinfo, path,
     * query or fragment — or empty when the component names none. Answered from the {@link Outbound} alone: the same answer
     * twice, for an equal copy, from a fresh component, and while and after the consumer it reaches runs.
     */
    @Test
    public void targetIsAnsweredFromTheOutboundAloneAndStable() {
        Component component = component();
        Consumer consumer = component.consumer(inbound(), ACCEPT);
        consumer.start().await();
        Outbound where;
        Optional<String> target;
        try {
            where = outbound(consumer);
            target = component.target(where);
            assertNotNull(target, "target answers an Optional, never null");
            target.ifPresent(t -> assertTrue(TARGET.matcher(t).matches(),
                    "a target is scheme://host:port, lower-cased, without userinfo, path, query or fragment: '" + t + "'"));
            assertEquals(target, component.target(where), "asked twice");
            Outbound copy = new Outbound(where.connection().map(c -> new Connection(c.key(), new HashMap<>(c.values()), new HashMap<>(c.secrets()))));
            assertEquals(target, component.target(copy), "an equal copy of the outbound");
            assertEquals(target, component().target(where), "a fresh component");
        } finally {
            consumer.stop().await();
        }
        assertEquals(target, component.target(where), "after the consumer stopped");
    }

    /** {@code scheme://host:port}: lower case, a bracketed IPv6 literal or a host without '@', '/', '?', '#', ':'; a port. */
    private static final java.util.regex.Pattern TARGET =
            java.util.regex.Pattern.compile("[a-z][a-z0-9+.-]*://(\\[[0-9a-f:.]+\\]|[a-z0-9._~%!$&'()*+,;=-]+):[0-9]{1,5}");
}
