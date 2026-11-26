package dev.rail8.components.http;

import dev.rail8.api.Ack;
import dev.rail8.api.Consumer;
import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.NackException;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP mapping (ARCHITECTURE §4): statuses per Ack and failure, limits never defaulted, the body streamed both ways. */
@Timeout(60)
class HttpComponentTest {

    static Vertx vertx;
    static SharedHttpPort port;
    static HttpClientAgent raw;
    static Context rawContext;
    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids(), onVertx(), port);
    final List<Consumer> started = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    /** A call without a path, replies up to 4096 bytes. */
    static final Call CALL = Uses.call(4096);

    /** A timer on Vert.x timers: the producers' bounded sends are cut in real time, as with the three-argument constructor. */
    static dev.rail8.api.Timer onVertx() {
        return (delay, task) -> {
            long id = vertx.setTimer(Math.max(1, delay.toMillis()), fired -> task.run());
            return () -> vertx.cancelTimer(id);
        };
    }

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        port = SharedHttpPort.loopback(vertx, Map.of("http", HttpComponent.PREFIX)).await();
        raw = vertx.createHttpClient();
        rawContext = vertx.getOrCreateContext();
    }

    @AfterAll
    static void stop() {
        raw.close().await();
        port.close().await();
        vertx.close().await();
    }

    @AfterEach
    void down() {
        for (Consumer c : started) c.stop().await();
        for (HttpServer s : servers) s.close().await();
    }

    HttpComponent.HttpConsumer listen(Function<Envelope, Future<Ack>> handler, long maxBodyBytes) {
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) http.consumer(Uses.in("invoice-sync", "/in", maxBodyBytes), handler);
        c.start().await();
        started.add(c);
        return c;
    }

    record Answer(int status, io.vertx.core.MultiMap headers, Buffer body) {}

    /**
     * Sends one request of the test's client, {@code send} writing it, and gives the answer. Every request starts on one Vert.x
     * context: a request started on a thread outside Vert.x gets a context of its own, and when its pooled connection's events
     * arrive on another event loop the response's body can be dropped before the body handler is set, so the answer would come
     * back with an empty body.
     */
    static Future<Answer> exchange(RequestOptions options, Function<HttpClientRequest, Future<HttpClientResponse>> send) {
        Promise<Answer> answer = Promise.promise();
        rawContext.runOnContext(v -> raw.request(options).compose(send)
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b)))
                .onComplete(answer));
        return answer.future();
    }

    /** The port the consumer's mount is on; -1 when it has none. */
    static int portOf(HttpComponent.HttpConsumer c) {
        return c.url().map(URI::getPort).orElse(-1);
    }

    Answer post(int port, String path, Buffer body, Map<String, String> headers) {
        return exchange(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + path), req -> {
            headers.forEach(req::putHeader);
            return req.send(body);
        }).await();
    }

    @Test
    void aReplyIsA200WithTheReplysHeadersAndBodyAndTheHandlerRunsOffTheEventLoop() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        List<Boolean> onVirtual = new CopyOnWriteArrayList<>();
        HttpComponent.HttpConsumer c = listen(e -> {
            seen.add(e);
            onVirtual.add(Thread.currentThread().isVirtual());
            return Future.succeededFuture(new Ack.Reply(e.next(rt.store(e, Buffer.buffer("<Ack/>"))).withHeader("content-type", "application/xml").withHeader("x-flow", e.flow())));
        }, 4096);
        assertEquals(Optional.of(URI.create("http://127.0.0.1:" + port.port() + "/http/in")), c.url(), "mounted on the shared port under /http");
        Answer a = post(portOf(c), "/http/in", Buffer.buffer("<Invoice/>"), Map.of("Content-Type", "application/xml", "X-Request-Id", "r-1"));
        assertEquals(200, a.status());
        assertEquals("<Ack/>", a.body().toString());
        assertEquals("application/xml", a.headers().get("content-type"));
        assertEquals("invoice-sync", a.headers().get("x-flow"));
        assertEquals(1, seen.size());
        Envelope in = seen.get(0);
        assertEquals("invoice-sync", in.flow());
        assertEquals(0, in.step());
        assertEquals("<Invoice/>", in.body().whole().toString());
        assertEquals("r-1", in.headers().get("x-request-id"), "header names arrive lower-cased");
        assertEquals("application/xml", in.headers().get("content-type"));
        assertEquals(Optional.of("POST"), in.get(HttpComponent.METHOD));
        assertEquals(Optional.of("/http/in"), in.get(HttpComponent.PATH), "the request's whole path");
        assertEquals(List.of(true), onVirtual, "the handler runs on the message's virtual thread, never on the socket's event loop");
        assertEquals(Health.Status.UP, http.health().await().status());
    }

    @Test
    void acceptedIs202NacksAre503Or400AndAFailedHandlerIs500WithAFixedTextAndItsReasonInTheLog() {
        HttpComponent.HttpConsumer c = listen(e -> switch (e.headers().get("x-case")) {
            case "accept" -> Future.succeededFuture(new Ack.Accepted(e.id()));
            case "busy" -> Future.succeededFuture(new Ack.Nack("admission: 3/2 requests", true));
            case "bad" -> Future.succeededFuture(new Ack.Nack("no invoice id", false));
            case "throw" -> throw new IllegalStateException("validation failed at step 'validate'");
            default -> Future.failedFuture(new IllegalStateException("partner down"));
        }, 4096);
        Answer accepted = post(portOf(c), "/http/in", Buffer.buffer("x"), Map.of("x-case", "accept"));
        assertEquals(202, accepted.status());
        assertTrue(accepted.headers().get(HttpComponent.H_MESSAGE_ID).startsWith("m-"), "the message id travels back: " + accepted.headers().get(HttpComponent.H_MESSAGE_ID));
        Answer busy = post(portOf(c), "/http/in", Buffer.buffer("x"), Map.of("x-case", "busy"));
        assertEquals(503, busy.status());
        assertEquals("1", busy.headers().get("retry-after"));
        assertTrue(busy.body().toString().contains("admission"));
        Answer bad = post(portOf(c), "/http/in", Buffer.buffer("x"), Map.of("x-case", "bad"));
        assertEquals(400, bad.status());
        assertEquals("no invoice id", bad.body().toString());
        try (Logged logged = new Logged()) {
            Answer thrown = post(portOf(c), "/http/in", Buffer.buffer("x"), Map.of("x-case", "throw"));
            assertEquals(500, thrown.status());
            assertEquals("the flow failed", thrown.body().toString());
            assertTrue(logged.holds("validation failed at step 'validate'"), "the reason is in the log: " + logged.lines());
            Answer failed = post(portOf(c), "/http/in", Buffer.buffer("x"), Map.of("x-case", "fail"));
            assertEquals(500, failed.status());
            assertEquals("the flow failed", failed.body().toString());
            assertTrue(logged.holds("message " + failed.headers().get(HttpComponent.H_MESSAGE_ID) + " of flow invoice-sync answered 500: java.lang.IllegalStateException: partner down"),
                    "the log names the message, its flow and the failure: " + logged.lines());
        }
    }

    /** A store whose streaming writes fail: the disk under it is full. */
    static final class FullStore implements dev.rail8.api.BlobStore {
        final dev.rail8.api.BlobStore inner;
        FullStore(dev.rail8.api.BlobStore inner) { this.inner = inner; }
        @Override public long treeLimit() { return inner.treeLimit(); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, io.vertx.core.streams.ReadStream<Buffer> in, long maxSize) {
            in.resume();
            return Future.failedFuture(new java.io.IOException("no space left on /var/lib/rail8/blobs"));
        }
        @Override public io.vertx.core.streams.ReadStream<Buffer> open(dev.rail8.api.BlobKey key) { return inner.open(key); }
        @Override public Future<Buffer> read(dev.rail8.api.BlobKey key) { return inner.read(key); }
        @Override public Future<Void> persist(dev.rail8.api.BlobKey key) { return inner.persist(key); }
    }

    @Test
    void aBodyTheStoreCannotTakeIsA500WithAFixedTextAndTheStoresFailureInTheLog() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        HttpComponent full = new HttpComponent(vertx, new FullStore(rt.blobs()), rt.ids(), onVertx(), port);
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) full.consumer(Uses.in("invoice-sync", "/in", 4096), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        c.start().await();
        started.add(c);
        try (Logged logged = new Logged()) {
            Answer a = post(portOf(c), "/http/in", Buffer.buffer("<Invoice/>"), Map.of());
            assertEquals(500, a.status());
            assertEquals("the message could not be stored", a.body().toString(), "a fixed text, never the store's failure");
            assertEquals("text/plain", a.headers().get("content-type"));
            assertTrue(logged.lines().stream().anyMatch(line -> line.startsWith("WARNING message m-") && line.contains("of flow invoice-sync: its body could not be stored")
                    && line.contains("no space left on /var/lib/rail8/blobs")), "the store's failure is in the log: " + logged.lines());
        }
        assertEquals(List.of(), seen, "nothing reached the handler");
    }

    /**
     * The four cookies of the node's UI never reach a flow: they are cut from the request's {@code cookie}, so neither the
     * envelope nor the partner the flow sends it to sees them, and a reply that sets one of them reaches the caller without
     * that {@code set-cookie}. Any other cookie passes both ways.
     */
    @Test
    void thePlatformsCookiesAreCutFromTheRequestAndFromTheReplyOtherCookiesPass() {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            partnerSaw.add(String.valueOf(req.getHeader("cookie")));
            req.response().putHeader("set-cookie", req.getHeader("x-set")).end("<PartnerAck/>");
        }); return null; });
        Producer toPartner = producerTo(partner, "/in", Map.of());
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        HttpComponent.HttpConsumer c = listen(e -> {
            seen.add(e);
            if (e.header("x-direct").isPresent()) return Future.succeededFuture(new Ack.Reply(e.withHeader("Set-Cookie", e.headers().get("x-set"))));
            return Future.succeededFuture(new Ack.Reply(toPartner.send(e, CALL).await()));
        }, 4096);
        String cookies = "rail8-session=s1; __Host-rail8-session=s2; rail8-device=d1; theme=dark; __Host-rail8-device=d2";
        for (String name : List.of("rail8-session", "__Host-rail8-session", "rail8-device", "__Host-rail8-device", "theme")) {
            String set = name + "=v; Path=/; HttpOnly";
            for (Map<String, String> how : List.<Map<String, String>>of(Map.of(), Map.of("x-direct", "1"))) {
                Map<String, String> headers = new java.util.HashMap<>(how);
                headers.putAll(Map.of("Cookie", cookies, "x-set", set));
                Answer a = post(portOf(c), "/http/in", Buffer.buffer("<Invoice/>"), headers);
                assertEquals(200, a.status(), name + " " + how + ": " + a.body());
                assertEquals("theme=dark", seen.getLast().headers().get("cookie"), "the envelope holds only the other cookie");
                assertEquals(name.equals("theme") ? List.of(set) : List.of(), a.headers().getAll("set-cookie"),
                        name + " " + how + ": a reply never sets one of the platform's cookies, and sets any other");
            }
        }
        assertEquals(java.util.Collections.nCopies(5, "theme=dark"), partnerSaw, "the partner saw only the other cookie");
        post(portOf(c), "/http/in", Buffer.buffer("<Invoice/>"), Map.of("Cookie", "__Host-rail8-session=s2;rail8-device=d1", "x-direct", "1", "x-set", "theme=v"));
        assertFalse(seen.getLast().headers().asMap().containsKey("cookie"), "a cookie with only the platform's is removed: " + seen.getLast().headers());
        toPartner.close().await();
    }

    /** The store as the http consumer sees it, counting the streaming writes it was asked for. */
    static final class CountingStore implements dev.rail8.api.BlobStore {
        final dev.rail8.api.BlobStore inner; final java.util.concurrent.atomic.AtomicInteger streamingWrites = new java.util.concurrent.atomic.AtomicInteger();
        CountingStore(dev.rail8.api.BlobStore inner) { this.inner = inner; }
        @Override public long treeLimit() { return inner.treeLimit(); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, io.vertx.core.streams.ReadStream<Buffer> in, long maxSize) { streamingWrites.incrementAndGet(); return inner.write(key, in, maxSize); }
        @Override public io.vertx.core.streams.ReadStream<Buffer> open(dev.rail8.api.BlobKey key) { return inner.open(key); }
        @Override public Future<Buffer> read(dev.rail8.api.BlobKey key) { return inner.read(key); }
        @Override public Future<Void> persist(dev.rail8.api.BlobKey key) { return inner.persist(key); }
    }

    @Test
    void theBodyLimitIs413WrongPathIs404WrongMethodIs405() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        CountingStore store = new CountingStore(rt.blobs());
        HttpComponent counted = new HttpComponent(vertx, store, rt.ids(), onVertx(), port);
        HttpComponent.HttpConsumer c = (HttpComponent.HttpConsumer) counted.consumer(Uses.in("invoice-sync", "/in", 16), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        c.start().await();
        started.add(c);
        Answer declared = post(portOf(c), "/http/in", Buffer.buffer("x".repeat(17)), Map.of());
        assertEquals(413, declared.status(), "declared above the limit: refused before a byte is stored");
        assertEquals(0, store.streamingWrites.get(), "the store was never asked: the declared length is refused at the door");
        Answer chunked = exchange(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + portOf(c) + "/http/in"),
                req -> { req.setChunked(true); return req.send(Buffer.buffer("y".repeat(40))); }).await();
        assertEquals(413, chunked.status(), "undeclared length: the store stops at the limit");
        assertEquals(1, store.streamingWrites.get(), "undeclared: the store is asked and refuses at the limit");
        assertEquals(202, post(portOf(c), "/http/in", Buffer.buffer("x".repeat(16)), Map.of()).status(), "at the limit is fine");
        assertEquals(404, post(portOf(c), "/http/elsewhere", Buffer.buffer("x"), Map.of()).status(), "the port answers a path no endpoint serves");
        Answer get = exchange(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + portOf(c) + "/http/in"), HttpClientRequest::send).await();
        assertEquals(405, get.status());
        assertEquals("POST, PUT", get.headers().get("allow"));
        assertEquals(1, seen.size(), "only the body within the limit reached the handler");
        assertFalse(rt.blobs().contains(dev.rail8.api.BlobKey.body(seen.get(0).id(), 1)), "nothing else was stored under that message");
    }

    @Test
    void aProducerNeedsAConnectionWithAUrlAndAStoppedConsumerFreesItsPath() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> http.producer(new dev.rail8.api.Outbound(Optional.empty()))).getMessage().contains("connection"));
        assertEquals("connection 'partner': the setting 'url' is required",
                assertThrows(IllegalArgumentException.class, () -> http.producer(Uses.out(Map.of("timeoutMs", 100)))).getMessage());
        HttpComponent.HttpConsumer c = listen(e -> Future.succeededFuture(new Ack.Accepted(e.id())), 4096);
        c.stop().await();
        started.remove(c);
        assertEquals(Optional.empty(), c.url());
        assertEquals(Health.Status.DOWN, http.health().await().status());
        assertEquals(404, post(port.port(), "/http/in", Buffer.buffer("x"), Map.of()).status(), "nothing serves the path any more");
        HttpComponent.HttpConsumer again = listen(e -> Future.succeededFuture(new Ack.Accepted(e.id())), 4096);
        assertEquals(202, post(portOf(again), "/http/in", Buffer.buffer("x"), Map.of()).status(), "the path is free for the next consumer at once");
    }

    @Test
    void itsPrefixIsHttpItListensOnNoPortOfItsOwnAndItsAddressIsAnInOption() {
        assertEquals(Optional.of("/http"), http.httpPrefix());
        assertEquals(Optional.empty(), http.listensOn(Uses.in("invoice-sync", "/in", 1)));
        assertEquals(Map.of("http", "/http"), SharedHttpPort.prefixes(List.of(http)), "the node takes its prefix as it is");
    }

    @Test
    void aSecondConsumerOfOnePathDoesNotStartUntilTheFirstStopped() {
        HttpComponent.HttpConsumer first = listen(e -> Future.succeededFuture(new Ack.Accepted(e.id())), 4096);
        Consumer second = http.consumer(Uses.in("other", "/in", 4096), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        Future<Void> refused = second.start();
        refused.otherwiseEmpty().await();
        assertTrue(refused.failed(), "the path is held");
        assertTrue(refused.cause().getMessage().contains("flow 'other': the path /http/in is mounted by flow 'invoice-sync'"), refused.cause().getMessage());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:" + port.port() + "/http/in")), first.url());
    }

    @Test
    void withoutAnHttpPortNoConsumerStarts() {
        HttpComponent alone = new HttpComponent(vertx, rt.blobs(), rt.ids());
        Future<Void> start = alone.consumer(Uses.in("orders", "/in", 1),
                e -> Future.succeededFuture(new Ack.Accepted(e.id()))).start();
        start.otherwiseEmpty().await();
        assertTrue(start.failed());
        assertEquals("flow 'orders': no HTTP port is open here", start.cause().getMessage());
    }

    HttpServer partner(Function<io.vertx.core.http.HttpServerRequest, Void> handler) {
        HttpServer s = vertx.createHttpServer().requestHandler(handler::apply).listen(0, "127.0.0.1").await();
        servers.add(s);
        return s;
    }

    Producer producerTo(HttpServer partner, String path, Map<String, Object> extra) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of("url", "http://127.0.0.1:" + partner.actualPort() + path));
        values.putAll(extra);
        return http.producer(Uses.out(values));
    }

    @Test
    void theProducerPostsTheBodyWithTheHeadersAndTheResponseIsTheReplyEnvelope() {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            seen.add(req.method().name() + " " + req.path() + " " + req.getHeader("x-request-id") + " " + req.getHeader("content-length") + " " + b);
            req.response().setStatusCode(201).putHeader("content-type", "application/xml").putHeader("x-partner", "p1").end("<PartnerAck/>");
        }); return null; });
        Producer p = producerTo(partner, "/invoices", Map.of());
        Envelope in = rt.ingress("invoice-sync", Headers.empty().with("x-request-id", "r-9").with("content-type", "application/xml"), Buffer.buffer("<Invoice/>")).advance();
        Envelope reply = p.send(in, CALL).await();
        assertEquals(List.of("POST /invoices r-9 10 <Invoice/>"), seen);
        assertEquals(2, reply.step(), "the reply is the next step's envelope");
        assertEquals(in.id(), reply.id());
        assertEquals("<PartnerAck/>", reply.body().whole().toString());
        assertEquals("application/xml", reply.headers().get("content-type"));
        assertEquals("p1", reply.headers().get("x-partner"));
        assertEquals(Optional.of(201), reply.get(HttpComponent.STATUS));
        assertEquals(2, reply.body().key().step());
        p.send(in, CALL).await();
        assertEquals(2, seen.size(), "a second send re-opens the body and posts it again");
        assertEquals(2, ((HttpComponent.HttpProducer) p).sends());
        p.close().await();
    }

    @Test
    void non2xxResponsesFailTheSendRetryableFor503And429FinalOtherwise() {
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            int status = Integer.parseInt(req.path().substring(1));
            req.response().setStatusCode(status).end(req.getHeader("x-long") != null ? "status " + status + " " + "e".repeat(100_000) : "status " + status + " body");
        }); return null; });
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        for (int status : List.of(503, 429)) {
            Producer p = producerTo(partner, "/" + status, Map.of());
            Future<Envelope> f = p.send(in, CALL);
            f.otherwiseEmpty().await();
            assertTrue(f.cause() instanceof NackException n && n.retryable() && n.reason().contains("status " + status), String.valueOf(f.cause()));
            p.close().await();
        }
        for (int status : List.of(400, 404, 500)) {
            Producer p = producerTo(partner, "/" + status, Map.of());
            Future<Envelope> f = p.send(in, CALL);
            f.otherwiseEmpty().await();
            assertTrue(f.cause() instanceof HttpStatusException x && x.status() == status && x.retryable() == (status >= 500) && x.getMessage().contains("status " + status + " body"), String.valueOf(f.cause()));
            p.close().await();
        }
        Producer p = producerTo(partner, "/502", Map.of());
        Future<Envelope> big = p.send(in.withHeader("x-long", "1"), CALL);
        big.otherwiseEmpty().await();
        assertTrue(big.cause() instanceof HttpStatusException x && x.status() == 502 && x.getMessage().startsWith("HTTP 502: status 502 eee"), String.valueOf(big.cause()));
        assertTrue(big.cause().getMessage().length() < HttpComponent.ERROR_BODY_BYTES + 32, "a 100 kB error page is cut at " + HttpComponent.ERROR_BODY_BYTES + " bytes, never buffered whole: " + big.cause().getMessage().length());
        p.close().await();
        HttpStatusException e = new HttpStatusException(502, "");
        assertEquals("HTTP 502", e.getMessage());
        assertTrue(e.retryable());
    }

    @Test
    void aReplyAboveTheLimitFailsAndASlowPartnerTimesOut() {
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            if (req.path().equals("/big")) req.response().end("z".repeat(5000));
            // "/never": no response at all
        }); return null; });
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        Producer toBig = producerTo(partner, "/big", Map.of());
        Future<Envelope> big = toBig.send(in, CALL);
        big.otherwiseEmpty().await();
        assertTrue(big.cause() instanceof dev.rail8.api.BodyTooLargeException, "the reply is written under the endpoint's limit like any body: " + big.cause());
        toBig.close().await();
        Producer toNever = producerTo(partner, "/never", Map.of("timeoutMs", 200));
        Future<Envelope> slow = toNever.send(in, CALL);
        slow.otherwiseEmpty().await();
        assertTrue(slow.failed(), "timeoutMs bounds the wait: " + slow.cause());
        toNever.close().await();
    }

    /**
     * P2-15b (D-101 (3)): every request carries the envelope's id as {@code x-message-id}, exactly one value — a value forwarded
     * under that name, in any case, is dropped, the other headers still go — through {@code send(e)} and {@code send(e, d)}.
     */
    @Test
    void aForwardedMessageIdIsReplaced() {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            seen.add(req.headers().getAll("x-message-id") + " " + req.getHeader("x-request-id"));
            req.response().setStatusCode(202).end();
        }); return null; });
        Producer p = producerTo(partner, "/in", Map.of());
        Envelope in = rt.ingress("f", Headers.empty().with("X-Message-ID", "upstream-1").with("x-message-id", "upstream-2").with("x-request-id", "r-1"),
                Buffer.buffer("x"));
        p.send(in, CALL).await();
        Envelope bounded = in.withId("m-bounded");
        p.send(bounded, CALL, () -> java.time.Duration.ofSeconds(30)).await();
        assertEquals(List.of("[" + in.id() + "] r-1", "[m-bounded] r-1"), seen,
                "one x-message-id per request, the envelope's own; every forwarded value dropped whatever its case, the other headers kept");
        p.close().await();
    }

    /**
     * P2-15b (D-113 draft): a send whose deadline runs out while its reply is still streaming in is cut on the component's timer:
     * the request is reset (the partner sees its connection closed), the send fails with a retryable Nack and no reply is stored
     * under the envelope's next key. Witness: the same partner streaming the same way to a send whose deadline was renewed
     * meanwhile — not cut, the connection kept, the reply stored, the timer cancelled once the send settled.
     */
    @Test
    void aCutSendResetsTheRequestAndStoresNoReply() {
        HttpComponent timed = new HttpComponent(vertx, rt.blobs(), rt.ids(), rt.timer());
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, io.vertx.core.http.HttpServerResponse> streaming = new java.util.concurrent.ConcurrentHashMap<>();
        HttpServer partner = partner(req -> {
            String path = req.path();
            req.connection().closeHandler(v -> events.add("closed " + path));   // one handler per connection: the latest request's
            req.body().onSuccess(b -> {
                io.vertx.core.http.HttpServerResponse res = req.response().setStatusCode(200).setChunked(true).putHeader("content-type", "text/plain");
                res.write("partial-");   // the head and a first chunk: the reply streams into the store when the deadline comes
                streaming.put(path, res);
                events.add("streaming " + path);
            });
            return null;
        });
        String base = "http://127.0.0.1:" + partner.actualPort();
        Producer p = timed.producer(Uses.out(base + "/r"));
        Producer q = timed.producer(Uses.out(base + "/c"));
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> until = new java.util.concurrent.atomic.AtomicReference<>(rt.clock().instant().plusSeconds(5));
        dev.rail8.api.Deadline d = () -> java.time.Duration.between(rt.clock().instant(), until.get());
        try {
            Envelope kept = rt.ingress("f", Headers.empty(), Buffer.buffer("kept")).advance();
            Future<Envelope> renewed = p.send(kept, CALL, d);
            assertTrue(soon(() -> events.contains("streaming /r")), "fixture: the reply streams: " + events);
            rt.clock().advance(java.time.Duration.ofSeconds(4));
            until.set(rt.clock().instant().plusSeconds(6));   // renewed: 5 s past the deadline it was sent with
            rt.clock().advance(java.time.Duration.ofSeconds(2));
            streaming.get("/r").end("rest");
            Envelope reply = renewed.await(10, TimeUnit.SECONDS);
            assertEquals("partial-rest", reply.body().whole().toString(), "witness: the renewed send's reply arrives whole");
            assertTrue(rt.blobs().contains(kept.nextKey()), "and is stored under the envelope's next key");
            assertTrue(soon(() -> rt.timer().pending() == 0), "the send's timer is cancelled once it settled: " + rt.timer().pending() + " pending");
            assertFalse(events.contains("closed /r"), "the renewed send's connection kept: " + events);

            Envelope cut = rt.ingress("f", Headers.empty(), Buffer.buffer("cut")).advance();
            until.set(rt.clock().instant().plusSeconds(3));
            Future<Envelope> outcome = q.send(cut, CALL, d);
            assertTrue(soon(() -> events.contains("streaming /c")), "fixture: the reply streams: " + events);
            rt.clock().advance(java.time.Duration.ofSeconds(3));
            outcome.otherwiseEmpty().await(10, TimeUnit.SECONDS);
            assertTrue(outcome.failed(), "cut at its deadline: " + outcome);
            assertTrue(outcome.cause() instanceof NackException n && n.retryable(), "a retryable Nack: " + outcome.cause());
            assertTrue(soon(() -> events.contains("closed /c")), "the request was reset: the partner's connection closed: " + events);
            streaming.get("/c").end("rest").otherwiseEmpty().await(10, TimeUnit.SECONDS);   // the partner finishes, too late
            assertFalse(rt.blobs().contains(cut.nextKey()), "no reply stored for the cut send");
        } catch (TimeoutException stuck) {
            throw new AssertionError("a send never settled: " + events, stuck);
        } finally {
            p.close().await();
            q.close().await();
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds, at most 10 s. */
    static boolean soon(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /** How long {@link LateListeningStore} waits for a reply to end on its own before it listens anyway (a paused reply never does). */
    static final long LATE_LISTEN_MS = 1_000;

    /**
     * The store as the producer sees it, listening late — which the {@code BlobStore} contract allows: a stream "may be handed over
     * paused, the store resumes it once its handlers are attached". It attaches its handlers only once the reply has ended (its
     * {@code end()} future, which attaches nothing) or after {@link #LATE_LISTEN_MS}, and records whether the reply ended unheard.
     */
    static final class LateListeningStore implements dev.rail8.api.BlobStore {
        final dev.rail8.api.BlobStore inner;
        final AtomicInteger attached = new AtomicInteger();
        final AtomicBoolean endedUnheard = new AtomicBoolean();
        LateListeningStore(dev.rail8.api.BlobStore inner) { this.inner = inner; }
        @Override public long treeLimit() { return inner.treeLimit(); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }
        @Override public io.vertx.core.streams.ReadStream<Buffer> open(dev.rail8.api.BlobKey key) { return inner.open(key); }
        @Override public Future<Buffer> read(dev.rail8.api.BlobKey key) { return inner.read(key); }
        @Override public Future<Void> persist(dev.rail8.api.BlobKey key) { return inner.persist(key); }
        @Override public Future<dev.rail8.api.Body> write(dev.rail8.api.BlobKey key, io.vertx.core.streams.ReadStream<Buffer> in, long maxSize) {
            HttpClientResponse reply = (HttpClientResponse) in;   // the producer hands its partner's response over
            io.vertx.core.Context ctx = Vertx.currentContext();
            io.vertx.core.Promise<dev.rail8.api.Body> written = io.vertx.core.Promise.promise();
            AtomicBoolean listened = new AtomicBoolean();
            Runnable listen = () -> {
                attached.incrementAndGet();
                try {
                    inner.write(key, in, maxSize).onComplete(written);
                } catch (RuntimeException refused) {   // e.g. "Response already ended": the reply's bytes went by unheard
                    written.fail(refused);
                }
            };
            reply.end().onComplete(done -> {   // an end after the store listened is the normal end, not this
                if (listened.compareAndSet(false, true)) {
                    endedUnheard.set(true);
                    listen.run();
                }
            });
            ctx.owner().setTimer(LATE_LISTEN_MS, id -> ctx.runOnContext(v -> { if (listened.compareAndSet(false, true)) listen.run(); }));
            return written.future();
        }
    }

    /**
     * FIX-21: the producer hands its partner's reply to the store paused, like the consumer hands its request: a store that
     * listens late still gets the whole 1-byte reply, the reply never ends before anyone listened (else the producer is left with
     * "Response already ended" and its retryable step sends again), and the partner sees the send exactly once.
     */
    @Test
    void theReplyIsHandedToTheStorePausedSoALateListeningStoreGetsItWholeAndThePartnerSeesTheSendOnce() throws Exception {
        AtomicInteger partnerSaw = new AtomicInteger();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            partnerSaw.incrementAndGet();
            req.response().putHeader("content-type", "text/plain").end("k");
        }); return null; });
        LateListeningStore store = new LateListeningStore(rt.blobs());
        HttpComponent late = new HttpComponent(vertx, store, rt.ids());
        String url = "http://127.0.0.1:" + partner.actualPort() + "/tiny";
        Producer p = late.producer(Uses.out(url));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        java.util.concurrent.CompletableFuture<Future<Envelope>> sent = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("message").start(() -> {   // as in a flow: the send starts on the message's virtual thread
            Future<Envelope> f = p.send(in, CALL);
            try {
                f.otherwiseEmpty().await(10, TimeUnit.SECONDS);
                sent.complete(f);
            } catch (TimeoutException stuck) {
                sent.completeExceptionally(stuck);
            }
        });
        Future<Envelope> f = sent.get(15, TimeUnit.SECONDS);
        assertEquals(1, partnerSaw.get(), "the partner saw the send exactly once");
        assertEquals(1, store.attached.get(), "the store listened once");
        assertFalse(store.endedUnheard.get(), "the reply ended before the store listened: its bytes went by unheard; the send: " + (f.failed() ? f.cause() : "succeeded"));
        assertTrue(f.succeeded(), "the send returns the partner's reply: " + f.cause());
        assertEquals("k", f.result().body().whole().toString());
        p.close().await(10, TimeUnit.SECONDS);
    }

    // ---- the target a producer calls -----------------------------------------------------------------------------------

    Optional<String> target(String url) {
        return http.target(Uses.out(url));
    }

    static Outbound out(String key, String url) {
        return new Outbound(Optional.of(new Connection(key, Map.of("url", url), Map.of())));
    }

    /**
     * The target of an http producer is {@code scheme://host:port} of its connection's {@code url}: lower-cased, the scheme's
     * default port filled in (https keeps 443), never userinfo, path, query or fragment — two connections on one partner's paths
     * name one target. Answered from the connection alone: a host nobody resolves is answered as written.
     */
    @Test
    void targetIsSchemeHostPortFromUrlWithoutUserinfoPathQuery() {
        assertEquals(Optional.of("https://partner.example.com:443"), target("https://User:Secret@Partner.Example.COM/orders/v1?draft=true#top"),
                "userinfo, path, query and fragment dropped, lower-cased, https default port");
        assertEquals(Optional.of("http://partner.example:80"), target("http://partner.example/in"), "http default port");
        assertEquals(Optional.of("http://partner.example:8080"), target("http://partner.example:8080/in"), "an explicit port");
        assertEquals(Optional.of("https://partner.example:8443"), target("HTTPS://PARTNER.example:8443"), "an upper-case scheme, no path");
        assertEquals(Optional.of("http://[::1]:9000"), target("http://[::1]:9000/x"), "an IPv6 literal keeps its brackets");
        assertEquals(target("http://partner.example:8080/a"), target("http://partner.example:8080/b/c?d=e"), "two paths of one partner: one target");
        assertEquals(Optional.of("https://partner.invalid:443"), target("https://partner.invalid/never"), "a host that never resolves is answered as configured: no DNS");
    }

    /**
     * A host java.net.URI cannot read as a server name (an underscore: its authority is registry-based, getHost() is null) still
     * gets a key shared by every connection to that partner: from the raw authority, userinfo stripped, lower-cased, the scheme's
     * default port filled in.
     */
    @Test
    void aHostWithAnUnderscoreStillGetsASharedTarget() {
        assertEquals(Optional.of("https://my_host.example:443"), target("https://User@My_Host.example:443/x"), "userinfo stripped, lower-cased, the port as written");
        assertEquals(Optional.of("https://my_host.example:443"), target("https://u:pw@MY_HOST.example/y?q=1"), "the https default port filled in, path and query dropped");
        assertEquals(Optional.of("http://my_host.example:80"), target("HTTP://My_Host.example/z"), "the http default port, the scheme lower-cased");
        assertEquals(target("https://my_host.example/a"), target("https://My_Host.example:443/b#c"), "two paths of one partner: one target");
    }

    /** An IPv6 literal without a port: the colons inside its brackets are not a port; the scheme's default port is filled in. */
    @Test
    void anIpv6LiteralWithoutAPortGetsTheDefaultPort() {
        assertEquals(Optional.of("http://[::1]:80"), target("http://[::1]/x"), "http, no port");
        assertEquals(Optional.of("https://[::1]:443"), target("https://[::1]"), "https, no port, no path");
        assertEquals(Optional.of("http://[fe80::1]:80"), target("http://[FE80::1]/in"), "lower-cased");
    }

    /**
     * Vert.x parses the producer's url with {@code java.net.URL} (RequestOptions.setAbsoluteURI), which takes a query character
     * {@code java.net.URI} refuses (an unencoded {@code |}) and a space: without a path the producer sends to such a url as it
     * is, and its target is still {@code scheme://host:port}, answered without a throw.
     */
    @Test
    void aUrlTheProducerAcceptsButURIRejectsStillSends() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> URI.create("http://127.0.0.1:1/in?x=a|b"), "fixture: java.net.URI refuses the url");
        assertEquals(Optional.of("http://partner.example:8080"), target("http://User@Partner.Example:8080/a b?q=1 2|3"),
                "a space and a | in path and query: userinfo stripped, lower-cased, the port as written");
        assertEquals(Optional.of("https://partner.example:443"), target("https://partner.example/in?x=a|b"), "the https default port");

        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(req -> {
            seen.add(req.uri());
            req.response().end("ok");
            return null;
        });
        Outbound out = Uses.out("http://127.0.0.1:" + partner.actualPort() + "/in?x=a|b");
        assertEquals(Optional.of("http://127.0.0.1:" + partner.actualPort()), http.target(out), "the target of the url the producer sends to");
        Producer p = http.producer(out);
        try {
            Future<Envelope> f = onAVirtualThread(() -> p.send(rt.ingress("f", Headers.empty(), Buffer.buffer("x")), CALL));
            assertTrue(f.succeeded(), "the producer sends to the url java.net.URI refuses: " + f.cause());
            assertEquals(List.of("/in?x=a|b"), seen, "the partner saw the request as the url names it");
        } finally {
            p.close().await(10, TimeUnit.SECONDS);
        }
    }

    /** Runs {@code send} on a virtual thread, as a flow does, and gives the send once it settled. */
    static Future<Envelope> onAVirtualThread(java.util.function.Supplier<Future<Envelope>> send) throws Exception {
        java.util.concurrent.CompletableFuture<Future<Envelope>> sent = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("message").start(() -> {
            Future<Envelope> f = send.get();
            try {
                f.otherwiseEmpty().await(10, TimeUnit.SECONDS);
                sent.complete(f);
            } catch (TimeoutException stuck) {
                sent.completeExceptionally(stuck);
            }
        });
        return sent.get(15, TimeUnit.SECONDS);
    }

    /** A url with an empty host (as the producer parses it) names no target: its sends get a limit of their own, never a key {@code http://:80}. */
    @Test
    void aUrlWithNoHostHasNoTarget() {
        assertEquals(Optional.empty(), target("http:///x"), "http:///x has no host");
    }

    /**
     * A url the producer cannot send to (a scheme other than http/https, no scheme, a port that is no number) refuses the build:
     * an IllegalArgumentException naming the connection and its setting {@code url} — never the url itself, which may carry a
     * password, neither in its message nor anywhere down its cause chain.
     */
    @Test
    void aUrlTheProducerCannotSendToRefusesTheBuildNamingTheConnectionNotTheUrl() {
        for (String url : List.of("foo://x", "x:abc", "ftp://h/x", "https://u:Secret@h:abc/x")) {
            for (java.util.function.Function<Outbound, Object> use : List.<java.util.function.Function<Outbound, Object>>of(http::target, http::producer)) {
                IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> use.apply(out("partner-orders", url)), url + ": refused");
                String message = String.valueOf(refused.getMessage());
                assertEquals("connection 'partner-orders': the setting 'url' is no http(s) URL the producer can send to", message, url);
                for (Throwable c = refused.getCause(); c != null; c = c.getCause()) {
                    assertFalse(String.valueOf(c.getMessage()).contains("Secret"), url + ": no password down the cause chain: " + c);
                }
            }
        }
        assertEquals("connection 'partner-orders': the setting 'url' is required",
                assertThrows(IllegalArgumentException.class, () -> http.target(new Outbound(Optional.of(new Connection("partner-orders", Map.of(), Map.of()))))).getMessage(),
                "a connection without a url names the setting");
    }

    // ---- the path of a call ---------------------------------------------------------------------------------------------

    /** A partner that answers each request with its URI, the requests it saw in {@code seen}. */
    HttpServer echo(List<String> seen) {
        return partner(req -> { req.body().onSuccess(b -> {
            seen.add(req.uri() + (req.getHeader("authorization") == null ? "" : " authorization"));
            req.response().end(req.uri());
        }); return null; });
    }

    /**
     * A call's {@code path} is joined to the url's path and the url's query kept; the request is built from the parts of that
     * one joined URI. A path that stays inside the url's path after its dot segments are resolved is sent there.
     */
    @Test
    void aCallsPathIsJoinedToTheUrlsPathAndTheUrlsQueryKept() {
        List<String> seen = new CopyOnWriteArrayList<>();
        String base = "http://127.0.0.1:" + echo(seen).actualPort();
        Producer withQuery = http.producer(Uses.out(base + "/api?k=1"));
        Producer withSlash = http.producer(Uses.out(base + "/api/"));
        Producer atRoot = http.producer(Uses.out(base));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        try {
            assertEquals("/api/orders?k=1", withQuery.send(in, Uses.call("/orders", 4096)).await().body().whole().toString());
            assertEquals("/api/orders/7", withSlash.send(in, Uses.call("/orders/7", 4096)).await().body().whole().toString(), "the url's trailing / is not doubled");
            assertEquals("/api/a/b", withSlash.send(in, Uses.call("/a/./x/../b", 4096)).await().body().whole().toString(), "dot segments inside the url's path resolved");
            assertEquals("/orders%20x", atRoot.send(in, Uses.call("/orders%20x", 4096)).await().body().whole().toString(), "an escape kept as it is");
            assertEquals("/api?k=1", withQuery.send(in, CALL).await().body().whole().toString(), "without a path: the url as it is");
            assertEquals(List.of("/api/orders?k=1", "/api/orders/7", "/api/a/b", "/orders%20x", "/api?k=1"), seen);
        } finally {
            withQuery.close().await();
            withSlash.close().await();
            atRoot.close().await();
        }
    }

    /**
     * A path that would leave the url's path or change its host — dot segments, encoded or not, a path not from {@code /}, a
     * query or fragment in it — fails the send with a {@link dev.rail8.api.Fault} of kind {@code INVALID}, through both sends,
     * before a request: the partner sees nothing, the send is not counted, and the message never carries the url's user info.
     */
    @Test
    void aPathThatLeavesTheUrlsPathIsRefusedAsInvalidBeforeARequest() {
        List<String> seen = new CopyOnWriteArrayList<>();
        int port = echo(seen).actualPort();
        Producer api = http.producer(Uses.out("http://user:Secret@127.0.0.1:" + port + "/api"));
        Producer root = http.producer(Uses.out("http://127.0.0.1:" + port));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        List<String> leaving = List.of("/../admin", "/%2e%2e/admin", "/%2E%2E/admin", "/x/../../admin", "/..", "orders", "@evil.example/x",
                "/a?b=1", "/a#top", "/a b");
        try {
            for (String path : leaving) {
                for (Future<Envelope> f : List.of(api.send(in, Uses.call(path, 4096)), api.send(in, Uses.call(path, 4096), () -> Duration.ofSeconds(30)))) {
                    f.otherwiseEmpty().await();
                    assertTrue(f.cause() instanceof dev.rail8.api.Fault fault && fault.kind() == dev.rail8.api.Fault.Kind.INVALID, path + ": " + f.cause());
                    assertTrue(f.cause().getMessage().startsWith("connection 'partner', flow '" + Uses.FLOW + "', step 'send': "), f.cause().getMessage());
                    assertFalse(f.cause().getMessage().contains("Secret") || f.cause().getMessage().contains("user:"), f.cause().getMessage());
                }
            }
            for (String path : List.of("/../admin", "/%2e%2e", "/x/../../y")) {
                Future<Envelope> f = root.send(in, Uses.call(path, 4096));
                f.otherwiseEmpty().await();
                assertTrue(f.cause() instanceof dev.rail8.api.Fault fault && fault.kind() == dev.rail8.api.Fault.Kind.INVALID, "at the url's root too: " + path + ": " + f.cause());
            }
            assertEquals(List.of(), seen, "no request reached the partner");
            assertEquals(0, ((HttpComponent.HttpProducer) api).sends(), "no refused send was counted");
        } finally {
            api.close().await();
            root.close().await();
        }
    }

    /** Without a path the url is sent to as it is: a url with user info still sends, and Vert.x sends no user info. */
    @Test
    void withoutAPathAUrlWithUserInfoStillSendsAsItIs() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Producer p = http.producer(Uses.out("http://user:Secret@127.0.0.1:" + echo(seen).actualPort() + "/in?x=1"));
        try {
            assertEquals("/in?x=1", p.send(rt.ingress("f", Headers.empty(), Buffer.buffer("x")), CALL).await().body().whole().toString());
            assertEquals(List.of("/in?x=1"), seen, "sent to the url's path and query, no authorization header");
        } finally {
            p.close().await();
        }
    }

    /** The reply limit is the call's {@code maxReplyBytes}: one producer takes a reply at a call's limit and refuses it above another's. */
    @Test
    void theReplyLimitIsTheCallsMaxReplyBytes() {
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> req.response().end("r".repeat(100))); return null; });
        Producer p = http.producer(Uses.out("http://127.0.0.1:" + partner.actualPort() + "/in"));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        try {
            assertEquals(100, p.send(in, Uses.call(100)).await().body().size(), "at the call's limit");
            Future<Envelope> above = p.send(in, Uses.call(99));
            above.otherwiseEmpty().await();
            assertTrue(above.cause() instanceof dev.rail8.api.BodyTooLargeException, "above it: " + above.cause());
            Future<Envelope> bounded = p.send(in, Uses.call(99), () -> Duration.ofSeconds(30));
            bounded.otherwiseEmpty().await();
            assertTrue(bounded.cause() instanceof dev.rail8.api.BodyTooLargeException, "a bounded send too: " + bounded.cause());
        } finally {
            p.close().await();
        }
    }

    /** One producer serves calls with different paths at once: the partner holds both requests before it answers either. */
    @Test
    void oneProducerServesTwoCallsWithDifferentPathsAtOnce() {
        Map<String, io.vertx.core.http.HttpServerResponse> held = new java.util.concurrent.ConcurrentHashMap<>();
        HttpServer partner = partner(req -> { req.body().onSuccess(b -> {
            held.put(req.path(), req.response());
            if (held.size() == 2) held.forEach((path, res) -> res.end(path));
        }); return null; });
        Producer p = http.producer(Uses.out("http://127.0.0.1:" + partner.actualPort() + "/api"));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("x"));
        try {
            Future<Envelope> a = p.send(in, Uses.call("/a", 4096));
            Future<Envelope> b = p.send(rt.ingress("f", Headers.empty(), Buffer.buffer("y")), Uses.call("/b", 4096));
            assertEquals("/api/a", a.await().body().whole().toString());
            assertEquals("/api/b", b.await().body().whole().toString());
            assertEquals(java.util.Set.of("/api/a", "/api/b"), held.keySet());
        } finally {
            p.close().await();
        }
    }
}
