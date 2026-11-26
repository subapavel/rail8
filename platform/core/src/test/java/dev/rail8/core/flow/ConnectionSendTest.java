package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Snapshot;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static dev.rail8.core.flow.DeliveryTest.until;
import static dev.rail8.core.flow.FlowBuilderTargetTest.failureOf;
import static dev.rail8.core.flow.FlowBuilderTargetTest.run;
import static dev.rail8.core.flow.FlowBuilderTest.NO_FILES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flow of {@code schemaVersion: 2} as core reads and runs it: its sender and its send steps name an adapter, a connection by
 * key and the settings in the flow, and each attempt of a send through a connection asks {@link ConnectionSends} for the
 * connection's producer, holds it until its send settles, and names the connection's key in its row of the history.
 */
class ConnectionSendTest {

    final FakeRuntime rt = FakeRuntime.create();

    /** An adapter {@code partner} that connects to send, its flow form a {@code path} defaulting to {@code /}; its producers record each call. */
    static final class Partner implements Component {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<Outbound> built = new CopyOnWriteArrayList<>();
        final boolean connects;

        Partner(boolean connects) { this.connects = connects; }

        @Override public String scheme() { return connects ? "partner" : "local"; }

        @Override public Forms forms() {
            Settings connection = connects ? Settings.of(Field.text("url", "URL", "Where.").server().out()) : Settings.NONE;
            return new Forms(connection, Settings.of(Field.text("path", "Path", "What.").byDefault("/").out(), Field.text("method", "Method", "How.").byDefault("POST").out()),
                    Map.of(Direction.OUT, connects ? Forms.Reach.CONNECTION : Forms.Reach.NO_CONNECTION));
        }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

        @Override public Producer producer(Outbound out) {
            built.add(out);
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e, Call call) {
                    calls.add(call);
                    return Future.succeededFuture(e.withHeader("sent", call.settings().get("path").toString()));
                }
                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
    }

    /** The node's side: each attempt counted, its producer one the test hands, failing while {@link #refusal} is set; settles counted. */
    static final class Sends implements ConnectionSends {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final AtomicInteger settled = new AtomicInteger();
        volatile Producer producer;
        volatile String refusal;
        volatile int refusals;

        @Override public Attempt attempt(String key, String adapter, Duration within) {
            asked.add(key + " " + adapter);
            if (refusal != null && refusals-- > 0) throw new NackException("connection '" + key + "' cannot serve the send: " + refusal, true);
            Producer p = producer;
            return new Attempt() {
                @Override public Producer producer() { return p; }
                @Override public Optional<String> target() { return Optional.of("partner://erp"); }
                @Override public void settled() { settled.incrementAndGet(); }
            };
        }
    }

    static Map<String, Object> doc(Map<String, Object> from, List<Map<String, Object>> steps) {
        return new HashMap<>(Map.of("schemaVersion", 2, "kind", "flow", "id", "demo/invoices", "version", "1.0.0", "from", from,
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", 4096),
                "retry", Map.of("attempts", 1, "initial", "1s", "max", "1s"), "steps", steps));
    }

    static final Map<String, Object> HTTP_SENDER = Map.of("adapter", "http", "settings", Map.of("address", "/in"));

    static Map<String, Object> send(Map<String, Object> extra) {
        Map<String, Object> step = new HashMap<>(Map.of("type", "to", "name", "partner", "adapter", "partner", "connection", "acme/erp/rest"));
        step.putAll(extra);
        return step;
    }

    @Test
    void aFlowOfSchemaVersionTwoReadsItsSenderAndItsSendSteps() {
        FlowDefinition def = FlowDefinition.from(doc(Map.of("adapter", "sftp", "connection", "billing/sftp", "settings", Map.of("dir", "/inbox")),
                List.of(send(Map.of("settings", Map.of("path", "/invoices"), "maxReplyBytes", 10)))));
        assertEquals(Optional.of(new AdapterUse("from", "sftp", Optional.of("billing/sftp"), Direction.IN, Map.of("dir", "/inbox"), OptionalLong.empty())), def.sender());
        assertEquals("demo/invoices/from", def.fromEndpoint(), "a name no endpoint file has");
        assertEquals(List.of(new AdapterUse("partner", "partner", Optional.of("acme/erp/rest"), Direction.OUT, Map.of("path", "/invoices"), OptionalLong.of(10))), def.sends());
        assertEquals(List.of(), def.referencedEndpoints());
        assertEquals(2, def.uses().size());
    }

