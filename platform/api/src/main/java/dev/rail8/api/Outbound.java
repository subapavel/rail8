package dev.rail8.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a producer is built for: one connection, or none for one of the platform's internal adapters. Every flow and step
 * that sends through the connection shares the producer; what a flow decides travels in each {@link Call}.
 *
 * @param connection the connection, empty when the out direction reaches none
 * @since 0.4
 */
public record Outbound(Optional<Connection> connection) {

    public Outbound {
        Objects.requireNonNull(connection, "connection");
    }
}
