package dev.rail8.ingress;

import dev.rail8.api.Inbound;
import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shared port reads of a caller from the head of a request, and how a refusal is answered on it: over a real port on
 * 127.0.0.1, so every request comes from loopback.
 */
class SharedHttpPortEntryTest {

    static final Presented.Via LOOPBACK = new Presented.Via("127.0.0.1", true, false, Optional.empty());

    final Vertx vertx = Vertx.vertx();
    final FakeTimer timer = new FakeTimer(FakeClock.at(Instant.parse("2026-01-01T00:00:00Z")));
    final SharedHttpPort port = new SharedHttpPort(vertx, timer, "127.0.0.1", 0, Duration.ofSeconds(5));
    final HttpClientAgent client = vertx.createHttpClient(new PoolOptions().setHttp1MaxSize(1));
    final AtomicReference<Presented> read = new AtomicReference<>();
    final AtomicReference<Refused> refusing = new AtomicReference<>();
    final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();

    @BeforeEach void mount() {
        port.listen(Map.of("http", "/http"), path -> false).await();
        port.mount("http", new Inbound("orders", Optional.empty(), Map.of("address", "/orders"), 1024), req -> {
            connections.add(req.connection());
            read.set(port.presented(req));
            if (refusing.get() != null) port.refuse(req, refusing.get());
            else req.end().onComplete(ended -> req.response().end("taken"));
        }).await();
    }

    @AfterEach void close() {
        client.close().await();
        port.close().await();
        vertx.close().await();
    }

    /** What a POST to the mount with {@code headers} presented. */
    Presented presenting(String... headers) {
        send(null, headers);
        return read.get();
    }

