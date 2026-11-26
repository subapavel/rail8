package dev.rail8.api;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A refusal at the entry ({@link Entry#enter}): the caller may not send this message, and no message exists. Its
 * {@link #getMessage()} is the fixed text of its {@link #code()}, so it names no system, no flow and nothing presented. An
 * adapter on the node's HTTP port answers it with {@link dev.rail8.api.plugin.HttpIngress#refuse}; an adapter of another
 * protocol answers it in that protocol, from {@link #reason()}, {@link #code()} and {@link #retryAfter()}.
 *
 * @since 0.4
 */
public final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Why the caller is refused.
     *
     * @since 0.4
     */
    public enum Reason {
        /** Nothing presented, or a malformed, unknown, wrong or removed credential. */
        UNKNOWN,
        /** A known caller the flow does not allow. */
        NOT_ALLOWED,
        /** The request must name one sending system, and does not. */
        SENDER_NEEDED,
        /** Too many failures, or over the flow's limit per caller. */
        TOO_MANY,
        /** No room at the moment. */
        BUSY,
        /** A credential presented in clear text. */
        TLS_REQUIRED
    }

    private final Reason reason;
    private final Duration retryAfter;   // null for a reason that does not wait

    /**
     * A refusal for {@code reason}; for the platform and the testkit.
     *
     * @param retryAfter when the caller may try again: present for {@link Reason#TOO_MANY} and {@link Reason#BUSY}, positive,
     *                   and empty for every other reason
     */
    public Refused(Reason reason, Optional<Duration> retryAfter) {
        super(text(Objects.requireNonNull(reason, "reason")), null, false, false);
        Objects.requireNonNull(retryAfter, "retryAfter");
        boolean waits = reason == Reason.TOO_MANY || reason == Reason.BUSY;
        if (waits != retryAfter.isPresent()) {
            throw new IllegalArgumentException(reason + (waits ? " takes a time to try again after" : " takes no time to try again after"));
        }
        if (retryAfter.isPresent() && (retryAfter.get().isNegative() || retryAfter.get().isZero())) {
            throw new IllegalArgumentException("the time to try again after is not positive: " + retryAfter.get());
        }
        this.reason = reason;
        this.retryAfter = retryAfter.orElse(null);
    }

    public Reason reason() { return reason; }

    /** {@code unknown-caller}, {@code not-allowed}, {@code sender-needed}, {@code too-many}, {@code busy} or {@code tls-required}. */
    public String code() {
        return switch (reason) {
            case UNKNOWN -> "unknown-caller";
            case NOT_ALLOWED -> "not-allowed";
            case SENDER_NEEDED -> "sender-needed";
            case TOO_MANY -> "too-many";
            case BUSY -> "busy";
            case TLS_REQUIRED -> "tls-required";
        };
    }

    /** When the caller may try again: for {@link Reason#TOO_MANY} and {@link Reason#BUSY}. */
    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }

    /** The fixed English text of {@code reason}'s code. */
    static String text(Reason reason) {
        return switch (reason) {
            case UNKNOWN -> "the caller is not known";
            case NOT_ALLOWED -> "the caller may not send to this flow";
            case SENDER_NEEDED -> "the request must name one sending system";
            case TOO_MANY -> "too many attempts; try again later";
            case BUSY -> "no message is taken at the moment; try again later";
            case TLS_REQUIRED -> "a credential is taken only over TLS";
        };
    }

    @Override
    public String toString() {
        return "Refused[" + code() + retryAfter().map(wait -> ", retry after " + wait.toString().toLowerCase(Locale.ROOT)).orElse("") + "]";
    }
}
