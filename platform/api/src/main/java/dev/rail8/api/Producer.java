package dev.rail8.api;

import io.vertx.core.Future;

/**
 * The outbound side of a component for one connection, or for an internal adapter: sends an envelope as a {@link Call}
 * says and yields the envelope that came back (the reply, or the sent envelope with transport headers such as
 * {@code http.status}). Every flow and step that sends through the connection shares it, so two calls with different
 * settings may be out at once. Producers pipe {@link Body#open()}; they never buffer. A producer sends the message id to
 * its partner under {@link Envelope#H_MESSAGE_ID} ({@link Envelope#withOutboundId()}), so the partner can drop a send it
 * already got. Building one opens nothing: its first send does.
 *
 * <p>The node may build a fresh producer of the same connection at any time, and closes the old one once its sends
 * settle, at the latest at their deadline; closing one never breaks another.
 *
 * @since 0.1
 */
public interface Producer {

    /** @since 0.4 */
    Future<Envelope> send(Envelope e, Call call);

    /**
     * {@link #send(Envelope, Call)} bounded by {@code d}: with no time left ({@code d.remaining()} zero or less) a failed
     * future with a retryable {@link NackException}, nothing started; else {@code send(e, call)}. A producer that overrides
     * it also cuts a send still open once a re-read of {@code d} finds no time left — the same retryable failure.
     *
     * @since 0.4
     */
    default Future<Envelope> send(Envelope e, Call call, Deadline d) {
        if (!d.remaining().isPositive()) {
            return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent", true));
        }
        return send(e, call);
    }

    Future<Void> close();
}
