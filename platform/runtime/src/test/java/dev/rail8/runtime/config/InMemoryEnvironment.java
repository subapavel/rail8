package dev.rail8.runtime.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An {@link Environment} a test fills and activates by hand: each change takes a new snapshot one generation higher and calls
 * the listeners with it, in order.
 */
final class InMemoryEnvironment implements Environment {

    private final String name;
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot held = new Snapshot(0, false, Map.of(), Map.of(), Map.of(), 0);

    InMemoryEnvironment(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Snapshot current() {
        return held;
    }

    @Override
    public void onActivation(Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Puts {@code state} in force as the connection {@code key}. */
    synchronized InMemoryEnvironment connection(String key, ConnectionState state) {
        Map<String, ConnectionState> connections = new HashMap<>(held.connections());
        connections.put(key, state);
        return take(held.mocksAllowed(), connections, held.instanceNames());
    }

    /** Removes the connection {@code key}. */
    synchronized InMemoryEnvironment remove(String key) {
        Map<String, ConnectionState> connections = new HashMap<>(held.connections());
        connections.remove(key);
        return take(held.mocksAllowed(), connections, held.instanceNames());
    }

    /** Puts {@code instanceName} in force as the instance name of the system {@code key}. */
    synchronized InMemoryEnvironment instanceName(String key, String instanceName) {
        Map<String, String> names = new HashMap<>(held.instanceNames());
        names.put(key, instanceName);
        return take(held.mocksAllowed(), held.connections(), names);
    }

    /** Allows or refuses mocks. */
    synchronized InMemoryEnvironment mocksAllowed(boolean allowed) {
        return take(allowed, held.connections(), held.instanceNames());
    }

    private InMemoryEnvironment take(boolean mocksAllowed, Map<String, ConnectionState> connections, Map<String, String> names) {
        Snapshot next = new Snapshot(held.generation() + 1, mocksAllowed, connections, names, Map.of(), 0);
        held = next;
        for (Consumer<Snapshot> listener : listeners) listener.accept(next);
        return this;
    }
}
