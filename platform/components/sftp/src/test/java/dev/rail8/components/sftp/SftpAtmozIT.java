package dev.rail8.components.sftp;

import dev.rail8.api.Ack;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Secret;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login of the {@code sftp} consumer by each kind of secret against the SFTP server the compose stack runs (the same
 * image, pinned by digest, user {@code demo} with the directory {@code inbox}): a plain password, credentials, an OpenSSH key
 * as text, and a key pair with no certificate, overwritten between two sessions while the server switches the key it takes.
 * The server's RSA host key is the one the client negotiates; its fingerprint is the endpoint's {@code hostKey}.
 */
@Timeout(120)
class SftpAtmozIT {

    static final String IMAGE = "atmoz/sftp:alpine@sha256:43dd1c023cf1bae487127d4b54660b0aee6e94c188a6599be36fb89ba115f961";
    static final String PASSWORD = "demo-password";

    @SuppressWarnings("resource")   // stopped in stop()
    static final GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withCommand("demo:" + PASSWORD + ":1001:100:inbox")
            .withExposedPorts(22);
    static String hostKey;

    final FakeRuntime rt = FakeRuntime.create();
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
    private final List<TcpRelay> relays = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server.start();
        String printed = run("ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key.pub");
        hostKey = printed.split(" ")[1];
        assertTrue(hostKey.startsWith("SHA256:"), printed);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @AfterEach
    void down() throws Exception {
        for (Consumer c : consumers) c.stop().otherwiseEmpty().await(10, TimeUnit.SECONDS);
        for (TcpRelay r : relays) r.close();
        run("rm -f /home/demo/inbox/* /home/demo/.ssh/authorized_keys");
    }

    /** Runs {@code command} in the server's container as root; its output, or a failure naming its exit code and error. */
    static String run(String command) throws IOException, InterruptedException {
        Container.ExecResult result = server.execInContainer("sh", "-c", command);
        if (result.getExitCode() != 0) throw new IllegalStateException(command + ": exit " + result.getExitCode() + ": " + result.getStderr());
        return result.getStdout().trim();
    }

    /** Puts the file {@code name} into the user's {@code inbox}, owned by the user, so a delivery may delete it. */
    static void drop(String name) throws IOException, InterruptedException {
        run("printf '<x/>' > /home/demo/inbox/" + name + " && chown 1001:100 /home/demo/inbox/" + name);
    }

    /** From now on the server takes a public-key login of {@code demo} by {@code key} alone. */
    static void authorize(KeyPair key) throws IOException, InterruptedException {
        run("mkdir -p /home/demo/.ssh && printf '%s\\n' '" + PublicKeyEntry.toString(key.getPublic()) + "' > /home/demo/.ssh/authorized_keys"
                + " && chown -R 1001:100 /home/demo/.ssh && chmod 700 /home/demo/.ssh && chmod 600 /home/demo/.ssh/authorized_keys");
    }

    static KeyPair rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        return generator.generateKeyPair();
    }

    /**
     * The sender of {@code inbox} through a new relay (which tells when the client closed its session), logging in by
     * {@code login}, closing a session idle for 500 ms; the fake clock moved past the files' real mtimes.
     */
    Inbound endpoint(TcpRelay relay, Map<String, Object> login) {
        rt.clock().advance(Duration.between(rt.clock().instant(), Instant.now()).plusHours(1));
        Map<String, Object> all = new HashMap<>(Map.of("host", "127.0.0.1", "port", relay.port(), "hostKey", hostKey,
                "ioTimeoutMs", 500, "maxBodyBytes", 1_000_000L, "flow", "contract", "dir", "/inbox"));
        all.putAll(login);
        return EmbeddedSftp.inbound("sftp-in", all);
    }

    TcpRelay relay() throws IOException {
        TcpRelay relay = new TcpRelay(server.getMappedPort(22));
        relays.add(relay);
        return relay;
    }

    SftpComponent started(Inbound cfg, List<Envelope> seen) {
        SftpComponent component = new SftpComponent(rt.blobs(), rt.ids(), rt.timer(), rt.clock());
        Function<Envelope, Future<Ack>> accepting = e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        };
        Consumer c = component.consumer(cfg, accepting);
        consumers.add(c);
        c.start().await();
        return component;
    }

    void delivers(Map<String, Object> login, String file) throws Exception {
        drop(file);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = started(endpoint(relay(), login), seen);
        SftpComponentTest.round(rt);
        assertEquals(List.of(file), SftpComponentTest.files(seen), "logged in and delivered: " + login.keySet());
        assertEquals("", run("ls /home/demo/inbox"), "and deleted it");
        assertEquals(Health.Status.UP, component.health().await().status());
    }

    @Test
    void aPlainPasswordLogsIn() throws Exception {
        delivers(Map.of("user", "demo", "password", PASSWORD), "by-password.xml");
    }

    @Test
    void credentialsLogIn() throws Exception {
        delivers(Map.of("password", Secret.credentials("secret://partner-login", () -> new Secret.Credentials("demo", PASSWORD))), "by-credentials.xml");
    }

    @Test
    void anOpenSshKeyAsTextLogsIn() throws Exception {
        KeyPair key = rsaKey();
        authorize(key);
        delivers(Map.of("user", "demo", "privateKey", SftpConsumerEdgeTest.openSsh(key)), "by-openssh-key.xml");
    }

    @Test
    void aKeyPairWithNoCertificateLogsInAndAnOverwriteIsTheNextSessionsKey() throws Exception {
        KeyPair first = rsaKey();
        KeyPair second = rsaKey();
        AtomicReference<KeyPair> held = new AtomicReference<>(first);
        Secret pair = Secret.keyPair("secret://partner-key", () -> new Secret.KeyPair(held.get().getPrivate(), held.get().getPublic(), List.of()));
        authorize(first);
        drop("a.xml");
        TcpRelay relay = relay();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = started(endpoint(relay, Map.of("user", "demo", "privateKey", pair)), seen);
        SftpComponentTest.round(rt);
        assertEquals(List.of("a.xml"), SftpComponentTest.files(seen), "the first session logged in by the first key");
        assertTrue(SftpComponentTest.soon(() -> relay.clientsClosed() == 1), "the client closed its session idle for ioTimeoutMs");

        authorize(second);
        held.set(second);
        drop("b.xml");
        SftpComponentTest.round(rt);
        assertEquals(List.of("a.xml", "b.xml"), SftpComponentTest.files(seen), "the second session logged in by the new key, which alone the server takes now");
        assertEquals(Health.Status.UP, component.health().await().status());
    }
}
