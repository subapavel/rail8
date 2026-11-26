package dev.rail8.ingress;

import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The node's HTTP port ({@link HttpIngress}): one Vert.x server on one host and port, owned by a verticle of its own and shared
 * by every component that declares a prefix. A request's path, the query aside, picks the mount that holds it, matched exactly,
 * and that mount's handler gets the request on the connection's event loop. The port answers a path no mount holds itself: 503
 * with {@code Retry-After: 1} when {@code held} (given to {@link #listen}) says a flow serves it at the moment, else 404. A
 * handler that throws gets its request a 500 when no response head was written yet, else the request is reset.
 *
 * <p>A mount's {@link Mount#close()} frees its path at once and completes once every request it took has ended its response or
 * lost its connection, looked at every {@link #DRAIN_CHECK} on the timer; at the drain deadline, counted from that close, those
 * still open are reset. A reset closes the request's connection under HTTP/1.1 and only the request's stream under HTTP/2. The
 * port keeps the requests a mount took in a set it prunes of answered ones whenever it doubled in size, so it stays near the
 * number of requests in flight.
 *
 * <p>Lifecycle: built, then {@link #listen} once, then {@link #close} once. A mount fails before the listen and after the close.
 */
public final class SharedHttpPort implements HttpIngress {

    /** How often a closing mount looks whether the requests it took have answered. */
    public static final Duration DRAIN_CHECK = Duration.ofMillis(20);

    /** The size of a mount's set of requests at which it is first pruned of the answered ones. */
    static final int PRUNE_AT = 64;

    private static final System.Logger LOG = System.getLogger(SharedHttpPort.class.getName());
    private static final Pattern PREFIX = Pattern.compile(PREFIX_PATTERN);
    private static final Pattern ADDRESS_FORM = Pattern.compile(ADDRESS_PATTERN);
    /** {@link #ADDRESS} as a description lists it: what a prefixed component's settings must hold. */
    private static final Object ADDRESS_FIELD = ((List<?>) Settings.of(ADDRESS).toJson().get("fields")).getFirst();

    private enum State { NEW, LISTENING, OPEN, CLOSED }

    private final Vertx vertx;
    private final Timer timer;
    private final String host;
    private final int port;
    private final Duration drain;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final ConcurrentMap<String, Mounted> mounts = new ConcurrentHashMap<>();
    private volatile Map<String, String> prefixes = Map.of();
    private volatile Predicate<String> held = path -> false;
    private volatile int bound = -1;
    private volatile String deployment;

    /**
     * A port that will listen on {@code host}:{@code port} (0: any free port).
     *
     * @param timer the node's timer: the drain checks and deadlines of closing mounts
     * @param drain how long a closing mount waits for the requests it took before it resets them; not negative
     */
    public SharedHttpPort(Vertx vertx, Timer timer, String host, int port, Duration drain) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port " + port + " is not from 0 to 65535");
        this.port = port;
        this.drain = Objects.requireNonNull(drain, "drain");
        if (drain.isNegative()) throw new IllegalArgumentException("drain " + drain + " is negative");
    }

    /**
     * A port on 127.0.0.1 and a free port, its drain checks and deadline (PT30S) on Vert.x timers of {@code vertx}, listening for
     * {@code prefixes} (by scheme) with no path held: for the tests of a component that takes requests on the node's HTTP port.
     */
    public static Future<SharedHttpPort> loopback(Vertx vertx, Map<String, String> prefixes) {
        Timer timer = (delay, task) -> {
            long id = vertx.setTimer(Math.max(1, delay.toMillis()), fired -> task.run());
            return () -> vertx.cancelTimer(id);
        };
        SharedHttpPort port = new SharedHttpPort(vertx, timer, "127.0.0.1", 0, Duration.ofSeconds(30));
        return port.listen(prefixes, path -> false).map(bound -> port);
    }

    /**
     * The prefix of each of {@code components} that declares one, by its scheme, each read once: what {@link #listen} takes.
     *
     * @throws IllegalStateException naming the component, with its scheme and class, when its {@code httpPrefix()} throws or
     *                               answers null, when the prefix is not of {@link #PREFIX_PATTERN} or is
     *                               {@link #RESERVED_PREFIX}, when its settings do not list {@link #ADDRESS}, and naming both
     *                               when two components declare one prefix
     */
    public static Map<String, String> prefixes(List<Component> components) {
        Map<String, String> byScheme = new HashMap<>();
        Map<String, Component> byPrefix = new HashMap<>();
        for (Component c : components) {
            Optional<String> declared;
            try {
                declared = Objects.requireNonNull(c.httpPrefix(), "httpPrefix() answered null");
            } catch (RuntimeException thrown) {
                throw new IllegalStateException(owner(c) + ": its HTTP prefix cannot be read: " + thrown, thrown);
            }
            if (declared.isEmpty()) continue;
            String prefix = declared.get();
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix '" + prefix + "': a prefix is / and one segment, a lower-case"
                        + " letter, then at most 31 lower-case letters, digits or dashes");
            }
            if (RESERVED_PREFIX.equals(prefix)) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix " + prefix + ", which is kept for the platform itself");
            }
            Component other = byPrefix.putIfAbsent(prefix, c);
            if (other != null) {
                throw new IllegalStateException(owner(other) + " and " + owner(c) + " both declare the HTTP prefix " + prefix
                        + "; a prefix has one component");
            }
            if (!((List<?>) c.forms().flow().toJson().get("fields")).contains(ADDRESS_FIELD)) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix " + prefix + " but its flow form does not list the in field"
                        + " 'address' (HttpIngress.ADDRESS)");
            }
            byScheme.put(c.scheme(), prefix);
        }
        return Map.copyOf(byScheme);
    }

    private static String owner(Component c) {
        return "the component of the scheme " + c.scheme() + " (" + c.getClass().getName() + ")";
    }

    /**
     * The path a sender with the setting {@code address} is served at under {@code prefix}: the prefix, then the address.
     *
     * @throws IllegalArgumentException the address is missing, is no text or is not of {@link #ADDRESS_PATTERN}
     */
    public static String path(String prefix, Object address) {
        if (!(address instanceof String a) || !ADDRESS_FORM.matcher(a).matches()) {
            throw new IllegalArgumentException(address == null ? "option 'address' is missing" : "option 'address' " + address
                    + " is not a path such as /orders: segments of / and A-Z a-z 0-9 . _ ~ -, none of them . or .., at most 256 characters");
        }
        return prefix + a;
    }

    /**
     * Binds the port and from then on takes mounts.
     *
     * @param prefixes the prefix of each scheme whose consumers mount, as {@link #prefixes} answers it
     * @param held     whether a path no mount holds belongs to a flow at the moment, so its senders are to try again (503) rather
     *                 than hear that nothing serves it (404); called on the event loop, it must not block
     * @return the port bound (the actual one when it was built with 0); failed when it was called before or nothing could bind,
     *         and the port then takes no mounts
     */
    public Future<Integer> listen(Map<String, String> prefixes, Predicate<String> held) {
        Map<String, String> copied = Map.copyOf(prefixes);
        Objects.requireNonNull(held, "held");
        if (!state.compareAndSet(State.NEW, State.LISTENING)) return Future.failedFuture(new IllegalStateException("the HTTP port listens once"));
        this.prefixes = copied;
        this.held = held;
        Listener listener = new Listener();
        return vertx.deployVerticle(listener).compose(id -> {
            deployment = id;
            bound = listener.server.actualPort();
            if (state.compareAndSet(State.LISTENING, State.OPEN)) return Future.succeededFuture(bound);
            return vertx.undeploy(id).transform(ignored -> Future.failedFuture(new IllegalStateException("the HTTP port was closed while it bound")));
        }, failure -> {
            state.set(State.CLOSED);
            return Future.failedFuture(failure);
        });
    }

    /** The port bound: the actual one when it was built with 0; -1 before {@link #listen} succeeded. */
    public int port() {
        return bound;
    }

    /** How many requests the mount at {@code path} holds as taken, answered ones not yet pruned among them; 0 when none is mounted. */
    int taken(String path) {
        Mounted mounted = mounts.get(path);
        return mounted == null ? 0 : mounted.taken.size();
    }

    @Override
    public Future<Mount> mount(String scheme, Inbound in, Handler<HttpServerRequest> requests) {
        try {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(in, "in");
            Objects.requireNonNull(requests, "requests");
            String who = "flow '" + in.flow() + "'";
            String prefix = prefixes.get(scheme);
            if (state.get() != State.OPEN) {
                throw new IllegalStateException(who + ": the HTTP port takes no mounts " + (state.get() == State.CLOSED
                        ? "any more: it is closed" : "yet: it is not listening"));
            }
            if (prefix == null) throw new IllegalArgumentException(who + ": the scheme " + scheme + " declares no HTTP prefix");
            String path;
            try {
                path = path(prefix, in.settings().get(ADDRESS.name()));
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException(who + ": " + bad.getMessage(), bad);
            }
            Mounted mounted = new Mounted(who, path, requests, url(path));
            Mounted other = mounts.putIfAbsent(path, mounted);
            if (other != null) {
                throw new IllegalStateException(who + ": the path " + path + " is mounted by " + other.owner);
            }
            if (state.get() != State.OPEN) {   // closed while it mounted: never routed to
                mounts.remove(path, mounted);
                throw new IllegalStateException(who + ": the HTTP port takes no mounts any more: it is closed");
            }
            return Future.succeededFuture(mounted);
        } catch (RuntimeException refused) {
            return Future.failedFuture(refused);
        }
    }

    /**
     * What the caller of {@code request} presented, read from its head and its TLS session, never its body. The first proof
     * present decides: {@code Authorization}, then {@code X-API-Key}, then the TLS peer chain. {@code Authorization: Basic}
     * is a {@link Presented.Basic} of the user and the password it encodes, split at the first colon; {@code Authorization:
     * Bearer} a {@link Presented.Bearer}. An {@code Authorization} or an {@code X-API-Key} the port cannot read (more than one
     * copy, a basic value that does not decode, another scheme) still presents a credential, one that no credential matches:
     * an empty basic user and password, or an empty token or key. The request's {@link Presented.Via}: its remote address is
     * the socket's peer, it is secure over TLS or from loopback, it comes from a browser when it carries {@code Origin} or
     * {@code Referer}, or {@code Sec-Fetch-Site} with any value but {@code none}, and its sender choice is its one
     * {@code Rail8-Sender}.
     */
    @Override
    public Presented presented(HttpServerRequest request) {
        Presented.Via via = via(request);
        List<String> authorization = request.headers().getAll("authorization");
        if (!authorization.isEmpty()) return authorization.size() == 1 ? authorization(authorization.getFirst(), via) : new Presented.Basic("", "", via);
        List<String> apiKey = request.headers().getAll("x-api-key");
        if (!apiKey.isEmpty()) return new Presented.ApiKey(apiKey.size() == 1 ? apiKey.getFirst().strip() : "", via);
        if (request.isSSL()) {
            try {
                List<Certificate> peer = request.connection().peerCertificates();
                List<X509Certificate> chain = peer == null ? List.of() : peer.stream().filter(X509Certificate.class::isInstance).map(X509Certificate.class::cast).toList();
                if (!chain.isEmpty() && chain.size() == peer.size()) return new Presented.Certificate(chain, via);
            } catch (SSLPeerUnverifiedException none) {   // the peer sent no certificate
                return new Presented.Nothing(via);
            }
        }
        return new Presented.Nothing(via);
    }

    /** The one place that writes a refusal's answer on the node's HTTP port. */
    @Override
    public void refuse(HttpServerRequest request, Refused refused) {
        HttpServerResponse response = request.response();
        if (response.headWritten() || response.closed()) return;
        boolean body = hasBody(request);
        response.setStatusCode(status(refused.reason())).putHeader("content-type", "application/json");
        if (refused.reason() == Refused.Reason.UNKNOWN) response.putHeader("www-authenticate", "Basic realm=\"rail8\", charset=\"UTF-8\"");
        refused.retryAfter().ifPresent(wait -> response.putHeader("retry-after", Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000))));
        boolean http2 = request.version() == HttpVersion.HTTP_2;
        if (body && !http2) response.putHeader("connection", "close");
        response.end("{\"code\":\"" + refused.code() + "\",\"error\":\"" + refused.getMessage() + "\"}").onComplete(sent -> {
            if (!body) return;
            if (http2) response.reset(0);
            else request.connection().close();
        });
    }

    /** The HTTP status of a refusal for {@code reason}: 401, 403, 400, 429 or 503. */
    static int status(Refused.Reason reason) {
        return switch (reason) {
            case UNKNOWN -> 401;
            case NOT_ALLOWED, TLS_REQUIRED -> 403;
            case SENDER_NEEDED -> 400;
            case TOO_MANY -> 429;
            case BUSY -> 503;
        };
    }

    /** Whether {@code request} carries a body: a {@code content-length} other than 0, a {@code transfer-encoding}, or an HTTP/2 stream not ended. */
    private static boolean hasBody(HttpServerRequest request) {
        String length = request.getHeader("content-length");
        if (length != null) return !"0".equals(length.strip());
        return request.getHeader("transfer-encoding") != null
                || request.version() == HttpVersion.HTTP_2 && !request.isEnded();
    }

    /** One {@code Authorization} value as what it presents. */
    private static Presented authorization(String value, Presented.Via via) {
        String v = value.strip();
        int space = v.indexOf(' ');
        String scheme = (space < 0 ? v : v.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : v.substring(space + 1).strip();
        if (scheme.equals("bearer")) return new Presented.Bearer(rest, via);
        if (!scheme.equals("basic")) return new Presented.Bearer("", via);
        try {
            String decoded = new String(Base64.getDecoder().decode(rest), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) return new Presented.Basic("", "", via);
            return new Presented.Basic(decoded.substring(0, colon), decoded.substring(colon + 1), via);
        } catch (IllegalArgumentException notBase64) {
            return new Presented.Basic("", "", via);
        }
    }

    /** How {@code request} reached the port. */
    private static Presented.Via via(HttpServerRequest request) {
        SocketAddress peer = request.remoteAddress();
        String remote = peer == null || peer.hostAddress() == null ? "" : peer.hostAddress();
        String fetchSite = request.getHeader("sec-fetch-site");
        boolean browser = request.getHeader("origin") != null || request.getHeader("referer") != null
                || fetchSite != null && !"none".equalsIgnoreCase(fetchSite.strip());
        List<String> sender = request.headers().getAll("rail8-sender");
        Optional<String> choice = sender.size() == 1 ? Optional.of(sender.getFirst().strip()) : Optional.empty();
        return new Presented.Via(remote, request.isSSL() || loopback(remote), browser, choice);
    }

    /** Whether {@code address}, a literal IPv4 or IPv6 address, is a loopback address; false for anything else. */
    static boolean loopback(String address) {
        if (address.isEmpty() || !address.matches("[0-9A-Fa-f:.%]+")) return false;   // a literal only: nothing is resolved
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException unformed) {
            return false;
        }
    }

    private URI url(String path) {
        try {
            return new URI("http", null, host, bound, path, null, null);
        } catch (URISyntaxException unformed) {
            throw new IllegalArgumentException("the host " + host + " and the path " + path + " form no URL", unformed);
        }
    }

    /**
     * Takes no mounts from now on and stops listening: its connections close, a request still open on them is cut. Close the
     * mounts first, so their requests drain. Calling it again, or before {@link #listen}, does nothing more.
     */
    public Future<Void> close() {
        State was = state.getAndSet(State.CLOSED);
        String id = deployment;
        deployment = null;
        if (was == State.CLOSED || id == null) return Future.succeededFuture();
        return vertx.undeploy(id);
    }

    /** Routes {@code req} by its path; on the connection's event loop. */
    private void dispatch(HttpServerRequest req) {
        String path = req.path();
        Mounted mounted = path == null ? null : mounts.get(path);
        if (mounted != null && mounted.take(req)) {
            try {
                mounted.requests.handle(req);
            } catch (RuntimeException thrown) {
                LOG.log(System.Logger.Level.WARNING, "the handler of " + mounted.owner + " threw on a request at " + path, thrown);
                HttpServerResponse res = req.response();
                if (!res.headWritten()) res.setStatusCode(500).putHeader("content-type", "text/plain").end("the endpoint failed to take the request");
                else if (!res.ended()) res.reset();
            }
            return;
        }
        HttpServerResponse res = req.response().putHeader("content-type", "text/plain");
        if (path != null && held.test(path)) res.setStatusCode(503).putHeader("retry-after", "1").end("the endpoint at " + path + " takes no request at the moment");
        else res.setStatusCode(404).end("no endpoint at " + path);
    }

    /** Whether {@code req}'s response ended, or its connection is gone and it never will. */
    private static boolean answered(HttpServerRequest req) {
        HttpServerResponse res = req.response();
        return res.ended() || res.closed();
    }

    /** A sender mounted on the port, named by its flow, and the requests it took that may not have answered yet. */
    private final class Mounted implements Mount {
        final String owner;
        final String path;
        final Handler<HttpServerRequest> requests;
        final URI url;
        final Set<HttpServerRequest> taken = ConcurrentHashMap.newKeySet();
        final AtomicInteger pruneAt = new AtomicInteger(PRUNE_AT);
        final AtomicBoolean closing = new AtomicBoolean();
        final Promise<Void> drained = Promise.promise();
        volatile boolean closed;

        Mounted(String owner, String path, Handler<HttpServerRequest> requests, URI url) {
            this.owner = owner;
            this.path = path;
            this.requests = requests;
            this.url = url;
        }

        /** Whether the handler gets {@code req}: false once the mount is closing. Added before the look, so a close sees it. */
        boolean take(HttpServerRequest req) {
            taken.add(req);
            if (closed) {
                taken.remove(req);
                return false;
            }
            if (taken.size() >= pruneAt.get()) {
                taken.removeIf(SharedHttpPort::answered);
                pruneAt.set(Math.max(PRUNE_AT, 2 * taken.size()));
            }
            return true;
        }

        @Override
        public URI url() {
            return url;
        }

        @Override
        public Future<Void> close() {
            if (closing.compareAndSet(false, true)) {
                closed = true;
                mounts.remove(path, this);
                try {
                    Timer.Cancellable deadline = timer.schedule(drain, this::cut);
                    check(deadline);
                } catch (RuntimeException refused) {   // a timer that takes no task: nothing waits
                    cut();
                }
            }
            return drained.future();
        }

        private void check(Timer.Cancellable deadline) {
            taken.removeIf(SharedHttpPort::answered);
            if (taken.isEmpty()) {
                deadline.cancel();
                drained.tryComplete();
            } else if (!drained.future().isComplete()) {
                timer.schedule(DRAIN_CHECK, () -> check(deadline));
            }
        }

        /** Resets what is still open and completes the close. */
        private void cut() {
            for (HttpServerRequest req : taken) {
                if (answered(req)) continue;
                LOG.log(System.Logger.Level.WARNING, owner + " closed with a request at " + path + " still open after " + drain
                        + ": it is reset");
                req.response().reset();
            }
            taken.clear();
            drained.tryComplete();
        }
    }

    /** The verticle that owns the server. */
    private final class Listener extends VerticleBase {
        volatile HttpServer server;

        @Override
        public Future<?> start() {
            return vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)).requestHandler(SharedHttpPort.this::dispatch).listen()
                    .map(listening -> server = listening);
        }

        @Override
        public Future<?> stop() {
            return server.close();
        }
    }
}
