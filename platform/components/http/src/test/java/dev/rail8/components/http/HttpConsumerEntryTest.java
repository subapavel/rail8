package dev.rail8.components.http;

import dev.rail8.api.Ack;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP consumer enters each request before it reads a byte of it: a refused caller gets the refusal's answer, whatever its
 * method or size, and nothing is stored or handed on; an entry that fails is a 500; an admitted caller's message reaches the
 * handler the entry gave.
 */
@Timeout(30)
class HttpConsumerEntryTest {

    static Vertx vertx;
    static HttpClientAgent raw;
    static SharedHttpPort port;
    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids(), rt.timer(), port);
    final List<Presented> presented = new CopyOnWriteArrayList<>();
    final List<Envelope> handled = new CopyOnWriteArrayList<>();
    final Function<Envelope, Future<Ack>> handler = e -> {
        handled.add(e);
        return Future.succeededFuture(new Ack.Accepted(e.id()));
    };
    HttpComponent.HttpConsumer consumer;

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        raw = vertx.createHttpClient();
        port = SharedHttpPort.loopback(vertx, Map.of("http", HttpComponent.PREFIX)).await();
    }

    @AfterAll
    static void stop() {
        raw.close().await();
        port.close().await();
        vertx.close().await();
    }

    @AfterEach
    void down() {
        if (consumer != null) consumer.stop().await();
    }

    /** A consumer of the flow {@code orders} with a body limit of 16 bytes, entering through {@code gate}. */
    void consumer(Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate) {
        consumer = (HttpComponent.HttpConsumer) http.consumer(Uses.in("orders", "/in", 16), Entry.of(p -> {
            presented.add(p);
            return gate.apply(p);
        }));
        consumer.start().await();
    }

    /** {@code status content-type body} of a request with {@code body} and the Basic credential of {@code user}. */
    String call(HttpMethod method, String body, String user) {
        String basic = "Basic " + Base64.getEncoder().encodeToString((user + ":pw").getBytes(StandardCharsets.UTF_8));
        return raw.request(new RequestOptions().setMethod(method).setAbsoluteURI(consumer.url().orElseThrow().toString()))
                .compose(req -> { req.putHeader("authorization", basic); return body == null ? req.send() : req.send(Buffer.buffer(body)); })
                .compose(res -> res.body().map(b -> res.statusCode() + " " + res.getHeader("content-type") + " " + b)).await();
    }

    static final String UNKNOWN = "401 application/json {\"code\":\"unknown-caller\",\"error\":\"the caller is not known\"}";

    @Test
    void aRefusedCallerGetsTheRefusalWhateverItsMethodOrSizeAndNothingIsStored() {
        consumer(p -> Future.failedFuture(new Refused(Refused.Reason.UNKNOWN, Optional.empty())));
        assertEquals(UNKNOWN, call(HttpMethod.POST, "<x/>", "acme"));
        assertEquals(UNKNOWN, call(HttpMethod.GET, null, "acme"), "refused before the method is looked at");
        assertEquals(UNKNOWN, call(HttpMethod.POST, "<order>longer than sixteen bytes</order>", "acme"), "refused before the size is");
        assertEquals(0, rt.blobs().objects(), "no byte stored");
        assertEquals(List.of(), handled, "nothing handed on");
        assertTrue(presented.stream().allMatch(p -> p instanceof Presented.Basic b && b.user().equals("acme")), "each request entered with what it presented");
        assertEquals(3, presented.size());
    }

    @Test
    void anEntryThatFailsIsAnswered500AndNothingIsStored() {
        consumer(p -> Future.failedFuture(new IllegalStateException("the index is gone")));
        assertEquals("500 text/plain the flow failed", call(HttpMethod.POST, "<x/>", "acme"));
        assertEquals(0, rt.blobs().objects());
        assertEquals(List.of(), handled);
    }

    @Test
    void anAdmittedCallersMessageReachesTheHandlerTheEntryGaveAndOnlyThenIsItsMethodOrSizeJudged() {
        consumer(p -> Future.succeededFuture(handler));
        assertTrue(call(HttpMethod.POST, "<x/>", "acme").startsWith("202 "), "accepted");
        assertEquals(1, handled.size());
        assertEquals("<x/>", handled.getFirst().body().whole().toString());
        assertTrue(call(HttpMethod.GET, null, "acme").startsWith("405 "), "a GET of an admitted caller");
        assertTrue(call(HttpMethod.POST, "<order>longer than sixteen bytes</order>", "acme").startsWith("413 "), "too large for an admitted caller");
        assertEquals(1, handled.size());
        assertInstanceOf(Presented.Basic.class, presented.getFirst());
    }
}
