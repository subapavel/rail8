package dev.rail8.runtime.node;

import dev.rail8.testkit.TestCertificates;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static dev.rail8.runtime.node.NodeConfigTest.minimal;
import static dev.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin listener's encryption: {@code RAIL8_ADMIN_TLS_CERT} and {@code RAIL8_ADMIN_TLS_KEY} give its certificate, read when
 * the configuration is; {@code RAIL8_ADMIN_TLS} is {@code off} or not set, and {@code off} with a certificate is refused.
 */
class NodeConfigAdminTlsTest {

    @TempDir
    static Path dir;
    static Path cert;
    static Path key;

    @BeforeAll
    static void pair() throws Exception {
        TestCertificates.Server server = TestCertificates.ca(dir, "ca").server("admin", "localhost", "dns:localhost");
        cert = Files.write(dir.resolve("admin.crt"), server.certificate());
        key = Files.write(dir.resolve("admin.key"), server.key());
    }

    static Map<String, String> offLoopback() {
        return with(with(minimal(), "RAIL8_ADMIN_HOST", "0.0.0.0"), "RAIL8_ADMIN_TOKEN", "s3cret");
    }

    static Map<String, String> certified(Map<String, String> env) {
        return with(with(env, "RAIL8_ADMIN_TLS_CERT", cert.toString()), "RAIL8_ADMIN_TLS_KEY", key.toString());
    }

    @Test
    void withoutTheVariablesThePlainListenerOffLoopbackRefusesLogins() {
        var admin = NodeConfig.from(offLoopback()).admin();

        assertTrue(admin.tls().isEmpty());
        assertFalse(admin.tlsOff());
        assertTrue(admin.refusesLogins());
        assertFalse(NodeConfig.from(minimal()).admin().refusesLogins(), "a loopback bind takes them");
    }

    @Test
    void theCertificateIsReadWithTheConfiguration() {
        var admin = NodeConfig.from(certified(offLoopback())).admin();

        assertTrue(admin.tls().orElseThrow().toString().contains("RAIL8_ADMIN_TLS_CERT=" + cert));
        assertFalse(admin.refusesLogins());
    }

    @Test
    void offTakesLoginsOnAPlainBind() {
        var admin = NodeConfig.from(with(offLoopback(), "RAIL8_ADMIN_TLS", "off")).admin();

        assertTrue(admin.tlsOff());
        assertFalse(admin.refusesLogins());
        assertFalse(NodeConfig.from(with(offLoopback(), "RAIL8_ADMIN_TLS", "")).admin().tlsOff(), "empty counts as not set");
    }

    @Test
    void anyOtherValueOfTheSwitchIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(offLoopback(), "RAIL8_ADMIN_TLS", "on")));

        assertEquals("RAIL8_ADMIN_TLS must be off or not set, was 'on'", refused.getMessage());
        IllegalArgumentException upperCase = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(offLoopback(), "RAIL8_ADMIN_TLS", "OFF")));
        assertEquals("RAIL8_ADMIN_TLS must be off or not set, was 'OFF'", upperCase.getMessage(), "off in lower case only");
    }

    @Test
    void offWithACertificateIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(certified(offLoopback()), "RAIL8_ADMIN_TLS", "off")));

        assertTrue(refused.getMessage().startsWith("RAIL8_ADMIN_TLS=off and a certificate"), refused.getMessage());
    }

    @Test
    void aKeyWithoutACertificateIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(offLoopback(), "RAIL8_ADMIN_TLS_KEY", key.toString())));

        assertEquals("RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY are given both or neither: RAIL8_ADMIN_TLS_CERT is not set", refused.getMessage());
    }
}
