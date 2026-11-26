package dev.rail8.runtime.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The environment a test fills by hand takes one snapshot per change, one generation higher, and tells each listener in order. */
class InMemoryEnvironmentTest {

    @Test
    void eachChangeIsANewSnapshotOneGenerationHigherToldToTheListenersInOrder() {
        InMemoryEnvironment env = new InMemoryEnvironment("test");
        List<Long> told = new ArrayList<>();
        env.onActivation(s -> told.add(s.generation()));
        ConnectionState.Values values = new ConnectionState.Values("web", 7, Map.of("url", "https://acme.example"), Optional.empty());

        env.connection("acme/api", values).instanceName("acme", "Acme").mocksAllowed(true).remove("acme/api");

        assertEquals(List.of(1L, 2L, 3L, 4L), told);
        assertEquals(Optional.empty(), env.current().connection("acme/api"));
        assertEquals(Optional.of("Acme"), env.current().instanceName("acme"));
        assertEquals(true, env.current().mocksAllowed());
        assertEquals("test", env.name());
    }
}
