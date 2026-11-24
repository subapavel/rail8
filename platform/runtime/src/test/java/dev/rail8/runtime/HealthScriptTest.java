package dev.rail8.runtime;

import dev.rail8.ingress.ServerCertificate;
import dev.rail8.testkit.TestCertificates;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image's health check, {@code deploy/quickstart/health.sh}, run with bash against a listener on this host's loopback at
 * {@code RAIL8_ADMIN_PORT}. It exits 0 on a 200 of {@code GET /health}: over HTTPS when {@code RAIL8_ADMIN_TLS_CERT} is set, over
 * plain HTTP otherwise. Any other answer, or the other protocol than the listener's, is not healthy.
 */
class HealthScriptTest {

    private static final Path SCRIPT = Path.of("..", "..", "deploy", "quickstart", "health.sh").toAbsolutePath().normalize();

    static Vertx vertx;
    static ServerCertificate certificate;
    final List<HttpServer> servers = new ArrayList<>();

    @BeforeAll
    static void up(@TempDir Path dir) throws Exception {
        vertx = Vertx.vertx();
        TestCertificates.Server server = TestCertificates.ca(dir, "ca").server("admin", "localhost", "dns:localhost,ip:127.0.0.1");
        Path cert = Files.write(dir.resolve("admin.crt"), server.certificate());
        Path key = Files.write(dir.resolve("admin.key"), server.key());
        certificate = ServerCertificate.fromEnv(Map.of("RAIL8_ADMIN_TLS_CERT", cert.toString(), "RAIL8_ADMIN_TLS_KEY", key.toString()),
                "RAIL8_ADMIN_TLS").orElseThrow();
    }

    @AfterAll
    static void down() {
        vertx.close().await();
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.close().await());
    }

    @Test
    void withACertificateItAsksOverHttps() throws Exception {
        assertEquals(0, run(listener(true, 200), true));
    }

    @Test
    void withoutOneItAsksOverPlainHttp() throws Exception {
        assertEquals(0, run(listener(false, 200), false));
    }

    @Test
    void anotherAnswerThanA200IsNotHealthy() throws Exception {
        assertNotEquals(0, run(listener(true, 503), true), "over HTTPS");
        assertNotEquals(0, run(listener(false, 503), false), "over plain HTTP");
    }

    @Test
    void theOtherProtocolThanTheListenersIsNotHealthy() throws Exception {
        assertNotEquals(0, run(listener(true, 200), false), "plain HTTP to an HTTPS listener");
        assertNotEquals(0, run(listener(false, 200), true), "HTTPS to a plain listener");
    }

    /** A listener on 127.0.0.1 that answers {@code GET /health} with {@code status}, over HTTPS when {@code https}: its port. */
    private int listener(boolean https, int status) {
        HttpServerOptions plain = new HttpServerOptions().setHost("127.0.0.1").setPort(0);
        HttpServer server = vertx.createHttpServer(https ? certificate.secure(plain) : plain)
                .requestHandler(req -> req.response().setStatusCode(req.path().equals("/health") ? status : 404).end())
                .listen().await();
        servers.add(server);
        return server.actualPort();
    }

    /** The script's exit code against {@code port}, with {@code RAIL8_ADMIN_TLS_CERT} set when {@code withCertificate}. */
    private static int run(int port, boolean withCertificate) throws Exception {
        ProcessBuilder bash = new ProcessBuilder("bash", SCRIPT.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        bash.environment().put("RAIL8_ADMIN_PORT", Integer.toString(port));
        if (withCertificate) bash.environment().put("RAIL8_ADMIN_TLS_CERT", "/etc/rail8/admin.crt");   // only whether it is set counts
        else bash.environment().remove("RAIL8_ADMIN_TLS_CERT");
        Process script = bash.start();
        assertTrue(script.waitFor(30, TimeUnit.SECONDS), "the script ends");
        return script.exitValue();
    }
}
