package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The entries the node builds its senders' consumers with ({@link dev.rail8.api.Component#consumer(dev.rail8.api.Inbound, Entry)}).
 */
final class Entries {

    private Entries() {}

    /**
     * An entry of the flow {@code flow} that admits every caller, whatever it presented, and hands each message to
     * {@code delivery} through a handler of its own: one that takes one envelope of {@code flow}, once.
     */
    static Entry admitting(String flow, Function<Envelope, Future<Ack>> delivery) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(delivery, "delivery");
        return Entry.of(presented -> Future.succeededFuture(once(flow, delivery)));
    }

    /**
     * A handler of one message of {@code flow}: it hands its envelope to {@code delivery}, and fails with an
     * {@link IllegalArgumentException} for an envelope of another flow and with an {@link IllegalStateException} when applied
     * again.
     */
    static Function<Envelope, Future<Ack>> once(String flow, Function<Envelope, Future<Ack>> delivery) {
        AtomicBoolean applied = new AtomicBoolean();
        return e -> {
            if (!flow.equals(e.flow())) {
                return Future.failedFuture(new IllegalArgumentException("an envelope of flow '" + e.flow() + "' entered flow '" + flow + "'"));
            }
            if (!applied.compareAndSet(false, true)) {
                return Future.failedFuture(new IllegalStateException("the handler of one message of flow '" + flow + "' was applied twice"));
            }
            return delivery.apply(e);
        };
    }
}
