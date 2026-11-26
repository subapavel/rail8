package dev.rail8.testkit;

import dev.rail8.api.Ack;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import io.vertx.core.Future;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An entry for the tests of a component and of the code around it, built with {@link Entry#of}: it admits a caller as a
 * system or refuses it with a reason, by script, as the node's entry would. The script is the decisions queued with
 * {@link #thenAdmit} and {@link #thenRefuse}, taken one per entered message in order, then the default ({@link #admitting} or
 * {@link #refusing}; admitting as {@value #SYSTEM} at first).
 *
 * <p>It records every {@link Presented} it was handed and every envelope a handler it gave delivered. A handler it gave takes
 * one envelope of its flow, once: it fails with an {@link IllegalArgumentException} for another flow's and with an
 * {@link IllegalStateException} when applied again, counted in {@link #secondApplies()}. It removes from each delivered
 * envelope the headers that carry a caller's proof or choice ({@link #REMOVED_HEADERS}, in any case), and cuts the cookies of
 * the node's UI ({@link #PLATFORM_COOKIES}) out of its {@code cookie}, before the flow's handler sees it. An adapter's
 * test over it so sees no proof delivered, whatever the adapter passes on.
 *
 * @since 0.4
 */
public final class FakeEntry {

    /** The system a fresh fake admits every caller as. */
    public static final String SYSTEM = "contract";

    /** The headers this fake removes from every envelope it delivers: those that carry a caller's proof or choice. */
    public static final Set<String> REMOVED_HEADERS = Set.of("authorization", "x-api-key", "apikey", "rail8-sender", "rail8-proxy-secret",
            "x-forwarded-client-cert");

    /** The cookies of the node's UI, which this fake cuts out of a delivered envelope's {@code cookie}. */
    public static final Set<String> PLATFORM_COOKIES = Set.of("rail8-session", "__Host-rail8-session", "rail8-device", "__Host-rail8-device");

    /** What the entry decides for one message: admit as a system, or refuse. */
    public sealed interface Decision {
        /** Admits the caller as {@code system}. */
        record Admit(String system) implements Decision {
            public Admit { Objects.requireNonNull(system, "system"); }
        }

        /** Refuses the caller for {@code reason}; a refusal that waits says to try again after a second. */
        record Refuse(Refused.Reason reason) implements Decision {
            public Refuse { Objects.requireNonNull(reason, "reason"); }
        }
    }

    private final ConcurrentLinkedDeque<Decision> script = new ConcurrentLinkedDeque<>();
    private volatile Decision otherwise = new Decision.Admit(SYSTEM);
    private final List<Presented> presented = new CopyOnWriteArrayList<>();
    private final List<Envelope> delivered = new CopyOnWriteArrayList<>();
    private final List<String> admitted = new CopyOnWriteArrayList<>();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger secondApplies = new AtomicInteger();

    /** From now on, a caller with no decision queued is admitted as {@code system}. */
    public FakeEntry admitting(String system) {
        otherwise = new Decision.Admit(system);
        return this;
    }

    /** From now on, a caller with no decision queued is refused for {@code reason}. */
    public FakeEntry refusing(Refused.Reason reason) {
        otherwise = new Decision.Refuse(reason);
        return this;
    }

    /** The next caller with no decision before it is admitted as {@code system}. */
    public FakeEntry thenAdmit(String system) {
        script.addLast(new Decision.Admit(system));
        return this;
    }

    /** The next caller with no decision before it is refused for {@code reason}. */
    public FakeEntry thenRefuse(Refused.Reason reason) {
        script.addLast(new Decision.Refuse(reason));
        return this;
    }

    /** An entry of the flow {@code flow} whose admitted messages reach {@code handler}, as the node's would reach the flow. */
    public Entry entry(String flow, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(handler, "handler");
        return Entry.of(p -> {
            presented.add(Objects.requireNonNull(p, "presented"));
            Decision decision = Optional.ofNullable(script.pollFirst()).orElse(otherwise);
            return switch (decision) {
                case Decision.Refuse(Refused.Reason reason) -> {
                    refused.incrementAndGet();
                    boolean waits = reason == Refused.Reason.TOO_MANY || reason == Refused.Reason.BUSY;
                    yield Future.failedFuture(new Refused(reason, waits ? Optional.of(Duration.ofSeconds(1)) : Optional.empty()));
                }
                case Decision.Admit(String system) -> {
                    admitted.add(system);
                    yield Future.succeededFuture(once(flow, handler));
                }
            };
        });
    }

    private Function<Envelope, Future<Ack>> once(String flow, Function<Envelope, Future<Ack>> handler) {
        AtomicBoolean applied = new AtomicBoolean();
        return e -> {
            if (!flow.equals(e.flow())) return Future.failedFuture(new IllegalArgumentException("an envelope of flow '" + e.flow() + "' entered flow '" + flow + "'"));
            if (!applied.compareAndSet(false, true)) {
                secondApplies.incrementAndGet();
                return Future.failedFuture(new IllegalStateException("the handler of one message of flow '" + flow + "' was applied twice"));
            }
            Envelope entered = withoutProof(e);
            delivered.add(entered);
            return handler.apply(entered);
        };
    }

    /** {@code e} without the {@link #REMOVED_HEADERS} and with the {@link #PLATFORM_COOKIES} cut from its {@code cookie}. */
    public static Envelope withoutProof(Envelope e) {
        Map<String, String> kept = new HashMap<>();
        e.headers().asMap().forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (REMOVED_HEADERS.contains(lower)) return;
            if (lower.equals("cookie")) {
                String rest = String.join(";", List.of(value.split(";")).stream().filter(pair -> !PLATFORM_COOKIES.contains(cookieName(pair))).toList()).strip();
                if (!rest.isEmpty()) kept.put(name, rest);
                return;
            }
            kept.put(name, value);
        });
        if (kept.equals(e.headers().asMap())) return e;
        return new Envelope(e.id(), e.flow(), e.step(), new Headers(kept), e.props(), e.keys(), e.body(), e.attachments(), e.views());
    }

    private static String cookieName(String pair) {
        int eq = pair.indexOf('=');
        return (eq < 0 ? pair : pair.substring(0, eq)).strip();
    }

    /**
     * What a caller whose request carries {@code headers} presents, as the node's HTTP port reads a request's head:
     * {@code authorization} ({@code Basic} or {@code Bearer}), then {@code x-api-key}, in any case; a value that cannot be
     * read presents an empty user and password, or an empty token. For a mock that stands in for an adapter on that port.
     */
    public static Presented presented(Headers headers, Presented.Via via) {
        String authorization = header(headers, "authorization");
        if (authorization != null) {
            String v = authorization.strip();
            int space = v.indexOf(' ');
            String kind = (space < 0 ? v : v.substring(0, space)).toLowerCase(Locale.ROOT);
            String rest = space < 0 ? "" : v.substring(space + 1).strip();
            if (kind.equals("bearer")) return new Presented.Bearer(rest, via);
            if (!kind.equals("basic")) return new Presented.Bearer("", via);
            try {
                String decoded = new String(Base64.getDecoder().decode(rest), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                return colon < 0 ? new Presented.Basic("", "", via) : new Presented.Basic(decoded.substring(0, colon), decoded.substring(colon + 1), via);
            } catch (IllegalArgumentException notBase64) {
                return new Presented.Basic("", "", via);
            }
        }
        String key = header(headers, "x-api-key");
        return key != null ? new Presented.ApiKey(key.strip(), via) : new Presented.Nothing(via);
    }

    private static String header(Headers headers, String name) {
        for (Map.Entry<String, String> h : headers.asMap().entrySet()) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    /** What every entered caller presented, in order. */
    public List<Presented> presented() { return List.copyOf(presented); }

    /** What the handlers this entry gave delivered, in order, as the flow got it. */
    public List<Envelope> delivered() { return List.copyOf(delivered); }

    /** The system of every admitted caller, in order. */
    public List<String> admitted() { return List.copyOf(admitted); }

    /** How many callers were refused. */
    public int refused() { return refused.get(); }

    /** How many times a handler this entry gave was applied after its first. */
    public int secondApplies() { return secondApplies.get(); }
}
