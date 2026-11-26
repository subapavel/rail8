package dev.rail8.api;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.Objects;
import java.util.function.Function;

/**
 * The entry of one listening sender of a flow. The node makes it and builds the sender's consumer with it
 * ({@link Component#consumer(Inbound, Entry)}); the consumer enters every message through it, and a message reaches the
 * flow only through the handler that {@link #enter} gave.
 *
 * <p>It is a class, not a functional interface, so that a lambda passed to {@code consumer(in, …)} still means the
 * handler of {@link Component#consumer(Inbound, Function)}.
 *
 * @since 0.4
 */
public final class Entry {

    private final Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate;

    private Entry(Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * An entry over {@code gate}, which decides what a caller presented: it completes with the handler of that one message,
     * or fails with {@link Refused}. For the node and the testkit.
     */
    public static Entry of(Function<Presented, Future<Function<Envelope, Future<Ack>>>> gate) {
        return new Entry(gate);
    }

    /**
     * Hands over what the caller of one message presented, before any of the message's body is read. Completes with the
     * handler of that one message, or fails with {@link Refused}; any other failure is a fault of the platform, which the
     * adapter answers as it answers a failed handler. Never blocks, and may be called on any thread; called on a Vert.x
     * context, its future completes on that context, so the caller may touch its request there. The handler takes one
     * envelope of this entry's flow, once: it fails with an {@link IllegalArgumentException} for an envelope of another
     * flow and with an {@link IllegalStateException} when applied twice.
     */
    public Future<Function<Envelope, Future<Ack>>> enter(Presented presented) {
        Objects.requireNonNull(presented, "presented");
        Future<Function<Envelope, Future<Ack>>> decided;
        try {
            decided = Objects.requireNonNull(gate.apply(presented), "the entry's gate answered null");
        } catch (RuntimeException failed) {
            decided = Future.failedFuture(failed);
        }
        Context context = Vertx.currentContext();
        if (context == null) return decided;
        Promise<Function<Envelope, Future<Ack>>> here = Promise.promise();
        decided.onComplete(result -> {
            if (Vertx.currentContext() == context) here.handle(result);
            else context.runOnContext(v -> here.handle(result));
        });
        return here.future();
    }
}
