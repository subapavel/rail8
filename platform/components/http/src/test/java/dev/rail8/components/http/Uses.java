package dev.rail8.components.http;

import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;

import java.util.Map;
import java.util.Optional;

/** The uses a test hands the http component, as the node hands them over. */
final class Uses {

    /** The flow of every call of a case. */
    static final String FLOW = "shop/orders";

    private Uses() {}

    /** A sender of {@code flow} at {@code address}, bodies up to {@code maxBodyBytes}; no connection, as the in direction reaches none. */
    static Inbound in(String flow, String address, long maxBodyBytes) {
        return new Inbound(flow, Optional.empty(), Map.of("address", address), maxBodyBytes);
    }

    /** A producer's connection {@code partner} with {@code values}. */
    static Outbound out(Map<String, Object> values) {
        return new Outbound(Optional.of(new Connection("partner", values, Map.of())));
    }

    /** A producer's connection {@code partner} to {@code url}, the other values their defaults. */
    static Outbound out(String url) {
        return out(Map.of("url", url));
    }

    /** A call of the step {@code send} without a path, a reply up to {@code maxReplyBytes}. */
    static Call call(long maxReplyBytes) {
        return new Call(FLOW, "send", Map.of(), maxReplyBytes, Optional.empty());
    }

    /** A call of the step {@code send} to {@code path}, a reply up to {@code maxReplyBytes}. */
    static Call call(String path, long maxReplyBytes) {
        return new Call(FLOW, "send", Map.of("path", path), maxReplyBytes, Optional.empty());
    }
}
