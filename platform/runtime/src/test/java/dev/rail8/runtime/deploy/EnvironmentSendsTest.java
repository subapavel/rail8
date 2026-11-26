package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.flow.ConnectionSends;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.InMemoryEnvironment;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The producers of the connections over an environment: one per version, the swap, the limit of those closing, the refusals. */
class EnvironmentSendsTest {

    static final String KEY = "acme/erp/rest";
    static final Duration WITHIN = Duration.ofSeconds(30);

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-26T10:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    final InMemoryEnvironment environment = new InMemoryEnvironment("dev");
    final Web web = new Web();
    final EnvironmentSends sends = new EnvironmentSends(environment, Map.of("web", web), Secrets.placeholder(), timer);

    static ConnectionState.Values values(long version, String url) {
        return new ConnectionState.Values("web", version, Map.of("url", url), Optional.empty());
    }

    @Test
    void anAttemptTakesTheProducerOfTheConnectionsVersionBuiltOnceFromItsValues() {
        environment.connection(KEY, values(1, "https://erp.example"));
        ConnectionSends.Attempt first = sends.attempt(KEY, "web", WITHIN);
        ConnectionSends.Attempt second = sends.attempt(KEY, "web", WITHIN);
        assertSame(first.producer(), second.producer(), "one producer per connection and version");
        assertEquals(1, web.built.size());
        assertEquals(KEY, web.built.getFirst().out.connection().orElseThrow().key());
        assertEquals("https://erp.example", web.built.getFirst().out.connection().orElseThrow().values().get("url"));
        assertEquals(Optional.of("erp.example"), first.target(), "the target the adapter names for the connection");
    }

    @Test
    void aNewVersionBuildsANewProducerAndTheOldOneClosesOnceItsAttemptsSettled() {
        environment.connection(KEY, values(1, "https://one.example"));
        ConnectionSends.Attempt old = sends.attempt(KEY, "web", WITHIN);
        environment.connection(KEY, values(2, "https://two.example"));
        ConnectionSends.Attempt fresh = sends.attempt(KEY, "web", WITHIN);
        assertEquals(2, web.built.size());
        assertEquals("https://two.example", web.built.get(1).out.connection().orElseThrow().values().get("url"));
        assertFalse(web.built.getFirst().closeCalled, "the old producer serves its attempt until it settled");
        old.settled();
        assertTrue(web.built.getFirst().closeCalled, "settled by all, the old one closes");
        assertFalse(web.built.get(1).closeCalled);
        fresh.settled();
        assertFalse(web.built.get(1).closeCalled, "the producer in force stays");
    }

    @Test
    void aVersionSetBackIsAChangeToo() {
        environment.connection(KEY, values(5, "https://five.example"));
        sends.attempt(KEY, "web", WITHIN).settled();
        environment.connection(KEY, values(3, "https://three.example"));
        sends.attempt(KEY, "web", WITHIN).settled();
        assertEquals(2, web.built.size());
        assertTrue(web.built.getFirst().closeCalled);
    }

    @Test
    void onlyTheChangedConnectionsProducerIsBuiltAgain() {
        environment.connection(KEY, values(1, "https://erp.example"));
        environment.connection("globex/erp/rest", values(2, "https://globex.example"));
        sends.attempt(KEY, "web", WITHIN).settled();
        sends.attempt("globex/erp/rest", "web", WITHIN).settled();
        environment.connection("globex/erp/rest", values(3, "https://globex-2.example"));
        sends.attempt(KEY, "web", WITHIN).settled();
        sends.attempt("globex/erp/rest", "web", WITHIN).settled();
        assertEquals(3, web.built.size());
        assertFalse(web.built.getFirst().closeCalled, "the unchanged connection keeps its producer");
        assertTrue(web.built.get(1).closeCalled);
    }

    @Test
    void anActivationThatRemovesTheConnectionClosesItsIdleProducer() {
        environment.connection(KEY, values(1, "https://erp.example"));
        sends.attempt(KEY, "web", WITHIN).settled();
        environment.remove(KEY);
        assertTrue(web.built.getFirst().closeCalled);
    }

