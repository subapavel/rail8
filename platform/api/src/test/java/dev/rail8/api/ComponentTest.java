package dev.rail8.api;

import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The defaults of {@link Component}: a component that does not say where its consumers listen holds no port, one that names
 * no polled place is never run under a lease, one that names no target gets a limit per connection, one that names no
 * node-local address reaches outside the node, and one that names no prefix serves nothing on the node's HTTP port.
 */
class ComponentTest {

    /** A component implementing only what it must: it polls, subscribes or connects out, as far as anyone can tell. */
    static final Component BARE = new Component() {
        @Override public String scheme() { return "bare"; }

        @Override public Forms forms() {
            return new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));
        }

        @Override
        public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
            throw new UnsupportedOperationException("no consumer in this test");
        }

        @Override
        public Producer producer(Outbound out) {
            throw new UnsupportedOperationException("no producer in this test");
        }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    };

    static final Connection PARTNER = new Connection("partner", Map.of("host", "partner.example", "port", 22, "url", "https://partner.example/orders"), Map.of());

    static final Inbound IN = new Inbound("orders", Optional.of(PARTNER), Map.of("address", "/orders", "dir", "/outbox"), 1024);

    @Test
    void aComponentReadsNoCredentialByDefault() {
        assertEquals(java.util.Set.of(), BARE.reads(), "an adapter that declares nothing reads no credential from its callers");
    }

    /**
     * The default consumer of an entry: what the handler-only consumer takes in is entered with nothing presented, and reaches
     * the handler that the entry gave; a refusal fails that message as a failed handler would.
     */
    @Test
    void theDefaultConsumerOfAnEntryEntersEachMessageWithNothingPresented() {
        java.util.List<Presented> presented = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Function<Envelope, Future<Ack>>> built = new java.util.concurrent.atomic.AtomicReference<>();
        Component handlerOnly = new Component() {
            @Override public String scheme() { return BARE.scheme(); }
            @Override public Forms forms() { return BARE.forms(); }
            @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
                built.set(handler);
                return BARE_CONSUMER;
            }
            @Override public Producer producer(Outbound out) { return BARE.producer(out); }
            @Override public Future<Health> health() { return BARE.health(); }
        };
        Entry entry = Entry.of(p -> {
            presented.add(p);
            return p.via().remote().isEmpty() && presented.size() == 1
                    ? Future.succeededFuture(e -> Future.succeededFuture(new Ack.Accepted("taken-" + e.id())))
                    : Future.failedFuture(new Refused(Refused.Reason.UNKNOWN, Optional.empty()));
        });
        handlerOnly.consumer(IN, entry);
        Body body = new InMemoryBlobStore(1_000).write(BlobKey.body("m1", 0), io.vertx.core.buffer.Buffer.buffer("<a/>"), 100).await();
        Envelope e = Envelope.ingress("m1", "orders", Headers.empty(), body);
        assertEquals(new Ack.Accepted("taken-m1"), built.get().apply(e).result(), "the message reaches the handler the entry gave");
        assertEquals(java.util.List.of(new Presented.Nothing(Presented.Via.of("", false))), presented, "entered with nothing presented");
        assertEquals("unknown-caller", ((Refused) built.get().apply(e).cause()).code(), "a refusal fails that message");
    }

    static final Consumer BARE_CONSUMER = new Consumer() {
        @Override public Future<Void> start() { return Future.succeededFuture(); }
        @Override public Future<Void> stop() { return Future.succeededFuture(); }
    };

    @Test
    void listensOnIsEmptyByDefault() {
        assertEquals(Optional.empty(), BARE.listensOn(IN), "by default no address, whatever host and port the connection names");
    }

    @Test
    void polledPlaceIsEmptyByDefault() {
        assertEquals(Optional.empty(), BARE.polledPlace(IN), "by default a consumer is not leased, whatever its inbound looks like");
    }

    @Test
    void targetIsEmptyByDefault() {
        assertEquals(Optional.empty(), BARE.target(new Outbound(Optional.of(PARTNER))), "by default no target: the node limits the sends by the connection's key");
    }

    @Test
    void internalAddressIsEmptyByDefault() {
        assertEquals(Optional.empty(), BARE.internalAddress(Direction.OUT, Map.of("address", "/orders")),
                "by default no node-local address, whatever the settings name: the use reaches outside the node");
        assertEquals(Optional.empty(), BARE.internalAddress(Direction.IN, Map.of("address", "/orders")), "nor for the in direction");
    }

    @Test
    void httpPrefixIsEmptyByDefault() {
        assertEquals(Optional.empty(), BARE.httpPrefix(), "by default nothing on the node's HTTP port");
    }
}
