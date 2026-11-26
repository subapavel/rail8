package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** What a caller presented: no record's text shows the user, password, key or token it holds. */
class PresentedTest {

    static final Presented.Via VIA = new Presented.Via("192.0.2.7", true, false, Optional.of("eshop"));

    @Test
    void noRecordsTextShowsWhatTheCallerPresented() {
        List<Presented> all = List.of(
                new Presented.Basic("acme-erp", "s3cret-password", VIA),
                new Presented.ApiKey("s3cret-key", VIA),
                new Presented.Bearer("s3cret-token", VIA),
                new Presented.Certificate(List.of(), VIA),
                new Presented.Nothing(VIA));
        for (Presented p : all) {
            String text = p.toString();
            for (String secret : List.of("acme-erp", "s3cret-password", "s3cret-key", "s3cret-token")) {
                assertFalse(text.contains(secret), p.getClass().getSimpleName() + "'s text shows what was presented: " + text);
            }
            assertEquals(true, text.contains("192.0.2.7"), "its text says how the request reached the node: " + text);
        }
    }

    @Test
    void twoPresentationsEqualButForViaAreNotEqual() {
        Presented.Via clear = new Presented.Via("192.0.2.7", false, false, Optional.of("eshop"));
        assertNotEquals(new Presented.Basic("u", "p", VIA), new Presented.Basic("u", "p", clear), "how the request came is part of what was presented");
        assertEquals(new Presented.Basic("u", "p", VIA), new Presented.Basic("u", "p", VIA));
    }

    @Test
    void aViaOfAnAdaptersOwnListenerReadsNoBrowserAndNoSender() {
        assertEquals(new Presented.Via("10.0.0.1", true, false, Optional.empty()), Presented.Via.of("10.0.0.1", true));
    }
}
