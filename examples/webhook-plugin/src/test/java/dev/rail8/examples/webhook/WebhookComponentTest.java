package dev.rail8.examples.webhook;

import dev.rail8.api.Ack;
import dev.rail8.api.Consumer;
import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the contract suite leaves to the component: the HTTP statuses it answers and how it reads a partner's refusal. An adapter
 * for another protocol tests its own answers and refusals here. Its consumers mount on a shared HTTP port of the test's own, as
 * they would on the node's.
 */
class WebhookComponentTest {

    static Vertx vertx;
    static SharedHttpPort port;
    final FakeRuntime rt = FakeRuntime.create();
    final WebhookComponent component = new WebhookComponent(vertx, rt.blobs(), rt.ids(), rt.timer(), port);

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        port = SharedHttpPort.loopback(vertx, Map.of(WebhookComponent.SCHEME, WebhookComponent.PREFIX)).await();
    }

    @AfterAll
    static void stop() {
        port.close().await();
        vertx.close().await();
    }

    @Test
    void itsPrefixIsItsOwnAndItsInEndpointsNameAnAddress() {
        assertEquals(Optional.of("/webhook"), component.httpPrefix());
        assertEquals(Optional.empty(), component.listensOn(in("/in", 1024)), "no port of its own: the node's HTTP port");
        assertEquals(List.of(new Settings.Problem("/address", Settings.Problem.REQUIRED, "is required")), component.forms().flow().check(Map.of(), Direction.IN));
        assertEquals(List.of(new Settings.Problem("/url", Settings.Problem.REQUIRED, "is required")), component.forms().connection().check(Map.of(), Direction.OUT));
        assertTrue(component.forms().connects(Direction.OUT) && !component.forms().connects(Direction.IN), "a connection for the out direction only");
    }

    @Test
    void theConsumerAnswersAFinalRefusal400AnotherPath404AnotherMethod405AndABodyAboveItsLimit413() {
        Consumer consumer = component.consumer(in("/in", 8), e -> Future.succeededFuture(new Ack.Nack("no such order", false)));
        consumer.start().await();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            assertEquals(Optional.of(URI.create("http://127.0.0.1:" + port.port() + "/webhook/in")), ((WebhookComponent.WebhookConsumer) consumer).url(),
                    "served at its prefix and its address");
            assertEquals(400, status(client, HttpMethod.POST, port.port(), "/webhook/in", "order"));
            assertEquals(404, status(client, HttpMethod.POST, port.port(), "/webhook/other", "order"), "the port answers a path nothing serves");
            assertEquals(405, status(client, HttpMethod.GET, port.port(), "/webhook/in", ""));
            assertEquals(413, status(client, HttpMethod.POST, port.port(), "/webhook/in", "nine bytes"));
        } finally {
            client.close().await();
            consumer.stop().await();
        }
    }

    @Test
    void theConsumerAnswersAnAcceptedMessage202WithItsId() {
        AtomicReference<String> accepted = new AtomicReference<>();
        Consumer consumer = component.consumer(in("/in", 1024), e -> {
            accepted.set(e.id());
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        consumer.start().await();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            HttpClientResponse res = client.request(HttpMethod.POST, port.port(), "127.0.0.1", "/webhook/in").compose(req -> req.send("order")).await();
            assertEquals(202, res.statusCode());
            assertEquals(accepted.get(), res.getHeader("x-message-id"), "the id of the message the handler accepted");
        } finally {
            client.close().await();
            consumer.stop().await();
        }
    }

    @Test
    void aPartnersRefusalFailsTheSendRetryableFor429And5xxAndFinalOtherwise() {
        for (int status : new int[] {400, 404, 429, 500, 503}) {
            HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> req.response().setStatusCode(status).end("why: " + status)))
                    .listen(0, "127.0.0.1").await();
            Producer producer = component.producer(partner("http://127.0.0.1:" + partner.actualPort() + "/"));
            try {
                Future<?> sent = producer.send(rt.ingress("test", dev.rail8.api.Headers.empty(), Buffer.buffer("order")), CALL);
                sent.otherwiseEmpty().await();
                NackException nack = assertInstanceOf(NackException.class, sent.cause(), status + ": " + sent.cause());
                assertEquals(status == 429 || status >= 500, nack.retryable(), status + ": " + nack);
                assertTrue(nack.reason().contains("why: " + status), "the partner's reason travels: " + nack.reason());
            } finally {
                producer.close().await();
                partner.close().await();
            }
        }
    }

    @Test
    void theTargetIsTheUrlsSchemeHostAndPort() {
        assertEquals(Optional.of("http://partner.example:80"), component.target(partner("http://Partner.example/orders?x=1")));
        assertEquals(Optional.of("https://partner.example:443"), component.target(partner("https://partner.example/orders")));
        assertEquals(Optional.of("http://partner.example:8443"), component.target(partner("http://partner.example:8443")));
        assertEquals(Optional.empty(), component.target(partner("http:///orders")), "a url without a host names no partner");
    }

    /** The reply limit is the call's: a reply above it fails the send. */
    @Test
    void aReplyAboveTheCallsLimitFailsTheSend() {
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> req.response().end("r".repeat(100))))
                .listen(0, "127.0.0.1").await();
        Producer producer = component.producer(partner("http://127.0.0.1:" + partner.actualPort() + "/"));
        try {
            dev.rail8.api.Envelope order = rt.ingress("test", dev.rail8.api.Headers.empty(), Buffer.buffer("order"));
            assertEquals(100, producer.send(order, call(100)).await().body().size(), "at the call's limit");
            Future<?> above = producer.send(order, call(99));
            above.otherwiseEmpty().await();
            assertInstanceOf(dev.rail8.api.BodyTooLargeException.class, above.cause(), "above it");
        } finally {
            producer.close().await();
            partner.close().await();
        }
    }

    /** A sender of the flow {@code test} at {@code address}, bodies up to {@code maxBodyBytes}. */
    static Inbound in(String address, long maxBodyBytes) {
        return new Inbound("test", Optional.empty(), Map.of("address", address), maxBodyBytes);
    }

    /** A producer's connection {@code partner} to {@code url}. */
    static Outbound partner(String url) {
        return new Outbound(Optional.of(new Connection("partner", Map.of("url", url), Map.of())));
    }

    /** A call of the step {@code send}, replies up to {@code maxReplyBytes}. */
    static Call call(long maxReplyBytes) {
        return new Call("test", "send", Map.of(), maxReplyBytes, Optional.empty());
    }

    static final Call CALL = call(1024);

    static int status(HttpClientAgent client, HttpMethod method, int port, String path, String body) {
        return client.request(method, port, "127.0.0.1", path).compose(req -> req.send(body)).await().statusCode();
    }
}
