package dev.rail8.components.mock;

import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;

import java.util.Map;
import java.util.Optional;

/**
 * The uses a test hands the mock, as the node hands them over: each names a connection, keyed by the endpoint's id, which
 * holds every value of the endpoint's config since the mock's forms describe nothing.
 */
final class Uses {

    /** A body and reply limit no case reaches. */
    static final long LIMIT = 1L << 30;

    /** The call of every send of a case: the step {@code send} of the flow {@code test}, no settings, a reply up to {@link #LIMIT}. */
    static final Call CALL = new Call("test", "send", Map.of(), LIMIT, Optional.empty());

    private Uses() {}

    /** The sender of the endpoint {@code key}, for the flow of the same name, bodies up to {@link #LIMIT}. */
    static Inbound in(String key) {
        return in(key, key, LIMIT, Map.of());
    }

    /** The sender of the endpoint {@code key} for the flow {@code flow}, bodies up to {@code maxBodyBytes}, the connection holding {@code values}. */
    static Inbound in(String key, String flow, long maxBodyBytes, Map<String, Object> values) {
        return new Inbound(flow, Optional.of(new Connection(key, values, Map.of())), Map.of(), maxBodyBytes);
    }

    /** What a producer of the endpoint {@code key} is built for. */
    static Outbound out(String key) {
        return new Outbound(Optional.of(new Connection(key, Map.of(), Map.of())));
    }

    /** A call like {@link #CALL} whose reply is at most {@code maxReplyBytes}. */
    static Call call(long maxReplyBytes) {
        return new Call("test", "send", Map.of(), maxReplyBytes, Optional.empty());
    }
}
