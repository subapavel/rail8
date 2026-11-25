package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Format 1 of a sealed secret: AES-256-GCM with a 128-bit tag and a fresh 96-bit nonce from {@link SecureRandom} for every
 * seal, under HKDF-SHA256 of the store's key with the info {@code rail8 secrets v1 seal}. The associated data is the format, the
 * scope, the name, the kind and the key's id, each length-prefixed, so a sealed value moved to another row, another kind or
 * another key does not open. The plain text is the value's JSON object ({@link SecretValue#plain()}) padded with spaces to the
 * next multiple of {@value #PAD} bytes, so a row tells no value's length within that.
 *
 * <p>A key's id is the first 8 bytes of HKDF-SHA256 of the key with the info {@code rail8 secrets v1 id}: it tells keys apart
 * and tells nothing of the key.
 */
final class Seal {

    /** The format this class seals and opens. */
    static final int FORMAT = 1;
    /** What the plain text is padded to a multiple of. */
    static final int PAD = 256;
    /** The bytes of a nonce. */
    static final int NONCE = 12;
    /** The bytes of a key's id. */
    static final int KEY_ID = 8;

    private static final int TAG_BITS = 128;
    private static final byte[] SEAL_INFO = "rail8 secrets v1 seal".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_INFO = "rail8 secrets v1 id".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Seal() {}

    /** A sealed value: the id of the key it is sealed under, its nonce, and its ciphertext with the tag. */
    record Sealed(byte[] keyId, byte[] nonce, byte[] sealed) { }

    /** The id of {@code key}. */
    static byte[] keyId(byte[] key) {
        return Arrays.copyOf(hkdf(key, ID_INFO, 32), KEY_ID);
    }

    /** {@code plain} padded and sealed under {@code key} for the row {@code (scope, name)} of {@code kind}. */
    static Sealed seal(byte[] key, String scope, String name, Secret.Kind kind, byte[] plain) {
        byte[] keyId = keyId(key);
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad(FORMAT, scope, name, kind, keyId));
            return new Sealed(keyId, nonce, c.doFinal(padded(plain)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("AES-256-GCM is not available", failure);
        }
    }

    /**
     * The padded plain text of {@code sealed}, opened under {@code key}.
     *
     * @throws GeneralSecurityException it does not open: another key, another row or kind, or a changed byte
     */
    static byte[] open(byte[] key, int format, String scope, String name, Secret.Kind kind, Sealed sealed) throws GeneralSecurityException {
        if (format != FORMAT) throw new GeneralSecurityException("format " + format + " is not known to this node");
        Cipher c = cipher(Cipher.DECRYPT_MODE, key, sealed.nonce(), aad(format, scope, name, kind, sealed.keyId()));
        return c.doFinal(sealed.sealed());
    }

    /** {@code plain} with spaces up to the next multiple of {@value #PAD} bytes. */
    static byte[] padded(byte[] plain) {
        int length = Math.max(PAD, (plain.length + PAD - 1) / PAD * PAD);
        byte[] out = Arrays.copyOf(plain, length);
        Arrays.fill(out, plain.length, length, (byte) ' ');
        return out;
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(hkdf(key, SEAL_INFO, 32), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        c.updateAAD(aad);
        return c;
    }

    /** The associated data: the format, then the scope, the name, the kind's id and the key's id, each with its length first. */
    static byte[] aad(int format, String scope, String name, Secret.Kind kind, byte[] keyId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(format).array());
        for (byte[] part : new byte[][]{scope.getBytes(StandardCharsets.UTF_8), name.getBytes(StandardCharsets.UTF_8),
                kind.id().getBytes(StandardCharsets.UTF_8), keyId}) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(part.length).array());
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** HKDF-SHA256 (RFC 5869) of {@code key} with an empty salt and {@code info}: {@code length} bytes, at most 32. */
    static byte[] hkdf(byte[] key, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(key);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), length);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is not available", failure);
        }
    }
}
