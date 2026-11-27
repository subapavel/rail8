package dev.rail8.runtime.config;

import dev.rail8.api.Health;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StoredEnvironment} over a store whose state a test scripts: what it takes into a snapshot and what it leaves out,
 * the coalesced reads, the order of the listeners, the warnings, the report it writes and writes again, and a store that
 * cannot be read.
 */
class StoredEnvironmentTest {

    static final Instant START = Instant.parse("2026-11-26T10:00:00Z");
    static final Ref API = Ref.connection("acme/api");
    static final Ref MOCKED = Ref.connection("acme/mocked");
    static final Ref ACME = Ref.system("acme");

    final FakeClock clock = FakeClock.at(START);
    final FakeTimer timer = new FakeTimer(clock);
    final Scripted store = new Scripted();
    final List<String> warnings = new CopyOnWriteArrayList<>();
    final StoredEnvironment env = new StoredEnvironment("test", store, "node-a", () -> 3, clock, timer, warnings::add);

    /** A store in memory whose rows, generation and failures a test sets: {@link #state} as the real one computes it. */
    static final class Scripted implements ConfigStore {
        long generation;
        long stamp = 1;
        boolean mocksAllowed;
        final Map<Ref, InForce> rows = new HashMap<>();
        Refused refused = Refused.none();
        Map<Ref, String> refusedKeys = Map.of();
        volatile boolean away;
        volatile boolean reportFails;
        volatile CountDownLatch gate;
        volatile CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        final List<List<Object>> reports = new CopyOnWriteArrayList<>();

        synchronized void put(InForce f) {
            rows.put(f.ref(), f);
            bump();
        }

        /** A new generation with nothing in force changed. */
        synchronized void bump() {
            generation++;
            stamp++;
        }

        @Override
        public State state(Held held) {
            reads.incrementAndGet();
            entered.countDown();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (away) throw new IllegalStateException("the database is away");
            synchronized (this) {
                if (held.generation() == generation && held.stamp() == stamp) {
                    return new State(generation, stamp, mocksAllowed, List.of(), Set.of(), Refused.none(), Map.of());
                }
                List<InForce> changed = new ArrayList<>();
                for (InForce f : rows.values()) {
                    Holding h = held.objects().get(f.ref());
                    if (h == null || h.activation() != f.activation() || !h.stopped().equals(f.stopped())) changed.add(f);
                }
                Set<Ref> gone = new HashSet<>(held.objects().keySet());
                gone.removeAll(rows.keySet());
                return new State(generation, stamp, mocksAllowed, changed, gone, refused, refusedKeys);
            }
        }

        @Override
        public void holds(String node, long epoch, long generation, long stamp, Refused refused) {
            if (reportFails) throw new IllegalStateException("the database is away");
            reports.add(List.of(node, epoch, generation, stamp, refused));
        }

        @Override public ObjectView read(Ref ref) { throw new UnsupportedOperationException(); }
        @Override public Page<ObjectView> list(Query query) { throw new UnsupportedOperationException(); }
        @Override public ObjectView write(Actor actor, Ref ref, Change change) { throw new UnsupportedOperationException(); }
        @Override public void writeMocks(Actor actor, Change.Mocks change) { throw new UnsupportedOperationException(); }
        @Override public Overview overview() { throw new UnsupportedOperationException(); }
        @Override public Page<HistoryRow> history(Ref ref, OptionalLong after, int limit) { throw new UnsupportedOperationException(); }
        @Override public Export export(Scope scope) { throw new UnsupportedOperationException(); }
        @Override public Plan importCheck(Set<Ref.Kind> writes, Scope scope, List<File> files) { throw new UnsupportedOperationException(); }
        @Override public Plan importWrite(Actor actor, Set<Ref.Kind> writes, Scope scope, List<File> files, String expect, Optional<String> note) {
            throw new UnsupportedOperationException();
        }
    }

    static ConfigStore.InForce connection(Ref ref, long activation, JsonObject body, Optional<ConnectionState.Stop> stopped) {
        return new ConfigStore.InForce(ref, activation, ConfigFiles.STANDARD.newest(ref.kind()), body, stopped);
    }

    static JsonObject web(String url) {
        return new JsonObject().put("adapter", "web").put("values", new JsonObject().put("url", url).put("token", "secret://acme.token"));
    }

    static <T> T await(Future<T> f) {
        try {
            return f.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the read failed: " + e, e);
        }
    }

    void tick() {
        clock.advance(StoredEnvironment.TICK);
        try {
            env.ticked().await(20, TimeUnit.SECONDS);
        } catch (Exception failed) {
            // a read that failed is what some tests make
        }
    }

