package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Secret} shows its reference and never its value, reads its value from the source on every call (a rotated value is
 * seen at once), refuses a reference that is not {@code secret://<name>}, and fails with the reference when the source has no
 * value.
 */
class SecretTest {

    @Test
    void itShowsTheReferenceAndNeverTheValue() {
        Secret s = Secret.of("secret://partner-hmac", () -> "s3cr3t-value");
        assertEquals("secret://partner-hmac", s.reference());
        assertEquals("secret://partner-hmac", s.toString());
        assertEquals("[secret://partner-hmac]", java.util.List.of(s).toString(), "a collection of secrets shows references too");
    }

    @Test
    void theValueIsReadOnEveryCall() {
        AtomicReference<String> source = new AtomicReference<>("first");
        Secret s = Secret.of("secret://rotating", source::get);
        assertEquals("first", s.value());
        source.set("second");
        assertEquals("second", s.value(), "a rotated value is seen without building the secret again");
    }

    @Test
    void aSourceWithoutAValueFailsNamingTheReference() {
        Secret s = Secret.of("secret://gone", () -> null);
        IllegalStateException e = assertThrows(IllegalStateException.class, s::value);
        assertTrue(e.getMessage().contains("secret://gone"), e.getMessage());
    }

    @Test
    void aReferenceThatIsNotSecretAndANameIsRefused() {
        for (String bad : new String[]{"partner-hmac", "secret://", "secret://Upper", "secret://-dash", "secret://a b", "https://x"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Secret.of(bad, () -> "v"), bad);
            assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
        }
        assertEquals("secret://a.b_c-9", Secret.of("secret://a.b_c-9", () -> "v").reference());
    }

    @Test
    void twoSecretsAreEqualOnlyWhenTheyAreTheSameObject() {
        Secret a = Secret.of("secret://same", () -> "v");
        Secret b = Secret.of("secret://same", () -> "v");
        assertEquals(a, a);
        assertNotEquals(a, b);
    }

    @Test
    void eachKindHasItsIdAndAReadOfAnotherKindFailsNamingTheReferenceAndBothKinds() {
        assertEquals("value", Secret.Kind.VALUE.id());
        assertEquals("credentials", Secret.Kind.CREDENTIALS.id());
        assertEquals("key-pair", Secret.Kind.KEY_PAIR.id());
        Secret login = Secret.credentials("secret://login", () -> new Secret.Credentials("acme", "pa55"));
        assertEquals(Secret.Kind.CREDENTIALS, login.kind());
        assertEquals("pa55", login.credentials().password());
        IllegalStateException e = assertThrows(IllegalStateException.class, login::value);
        assertTrue(e.getMessage().contains("secret://login") && e.getMessage().contains("credentials") && e.getMessage().contains("value"), e.getMessage());
        assertThrows(IllegalStateException.class, login::keyPair);
        assertEquals(Secret.Kind.VALUE, Secret.of("secret://v", () -> "x").kind());
    }

    @Test
    void credentialsNameTheUserOnly() {
        Secret.Credentials c = new Secret.Credentials("acme", "pa55-w0rd");
        assertTrue(c.toString().contains("acme"), c.toString());
        assertTrue(!c.toString().contains("pa55-w0rd"), c.toString());
        assertEquals("secret://login", Secret.credentials("secret://login", () -> c).toString());
    }

    @Test
    void aKeyPairNamesItsAlgorithmOnlyAndGivesTheJdkPair() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        java.security.KeyPair jdk = g.generateKeyPair();
        Secret.KeyPair pair = new Secret.KeyPair(jdk.getPrivate(), jdk.getPublic(), java.util.List.of());
        assertEquals("KeyPair[EC]", pair.toString());
        assertEquals(jdk.getPrivate(), pair.pair().getPrivate());
        assertEquals(jdk.getPublic(), pair.pair().getPublic());
        Secret s = Secret.keyPair("secret://tls", () -> pair);
        assertEquals(Secret.Kind.KEY_PAIR, s.kind());
        assertEquals(pair, s.keyPair());
    }
}
