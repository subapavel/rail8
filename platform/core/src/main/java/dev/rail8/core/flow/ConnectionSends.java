package dev.rail8.core.flow;

import dev.rail8.api.NackException;
import dev.rail8.api.Producer;

import java.time.Duration;
import java.util.Optional;

/**
 * What a send step of a flow of {@code schemaVersion: 2} asks for each attempt through a connection: the connection's producer
 * at the version one snapshot of the environment holds. The node implements it over its environment; core never reads the
 * environment itself.
 *
 * @since 0.4
 */
public interface ConnectionSends {

    /**
     * The producer of the connection {@code key} for one attempt of a send step of {@code adapter}, built from the values the
     * snapshot read now holds when no producer of that version is there. The attempt keeps it until {@link Attempt#settled()}.
     * It may wait, at most {@code within}, while the connection's old producers that are closing are as many as it allows.
     *
     * @throws NackException retryable, when the connection cannot serve the send: not set, stopped (by whom and when), set
     *         for another adapter, incomplete for sending, or left out by this node; its message names the key and the reason
     *         and never a value
     */
    Attempt attempt(String key, String adapter, Duration within);

    /**
     * One attempt's hold on a connection's producer.
     *
     * @since 0.4
     */
    interface Attempt {
        /** The producer to send through. */
        Producer producer();

        /** The target the producer's sends count against, by its component's name for it; empty for one of its own. */
        Optional<String> target();

        /** The attempt's send settled, or never started: once every attempt that took an old producer has, it closes. Called once. */
        void settled();
    }

    /** A node without an environment: every connection is not set. */
    ConnectionSends NONE = (key, adapter, within) -> {
        throw new NackException("connection '" + key + "' cannot serve the send: not set", true);
    };
}
