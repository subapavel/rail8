package dev.rail8.runtime.node;

import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A pooled connection to Postgres whose server went silent after a statement was sent is given up after
 * {@code RAIL8_PG_IDLE_TIMEOUT}: the statement's caller gets a failure, and the pool serves its next caller on a fresh
 * connection. The node's pools are built by {@link Node#pool}; here one of a single connection runs through a proxy that the
 * test silences: from then on it forwards nothing on the connections already open, as a cut network or a frozen host does,
 * while a connection opened later passes. The bound is the client's own timer, so the test runs on real time, with a bound of
 * 300 ms and a deadline far above it.
 */
class NodePoolSilenceIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final Duration IDLE_TIMEOUT = Duration.ofMillis(300);
    static final long DEADLINE_SECONDS = 20;
    static Vertx vertx;

    @BeforeAll
    static void start() { pg.start(); vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    @Test
    void aStatementOnASilentConnectionFailsAndThePoolServesTheNextCaller() throws Exception {
        try (SilenceableProxy proxy = new SilenceableProxy(pg.getHost(), pg.getMappedPort(5432))) {
            NodeConfig.Pg config = new NodeConfig.Pg("127.0.0.1", proxy.port(), pg.getDatabaseName(), pg.getUsername(), pg.getPassword(), 2,
                    NodeConfig.Pg.SslMode.DISABLE, Optional.empty(), NodeConfig.Pg.Durability.LOCAL, 1, Duration.ofSeconds(DEADLINE_SECONDS), 8,
                    IDLE_TIMEOUT);
            Pool pool = Node.pool(vertx, config, Node.connectOptions(config, "work", "node-1"), 1);
            try {
                assertEquals(1, select(pool, 1), "witness: the pool's one connection answers through the proxy");

                proxy.silenceOpenConnections();
                ExecutionException failed = assertThrows(ExecutionException.class, () -> pool.query("select 2").execute()
                        .toCompletionStage().toCompletableFuture().get(DEADLINE_SECONDS, TimeUnit.SECONDS),
                        "the statement sent on the silent connection fails within the deadline, it does not wait for it");

                assertEquals(3, select(pool, 3), "the pool's next caller is served on a fresh connection after " + failed.getCause());
            } finally {
                pool.close().await();
            }
        }
    }

    private static int select(Pool pool, int value) throws Exception {
        return pool.query("select " + value).execute().toCompletionStage().toCompletableFuture().get(DEADLINE_SECONDS, TimeUnit.SECONDS)
                .iterator().next().getInteger(0);
    }

    /**
     * A TCP proxy on a loopback port to one upstream. {@link #silenceOpenConnections} makes every connection open at that moment
     * drop what either side sends from then on; a connection accepted later forwards as before.
     */
    static final class SilenceableProxy implements AutoCloseable {

        private final ServerSocket server;
        private final List<Link> links = new CopyOnWriteArrayList<>();

        SilenceableProxy(String upstreamHost, int upstreamPort) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        Link link = new Link(client, new Socket(upstreamHost, upstreamPort));
                        links.add(link);
                        link.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        void silenceOpenConnections() {
            links.forEach(link -> link.silent = true);
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Link link : links) link.close();
        }

        private static final class Link {
            private final Socket client;
            private final Socket upstream;
            volatile boolean silent;

            Link(Socket client, Socket upstream) {
                this.client = client;
                this.upstream = upstream;
            }

            void start() {
                Thread.ofVirtual().start(() -> pump(client, upstream));
                Thread.ofVirtual().start(() -> pump(upstream, client));
            }

            /** Copies {@code from} to {@code to} until either closes; once silent, reads on and forwards nothing. */
            private void pump(Socket from, Socket to) {
                byte[] buffer = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    for (int n; (n = in.read(buffer)) >= 0; ) {
                        if (!silent) {
                            out.write(buffer, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException closed) {
                    // either side went away: the link ends
                } finally {
                    close();
                }
            }

            void close() {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // already closed
                }
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
    }
}
