package dev.rail8.runtime.secret;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The store's key from the node's environment: each refusal names its variable and holds nothing of what it was given. */
class StoreKeyTest {

    private static String key(int bytes, int fill) {
        byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    private static final String KEY = key(32, 7);
    private static final String OTHER = key(32, 9);

    private static IllegalArgumentException refused(Map<String, String> env) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> StoreKey.fromEnv(env));
        env.values().forEach(value -> assertFalse(refused.getMessage().contains(value), "no value in: " + refused.getMessage()));
        return refused;
    }

    @Test
    void aKeyThatIsNotBase64IsRefusedNamingItsVariable() {
        assertEquals("RAIL8_SECRETS_KEY is not base64: give 256 random bits, as openssl rand -base64 32 writes them",
                refused(Map.of("RAIL8_SECRETS_KEY", "not*base64*at*all")).getMessage());
        assertTrue(refused(Map.of("RAIL8_SECRETS_KEY", KEY, "RAIL8_SECRETS_KEY_PREVIOUS", "%%%%")).getMessage()
                .startsWith("RAIL8_SECRETS_KEY_PREVIOUS is not base64"));
    }

    @Test
    void aKeyOfAnotherLengthThan256BitsIsRefusedNamingItsVariable() {
        assertEquals("RAIL8_SECRETS_KEY holds 128 bits, not 256: give the output of openssl rand -base64 32",
                refused(Map.of("RAIL8_SECRETS_KEY", key(16, 3))).getMessage());
        assertTrue(refused(Map.of("RAIL8_SECRETS_KEY", KEY, "RAIL8_SECRETS_KEY_PREVIOUS", key(33, 3))).getMessage()
                .startsWith("RAIL8_SECRETS_KEY_PREVIOUS holds 264 bits"));
    }

    @Test
    void aPreviousKeyWithoutAKeyIsRefused() {
        assertEquals("RAIL8_SECRETS_KEY_PREVIOUS is set without RAIL8_SECRETS_KEY",
                refused(Map.of("RAIL8_SECRETS_KEY_PREVIOUS", KEY)).getMessage());
    }

    @Test
    void twoEqualKeysAreRefused() {
        assertTrue(refused(Map.of("RAIL8_SECRETS_KEY", KEY, "RAIL8_SECRETS_KEY_PREVIOUS", KEY)).getMessage()
                .startsWith("RAIL8_SECRETS_KEY_PREVIOUS equals RAIL8_SECRETS_KEY"));
    }

    @Test
    void aKeyAndItsPreviousOneAreHeldAndNamedByTheirIdsOnly() {
        StoreKey both = StoreKey.fromEnv(Map.of("RAIL8_SECRETS_KEY", KEY + "\n", "RAIL8_SECRETS_KEY_PREVIOUS", OTHER)).orElseThrow();
        assertTrue(both.equalsKey(Base64.getDecoder().decode(KEY)), "the key, its line end stripped");
        assertFalse(both.equalsKey(Base64.getDecoder().decode(OTHER)), "the previous key is not the key");
        assertFalse(both.toString().contains(KEY) || both.toString().contains(OTHER), both.toString());
        assertEquals(java.util.Optional.empty(), StoreKey.fromEnv(Map.of("RAIL8_SECRETS_KEY", "")), "an empty variable is not set");
        assertEquals(java.util.Optional.empty(), StoreKey.fromEnv(Map.of()));
    }
}