    @Test
    void aFlowOfSchemaVersionOneHasNoSenderOfThisShape() {
        Map<String, Object> v1 = doc(Map.of("endpoint", "in"), List.of(Map.of("type", "to", "endpoint", "partner")));
        v1.put("schemaVersion", 1);
        FlowDefinition def = FlowDefinition.from(v1);
        assertEquals(Optional.empty(), def.sender());
        assertEquals(List.of(), def.sends());
        assertEquals("in", def.fromEndpoint());
    }

    @Test
    void whatTheShapeDoesNotTakeIsRefusedNamingTheStep() {
        assertRefused("step 'partner': a computed receiver (by, connections) is not supported yet; name the connection",
                HTTP_SENDER, send(Map.of("by", "${prop.PARTNER}")));
        assertRefused("step 'partner': a computed receiver (by, connections) is not supported yet; name the connection",
                HTTP_SENDER, send(Map.of("connections", Map.of("a", "acme/erp/rest"))));
        assertRefused("step 'partner' endpoint: a flow of schemaVersion 2 names its receiver's adapter and connection, not an endpoint",
                HTTP_SENDER, send(Map.of("endpoint", "partner")));
        assertRefused("step 'partner' agreement: a receiver by agreement is reserved, not supported", HTTP_SENDER, send(Map.of("agreement", "x")));
        assertRefused("step 'partner' settings.path: '${' is not supported yet: every setting is literal", HTTP_SENDER,
                send(Map.of("settings", Map.of("path", "/i/${key.id}"))));
        assertRefused("step 'partner' connection: the connection key 'rail8/erp/rest' starts with 'rail8', which belongs to the platform: no key "
                + "starts with rail8, subscribers or by-agreement", HTTP_SENDER, send(Map.of("connection", "rail8/erp/rest")));
        assertRefused("from.endpoint: a flow of schemaVersion 2 names its sender's adapter and connection, not an endpoint",
                Map.of("adapter", "http", "endpoint", "in"), send(Map.of()));
        assertRefused("from.senders: who may send to a flow is not supported yet; remove it", Map.of("adapter", "http", "senders", List.of("eshop")), send(Map.of()));
        assertRefused("from settings.address: '${' is not supported yet: every setting is literal",
                Map.of("adapter", "http", "settings", Map.of("address", "/${x}")), send(Map.of()));
    }

