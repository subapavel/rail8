package dev.rail8.runtime.access;

import java.io.Serial;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * A refusal the admin API answers as it is: its HTTP status, its code when the status has codes (401, 403, 429 and the 503
 * of a busy hash), the roles that would grant a refused action (403), and the seconds to wait before another attempt.
 */
public final class AccessRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Optional<String> code;
    private final transient Set<Role> needs;
    private final transient OptionalLong retryAfter;

    private AccessRefused(int status, Optional<String> code, String message, Set<Role> needs, OptionalLong retryAfter) {
        super(Objects.requireNonNull(message, "message"), null, false, false);
        this.status = status;
        this.code = code;
        this.needs = Set.copyOf(needs);
        this.retryAfter = retryAfter;
    }

    /** 401 with {@code code}: no credential, an unknown one, or one whose secret matched but that no longer holds. */
    public static AccessRefused unauthenticated(String code, String message) {
        return new AccessRefused(401, Optional.of(code), message, Set.of(), OptionalLong.empty());
    }

    /** 403 with {@code code}, naming the roles that grant what was refused. */
    public static AccessRefused forbidden(String code, String message, Set<Role> needs) {
        return new AccessRefused(403, Optional.of(code), message, needs, OptionalLong.empty());
    }

    /** 429 {@code too-many-attempts}: the next attempt is counted only after {@code seconds}. */
    public static AccessRefused tooManyAttempts(long seconds) {
        return new AccessRefused(429, Optional.of("too-many-attempts"), "too many attempts: try again in " + seconds + " s", Set.of(),
                OptionalLong.of(seconds));
    }

    /** 503 {@code busy}: no place to hash a password is free on this node; nothing was counted. */
    public static AccessRefused busy() {
        return new AccessRefused(503, Optional.of("busy"), "the node is busy hashing passwords: try again", Set.of(), OptionalLong.of(1));
    }

    /** 503 without a code: a required audit row was not written, so nothing ran. */
    public static AccessRefused unaudited(String message) {
        return new AccessRefused(503, Optional.empty(), message, Set.of(), OptionalLong.empty());
    }

    /** A refusal without a code: 400, 404 or 409 of an act on users. */
    public static AccessRefused of(int status, String message) {
        return new AccessRefused(status, Optional.empty(), message, Set.of(), OptionalLong.empty());
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The refusal's code, for 401, 403, 429 and a busy 503. */
    public Optional<String> code() {
        return code;
    }

    /** The roles that grant the refused action (a 403); empty otherwise. */
    public Set<Role> needs() {
        return needs;
    }

    /** The seconds for {@code Retry-After}, for a 429 and a busy 503. */
    public OptionalLong retryAfter() {
        return retryAfter;
    }
}
