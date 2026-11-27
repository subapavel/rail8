package dev.rail8.core.key;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysTest {

    @Test
    void aSystemKeyHasOneOrTwoSegmentsAndAConnectionKeyTwoOrThree() {
        assertEquals("eshop", Keys.system("eshop"));
        assertEquals("acme/erp", Keys.system("acme/erp"));
        assertEquals("eshop/rest", Keys.connection("eshop/rest"));
        assertEquals("acme/erp/rest", Keys.connection("acme/erp/rest"));
        assertEquals("acme/erp", Keys.systemOf("acme/erp/rest"));
        assertEquals("acme/erp/edi-2026", Keys.credential("acme/erp/edi-2026"));
        assertEquals("acme/erp", Keys.systemOf(Keys.credential("acme/erp/edi-2026")));
        assertThrows(IllegalArgumentException.class, () -> Keys.credential("acme"), "a credential key names its system and itself");
        assertThrows(IllegalArgumentException.class, () -> Keys.credential("rail8/main"));
        assertEquals("eshop", Keys.systemOf("eshop/rest"));
    }

    @Test
    void aKeyOfOneSegmentTooFewOrTooManyIsRefusedNamingTheKey() {
        assertRefused(() -> Keys.system("a/b/c"), "a/b/c", "3 segments");
        assertRefused(() -> Keys.connection("eshop"), "eshop", "1 segments");
        assertRefused(() -> Keys.connection("a/b/c/d"), "a/b/c/d", "4 segments");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Eshop", "1shop", "-shop", "e_shop", "e shop", "eshop/", "/eshop", "e//rest", "é"})
    void aSegmentThePatternRefusesIsRefused(String key) {
        assertRefused(() -> Keys.system(key), key, "segment");
    }

    @Test
    void aSegmentOfSixtyFourCharactersIsTakenAndOneOfSixtyFiveRefused() {
        String longest = "a".repeat(64);
        assertEquals(longest, Keys.system(longest));
        assertRefused(() -> Keys.system(longest + "a"), "a".repeat(65), "segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rail8", "subscribers", "by-agreement"})
    void theReservedWordsNeverStartAKeyAndAreTakenLater(String word) {
        assertRefused(() -> Keys.system(word), word, "starts with '" + word + "'");
        assertRefused(() -> Keys.system(word + "/erp"), word + "/erp", "starts with '" + word + "'");
        assertRefused(() -> Keys.connection(word + "/rest"), word + "/rest", "starts with '" + word + "'");
        assertRefused(() -> Keys.connection(word + "/erp/rest"), word + "/erp/rest", "starts with '" + word + "'");
        assertEquals("acme/" + word, Keys.system("acme/" + word));
        assertEquals("acme/" + word + "/" + word, Keys.connection("acme/" + word + "/" + word));
        assertEquals("rail8x/rest", Keys.connection("rail8x/rest"), "only the whole segment is reserved");
    }

    @Test
    void aVeryLongKeyIsRefusedWithoutQuotingAllOfIt() {
        String key = "a/".repeat(1000) + "a";
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Keys.system(key));
        assertTrue(refused.getMessage().length() < 400, refused.getMessage());
    }

    private static void assertRefused(Runnable parse, String key, String rule) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, parse::run);
        assertTrue(refused.getMessage().contains("'" + key + "'"), refused.getMessage());
        assertTrue(refused.getMessage().contains(rule), refused.getMessage());
    }
}
