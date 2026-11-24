package dev.rail8.runtime.cache;

import dev.rail8.api.Cache;
import dev.rail8.core.cache.InMemoryCache;
import dev.rail8.core.scope.MessageContext;
import dev.rail8.testkit.CacheContract;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeIds;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * node's clock; a lock is made to lapse with {@code PEXPIRE}, and a value with a short ttl left to lapse, each waited for by
 * polling {@code EXISTS}, never slept. {@code CLIENT PAUSE} holds a node's call back, and {@code maxmemory} fills Valkey; each
 * test that changes them changes them back. A node's own client can also hold back an answer Valkey has given, or refuse
 * every call.
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
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger(), "a ttl of zero keeps nothing on Valkey");
            assertEquals(0, api.zcard(base(pkg) + "z").await().toLong(), "nor an entry in the package's share");
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
    void aRemoveThatValkeyRunsBeforeAnEarlierReadKeepsThatReadsLoadFromBeingKept() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "stale", Duration.ofMinutes(5));
            String held = api.get(lockKey(pkg, "k")).await().toString();
            call(() -> { a.scope(pkg).remove("k"); return null; });
            api.set(List.of(lockKey(pkg, "k"), held, "PX", "10000")).await();   // as if the read had run after the remove
            loading.release.countDown();
            assertEquals("stale", result(loading.call), "its own caller still gets the value it loaded");
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger(), "the load kept nothing");
            assertEquals(0, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger(), "and freed its lock");
            assertEquals("fresh", call(() -> a.scope(pkg).get("k", value("fresh", Duration.ofMinutes(5)))));
        }
    }

    @Test
    void removesOfTheTokenThatFailedOnTwoNodesLoadTheNextOnce() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            assertEquals("token-1", call(() -> a.scope(pkg).get("k", loader)));
            for (Node node : List.of(a, b, a, b)) {
                assertEquals("token-2", call(() -> { node.scope(pkg).remove("k", "token-1"); return node.scope(pkg).get("k", loader); }));
            }
            assertEquals(2, loads.get(), "the token that failed and the next one, each loaded once in the pool");
        }
    }

    @Test
    void aRemoveWithTheValueOnAnotherNodeForgetsTheValueAndItsCharge() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("t1", Duration.ofMinutes(5))));
            call(() -> { b.scope(pkg).remove("k", "t1"); return null; });
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger());
            assertEquals(0, api.zcard(base(pkg) + "z").await().toLong());
            assertNull(api.hget(base(pkg) + "n", STEP + ":k").await());
            assertEquals(0, Long.parseLong(api.hget(base(pkg) + "n", "#").await().toString()));
            assertEquals("t2", call(() -> a.scope(pkg).get("k", value("t2", Duration.ofMinutes(5)))));
        }
    }

    @Test
    void aRemoveWithAValueLeavesAnotherNodesLockAndLoadAlone() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            Parked loading = park(a.scope(pkg), "k", "fresh", Duration.ofMinutes(5));
            call(() -> { b.scope(pkg).remove("k", "stale"); return null; });
            call(() -> { b.scope(pkg).remove("k", "fresh"); return null; });
            assertEquals(1, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger(), "A still holds the lock");
            loading.release.countDown();
            assertEquals("fresh", result(loading.call));
            AtomicInteger loadsOnB = new AtomicInteger();
            assertEquals("fresh", call(() -> b.scope(pkg).get("k", () -> {
                loadsOnB.incrementAndGet();
                return new Cache.Entry("other", Duration.ofMinutes(5));
            })));
            assertEquals(0, loadsOnB.get(), "A's load was kept");
        }
    }

    @Test
    void aRemoveFromANodeWithAnotherMasterKeyMatchesNoValue() throws Exception {
        try (Node a = new Node("node-a"); Node other = new Node("node-b", key(2))) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("t1", Duration.ofMinutes(5))));
            call(() -> { other.scope(pkg).remove("k", "t1"); return null; });
            assertEquals("t1", call(() -> a.scope(pkg).get("k", value("unused", Duration.ofMinutes(5)))), "the digest is keyed");
        }
    }

    @Test
    void aRemoveWithAValueLeavesTheNodesOwnRefreshAndItsWaitersAlone() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("t1", Duration.ofMinutes(5))));
            a.clock.advance(Duration.ofMinutes(5));   // t1 has expired on A's clock, and Valkey still holds it
            Parked loading = park(a.scope(pkg), "k", "fresh", Duration.ofMinutes(5));
            call(() -> { a.scope(pkg).remove("k", "t1"); return null; });
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger(), "Valkey forgot t1");
            assertEquals(1, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger(), "and kept the lock of A's load");
            AtomicInteger loadsLater = new AtomicInteger();
            Waiter later = waiter(() -> a.scope(pkg).get("k", () -> {
                loadsLater.incrementAndGet();
                return new Cache.Entry("other", Duration.ofMinutes(5));
            }));
            until(later::waitsForTheNodesLoad, "the later caller waits for A's load on the node");
            loading.release.countDown();
            assertEquals("fresh", result(loading.call));
            assertEquals("fresh", result(later.call()), "the later caller got the load's value");
            assertEquals(0, loadsLater.get());
            assertEquals("fresh", call(() -> b.scope(pkg).get("k", value("other", Duration.ofMinutes(5)))), "and Valkey kept it");
        }
    }

    @Test
    void aReadThatFoundTheValueBeforeARemoveOfItIsSentAgain() throws Exception {
        HeldClient heldA = new HeldClient();
        try (Node a = new Node("node-a", heldA); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            assertEquals("token-1", call(() -> b.scope(pkg).get("k", loader)));
            Held read = heldA.holdNext();
            CompletableFuture<String> first = async(() -> a.scope(pkg).get("k", loader));
            read.awaitHeld();   // Valkey ran A's read, which found token-1; its answer waits
            call(() -> { b.scope(pkg).remove("k", "token-1"); return null; });   // B forgets token-1 first
            call(() -> { a.scope(pkg).remove("k", "token-1"); return null; });   // and A's remove finds it gone
            Waiter later = waiter(() -> a.scope(pkg).get("k", loader));
            until(later::waitsForTheNodesLoad, "the later caller on A waits for A's read");
            read.release();
            assertEquals("token-2", result(first), "A read again and loaded the next token");
            assertEquals("token-2", result(later.call()), "the later caller got it, not the token it saw fail");
            assertEquals(2, loads.get(), "the next token was loaded once");
        }
    }

    @Test
    void aRemoveOfTheValueDuringAReadSentAgainHasItSentOnceMore() throws Exception {
        HeldClient heldA = new HeldClient();
        try (Node a = new Node("node-a", heldA); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            assertEquals("token-1", call(() -> b.scope(pkg).get("k", loader)));
            Held firstRead = heldA.holdNext();
            CompletableFuture<String> first = async(() -> a.scope(pkg).get("k", loader));
            firstRead.awaitHeld();
            call(() -> { a.scope(pkg).remove("k", "token-0"); return null; });   // another value: token-1 stays, and A reads again
            Held secondRead = heldA.holdNext();
            firstRead.release();
            secondRead.awaitHeld();   // the read sent again found token-1 too
            call(() -> { b.scope(pkg).remove("k", "token-1"); return null; });
            call(() -> { a.scope(pkg).remove("k", "token-1"); return null; });
            Waiter later = waiter(() -> a.scope(pkg).get("k", loader));
            until(later::waitsForTheNodesLoad, "the later caller on A waits for A's read");
            secondRead.release();
            assertEquals("token-2", result(first));
            assertEquals("token-2", result(later.call()), "the later caller got the next token, not the one it saw fail");
            assertEquals(2, loads.get(), "the next token was loaded once");
        }
    }

    @Test
    void aReadSentWhileARemoveOfItsValueIsOnItsWayToValkeyIsSentAgain() throws Exception {
        HeldClient heldA = new HeldClient();
        try (Node a = new Node("node-a", heldA); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            assertEquals("token-1", call(() -> b.scope(pkg).get("k", loader)));
            Held script = heldA.delayNext();
            CompletableFuture<String> removing = async(() -> { a.scope(pkg).remove("k", "token-1"); return null; });
            script.awaitHeld();   // A's remove is on its way to Valkey
            Held read = heldA.holdNext();
            CompletableFuture<String> first = async(() -> a.scope(pkg).get("k", loader));
            read.awaitHeld();   // Valkey ran A's read before the remove, and the read found token-1
            script.release();
            assertNull(result(removing), "the remove returned once Valkey had run it");
            Waiter later = waiter(() -> a.scope(pkg).get("k", loader));
            until(later::waitsForTheNodesLoad, "the later caller on A waits for A's read");
            read.release();
            assertEquals("token-2", result(first), "A read again and loaded the next token");
            assertEquals("token-2", result(later.call()), "the later caller got it, not the token it saw fail");
            assertEquals(2, loads.get(), "the next token was loaded once");
        }
    }

    @Test
    void aRemoveThatForgetsTheValueOnValkeyKeepsTheNodesOutageCopyOfAnother() throws Exception {
        HeldClient heldA = new HeldClient();
        try (Node a = new Node("node-a", heldA); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("token-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            assertEquals("token-1", call(() -> b.scope(pkg).get("k", loader)));
            heldA.cut = true;
            assertEquals("token-2", call(() -> a.scope(pkg).get("k", loader)), "A loads on its own and keeps the value for a minute");
            heldA.cut = false;
            call(() -> { a.scope(pkg).remove("k", "token-1"); return null; });
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger(), "Valkey forgot token-1");
            assertEquals("token-2", call(() -> a.scope(pkg).get("k", loader)), "A kept its copy of token-2");
            assertEquals(2, loads.get());
        }
    }

    @Test
    void aReadWhoseAnswerIsHeldBackStillHandsItsLoadToTheOtherNodes() throws Exception {
        try (Node a = new Node("node-a", 1); Node b = new Node("node-b")) {   // one connection: the write follows the read
            String pkg = freshPackage();
            a.client.send(Request.cmd(Command.PING)).await();   // connected before the pause
            CountDownLatch loaded = new CountDownLatch(1);
            AtomicInteger locksSeen = new AtomicInteger(-1);
            CompletableFuture<String> got;
            try {
                api.client(List.of("PAUSE", "60000", "WRITE")).await();
                got = async(() -> a.scope(pkg).get("k", () -> {
                    locksSeen.set(api.exists(List.of(lockKey(pkg, "k"))).await().toInteger());
                    loaded.countDown();
                    return new Cache.Entry("token", Duration.ofMinutes(5));
                }));
                until(a::calling, "A's read waits for its answer");
                a.clock.advance(ValkeyCache.CALL);
                await(loaded);
            } finally {
                api.client(List.of("UNPAUSE")).await();
            }
            assertEquals("token", result(got));
            assertEquals(0, locksSeen.get(), "A loaded before Valkey ran its read");
            assertEquals(0, api.exists(List.of(lockKey(pkg, "k"))).await().toInteger(), "the lock the read took went with A's write");
            AtomicInteger loadsOnB = new AtomicInteger();
            assertEquals("token", call(() -> b.scope(pkg).get("k", () -> {
                loadsOnB.incrementAndGet();
                return new Cache.Entry("other", Duration.ofMinutes(5));
            })));
            assertEquals(0, loadsOnB.get(), "B got A's value without waiting for a lease to lapse");
            call(() -> { b.scope(pkg).remove("k"); return null; });
            assertEquals("fresh", call(() -> a.scope(pkg).get("k", value("fresh", Duration.ofMinutes(5)))),
                    "A kept no copy of its own: Valkey had taken its value");
        }
    }

    @Test
    void aValueValkeyDidNotTakeAfterAReadWithoutAnswerIsKeptOnTheNodeForAtMostAMinute() throws Exception {
        try (Node a = new Node("node-a", 1)) {   // one connection: the write follows the read
            String pkg = freshPackage();
            a.client.send(Request.cmd(Command.PING)).await();   // connected before the pause
            CompletableFuture<String> got;
            try {
                api.client(List.of("PAUSE", "60000", "WRITE")).await();
                got = async(() -> a.scope(pkg).get("k", () -> {
                    api.client(List.of("UNPAUSE")).await();
                    until(() -> api.exists(List.of(lockKey(pkg, "k"))).await().toInteger() == 1, "Valkey runs the read, which takes the lock");
                    api.del(List.of(lockKey(pkg, "k"))).await();   // the lock lapses before the write
                    return new Cache.Entry("token", Duration.ofMinutes(5));
                }));
                until(a::calling, "A's read waits for its answer");
                a.clock.advance(ValkeyCache.CALL);
                assertEquals("token", result(got));
            } finally {
                api.client(List.of("UNPAUSE")).await();
            }
            assertEquals(0, api.exists(List.of(valueKey(pkg, "k"))).await().toInteger(), "Valkey did not take the value");
            assertEquals("token", call(() -> a.scope(pkg).get("k", value("unused", Duration.ofMinutes(5)))), "the node keeps it");
            a.clock.advance(ValkeyCache.OUTAGE_KEEP);
            assertEquals("again", call(() -> a.scope(pkg).get("k", value("again", Duration.ofMinutes(5)))), "for a minute, not its ttl");
        }
    }

    @Test
    void aFullValkeyCountsAsDownAndRefusesEachScriptWhole() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            Cache c = a.scope(pkg);
            assertEquals("kept", call(() -> c.get("a", value("kept", Duration.ofMinutes(5)))));
            Parked loading = park(c, "b", "late", Duration.ofMinutes(5));
            AtomicInteger loads = new AtomicInteger();
            Cache.Loader loader = () -> new Cache.Entry("loaded-" + loads.incrementAndGet(), Duration.ofMinutes(5));
            try {
                api.config(List.of("SET", "maxmemory", "1")).await();
                assertEquals("loaded-1", call(() -> c.get("a", loader)), "the read is refused: a full Valkey loses its hits");
                assertEquals("loaded-1", call(() -> c.get("a", loader)), "the node keeps what it loaded meanwhile");
                loading.release.countDown();
                assertEquals("late", result(loading.call));
                assertEquals(1, api.exists(List.of(lockKey(pkg, "b"))).await().toInteger(), "the write did not free the lock");
                assertEquals(0, api.exists(List.of(valueKey(pkg, "b"))).await().toInteger(), "nor keep the value");
            } finally {
                api.config(List.of("SET", "maxmemory", "0")).await();
            }
            assertEquals(1, loads.get());
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
    void anEntryIsChargedForItsValueKeyExpirySealedValueDigestAndItsNameInTheBookkeeping() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("v", Duration.ofMinutes(5))));
            String entry = STEP + ":k";
            long expiry = FakeRuntime.T0.plus(Duration.ofMinutes(5)).toEpochMilli();
            long sealed = 16 + "v".length() + 16;   // salt, value, tag
            long digest = 32;
            long charged = valueKey(pkg, "k").length() + Long.toString(expiry).length() + sealed + digest + 2L * entry.length();
            assertEquals(charged, Long.parseLong(api.hget(base(pkg) + "n", entry).await().toString()));
            assertEquals(charged, Long.parseLong(api.hget(base(pkg) + "n", "#").await().toString()));
        }
    }

    @Test
    void aTtlAboveTheLimitLivesOnValkeyForTheLimit() throws Exception {
        try (Node a = new Node("node-a")) {
            String pkg = freshPackage();
            call(() -> a.scope(pkg).get("k", value("v", Duration.ofDays(30))));
            long pttl = api.pttl(valueKey(pkg, "k")).await().toLong();
            assertTrue(pttl <= Cache.MAX_TTL.toMillis() && pttl > Cache.MAX_TTL.minusMinutes(1).toMillis(), "PTTL " + pttl);
        }
    }

    @Test
    void aValueValkeyDropsAfterItsTtlIsLoadedAgainWhileTheClocksStandStill() throws Exception {
        try (Node a = new Node("node-a"); Node b = new Node("node-b")) {
            String pkg = freshPackage();
            assertEquals("first", call(() -> a.scope(pkg).get("k", value("first", Duration.ofMillis(300)))));
            until(() -> api.exists(List.of(valueKey(pkg, "k"))).await().toInteger() == 0, "Valkey drops the value after 300 ms");
            assertEquals("again", call(() -> b.scope(pkg).get("k", value("again", Duration.ofMinutes(5)))));
            assertEquals("again", call(() -> a.scope(pkg).get("k", value("unused", Duration.ofMinutes(5)))));
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
        final Redis client;
        final ValkeyCache caches;

        Node(String id) {
            this(id, MASTER, Redis.createClient(vertx, uri()));
        }

        Node(String id, SecretKey master) {
            this(id, master, Redis.createClient(vertx, uri()));
        }

        Node(String id, Redis client) {
            this(id, MASTER, client);
        }

        /** A node whose client has {@code connections} connections. */
        Node(String id, int connections) {
            this(id, MASTER, Redis.createClient(vertx, new RedisOptions().setConnectionString(uri()).setMaxPoolSize(connections)));
        }

        private Node(String id, SecretKey master, Redis client) {
            this.client = client;
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

        /** Whether the node's only pending task is the bound of a call to Valkey. */
        boolean calling() {
            try {
                return timer.pending() == 1 && timer.nextDelay().equals(ValkeyCache.CALL);
            } catch (IllegalStateException cancelledMeanwhile) {
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
        return waiter(task).call();
    }

    /** A caller on a virtual thread of its own, which the test can see waiting for a load its node runs. */
    private record Waiter(Thread thread, CompletableFuture<String> call) {
        boolean waitsForTheNodesLoad() {
            return Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().equals(InMemoryCache.class.getName()) && frame.getMethodName().equals("await"));
        }
    }

    private static Waiter waiter(Callable<String> task) {
        CompletableFuture<String> done = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                done.complete(task.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return new Waiter(thread, done);
    }

    /**
     * A node's client that can hold its next call back before it reaches Valkey, or hold back the answer to its next call
     * once Valkey has run it, and can refuse every call.
     */
    private static final class HeldClient implements Redis {
        private final Redis client = Redis.createClient(vertx, uri());
        private final AtomicReference<Held> before = new AtomicReference<>();
        private final AtomicReference<Held> after = new AtomicReference<>();
        volatile boolean cut;

        /** Holds the next call back before it reaches Valkey. */
        Held delayNext() {
            Held held = new Held();
            before.set(held);
            return held;
        }

        /** Holds back the answer to the next call once Valkey has run it. */
        Held holdNext() {
            Held held = new Held();
            after.set(held);
            return held;
        }

        @Override
        public Future<Response> send(Request request) {
            if (cut) return Future.failedFuture(new IOException("the test cut the node off Valkey"));
            Promise<Response> answer = Promise.promise();
            Held delayed = before.getAndSet(null);
            if (delayed != null) {
                delayed.holding.countDown();
                delayed.release.thenRun(() -> client.send(request).onComplete(answer::handle));
                return answer.future();
            }
            Held held = after.getAndSet(null);
            Future<Response> sent = client.send(request);
            if (held == null) return sent;
            sent.onComplete(done -> {
                held.holding.countDown();
                held.release.thenRun(() -> answer.handle(done));
            });
            return answer.future();
        }

        @Override
        public Future<RedisConnection> connect() {
            return client.connect();
        }

        @Override
        public Future<List<Response>> batch(List<Request> requests) {
            return client.batch(requests);
        }

        @Override
        public Future<Void> close() {
            return client.close();
        }
    }

    /** One call held back until {@link #release()}: before it reaches Valkey, or its answer once Valkey has run it. */
    private static final class Held {
        private final CountDownLatch holding = new CountDownLatch(1);
        private final CompletableFuture<Void> release = new CompletableFuture<>();

        /** Returns once the call is held: it has reached the client, or Valkey has run it. */
        void awaitHeld() throws InterruptedException {
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the call was not held within 10 s");
        }

        /** Sends the call on, or hands its answer to the caller. */
        void release() {
            release.complete(null);
        }
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
