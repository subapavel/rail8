package dev.rail8.runtime.cache;

import dev.rail8.api.Cache;
import dev.rail8.core.scope.MessageContext;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeIds;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.Ports;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Valkey cache while Valkey does not answer, with no Valkey at all: a port nothing listens on, and a socket that accepts
 * and never answers. The node then loads on its own and keeps the value for its ttl, at most a minute.
 */
class ValkeyCacheTest {

    private static final SecretKey MASTER = new SecretKeySpec(new byte[32], "AES");

    private final Vertx vertx = Vertx.vertx();
    private final FakeClock clock = FakeClock.at(FakeRuntime.T0);
    private final FakeTimer timer = new FakeTimer(clock);

    @AfterEach
    void close() {
        vertx.close().await();
    }

    @Test
    void withValkeyOutOfReachTheNodeLoadsAndKeepsTheValueForItsTtlAtMostAMinute() throws Exception {
        Cache c = cache("redis://127.0.0.1:" + Ports.free());
        AtomicInteger loads = new AtomicInteger();
        Cache.Loader hourly = () -> new Cache.Entry("t" + loads.incrementAndGet(), Duration.ofHours(1));
        assertEquals("t1", onVirtualThread(() -> c.get("k", hourly)));
        assertEquals("t1", onVirtualThread(() -> c.get("k", hourly)), "kept on the node");
        clock.advance(ValkeyCache.OUTAGE_KEEP);
        assertEquals("t2", onVirtualThread(() -> c.get("k", hourly)), "kept a minute, not the hour");

        onVirtualThread(() -> { c.remove("k"); return null; });
        assertEquals("t3", onVirtualThread(() -> c.get("k", hourly)), "a remove forgets what the node kept");

        assertEquals("short", onVirtualThread(() -> c.get("s", () -> new Cache.Entry("short", Duration.ofSeconds(10)))));
        clock.advance(Duration.ofSeconds(10));
        assertEquals("again", onVirtualThread(() -> c.get("s", () -> new Cache.Entry("again", Duration.ofSeconds(10)))), "a shorter ttl is kept");
    }

    @Test
    void withValkeyOutOfReachARemoveWithAValueForgetsWhatTheNodeKeptOnlyWhenItIsThatValue() throws Exception {
        Cache c = cache("redis://127.0.0.1:" + Ports.free());
        AtomicInteger loads = new AtomicInteger();
        Cache.Loader hourly = () -> new Cache.Entry("t" + loads.incrementAndGet(), Duration.ofHours(1));
        assertEquals("t1", onVirtualThread(() -> c.get("k", hourly)));
        Logger logger = Logger.getLogger(ValkeyCache.class.getName());
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            onVirtualThread(() -> { c.remove("k", "t0"); return null; });
            assertEquals("t1", onVirtualThread(() -> c.get("k", hourly)), "another value keeps what the node kept");
            onVirtualThread(() -> { c.remove("k", "t1"); return null; });
            assertEquals("t2", onVirtualThread(() -> c.get("k", hourly)), "the value forgets it");
        } finally {
            logger.removeHandler(handler);
        }
        List<String> warnings = logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains("not removed")).toList();
        assertEquals(2, warnings.size(), warnings.toString());
        for (String warning : warnings) {
            assertTrue(warning.contains("'k'"), warning);
            assertFalse(warning.contains("t0") || warning.contains("t1"), warning);
        }
    }

    @Test
    void aCallValkeyDoesNotAnswerEndsOnTheTimer() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {   // accepts, never answers
            Cache c = cache("redis://127.0.0.1:" + silent.getLocalPort());
            CompletableFuture<String> got = async(() -> c.get("k", () -> new Cache.Entry("loaded", Duration.ofMinutes(5))));
            endCall(got);   // the read
            endCall(got);   // the write of what the node loaded
            assertEquals("loaded", got.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aTenantOrPackageOutsideThePackageNameRuleIsRefusedNamingIt() {
        ValkeyCache caches = new ValkeyCache(Redis.createClient(vertx, "redis://127.0.0.1:" + Ports.free()), MASTER, "a", clock, timer, new FakeIds("o"));
        for (String bad : new String[]{"", "Upper", "a}b", "a/b", "9lives", "a".repeat(65)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> caches.scope(bad, "p", "t"), bad);
            assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> caches.scope(MessageContext.DEFAULT_TENANT, bad, "t"), bad);
        }
        caches.scope(MessageContext.DEFAULT_TENANT, "a".repeat(64), "any step type: here, too");
    }

    /** Waits until a call to Valkey is the one thing on the timer, then moves the clock past its bound. */
    private void endCall(CompletableFuture<?> got) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!callPending()) {
            if (got.isDone()) fail("the get ended before the bound of its call: " + got.join());
            if (System.nanoTime() > deadline) fail("no call to Valkey is pending");
            Thread.onSpinWait();
        }
        clock.advance(ValkeyCache.CALL);
    }

    private boolean callPending() {
        try {
            return timer.pending() == 1 && timer.nextDelay().equals(ValkeyCache.CALL);
        } catch (IllegalStateException cancelledMeanwhile) {   // nothing pending by the time the delay was asked for
            return false;
        }
    }

    private Cache cache(String uri) {
        return new ValkeyCache(Redis.createClient(vertx, uri), MASTER, "a", clock, timer, new FakeIds("o")).scope(MessageContext.DEFAULT_TENANT, "p", "token");
    }

    private static <T> T onVirtualThread(Callable<T> task) throws Exception {
        return async(task).get(10, TimeUnit.SECONDS);
    }

    private static <T> CompletableFuture<T> async(Callable<T> task) {
        CompletableFuture<T> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                done.complete(task.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }
}
