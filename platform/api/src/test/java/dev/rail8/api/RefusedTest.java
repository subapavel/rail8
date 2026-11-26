package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A refusal at the entry: its code and status by reason, and a time to try again after only where it waits. */
class RefusedTest {

    @Test
    void eachReasonHasItsCodeAndStatus() {
        Map<Refused.Reason, String> answers = Map.of(
                Refused.Reason.UNKNOWN, "401 unknown-caller",
                Refused.Reason.NOT_ALLOWED, "403 not-allowed",
                Refused.Reason.SENDER_NEEDED, "400 sender-needed",
                Refused.Reason.TOO_MANY, "429 too-many",
                Refused.Reason.BUSY, "503 busy",
                Refused.Reason.TLS_REQUIRED, "403 tls-required");
        for (Refused.Reason reason : Refused.Reason.values()) {
            boolean waits = reason == Refused.Reason.TOO_MANY || reason == Refused.Reason.BUSY;
            Refused r = new Refused(reason, waits ? Optional.of(Duration.ofSeconds(3)) : Optional.empty());
            assertEquals(answers.get(reason), r.status() + " " + r.code(), reason.name());
            assertEquals(Refused.text(reason), r.getMessage(), "its message is the fixed text of its code");
        }
    }

    @Test
    void onlyARefusalThatWaitsTakesATimeToTryAgainAfter() {
        assertThrows(IllegalArgumentException.class, () -> new Refused(Refused.Reason.BUSY, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Refused(Refused.Reason.TOO_MANY, Optional.of(Duration.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> new Refused(Refused.Reason.UNKNOWN, Optional.of(Duration.ofSeconds(1))));
        assertEquals(Optional.of(Duration.ofSeconds(2)), new Refused(Refused.Reason.TOO_MANY, Optional.of(Duration.ofSeconds(2))).retryAfter());
    }
}
