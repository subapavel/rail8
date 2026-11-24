package dev.rail8.runtime.node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The file form of the node's secret variables: for each variable {@code X} of {@link #NAMED} and for every
 * {@code RAIL8_SECRET_<NAME>} with a non-empty name, {@code X_FILE} may give the absolute path of a file that holds the value
 * instead. {@code RAIL8_SECRET_FILE} alone is the plain variable of {@code secret://file}; a secret whose own variable ends in
 * {@code _FILE} is given as a file only ({@code RAIL8_SECRET_DB_FILE_FILE} for {@code secret://db-file}). The journal's
 * {@code RAIL8_JOURNAL_KEY_FILE} is not handled here: its key source reads that file itself.
 *
 * <p>A variable set to the empty string counts as not set, either form. The file is read once, when the node's configuration
 * is read: replacing it takes a restart. One trailing {@code \n} or {@code \r\n} is dropped and nothing else of the content
 * is changed. No refusal shows a value or a file's content; each names the variable, and the path once it is an absolute one.
 *
 * @since 0.1
 */
final class SecretFiles {

    /** The named secret variables that have a file form, beside every {@code RAIL8_SECRET_<NAME>}. */
    static final Set<String> NAMED = Set.of("RAIL8_PG_PASSWORD", "RAIL8_ADMIN_TOKEN", "RAIL8_GIT_TOKEN", "RAIL8_VALKEY_URI");
    /** The most bytes a secret file may hold: enough for a PEM key or a service account's JSON. */
    static final int MAX_BYTES = 64 * 1024;

    private static final String SECRET = "RAIL8_SECRET_";
    private static final String SUFFIX = "_FILE";

    private SecretFiles() {}

    /**
     * {@code env} with each file form replaced: {@code X} set to the file's content and {@code X_FILE} removed; an empty
     * {@code X_FILE} is removed and ignored. Every other variable is kept as it is.
     *
     * @throws IllegalArgumentException a value is given both as {@code X} and as {@code X_FILE} (the message names the two);
     *                                  the path is not absolute (it names the variable only); the file is missing, cannot be
     *                                  read, is empty once the line end is dropped, holds more than {@value #MAX_BYTES} bytes
     *                                  or is not UTF-8 text (it names the variable and the path)
     */
    static Map<String, String> resolve(Map<String, String> env) {
        Map<String, String> resolved = new HashMap<>(env);
        for (Map.Entry<String, String> entry : new TreeMap<>(env).entrySet()) {   // sorted: the first refusal is always the same
            String fileKey = entry.getKey();
            String key = covered(fileKey);
            if (key == null) continue;
            resolved.remove(fileKey);
            if (entry.getValue().isEmpty()) continue;
            String given = env.get(key);
            if (given != null && !given.isEmpty()) {
                throw new IllegalArgumentException(key + " and " + fileKey + " are both set: the value comes from one of them (the values are not shown)");
            }
            resolved.put(key, read(fileKey, entry.getValue()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    /** The variable whose file form {@code fileKey} is, or null when it is none. */
    private static String covered(String fileKey) {
        if (!fileKey.endsWith(SUFFIX)) return null;
        String key = fileKey.substring(0, fileKey.length() - SUFFIX.length());
        return NAMED.contains(key) || (key.startsWith(SECRET) && key.length() > SECRET.length()) ? key : null;
    }

    /** The content of the file {@code raw} names, its one trailing line end dropped. */
    private static String read(String fileKey, String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException notAPath) {
            path = null;
        }
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException(fileKey + " must be the absolute path of a file (the value is not shown)");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException missing) {
            throw new IllegalArgumentException(fileKey + " names a file that does not exist: " + path, missing);
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException(fileKey + " names a file that cannot be read: " + path, unreadable);
        }
        try {
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException(fileKey + " names a file of more than " + MAX_BYTES + " bytes: " + path);
            }
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\n') {
                length--;
                if (length > 0 && bytes[length - 1] == '\r') length--;
            }
            if (length == 0) throw new IllegalArgumentException(fileKey + " names an empty file: " + path);
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            } catch (CharacterCodingException notText) {
                throw new IllegalArgumentException(fileKey + " names a file that is not UTF-8 text (the content is not shown): " + path);
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