    /** {@code status headers body} of a POST to the mount with {@code body} (none when null) and {@code headers}. */
    String send(String body, String... headers) {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length; i += 2) h.add(headers[i], headers[i + 1]);
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost("127.0.0.1").setPort(port.port()).setURI("/http/orders").setHeaders(h);
        Future<String> answer = client.request(options).compose(req -> (body == null ? req.send() : req.send(body))
                .compose(res -> res.body().map(b -> res.statusCode()
                        + " www-authenticate=" + res.getHeader("www-authenticate") + " retry-after=" + res.getHeader("retry-after")
                        + " connection=" + res.getHeader("connection") + " content-type=" + res.getHeader("content-type") + " " + b)));
        try {
            return answer.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new AssertionError("no answer", failed);
        }
    }

    static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void basicIsTheUserAndThePasswordSplitAtTheFirstColon() {
        assertEquals(new Presented.Basic("acme-erp", "pa:ss wörd", LOOPBACK), presenting("Authorization", basic("acme-erp:pa:ss wörd")));
        assertEquals(new Presented.Basic("acme-erp", "x", LOOPBACK), presenting("authorization", "bAsIc   " + basic("acme-erp:x").substring(6)),
                "the scheme in any case, spaces before the value");
    }

    @Test
    void bearerIsItsTokenAndAnApiKeyIsItsKey() {
        assertEquals(new Presented.Bearer("tok.en-1", LOOPBACK), presenting("Authorization", "Bearer tok.en-1"));
        assertEquals(new Presented.ApiKey("key-1", LOOPBACK), presenting("X-API-Key", " key-1 "));
    }

    @Test
    void authorizationDecidesBeforeAnApiKey() {
        assertEquals(new Presented.Bearer("tok", LOOPBACK), presenting("X-API-Key", "key-1", "Authorization", "Bearer tok"));
    }

    @Test
    void aProofThePortCannotReadStillPresentsACredentialThatNoneMatches() {
        assertEquals(new Presented.Basic("", "", LOOPBACK), presenting("Authorization", "Basic !!not-base64!!"));
        assertEquals(new Presented.Basic("", "", LOOPBACK), presenting("Authorization", basic("no-colon")));
        assertEquals(new Presented.Basic("", "", LOOPBACK), presenting("Authorization", "Bearer a", "Authorization", "Bearer b"), "two copies");
        assertEquals(new Presented.Bearer("", LOOPBACK), presenting("Authorization", "Digest username=\"x\""), "another scheme");
        assertEquals(new Presented.ApiKey("", LOOPBACK), presenting("X-API-Key", "a", "X-API-Key", "b"), "two copies");
    }

    @Test
    void aRequestWithoutProofPresentsNothing() {
        assertEquals(new Presented.Nothing(LOOPBACK), presenting());
    }

    @Test
    void viaSaysWhetherABrowserSentItAndWhichSenderItChose() {
        assertTrue(presenting("Origin", "https://evil.example").via().browser());
        assertTrue(presenting("Referer", "https://evil.example/page").via().browser());
        assertTrue(presenting("Sec-Fetch-Site", "cross-site").via().browser());
        assertFalse(presenting("Sec-Fetch-Site", "none").via().browser(), "typed into the address bar: no other site sent it");
        assertEquals(Optional.of("eshop"), presenting("Rail8-Sender", " eshop ").via().senderChoice());
        assertEquals(Optional.empty(), presenting("Rail8-Sender", "a", "Rail8-Sender", "b").via().senderChoice(), "two copies name none");
    }

    @Test
    void onlyALiteralLoopbackAddressIsLoopback() {
        assertTrue(SharedHttpPort.loopback("127.0.0.1"));
        assertTrue(SharedHttpPort.loopback("127.9.9.9"));
        assertTrue(SharedHttpPort.loopback("::1"));
        assertTrue(SharedHttpPort.loopback("0:0:0:0:0:0:0:1"));
        assertFalse(SharedHttpPort.loopback("localhost"), "a name is never resolved");
        assertFalse(SharedHttpPort.loopback("10.0.0.1"));
        assertFalse(SharedHttpPort.loopback("::ffff:10.0.0.1"));
        assertFalse(SharedHttpPort.loopback(""));
    }

    @Test
    void anUnknownCallerIsAskedForBasicAndTheConnectionCarriesOnWithoutABody() {
        refusing.set(new Refused(Refused.Reason.UNKNOWN, Optional.empty()));
        String expected = "401 www-authenticate=Basic realm=\"rail8\", charset=\"UTF-8\" retry-after=null connection=null content-type=application/json "
                + "{\"code\":\"unknown-caller\",\"error\":\"the caller is not known\"}";
        assertEquals(expected, send(null));
        assertEquals(expected, send(null));
        assertEquals(1, connections.size(), "a refused request without a body keeps its connection: " + connections);
    }

    @Test
    void aRefusedRequestWithABodyHasItsConnectionClosedAndItsBodyUnread() {
        refusing.set(new Refused(Refused.Reason.TOO_MANY, Optional.of(Duration.ofMillis(1_500))));
        String expected = "429 www-authenticate=null retry-after=2 connection=close content-type=application/json "
                + "{\"code\":\"too-many\",\"error\":\"too many attempts; try again later\"}";
        assertEquals(expected, send("<order/>"));
        assertEquals(expected, send("<order/>"));
        assertEquals(2, connections.size(), "each refused request with a body closed its connection: " + connections);
    }

    @Test
    void eachReasonIsAnsweredWithItsStatusAndCode() {
        Map<Refused.Reason, String> expected = Map.of(
                Refused.Reason.UNKNOWN, "401 unknown-caller", Refused.Reason.NOT_ALLOWED, "403 not-allowed",
                Refused.Reason.SENDER_NEEDED, "400 sender-needed", Refused.Reason.TOO_MANY, "429 too-many",
                Refused.Reason.BUSY, "503 busy", Refused.Reason.TLS_REQUIRED, "403 tls-required");
        for (Refused.Reason reason : Refused.Reason.values()) {
            boolean waits = reason == Refused.Reason.TOO_MANY || reason == Refused.Reason.BUSY;
            Refused r = new Refused(reason, waits ? Optional.of(Duration.ofSeconds(4)) : Optional.empty());
            refusing.set(r);
            String answer = send(null);
            assertEquals(expected.get(reason), answer.substring(0, answer.indexOf(' ')) + " " + r.code(), answer);
            assertTrue(answer.endsWith("{\"code\":\"" + r.code() + "\",\"error\":\"" + r.getMessage() + "\"}"), answer);
            assertEquals(waits, answer.contains("retry-after=4"), answer);
            assertEquals(reason == Refused.Reason.UNKNOWN, !answer.contains("www-authenticate=null"), answer);
        }
    }

    @Test
    void aRefusalOfAnAnswerAlreadyBegunLeavesItAlone() {
        port.mount("http", new Inbound("begun", Optional.empty(), Map.of("address", "/begun"), 1024), req -> {
            req.response().setChunked(true).write("begun");
            port.refuse(req, new Refused(Refused.Reason.NOT_ALLOWED, Optional.empty()));
            req.response().end(" and ended");
        }).await();
        Future<String> answer = client.request(HttpMethod.POST, port.port(), "127.0.0.1", "/http/begun")
                .compose(req -> req.send().compose(res -> res.body().map(b -> res.statusCode() + " " + b)));
        assertEquals("200 begun and ended", answer.await());
    }
}
