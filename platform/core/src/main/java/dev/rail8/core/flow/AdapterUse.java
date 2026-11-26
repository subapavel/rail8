package dev.rail8.core.flow;

import dev.rail8.api.settings.Direction;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One end of a flow of {@code schemaVersion: 2} as written: its sender, or one of its send steps. It names an adapter, the
 * connection of a system it reaches through, by key, when the adapter connects in that direction, and the settings in the flow,
 * each literal.
 *
 * @param step          the send step's name; {@link #SENDER} for the sender
 * @param adapter       the adapter's type, the scheme its component serves
 * @param connection    the connection key, {@code <system key>/<connection>}; empty for an adapter that reaches no connection
 *                      in {@code direction}, such as {@code http} receiving or {@code direct}
 * @param direction     {@link Direction#IN} for the sender, {@link Direction#OUT} for a send step
 * @param settings      the settings in the flow as written
 * @param maxReplyBytes a send step's largest reply taken; empty for the flow's {@code limits.maxBodyBytes}, and for the sender
 * @since 0.4
 */
public record AdapterUse(String step, String adapter, Optional<String> connection, Direction direction, Map<String, Object> settings,
                         OptionalLong maxReplyBytes) {

    /** The step a sender is named as where a step is named: its uses, its problems. */
    public static final String SENDER = "from";

    public AdapterUse {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(direction, "direction");
        settings = Map.copyOf(settings);
        Objects.requireNonNull(maxReplyBytes, "maxReplyBytes");
    }

    /** How a problem names it: {@code from}, or {@code step '<name>'}. */
    public String label() {
        return direction == Direction.IN ? SENDER : "step '" + step + "'";
    }
}
