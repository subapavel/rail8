package dev.rail8.runtime.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The RFC 8785 canonical JSON of the values a body, a binding and a token hold: strings, whole numbers within ±2^53, booleans,
 * null, lists and maps, a map's keys sorted by their UTF-16 code units. A whole number is written in decimal, as RFC 8785 writes
 * a double that holds it exactly; a number with a fraction or beyond ±2^53 is refused, so the canonical form is exact.
 */
final class Jcs {

    /** The largest whole number a double holds exactly: 2^53. */
    static final long MAX_EXACT = 1L << 53;

    private Jcs() {}

    /** The canonical JSON of {@code value}. @throws IllegalArgumentException for a value of another type or number */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    /** The UTF-8 bytes of the canonical JSON of {@code value}. */
    static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /** The SHA-256 of the canonical JSON of {@code value}. */
    static byte[] sha256(Object value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes(value));
        } catch (NoSuchAlgorithmException every) {
            throw new IllegalStateException("every Java platform has SHA-256", every);
        }
    }

    /** Whether {@code value} is a number this form refuses: a fraction, or beyond ±2^53. */
    static boolean inexact(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return false;
        if (value instanceof Long l) return l > MAX_EXACT || l < -MAX_EXACT;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != Math.rint(d) || Math.abs(d) > MAX_EXACT;
        }
        return false;
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b);
            case String s -> string(out, s);
            case Number n -> {
                if (inexact(n)) throw new IllegalArgumentException("a number is a whole number within ±2^53");
                out.append(n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal ? Long.toString((long) n.doubleValue())
                        : n.toString());
            }
            case JsonObject o -> map(out, o.getMap());
            case Map<?, ?> m -> map(out, m);
            case JsonArray a -> list(out, a.getList());
            case Collection<?> c -> list(out, c);
            default -> throw new IllegalArgumentException("a value of type " + value.getClass().getSimpleName() + " has no canonical JSON");
        }
    }

    private static void map(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) keys.add((String) k);
        keys.sort(String::compareTo);
        out.append('{');
        boolean first = true;
        for (String k : keys) {
            if (!first) out.append(',');
            first = false;
            string(out, k);
            out.append(':');
            write(out, map.get(k));
        }
        out.append('}');
    }

    private static void list(StringBuilder out, Collection<?> list) {
        out.append('[');
        boolean first = true;
        for (Object v : list) {
            if (!first) out.append(',');
            first = false;
            write(out, v);
        }
        out.append(']');
    }

    /** {@code s} as an RFC 8785 string: quoted, {@code "} and {@code \} escaped, a control character as its short or \\u form. */
    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
