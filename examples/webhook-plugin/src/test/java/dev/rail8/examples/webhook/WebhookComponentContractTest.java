package dev.rail8.examples.webhook;

import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Timer;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.Map;
import java.util.Optional;

/**
 * The webhook component over real sockets on the loopback interface, its consumers mounted on a shared HTTP port of the test's
 * own, passes the contract suite every component passes.
 */
class WebhookComponentContractTest extends ComponentContract {

    static Vertx vertx;
    static SharedHttpPort port;

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

    @Override protected Component component() {
        return component(FakeRuntime.create().timer());
    }

    @Override protected Component component(Timer timer) {
        FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB stream through the server and the client
        return new WebhookComponent(vertx, rt.blobs(), rt.ids(), timer, port);
    }

    /** The consumer is served at {@code /webhook/in}; the in direction reaches no connection. */
    @Override protected Inbound inbound() {
        return new Inbound(FLOW, Optional.empty(), Map.of("address", "/in"), 1_000_000);
    }

    /** The producer sends through a connection to the URL the started consumer is served at. */
    @Override protected Outbound outbound(Consumer started) {
        String url = ((WebhookComponent.WebhookConsumer) started).url().orElseThrow().toString();
        return new Outbound(Optional.of(new Connection("out", Map.of("url", url), Map.of())));
    }
}
