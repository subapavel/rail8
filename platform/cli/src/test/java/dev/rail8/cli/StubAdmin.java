package dev.rail8.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A node's admin API as {@code rail8 deploy} sees it (P2-03a test fixture): the JDK's HTTP server on 127.0.0.1 and an ephemeral
 * port that records every request whole — method, path, raw query, headers (names lower-cased), body — before it answers the
 * status and body the case set for its path, else for every path (200 {@code {}} by default). A request is recorded before its answer leaves, so once the CLI
 * returned, what it sent is here; {@link #requests()} empty after a run is the witness that nothing was sent. Built with an
 * {@link SSLContext}, it serves HTTPS instead: a request is recorded only once the handshake completed.
 */
final class StubAdmin implements AutoCloseable {

    /** One request as it arrived. */
    record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        /** The values of the header {@code name} (any case), empty when it was not sent. */
        List<String> header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private final HttpServer server;
    private final String scheme;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private final Map<String, Map.Entry<Integer, String>> answers = new ConcurrentHashMap<>();

    StubAdmin() throws IOException {
        this(HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 0), "http://127.0.0.1:");
    }

    /** The same over HTTPS with the certificate of {@code tls}, at {@code https://localhost:<port>}. */
    StubAdmin(SSLContext tls) throws IOException {
        this(https(tls), "https://localhost:");
    }

    private StubAdmin(HttpServer server, String scheme) {
        this.server = server;
        this.scheme = scheme;
        server.createContext("/", this::handle);
        server.start();
    }

    private static HttpsServer https(SSLContext tls) throws IOException {
        HttpsServer secured = HttpsServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 0);
        secured.setHttpsConfigurator(new HttpsConfigurator(tls));
        return secured;
    }

    /** From now on every request is answered {@code status} with {@code body}. */
    StubAdmin answer(int status, String body) {
        this.status = status;
        this.answer = body;
        return this;
    }

    /** From now on every request to {@code path} is answered {@code status} with {@code body}, whatever {@link #answer(int, String)} set. */
    StubAdmin answer(String path, int status, String body) {
        answers.put(path, Map.entry(status, body));
        return this;
    }

    /** {@code http://127.0.0.1:<port>}, or {@code https://localhost:<port>} over HTTPS; no trailing slash. */
    String url() {
        return scheme + port();
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The one request sent; fails the case naming what arrived when there is not exactly one. */
    Request only() {
        List<Request> all = requests();
        if (all.size() != 1) throw new AssertionError("exactly one request expected, got " + all.size() + ": " + all.stream().map(r -> r.method() + " " + r.path() + "?" + r.query()).toList());
        return all.getFirst();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(), headers, body));
            Map.Entry<Integer, String> set = answers.getOrDefault(exchange.getRequestURI().getRawPath(), Map.entry(status, answer));
            int s = set.getKey();
            byte[] bytes = set.getValue().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (s == 204 || s == 304 || bytes.length == 0) {
                exchange.sendResponseHeaders(s, -1);
            } else {
                exchange.sendResponseHeaders(s, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
