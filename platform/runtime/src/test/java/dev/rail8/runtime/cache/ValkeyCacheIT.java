package dev.rail8.runtime.cache;

import dev.rail8.api.Cache;
import dev.rail8.core.scope.MessageContext;
import dev.rail8.testkit.CacheContract;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeIds;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Valkey cache against valkey/valkey:8: the cache contract on one node, then two nodes over one Valkey, each with its
 * own client, clock and timer. Every test works in a package of its own, so no test sees another's keys. A node waiting for
 * another node's load is seen when its only pending timer task is a pause of a poll, and the pause is ended by moving that
 * node's clock; a lock is made to lapse with {@code PEXPIRE} and waited for by polling {@code EXISTS}, never slept.
 */
class ValkeyCacheIT extends CacheContract {

    private static final String TENANT = MessageContext.DEFAULT_TENANT;
    private static final String STEP = "token";
    private static final SecretKey MASTER = key(1);
    private static final long PATIENCE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicInteger PACKAGES = new AtomicInteger();

    @SuppressWarnings("resource")   // stopped in stop()
    static final GenericContainer<?> valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8")).withExposedPorts(6379);
    static Vertx vertx;
    static Redis redis;
    static RedisAPI api;

    private FakeClock clock;

    @BeforeAll
    static void start() {
        valkey.start();
        vertx = Vertx.vertx();
        redis = Redis.createClient(vertx, uri());
        api = RedisAPI.api(redis);
    }

    @AfterAll
    static void stop() {
        redis.close().await();
        vertx.close().await();
        valkey.stop();
    }

    @Override
    protected Cache cache() {
        clock = FakeClock.at(FakeRuntime.T0);
        return new ValkeyCache(redis, MASTER, "node-a", clock, new FakeTimer(clock), new FakeIds("o")).scope(TENANT, freshPackage(), STEP);
    }

    @Override
    protected void advance(Duration by) {
        clock.advance(by);
    }

