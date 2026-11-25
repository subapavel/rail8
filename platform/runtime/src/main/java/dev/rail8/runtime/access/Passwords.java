package dev.rail8.runtime.access;

import dev.rail8.runtime.refusal.RefusalCode;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** The rules of a password, and the first password the node generates. */
final class Passwords {

    static final int MIN = 12;
    static final int MAX = 128;

    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

    private Passwords() {}

    /** {@code password} in NFC: the form it is checked, hashed and compared in. */
    static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    /**
     * Why {@code next}, normalized, is refused as {@code name}'s new password: shorter than 12 or longer than 128 characters,
     * containing the name in any letter case, or the same as {@code current}; empty when it is fine.
     */
    static Optional<AccessRefused> refusal(String next, String name, String current) {
        int length = next.codePointCount(0, next.length());
        if (length < MIN || length > MAX) {
            return Optional.of(AccessRefused.of(400, RefusalCode.PASSWORD_LENGTH, "a password has " + MIN + " to " + MAX + " characters",
                    Map.of("min", MIN, "max", MAX)));
        }
        if (next.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
            return Optional.of(AccessRefused.of(400, RefusalCode.PASSWORD_CONTAINS_NAME, "a password does not contain the name", Map.of()));
        }
        if (next.equals(current)) return Optional.of(AccessRefused.of(400, RefusalCode.PASSWORD_UNCHANGED, "the new password is the current one", Map.of()));
        return Optional.empty();
    }

    /** A first password: 20 characters of lowercase base32, 100 random bits. */
    static String first(RandomGenerator random) {
        StringBuilder out = new StringBuilder(20);
        for (int i = 0; i < 20; i++) out.append(BASE32.charAt(random.nextInt(32)));
        return out.toString();
    }
}
