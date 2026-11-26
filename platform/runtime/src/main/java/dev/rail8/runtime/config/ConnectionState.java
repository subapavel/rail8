package dev.rail8.runtime.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A connection in force as a node holds it: its values, or the platform's mock in their place, and whether it is stopped.
 *
 * @since 0.4
 */
public sealed interface ConnectionState permits ConnectionState.Values, ConnectionState.Mocked {

    /** The adapter the connection is for. */
    String adapter();

    /** The connection's activation: changes only when its values or its mock change, and is never drawn again. */
    long version();

    /** Who stopped the connection and when, while it is stopped. */
    Optional<Stop> stopped();

    /** A stop: by whom and when. */
    record Stop(String by, Instant at) {
        public Stop {
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A connection's values by field: a text, a whole number, a boolean, or the list of allowed hosts. A secret field holds
     * its reference {@code secret://<name>}, never a value: the value is read through the secret's handle at each use.
     */
    record Values(String adapter, long version, Map<String, Object> values, Optional<Stop> stopped) implements ConnectionState {
        public Values {
            Objects.requireNonNull(adapter, "adapter");
            values = Map.copyOf(values);
            Objects.requireNonNull(stopped, "stopped");
        }
    }

    /** A connection the platform's mock serves in place of its system. */
    record Mocked(String adapter, long version, MockBehaviour behaviour, Optional<Stop> stopped) implements ConnectionState {
        public Mocked {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(behaviour, "behaviour");
            Objects.requireNonNull(stopped, "stopped");
        }
    }

    /** What the mock does: replies with the message or empty, after a delay, or fails. */
    record MockBehaviour(Reply reply, Duration delay, Optional<Failure> fail) {
        public MockBehaviour {
            Objects.requireNonNull(reply, "reply");
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(fail, "fail");
        }

        /** What a mock replies. */
        public enum Reply { ECHO, EMPTY }

        /** How a mock fails. */
        public enum Failure { RETRYABLE, INVALID }
    }
}
