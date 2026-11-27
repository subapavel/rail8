package dev.rail8.testkit;

import dev.rail8.api.Cache;
import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.StepConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Services} for a step's unit test: the secrets the test sets, of every kind, and one in-memory cache on the test's clock
 * that passes {@link CacheContract}. {@link #secret(StepConfig, String, Secret.Kind...)} refuses what a node refuses: an option
 * that is absent, not a string, not a reference, not a well-formed reference, names a secret the test did not set, or names one
 * of a kind the step did not ask for. A value set again is what a {@link Secret} taken earlier reads from then on, as a secret
 * overwritten in the store is, so a test can change one between two messages.
 *
 * <pre>{@code
 * FakeRuntime rt = FakeRuntime.create();
 * FakeServices services = FakeServices.create(rt.clock()).secret("secret://partner-hmac", "k3y")
 *         .secret("secret://partner-login", new Secret.Credentials("acme", "pa55"))
 *         .secret("secret://partner-tls", FakeServices.placeholderKeyPair());
 * Stage stage = factory.create(step, rt.stepResources(dir, services));
 * }</pre>
 *
 * @since 0.1
 */
public final class FakeServices implements Services {

    /** Where the placeholder key pair lies on the class path: a PKCS#8 EC secp256r1 key, then its self-signed certificate. */
    private static final String PLACEHOLDER = "/dev/rail8/testkit/invalid.pem";

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Cache cache;

    private FakeServices(Clock clock) {
        this.cache = new FakeCache(clock);
    }

    /** No secrets yet, and an empty cache on {@code clock}. */
    public static FakeServices create(Clock clock) {
        return new FakeServices(clock);
    }

    /** Sets the plain value of the secret {@code reference}, {@code secret://<name>}; returns these services. */
    public FakeServices secret(String reference, String value) {
        return put(reference, Objects.requireNonNull(value, "value"));
    }

    /**
     * Sets the credentials of the secret {@code reference}; returns these services.
     *
     * @since 0.4
     */
    public FakeServices secret(String reference, Secret.Credentials value) {
        return put(reference, Objects.requireNonNull(value, "value"));
    }

    /**
     * Sets the key pair of the secret {@code reference}; returns these services.
     *
     * @since 0.4
     */
    public FakeServices secret(String reference, Secret.KeyPair value) {
        return put(reference, Objects.requireNonNull(value, "value"));
    }

    private FakeServices put(String reference, Object value) {
        Secret.of(reference, () -> null);   // refuses a malformed reference
        values.put(reference, value);
        return this;
    }

    /**
     * The placeholder key pair, named invalid: a fixed EC secp256r1 key and its self-signed certificate, {@code CN=invalid}. It is
     * what {@code rail8 validate} and {@code rail8 test} read a key pair as. It is no secret: anyone may read it here.
     *
     * @since 0.4
     */
    public static Secret.KeyPair placeholderKeyPair() {
        try (InputStream in = FakeServices.class.getResourceAsStream(PLACEHOLDER)) {
            if (in == null) throw new IllegalStateException(PLACEHOLDER + " is not on the class path");
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            byte[] key = Base64.getMimeDecoder().decode(between(pem, "PRIVATE KEY"));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getMimeDecoder().decode(between(pem, "CERTIFICATE"))));
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(key));
            return new Secret.KeyPair(privateKey, certificate.getPublicKey(), List.of(certificate));
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException(PLACEHOLDER + " cannot be read", failure);
        }
    }

    private static String between(String pem, String type) {
        int from = pem.indexOf("-----BEGIN " + type + "-----");
        int to = pem.indexOf("-----END " + type + "-----");
        if (from < 0 || to < 0) throw new IllegalStateException(PLACEHOLDER + " holds no " + type);
        return pem.substring(from + type.length() + 16, to);
    }

    @Override
    public Secret secret(StepConfig step, String key, Secret.Kind... kinds) {
        Objects.requireNonNull(step, "step");
        Set<Secret.Kind> asked = kinds.length == 0 ? Set.of() : EnumSet.copyOf(Arrays.asList(kinds));
        if (asked.isEmpty()) throw new IllegalArgumentException(step + ": '" + key + "': no kind of secret is asked for");
        if (asked.contains(Secret.Kind.VERIFIER)) {
            throw new IllegalArgumentException(step + ": '" + key + "': no step takes a verifier: only a credential of the environment names one");
        }
        String reference = step.requireString(key);
        if (!reference.startsWith("secret://")) {
            throw new IllegalArgumentException(step + ": '" + key + "' must name a secret, secret://<name>; its value is not shown");
        }
        try {
            Secret.of(reference, () -> null);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(step + ": '" + key + "': " + malformed.getMessage(), malformed);
        }
        Object value = values.get(reference);
        if (value == null) {
            throw new IllegalArgumentException(step + ": '" + key + "': secret '" + reference + "' is not set in these FakeServices");
        }
        Secret.Kind kind = kindOf(value);
        if (!asked.contains(kind)) {
            throw new IllegalArgumentException(step + ": '" + key + "': secret '" + reference + "' is of kind " + kind.id() + ", and the step takes "
                    + asked.stream().map(Secret.Kind::id).toList());
        }
        return switch (kind) {
            case VALUE -> Secret.of(reference, () -> values.get(reference) instanceof String v ? v : null);
            case CREDENTIALS -> Secret.credentials(reference, () -> values.get(reference) instanceof Secret.Credentials v ? v : null);
            case KEY_PAIR -> Secret.keyPair(reference, () -> values.get(reference) instanceof Secret.KeyPair v ? v : null);
            case VERIFIER -> throw new IllegalStateException("a fake value is never a verifier");
        };
    }

    private static Secret.Kind kindOf(Object value) {
        if (value instanceof Secret.Credentials) return Secret.Kind.CREDENTIALS;
        if (value instanceof Secret.KeyPair) return Secret.Kind.KEY_PAIR;
        return Secret.Kind.VALUE;
    }

    @Override
    public Cache cache() {
        return cache;
    }

    /** The references set, never a value. */
    @Override
    public String toString() {
        return "FakeServices[" + new java.util.TreeSet<>(values.keySet()) + "]";
    }
}
