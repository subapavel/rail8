package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Format 1 of a sealed secret: what it binds, its nonces, its padding, and the key's id. */
class SealTest {

    static final byte[] KEY = key(1);
    static final byte[] OTHER = key(2);
    static final byte[] PLAIN = new SecretValue.Plain("s3cret").plain();

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return k;
    }

    @Test
    void aSealOpensUnderItsKeyRowAndKind() throws GeneralSecurityException {
        Seal.Sealed s = Seal.seal(KEY, "installation", "db", Secret.Kind.VALUE, PLAIN);
        byte[] opened = Seal.open(KEY, 1, "installation", "db", Secret.Kind.VALUE, s);
        assertEquals("{\"value\":\"s3cret\"}", new String(opened, StandardCharsets.UTF_8).strip());
        assertArrayEquals(Seal.keyId(KEY), s.keyId(), "the seal names the key it is under");
    }

    @Test
    void eachBoundFieldChangedFailsToOpen() {
        Seal.Sealed s = Seal.seal(KEY, "installation", "db", Secret.Kind.VALUE, PLAIN);
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 2, "installation", "db", Secret.Kind.VALUE, s), "another format");
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "partner", "db", Secret.Kind.VALUE, s), "another scope");
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "installation", "dc", Secret.Kind.VALUE, s), "another name");
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "installation", "db", Secret.Kind.CREDENTIALS, s), "another kind");
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "installation", "db", Secret.Kind.VALUE,
                new Seal.Sealed(Seal.keyId(OTHER), s.nonce(), s.sealed())), "another key id");
        byte[] nonce = s.nonce().clone();
        nonce[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "installation", "db", Secret.Kind.VALUE,
                new Seal.Sealed(s.keyId(), nonce, s.sealed())), "another nonce");
        byte[] sealed = s.sealed().clone();
        sealed[3] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "installation", "db", Secret.Kind.VALUE,
                new Seal.Sealed(s.keyId(), s.nonce(), sealed)), "a changed byte");
        assertThrows(GeneralSecurityException.class, () -> Seal.open(OTHER, 1, "installation", "db", Secret.Kind.VALUE, s), "another key");
    }

    @Test
    void namesAndScopesDoNotRunTogether() {
        Seal.Sealed s = Seal.seal(KEY, "ab", "c", Secret.Kind.VALUE, PLAIN);
        assertThrows(GeneralSecurityException.class, () -> Seal.open(KEY, 1, "a", "bc", Secret.Kind.VALUE, s),
                "each part is length-prefixed in what the seal binds");
    }

    @Test
    void twoSealsOfOneValueDiffer() {
        Seal.Sealed a = Seal.seal(KEY, "installation", "db", Secret.Kind.VALUE, PLAIN);
        Seal.Sealed b = Seal.seal(KEY, "installation", "db", Secret.Kind.VALUE, PLAIN);
        assertFalse(Arrays.equals(a.nonce(), b.nonce()), "a fresh nonce each time");
        assertFalse(Arrays.equals(a.sealed(), b.sealed()));
    }

    @Test
    void valuesWithinOnePaddingGiveRowsOfOneLength() {
        byte[] one = new SecretValue.Plain("x").plain();
        byte[] most = new SecretValue.Plain("y".repeat(244)).plain();
        assertEquals(13, one.length);
        assertEquals(256, most.length);
        Seal.Sealed a = Seal.seal(KEY, "installation", "a", Secret.Kind.VALUE, one);
        Seal.Sealed b = Seal.seal(KEY, "installation", "b", Secret.Kind.VALUE, most);
        assertEquals(256 + 16, a.sealed().length, "padded to 256 bytes, with the 16-byte tag");
        assertEquals(a.sealed().length, b.sealed().length);
        assertEquals(512 + 16, Seal.seal(KEY, "installation", "c", Secret.Kind.VALUE, new SecretValue.Plain("z".repeat(245)).plain()).sealed().length,
                "one byte more takes the next 256");
    }

    @Test
    void aKeysIdIsEightBytesAndTellsKeysApart() {
        assertEquals(8, Seal.keyId(KEY).length);
        assertArrayEquals(Seal.keyId(KEY), Seal.keyId(KEY.clone()));
        assertFalse(Arrays.equals(Seal.keyId(KEY), Seal.keyId(OTHER)));
    }

    @Test
    void hkdfIsRfc5869() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d",
                HexFormat.of().formatHex(Seal.hkdf(ikm, new byte[0], 32)), "test case 3 of RFC 5869: an empty salt and info");
    }
}
