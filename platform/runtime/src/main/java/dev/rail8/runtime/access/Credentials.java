package dev.rail8.runtime.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forms of the admin API's credentials: {@code rail8s_<id>_<secret>} a session, {@code rail8t_<id>_<secret>} a token,
 * {@code rail8d_<id>_<secret>} a device of the login limit, {@code rail8k_<secret>} the emergency key;
 * {@code <id>} is 16 lowercase hex digits, {@code <secret>} 43 base64url characters (256 random bits). Each is stored only as
 * its fingerprint: SHA-256 of the whole string in UTF-8.
 */
final class Credentials {

    static final String SESSION = "rail8s_";
    static final String TOKEN = "rail8t_";
    static final String DEVICE = "rail8d_";
    static final String KEY = "rail8k_";
    /** The fingerprint an unknown id is compared with, so both paths take the same time. */
    static final String DUMMY_FINGERPRINT = "0".repeat(64);

    private static final Pattern SESSION_FORM = Pattern.compile("rail8s_([0-9a-f]{16})_[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN_FORM = Pattern.compile("rail8t_([0-9a-f]{16})_[A-Za-z0-9_-]{43}");
    private static final Pattern DEVICE_FORM = Pattern.compile("rail8d_([0-9a-f]{16})_[A-Za-z0-9_-]{43}");
    private static final Pattern KEY_FORM = Pattern.compile("rail8k_[A-Za-z0-9_-]{43}");
    /** The secret of a session, a token, a device or the key, wherever it stands: what follows the id, or the key's prefix. */
    private static final Pattern SECRET = Pattern.compile("(rail8[std]_[0-9a-f]{16}_|rail8k_)[A-Za-z0-9_-]+");

    private Credentials() {}

    /** The credential an {@code Authorization} header presents: what follows {@code Bearer } (in any case), stripped; else empty. */
    static Optional<String> presented(String header) {
        return header.regionMatches(true, 0, "Bearer ", 0, 7) ? Optional.of(header.substring(7).strip()) : Optional.empty();
    }

    /** {@code text} with the secret of every session, token, device and key in it replaced by {@code ***}. */
    static String redacted(String text) {
        return SECRET.matcher(text).replaceAll("$1***");
    }

    /** Whether {@code value} has the emergency key's form. */
    static boolean isKey(String value) {
        return KEY_FORM.matcher(value).matches();
    }

    /** The id of a session credential, read by position; empty for any other form. */
    static Optional<String> sessionId(String value) {
        Matcher m = SESSION_FORM.matcher(value);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The id of a token, read by position; empty for any other form. */
    static Optional<String> tokenId(String value) {
        Matcher m = TOKEN_FORM.matcher(value);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The id of a device credential, read by position; empty for any other form. */
    static Optional<String> deviceId(String value) {
        Matcher m = DEVICE_FORM.matcher(value);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** SHA-256 of {@code value} in UTF-8, as 64 lowercase hex digits. */
    static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java runtime has SHA-256", absent);
        }
    }

    /** Whether two fingerprints are equal, in time that does not depend on where they differ. */
    static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    /** A new id: 64 random bits as 16 lowercase hex digits. */
    static String newId(RandomGenerator random) {
        byte[] bits = new byte[8];
        random.nextBytes(bits);
        return HexFormat.of().formatHex(bits);
    }

    /** A new secret: 256 random bits as 43 base64url characters. */
    static String newSecret(RandomGenerator random) {
        byte[] bits = new byte[32];
        random.nextBytes(bits);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bits);
    }
}
