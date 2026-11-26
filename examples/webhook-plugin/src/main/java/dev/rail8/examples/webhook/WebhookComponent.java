package dev.rail8.examples.webhook;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.CredentialKind;
import dev.rail8.api.Deadline;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Inbound;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Refused;
import dev.rail8.api.Timer;
import dev.rail8.api.Views;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The {@code webhook} component: a consumer that takes HTTP POSTs on the node's HTTP port and a producer that POSTs to a URL,
 * over the Vert.x HTTP client.
 *
 * <p><b>Consumer</b> (the flow's setting {@code address}, required, the path under the prefix {@value #PREFIX} that senders
 * call, see {@link WebhookConsumer#url()}; no connection). Its start mounts the sender on the node's HTTP port, its stop closes
 * the mount. A request at the sender's path is paused and entered first, with what its caller presented as the port reads it
 * ({@link HttpIngress#presented}), before any byte of its body is read: a refused caller gets the refusal's answer and nothing
 * is stored. An entered POST is streamed into the blob store, refused with 413 above the flow's
 * {@link Inbound#maxBodyBytes}, and handed as a new message to the handler the entry gave, on a virtual thread of its own. The handler's
 * {@link Ack} is the answer: {@code Accepted} 202 with the message id in {@code x-message-id}, {@code Reply} 200 with the reply's
 * headers and body, a retryable {@code Nack} 503 and a final one 400, both with the reason as the body. A handler that fails is
 * 500 with its message. Another method is 405. Once {@link Consumer#stop()} began, a request is 503 and the handler is not called.
 *
 * <p><b>Producer</b> (one per connection: its {@code url}, required, with no user info; {@code timeoutMs}, default 30 000;
 * each send's {@link Call#maxReplyBytes}, the most a reply may hold). A send POSTs the envelope's body, streamed from a fresh {@code open()}, with its headers
 * and its message id as {@code x-message-id}. A 2xx response is the reply: its headers, and its body stored as the next step's.
 * Any other status fails the send with a {@link NackException} whose reason holds the first {@value #REASON_BYTES} bytes of the
 * response body: retryable for 429 and 5xx, final otherwise. A send that times out fails retryable, and so does a bounded send
 * still unanswered when its {@link Deadline} runs out: the request is reset.
 *
 * <p><b>What is HTTP here.</b> The prefix and the settings {@code address}, {@code url} and {@code timeoutMs}, the mount on the
 * node's HTTP port in {@link WebhookConsumer}, the client in {@link WebhookProducer}, and the statuses they answer and read are
 * this component's protocol; the parts marked {@code HTTP:} below are where an adapter for another protocol puts its own. An
 * adapter taking requests over HTTP keeps the prefix, its own, and the mount; one for a protocol that is not HTTP declares no
 * prefix and opens its listener or connection itself. What every adapter keeps: each message entered before any byte of it is
 * stored, the body streamed into and out of the blob store, one call of the handler its entry gave, on a virtual thread of
 * its own, per message, the answer taken only from the handler's {@link Ack}, the
 * message id sent on, the cut at the deadline on the {@link Timer}, and a stop that waits for the deliveries in flight.
 */
public final class WebhookComponent implements Component {

    /** The scheme this component serves. */
    public static final String SCHEME = "webhook";

    /** Its address on the node's HTTP port: an in endpoint is served at this prefix followed by its {@code address}. */
    public static final String PREFIX = "/webhook";

    /** A URL without user info: no {@code @} between {@code //} and the path, query or fragment. */
    static final String NO_USER_INFO = "[A-Za-z][A-Za-z0-9+.-]*://[^/?#@]+([/?#].*)?";

    /**
     * The two forms: the node checks every use against them, and the UI draws its forms from them. The connection says where
     * the partner is; the flow says where senders call. A sender reaches no connection, a send always does. The flow's name
     * and the body limits are the node's and are no field here.
     */
    static final Forms FORMS = new Forms(
            Settings.of(
                    Field.text("url", "URL", "The URL each message is posted to, such as https://partner.example/hook. "
                            + "No user name or password in it.").required().pattern(NO_USER_INFO).out().server(),
                    Field.integer("timeoutMs", "Timeout (ms)", "How long a request may take, in milliseconds.").min(1).byDefault(30_000).out()),
            Settings.of(HttpIngress.ADDRESS),
            Map.of(Direction.IN, Forms.Reach.NO_CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));

    /** Headers that describe one connection, never copied from a message to a request or a response, or back. */
    private static final Set<String> HOP_BY_HOP =
            Set.of("content-length", "transfer-encoding", "connection", "host", "keep-alive", "upgrade", "expect", "te", "trailer");
    /** How much of a refusal's response body the send's failure names. */
    static final int REASON_BYTES = 512;

    private final Vertx vertx;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final HttpIngress http;
    private final Set<WebhookConsumer> running = ConcurrentHashMap.newKeySet();

    /**
     * A component on {@code vertx}; it stores bodies in {@code blobs}, names messages from {@code ids}, cuts sends on {@code timer}
     * and mounts its consumers on {@code http}, the node's HTTP port.
     */
    public WebhookComponent(Vertx vertx, BlobStore blobs, IdGenerator ids, Timer timer, HttpIngress http) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override public String scheme() { return SCHEME; }

    @Override public Optional<String> httpPrefix() { return Optional.of(PREFIX); }

    @Override public Forms forms() { return FORMS; }

    /** A consumer whose every request reaches {@code handler}, whoever its caller: for tests and tools. */
    @Override
    public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(handler, "handler");
        return new WebhookConsumer(in, Entry.of(presented -> Future.succeededFuture(handler)));
    }

    // HTTP: the credentials the node's HTTP port reads; an adapter with a listener of its own names those it reads there.
    @Override
    public Set<CredentialKind> reads() {
        return HttpIngress.READS;
    }

    /** The consumer the node builds: every request is entered through {@code entry} before any byte of its body is read. */
    @Override
    public Consumer consumer(Inbound in, Entry entry) {
        return new WebhookConsumer(in, entry);
    }

    @Override
    public Producer producer(Outbound out) {
        return new WebhookProducer(connection(out));
    }

    /** Up while a consumer is mounted. */
    @Override
    public Future<Health> health() {
        return Future.succeededFuture(running.isEmpty() ? Health.down("no consumer mounted") : Health.up());
    }

    /**
     * The partner a producer of {@code out} calls, {@code http://host:port} of its connection's {@code url}, so that the node
     * limits the sends to one partner together; empty when the url names no host.
     */
    @Override
    public Optional<String> target(Outbound out) {
        URI uri = URI.create(text(connection(out), "url", null));
        if (uri.getHost() == null || uri.getScheme() == null) return Optional.empty();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
    }

    /** The connection {@code out} names; refused when it names none. */
    private static Connection connection(Outbound out) {
        return Objects.requireNonNull(out, "out").connection()
                .orElseThrow(() -> new IllegalArgumentException("a webhook producer posts through a connection, and none is named"));
    }

    /** The connection's value {@code key} as text; {@code fallback} when it is absent, refused when it is absent and {@code fallback} is null. */
    private static String text(Connection c, String key, String fallback) {
        Object value = c.values().get(key);
        if (value == null && fallback == null) throw new IllegalArgumentException("connection '" + c.key() + "': the setting '" + key + "' is required");
        return value == null ? fallback : String.valueOf(value);
    }

    /** The connection's value {@code key} as a number; {@code fallback} when it is absent. */
    private static long number(Connection c, String key, long fallback) {
        Object value = c.values().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value == null) return fallback;
        throw new IllegalArgumentException("connection '" + c.key() + "': the setting '" + key + "' must be a number, was " + value);
    }

    /** {@code raw} as a message's headers: names in lower case, the hop-by-hop ones left out. */
    private static Headers headers(MultiMap raw) {
        Map<String, String> kept = new HashMap<>();
        raw.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(lower)) kept.put(lower, value);
        });
        return new Headers(kept);
    }

    /** A consumer: its sender mounted on the node's HTTP port. */
    public final class WebhookConsumer implements Consumer {
        private final Inbound in;
        private final Entry entry;
        private final String flow;
        private final long maxBodyBytes;
        private volatile HttpIngress.Mount mount;

        WebhookConsumer(Inbound in, Entry entry) {
            this.in = Objects.requireNonNull(in, "in");
            this.entry = Objects.requireNonNull(entry, "entry");
            this.flow = in.flow();
            this.maxBodyBytes = in.maxBodyBytes();
        }

        /** The URL senders call, on the node's HTTP port; empty while it is not started. */
        public Optional<URI> url() {
            return Optional.ofNullable(mount).map(HttpIngress.Mount::url);
        }

        @Override
        public Future<Void> start() {
            // HTTP: the sender mounted on the node's HTTP port; another protocol opens its listener or connection here.
            return http.mount(SCHEME, in, this::handle).map(mounted -> {
                mount = mounted;
                running.add(this);
                return null;
            });
        }

        /**
         * Takes no new message from now on, and completes once the requests in flight were answered (or at the node's stop
         * deadline, when the node resets them).
         */
        @Override
        public Future<Void> stop() {
            HttpIngress.Mount m = mount;
            mount = null;
            running.remove(this);
            return m == null ? Future.succeededFuture() : m.close();
        }

        // HTTP: one request is one message. On the event loop, before any byte of its body is read, it is paused and entered with
        // what its caller presented; the entry decides, then its body goes into the blob store and its headers become the message's.
        private void handle(HttpServerRequest req) {
            req.pause();   // nothing flows until the entry decided and the blob store reads the body
            entry.enter(http.presented(req)).onComplete(entered -> {
                if (entered.succeeded()) take(req, entered.result());
                else if (entered.cause() instanceof Refused refused) http.refuse(req, refused);   // HTTP: another protocol answers in its own, from refused.reason()
                else reason(req.response(), 500, "the entry failed");
            });
        }

        /** An entered request, still paused, with the handler of its one message. */
        private void take(HttpServerRequest req, Function<Envelope, Future<Ack>> handler) {
            HttpServerResponse res = req.response();
            if (req.method() != HttpMethod.POST) {
                res.setStatusCode(405).putHeader("allow", "POST").end();
                req.resume();   // its body, if any, is read and dropped
                return;
            }
            String id = ids.next();
            Headers headers = headers(req.headers());
            Context context = vertx.getOrCreateContext();
            blobs.write(BlobKey.body(id, 0), req, maxBodyBytes).onComplete(written -> {
                if (written.failed()) {
                    reason(res, written.cause() instanceof BodyTooLargeException ? 413 : 500, String.valueOf(written.cause().getMessage()));
                    return;
                }
                if (!running.contains(this)) {
                    reason(res, 503, "the consumer of flow " + flow + " is stopping");
                    return;
                }
                Envelope in = Envelope.ingress(id, flow, headers, written.result());
                // The flow runs on a virtual thread of its own, never on the server's event loop. Future.future catches a
                // handler that throws or answers null: the request is answered 500.
                Thread.ofVirtual().name("webhook-" + flow).start(() -> Future.<Ack>future(answer -> handler.apply(in).onComplete(answer))
                        .onComplete(done -> context.runOnContext(v -> answer(res, done))));
            });
        }

        // HTTP: the handler's Ack as a status; another protocol answers its sender in its own terms, from the Ack alone.
        private void answer(HttpServerResponse res, AsyncResult<Ack> done) {
            if (done.failed()) {
                reason(res, 500, String.valueOf(done.cause().getMessage()));
                return;
            }
            switch (done.result()) {
                case Ack.Accepted accepted -> res.setStatusCode(202).putHeader(Envelope.H_MESSAGE_ID, accepted.messageId()).end();
                case Ack.Nack nack -> reason(res, nack.retryable() ? 503 : 400, nack.reason());
                case Ack.Reply reply -> {
                    Envelope out = reply.reply();
                    res.setStatusCode(200);
                    out.headers().asMap().forEach((name, value) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) res.putHeader(name, value);
                    });
                    res.putHeader("content-length", String.valueOf(out.body().size()));
                    out.body().open().pipeTo(res);
                }
            }
        }

        private static void reason(HttpServerResponse res, int status, String reason) {
            res.setStatusCode(status).putHeader("content-type", "text/plain; charset=utf-8").end(reason);
        }
    }

    /** A producer of one connection, shared by every flow that sends through it: an HTTP client with a connection pool of its own. */
    public final class WebhookProducer implements Producer {
        private final String name;
        private final String url;
        private final long timeoutMs;
        private final HttpClientAgent client;

        WebhookProducer(Connection c) {
            this.name = c.key();
            this.url = text(c, "url", null);
            this.timeoutMs = number(c, "timeoutMs", 30_000L);
            this.client = vertx.createHttpClient();
        }

        @Override
        public Future<Envelope> send(Envelope e, Call call) {
            return post(e, call.maxReplyBytes(), null);
        }

        /**
         * {@link #send(Envelope, Call)}, cut when {@code d} runs out: with no time left nothing is sent; else a timer at what is left
         * reads {@code d} again, waits on while time is left (the deadline was renewed) and otherwise fails the send and resets
         * its request.
         */
        @Override
        public Future<Envelope> send(Envelope e, Call call, Deadline d) {
            Duration left = d.remaining();
            if (!left.isPositive()) return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent to " + name, true));
            Cut cut = new Cut(d);
            cut.arm(left);
            post(e, call.maxReplyBytes(), cut).onComplete(done -> {
                cut.armed.cancel();
                if (done.succeeded()) cut.out.tryComplete(done.result());
                else cut.out.tryFail(done.cause());
            });
            return cut.out.future();
        }

        // HTTP: one POST per send, the body streamed from a fresh open(); another protocol writes to its partner here.
        private Future<Envelope> post(Envelope e, long maxReplyBytes, Cut cut) {
            Envelope out = e.withOutboundId();
            RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(url)
                    .setConnectTimeout(timeoutMs).setIdleTimeout(timeoutMs);
            return client.request(options).compose(req -> {
                if (cut != null && !cut.keep(req)) return Future.<HttpClientResponse>failedFuture(cut.failure());
                out.headers().asMap().forEach((header, value) -> {
                    if (!HOP_BY_HOP.contains(header.toLowerCase(Locale.ROOT))) req.putHeader(header, value);
                });
                req.putHeader("content-length", String.valueOf(e.body().size()));
                // Paused as it arrives, so that no byte of the response is lost before reply() reads it.
                req.response().onSuccess(HttpClientResponse::pause);
                return req.send(e.body().open());
            }).compose(res -> reply(e, res, maxReplyBytes)).recover(failure -> Future.failedFuture(failure instanceof TimeoutException
                    ? new NackException("the request to " + name + " timed out: " + failure.getMessage(), true)
                    : failure));
        }

        // HTTP: a 2xx response is the reply, any other status a refusal, retryable or for good.
        private Future<Envelope> reply(Envelope e, HttpClientResponse res, long maxReplyBytes) {
            int status = res.statusCode();
            if (status / 100 == 2) {
                Headers headers = headers(res.headers());
                return blobs.write(e.nextKey(), res, maxReplyBytes)
                        .map(body -> new Envelope(e.id(), e.flow(), e.step() + 1, headers, e.props(), e.keys(), body, e.attachments(), new Views()));
            }
            boolean retryable = status == 429 || status / 100 == 5;
            return head(res).compose(reason -> Future.failedFuture(new NackException("HTTP " + status + " from " + name + ": " + reason, retryable)));
        }

        /** The first {@value #REASON_BYTES} bytes of the response body as text; the rest is read and dropped. */
        private static Future<String> head(HttpClientResponse res) {
            Promise<String> done = Promise.promise();
            Buffer kept = Buffer.buffer();
            res.exceptionHandler(failure -> done.tryComplete(kept.toString()));
            res.endHandler(v -> done.tryComplete(kept.toString()));
            res.handler(chunk -> {
                int room = REASON_BYTES - kept.length();
                if (room > 0) kept.appendBuffer(chunk, 0, Math.min(room, chunk.length()));
            });
            res.resume();
            return done.future();
        }

        @Override
        public Future<Void> close() {
            return client.close();
        }

        /** The cut of one bounded send: its outcome, the timer armed for it, and its request once there is one. */
        private final class Cut {
            private final Deadline deadline;
            private final Promise<Envelope> out = Promise.promise();
            private volatile Timer.Cancellable armed;
            private volatile HttpClientRequest request;

            Cut(Deadline deadline) {
                this.deadline = deadline;
            }

            void arm(Duration left) {
                armed = timer.schedule(left, this::fire);
            }

            private void fire() {
                if (out.future().isComplete()) return;
                Duration left = deadline.remaining();
                if (left.isPositive()) {
                    arm(left);
                    return;
                }
                if (out.tryFail(failure())) {
                    HttpClientRequest sent = request;
                    if (sent != null) sent.reset();
                }
            }

            /** Keeps {@code req} for a later cut to reset; false, and {@code req} reset, when the send was cut already. */
            boolean keep(HttpClientRequest req) {
                request = req;
                if (!out.future().isComplete()) return true;
                req.reset();
                return false;
            }

            NackException failure() {
                return new NackException("deadline passed: the request to " + name + " was cut", true);
            }
        }
    }
}