    @Test
    void anAttemptThatWouldNeedAFifthClosingProducerWaitsForOneToClose() throws Exception {
        List<ConnectionSends.Attempt> held = new ArrayList<>();
        for (int v = 1; v <= 5; v++) {
            environment.connection(KEY, values(v, "https://v" + v + ".example"));
            held.add(sends.attempt(KEY, "web", WITHIN));
        }
        environment.connection(KEY, values(6, "https://v6.example"));
        CompletableFuture<ConnectionSends.Attempt> waiting = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                waiting.complete(sends.attempt(KEY, "web", WITHIN));
            } catch (Throwable t) {
                waiting.completeExceptionally(t);
            }
        });
        DeliveryWait.until(() -> timer.pending() == 1, () -> "the attempt waits, its deadline scheduled");
        assertFalse(waiting.isDone(), "four old producers close: the attempt waits");
        held.getFirst().settled();
        ConnectionSends.Attempt sixth = waiting.get(10, TimeUnit.SECONDS);
        assertEquals(6, web.built.size());
        assertSame(web.built.get(5), sixth.producer());
    }

    @Test
    void anAttemptWaitsForAClosingProducerAtMostItsOwnTime() throws Exception {
        for (int v = 1; v <= 5; v++) {
            environment.connection(KEY, values(v, "https://v" + v + ".example"));
            sends.attempt(KEY, "web", WITHIN);
        }
        environment.connection(KEY, values(6, "https://v6.example"));
        CompletableFuture<ConnectionSends.Attempt> waiting = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                waiting.complete(sends.attempt(KEY, "web", Duration.ofSeconds(2)));
            } catch (Throwable t) {
                waiting.completeExceptionally(t);
            }
        });
        DeliveryWait.until(() -> timer.pending() == 1, () -> "the attempt waits, its deadline scheduled");
        clock.advance(Duration.ofSeconds(2));
        Throwable failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS)).getCause();
        NackException nack = (NackException) failed;
        assertTrue(nack.retryable());
        assertEquals("connection 'acme/erp/rest' cannot serve the send: its 4 old producers are still closing after PT2S", nack.reason());
    }

    @Test
    void aConnectionThatCannotServeFailsTheAttemptRetryablyNamingTheReason() {
        assertRefused("connection 'acme/erp/rest' cannot serve the send: not set");
        environment.connection(KEY, new ConnectionState.Values("web", 1, Map.of("url", "https://erp.example"),
                Optional.of(new ConnectionState.Stop("ops", Instant.parse("2026-11-26T09:00:00Z")))));
        assertRefused("connection 'acme/erp/rest' cannot serve the send: stopped by ops at 2026-11-26T09:00:00Z");
        environment.connection(KEY, new ConnectionState.Values("sftp", 2, Map.of("host", "files.example"), Optional.empty()));
        assertRefused("connection 'acme/erp/rest' cannot serve the send: set for the adapter 'sftp', not 'web'");
        environment.connection(KEY, new ConnectionState.Values("web", 3, Map.of(), Optional.empty()));
        assertRefused("connection 'acme/erp/rest' cannot serve the send: incomplete for the out direction: url (required)");
        environment.connection(KEY, new ConnectionState.Mocked("web", 4, new ConnectionState.MockBehaviour(ConnectionState.MockBehaviour.Reply.ECHO,
                Duration.ZERO, Optional.empty()), Optional.empty()));
        assertRefused("connection 'acme/erp/rest' cannot serve the send: left out by this node: it is mocked, and this node serves no mocked connection yet");
        environment.leftOut(KEY, "its binding does not verify: save and activate it again");
        assertRefused("connection 'acme/erp/rest' cannot serve the send: left out by this node: its binding does not verify: save and activate it again");
        assertEquals(0, web.built.size(), "no producer is built for a connection that cannot serve");
    }

    @Test
    void aSecretTheValuesNameThatIsNotSetIsAConnectionNotSet() throws Exception {
        dev.rail8.runtime.secret.MemorySecretStores stores = new dev.rail8.runtime.secret.MemorySecretStores();
        var store = stores.start("node-1", Map.of("RAIL8_SECRETS_KEY", dev.rail8.runtime.secret.MemorySecretStores.KEY), clock, timer);
        Secrets secrets = Secrets.fromEnv(Map.of()).withStore(store);
        EnvironmentSends withStore = new EnvironmentSends(environment, Map.of("web", web), secrets, timer);
        environment.connection(KEY, new ConnectionState.Values("web", 1, Map.of("url", "https://erp.example", "token", "secret://erp-token"), Optional.empty()));
        NackException nack = assertThrows(NackException.class, () -> withStore.attempt(KEY, "web", WITHIN));
        assertEquals("connection 'acme/erp/rest' cannot serve the send: not set: its field 'token': secret 'secret://erp-token' is not set: "
                + "enter it in the secret store", nack.reason());
        dev.rail8.runtime.secret.MemorySecretStores.createValue(store, "erp-token", "s3cr3t-value");
        ConnectionSends.Attempt attempt = withStore.attempt(KEY, "web", WITHIN);
        Secret token = web.built.getFirst().out.connection().orElseThrow().secret("token").orElseThrow();
        assertEquals("s3cr3t-value", token.value(), "the secret field is a live handle of the store's row");
        assertFalse(web.built.getFirst().out.connection().orElseThrow().toString().contains("s3cr3t"));
        attempt.settled();
    }

    void assertRefused(String message) {
        NackException nack = assertThrows(NackException.class, () -> sends.attempt(KEY, "web", WITHIN));
        assertTrue(nack.retryable(), "a connection that cannot serve is a failed send, retried");
        assertEquals(message, nack.reason());
    }

    /** Waits on the test's thread, spinning and never asleep, for a condition another thread brings about, at most 10 s. */
    static final class DeliveryWait {
        static void until(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                if (condition.getAsBoolean()) return;
                Thread.onSpinWait();
                Thread.yield();
            }
            throw new AssertionError("never: " + what.get());
        }
    }

    /** A web-like adapter, out through a connection: its url names the server, its token is a secret. */
    static final class Web implements Component {
        static final Forms FORMS = new Forms(Settings.of(
                Field.text("url", "URL", "Where the partner is.").required().server().out(),
                Field.secret("token", "Token", "The login.").out()),
                Settings.of(Field.text("path", "Path", "Below the url.").out()),
                Map.of(Direction.OUT, Forms.Reach.CONNECTION));
        final List<Built> built = new ArrayList<>();

        @Override public String scheme() { return "web"; }
        @Override public Forms forms() { return FORMS; }
        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public synchronized Producer producer(Outbound out) {
            Built b = new Built(out);
            built.add(b);
            return b;
        }
        @Override public Optional<String> target(Outbound out) {
            return Optional.of(java.net.URI.create((String) out.connection().orElseThrow().values().get("url")).getHost());
        }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** A producer that records its close. */
    static final class Built implements Producer {
        final Outbound out;
        volatile boolean closeCalled;

        Built(Outbound out) {
            this.out = out;
        }

        @Override public Future<Envelope> send(Envelope e, Call call) { return Promise.<Envelope>promise().future(); }
        @Override public Future<Void> close() {
            closeCalled = true;
            return Future.succeededFuture();
        }
    }
}
