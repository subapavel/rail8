package dev.rail8.core.key;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one parser of system, connection and credential keys. A key is segments joined by {@code /}, each matching
 * {@code ^[a-z][a-z0-9-]{0,63}$}: a system key has one or two ({@code eshop}, {@code acme/erp}), a connection key two or three
 * ({@code eshop/rest}, {@code acme/erp/rest}), its system's key followed by the connection's name, and a credential key the
 * same, its system's key followed by the credential's name ({@code eshop/main}, {@code acme/erp/edi-2026}). {@code rail8},
 * {@code subscribers} and {@code by-agreement} are never the first segment; as a later segment they are taken.
 *
 * <p>Pure: no state, no I/O. A refusal is an {@link IllegalArgumentException} that names the key and the rule it breaks.
 *
 * @since 0.4
 */
public final class Keys {

    /** What a segment of a key matches. */
    public static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    /** The words that never start a key: they belong to the platform and to the design of who may send. */
    public static final Set<String> RESERVED_FIRST = Set.of("rail8", "subscribers", "by-agreement");

    private static final int MAX_LENGTH = 3 * 64 + 2;

    private Keys() {}

    /**
     * {@code key} when it is a system key: one or two segments.
     *
     * @throws IllegalArgumentException naming the key and the rule it breaks
     */
    public static String system(String key) {
        return parse("system", key, 1, 2);
    }

    /**
     * {@code key} when it is a connection key: two or three segments.
     *
     * @throws IllegalArgumentException naming the key and the rule it breaks
     */
    public static String connection(String key) {
        return parse("connection", key, 2, 3);
    }

    /**
     * {@code key} when it is a credential key: two or three segments.
     *
     * @throws IllegalArgumentException naming the key and the rule it breaks
     */
    public static String credential(String key) {
        return parse("credential", key, 2, 3);
    }

    /** The key of the system a connection or credential key belongs to: every segment but the last. */
    public static String systemOf(String connectionKey) {
        String key = connection(connectionKey);
        return key.substring(0, key.lastIndexOf('/'));
    }

    private static String parse(String kind, String key, int fewest, int most) {
        Objects.requireNonNull(key, kind + " key");
        String shown = key.length() > MAX_LENGTH ? key.substring(0, MAX_LENGTH) + "…" : key;
        String at = "the " + kind + " key '" + shown + "' ";
        if (key.length() > MAX_LENGTH) throw new IllegalArgumentException(at + "is longer than " + MAX_LENGTH + " characters");
        String[] segments = key.split("/", -1);
        if (segments.length < fewest || segments.length > most) {
            throw new IllegalArgumentException(at + "has " + segments.length + " segments; a " + kind + " key has "
                    + fewest + " or " + most + ", joined by '/'");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(at + "has the segment '" + segment + "': a segment is a lower-case letter, then up"
                        + " to 63 lower-case letters, digits or '-'");
            }
        }
        if (RESERVED_FIRST.contains(segments[0])) {
            throw new IllegalArgumentException(at + "starts with '" + segments[0] + "', which belongs to the platform: no key starts with "
                    + "rail8, subscribers or by-agreement");
        }
        return key;
    }
}
