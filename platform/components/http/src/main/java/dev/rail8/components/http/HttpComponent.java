package dev.rail8.components.http;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.CredentialKind;
import dev.rail8.api.Deadline;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Inbound;
import dev.rail8.api.Key;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Refused;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Escape;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The {@code http} component: its consumers take requests on the node's HTTP port, its producers send over the Vert.x client.
 *
 * <p><b>Consumer</b> (the flow form's {@code address}; no connection): it takes requests on the node's HTTP
 * port ({@link HttpIngress}) under the prefix {@value #PREFIX}, at {@code /http} followed by its {@code address}, such as
 * {@code /http/orders} ({@link HttpConsumer#url()}); the port's verticle owns the socket and the consumer never runs a pipeline
 * on its event loop. A request at the path is paused and entered first ({@link Entry#enter}) with what the port read from its
 * head ({@link HttpIngress#presented}), before any byte of its body is read: a {@link Refused} caller gets the refusal's answer
 * ({@link HttpIngress#refuse}) and nothing is stored, any other failure of the entry is 500 with a fixed text. An entered request
 * is answered 405 for a method other than POST and PUT, and is otherwise streamed into the {@link BlobStore} under the {@link Inbound#maxBodyBytes} of its
 * flow (413 above it; a store that fails otherwise is 500 with a fixed text, its failure logged at WARNING),
 * becomes the ingress envelope (new id, the request headers, {@link #METHOD} / {@link #PATH} props) and is handed to the handler
 * on its own virtual thread, through the handler the entry gave;
 * the answer follows the {@link Ack}: {@code Reply} → 200 with the reply's headers and its body streamed, {@code Accepted} →
 * 202 with {@code x-message-id}, a retryable {@code Nack} → 503 + {@code Retry-After}, a final one → 400. A failed handler (a
 * sync flow's error reaches the caller) is answered by the failure's {@link Fault.Kind}: {@code MALFORMED} 400,
 * {@code INVALID} 422, {@code DOWNSTREAM} 503 + {@code Retry-After} when retryable ({@link NackException},
 * {@link HttpStatusException}) else 502, {@code INTERNAL} and any failure that is no {@link Fault} 500 — with
 * {@code x-message-id} and, as {@code text/plain}, a fixed text per status that says what kind of
 * fault it was and nothing of the failure. The failure itself is logged with the message's id and flow (INFO for a 4xx,
 * WARNING with its stack for a 5xx) and is in the message's history. The platform's own cookies
 * ({@link #PLATFORM_COOKIES}) are cut from the request's {@code cookie} before it becomes the envelope, and a reply's
 * {@code set-cookie} of one of them is never sent; other cookies pass. Its stop closes its mount: no request reaches it from then
 * on, and the stop completes once the requests it took have answered their senders, or at the node's stop deadline.
 *
 * <p><b>Producer</b> (the connection's {@code url}, {@code timeoutMs} and {@code poolSize}; each call's {@code path} and
 * {@link Call#maxReplyBytes} for the reply): one client per connection with its connection pool, shared by every flow that
 * sends through it. A call without {@code path} goes to {@code url} as it is. A call with one goes to {@code url} with the
 * path joined to the url's own path, the url's query kept: {@code https://h/api?k=1} and {@code /orders} give
 * {@code https://h/api/orders?k=1}. The joined URI is parsed once and the request is built from its parts (host, port, path
 * and query, TLS for https); a path that is not a path from {@code /}, holds a query or a fragment, does not parse, or once
 * its dot segments are resolved ({@code %2e} read as {@code .}) leaves the url's path, host, port or scheme fails the send
 * with a {@link Fault} of kind {@link Fault.Kind#INVALID}, nothing sent; its message never quotes the url. The url's user
 * info is never sent, with a path or without, as Vert.x never sends it. A send POSTs the envelope's body — streamed from {@link Body#open()}, so a retry
 * re-opens it — with the envelope's headers (hop-by-hop ones aside) and its message id as {@code x-message-id}, a forwarded one
 * replaced ({@link Envelope#withOutboundId()}); a bounded send ({@code send(e, call, d)}) is cut at its deadline on the
 * component's {@link Timer} — the request reset — and a {@code timeoutMs} expiry fails as a cut does, a retryable
 * {@link NackException}; a 2xx response is the reply envelope (its body the next
 * step's, stored under {@link Envelope#nextKey()} of the envelope sent — the run's own key from its second attempt on —
 * {@link #STATUS} prop, the response headers), 503 and 429 fail with a retryable {@link NackException}, any other
 * non-2xx with {@link HttpStatusException} carrying the first {@value #ERROR_BODY_BYTES} bytes of the response (the rest drained).
 *
 * @since 0.1
 */
public final class HttpComponent implements Component {

    /** The request's method on an ingress envelope. */
    public static final Key<String> METHOD = new Key<>("http.method");
    /** The request's path on an ingress envelope: the prefix and the address, such as {@code /http/orders}. */
    public static final Key<String> PATH = new Key<>("http.path");
    /** The component's prefix on the node's HTTP port. */
    public static final String PREFIX = "/http";
    /** The response status on a reply envelope. */
    public static final Key<Integer> STATUS = new Key<>("http.status");
    /** The header carrying the message id on a 202, and on every request a producer sends ({@link Envelope#H_MESSAGE_ID}). */
    public static final String H_MESSAGE_ID = Envelope.H_MESSAGE_ID;
    /**
     * The names of the cookies of the node's own UI: its session and its device, each with and without {@code __Host-}. The UI
     * can share a host with the node's HTTP port, and a browser sends a host's cookies to every port of it, so a consumer
     * cuts these from a request's {@code cookie} and never sends a reply's {@code set-cookie} of one of them.
     */
    public static final Set<String> PLATFORM_COOKIES = Set.of("rail8-session", "__Host-rail8-session", "rail8-device", "__Host-rail8-device");
    /** The text of the 500 a consumer answers when the request's body could not be stored. */
    static final String NOT_STORED = "the message could not be stored";

    private static final System.Logger LOG = System.getLogger(HttpComponent.class.getName());

    private static final Set<String> HOP_BY_HOP = Set.of("content-length", "transfer-encoding", "connection", "host", "keep-alive", "upgrade", "expect", "te", "trailer", "proxy-authorization", "proxy-authenticate");
    /** A URL without user info: no {@code @} between {@code //} and the path, query or fragment. */
    static final String NO_USER_INFO = "[A-Za-z][A-Za-z0-9+.-]*://[^/?#@]+([/?#].*)?";
    /**
     * The forms: the connection says where the partner is (url, timeout, pool), the flow says the consumer's address and the
     * path of a send. A sender reaches no connection; a send always does.
     */
    static final Forms FORMS = new Forms(
            Settings.of(
                    Field.text("url", "URL", "The http or https URL requests are posted to: scheme, host, port and a path prefix, "
                            + "such as https://partner.example/api. No user name or password in it.").required().pattern(NO_USER_INFO).out().server(),
                    Field.integer("timeoutMs", "Timeout (ms)", "How long a request may take, in milliseconds.")
                            .range(1, Integer.MAX_VALUE).byDefault(30_000).out(),
                    Field.integer("poolSize", "Pool size", "How many connections to the partner are kept open at most.")
                            .range(1, Integer.MAX_VALUE).byDefault(8).out()),
            Settings.of(
                    HttpIngress.ADDRESS,
                    Field.text("path", "Path", "The path a send goes to, joined to the path of the connection's url, such as /orders. "
                            + "None: the url as it is.").pattern("/.*").expression(Escape.PATH).out()),
            Map.of(Direction.IN, Forms.Reach.NO_CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));
    /** How much of a non-2xx response body travels in the failure's message; the rest is read and dropped. */
    static final int ERROR_BODY_BYTES = 512;

    private final Vertx vertx;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final HttpIngress http;
    private final Map<HttpConsumer, Boolean> running = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong handled = new java.util.concurrent.atomic.AtomicLong();   // numbers the handler threads

    /** One whose producers time their bounded sends on Vert.x timers of {@code vertx}, without an HTTP port: no consumer of it starts. */
    public HttpComponent(Vertx vertx, BlobStore blobs, IdGenerator ids) {
        this(vertx, blobs, ids, vertxTimer(vertx));
    }

    /** One whose producers time their bounded sends on {@code timer}, without an HTTP port: no consumer of it starts. */
    public HttpComponent(Vertx vertx, BlobStore blobs, IdGenerator ids, Timer timer) {
        this(vertx, blobs, ids, timer, HttpIngress.NONE);
    }

    /**
     * One whose producers time their bounded sends on {@code timer} (the node's: {@code Runtime.timer()}) and whose consumers
     * mount their endpoints on {@code http} (the node's HTTP port).
     */
    public HttpComponent(Vertx vertx, BlobStore blobs, IdGenerator ids, Timer timer, HttpIngress http) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.http = Objects.requireNonNull(http, "http");
    }

    /** A {@link Timer} on {@code vertx}'s timers, at least a millisecond (Vert.x refuses less), rounded up. */
    private static Timer vertxTimer(Vertx vertx) {
        Objects.requireNonNull(vertx, "vertx");
        return (delay, task) -> {
            long id = vertx.setTimer(Math.max(1, (delay.toNanos() + 999_999) / 1_000_000), fired -> task.run());
            return () -> vertx.cancelTimer(id);
        };
    }

    @Override public String scheme() { return "http"; }

    @Override public Forms forms() { return FORMS; }

    /** A consumer whose every request is entered with {@code handler} as its handler, whoever its caller: for tests and tools. */
    @Override
    public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(handler, "handler");
        return new HttpConsumer(in, Entry.of(presented -> Future.succeededFuture(handler)));
    }

    /** {@link HttpIngress#READS}: the port reads every kind for its adapters. */
    @Override
    public Set<CredentialKind> reads() {
        return HttpIngress.READS;
    }

    /** The consumer the node builds: every request is entered through {@code entry} before its body is read. */
    @Override
    public Consumer consumer(Inbound in, Entry entry) {
        return new HttpConsumer(in, entry);
    }

    /** The producer of the connection {@code out} names; an {@link IllegalArgumentException} when it names none or no url. */
    @Override
    public Producer producer(Outbound out) {
        return new HttpProducer(connection(out));
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(running.isEmpty() ? Health.down("no consumer listening") : Health.up());
    }

    /** {@value #PREFIX}: every consumer of it takes requests on the node's HTTP port. */
    @Override
    public Optional<String> httpPrefix() {
        return Optional.of(PREFIX);
    }

    /**
     * The partner a producer of {@code out} calls: {@code scheme://host:port} of its connection's {@code url}, read as the
     * producer reads it — Vert.x {@link RequestOptions#setAbsoluteURI(String)}, i.e. {@code java.net.URL}, which takes a host
     * {@code java.net.URI} reads none from (an underscore) and an unencoded space or {@code |} — lower-cased, the scheme's
     * default port filled in (443 for https, else 80), no userinfo, path, query or fragment. A url the producer cannot send to
     * (a scheme other than http/https, none, a port that is no number) refuses the build with an
     * {@link IllegalArgumentException} naming the connection and its setting {@code url}, never the url, which may carry a
     * password, and with no cause; one with an empty host names no target.
     */
    @Override
    public Optional<String> target(Outbound out) {
        Connection c = connection(out);
        RequestOptions parsed = absolute(c);
        return Optional.of(parsed.getHost()).filter(host -> !host.isEmpty())
                .map(host -> (parsed.isSsl() ? "https" : "http") + "://" + host.toLowerCase(Locale.ROOT) + ":" + parsed.getPort());
    }

    // --- settings

    /** The connection {@code out} names, with a url; an {@link IllegalArgumentException} otherwise. */
    private static Connection connection(Outbound out) {
        Connection c = Objects.requireNonNull(out, "out").connection()
                .orElseThrow(() -> new IllegalArgumentException("an http producer sends through a connection, and none is named"));
        if (!(c.values().get("url") instanceof String)) throw new IllegalArgumentException("connection '" + c.key() + "': the setting 'url' is required");
        return c;
    }

    /** The request options of {@code c}'s url as it is; an {@link IllegalArgumentException} that never quotes it. */
    private static RequestOptions absolute(Connection c) {
        try {
            return new RequestOptions().setAbsoluteURI((String) c.values().get("url"));
        } catch (RuntimeException unparsable) {   // VertxException "Invalid url: <url>" or a bare IAE: both would leak or say nothing
            throw new IllegalArgumentException("connection '" + c.key() + "': the setting 'url' is no http(s) URL the producer can send to");
        }
    }

    static int intValue(Map<String, Object> values, String key, int fallback) {
        return values.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** The request's headers as the envelope's, names lower-cased; hop-by-hop ones left out. */
    static Headers headers(io.vertx.core.MultiMap raw) {
        Map<String, String> m = new HashMap<>();
        raw.forEach((k, v) -> { String name = k.toLowerCase(Locale.ROOT); if (!HOP_BY_HOP.contains(name)) m.put(name, v); });
        return new Headers(m);
    }

    /** {@code headers} with the {@link #PLATFORM_COOKIES} cut from {@code cookie}, the other cookies as written; a {@code cookie} left with none is removed. */
    static Headers withoutPlatformCookies(Headers headers) {
        String cookie = headers.get("cookie");
        if (cookie == null) return headers;
        List<String> pairs = List.of(cookie.split(";"));
        List<String> kept = pairs.stream().filter(pair -> !PLATFORM_COOKIES.contains(cookieName(pair))).toList();
        if (kept.size() == pairs.size()) return headers;
        Map<String, String> m = new HashMap<>(headers.asMap());
        String rest = String.join(";", kept).strip();
        if (rest.isEmpty()) m.remove("cookie");
        else m.put("cookie", rest);
        return new Headers(m);
    }

    /** Whether the reply header {@code name}: {@code value} sets one of the {@link #PLATFORM_COOKIES}. */
    static boolean setsAPlatformCookie(String name, String value) {
        return "set-cookie".equalsIgnoreCase(name) && PLATFORM_COOKIES.contains(cookieName(value));
    }

    /** The name of a cookie in a {@code cookie} pair or a {@code set-cookie} value: what comes before its first {@code =}, trimmed. */
    private static String cookieName(String cookie) {
        int eq = cookie.indexOf('=');
        return (eq < 0 ? cookie : cookie.substring(0, eq)).strip();
    }

    /**
     * The text a failed handler's answer carries, by its {@link #status}: the kind of fault, never the failure's own message,
     * which may name the flow's steps, its partners or its data.
     */
    static String failedText(int status) {
        return switch (status) {
            case 400 -> "the message cannot be read";
            case 422 -> "the message is invalid";
            case 502 -> "the receiver refused the message";
            case 503 -> "the receiver cannot be reached now";
            default -> "the flow failed";
        };
    }

    /**
     * The status the consumer answers its sender with, also {@code rail8 test}'s {@code expect.status}: a
     * handler that failed by its failure's {@link Fault.Kind} — {@code MALFORMED} 400, {@code INVALID} 422, {@code DOWNSTREAM}
     * 503 when the failure says the sender may try again ({@link NackException}, {@link HttpStatusException}) else 502,
     * {@code INTERNAL} and a failure that is no {@link Fault} 500; else its {@link Ack}: {@code Accepted} 202, a retryable
     * {@code Nack} 503, a final one 400, a {@code Reply} its {@link #STATUS} prop or 200.
     *
     * @param ack the handler's answer; read only when {@code failure} is null
     * @param failure what the handler failed with; null when it answered {@code ack}
     */
    public static int status(Ack ack, Throwable failure) {
        if (failure != null) {
            // a downstream failure says itself whether the sender may try again
            boolean retryable = failure instanceof NackException nack ? nack.retryable() : failure instanceof HttpStatusException partner && partner.retryable();
            return !(failure instanceof Fault fault) ? 500 : switch (fault.kind()) {
                case MALFORMED -> 400;
                case INVALID -> 422;
                case DOWNSTREAM -> retryable ? 503 : 502;
                case INTERNAL -> 500;
            };
        }
        return switch (ack) {
            case Ack.Accepted a -> 202;
            case Ack.Nack n -> n.retryable() ? 503 : 400;
            case Ack.Reply r -> r.reply().get(STATUS).orElse(200);
        };
    }

    // --- consumer

    /** @since 0.1 */
    public final class HttpConsumer implements Consumer {
        private final Inbound in;
        private final Entry entry;
        private final String flow;
        private final long maxBodyBytes;
        private volatile HttpIngress.Mount mount;

        HttpConsumer(Inbound in, Entry entry) {
            this.in = Objects.requireNonNull(in, "in");
            this.entry = Objects.requireNonNull(entry, "entry");
            this.flow = in.flow();
            this.maxBodyBytes = in.maxBodyBytes();
        }

        /** The URL of its mount on the node's own socket ({@link HttpIngress.Mount#url()}); empty before {@link #start()} succeeded and after {@link #stop()}. */
        public Optional<URI> url() {
            return Optional.ofNullable(mount).map(HttpIngress.Mount::url);
        }

        /** Mounts the sender on the node's HTTP port; fails as the mount fails (the path is held, no port is open). */
        @Override
        public Future<Void> start() {
            return http.mount(scheme(), in, this::handle).map(mounted -> { mount = mounted; running.put(this, Boolean.TRUE); return null; });
        }

        /**
         * Stops: health DOWN at once; a request whose body completes from now on is answered 503 + {@code retry-after}
         * without calling the handler; the mount closes, so no new request reaches the consumer, and the stop completes once the
         * requests it took have answered — the deliveries in flight answer their senders — or at the node's stop deadline.
         */
        @Override
        public Future<Void> stop() {
            HttpIngress.Mount m = mount;
            mount = null;
            running.remove(this);   // health DOWN, and a request whose body completes from now on is refused
            return m == null ? Future.succeededFuture() : m.close();
        }

        /** On the event loop, before any of the body is read: the request stays paused until the entry decides. */
        private void handle(HttpServerRequest req) {
            req.pause();
            Future<Function<Envelope, Future<Ack>>> entered;
            try {
                entered = entry.enter(http.presented(req));
            } catch (RuntimeException failed) {
                entered = Future.failedFuture(failed);
            }
            entered.onComplete(decided -> {
                if (decided.succeeded()) {
                    try {
                        take(req, decided.result());
                    } catch (RuntimeException thrown) {   // no port is left to answer for a callback: this does
                        LOG.log(System.Logger.Level.WARNING, "a request of flow " + flow + " failed before its body was stored", thrown);
                        if (!req.response().headWritten()) req.response().setStatusCode(500).putHeader("content-type", "text/plain").end(failedText(500));
                        else req.response().reset();
                    }
                } else if (decided.cause() instanceof Refused refused) {
                    http.refuse(req, refused);
                } else {
                    LOG.log(System.Logger.Level.WARNING, "the entry of flow " + flow + " failed", decided.cause());
                    req.response().setStatusCode(500).putHeader("content-type", "text/plain").end(failedText(500));
                }
            });
        }

        /** An entered request, still paused, with the handler of its one message: its body stored, then the flow run. */
        private void take(HttpServerRequest req, Function<Envelope, Future<Ack>> handler) {
            HttpServerResponse res = req.response();
            if (req.method() != HttpMethod.POST && req.method() != HttpMethod.PUT) {
                res.setStatusCode(405).putHeader("allow", "POST, PUT").end();
                req.resume();   // its body, if any, is read and dropped
                return;
            }
            String declared = req.getHeader("content-length");
            if (declared != null && Long.parseLong(declared) > maxBodyBytes) {
                res.setStatusCode(413).end("body above " + maxBodyBytes + " bytes");
                req.resume();
                return;
            }
            String id = ids.next();
            Context ctx = vertx.getOrCreateContext();
            Headers headers = withoutPlatformCookies(headers(req.headers()));
            String method = req.method().name();
            String path = req.path();
            // the request is still paused: nothing flows until the store attached its handlers (a store that attaches later resumes)
            blobs.write(BlobKey.body(id, 0), req, maxBodyBytes).onComplete(written -> {
                if (written.failed()) {
                    if (written.cause() instanceof BodyTooLargeException) res.setStatusCode(413).end("body above " + maxBodyBytes + " bytes");
                    else {
                        LOG.log(System.Logger.Level.WARNING, "message " + id + " of flow " + flow + ": its body could not be stored", written.cause());
                        res.setStatusCode(500).putHeader("content-type", "text/plain").end(NOT_STORED);
                    }
                    return;
                }
                if (!running.containsKey(this)) {   // stopping (or not yet started): never handed to the handler
                    res.setStatusCode(503).putHeader("retry-after", "1").end("consumer of flow " + flow + " is stopping");
                    return;
                }
                Envelope in = Envelope.ingress(id, flow, headers, written.result()).with(METHOD, method).with(PATH, path);
                // the handler runs the flow on the message's own virtual thread; the socket's event loop never does
                // a handler that throws (a checked one too, sneakily: an interrupted await) or answers null fails the answer — Future.future
                // catches either — and the request gets a 500, never a hang
                Thread.ofVirtual().name("rail8-http-" + handled.incrementAndGet()).start(() -> Future.<Ack>future(p -> handler.apply(in).onComplete(p))
                        .onComplete(done -> ctx.runOnContext(v -> answer(res, id, done.succeeded() ? done.result() : null, done.cause()))));
            });
        }

        private void answer(HttpServerResponse res, String id, Ack ack, Throwable failure) {
            int status = status(ack, failure);
            if (failure != null) {
                String what = "message " + id + " of flow " + flow + " answered " + status + ": " + failure;
                if (status < 500) LOG.log(System.Logger.Level.INFO, what);
                else LOG.log(System.Logger.Level.WARNING, what, failure);
                if (status == 503) res.putHeader("retry-after", "1");
                res.setStatusCode(status).putHeader(H_MESSAGE_ID, id).putHeader("content-type", "text/plain").end(failedText(status));
                return;
            }
            switch (ack) {
                case Ack.Accepted a -> res.setStatusCode(status).putHeader(H_MESSAGE_ID, a.messageId()).end();
                case Ack.Nack n -> {
                    if (n.retryable()) res.putHeader("retry-after", "1");
                    res.setStatusCode(status).end(n.reason());
                }
                case Ack.Reply r -> {
                    Envelope reply = r.reply();
                    res.setStatusCode(status);
                    reply.headers().asMap().forEach((k, v) -> { if (!HOP_BY_HOP.contains(k) && !setsAPlatformCookie(k, v)) res.putHeader(k, v); });
                    res.putHeader("content-length", String.valueOf(reply.body().size()));
                    reply.body().open().pipeTo(res);
                }
            }
        }
    }

    // --- producer

    /** @since 0.1 */
    public final class HttpProducer implements Producer {
        private final String name;
        private final String url;
        private final URI base;   // the url parsed once; null when java.net.URI reads no host from it: a call with a path fails
        private final long timeoutMs;
        private final HttpClientAgent client;
        private final AtomicInteger sends = new AtomicInteger();

        HttpProducer(Connection c) {
            this.name = c.key();
            this.url = (String) c.values().get("url");
            absolute(c);   // a url the client cannot send to refuses the build, as target() does
            this.base = parsed(url);
            this.timeoutMs = intValue(c.values(), "timeoutMs", 30_000);
            this.client = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(intValue(c.values(), "poolSize", 8)));
        }

        private static URI parsed(String url) {
            try {
                URI u = new URI(url);
                return u.getHost() == null ? null : u;
            } catch (URISyntaxException unparsable) {
                return null;
            }
        }

        /** Sends so far, each one a fresh request with the body re-opened. */
        public int sends() { return sends.get(); }

        @Override
        public Future<Envelope> send(Envelope e, Call call) {
            Objects.requireNonNull(e, "envelope");
            RequestOptions options;
            try {
                options = target(call).setTimeout(timeoutMs);
            } catch (PathRefused refused) {
                return Future.failedFuture(refused);
            }
            sends.incrementAndGet();
            return request(e, options, call.maxReplyBytes(), null)
                    .recover(failure -> Future.failedFuture(retryableIfTimedOut(failure)));
        }

        /**
         * {@link #send(Envelope, Call)} bounded by {@code d}: no time left — no request, not counted, a retryable Nack;
         * else the request's connect timeout is the earlier of what is left and {@code timeoutMs}, and a timer at what is left
         * re-reads {@code d}: time left again (renewed), it is re-armed; none, the send is cut — the request reset (one not yet
         * obtained is reset unsent), no reply stored once the cut fires while the reply is still streaming (a reply already
         * received whole may still be stored: a race), the send failed with the same retryable Nack as a {@code timeoutMs} one.
         * A timer that refuses the cut fails the send with its own exception, nothing sent, not counted. The timer is cancelled
         * once the send settles. A path refused fails the send before the deadline is read.
         */
        @Override
        public Future<Envelope> send(Envelope e, Call call, Deadline d) {
            Objects.requireNonNull(e, "envelope");
            RequestOptions options;
            try {
                options = target(call);
            } catch (PathRefused refused) {
                return Future.failedFuture(refused);
            }
            Duration left = d.remaining();
            if (!left.isPositive()) return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent to " + name, true));
            Cut cut = new Cut(d);
            try {
                cut.arm(left);   // before the request exists: a cut that comes first resets it unsent
            } catch (RuntimeException refused) {   // the timer refused it: a refusal is a failed future, nothing sent, not counted
                return Future.failedFuture(refused);
            }
            sends.incrementAndGet();
            long leftMs = Math.max(1, (left.toNanos() + 999_999) / 1_000_000);   // rounded up: Vert.x reads 0 as no connect timeout
            options.setConnectTimeout(Math.min(leftMs, timeoutMs)).setIdleTimeout(timeoutMs);
            Future<Envelope> sent = request(e, options, call.maxReplyBytes(), cut).recover(failure -> Future.failedFuture(retryableIfTimedOut(failure)));
            return cut.watch(sent);
        }

        /**
         * The POST of {@code call}: to {@code url} as it is without a {@code path}; with one, to the url with the path joined to
         * the url's path and its query kept, built from the parts of the one joined URI.
         *
         * @throws PathRefused when the path is not a path from {@code /}, holds a query or fragment, does not parse, or the
         *                     joined URI leaves the url's scheme, host, port or path
         */
        private RequestOptions target(Call call) {
            Object path = Objects.requireNonNull(call, "call").settings().get("path");
            RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST);
            if (path == null) return options.setAbsoluteURI(url);
            String who = "connection '" + name + "', flow '" + call.flow() + "', step '" + call.step() + "': ";
            if (!(path instanceof String p) || !p.startsWith("/") || p.indexOf('?') >= 0 || p.indexOf('#') >= 0) {
                throw new PathRefused(who + "the setting 'path' must be a path from /, without a query or fragment, was " + path);
            }
            if (base == null) throw new PathRefused(who + "the setting 'url' takes no path: it does not parse as a URI with a host");
            String scheme = base.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw new PathRefused(who + "the setting 'url' is no http(s) URL");
            String prefix = Optional.ofNullable(base.getRawPath()).orElse("");
            if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            URI joined;
            try {
                // %2e is an encoded dot: read as one, so a dot segment cannot pass encoded
                String dotted = p.replace("%2e", ".").replace("%2E", ".");
                joined = new URI(base.getScheme() + "://" + base.getRawAuthority() + prefix + dotted
                        + (base.getRawQuery() == null ? "" : "?" + base.getRawQuery())).normalize();
            } catch (URISyntaxException unparsable) {
                throw new PathRefused(who + "the setting 'path' does not parse joined to the url: " + path);
            }
            int port = port(base);
            if (!scheme.equalsIgnoreCase(joined.getScheme()) || !base.getHost().equalsIgnoreCase(String.valueOf(joined.getHost()))
                    || port != port(joined) || !joined.getRawPath().startsWith(prefix + "/")
                    || joined.getRawPath().equals("/..") || joined.getRawPath().startsWith("/../")) {   // a dot segment normalize() keeps above the root
                throw new PathRefused(who + "the setting 'path' leaves the url's path: " + path);
            }
            return options.setSsl(scheme.equals("https")).setHost(joined.getHost()).setPort(port)
                    .setURI(joined.getRawPath() + (joined.getRawQuery() == null ? "" : "?" + joined.getRawQuery()));
        }

        /** The port of {@code u}, its scheme's default (443 for https, else 80) when it names none. */
        private static int port(URI u) {
            return u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }

        /**
         * POSTs {@code e} under {@code options}, a reply body taken up to {@code maxReplyBytes}; with a {@code cut}, a request
         * obtained after the cut is reset unsent.
         */
        private Future<Envelope> request(Envelope e, RequestOptions options, long maxReplyBytes, Cut cut) {
            Envelope out = e.withOutboundId();   // the partner dedupes on it: exactly one x-message-id, the forwarded ones dropped
            return client.request(options).compose(req -> {
                if (cut != null && !cut.obtained(req)) return Future.failedFuture(cut.failure());   // cut before it was obtained: reset unsent
                out.headers().asMap().forEach((k, v) -> { if (!HOP_BY_HOP.contains(k)) req.putHeader(k, v); });
                req.putHeader("content-length", String.valueOf(e.body().size()));
                // Registered before the send, so it runs in the event-loop callback that delivers the response: nothing flows
                // until reply() attached its handlers (the store resumes a 2xx reply, head() any other), which runs a deferred
                // task later; otherwise a small reply could end unheard in between ("Response already ended") and be sent again by a retry.
                req.response().onSuccess(HttpClientResponse::pause);
                return req.send(e.body().open());
            }).compose(res -> reply(e, res, maxReplyBytes));   // a cut resets the request: a reply still streaming fails its store write
        }

        /** A {@code timeoutMs} (or connect timeout) expiry is the same retryable Nack as a cut send; any other failure as it is. */
        private Throwable retryableIfTimedOut(Throwable failure) {
            return failure instanceof TimeoutException
                    ? new NackException("HTTP request to " + name + " timed out: " + failure.getMessage(), true)
                    : failure;
        }

        /**
         * One bounded send's cut: a timer at what its deadline has left re-reads it — re-armed while time is left, else the send is
         * cut: the request reset (or, not yet obtained, reset when it is) and the send failed with a retryable Nack.
         */
        private final class Cut {
            private final Deadline d;
            private final io.vertx.core.Promise<Envelope> out = io.vertx.core.Promise.promise();
            private Timer.Cancellable armed;    // guarded by this
            private HttpClientRequest request;  // guarded by this: once obtained
            private boolean cut;                // guarded by this

            Cut(Deadline d) { this.d = d; }

            void arm(Duration left) {
                Timer.Cancellable next = timer.schedule(left, this::reread);
                synchronized (this) { armed = next; }
            }

            private void reread() {
                if (out.future().isComplete()) return;   // settled meanwhile: its cancel came too late for this one
                Duration left = d.remaining();
                if (left.isPositive()) {
                    arm(left);
                    return;
                }
                HttpClientRequest obtained;
                synchronized (this) {
                    cut = true;
                    obtained = request;
                }
                out.tryFail(failure());
                if (obtained != null) obtained.reset();
            }

            /** The request obtained: kept for a cut to reset; false — and reset at once — when the send was cut already. */
            boolean obtained(HttpClientRequest req) {
                synchronized (this) {
                    if (!cut) {
                        request = req;
                        return true;
                    }
                }
                req.reset();
                return false;
            }

            NackException failure() {
                return new NackException("deadline passed: the HTTP request to " + name + " was cut", true);
            }

            Future<Envelope> watch(Future<Envelope> sent) {
                sent.onComplete(settled -> {
                    Timer.Cancellable pending;
                    synchronized (this) { pending = armed; }
                    pending.cancel();
                    if (settled.succeeded()) out.tryComplete(settled.result());
                    else out.tryFail(settled.cause());
                });
                return out.future();
            }
        }

        private Future<Envelope> reply(Envelope e, HttpClientResponse res, long maxReplyBytes) {
            int status = res.statusCode();
            if (status / 100 == 2) {
                Headers headers = headers(res.headers());
                return blobs.write(e.nextKey(), res, maxReplyBytes)
                        .map(body -> new Envelope(e.id(), e.flow(), e.step() + 1, headers, e.props().with(STATUS, status), e.keys(), body, e.attachments(), new dev.rail8.api.Views()));
            }
            return head(res).compose(reason -> {
                if (status == 503 || status == 429) return Future.failedFuture(new NackException("HTTP " + status + " from " + name + ": " + reason, true));
                return Future.failedFuture(new HttpStatusException(status, reason));
            });
        }

        /** The first {@link #ERROR_BODY_BYTES} of the response body as text; the rest is drained, never buffered (a partner's error page is not a heap allocation). */
        private static Future<String> head(HttpClientResponse res) {
            io.vertx.core.Promise<String> done = io.vertx.core.Promise.promise();
            Buffer kept = Buffer.buffer();
            res.exceptionHandler(x -> done.tryComplete(kept.toString()));
            res.endHandler(v -> done.tryComplete(kept.toString()));
            res.handler(chunk -> {
                int room = ERROR_BODY_BYTES - kept.length();
                if (room > 0) kept.appendBuffer(chunk, 0, Math.min(room, chunk.length()));
            });
            res.resume();
            return done.future();
        }

        @Override
        public Future<Void> close() { return client.close(); }
    }

    /** A send whose path the producer refuses: the flow's settings broke a rule, and the same send fails again. */
    static final class PathRefused extends IllegalArgumentException implements Fault {
        PathRefused(String message) {
            super(message);
        }

        @Override public Kind kind() { return Kind.INVALID; }
    }
}
