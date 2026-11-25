package dev.rail8.cli;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.Deadline;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.components.mock.MockComponent;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The {@code direct} component of a {@code rail8 test} node. A call between flows runs for real, on the direct module's
 * component: a call to an address a flow of the package or of a dependency serves runs that flow. An endpoint the case's mocks
 * name is the mock's instead, in and out, as any other endpoint a case mocks. The flow form, the internal address and the health are the
 * real component's, so the deploy checks the addresses and the cycles of calls as a node does. Its forms reach a connection both ways,
 * one that describes nothing, so every consumer and producer is built for a connection whose key is the endpoint's id: the
 * mock's get it as it is, the real component's without it, as a node builds them.
 *
 * <p>What a real endpoint saw is recorded as the mock records it: every envelope sent on an out endpoint ({@link #sent},
 * {@link #sentTo}), retries included, and every envelope an in endpoint's handler was given ({@link #delivered}). The lists are
 * read without a lock.
 *
 * <p>{@link #ingest} sends a case's fixture to a flow fed by {@code direct} through a real producer, as a caller at depth 0 would.
 */
final class Calls implements Component, FlowTests.Door {

    /** The flow of the caller that sends a case's fixture: the test itself, named nowhere in the output. */
    static final String CALLER = "rail8-test";

    private final Component real;
    private final MockComponent mock;
    private final Set<String> mocked;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final Map<String, List<Envelope>> delivered = new ConcurrentHashMap<>();
    private final Map<String, Inbound> served = new ConcurrentHashMap<>();   // by in endpoint name: what its real consumer was built for

    /**
     * @param real the calls that run: the direct module's component
     * @param mock the {@code direct} mock, bound to the case's behaviours
     * @param mocked the endpoints the case's mocks name: the mock's
     * @param blobs where {@link #ingest} stores a fixture's bytes
     * @param ids the ids of the callers {@link #ingest} plays
     */
    Calls(Component real, MockComponent mock, Set<String> mocked, BlobStore blobs, IdGenerator ids) {
        this.real = Objects.requireNonNull(real, "real");
        this.mock = Objects.requireNonNull(mock, "mock");
        this.mocked = Set.copyOf(mocked);
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override public String scheme() { return real.scheme(); }

    /** The real component's flow form, its directions reaching a connection that describes nothing: the endpoint's id as its key. */
    @Override
    public Forms forms() {
        Map<Direction, Forms.Reach> reach = new java.util.EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) if (real.forms().serves(d)) reach.put(d, Forms.Reach.CONNECTION);
        return new Forms(Settings.NONE, real.forms().flow(), reach);
    }

    @Override
    public Optional<String> internalAddress(Direction direction, Map<String, Object> settings) { return real.internalAddress(direction, settings); }

    @Override public Future<Health> health() { return real.health(); }

    @Override
    public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(handler, "handler");
        String endpoint = endpoint(in.connection());
        if (mocked.contains(endpoint)) return mock.consumer(in, handler);
        List<Envelope> given = delivered.computeIfAbsent(endpoint, k -> new CopyOnWriteArrayList<>());
        Inbound unconnected = new Inbound(in.flow(), Optional.empty(), in.settings(), in.maxBodyBytes());
        Consumer consumer = real.consumer(unconnected, e -> {
            given.add(e);   // recorded before the handler runs: whoever reads its answer finds it
            return handler.apply(e);
        });
        served.put(endpoint, unconnected);
        return consumer;
    }

    /** The endpoint a connection stands for: its key, the endpoint's id. */
    private static String endpoint(Optional<Connection> connection) {
        return connection.orElseThrow(() -> new IllegalArgumentException("a direct endpoint of a test reaches a connection")).key();
    }

    @Override
    public Producer producer(Outbound out) {
        String endpoint = endpoint(out.connection());
        if (mocked.contains(endpoint)) return mock.producer(out);
        Producer producer = real.producer(new Outbound(Optional.empty()));
        List<Envelope> mine = sentTo.computeIfAbsent(endpoint, k -> new CopyOnWriteArrayList<>());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e, Call call) {
                record(e);
                return producer.send(e, call);
            }

            @Override public Future<Envelope> send(Envelope e, Call call, Deadline d) {
                record(e);
                return producer.send(e, call, d);
            }

            @Override public Future<Void> close() { return producer.close(); }

            private void record(Envelope e) {
                sent.add(e);
                mine.add(e);
            }
        };
    }

    /** Every envelope sent on a real out endpoint, in order. */
    List<Envelope> sent() { return List.copyOf(sent); }

    /** The envelopes sent on the out endpoint named {@code endpoint}: the mock's sends when the case mocks it. */
    List<Envelope> sentTo(String endpoint) {
        if (mocked.contains(endpoint)) return mock.sentTo(endpoint);
        return List.copyOf(sentTo.getOrDefault(endpoint, List.of()));
    }

    /**
     * Sends {@code bytes} with {@code headers} to the flow served on the in endpoint named {@code endpoint} as a caller at depth 0
     * would: a message of a new id and of the flow {@value #CALLER}, its body stored under {@code BlobKey.body(id, 0)}, sent through
     * a real producer to the endpoint's address, the reply taken up to the flow's limit. On an endpoint the case mocks, the mock's
     * {@link MockComponent#ingest}.
     *
     * @return what the call gave the caller as a {@link Ack.Reply}; failed as the call failed
     * @throws IllegalStateException no consumer was built on {@code endpoint}
     */
    @Override
    public Future<Ack> ingest(String endpoint, Headers headers, Buffer bytes) {
        if (mocked.contains(endpoint)) return mock.ingest(endpoint, headers, bytes);
        Inbound in = served.get(endpoint);
        if (in == null) throw new IllegalStateException("no consumer of direct runs on endpoint " + endpoint);
        Producer caller = real.producer(new Outbound(Optional.empty()));
        Call call = new Call(CALLER, CALLER, in.settings(), in.maxBodyBytes(), Optional.empty());
        String id = ids.next();
        return blobs.write(BlobKey.body(id, 0), bytes, bytes.length())
                .compose(body -> caller.send(Envelope.ingress(id, CALLER, headers, body), call))
                .map(Ack.Reply::new);
    }

    @Override
    public List<Envelope> delivered(String endpoint) {
        if (mocked.contains(endpoint)) return mock.delivered(endpoint);
        return List.copyOf(delivered.getOrDefault(endpoint, List.of()));
    }

    /** The mock's for an endpoint the case mocks; none on a real one, which takes no file. */
    @Override
    public Collection<String> undelivered(String endpoint) {
        return mocked.contains(endpoint) ? mock.undelivered(endpoint) : List.of();
    }

    /** The mock's for an endpoint the case mocks; none on a real one, which takes no file. */
    @Override
    public Collection<String> remaining(String endpoint) {
        return mocked.contains(endpoint) ? mock.remaining(endpoint) : List.of();
    }
}
