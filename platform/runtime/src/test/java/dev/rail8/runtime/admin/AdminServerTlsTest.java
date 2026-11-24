package dev.rail8.runtime.admin;

import dev.rail8.ingress.ServerCertificate;
import dev.rail8.testkit.TestCertificates;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin listener with a certificate serves HTTPS only. On a bind other than loopback without a certificate it starts with a
 * WARNING and refuses logins and sessions with 403 {@code tls-required} before the login reads its body, while the emergency key
 * and the shared token go on as during the bridge; {@code RAIL8_ADMIN_TLS=off} takes logins and sessions there and starts with a
 * WARNING of its own. A loopback bind and a bind with a certificate start without either.
 */
class AdminServerTlsTest extends AdminAccessFixture {

    static TestCertificates ca;
    static ServerCertificate certificate;
    static HttpClient trusting;

    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    final Logger logger = Logger.getLogger(AdminServer.class.getName());   // a strong reference: JUL holds loggers weakly
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeAll
    static void pair(@TempDir Path dir) throws Exception {
        ca = TestCertificates.ca(dir, "ca");
        TestCertificates.Server server = ca.server("admin", "localhost", "dns:localhost,ip:127.0.0.1");
        Path cert = Files.write(dir.resolve("admin.crt"), server.certificate());
        Path key = Files.write(dir.resolve("admin.key"), server.key());
        certificate = ServerCertificate.fromEnv(Map.of("RAIL8_ADMIN_TLS_CERT", cert.toString(), "RAIL8_ADMIN_TLS_KEY", key.toString()),
                "RAIL8_ADMIN_TLS").orElseThrow();
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(ca.certificate())) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusted.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trusted.getTrustManagers(), null);
        trusting = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void unlisten() {
        logger.removeHandler(capture);
    }

    int start(AdminServer.Config config) throws Exception {
        return server(config).start(vertx).await(10, TimeUnit.SECONDS);
    }

    List<String> warnings() {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    String loginBody(String name) {
        return new JsonObject().put("name", name).put("password", PASSWORD).encode();
    }

    @Test
    void aPlainBindOffLoopbackRefusesLoginsAndSessionsButNotTheKey() throws Exception {
        String root = root();
        logged.clear();
        int plain = start(new AdminServer.Config("0.0.0.0", 0, Optional.of(KEY)));

        Answer login = send(plain, "POST", "/admin/sessions", Optional.empty(), loginBody("root"));
        assertEquals(403, login.status(), login.body());
        assertEquals("tls-required", login.code());
        Answer session = send(plain, "GET", "/admin/me", Optional.of(root), null);
        assertEquals(403, session.status(), session.body());
        assertEquals("tls-required", session.code());
        assertEquals(200, send(plain, "GET", "/admin/me", Optional.of(KEY), null).status(), "the emergency key goes on");
        assertEquals(201, send(key, "POST", "/admin/sessions", Optional.empty(), loginBody("root")).status(), "the same login on loopback");
        assertEquals(200, send(key, "GET", "/admin/me", Optional.of(root), null).status(), "the same session on loopback");

        assertEquals(List.of("the admin API on 0.0.0.0:" + plain + " serves plain HTTP to other hosts: it refuses logins and sessions until it has"
                + " a certificate (RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY), or until RAIL8_ADMIN_TLS=off states that the network to it is"
                + " protected another way"), warnings());
    }

    @Test
    void aSessionIsRefusedByItsCredentialsFormBeforeTheStoreIsAsked() throws Exception {
        String root = root();
        assertEquals(204, send(key, "POST", "/admin/me/logout", Optional.of(root), null).status());
        int plain = start(new AdminServer.Config("0.0.0.0", 0, Optional.of(KEY)));

        Answer ended = send(plain, "GET", "/admin/me", Optional.of(root), null);

        assertEquals(403, ended.status(), "not 401 session-ended: the store is never asked, so a live session is not kept alive either");
        assertEquals("tls-required", ended.code());
    }

    @Test
    void aLoginIsRefusedBeforeItsBodyIsRead() throws Exception {
        int plain = start(new AdminServer.Config("0.0.0.0", 0, Optional.of(KEY)));

        Answer login = send(plain, "POST", "/admin/sessions", Optional.empty(), "x".repeat(AdminServer.LOGIN_BODY_MAX + 1));

        assertEquals(403, login.status(), "not 413: the body is never read");
        assertEquals("tls-required", login.code());
    }

    @Test
    void offTakesLoginsAndSessionsOnAPlainBindAndWarns() throws Exception {
        String root = root();
        logged.clear();
        int off = start(new AdminServer.Config("0.0.0.0", 0, Optional.of(KEY), Optional.empty(), true));

        Answer login = send(off, "POST", "/admin/sessions", Optional.empty(), loginBody("root"));
        assertEquals(201, login.status(), login.body());
        assertEquals(200, send(off, "GET", "/admin/me", Optional.of(root), null).status());

        assertEquals(List.of("RAIL8_ADMIN_TLS=off: the admin API on 0.0.0.0:" + off + " takes passwords and credentials over plain HTTP; the"
                + " network between the UI server, the command line and the nodes must be protected another way"), warnings());
    }

    @Test
    void aCertificateServesHttpsOnlyAndTakesLogins() throws Exception {
        String root = root();
        logged.clear();
        int secured = start(new AdminServer.Config("0.0.0.0", 0, Optional.of(KEY), Optional.of(certificate), false));

        HttpResponse<String> live = trusting.send(HttpRequest.newBuilder(URI.create("https://localhost:" + secured + "/live")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, live.statusCode());
        assertEquals("{\"status\":\"UP\"}", live.body());
        HttpResponse<String> login = trusting.send(HttpRequest.newBuilder(URI.create("https://localhost:" + secured + "/admin/sessions"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(loginBody("root"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, login.statusCode(), login.body());
        HttpResponse<String> me = trusting.send(HttpRequest.newBuilder(URI.create("https://localhost:" + secured + "/admin/me"))
                .header("Authorization", "Bearer " + root).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode(), me.body());
        assertThrows(UncheckedIOException.class, () -> send(secured, "GET", "/live", Optional.empty(), null), "no plain HTTP on it");

        assertEquals(List.of(), warnings());
    }

    @Test
    void aLoopbackBindWithoutACertificateStartsWithoutAWarning() throws Exception {
        start(new AdminServer.Config("127.0.0.1", 0, Optional.empty()));

        assertEquals(List.of(), warnings());
    }

    @Test
    void offWithACertificateIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new AdminServer.Config("0.0.0.0", 8081, Optional.of(KEY), Optional.of(certificate), true));

        assertEquals("RAIL8_ADMIN_TLS=off and a certificate (RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY) are both set: the admin API serves"
                + " HTTPS with the certificate, or plain HTTP with off", refused.getMessage());
    }

    @Test
    void itsTextShowsTheCertificateNeverTheToken() {
        String text = new AdminServer.Config("0.0.0.0", 8081, Optional.of(KEY), Optional.of(certificate), false).toString();

        assertTrue(text.startsWith("Config[host=0.0.0.0, port=8081, token=Optional[***], tls=Optional[ServerCertificate[RAIL8_ADMIN_TLS_CERT="), text);
        assertTrue(text.endsWith("], tlsOff=false]"), text);
    }
}
