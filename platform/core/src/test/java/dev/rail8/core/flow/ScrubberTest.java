package dev.rail8.core.flow;

import dev.rail8.api.Secret;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The scrubber of the texts the platform writes: every form of each value it is given replaced by its reference. */
class ScrubberTest {

    final Scrubber scrubber = Scrubber.of(Map.of(
            "secret://token", List.of("s3cr3t/v1"),
            "secret://db", List.of(new Secret.Credentials("app", "pw&1"))));

    @Test
    void aTextHoldsEachValueAsItsReferenceInEveryForm() {
        assertEquals("token secret://token, encoded secret://token, password secret://db, basic secret://db",
                scrubber.text("token s3cr3t/v1, encoded s3cr3t%2Fv1, password pw&1, basic YXBwOnB3JjE="));
        assertEquals(List.of("a secret://token", "none"), scrubber.texts(List.of("a s3cr3t/v1", "none")));
    }

    @Test
    void aFailureThatHoldsAValueIsACopyWithItReplacedAndOneThatHoldsNoneIsItself() {
        IOException none = new IOException("refused");
        assertSame(none, scrubber.failure(none));
        assertNull(scrubber.failure(null));
        Throwable copy = scrubber.failure(new IllegalStateException("bad", new IOException("sent pw&1")));
        assertFalse(copy.toString().contains("pw&1"));
        assertEquals("sent secret://db", copy.getCause().getMessage());
    }

    @Test
    void noValuesIsTheIdentity() {
        assertEquals("s3cr3t/v1", Scrubber.of(Map.of()).text("s3cr3t/v1"));
    }
}
