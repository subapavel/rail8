package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.testkit.TestCertificates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every command that calls the admin API decides where it goes before anything is sent: a plain {@code http://} URL whose host
 * is not loopback ({@code localhost}, an IPv4 literal in 127.0.0.0/8 or {@code ::1}, as written, never resolved) is a usage
 * error, exit 2, unless {@code RAIL8_ADMIN_TLS=off}. An {@code https://} URL trusts the JVM's default trust store, or only the
 * certificates of {@code RAIL8_ADMIN_CA}; off never turns the certificate's or the host name's check off. The node is a
 * {@link StubAdmin}; {@code http://0.0.0.0:<port>} reaches it on Linux, so a refused run and a run with off differ only by the
 * refusal.
 */
class AdminApiTest {

    static final String TOKEN = "rail8k_" + "T".repeat(43);
    /** What deploy sends: an account's token, never the key. */
    static final String DEPLOY_TOKEN = "rail8t_0123456789abcdef_" + "T".repeat(43);
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    static Path dir;
    static Path caPem;
    static SSLContext localhost;

    final List<StubAdmin> stubs = new ArrayList<>();

    @BeforeAll
    static void certificates() throws Exception {
        TestCertificates ca = TestCertificates.ca(dir, "ca");
        caPem = ca.certificate();
        localhost = serving(ca.server("admin", "localhost", "dns:localhost"));
    }

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubAdmin::close);
    }

    StubAdmin stub(StubAdmin admin) {
        stubs.add(admin);
        return admin;
    }

    static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    // --- the rule, before any client exists

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://localhost:8081", "http://LocalHost:8081/", "http://127.0.0.1:8081", "http://127.31.0.9:8081",
            "http://[::1]:8081", "https://node.example:8443", "https://10.0.0.5:8443"})
    void loopbackOrHttpsIsTaken(String url) {
        assertDoesNotThrow(() -> AdminApi.of("deploy", Optional.of(url), Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://node.example:8081", "http://0.0.0.0:8081", "http://10.0.0.5:8081", "http://localhost.:8081",
            "http://127.0.0.1.example:8081", "http://[fe80::1]:8081", "HTTP://node.example:8081"})
    void plainHttpToAnotherHostIsRefusedUnlessOff(String url) {
        Cli.Usage refused = assertThrows(Cli.Usage.class, () -> AdminApi.of("deploy", Optional.of(url), Map.of()));

        assertEquals("deploy: the admin URL '" + url + "' is plain HTTP to a host other than loopback, so nothing is sent: use https://,"
                + " or RAIL8_ADMIN_TLS=off where the network to the node is protected another way", refused.getMessage());
        assertDoesNotThrow(() -> AdminApi.of("deploy", Optional.of(url), Map.of("RAIL8_ADMIN_TLS", "off")));
    }

    @Test
    void theUrlIsTheFlagElseRail8AdminElseLocalhost() {
        assertEquals("http://localhost:8081", AdminApi.of("deploy", Optional.empty(), Map.of()).url());
        assertEquals("https://a.example", AdminApi.of("deploy", Optional.empty(), Map.of("RAIL8_ADMIN", "https://a.example/")).url());
        assertEquals("https://b.example", AdminApi.of("deploy", Optional.of("https://b.example"), Map.of("RAIL8_ADMIN", "https://a.example")).url());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ftp://node.example", "node.example:8081", "http://", "http://a b"})
    void aUrlThatIsNotHttpIsRefused(String url) {
        Cli.Usage refused = assertThrows(Cli.Usage.class, () -> AdminApi.of("user add", Optional.of(url), Map.of()));

        assertTrue(refused.getMessage().startsWith("user add: the admin URL '" + url + "' is not an http or https URL"), refused.getMessage());
    }

    @Test
    void theSwitchIsOffOrNotSet() {
        Cli.Usage refused = assertThrows(Cli.Usage.class, () -> AdminApi.of("deploy", Optional.empty(), Map.of("RAIL8_ADMIN_TLS", "on")));

        assertEquals("deploy: RAIL8_ADMIN_TLS must be off or not set, was 'on'", refused.getMessage());
        Cli.Usage upperCase = assertThrows(Cli.Usage.class, () -> AdminApi.of("deploy", Optional.empty(), Map.of("RAIL8_ADMIN_TLS", "OFF")));
        assertEquals("deploy: RAIL8_ADMIN_TLS must be off or not set, was 'OFF'", upperCase.getMessage(), "off in lower case only");
        Cli.Usage empty = assertThrows(Cli.Usage.class, () -> AdminApi.of("deploy", Optional.of("http://node.example"), Map.of("RAIL8_ADMIN_TLS", "")));
        assertTrue(empty.getMessage().contains("is plain HTTP to a host other than loopback"), "empty is not set: " + empty.getMessage());
    }

    @Test
    void aCaThatCannotBeReadIsRefusedForHttpsOnly() throws Exception {
        Path missing = dir.resolve("missing.pem");
        Path notACertificate = Files.writeString(dir.resolve("not.pem"), "not a certificate\n");
        Path large = Files.write(dir.resolve("large.pem"), new byte[(int) AdminApi.CA_MAX_BYTES + 1]);

        assertEquals("deploy: RAIL8_ADMIN_CA names a file that does not exist: " + missing, refusal(missing));
        assertEquals("deploy: RAIL8_ADMIN_CA holds no PEM certificate: " + notACertificate, refusal(notACertificate));
        assertEquals("deploy: RAIL8_ADMIN_CA names a file of more than 65536 bytes: " + large, refusal(large));
        assertDoesNotThrow(() -> AdminApi.of("deploy", Optional.of("http://localhost:8081"), Map.of("RAIL8_ADMIN_CA", missing.toString())),
                "plain HTTP reads no CA");
    }

    static String refusal(Path ca) {
        return assertThrows(Cli.Usage.class, () -> AdminApi.of("deploy", Optional.of("https://localhost:8443"),
                Map.of("RAIL8_ADMIN_CA", ca.toString()))).getMessage();
    }

    // --- the commands: refused before a byte is sent, else sent

    @Test
    void deployToPlainHttpOffLoopbackSendsNothingUnlessOff() throws Exception {
        StubAdmin admin = stub(new StubAdmin());
        String elsewhere = "http://0.0.0.0:" + admin.port();

        Ran refused = run(env("RAIL8_TOKEN", DEPLOY_TOKEN), "deploy", "demo", COMMIT, "--expect", "none", "--admin", elsewhere);
        assertEquals(2, refused.exit(), refused.toString());
        assertTrue(refused.err().contains("is plain HTTP to a host other than loopback"), refused.toString());
        assertFalse(refused.err().contains(DEPLOY_TOKEN), "the token is never printed: " + refused);
        assertEquals(List.of(), admin.requests(), "nothing sent");

        Ran off = run(env("RAIL8_TOKEN", DEPLOY_TOKEN, "RAIL8_ADMIN_TLS", "off"), "deploy", "demo", COMMIT, "--expect", "none", "--admin", elsewhere);
        assertEquals(0, off.exit(), off.toString());
        assertEquals(List.of("Bearer " + DEPLOY_TOKEN), admin.only().header("authorization"), "with off it goes, the token with it");
    }

    @Test
    void userAddToPlainHttpOffLoopbackSendsNothingUnlessOff() throws Exception {
        StubAdmin admin = stub(new StubAdmin()).answer(201, "{\"name\":\"alice\",\"firstPassword\":\"pw\"}");

        Ran refused = run(env("RAIL8_ADMIN_TOKEN", TOKEN, "RAIL8_ADMIN", "http://0.0.0.0:" + admin.port()), "user", "add", "alice");
        assertEquals(2, refused.exit(), refused.toString());
        assertEquals(List.of(), admin.requests(), "nothing sent");

        Ran off = run(env("RAIL8_ADMIN_TOKEN", TOKEN, "RAIL8_ADMIN", "http://0.0.0.0:" + admin.port(), "RAIL8_ADMIN_TLS", "off"), "user", "add", "alice");
        assertEquals(0, off.exit(), off.toString());
        assertEquals("/admin/users", admin.only().path());
    }

    @Test
    void httpsTrustsTheCaOfRail8AdminCa() throws Exception {
        StubAdmin admin = stub(new StubAdmin(localhost));

        Ran ran = run(env("RAIL8_TOKEN", DEPLOY_TOKEN, "RAIL8_ADMIN_CA", caPem.toString()), "deploy", "demo", COMMIT, "--expect", "none", "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of("Bearer " + DEPLOY_TOKEN), admin.only().header("authorization"));
    }

    @Test
    void aCertificateTheClientDoesNotTrustIsRefusedEvenWithOff() throws Exception {
        StubAdmin admin = stub(new StubAdmin(localhost));

        Ran untrusted = run(env("RAIL8_TOKEN", DEPLOY_TOKEN, "RAIL8_ADMIN_TLS", "off"), "deploy", "demo", COMMIT, "--expect", "none", "--admin", admin.url());

        assertEquals(1, untrusted.exit(), untrusted.toString());
        assertTrue(untrusted.err().contains("no answer from " + admin.url()), untrusted.toString());
        assertEquals(List.of(), admin.requests(), "the handshake failed: nothing sent");
    }

    @Test
    void aCertificateForAnotherHostIsRefusedEvenWithOff() throws Exception {
        StubAdmin admin = stub(new StubAdmin(localhost));
        String byAddress = "https://127.0.0.1:" + admin.port();   // the certificate names localhost only

        Ran ran = run(env("RAIL8_TOKEN", DEPLOY_TOKEN, "RAIL8_ADMIN_CA", caPem.toString(), "RAIL8_ADMIN_TLS", "off"),
                "deploy", "demo", COMMIT, "--expect", "none", "--admin", byAddress);

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(List.of(), admin.requests(), "the host name check failed: nothing sent");
    }

    /** A server's TLS context of {@code server}'s certificate and PKCS#8 key. */
    static SSLContext serving(TestCertificates.Server server) throws Exception {
        String pem = new String(server.key(), StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        var key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Certificate[] chain = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(server.certificate()))
                .toArray(Certificate[]::new);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("admin", key, "pass".toCharArray(), chain);
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "pass".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        return tls;
    }
}