    @Test
    void theSnapshotHoldsValuesAsReferencesInstanceNamesAndMocksOnlyWhereAllowedWithTheReasonOtherwise() {
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        store.put(connection(MOCKED, 12, new JsonObject().put("adapter", "web").put("values", new JsonObject())
                .put("mock", new JsonObject().put("reply", "empty").put("delay", "250ms").put("fail", "retryable")), Optional.empty()));
        store.put(new ConfigStore.InForce(ACME, 13, ConfigFiles.STANDARD.newest(Ref.Kind.SYSTEM), new JsonObject().put("instanceName", "Acme Corp"), Optional.empty()));
        env.start();

        Environment.Snapshot s = env.current();
        ConnectionState.Values api = assertInstanceOf(ConnectionState.Values.class, s.connection("acme/api").orElseThrow());
        assertEquals(11, api.version(), "the connection's version is its activation");
        assertEquals("secret://acme.token", api.values().get("token"), "a secret stays a reference");
        assertEquals(Optional.of("Acme Corp"), s.instanceName("acme"));
        assertEquals(Optional.empty(), s.connection("acme/mocked"));
        assertEquals(Map.of(MOCKED, "it is mocked and the environment does not allow mocks"), s.leftOut());
        assertEquals(List.of("node-a", 3L, store.generation, store.stamp, new ConfigStore.Refused(List.of(MOCKED), 1)), store.reports.getLast(),
                "the node reports what it left out");

        synchronized (store) {
            store.mocksAllowed = true;
            store.generation++;
        }
        await(env.notice());
        ConnectionState.Mocked mocked = assertInstanceOf(ConnectionState.Mocked.class, env.current().connection("acme/mocked").orElseThrow());
        assertEquals(new ConnectionState.MockBehaviour(ConnectionState.MockBehaviour.Reply.EMPTY, Duration.ofMillis(250),
                Optional.of(ConnectionState.MockBehaviour.Failure.RETRYABLE)), mocked.behaviour());
        assertEquals(Map.of(), env.current().leftOut());
    }

    @Test
    void aBodyThatDoesNotVerifyOrOfAFormatThisNodeDoesNotReadIsLeftOutAndTheNodeIsDegradedWhileOneDoesNotVerify() {
        store.put(new ConfigStore.InForce(API, 11, ConfigFiles.STANDARD.newest(Ref.Kind.CONNECTION) + 1, web("https://acme.example"), Optional.empty()));
        store.refused = new ConfigStore.Refused(List.of(Ref.connection("acme/forged")), 1);
        store.refusedKeys = Map.of(Ref.connection("acme/forged"), "00112233aabbccdd");
        env.start();

        assertEquals(Map.of(Ref.connection("acme/forged"), "its binding does not verify: save and activate it again",
                API, "its format 2 is not one this node reads"), env.current().leftOut());
        assertEquals(2, env.current().leftOutCount());
        assertEquals(Health.Status.DEGRADED, env.health().status());
        assertTrue(env.health().detail().contains("do not verify"), env.health().detail());
        store.bump();
        tick();
        assertEquals(1, warnings.stream().filter(w -> w.contains("connection acme/forged") && w.contains("00112233aabbccdd")).count(),
                "a WARNING names the key and the key's id once, over two reads that refuse it: " + warnings);
    }

    @Test
    void moreThanAHundredLeftOutAreCountedAndAHundredNamed() {
        List<Ref> named = new ArrayList<>();
        for (int i = 0; i < 100; i++) named.add(Ref.connection("acme/f" + i));
        store.refused = new ConfigStore.Refused(named, 150);
        store.put(connection(MOCKED, 12, new JsonObject().put("adapter", "web").put("values", new JsonObject())
                .put("mock", new JsonObject().put("reply", "echo")), Optional.empty()));
        env.start();

        assertEquals(100, env.current().leftOut().size());
        assertEquals(151, env.current().leftOutCount());
    }

    @Test
    void eachSnapshotTakenIsToldOnceInOrderAndAReadThatFindsNothingNewTakesNone() {
        List<Long> told = new CopyOnWriteArrayList<>();
        env.onActivation(s -> told.add(s.generation()));
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        env.start();
        await(env.notice());
        tick();
        store.put(connection(API, 14, web("https://acme.example/v2"), Optional.empty()));
        store.put(connection(MOCKED, 15, web("https://other.example"), Optional.empty()));
        await(env.notice());

        assertEquals(List.of(1L, 3L), told, "one snapshot may cover several activations");
        assertEquals(14, env.current().connection("acme/api").orElseThrow().version());
        assertEquals(4, store.reports.size(), "a report after each read, the ones that found nothing new included");
    }

