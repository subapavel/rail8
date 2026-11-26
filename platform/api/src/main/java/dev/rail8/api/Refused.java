package dev.rail8.api;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A refusal at the entry ({@link Entry#enter}): the caller may not send this message, and no message exists. Its
 * {@link #getMessage()} is the fixed text of its {@link #code()}, so it names no system, no flow and nothing presented.
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
    private final transient Optional<Duration> retryAfter;

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
        this.retryAfter = retryAfter;
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
    public Optional<Duration> retryAfter() { return retryAfter; }

    /** The HTTP status of the answer: 401, 403, 400, 429 or 503. */
    public int status() {
        return switch (reason) {
            case UNKNOWN -> 401;
            case NOT_ALLOWED, TLS_REQUIRED -> 403;
            case SENDER_NEEDED -> 400;
            case TOO_MANY -> 429;
            case BUSY -> 503;
        };
    }

    /**
     * Ends the request with the whole HTTP answer: the status, {@code WWW-Authenticate} for an unknown caller,
     * {@code Retry-After} in whole seconds rounded up when it waits, and the body {@code {"code": …, "error": …}} with the
     * fixed text of the code. A request with a body has its connection closed (HTTP/1.x), or its HTTP/2 stream reset once
     * the answer is sent, so the refused body is never read. A request whose answer was already begun is left as it is.
     */
    public void answer(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        if (response.headWritten() || response.closed()) return;
        boolean body = hasBody(request);
        response.setStatusCode(status()).putHeader("content-type", "application/json");
        if (reason == Reason.UNKNOWN) response.putHeader("www-authenticate", "Basic realm=\"rail8\", charset=\"UTF-8\"");
        retryAfter.ifPresent(wait -> response.putHeader("retry-after", Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000))));
        boolean http2 = request.version() == HttpVersion.HTTP_2;
        if (body && !http2) response.putHeader("connection", "close");
        response.end("{\"code\":\"" + code() + "\",\"error\":\"" + text(reason) + "\"}").onComplete(sent -> {
            if (!body) return;
            if (http2) response.reset(0);
            else request.connection().close();
        });
    }

    /** Whether {@code request} carries a body: a {@code content-length} other than 0, or a {@code transfer-encoding}. */
    private static boolean hasBody(HttpServerRequest request) {
        String length = request.getHeader("content-length");
        if (length != null) return !"0".equals(length.strip());
        return request.getHeader("transfer-encoding") != null
                || request.version() == HttpVersion.HTTP_2 && !request.isEnded();
    }

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
        return "Refused[" + code() + retryAfter.map(wait -> ", retry after " + wait.toString().toLowerCase(Locale.ROOT)).orElse("") + "]";
    }
}
