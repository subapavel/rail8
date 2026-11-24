package dev.rail8.runtime.access;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Argon2id (RFC 9106): 19,456 KiB of memory, 2 passes, parallelism 1, a 16-byte salt and a 32-byte hash, stored as the PHC
 * string {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>} (base64 without padding). A stored hash with other parameters
 * still checks, within finite bounds: at most 262,144 KiB, 10 passes, parallelism 4 and 64 bytes.
 */
public final class Argon2id implements PasswordHash {

    static final int MEMORY_KIB = 19_456;
    static final int PASSES = 2;
    static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;

    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,6}),t=(\\d{1,2}),p=(\\d)\\$([A-Za-z0-9+/]{11,88})\\$([A-Za-z0-9+/]{22,86})");
    private static final Base64.Encoder ENCODE = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getDecoder();
    private static final String DUMMY = "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + PASSES + ",p=" + PARALLELISM + "$"
            + ENCODE.encodeToString(new byte[SALT_BYTES]) + "$" + ENCODE.encodeToString(new byte[HASH_BYTES]);

    private final RandomGenerator random;

    /** A hash whose salts come from {@code random}: a {@code SecureRandom} in a node. */
    public Argon2id(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, PASSES, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + PASSES + ",p=" + PARALLELISM + "$" + ENCODE.encodeToString(salt) + "$"
                + ENCODE.encodeToString(hash);
    }

    @Override
    public boolean matches(String password, String stored) {
        Objects.requireNonNull(password, "password");
        Matcher m = PHC.matcher(stored);
        if (!m.matches()) return false;
        int memory = Integer.parseInt(m.group(1));
        int passes = Integer.parseInt(m.group(2));
        int parallelism = Integer.parseInt(m.group(3));
        byte[] salt = DECODE.decode(m.group(4));
        byte[] expected = DECODE.decode(m.group(5));
        if (memory > 262_144 || passes < 1 || passes > 10 || parallelism < 1 || parallelism > 4 || memory < 8 * parallelism
                || salt.length < 8 || expected.length < 16 || expected.length > 64) {
            return false;
        }
        return MessageDigest.isEqual(expected, derive(password, salt, memory, passes, parallelism, expected.length));
    }

    @Override
    public boolean current(String stored) {
        Matcher m = PHC.matcher(stored);
        return m.matches() && Integer.parseInt(m.group(1)) == MEMORY_KIB && Integer.parseInt(m.group(2)) == PASSES
                && Integer.parseInt(m.group(3)) == PARALLELISM && DECODE.decode(m.group(4)).length == SALT_BYTES
                && DECODE.decode(m.group(5)).length == HASH_BYTES;
    }

    @Override
    public String dummy() {
        return DUMMY;
    }

    private static byte[] derive(String password, byte[] salt, int memory, int passes, int parallelism, int length) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(memory).withIterations(passes).withParallelism(parallelism).build());
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
