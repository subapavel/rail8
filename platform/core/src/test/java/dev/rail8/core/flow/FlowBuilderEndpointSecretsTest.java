package dev.rail8.core.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Producer;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Snapshot;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handles of a producer's endpoint: a reference in a secret field is the flow's own handle over the source's, asked for the
 * field's kinds, read only when the producer sends, and every value it reads, earlier ones too, is replaced in what the flow
 * records. Off the node each kind the fields take is tried in order until the producer builds.
 */
class FlowBuilderEndpointSecretsTest {

    static final String LOGIN = "secret://partner.login";

    /** An adapter whose password is a plain value, with {@code user} beside it, or credentials, with no {@code user}. */
    static class Login implements Component {
        final List<String> built = new ArrayList<>();
        /** Whether a send fails, quoting the login it read. */
        final java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();

        @Override public String scheme() { return "login"; }

        @Override public Forms forms() {
            return new Forms(Settings.of(Field.text("user", "User", "The login.").out(),
                    Field.secret("password", "Password", "The password.", Secret.Kind.VALUE, Secret.Kind.CREDENTIALS).out()),
                    Settings.NONE, Map.of(Direction.OUT, Forms.Reach.CONNECTION));
        }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }

        @Override public Producer producer(Outbound out) {
            Connection c = out.connection().orElseThrow();
            Secret password = c.secret("password").orElseThrow();
            boolean credentials = password.kind() == Secret.Kind.CREDENTIALS;
            if (credentials && c.values().containsKey("user")) throw new IllegalArgumentException("user is given by the credentials");
            if (!credentials && !c.values().containsKey("user")) throw new IllegalArgumentException("user is required");
            built.add(password.kind().id());
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e, Call call) {
                    Secret.Credentials login = credentials ? password.credentials() : new Secret.Credentials((String) c.values().get("user"), password.value());
                    if (failing.get()) return Future.failedFuture(new IllegalStateException("refused " + login.user() + ":" + login.password()));
                    return Future.succeededFuture(e.withHeader("user", login.user()));
                }

                @Override public Future<Void> close() { return Future.succeededFuture(); }
            };
        }
    }

    final FakeRuntime rt = FakeRuntime.create();
    final Login login = new Login();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final AtomicReference<Secret.Credentials> now = new AtomicReference<>(new Secret.Credentials("rail8", "first-password-0"));
    final List<String> asked = new ArrayList<>();

    FlowBuilder builder(Map<String, Object> options, SecretSource source) {
        rt.withComponent(login);
        return new FlowBuilder(rt.runtime(), List.of(), List.of(), Map.of("out", new EndpointConfig("out", URI.create("login://out"), options)))
                .secrets(source).recording(store, () -> 0);
    }

    final SecretSource node = (reference, kinds) -> {
        asked.add(reference + " " + kinds.stream().map(Secret.Kind::id).toList());
        return Secret.credentials(reference, now::get);
    };

    Pipeline build(FlowBuilder builder) {
        return builder.build(FlowDefinition.from(Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", 4096),
                "steps", List.of(Map.of("type", "to", "name", "send", "endpoint", "out")))), Path.of(""));
    }

    Envelope in(boolean fail) {
        login.failing.set(fail);
        return rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
    }

    @Test
    void aReferenceIsAskedForTheFieldsKindsAndReadAtEachSend() {
        Pipeline p = build(builder(Map.of("password", LOGIN), node));
        assertEquals(List.of(LOGIN + " [value, credentials]"), asked, "the field's kinds, in its order");
        assertEquals("rail8", p.apply(in(false)).header("user").orElseThrow());
        now.set(new Secret.Credentials("rotated", "second-password-1"));
        assertEquals("rotated", p.apply(in(false)).header("user").orElseThrow(), "an overwrite reaches the next send, no rebuild");
    }

    @Test
    void aFailureQuotingAPasswordReadBeforeAnOverwriteIsRecordedWithItsReference() {
        Pipeline p = build(builder(Map.of("password", LOGIN), node));
        Envelope first = in(true);
        assertThrows(RuntimeException.class, () -> p.apply(first));
        now.set(new Secret.Credentials("rail8", "second-password-1"));
        Envelope second = in(true);
        assertThrows(RuntimeException.class, () -> p.apply(second));
        String before = store.timeline(first.id()).await().getLast().error().orElseThrow();
        String after = store.timeline(second.id()).await().getLast().error().orElseThrow();
        assertFalse(before.contains("first-password-0"), before);
        assertTrue(before.contains("rail8:" + LOGIN), before);
        assertFalse(after.contains("second-password-1"), after);
        Snapshot.Outcome outcome = store.timeline(second.id()).await().getLast().outcome();
        assertEquals(Snapshot.Outcome.FAILED, outcome);
    }

    /** A source of placeholders that hands out one kind at a time, as {@code rail8 validate} does. */
    final SecretSource placeholders = new SecretSource() {
        @Override public Secret handle(String reference, Set<Secret.Kind> kinds) {
            asked.add(reference + " " + kinds.stream().map(Secret.Kind::id).toList());
            return kinds.contains(Secret.Kind.VALUE) ? Secret.of(reference, () -> "invalid") : Secret.credentials(reference, now::get);
        }

        @Override public boolean placeholders() { return true; }
    };

    @Test
    void offTheNodeEachKindIsTriedInTheFieldsOrderUntilTheProducerBuilds() {
        build(builder(Map.of("password", LOGIN), placeholders));
        assertEquals(List.of(LOGIN + " [value]", LOGIN + " [credentials]"), asked, "a value needs user; credentials build");
        assertEquals(List.of("credentials"), login.built);
        asked.clear();
        build(builder(Map.of("user", "rail8", "password", LOGIN), placeholders));
        assertEquals(List.of(LOGIN + " [value]"), asked, "the first kind builds: no other is tried");
    }

    @Test
    void offTheNodeWhenNoKindBuildsTheFirstKindsFailureLeaves() {
        Login strict = new Login() {
            @Override public Producer producer(Outbound out) {
                throw new IllegalArgumentException("refused as " + out.connection().orElseThrow().secret("password").orElseThrow().kind().id());
            }
        };
        rt.withComponent(strict);
        FlowBuilder builder = new FlowBuilder(rt.runtime(), List.of(), List.of(),
                Map.of("out", new EndpointConfig("out", URI.create("login://out"), Map.of("password", LOGIN)))).secrets(placeholders);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> build(builder));
        assertEquals("refused as value", refused.getMessage());
    }

    @Test
    void onTheNodeOnlyTheFieldsKindsAreAskedForOnce() {
        build(builder(Map.of("user", "rail8", "password", LOGIN), (reference, kinds) -> {
            asked.add(reference + " " + kinds.stream().map(Secret.Kind::id).toList());
            return Secret.of(reference, () -> "a-password");
        }));
        assertEquals(List.of(LOGIN + " [value, credentials]"), asked);
        assertEquals(List.of("value"), login.built);
    }
}
