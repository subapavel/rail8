package dev.rail8.api;

import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import io.vertx.core.Future;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An adapter: the only way the platform touches the outside world. One implementation per scheme: the node ships some and
 * a {@link dev.rail8.api.plugin.Plugin} adds others, each built from the node's
 * {@link dev.rail8.api.plugin.ComponentContext}. Every component ships with its mock and both pass the same contract
 * suite.
 *
 * <p>A producer belongs to a connection, not to a flow and step: the node builds one per connection and every flow that
 * sends through it shares it, each send carrying the flow's settings in its {@link Call}. The node may build a fresh
 * consumer or producer of the same use at any time and closes the old one; building one opens nothing (no socket, no
 * session, no file) and reads no secret handle.
 *
 * @since 0.1
 */
public interface Component {

    /** The adapter type, as a flow names it: {@code "http"}, {@code "sftp"}, {@code "mock"} … */
    String scheme();

    /**
     * The adapter's two forms and the directions it serves ({@link Forms}): the node checks every use against them when
     * it validates, deploys or restores a package and splits an endpoint's config by them, the admin API serves them and
     * the UI draws its forms from them. It returns the same forms on every call. A plugin's component is called once when
     * the node loads the plugin; a throw there refuses the node's start.
     *
     * @since 0.4
     */
    Forms forms();

    /**
     * A consumer that calls {@code handler} for every message and completes its protocol from the returned {@link Ack}. The
     * node may call it again for {@code in}, with the same handler, once the previous consumer of {@code in} stopped: after
     * its start failed, after it broke ({@link Consumer#broken()}), after its poller lease moved, or when the connection
     * changed. It starts each consumer at most once: a stopped consumer is never started again.
     *
     * @since 0.4
     */
    Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler);

    /**
     * The kinds of credential this adapter's consumers read from callers; none by default. A component that declares a kind
     * overrides {@link #consumer(Inbound, Entry)}, or the node refuses it when it loads it. An adapter on the node's HTTP port
     * answers {@link dev.rail8.api.plugin.HttpIngress#READS}; one with a listener of its own the kinds it reads there, such as
     * {@link CredentialKind#CERTIFICATE}. A flow takes a sender of an adapter that reads none only with its check off.
     *
     * @since 0.4
     */
    default Set<CredentialKind> reads() {
        return Set.of();
    }

    /**
     * The consumer of one sender, which hands each message over through {@code entry}: the node builds every sender's
     * consumer with it. For each message the consumer calls {@link Entry#enter} with what the caller presented before it
     * reads or stores any byte of the body, keeps the request paused until {@code enter} completes, answers a {@link Refused}
     * in its own protocol ({@link dev.rail8.api.plugin.HttpIngress#refuse} on the node's HTTP port) and stores nothing then, and otherwise hands the message to
     * the handler {@code enter} gave, once. It holds no handler of its own.
     *
     * <p>The default keeps an adapter built only on {@link #consumer(Inbound, Function)} working: it stores the body as that
     * consumer does, then enters with {@link Presented.Nothing}. Such an adapter reads no credential, so a flow uses it as
     * a listening sender only with the check off, and it answers a refusal as it answers a failed handler.
     *
     * @since 0.4
     */
    default Consumer consumer(Inbound in, Entry entry) {
        return consumer(in, e -> entry.enter(new Presented.Nothing(Presented.Via.of("", false))).compose(h -> h.apply(e)));
    }

    /** A producer of the connection {@code out} names, or of the internal adapter when it names none. @since 0.4 */
    Producer producer(Outbound out);

    Future<Health> health();

    /**
     * The component's own address on the node's HTTP port ({@link dev.rail8.api.plugin.HttpIngress}): {@code /} and one segment
     * of {@link dev.rail8.api.plugin.HttpIngress#PREFIX_PATTERN}, such as {@code /http} or {@code /webhook}. A sender of the
     * component is served at this prefix followed by its {@code address} setting. Empty, the default: no consumer of it takes
     * requests on that port. It is the same on every call. The node reads it at its start, once its components are built, and
     * so do {@code rail8 validate} and {@code rail8 test}. A throw, a prefix not of that form,
     * {@link dev.rail8.api.plugin.HttpIngress#RESERVED_PREFIX}, a prefix another component declares, or a prefix without
     * {@link dev.rail8.api.plugin.HttpIngress#ADDRESS} among the in fields of the flow form refuses the node's start,
     * naming the owners.
     *
     * @since 0.3
     */
    default Optional<String> httpPrefix() {
        return Optional.empty();
    }

    /**
     * The local host and port a consumer of {@code in} binds, if it accepts connections on a port of its own; empty if it
     * polls, subscribes, connects out or takes requests on the node's HTTP port ({@link #httpPrefix()}). Answered from
     * {@code in} alone, already checked against the forms, as an unresolved address ({@link InetSocketAddress#createUnresolved},
     * the host as configured): nothing is bound and no name is resolved. Port 0 (any free port) is never compared. The node
     * calls it at deploy and at restore, on a virtual thread, to refuse two listeners on one host:port; a throw is a problem
     * line of that deploy.
     *
     * @since 0.4
     */
    default Optional<InetSocketAddress> listensOn(Inbound in) {
        return Optional.empty();
    }

    /**
     * What a consumer of {@code in} polls, within its connection, when it polls what another node may poll as well (for
     * {@code sftp}, its directory and glob); empty when it does not poll. Answered from {@code in} alone, already checked
     * against the forms: nothing is opened. When present, the node runs a consumer of {@code in} only while it holds the
     * flow's poller lease, the next one starts only once the one before it stopped, and a deploy refuses two flows whose
     * senders poll the same connection and place. The node calls it at deploy and at restore, on a virtual thread; a throw
     * is a problem line of that deploy.
     *
     * @since 0.4
     */
    default Optional<String> polledPlace(Inbound in) {
        return Optional.empty();
    }

    /**
     * The system a producer of {@code out} calls, the key its sends are limited by: {@code scheme://host:port}, lower-cased,
     * the scheme's default port filled in, never userinfo, path, query or fragment. Answered from the connection's values
     * alone: nothing is opened and no name is resolved. Empty: the producer's sends get a limit of their own, shared with no other.
     * The node calls it when it builds a producer; a throw fails that build.
     *
     * @since 0.4
     */
    default Optional<String> target(Outbound out) {
        return Optional.empty();
    }

    /**
     * The node-local address a use of {@code direction} with {@code settings}, the flow's settings for that direction,
     * serves (in) or calls (out), if the component joins flows of one node; empty if it reaches outside the node. Two uses
     * name one address when their schemes are equal and their answers are equal strings, matched exactly. Nothing is opened
     * or claimed, and the answer is the same on every call. The node calls it at deploy and at restore, on a virtual thread,
     * to refuse two flows on one address and calls between flows that would form a cycle; a throw is a problem line of that
     * deploy.
     *
     * @since 0.4
     */
    default Optional<String> internalAddress(Direction direction, Map<String, Object> settings) {
        return Optional.empty();
    }
}