    static void assertRefused(String message, Map<String, Object> from, Map<String, Object> step) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc(from, List.of(step))));
        assertEquals(message, refused.getMessage());
    }

    FlowBuilder builder(Partner partner, Sends sends, InMemoryMessageStore store) {
        return new FlowBuilder(rt.withComponent(partner).runtime(), List.of(), List.of(), Map.of()).connections(sends).recording(store, () -> 0)
                .runs(new Runs(rt.clock(), new FakeTimer(rt.clock()), Duration.ofSeconds(30)));   // the Runtime's timer holds the step's retry only
    }

    Envelope message() {
        return rt.ingress("demo/invoices", Headers.empty(), Buffer.buffer("<x/>"));
    }

    @Test
    void eachAttemptTakesTheConnectionsProducerAndHoldsItUntilItsSendSettles() throws Exception {
        Partner partner = new Partner(true);
        Sends sends = new Sends();
        Promise<Envelope> answer = Promise.promise();
        List<Call> calls = new CopyOnWriteArrayList<>();
        sends.producer = new Producer() {
            @Override public Future<Envelope> send(Envelope e, Call call) { calls.add(call); return answer.future(); }
            @Override public Future<Void> close() { throw new AssertionError("the connection's producer is not the attempt's to close"); }
        };
        InMemoryMessageStore store = new InMemoryMessageStore();
        Pipeline p = builder(partner, sends, store).build(FlowDefinition.from(doc(HTTP_SENDER, List.of(send(Map.of("settings", Map.of("path", "/invoices")))))), NO_FILES);
        Envelope in = message();
        CompletableFuture<Envelope> out = run(p, in);
        until(() -> calls.size() == 1, () -> "the send is out");
        assertEquals(0, sends.settled.get(), "held while its send is out");
        answer.complete(in);
        out.get();
        assertEquals(1, sends.settled.get());
        assertEquals(List.of("acme/erp/rest partner"), sends.asked);
        Call call = calls.getFirst();
        assertEquals(Map.of("path", "/invoices", "method", "POST"), call.settings(), "the settings as written, over the flow form's defaults");
        assertEquals(4096, call.maxReplyBytes(), "the flow's maxBodyBytes without maxReplyBytes");
        assertEquals("demo/invoices", call.flow());
        assertEquals("partner", call.step());
        assertTrue(partner.built.isEmpty(), "the node builds the connection's producers, never the step");
        List<Snapshot> rows = store.timeline(in.id()).await();
        Snapshot partnerRow = rows.stream().filter(s -> s.stepName().equals("partner")).findFirst().orElseThrow();
        assertEquals("acme/erp/rest", partnerRow.versions().get(FlowBuilder.CONNECTION), "the history names the connection's key");
    }

    @Test
    void aConnectionThatCannotServeFailsTheAttemptRetryablyAndTheNextAttemptAsksAgain() throws Exception {
        Partner partner = new Partner(true);
        Sends sends = new Sends();
        sends.producer = partner.producer(new Outbound(Optional.empty()));
        sends.refusal = "stopped by ops at 2026-11-26T10:00:00Z";
        sends.refusals = 1;
        InMemoryMessageStore store = new InMemoryMessageStore();
        Pipeline p = builder(partner, sends, store).build(FlowDefinition.from(doc(HTTP_SENDER, List.of(send(Map.of("retryable", true))))), NO_FILES);
        Envelope in = message();
        CompletableFuture<Envelope> out = run(p, in);
        until(() -> rt.timer().pending() == 1, () -> "the step's retry is scheduled");
        assertEquals(Duration.ofSeconds(1), rt.timer().nextDelay());
        rt.clock().advance(Duration.ofSeconds(1));
        assertEquals("/", out.get().headers().get("sent"));
        assertEquals(2, sends.asked.size(), "a retry is a new attempt, reading the connection again");
        assertEquals(1, sends.settled.get(), "the refused attempt took no producer");

        sends.refusals = 1;
        Pipeline once = builder(partner, sends, store).build(FlowDefinition.from(doc(HTTP_SENDER, List.of(send(Map.of())))), NO_FILES);
        Envelope second = message();
        NackException failed = assertInstanceOf(NackException.class, failureOf(run(once, second)));
        assertTrue(failed.retryable());
        assertEquals("connection 'acme/erp/rest' cannot serve the send: stopped by ops at 2026-11-26T10:00:00Z", failed.reason());
        Snapshot row = store.timeline(second.id()).await().stream().filter(s -> s.stepName().equals("partner")).findFirst().orElseThrow();
        assertEquals(Snapshot.Outcome.FAILED, row.outcome());
        assertEquals("acme/erp/rest", row.versions().get(FlowBuilder.CONNECTION));
        assertTrue(row.error().orElseThrow().contains("stopped by ops"), row.error().toString());
    }

    @Test
    void anAdapterThatConnectsNoWhereSendsThroughOneProducerOfItsOwn() throws Exception {
        Partner local = new Partner(false);
        Sends sends = new Sends();
        Map<String, Object> step = new HashMap<>(Map.of("type", "to", "name", "call", "adapter", "local", "settings", Map.of("path", "/orders")));
        Pipeline p = builder(local, sends, new InMemoryMessageStore()).build(FlowDefinition.from(doc(HTTP_SENDER, List.of(step))), NO_FILES);
        assertEquals(List.of(new Outbound(Optional.empty())), local.built, "built once, without a connection");
        assertEquals("/orders", run(p, message()).get().headers().get("sent"));
        assertEquals(List.of(), sends.asked);
    }

    @Test
    void theConnectionIsNamedExactlyWhenTheAdapterConnects() {
        Sends sends = new Sends();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> builder(new Partner(true), sends, new InMemoryMessageStore())
                .build(FlowDefinition.from(doc(HTTP_SENDER, List.of(new HashMap<>(Map.of("type", "to", "name", "partner", "adapter", "partner"))))), NO_FILES));
        assertTrue(missing.getMessage().endsWith("the adapter 'partner' sends through a connection: name it"), missing.getMessage());
        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class, () -> builder(new Partner(false), sends, new InMemoryMessageStore())
                .build(FlowDefinition.from(doc(HTTP_SENDER, List.of(send(Map.of("adapter", "local"))))), NO_FILES));
        assertTrue(extra.getMessage().endsWith("the adapter 'local' sends through no connection: name none"), extra.getMessage());
        assertFalse(new ArrayList<>(sends.asked).iterator().hasNext());
    }
}
