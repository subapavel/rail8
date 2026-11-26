package dev.rail8.api.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.CredentialKind;
import dev.rail8.api.Inbound;
import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import dev.rail8.api.settings.Field;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;

import java.net.URI;
import java.util.Set;

/**
 * The node's HTTP port, shared by every component that declares a prefix ({@link Component#httpPrefix()}): one port for all
 * inbound HTTP-based adapters, as in SAP Integration Suite, where the HTTPS adapter serves {@code /http/<address>} and the
 * SOAP adapter {@code /cxf/<address>} on one host. A request is routed by its path: the adapter's prefix, then the address its
 * in endpoint names ({@link #ADDRESS}). A component reaches the port through {@link ComponentContext#http()}; only the node
 * implements it.
 *
 * <p>A consumer mounts its endpoint when it starts and closes the mount when it stops. Between the two, every request at the
 * mount's path reaches the consumer's handler, whatever its method, and the consumer answers it by its own protocol: which
 * methods it takes, the body limit, streaming the body into the blob store, the message's own virtual thread and the status
 * from the flow's answer are the adapter's, as they would be on a port of its own. Before any of the body is read, the
 * consumer enters the message ({@link dev.rail8.api.Entry#enter}) with what {@link #presented} reads from the request, and
 * the request stays paused until the entry decides. The node answers only what no mount can:
 * 404 for a path that no flow serves; 503 with {@code Retry-After} for the path of a flow that is deployed but has no mount at
 * the moment (while a redeploy swaps it, while its consumer restarts, while it stops); 500 for a request whose handler threw
 * before it answered.
 *
 * @since 0.3
 */
public interface HttpIngress {

    /** A prefix's form: {@code /} and one segment, a lower-case letter, then at most 31 lower-case letters, digits or dashes. */
    String PREFIX_PATTERN = "^/[a-z][a-z0-9-]{0,31}$";

    /** The prefix kept for the platform itself; no component declares it. */
    String RESERVED_PREFIX = "/rail8";

    /**
     * An address's form: segments of {@code /} and at least one of {@code A-Z a-z 0-9 . _ ~ -}, none of them {@code .} or
     * {@code ..}, at most 256 characters in all.
     */
    String ADDRESS_PATTERN = "^(?=.{2,256}$)(/(?!\\.\\.?(/|$))[A-Za-z0-9._~-]+)+$";

    /**
     * The setting {@code address} of a sender: the path under the adapter's prefix that senders call, such as
     * {@code /orders}, matched exactly. A component with a prefix lists this field in its flow form.
     */
    Field ADDRESS = Field.text("address", "Address", "The path under the adapter's prefix on the node's HTTP port that senders call, "
            + "such as /orders; matched exactly.").required().pattern(ADDRESS_PATTERN).in();

    /** The kinds the port reads for every adapter on it: what such an adapter answers from {@link Component#reads()}. */
    Set<CredentialKind> READS = Set.of(CredentialKind.BASIC, CredentialKind.API_KEY, CredentialKind.CERTIFICATE, CredentialKind.OAUTH2);

    /** The port where none is open ({@code rail8 validate}, {@code rail8 test}): every mount fails. */
    HttpIngress NONE = (scheme, in, requests) -> Future.failedFuture(new IllegalStateException(
            "flow '" + in.flow() + "': no HTTP port is open here"));

    /**
     * Mounts the sender {@code in} on the port: the path is the prefix of the component of {@code scheme} followed by
     * {@code in}'s {@code address} setting, matched exactly against a request's path (the query aside, case-sensitive).
     *
     * <p>{@code requests} is called on the connection's event loop, once for every request at the path, before any of its body
     * is read. It must not block: every mount of every component shares the node's event loops, and a handler that blocks one
     * stalls every other request on it. A plugin runs inside the node as trusted code. It owns the request from then on and
     * ends its response.
     *
     * <p>The future fails, and nothing is mounted, with an {@link IllegalArgumentException} when the scheme's component declares
     * no prefix or the address is missing or not of {@link #ADDRESS_PATTERN}, and with an {@link IllegalStateException} when a
     * mount that is not closed holds the path or the port takes no mounts (none is open, or the node is closing). The consumer's
     * start fails with it, and the node does what it does with any failed start: it refuses a first deploy, rolls a redeploy
     * back, and starts the consumer again with back-off after a restore or a break. It may be called on any thread.
     *
     * @since 0.4
     */
    Future<Mount> mount(String scheme, Inbound in, Handler<HttpServerRequest> requests);

    /**
     * What the caller of a request this port handed to a mount presented. The first proof present decides, in this order:
     * {@code Authorization} ({@code Basic}, or {@code Bearer}), then {@code X-API-Key}, then the client certificate. It reads
     * the head and the TLS session only, never the body. The node's port overrides it; the default presents nothing, which
     * a check refuses.
     *
     * @since 0.4
     */
    default Presented presented(HttpServerRequest request) {
        return new Presented.Nothing(Presented.Via.of("", false));
    }

    /**
     * Ends a request this port handed to a mount with the whole HTTP answer to {@code refused}: its status (401 with
     * {@code WWW-Authenticate: Basic} for an unknown caller, 403, 400, 429 or 503), {@code Retry-After} in whole seconds when
     * it waits, and the body {@code {"code": …, "error": …}} with the fixed text of its code. A request with a body has its
     * connection closed, or its HTTP/2 stream reset, so the refused body is never read. A request whose answer was already
     * begun is left as it is. The node's port overrides it; the default throws an {@link IllegalStateException}, since a
     * port that is no node's hands no request to a mount.
     *
     * @since 0.4
     */
    default void refuse(HttpServerRequest request, Refused refused) {
        throw new IllegalStateException("this HTTP port answers no refusal: it hands no request to a mount");
    }

    /**
     * One endpoint mounted on the port.
     *
     * @since 0.3
     */
    interface Mount {

        /**
         * The URL of the mount on the node's own socket: the port's scheme, host and port as it listens, then the mount's path.
         * Behind a proxy or a published container port, senders call another host and port at the same path.
         */
        URI url();

        /**
         * Unmounts: from the call on no request reaches the handler, and the path is free for a new mount at once. Completes
         * once every request already handed to the handler has ended its response or lost its connection; the node resets those
         * still open at its stop deadline. Calling it again returns the same future. It never fails.
         */
        Future<Void> close();
    }
}