    @Test
    void aGenerationIsReportedHeldOnlyOnceEveryListenerTookItAndAFailedListenerIsCalledAgainAtEachTick() {
        List<Long> took = new CopyOnWriteArrayList<>();
        AtomicInteger failing = new AtomicInteger();
        env.onActivation(s -> took.add(s.generation()));
        env.onActivation(s -> {
            if (s.generation() == 2 && failing.getAndIncrement() < 2) throw new IllegalStateException("the index cannot be built yet");
            took.add(-s.generation());
        });
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        env.start();
        assertEquals(1L, store.reports.getLast().get(2), "every listener took generation 1");

        store.put(connection(API, 12, web("https://acme.example/v2"), Optional.empty()));
        await(env.notice());
        assertEquals(1L, store.reports.getLast().get(2), "a listener did not take generation 2: the node reports the generation all took");
        assertEquals(1, warnings.stream().filter(w -> w.contains("did not take")).count(), warnings.toString());
        assertEquals(12, env.current().connection("acme/api").orElseThrow().version(), "the snapshot itself is swapped");

        tick();
        assertEquals(1L, store.reports.getLast().get(2), "it failed again at the tick");
        tick();
        assertEquals(2L, store.reports.getLast().get(2), "it took the generation at the next tick, and the report says so");
        assertEquals(List.of(1L, -1L, 2L, -2L), took, "the listener that took it is not called again; the one that failed is, until it takes it");
        assertEquals(1, warnings.stream().filter(w -> w.contains("did not take")).count(), "one warning for a generation, not one each tick");
    }

    @Test
    void noticesDuringARunningReadMakeOneMoreRead() throws Exception {
        env.start();
        int before = store.reads.get();
        store.entered = new CountDownLatch(1);
        store.gate = new CountDownLatch(1);
        Future<Void> first = env.notice();
        assertTrue(store.entered.await(20, TimeUnit.SECONDS));
        Future<Void> second = env.notice();
        Future<Void> third = env.notice();
        store.gate.countDown();
        await(first);
        await(second);
        await(third);

        assertEquals(before + 2, store.reads.get(), "the read that ran, and one more for the notices during it");
    }

    @Test
    void aNoticeDuringTheStartsReadMakesOneMoreReadAndLaterNoticesRead() throws Exception {
        store.entered = new CountDownLatch(1);
        store.gate = new CountDownLatch(1);
        Thread starting = Thread.ofVirtual().start(env::start);
        assertTrue(store.entered.await(20, TimeUnit.SECONDS));
        Future<Void> during = env.notice();
        store.gate.countDown();
        starting.join();
        await(during);
        assertEquals(2, store.reads.get(), "the start's read, and one more for the notice during it");

        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        await(env.notice());
        assertEquals(11, env.current().connection("acme/api").orElseThrow().version(), "a later notice reads again");
    }

    @Test
    void aStoreThatGoesBackIsAWarningAndItsStateIsTaken() {
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        store.put(connection(API, 12, web("https://acme.example/v2"), Optional.empty()));
        env.start();
        synchronized (store) {
            store.rows.put(API, connection(API, 11, web("https://acme.example"), Optional.empty()));
            store.generation = 1;
            store.stamp = 2;
        }
        await(env.notice());
        assertEquals(11, env.current().connection("acme/api").orElseThrow().version());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("went back") && w.contains("generation 1")), warnings.toString());

        warnings.clear();
        synchronized (store) {
            store.stamp = 99;
        }
        await(env.notice());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("went back")), "the same generation with another stamp: " + warnings);
    }

    @Test
    void aStoreThatCannotBeReadLeavesTheLastGoodSnapshotIsDegradedAfterThirtySecondsAndAStopReachesTheNodeAtItsNextRead() {
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        env.start();
        Environment.Snapshot good = env.current();
        Instant lastRead = clock.instant();
        store.away = true;
        for (int i = 0; i < 5; i++) tick();
        assertEquals(good, env.current(), "the node sends by its last good snapshot");
        assertEquals(Health.Status.UP, env.health().status(), "25 s without a read");
        assertEquals(1, warnings.stream().filter(w -> w.contains("could not be read")).count(), "one warning for the streak");
        tick();
        Health degraded = env.health();
        assertEquals(Health.Status.DEGRADED, degraded.status(), "30 s without a read");
        assertTrue(degraded.detail().contains(lastRead.toString()), "names its last read: " + degraded.detail());

        ConnectionState.Stop stop = new ConnectionState.Stop("operator", clock.instant());
        store.put(connection(API, 11, web("https://acme.example"), Optional.of(stop)));
        store.away = false;
        tick();
        assertEquals(Optional.of(stop), env.current().connection("acme/api").orElseThrow().stopped(), "the stop committed meanwhile");
        assertEquals(Health.Status.UP, env.health().status());
    }

    @Test
    void aReportThatFailsIsAWarningAndIsWrittenAgainAtTheNextRead() {
        store.reportFails = true;
        store.put(connection(API, 11, web("https://acme.example"), Optional.empty()));
        env.start();
        assertTrue(store.reports.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("could not be written")), warnings.toString());
        store.reportFails = false;
        tick();
        assertEquals(1, store.reports.size(), "the next tick writes it");
        tick();
        assertEquals(2, store.reports.size(), "and every read writes it again, so the row says when the node last read the store");
    }

    @Test
    void aFirstReadThatFailsFailsTheStartAndAClosedEnvironmentTicksNoMore() {
        store.away = true;
        assertThrows(IllegalStateException.class, env::start);
        assertFalse(timer.pending() > 0, "no tick armed by a start that failed");
        store.away = false;
        env.start();
        env.close();
        int reads = store.reads.get();
        clock.advance(StoredEnvironment.TICK);
        assertEquals(reads, store.reads.get());
    }
}