    @Test
    void aKeyIsLoadedOnceAcrossTheNodes() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "token", Duration.ofMinutes(5));
            AtomicInteger loadsOnB = new AtomicInteger();
            CompletableFuture<String> waiting = async(() -> b.scope(pkg).get("k", () -> {
                loadsOnB.incrementAndGet();
                return new Cache.Entry("other", Duration.ofMinutes(5));
            }));
            b.awaitPoll();
            b.endPause();
            b.awaitPoll();   // asked again, still loading on A
            loading.release.countDown();
            assertEquals("token", result(loading.call));
            b.endPause();
            assertEquals("token", result(waiting), "B got A's value");
            assertEquals(0, loadsOnB.get(), "B never loaded");
        }
    }

    @Test
    void aLoadWhoseNodeStoppedIsTakenOverOnceItsLockLapses() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "from-a", Duration.ofMinutes(5));   // A's clock never moves: it never renews
            CompletableFuture<String> waiting = async(() -> b.scope(pkg).get("k", value("from-b", Duration.ofMinutes(5))));
            b.awaitPoll();
            api.pexpire(List.of(lockKey(pkg, "k"), "1")).await();
            until(() -> api.exists(List.of(lockKey(pkg, "k"))).await().toInteger() == 0, "the lock lapses");
            b.endPause();
            assertEquals("from-b", result(waiting), "B took the lock over and loaded");
            loading.release.countDown();
            assertEquals("from-a", result(loading.call), "A's caller still gets what A loaded");
            assertEquals("from-b", call(() -> a.scope(pkg).get("k", value("unused", Duration.ofMinutes(5)))),
                    "A's load, whose lock had lapsed, kept nothing");
        }
    }

    @Test
    void theNodeRunningALoadRenewsItsLock() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "v", Duration.ofMinutes(5));
            api.pexpire(List.of(lockKey(pkg, "k"), "5000")).await();
            a.clock.advance(ValkeyCache.RENEW);
            until(() -> api.pttl(lockKey(pkg, "k")).await().toLong() > 5_000, "the lock is renewed to the lease");
            assertTrue(api.pttl(lockKey(pkg, "k")).await().toLong() <= ValkeyCache.LEASE.toMillis());
            until(() -> a.timer.pending() == 1 && a.timer.nextDelay().equals(ValkeyCache.RENEW), "the next renewal is due in " + ValkeyCache.RENEW);
            loading.release.countDown();
            assertEquals("v", result(loading.call));
            assertEquals(0, a.timer.pending(), "the renewals stop with the load");
            assertEquals(0, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger(), "the lock is gone with the write");
        }
    }

    @Test
    void aCallerWaitsForAnotherNodesLoadForABoundedTimeAndThenFailsNamingTheKey() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "late", Duration.ofMinutes(5));
            CompletableFuture<String> waiting = async(() -> b.scope(pkg).get("k", value("unused", Duration.ofMinutes(5))));
            for (int polls = 0; !waiting.isDone(); polls++) {
                if (polls > 1_000) fail("still waiting after 1,000 polls");
                until(() -> waiting.isDone() || b.polling(), "B polls or gives up");
                if (!waiting.isDone()) b.endPause();
            }
            IllegalStateException e = assertInstanceOf(IllegalStateException.class, failure(waiting));
            assertTrue(e.getMessage().contains("'k'") && e.getMessage().contains(ValkeyCache.MAX_WAIT.toString()), e.getMessage());
            Duration waited = Duration.between(FakeRuntime.T0, b.clock.instant());
            assertTrue(waited.compareTo(ValkeyCache.MAX_WAIT) >= 0 && waited.compareTo(ValkeyCache.MAX_WAIT.plus(ValkeyCache.LAST_POLL)) <= 0,
                    "gave up after " + waited);
            loading.release.countDown();
            assertEquals("late", result(loading.call));
        }
    }

    @Test
    void aCallerWhoseWaitedForLoadFailedGetsAnIllegalStateNamingTheKeyWithoutACause() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            IOException refused = new IOException("the token endpoint refused");
            CompletableFuture<String> failing = async(() -> a.scope(pkg).get("k", () -> {
                entered.countDown();
                release.await();
                throw refused;
            }));
            await(entered);
            CompletableFuture<String> waiting = async(() -> b.scope(pkg).get("k", value("unused", Duration.ofMinutes(5))));
            b.awaitPoll();
            release.countDown();
            assertSame(refused, failure(failing));
            b.endPause();
            IllegalStateException e = assertInstanceOf(IllegalStateException.class, failure(waiting));
            assertTrue(e.getMessage().contains("'k'"), e.getMessage());
            assertNull(e.getCause(), "the other node's failure does not travel");
            assertEquals("fresh", call(() -> b.scope(pkg).get("k", value("fresh", Duration.ofMinutes(5)))), "a later caller loads");
        }
    }

    @Test
    void aCallerWhoseWaitedForLoadKeptNothingLoadsItself() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "once", Duration.ZERO);
            CompletableFuture<String> waiting = async(() -> b.scope(pkg).get("k", value("own", Duration.ofMinutes(5))));
            b.awaitPoll();
            loading.release.countDown();
            assertEquals("once", result(loading.call));
            b.endPause();
            assertEquals("own", result(waiting));
        }
    }

    @Test
    void aRemoveOnAnotherNodeDuringALoadKeepsTheLoadFromBeingKept() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "stale", Duration.ofMinutes(5));
            call(() -> { b.scope(pkg).remove("k"); return null; });
            loading.release.countDown();
            assertEquals("stale", result(loading.call), "its own caller still gets the value it loaded");
            assertEquals("fresh", call(() -> b.scope(pkg).get("k", value("fresh", Duration.ofMinutes(5)))));
            assertEquals("fresh", call(() -> a.scope(pkg).get("k", value("other", Duration.ofMinutes(5)))));
        }
    }

    @Test
    void aPackageKeepsAtMostItsShareOfEntriesAndTheOneNearestItsExpiryGoesFirst() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            Cache c = a.scope(pkg);
            call(() -> c.get("soon", value("s", Duration.ofMinutes(1))));
            for (int i = 0; i < ValkeyCache.SHARE_ENTRIES; i++) {
                String key = "k" + i;
                call(() -> c.get(key, value("v", Duration.ofMinutes(10))));
            }
            assertEquals(ValkeyCache.SHARE_ENTRIES, api.zcard(base(pkg) + "z").await().toLong());
            assertEquals(0, api.exists(List.of(valueKey(pkg, "soon"))).await().toInteger(), "the entry nearest its expiry went");
            assertEquals(1, api.exists(List.of(valueKey(pkg, "k0"))).await().toInteger());
            assertEquals("again", call(() -> c.get("soon", value("again", Duration.ofMinutes(1)))));
        }
    }

    @Test
    void aPackageKeepsAtMostItsShareOfBytes() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            Cache c = a.scope(pkg);
            String largest = "x".repeat((int) Cache.MAX_VALUE_BYTES);
            int written = (int) (ValkeyCache.SHARE_BYTES / Cache.MAX_VALUE_BYTES) + 2;
            for (int i = 0; i < written; i++) {
                String key = "big" + i;
                call(() -> c.get(key, value(largest, Duration.ofMinutes(10))));
            }
            long total = Long.parseLong(api.hget(base(pkg) + "n", "#").await().toString());
            long kept = api.zcard(base(pkg) + "z").await().toLong();
            assertTrue(total <= ValkeyCache.SHARE_BYTES && total > ValkeyCache.SHARE_BYTES - 2 * Cache.MAX_VALUE_BYTES, "bytes kept: " + total);
            assertTrue(kept < written, "entries kept: " + kept);
            assertEquals(0, api.exists(List.of(valueKey(pkg, "big0"))).await().toInteger(), "the first written went");
            assertEquals(1, api.exists(List.of(valueKey(pkg, "big" + (written - 1)))).await().toInteger());
        }
    }

    @Test
    void aValueLivesOnValkeyForItsTtlAndItsLockIsGone() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("v", Duration.ofMillis(45_678))));
            long pttl = api.pttl(valueKey(pkg, "k")).await().toLong();
            assertTrue(pttl > 40_000 && pttl <= 45_678, "PTTL " + pttl);
            assertEquals(0, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger());
            assertTrue(api.pttl(base(pkg) + "z").await().toLong() > Cache.MAX_TTL.toMillis(), "the bookkeeping outlives every entry");
        }
    }

    @Test
    void nothingOnValkeyShowsTheValue() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            String secret = "token-that-must-not-show-on-valkey";
            call(() -> a.scope(pkg).get("k", value(secret, Duration.ofMinutes(5))));
            byte[] everything = dump(base(pkg) + "*");
            assertTrue(everything.length > secret.length(), "the package's keys were read");
            assertFalse(contains(everything, secret.getBytes(StandardCharsets.UTF_8)), "a key of the package shows the value");
        }
    }

    @Test
    void aValueThatDoesNotOpenIsLoadedAgain() throws Exception {
        try (Node a = new Node("node-a"); Node other = new Node("node-b", key(2))) {
            String pkg = freshPackage();
            assertEquals("a", call(() -> a.scope(pkg).get("k", value("a", Duration.ofMinutes(5)))));
            assertEquals("other", call(() -> other.scope(pkg).get("k", value("other", Duration.ofMinutes(5)))), "another master key");

            assertEquals("b", call(() -> a.scope(pkg).get("b", value("b", Duration.ofMinutes(5)))));
            api.copy(List.of(valueKey(pkg, "b"), valueKey(pkg, "moved"))).await();
            assertEquals("loaded", call(() -> a.scope(pkg).get("moved", value("loaded", Duration.ofMinutes(5)))), "a value moved to another key");

            api.hset(List.of(valueKey(pkg, "b"), "x", Long.toString(FakeRuntime.T0.plus(Duration.ofHours(1)).toEpochMilli()))).await();
            assertEquals("later", call(() -> a.scope(pkg).get("b", value("later", Duration.ofMinutes(5)))), "a value given a later expiry");
        }
    }

    /** One node of the pool: its own client, clock and timer. */
    private static final class Node implements AutoCloseable {
        final FakeClock clock = FakeClock.at(FakeRuntime.T0);
        final FakeTimer timer = new FakeTimer(clock);
        final Redis client = Redis.createClient(vertx, uri());
        final ValkeyCache caches;

        Node(String id) {
            this(id, MASTER);
        }

        Node(String id, SecretKey master) {
            caches = new ValkeyCache(client, master, id, clock, timer, new FakeIds(id + "-"));
        }

        Cache scope(String pkg) {
            return caches.scope(TENANT, pkg, STEP);
        }

        /** Whether the node's only pending task is the pause of a caller waiting for another node's load. */
        boolean polling() {
            try {
                return timer.pending() == 1 && timer.nextDelay().compareTo(ValkeyCache.LAST_POLL) <= 0;
            } catch (IllegalStateException cancelledMeanwhile) {   // the bound of a call that has just been answered
                return false;
            }
        }

        void awaitPoll() {
            until(this::polling, "the node polls for another node's load");
        }

        /** Ends the pause: the waiting caller asks Valkey again. */
        void endPause() {
            clock.advance(timer.nextDelay());
        }

        @Override
        public void close() {
            client.close().await();
        }
    }

    /** A caller on its own virtual thread whose loader is parked until {@code release}. */
    private record Parked(CompletableFuture<String> call, CountDownLatch release) {}

    private static Parked park(Cache cache, String key, String value, Duration ttl) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> call = async(() -> cache.get(key, () -> {
            entered.countDown();
            release.await();
            return new Cache.Entry(value, ttl);
        }));
        await(entered);
        return new Parked(call, release);
    }

    private static CompletableFuture<String> async(Callable<String> task) {
        CompletableFuture<String> done = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                done.complete(task.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    private static String result(CompletableFuture<String> call) throws Exception {
        return call.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<String> call) throws Exception {
        try {
            String value = call.get(10, TimeUnit.SECONDS);
            throw new AssertionError("the call returned " + value + " instead of failing");
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the loader did not start within 10 s");
    }

    private static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + PATIENCE_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail(what + ": not within 10 s");
            Thread.onSpinWait();
        }
    }

    /** The contents of every key matching {@code pattern}, as one run of bytes. */
    private static byte[] dump(String pattern) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Response name : api.keys(pattern).await()) {
            String key = name.toString();
            switch (api.type(key).await().toString()) {
                case "hash" -> {
                    for (Response field : api.hkeys(key).await()) {
                        out.write(field.toBytes());
                        out.write(api.hget(key, field.toString()).await().toBytes());
                    }
                }
                case "zset" -> {
                    for (Response member : api.zrange(List.of(key, "0", "-1")).await()) out.write(member.toBytes());
                }
                case "string" -> out.write(api.get(key).await().toBytes());
                default -> fail("an unexpected key type for " + key);
            }
        }
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return true;
        }
        return false;
    }

    private static String freshPackage() {
        return "p" + PACKAGES.incrementAndGet();
    }

    private static String base(String pkg) {
        return ValkeyCache.PREFIX + "{" + TENANT + "/" + pkg + "}:";
    }

    private static String valueKey(String pkg, String key) {
        return base(pkg) + "v:" + STEP + ":" + key;
    }

    private static String lockKey(String pkg, String key) {
        return base(pkg) + "l:" + STEP + ":" + key;
    }

    private static SecretKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new SecretKeySpec(bytes, "AES");
    }

    private static String uri() {
        return "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
    }
}
