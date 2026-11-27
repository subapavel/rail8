package dev.rail8.runtime.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a node holds of a secret of kind {@code verifier}: its form, the id inside the value it verifies, and the SHA-256 of
 * that whole value in lower-case hex. It holds nothing a caller could send. Its {@code toString} names the form and the id.
 *
 * @param form        {@value #GENERATED}, the one form the store makes
 * @param id          the 16 lower-case hex digits after {@code rail8c_}
 * @param fingerprint the SHA-256 of the whole value, 64 lower-case hex digits
 * @since 0.4
 */
public record Verifier(String form, String id, String fingerprint) {

    /** The form of a value the store generated: {@code rail8c_<id>_<secret>}. */
    public static final String GENERATED = "generated";
    /** What every generated value starts with. */
    public static final String PREFIX = "rail8c_";

    private static final Pattern VALUE = Pattern.compile("rail8c_([0-9a-f]{16})_[A-Za-z0-9_-]{43}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    public Verifier {
        if (!GENERATED.equals(form)) throw new IllegalArgumentException("a verifier's form is " + GENERATED + ", not " + form);
        if (!ID.matcher(Objects.requireNonNull(id, "id")).matches()) throw new IllegalArgumentException("a verifier's id is 16 lower-case hex digits");
        if (!FINGERPRINT.matcher(Objects.requireNonNull(fingerprint, "fingerprint")).matches()) {
            throw new IllegalArgumentException("a verifier's fingerprint is 64 lower-case hex digits");
        }
    }

    /** The verifier of {@code value}, a generated value. */
    static Verifier of(String value) {
        return new Verifier(GENERATED, idOf(value).orElseThrow(() -> new IllegalArgumentException("not a generated value")), fingerprintOf(value));
    }

    /** The id inside {@code presented} when it has the generated form; empty for any other text. */
    public static Optional<String> idOf(String presented) {
        Matcher m = VALUE.matcher(presented);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The SHA-256 of {@code value}'s UTF-8 bytes, in lower-case hex. */
    public static String fingerprintOf(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is not available", absent);
        }
    }

    /** Whether {@code presented} is the value this verifies, compared in constant time over the fingerprints. */
    public boolean verifies(String presented) {
        return MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII), fingerprintOf(presented).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return "Verifier[" + form + ", " + id + "]";
    }
}
