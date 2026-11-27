package dev.rail8.api;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A secret a step holds: its reference, {@code secret://<name>}, its kind, and a way to read its value. A step takes one at
 * build time from {@link Services#secret} and reads it when it needs it, on every use: the value is not kept here, so a
 * source whose value changes (a secret overwritten in the store) is seen at the next read, without a redeploy. A step that
 * opens a connection, a login, a session or a handshake reads the handle once for it and uses that one value throughout.
 *
 * <p>{@link #toString()} is the reference, never the value, so a secret logged or put into a message by mistake shows
 * only its name. Two secrets are equal only when they are the same object.
 *
 * @since 0.1
 */
public final class Secret {

    private static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");

    /**
     * The kind of a secret: a plain value, credentials (a user and a password), a key pair with its certificates, or a verifier
     * of a calling system's password or key.
     *
     * @since 0.4
     */
    public enum Kind {
        /** One UTF-8 text. */
        VALUE,
        /** A user and a password. */
        CREDENTIALS,
        /** A private key, its public key and its certificates, the leaf first. */
        KEY_PAIR,
        /**
         * What the node checks a calling system's password or key against, never the value itself. Only a credential of the
         * environment names one: no field of an adapter and no custom code takes it.
         */
        VERIFIER;

        /** The kind's id, as the admin API and the settings name it: {@code value}, {@code credentials}, {@code key-pair} or {@code verifier}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * A user and a password. {@link #toString()} names the user only.
     *
     * @param user     the user, never null
     * @param password the password, never null
     * @since 0.4
     */
    public record Credentials(String user, String password) {

        public Credentials {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
        }

        /** The user, never the password. */
        @Override
        public String toString() {
            return "Credentials[user=" + user + "]";
        }
    }

    /**
     * A private key, the public key derived from it, and its certificates, the leaf first (none, or up to ten).
     * {@link #toString()} names the algorithm and the leaf's subject only.
     *
     * @param privateKey   the private key, never null
     * @param publicKey    its public key, never null
     * @param certificates the chain, the leaf first, whose leaf holds {@code publicKey}; may be empty, never null
     * @since 0.4
     */
    public record KeyPair(PrivateKey privateKey, PublicKey publicKey, List<X509Certificate> certificates) {

        public KeyPair {
            Objects.requireNonNull(privateKey, "privateKey");
            Objects.requireNonNull(publicKey, "publicKey");
            certificates = List.copyOf(Objects.requireNonNull(certificates, "certificates"));
        }

        /** The two keys as the JDK's pair, for a {@code KeyManager} or a key identity. */
        public java.security.KeyPair pair() {
            return new java.security.KeyPair(publicKey, privateKey);
        }

        /** The algorithm and the leaf's subject; never a key. */
        @Override
        public String toString() {
            return "KeyPair[" + privateKey.getAlgorithm()
                    + (certificates.isEmpty() ? "" : ", " + certificates.getFirst().getSubjectX500Principal().getName()) + "]";
        }
    }

    private final String reference;
    private final Kind kind;
    private final Supplier<?> value;

    private Secret(String reference, Kind kind, Supplier<?> value) {
        this.reference = reference;
        this.kind = kind;
        this.value = value;
    }

    /**
     * The plain value {@code reference} names, which {@code value} reads on every call of {@link #value()}.
     *
     * @throws IllegalArgumentException naming {@code reference} when it is not {@code secret://} and a name of
     *                                  {@code [a-z0-9][a-z0-9._-]*}
     */
    public static Secret of(String reference, Supplier<String> value) {
        return checked(reference, Kind.VALUE, value);
    }

    /**
     * The credentials {@code reference} names, which {@code value} reads on every call of {@link #credentials()}.
     *
     * @throws IllegalArgumentException naming {@code reference} when it is not {@code secret://} and a name
     * @since 0.4
     */
    public static Secret credentials(String reference, Supplier<Credentials> value) {
        return checked(reference, Kind.CREDENTIALS, value);
    }

    /**
     * The key pair {@code reference} names, which {@code value} reads on every call of {@link #keyPair()}.
     *
     * @throws IllegalArgumentException naming {@code reference} when it is not {@code secret://} and a name
     * @since 0.4
     */
    public static Secret keyPair(String reference, Supplier<KeyPair> value) {
        return checked(reference, Kind.KEY_PAIR, value);
    }

    private static Secret checked(String reference, Kind kind, Supplier<?> value) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(value, "value");
        if (!REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("'" + reference + "' is not a secret reference: secret://<name>, the name [a-z0-9][a-z0-9._-]*");
        }
        return new Secret(reference, kind, value);
    }

    /** {@code secret://<name>}: what the flow file names, safe to show anywhere. */
    public String reference() {
        return reference;
    }

    /**
     * The kind of this secret.
     *
     * @since 0.4
     */
    public Kind kind() {
        return kind;
    }

    /**
     * The plain value now, read from the source on every call. A source that is asked on every call may throw when it cannot
     * answer. The step then fails as it would on any other failure.
     *
     * @throws IllegalStateException naming the reference when the source has no value for it, or naming the reference and both
     *                               kinds when this secret is not a plain value
     */
    public String value() {
        return read(Kind.VALUE, String.class);
    }

    /**
     * The credentials now, read from the source on every call.
     *
     * @throws IllegalStateException naming the reference when the source has no value for it, or naming the reference and both
     *                               kinds when this secret is not credentials
     * @since 0.4
     */
    public Credentials credentials() {
        return read(Kind.CREDENTIALS, Credentials.class);
    }

    /**
     * The key pair now, read from the source on every call.
     *
     * @throws IllegalStateException naming the reference when the source has no value for it, or naming the reference and both
     *                               kinds when this secret is not a key pair
     * @since 0.4
     */
    public KeyPair keyPair() {
        return read(Kind.KEY_PAIR, KeyPair.class);
    }

    private <T> T read(Kind asked, Class<T> type) {
        if (asked != kind) throw new IllegalStateException(reference + " is a secret of kind " + kind.id() + ", read as " + asked.id());
        Object v = value.get();
        if (v == null) throw new IllegalStateException(reference + " has no value");
        return type.cast(v);
    }

    /** The reference, never the value. */
    @Override
    public String toString() {
        return reference;
    }
}
