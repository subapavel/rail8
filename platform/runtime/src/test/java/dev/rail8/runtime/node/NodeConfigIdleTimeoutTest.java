package dev.rail8.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.node.NodeConfigPoolsTest.inProcess;
import static dev.rail8.runtime.node.NodeConfigPoolsTest.read;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The longest a pooled Postgres connection goes without a byte read or written, {@code RAIL8_PG_IDLE_TIMEOUT} (ISO-8601,
 * PT0.001S to PT1H, default PT1M), read by {@link NodeConfig#from} and carried on {@link NodeConfig.Pg}; a value outside the
 * range is refused at start naming the key. The in-process constructors take the default.
 */
class NodeConfigIdleTimeoutTest {

    static final String KEY = "RAIL8_PG_IDLE_TIMEOUT";

    @Test
    void unsetTheIdleTimeoutIsOneMinute() {
        assertAll(
                () -> assertEquals(Duration.ofMinutes(1), read().pg().idleTimeout(), KEY + " unset: PT1M"),
                () -> assertEquals(Duration.ofMinutes(1), new NodeConfig.Pg("db", 5432, "rail8", "rail8", "", 16).idleTimeout(),
                        "an in-process Pg takes PT1M"));
    }

    @ParameterizedTest(name = "RAIL8_PG_IDLE_TIMEOUT {0} → {1} ms")
    @CsvSource({"PT0.001S, 1", "PT0.3S, 300", "PT5M, 300000", "PT1H, 3600000"})
    void theIdleTimeoutIsAcceptedWithinItsRange(String raw, long millis) {
        assertEquals(Duration.ofMillis(millis), read(KEY, raw).pg().idleTimeout(), KEY + " " + raw + " is accepted as given");
    }

    @Test
    void anIdleTimeoutOutsideItsRangeIsRefusedNamingTheKey() {
        Map<String, Duration> refused = new LinkedHashMap<>();
        refused.put("zero", Duration.ZERO);
        refused.put("negative", Duration.ofSeconds(-1));
        refused.put("below a millisecond", Duration.ofNanos(999_999));
        refused.put("above an hour by a millisecond", Duration.ofHours(1).plusMillis(1));
        refused.put("Long.MAX_VALUE s", Duration.ofSeconds(Long.MAX_VALUE));
        assertAll(refused.entrySet().stream().map(c -> () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> inProcess(withIdleTimeout(c.getValue())),
                    c.getKey() + ": a node over it is refused");
            assertTrue(e.getMessage().contains(KEY), c.getKey() + ": the refusal names " + KEY + ": " + e.getMessage());
        }));
    }

    @Test
    void anIdleTimeoutFromTheEnvironmentOutsideItsRangeIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(KEY, "PT2H"));
        assertTrue(e.getMessage().contains(KEY) && e.getMessage().contains("PT2H"), "the refusal names the key and the value: " + e.getMessage());
    }

    static NodeConfig.Pg withIdleTimeout(Duration idleTimeout) {
        return new NodeConfig.Pg("db", 5432, "rail8", "rail8", "", 16, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, 5, Duration.ofSeconds(5), 1024, idleTimeout);
    }
}
