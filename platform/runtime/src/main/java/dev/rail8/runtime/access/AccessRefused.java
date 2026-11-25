package dev.rail8.runtime.access;

import dev.rail8.runtime.refusal.RefusalCode;

import java.io.Serial;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * A refusal the admin API answers as it is: its HTTP status, its code and the values its text names, the roles that would grant
 * a refused action (403), and the seconds to wait before another attempt.
 */
public final class AccessRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final int status;
    private final transient RefusalCode code;
    private final transient Map<String, Object> values;
    private final transient Set<Role> needs;
    private final transient OptionalLong retryAfter;

    private AccessRefused(int status, RefusalCode code, String message, Map<String, Object> values, Set<Role> needs, OptionalLong retryAfter) {
        super(Objects.requireNonNull(message, "message"), null, false, false);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
        this.values = Map.copyOf(values);
        this.needs = Set.copyOf(needs);
        this.retryAfter = retryAfter;
    }

    /** 401 with {@code code}: no credential, an unknown one, or one whose secret matched but that no longer holds. */
    public static AccessRefused unauthenticated(RefusalCode code, String message) {
        return new AccessRefused(401, code, message, Map.of(), Set.of(), OptionalLong.empty());
    }

    /** 403 with {@code code} and the values its text names, naming the roles that grant what was refused. */
    public static AccessRefused forbidden(RefusalCode code, String message, Map<String, Object> values, Set<Role> needs) {
        return new AccessRefused(403, code, message, values, needs, OptionalLong.empty());
    }

    /** 429 {@code too-many-attempts}: the next attempt is counted only after {@code seconds}. */
    public static AccessRefused tooManyAttempts(long seconds) {
        return new AccessRefused(429, RefusalCode.TOO_MANY_ATTEMPTS, "too many attempts: try again in " + seconds + " s",
                Map.of("seconds", seconds), Set.of(), OptionalLong.of(seconds));
    }

    /** 503 {@code busy}: no place to hash a password is free on this node; nothing was counted. */
    public static AccessRefused busy() {
        return new AccessRefused(503, RefusalCode.BUSY, "the node is busy hashing passwords: try again", Map.of(), Set.of(), OptionalLong.of(1));
    }

    /** 503 {@code unaudited}: a required audit row was not written, so nothing ran. */
    public static AccessRefused unaudited(String message) {
        return new AccessRefused(503, RefusalCode.UNAUDITED, message, Map.of(), Set.of(), OptionalLong.empty());
    }

    /** A 400, 404 or 409 of an act on users, with its code and the values its text names. */
    public static AccessRefused of(int status, RefusalCode code, String message, Map<String, Object> values) {
        return new AccessRefused(status, code, message, values, Set.of(), OptionalLong.empty());
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The refusal's code. */
    public RefusalCode code() {
        return code;
    }

    /** The values the refusal's text names, strings or numbers, by name. */
    public Map<String, Object> values() {
        return values;
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
