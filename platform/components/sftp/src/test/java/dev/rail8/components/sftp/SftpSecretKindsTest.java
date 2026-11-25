package dev.rail8.components.sftp;

import dev.rail8.api.Ack;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Secret;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login of the {@code sftp} consumer by each kind of secret its fields take: {@code password} as credentials, which also
 * give the user, and {@code privateKey} as a key pair with no certificate, presented as the session's only key. Each login
 * reads the secret anew, so a secret overwritten between two sessions is what the second one logs in with. The plain password
 * and the OpenSSH key as text are the other tests' logins.
 */
@Timeout(60)
class SftpSecretKindsTest {

    @TempDir
    Path root;

    EmbeddedSftp sftp;
    final FakeRuntime rt = FakeRuntime.create();
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
    }

    @AfterEach
    void down() throws Exception {
        for (Consumer c : consumers) c.stop().otherwiseEmpty().await(10, TimeUnit.SECONDS);
        sftp.close();
    }

    SftpComponent component(FakeRuntime on) { return new SftpComponent(on.blobs(), on.ids(), on.timer(), on.clock()); }

    Consumer started(FakeRuntime on, Inbound cfg, List<Envelope> seen) {
        Function<Envelope, Future<Ack>> accepting = e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        };
        Consumer c = component(on).consumer(cfg, accepting);
        consumers.add(c);
        c.start().await();
        return c;
    }

    /** The sender of {@code /inbox} that logs in by {@code login}: the fields it sets, the others of the server's endpoint left out. */
    Inbound endpoint(Map<String, Object> login) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("user", null);
        extra.put("password", null);
        extra.putAll(login);
        return sftp.endpoint("sftp-in", "/inbox", extra);
    }

    /** A handle of the credentials {@code held} holds at each read, counting the reads. */
    static Secret credentials(AtomicReference<Secret.Credentials> held, AtomicInteger reads) {
        return Secret.credentials("secret://partner-login", () -> {
            reads.incrementAndGet();
            return held.get();
        });
    }

    /** A handle of the key pair {@code held} holds at each read, with no certificate, counting the reads. */
    static Secret keyPair(AtomicReference<KeyPair> held, AtomicInteger reads) {
        return Secret.keyPair("secret://partner-key", () -> {
            reads.incrementAndGet();
            KeyPair pair = held.get();
            return new Secret.KeyPair(pair.getPrivate(), pair.getPublic(), List.of());
        });
    }

    /** Whether {@code offered} holds at least one key and every key in it is {@code key}'s public key. */
    static boolean only(KeyPair key, List<PublicKey> offered) {
        return !offered.isEmpty() && offered.stream().allMatch(k -> KeyUtils.compareKeys(k, key.getPublic()));
    }

    /** Makes the next read of a file fail, so its round drops the session and the next round logs in again; counts that read. */
    void failTheNextRead(AtomicInteger failed) {
        sftp.onNextReading((session, path) -> {
            failed.incrementAndGet();
            throw new IOException("the test failed the read of " + path);
        });
    }

    @Test
    void credentialsGiveTheUserAndThePasswordAndAnOverwriteIsTheNextSessionsLogin() {
        sftp.login("partner", "first-password");
        AtomicReference<Secret.Credentials> held = new AtomicReference<>(new Secret.Credentials("partner", "first-password"));
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        failTheNextRead(failed);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(rt, endpoint(Map.of("password", credentials(held, reads))), seen);
        assertEquals(0, reads.get(), "built and started: nothing read");

        SftpComponentTest.round(rt);
        assertEquals(1, failed.get(), "the first session logged in as partner and reached the file");
        assertEquals(1, reads.get(), "its login read the credentials once");
        assertTrue(SftpComponentTest.soon(() -> sftp.sessionsClosed() == 1), "the failed read dropped the session");

        sftp.login("partner-2", "second-password");
        held.set(new Secret.Credentials("partner-2", "second-password"));
        SftpComponentTest.round(rt);
        assertEquals(List.of("a.xml"), SftpComponentTest.files(seen), "the second session logged in by the new credentials");
        assertEquals(2, reads.get(), "and read them once");
        assertEquals(2, sftp.sessionsCreated());
    }

    @Test
    void aKeyPairWithNoCertificateIsTheSessionsKeyAndAnOverwriteIsTheNextSessionsKey() throws Exception {
        KeyPair first = SftpConsumerEdgeTest.ecKey();
        KeyPair second = SftpConsumerEdgeTest.ecKey();
        sftp.authorize(first.getPublic());
        AtomicReference<KeyPair> held = new AtomicReference<>(first);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        sftp.drop("/inbox", "k.xml", Buffer.buffer("<k/>"));
        failTheNextRead(failed);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(rt, endpoint(Map.of("user", EmbeddedSftp.USER, "privateKey", keyPair(held, reads))), seen);
        assertEquals(0, reads.get(), "built and started: nothing read");

        SftpComponentTest.round(rt);
        assertEquals(1, failed.get(), "the first session logged in by the first key and reached the file");
        assertEquals(1, reads.get(), "its login read the key pair once");
        assertTrue(only(first, sftp.offeredKeys()), "the key pair was the only key offered");
        assertEquals(0, sftp.passwordChecks(), "and no password");
        assertTrue(SftpComponentTest.soon(() -> sftp.sessionsClosed() == 1), "the failed read dropped the session");

        sftp.authorize(second.getPublic());
        held.set(second);
        SftpComponentTest.round(rt);
        assertEquals(List.of("k.xml"), SftpComponentTest.files(seen), "the second session logged in by the new key, which alone the server takes now");
        assertEquals(2, reads.get());
        List<PublicKey> offered = sftp.offeredKeys();
        int firstOfSecond = offered.size() - 1;
        while (firstOfSecond > 0 && KeyUtils.compareKeys(offered.get(firstOfSecond - 1), second.getPublic())) firstOfSecond--;
        assertTrue(only(first, offered.subList(0, firstOfSecond)) && only(second, offered.subList(firstOfSecond, offered.size())),
                "the second login offered the new key alone");
        assertEquals(Set.of(), sftp.files("/inbox"));
    }

    @Test
    void aUserGivenTogetherWithCredentialsIsRefusedWhenTheConsumerIsBuilt() {
        AtomicInteger reads = new AtomicInteger();
        Inbound both = endpoint(Map.of("user", EmbeddedSftp.USER,
                "password", credentials(new AtomicReference<>(new Secret.Credentials(EmbeddedSftp.USER, EmbeddedSftp.PASSWORD)), reads)));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> component(rt).consumer(both, e -> Future.succeededFuture(new Ack.Accepted(e.id()))));
        assertEquals("connection 'sftp-in': the setting 'user' is given by the credentials of 'password'; leave it out", refused.getMessage());
        assertEquals(0, reads.get(), "decided by the secret's kind, which is no read");
    }

    @Test
    void aWrongHostKeySendsNeitherTheCredentialsNorTheKey() throws Exception {
        String wrong = EmbeddedSftp.fingerprint(EmbeddedSftp.OTHER_KEY);
        sftp.authorize(SftpConsumerEdgeTest.ecKey().getPublic());
        AtomicReference<KeyPair> key = new AtomicReference<>(SftpConsumerEdgeTest.ecKey());
        AtomicReference<Secret.Credentials> login = new AtomicReference<>(new Secret.Credentials(EmbeddedSftp.USER, EmbeddedSftp.PASSWORD));
        sftp.drop("/inbox", "secret.xml", Buffer.buffer("<secret/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        for (Map<String, Object> by : List.of(
                Map.<String, Object>of("password", credentials(login, new AtomicInteger()), "hostKey", wrong),
                Map.<String, Object>of("user", EmbeddedSftp.USER, "privateKey", keyPair(key, new AtomicInteger()), "hostKey", wrong))) {
            FakeRuntime on = FakeRuntime.create();
            SftpComponent component = component(on);
            Consumer consumer = component.consumer(endpoint(by), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
            consumers.add(consumer);
            consumer.start().await();
            SftpComponentTest.brokenRound(on, consumer);
            assertEquals(Health.Status.DOWN, component.health().await().status());
        }
        assertTrue(sftp.sessionsCreated() >= 2, "each consumer did connect: the refusal is the host key's");
        assertEquals(0, sftp.passwordChecks(), "the server was refused before the login: no password left the node");
        assertEquals(List.of(), sftp.offeredKeys(), "and no key was offered");
        assertEquals(List.of(), seen);
        assertEquals(Set.of("secret.xml"), sftp.files("/inbox"));
    }
}
