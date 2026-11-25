package dev.rail8.testkit;

import dev.rail8.api.Cache;
import dev.rail8.api.Secret;
import dev.rail8.api.StepConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakeServices}: its cache passes {@link CacheContract} on the fake clock, and its {@code secret} hands out the secrets the
 * test set and refuses what a node refuses, naming the step and the key and never showing an option that is not a reference.
 */
class FakeServicesTest {

    static StepConfig step(Map<String, Object> config) {
        return new StepConfig(1, "hmac-sign", "sign", false, config);
    }

    @Nested
    class TheCache extends CacheContract {
        final FakeClock clock = FakeClock.at(FakeRuntime.T0);

        @Override protected Cache cache() { return FakeServices.create(clock).cache(); }

        @Override protected void advance(Duration by) { clock.advance(by); }
    }

    @Test
    void aSecretTheTestSetIsHandedOutAndARotatedValueIsSeen() {
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("secret://partner-hmac", "first");
        Secret s = services.secret(step(Map.of("key", "secret://partner-hmac")), "key");
        assertEquals("secret://partner-hmac", s.toString());
        assertEquals("first", s.value());
        services.secret("secret://partner-hmac", "second");
        assertEquals("second", s.value());
        assertFalse(services.toString().contains("second"), services.toString());
    }

    @Test
    void whatANodeRefusesIsRefusedNamingTheStepAndTheKey() {
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("secret://set", "v");
        for (Map<String, Object> config : List.<Map<String, Object>>of(Map.of(), Map.of("key", 42), Map.of("key", "a-literal-password"),
                Map.of("key", "secret://Not-Well-Formed"), Map.of("key", "secret://not-set"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> services.secret(step(config), "key"), config.toString());
            assertTrue(e.getMessage().contains("step 'sign'") && e.getMessage().contains("'key'"), e.getMessage());
            assertFalse(e.getMessage().contains("a-literal-password"), "a literal is never shown: " + e.getMessage());
        }
    }

    @Test
    void aMalformedReferenceCannotBeSet() {
        assertThrows(IllegalArgumentException.class, () -> FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("partner", "v"));
    }

    @Test
    void stepResourcesCarryTheServices() {
        FakeRuntime rt = FakeRuntime.create();
        FakeServices services = FakeServices.create(rt.clock());
        assertSame(services, rt.stepResources(Path.of("."), services).services());
        assertSame(rt.clock(), rt.stepResources(Path.of("."), services).clock());
    }

    @Test
    void everyKindIsHandedOutAndAChangeBetweenTwoReadsIsSeen() {
        Secret.KeyPair pair = FakeServices.placeholderKeyPair();
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0))
                .secret("secret://login", new Secret.Credentials("acme", "first"))
                .secret("secret://tls", pair);
        Secret login = services.secret(step(Map.of("key", "secret://login")), "key", Secret.Kind.CREDENTIALS);
        Secret tls = services.secret(step(Map.of("key", "secret://tls")), "key", Secret.Kind.KEY_PAIR, Secret.Kind.VALUE);
        assertEquals(Secret.Kind.CREDENTIALS, login.kind());
        assertEquals("first", login.credentials().password());
        services.secret("secret://login", new Secret.Credentials("acme", "second"));
        assertEquals("second", login.credentials().password(), "a value set again is read at the next read");
        assertSame(pair, tls.keyPair());
        assertThrows(IllegalStateException.class, login::value, "a read of another kind fails");
    }

    @Test
    void aSecretOfAKindTheStepDoesNotTakeIsRefused() {
        FakeServices services = FakeServices.create(FakeClock.at(FakeRuntime.T0)).secret("secret://login", new Secret.Credentials("acme", "pa55"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> services.secret(step(Map.of("key", "secret://login")), "key"));
        assertTrue(e.getMessage().contains("credentials") && e.getMessage().contains("value"), e.getMessage());
        assertFalse(e.getMessage().contains("pa55"), e.getMessage());
    }

    @Test
    void thePlaceholderKeyPairIsAnEcP256PairWithItsSelfSignedCertificate() throws Exception {
        Secret.KeyPair pair = FakeServices.placeholderKeyPair();
        assertEquals("EC", pair.privateKey().getAlgorithm());
        assertEquals("CN=invalid", pair.certificates().getFirst().getSubjectX500Principal().getName());
        pair.certificates().getFirst().verify(pair.publicKey());
        java.security.Signature sign = java.security.Signature.getInstance("SHA256withECDSA");
        sign.initSign(pair.privateKey());
        sign.update(new byte[]{1});
        byte[] signature = sign.sign();
        sign.initVerify(pair.publicKey());
        sign.update(new byte[]{1});
        assertTrue(sign.verify(signature), "the key and its certificate's key are one pair");
    }
}
