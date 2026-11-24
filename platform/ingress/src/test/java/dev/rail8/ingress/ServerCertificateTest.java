package dev.rail8.ingress;

import dev.rail8.testkit.TestCertificates;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server certificate is read from {@code <prefix>_CERT} and {@code <prefix>_KEY} at the start: both or neither, each the
 * absolute path of a PEM file of at most 64 KiB, the key the first certificate's. Each refusal names the variable and the path
 * and never the content; a pair that passes serves HTTPS that a client trusting the CA completes.
 */
class ServerCertificateTest {

    static final String CERT = "RAIL8_ADMIN_TLS_CERT";
    static final String KEY = "RAIL8_ADMIN_TLS_KEY";
    static final String PREFIX = "RAIL8_ADMIN_TLS";

    @TempDir
    static Path dir;

    static TestCertificates ca;
    static Path cert;
    static Path key;
    static byte[] keyBytes;
    static Path otherKey;
    Vertx vertx;

    @BeforeAll
    static void pairs() throws Exception {
        ca = TestCertificates.ca(dir, "ca");
        TestCertificates.Server server = ca.server("admin", "localhost", "dns:localhost,ip:127.0.0.1");
        cert = Files.write(dir.resolve("admin.crt"), server.certificate());
        key = Files.write(dir.resolve("admin.key"), server.key());
        keyBytes = server.key();
        otherKey = Files.write(dir.resolve("other.key"), ca.server("other", "other", "dns:other").key());
    }

    @AfterEach
    void close() {
        if (vertx != null) vertx.close().await();
    }

    static Map<String, String> env(Object cert, Object key) {
        return Map.of(CERT, cert.toString(), KEY, key.toString());
    }

    static String refusal(Map<String, String> env) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ServerCertificate.fromEnv(env, PREFIX));
        String body = new String(keyBytes, StandardCharsets.US_ASCII).lines().filter(l -> !l.startsWith("-----")).findFirst().orElseThrow();
        assertFalse(refused.getMessage().contains(body), "a refusal never shows the key: " + refused.getMessage());
        return refused.getMessage();
    }

    @Test
    void neitherVariableIsNoCertificate() {
        assertEquals(Optional.empty(), ServerCertificate.fromEnv(Map.of(), PREFIX));
        assertEquals(Optional.empty(), ServerCertificate.fromEnv(env("", ""), PREFIX), "empty counts as not set");
    }

    @Test
    void oneWithoutTheOtherIsRefused() {
        assertEquals(CERT + " and " + KEY + " are given both or neither: " + KEY + " is not set", refusal(Map.of(CERT, cert.toString())));
        assertEquals(CERT + " and " + KEY + " are given both or neither: " + CERT + " is not set", refusal(env("", key)));
    }

    @Test
    void aPathThatIsNotAbsoluteIsRefusedWithoutItsValue() {
        String pasted = new String(keyBytes, StandardCharsets.US_ASCII);
        assertEquals(KEY + " must be the absolute path of a file (the value is not shown)", refusal(env(cert, pasted)));
        assertEquals(CERT + " must be the absolute path of a file (the value is not shown)", refusal(env("admin.crt", key)));
    }

    @Test
    void aMissingOrTooLargeFileIsRefusedByItsPath() throws Exception {
        Path missing = dir.resolve("missing.key");
        assertEquals(KEY + " names a file that does not exist: " + missing, refusal(env(cert, missing)));
        Path large = Files.write(dir.resolve("large.crt"), new byte[(int) ServerCertificate.MAX_BYTES + 1]);
        assertEquals(CERT + " names a file of more than 65536 bytes: " + large, refusal(env(large, key)));
        assertEquals(CERT + " names a file that cannot be read: " + dir, refusal(env(dir, key)));
    }

    @Test
    void aCertificateFileWithoutACertificateIsRefused() {
        assertEquals(CERT + " holds no PEM certificate: " + key, refusal(env(key, key)));
    }

    @Test
    void aKeyFileWithoutAKeyOfTheCertificatesAlgorithmIsRefused() throws Exception {
        assertEquals(KEY + " holds no unencrypted PEM private key (PRIVATE KEY, RSA PRIVATE KEY or EC PRIVATE KEY) of the first certificate's"
                + " EC key: " + cert, refusal(env(cert, cert)));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        Path rsaKey = Files.writeString(dir.resolve("rsa.key"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(rsa.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        assertEquals(KEY + " holds no unencrypted PEM private key (PRIVATE KEY, RSA PRIVATE KEY or EC PRIVATE KEY) of the first certificate's"
                + " EC key: " + rsaKey, refusal(env(cert, rsaKey)));
    }

    @Test
    void aKeyOfAnotherCertificateIsRefused() {
        assertEquals(KEY + " is not the key of the first certificate in " + CERT + " (CN=localhost; the server's own certificate comes first): "
                + otherKey, refusal(env(cert, otherKey)));
    }

    @Test
    void theServersOwnCertificateComesFirstAndItsChainAfterIt() throws Exception {
        byte[] caPem = Files.readAllBytes(ca.certificate());
        Path chain = Files.write(dir.resolve("chain.crt"), concat(Files.readAllBytes(cert), caPem));
        assertTrue(ServerCertificate.fromEnv(env(chain, key), PREFIX).isPresent(), "the chain after the server's certificate");
        Path reversed = Files.write(dir.resolve("reversed.crt"), concat(caPem, Files.readAllBytes(cert)));
        assertEquals(CERT + " holds its certificates out of order (the server's own first, each one's issuer right after it): " + reversed,
                refusal(env(reversed, key)));
        assertEquals(KEY + " is not the key of the first certificate in " + CERT + " (CN=Rail8 test ca; the server's own certificate comes first): "
                + key, refusal(env(ca.certificate(), key)), "the CA's certificate alone");
    }

    @Test
    void itsTextNamesTheFilesAndTheSubjectNeverTheKey() {
        String text = ServerCertificate.fromEnv(env(cert, key), PREFIX).orElseThrow().toString();
        assertTrue(text.startsWith("ServerCertificate[" + CERT + "=" + cert + ", subject=CN=localhost, notAfter="), text);
        assertTrue(text.endsWith(", " + KEY + "=" + key + "]"), text);
    }

    @Test
    void aServerItSecuresCompletesAHandshakeWithAClientThatTrustsTheCa() throws Exception {
        ServerCertificate certificate = ServerCertificate.fromEnv(env(cert, key), PREFIX).orElseThrow();
        vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer(certificate.secure(new HttpServerOptions().setHost("127.0.0.1").setPort(0)))
                .requestHandler(req -> req.response().end("secured")).listen().await();

        HttpResponse<String> answer = trusting(ca.certificate()).send(HttpRequest.newBuilder(URI.create("https://localhost:" + server.actualPort() + "/"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, answer.statusCode());
        assertEquals("secured", answer.body());
    }

    static HttpClient trusting(Path caPem) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(caPem)) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusted.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trusted.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(tls).build();
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }
}
