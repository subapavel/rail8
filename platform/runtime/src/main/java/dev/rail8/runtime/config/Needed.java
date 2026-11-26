package dev.rail8.runtime.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The pairs of a draft that need the secret administrator's consent, and their digest: the SHA-256 of the RFC 8785 JSON
 * {@code {"sends": [{"secret", "server"}]}} of those pairs, sorted. Immutable; the digest is copied in and out.
 *
 * @param pairs  the pairs that need consent, never empty
 * @param digest the 32 bytes of the digest
 */
record Needed(SortedSet<Send> pairs, byte[] digest) {

    Needed {
        pairs = Collections.unmodifiableSortedSet(new TreeSet<>(pairs));
        digest = digest.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }

    /** The digest in lower-case hex, as the answers show it. */
    String hex() {
        return HexFormat.of().formatHex(digest);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Needed n && pairs.equals(n.pairs) && Arrays.equals(digest, n.digest);
    }

    @Override
    public int hashCode() {
        return 31 * pairs.hashCode() + Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        return "Needed[" + pairs + ", " + hex() + "]";
    }
}
