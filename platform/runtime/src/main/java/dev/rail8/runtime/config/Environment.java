package dev.rail8.runtime.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The configuration of the environment as this node holds it in memory: the connections' values or mocks with their stops,
 * the systems' instance names and whether mocks are allowed. Adapters and {@code rail8-core} never see it.
 *
 * @since 0.4
 */
public interface Environment {

    /** The environment's name, as {@code RAIL8_ENV} gives it: {@code dev}, {@code test}, {@code prod}. */
    String name();

    /** The snapshot the node holds now; never blocks. An attempt to send reads it once and keeps it. */
    Snapshot current();

    /**
     * Calls {@code listener} once for each snapshot taken after this call, in the order taken. One snapshot may cover several
     * activations, and after a database set back it may carry a lower generation: a listener compares it with the one before.
     */
    void onActivation(Consumer<Snapshot> listener);

    /** The environment {@code name} that holds {@code snapshot} and never activates another: a node or a tool without a store. */
    static Environment fixed(String name, Snapshot snapshot) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(snapshot, "snapshot");
        return new Environment() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Snapshot current() {
                return snapshot;
            }

            @Override
            public void onActivation(Consumer<Snapshot> listener) {
                Objects.requireNonNull(listener, "listener");
            }
        };
    }

    /**
     * One immutable state of the configuration.
     *
     * @param generation    the store's generation it was read at, raised by every activation, stop, start and switch of mocks
     * @param mocksAllowed  whether the environment allows mocks
     * @param connections   each connection in force by its key; a removed one is absent
     * @param instanceNames each system's instance name by its key; a removed one is absent
     * @param leftOut       the objects in force the node left out, each with the reason, at most 100 of them
     * @param leftOutCount  how many objects the node left out
     */
    record Snapshot(long generation, boolean mocksAllowed, Map<String, ConnectionState> connections, Map<String, String> instanceNames,
                    Map<Ref, String> leftOut, int leftOutCount) {

        public Snapshot {
            connections = Map.copyOf(connections);
            instanceNames = Map.copyOf(instanceNames);
            leftOut = Map.copyOf(leftOut);
            if (leftOutCount < leftOut.size()) throw new IllegalArgumentException("the count of objects left out is below those named");
        }

        /** The snapshot of a node that has read nothing: generation -1, no mocks, no object. */
        public static Snapshot empty() {
            return new Snapshot(-1, false, Map.of(), Map.of(), Map.of(), 0);
        }

        /** The connection {@code connectionKey} in force, when the node holds one. */
        public Optional<ConnectionState> connection(String connectionKey) {
            return Optional.ofNullable(connections.get(Objects.requireNonNull(connectionKey, "connectionKey")));
        }

        /** The instance name of the system {@code systemKey}, when the node holds one. */
        public Optional<String> instanceName(String systemKey) {
            return Optional.ofNullable(instanceNames.get(Objects.requireNonNull(systemKey, "systemKey")));
        }
    }
}
