package dev.rail8.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One sender of a flow, what a consumer is built for: its connection when its adapter has one for the in direction, its
 * settings in the flow (the values of the flow form's fields serving the in direction, defaults filled in) and the
 * largest body it takes.
 *
 * @param flow the flow's qualified id, {@code <package>/<flow>}: the flow of every envelope the consumer hands on
 * @param connection the connection, empty when the in direction reaches none
 * @param settings the settings in the flow, by field name; an immutable copy
 * @param maxBodyBytes the largest body taken, in bytes, at least 1: the flow's {@code limits.maxBodyBytes}
 * @since 0.4
 */
public record Inbound(String flow, Optional<Connection> connection, Map<String, Object> settings, long maxBodyBytes) {

    public Inbound {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(connection, "connection");
        settings = Map.copyOf(settings);
        if (maxBodyBytes < 1) throw new IllegalArgumentException("maxBodyBytes must be at least 1, was " + maxBodyBytes);
    }
}
