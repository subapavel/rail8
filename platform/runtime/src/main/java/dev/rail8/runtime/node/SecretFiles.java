package dev.rail8.runtime.node;

import dev.rail8.runtime.secret.SecretFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The file form of the platform's own secret variables: for each variable {@code X} of {@link #NAMED}, {@code X_FILE} may give
 * the absolute path of a file that holds the value instead, read by {@link SecretFile#read}. The journal's
 * {@code RAIL8_JOURNAL_KEY_FILE} is not handled here: its key source reads that file itself. Nor are the file forms of the
 * {@code RAIL8_SECRET_<NAME>} variables, of {@code RAIL8_GIT_USER} and of {@code RAIL8_GIT_TOKEN}: the import of a store of
 * generation 0 alone reads those.
 *
 * <p>A variable set to the empty string counts as not set, either form. The file is read once, when the node's configuration
 * is read: replacing it takes a restart. No refusal shows a value or a file's content; each names the variable, and the path
 * once it is an absolute one.
 *
 * @since 0.1
 */
final class SecretFiles {

    /** The secret variables that have a file form read here. */
    static final Set<String> NAMED = Set.of("RAIL8_PG_PASSWORD", "RAIL8_ADMIN_TOKEN", "RAIL8_VALKEY_URI",
            "RAIL8_SECRETS_KEY", "RAIL8_SECRETS_KEY_PREVIOUS");
    /** The most bytes a secret file may hold. */
    static final long MAX_BYTES = SecretFile.MAX_BYTES;

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
            resolved.put(key, SecretFile.read(fileKey, entry.getValue()));
        }
        return Collections.unmodifiableMap(resolved);
    }

    /** The variable whose file form {@code fileKey} is, or null when it is none. */
    private static String covered(String fileKey) {
        if (!fileKey.endsWith(SUFFIX)) return null;
        String key = fileKey.substring(0, fileKey.length() - SUFFIX.length());
        return NAMED.contains(key) ? key : null;
    }
}
