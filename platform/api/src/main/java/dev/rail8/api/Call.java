package dev.rail8.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One send: the flow and the step it is for, every setting in the flow (the values of the flow form's fields serving the
 * out direction, defaults filled in), the largest reply taken, and the checked address of an address field. A producer
 * reads the flow's settings only from here.
 *
 * @param flow the flow's qualified id, {@code <package>/<flow>}
 * @param step the name of the send step
 * @param settings the settings in the flow, by field name; an immutable copy
 * @param maxReplyBytes the largest reply body taken, in bytes, at least 1
 * @param address the checked address of an address field; empty for a step without one
 * @since 0.4
 */
public record Call(String flow, String step, Map<String, Object> settings, long maxReplyBytes, Optional<URI> address) {

    public Call {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(step, "step");
        settings = Map.copyOf(settings);
        if (maxReplyBytes < 1) throw new IllegalArgumentException("maxReplyBytes must be at least 1, was " + maxReplyBytes);
        Objects.requireNonNull(address, "address");
    }
}
