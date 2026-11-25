package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Runtime;
import dev.rail8.components.sftp.EmbeddedSftp;
import dev.rail8.components.sftp.SftpComponent;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flow from an sftp endpoint without {@code user}, whose {@code password} names credentials in the node's secret store: the
 * deploy takes it, the node's consumer logs in by the credentials' user and password against an embedded SFTP server, and a
 * session after an overwrite of the credentials logs in by the new ones, with no build of the flow in between.
 */
@Timeout(60)
class DeploymentsSftpLoginTest {

    static final Map<String, String> KEY = Map.of("RAIL8_SECRETS_KEY", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");

    @TempDir Path tmp;
    @TempDir Path served;

    final FakeRuntime rt = FakeRuntime.create();
    final MemorySecretStores stores = new MemorySecretStores();
    final Doors doors = new Doors();
    EmbeddedSftp sftp;
    GitRepos repos;
    SecretStore store;
    Deployments deployments;

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(served);
        sftp.mkdir("/inbox");
        repos = new GitRepos(tmp.resolve("repos"));
        Map<String, String> env = new HashMap<>(KEY);
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        env.put("RAIL8_GIT_BASE", repos.base().toString());
        store = stores.start("node-1", env, rt.clock(), rt.timer());
        NodeConfig config = NodeConfig.from(env);
        config = config.withSecrets(config.secrets().withStore(store));
        Map<String, Component> components = Map.of(Doors.SCHEME, doors, "sftp", new SftpComponent(rt.blobs(), rt.ids(), rt.timer(), rt.clock()));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), new InMemoryMessageStore(),
                new InMemoryDlq(), new CountingQueue(rt.clock()), () -> 0L, new InMemoryDeploymentStore());
    }

    @AfterEach
    void down() throws Exception {
        doors.releaseAll();
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
        sftp.close();
    }

    /** {@code drop} 1.0.0: the sync flow {@code feed} from the sftp endpoint {@code drop} without {@code user} to {@code partner}. */
    PackageDraft drop() {
        return PackageDraft.of("drop", "1.0.0")
                .endpoint("drop", "sftp", "in", new JsonObject().put("host", "127.0.0.1").put("port", sftp.port()).put("dir", "/inbox")
                        .put("password", "secret://partner-login").put("hostKey", sftp.hostKey()).put("intervalMs", 1000))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("feed", "1.0.0", "sync", "drop", 4096, PackageDraft.toPartner());
    }

    /**
     * Moves the fake clock to each next task of the node's timer until {@code done}, within a bound of real time; after each move
     * it gives the node's other threads (a lease's answer, the SFTP session) up to 100 ms to act on it before the next one.
     */
    void until(BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, what);
            Duration due = null;
            synchronized (rt.timer()) {   // one read of the timer: a task may be cancelled between two
                if (rt.timer().pending() > 0) due = rt.timer().nextDelay();
            }
            if (due != null) rt.clock().advance(due);
            long settle = System.nanoTime() + Duration.ofMillis(100).toNanos();
            while (!done.getAsBoolean() && System.nanoTime() < settle) Thread.onSpinWait();
        }
    }

    @Test
    void aFlowFromAnSftpEndpointLogsInByTheStoresCredentialsAndANewSessionByTheirOverwrite() throws Exception {
        sftp.login("partner", "first-password");
        MemorySecretStores.createCredentials(store, "partner-login", "partner", "first-password");
        AtomicInteger failed = new AtomicInteger();
        sftp.onNextReading((session, path) -> {   // the first session logs in and reaches the file; its failed read drops it
            failed.incrementAndGet();
            throw new IOException("the test failed the read of " + path);
        });
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        String commit = repos.commit(drop());
        OnVirtualThread.call(() -> deployments.deploy("drop", commit, Deployments.ANY, TestActors.named("test")));

        until(() -> failed.get() == 1 && sftp.sessionsClosed() == 1, "the first session logged in as partner, reached the file and was dropped");

        sftp.login("partner-2", "second-password");
        MemorySecretStores.overwriteCredentials(store, "partner-login", "partner-2", "second-password");
        OnVirtualThread.call(() -> {
            deployments.follow();
            return null;
        });
        until(() -> !doors.partner().sent().isEmpty(), "the next session logged in by the new credentials and delivered the file");
        assertEquals(1, doors.partner().sent().size());
        assertEquals(2, sftp.sessionsCreated(), "two sessions, the flow never built again");
    }
}
