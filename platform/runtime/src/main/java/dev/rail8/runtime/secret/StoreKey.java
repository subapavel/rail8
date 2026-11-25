package dev.rail8.runtime.secret;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * The secret store's key as the node was started with it: {@code RAIL8_SECRETS_KEY}, and {@code RAIL8_SECRETS_KEY_PREVIOUS}
 * while the key is being changed. Each is the base64 of exactly 256 bits, as {@code openssl rand -base64 32} writes it; the
 * file forms ({@code _FILE}) are read before this, as for the node's other secrets. {@link #toString()} names the keys' ids
 * only.
 */
public final class StoreKey {

    /** The variable of the key. */
    public static final String VARIABLE = "RAIL8_SECRETS_KEY";
    /** The variable of the previous key, set only while the key is being changed. */
    public static final String PREVIOUS = "RAIL8_SECRETS_KEY_PREVIOUS";

    /** No key: the node reads its variables only, and a write is refused. */
    public static final StoreKey NONE = new StoreKey(null, null);

    private static final int BYTES = 32;

    private final byte[] current;
    private final byte[] previous;

    private StoreKey(byte[] current, byte[] previous) {
        this.current = current;
        this.previous = previous;
    }

    /**
     * The key of {@code env}'s two variables; {@link #NONE} when neither is set.
     *
     * @throws IllegalArgumentException a variable that is not the base64 of 256 bits, a previous key without a key, or two
     *                                  equal keys; the message names the variable, never a value
     */
    public static StoreKey fromEnv(Map<String, String> env) {
        byte[] current = read(env, VARIABLE);
        byte[] previous = read(env, PREVIOUS);
        if (current == null && previous != null) throw new IllegalArgumentException(PREVIOUS + " is set without " + VARIABLE);
        if (current != null && previous != null && MessageDigest.isEqual(current, previous)) {
            throw new IllegalArgumentException(PREVIOUS + " equals " + VARIABLE + ": set it only while the key is changed, to the key before");
        }
        return current == null ? NONE : new StoreKey(current, previous);
    }

    /** The key {@code current} and the previous one (null: none): tests. */
    static StoreKey of(byte[] current, byte[] previous) {
        return new StoreKey(current.clone(), previous == null ? null : previous.clone());
    }

    private static byte[] read(Map<String, String> env, String variable) {
        String value = env.get(variable);
        if (value == null || value.isEmpty()) return null;
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(variable + " is not base64: give 256 random bits, as openssl rand -base64 32 writes them");
        }
        if (key.length != BYTES) {
            throw new IllegalArgumentException(variable + " holds " + key.length * 8 + " bits, not 256: give the output of openssl rand -base64 32");
        }
        return key;
    }

    /** Whether the node holds a key. */
    public boolean present() {
        return current != null;
    }

    /** The key, which every seal of this node is under; empty without one. */
    Optional<byte[]> current() {
        return Optional.ofNullable(current).map(byte[]::clone);
    }

    /** The previous key, while the key is being changed. */
    Optional<byte[]> previous() {
        return Optional.ofNullable(previous).map(byte[]::clone);
    }

    /** Whether the key equals {@code other}'s bytes: the node refuses a store key equal to the journal's key. */
    public boolean equalsKey(byte[] other) {
        return current != null && other != null && MessageDigest.isEqual(current, other);
    }

    /** This key without its previous one. */
    StoreKey withoutPrevious() {
        return previous == null ? this : new StoreKey(current, null);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoreKey k && Arrays.equals(current, k.current) && Arrays.equals(previous, k.previous);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(current) * 31 + Arrays.hashCode(previous);
    }

    /** The ids of the keys, never a key. */
    @Override
    public String toString() {
        if (current == null) return "StoreKey[none]";
        HexFormat hex = HexFormat.of();
        return "StoreKey[" + hex.formatHex(Seal.keyId(current)) + (previous == null ? "" : ", previous " + hex.formatHex(Seal.keyId(previous))) + "]";
    }
}
