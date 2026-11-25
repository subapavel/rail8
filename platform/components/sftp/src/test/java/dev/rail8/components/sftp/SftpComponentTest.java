package dev.rail8.components.sftp;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.Consumer;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Secret;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What only the real sftp consumer does (D-076), against an embedded MINA SSHD server: two nodes on one endpoint lose
 * nothing, a dropped session or a failed read keeps the file, the size and age rules, the error and move directories,
 * the host key, a partner restart, the props, the options and the transport's own resources.
 */
@Timeout(60)
class SftpComponentTest {

    @TempDir
    Path root;

    EmbeddedSftp sftp;
    final FakeRuntime rt = FakeRuntime.create();
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
    private final List<Promise<Ack>> held = new CopyOnWriteArrayList<>();

    @BeforeEach
    void up() throws IOException {
        sftp = EmbeddedSftp.start(root);
        sftp.mkdir("/inbox");
    }

    @AfterEach
    void down() throws Exception {
        held.forEach(p -> p.tryComplete(new Ack.Nack("test cleanup", true)));
        for (Consumer c : consumers) c.stop().otherwiseEmpty().await(10, TimeUnit.SECONDS);
        sftp.close();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    static SftpComponent component(FakeRuntime on) { return new SftpComponent(on.blobs(), on.ids(), on.timer(), on.clock()); }

    Inbound inbox(Map<String, Object> extra) { return sftp.endpoint("sftp-in", "/inbox", extra); }

    Consumer started(SftpComponent component, Inbound cfg, Function<Envelope, Future<Ack>> handler) {
        Consumer c = component.consumer(cfg, handler);
        consumers.add(c);
        c.start().await();
        return c;
    }

    /** A handler recording into {@code seen} and answering Accepted. */
    static Function<Envelope, Future<Ack>> accepting(List<Envelope> seen) {
        return e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        };
    }

    Promise<Ack> hold() {
        Promise<Ack> p = Promise.promise();
        held.add(p);
        return p;
    }

    static String file(Envelope e) { return e.get(SftpComponent.FILE).orElse("<no sftp.file>"); }

    static List<String> files(List<Envelope> seen) { return seen.stream().map(SftpComponentTest::file).toList(); }

    static boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /** Waits until {@code on}'s consumer has its next round scheduled, then moves the fake clock to it. */
    static void fire(FakeRuntime on) {
        assertTrue(soon(() -> on.timer().pending() > 0), "a round is scheduled");
        on.clock().advance(on.timer().nextDelay());
    }

    /** Fires the next round and waits until it is over: the round after it is scheduled (fixed delay after the round ends). */
    static void round(FakeRuntime on) {
        fire(on);
        assertTrue(soon(() -> on.timer().pending() > 0), "the round ended: the next one is scheduled");
    }

    /** Fires the next round and waits until it broke {@code consumer}: it could not connect or list, and no round follows it. */
    static Throwable brokenRound(FakeRuntime on, Consumer consumer) {
        fire(on);
        assertTrue(soon(() -> consumer.broken().isComplete()), "the round broke the consumer");
        assertTrue(consumer.broken().failed(), "a break fails, it never succeeds");
        assertTrue(soon(() -> on.timer().pending() == 0), "no round follows a break");
        return consumer.broken().cause();
    }

    /** What the node does after a break: stops the consumer, then starts a fresh one of the same endpoint. */
    Consumer fresh(SftpComponent component, Consumer broken, Inbound cfg, Function<Envelope, Future<Ack>> handler) throws Exception {
        consumers.remove(broken);
        broken.stop().await(10, TimeUnit.SECONDS);
        return started(component, cfg, handler);
    }

    /** The server recorded {@code first}, and before the first event that starts with {@code then}. */
    static void assertBefore(List<String> events, String first, String then, String why) {
        List<String> seen = List.copyOf(events);
        int a = seen.indexOf(first);
        int b = java.util.stream.IntStream.range(0, seen.size()).filter(i -> seen.get(i).startsWith(then)).findFirst().orElse(-1);
        assertTrue(a >= 0 && b >= 0 && a < b, why + ": '" + first + "' at " + a + ", '" + then + "...' at " + b + " in " + seen);
    }

    static Health.Status health(SftpComponent component) {
        try {
            return component.health().await(10, TimeUnit.SECONDS).status();
        } catch (java.util.concurrent.TimeoutException pending) {
            throw new AssertionError("health() did not answer", pending);
        }
    }

    /** Every dropped file is in the ledger of durable saves or still on the server: nothing lost, at any step. */
    void assertNothingLost(Set<String> dropped, Set<String> ledger, String step) {
        Set<String> kept = new TreeSet<>(ledger);
        kept.addAll(sftp.files("/inbox"));
        assertTrue(kept.containsAll(dropped), step + ": every dropped file is saved or still on the server: " + kept);
    }

    /** A BlobStore that records every write before the store underneath does it. */
    static final class SpyBlobStore implements BlobStore {
        record Write(BlobKey key, long maxSize) {}

        private final BlobStore store;
        final List<Write> writes = new CopyOnWriteArrayList<>();

        SpyBlobStore(BlobStore store) { this.store = store; }

        List<BlobKey> keys() { return writes.stream().map(Write::key).toList(); }

        @Override public long treeLimit() { return store.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
            writes.add(new Write(key, maxSize));
            return store.write(key, whole, maxSize);
        }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            writes.add(new Write(key, maxSize));
            return store.write(key, in, maxSize);
        }

        @Override public ReadStream<Buffer> open(BlobKey key) { return store.open(key); }

        @Override public Future<Buffer> read(BlobKey key) { return store.read(key); }

        @Override public Future<Void> persist(BlobKey key) { return store.persist(key); }
    }

    // ---- two nodes, one endpoint ----------------------------------------------------------------------------------------

    @Test
    void aConsumerKilledAfterTheSaveLeavesTheFileForTheNextNode() {
        Set<String> dropped = Set.of("f1.xml", "f2.xml");
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        Set<String> ledger = ConcurrentHashMap.newKeySet();   // the durable saves: what a handler took over before it answered
        List<Envelope> onA = new CopyOnWriteArrayList<>();
        List<Envelope> onB = new CopyOnWriteArrayList<>();
        Promise<Ack> aNeverAnswers = hold();
        Inbound in = inbox(Map.of());
        started(component(rt), in, e -> {
            onA.add(e);
            ledger.add(file(e));
            return "f1.xml".equals(file(e)) ? aNeverAnswers.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        fire(rt);
        assertTrue(soon(() -> onA.size() == 1), "node A delivered the oldest file: " + files(onA));
        assertNothingLost(dropped, ledger, "A saved f1 and holds its answer");

        // A is killed after the save: its answer never comes. Node B, a fresh component, polls the same endpoint.
        FakeRuntime nodeB = FakeRuntime.create();
        started(component(nodeB), in, e -> {
            onB.add(e);
            ledger.add(file(e));
            return "f1.xml".equals(file(e)) ? Future.succeededFuture(new Ack.Accepted(e.id())) : Future.succeededFuture(new Ack.Nack("node B is full", true));
        });
        fire(nodeB);
        assertTrue(soon(() -> onB.size() == 2), "node B delivered the file A holds, then the next one: " + files(onB));
        assertEquals(List.of("f1.xml", "f2.xml"), files(onB));
        assertTrue(soon(() -> !sftp.files("/inbox").contains("f1.xml")), "B's Accepted removed f1: " + sftp.files("/inbox"));
        assertNothingLost(dropped, ledger, "B accepted f1 and refused f2 for now");

        aNeverAnswers.complete(new Ack.Accepted(onA.get(0).id()));   // A was not dead after all: its answer comes late
        assertTrue(soon(() -> onA.size() == 2),
                "A's delete found no file and counted it done: its round went on to the next file without another tick: " + files(onA));
        assertEquals("f2.xml", file(onA.get(1)));
        assertTrue(soon(() -> sftp.files("/inbox").isEmpty()), "A accepted f2: removed: " + sftp.files("/inbox"));
        assertNothingLost(dropped, ledger, "both answered");
        assertEquals(dropped, ledger, "every dropped file was saved");
    }

    // ---- failures that keep the file ----------------------------------------------------------------------------------

    @Test
    void aSessionDroppedOnTheDeleteKeepsTheFile() {
        sftp.drop("/inbox", "order.xml", Buffer.buffer("<order/>"));
        sftp.onNextRemoving((session, path) -> {
            session.close(true);
            throw new IOException("the test dropped the session on the delete");
        });
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("order.xml"), files(seen), "delivered and accepted");
        assertTrue(sftp.events.contains("remove /inbox/order.xml"), "the accept asked for the delete: " + sftp.events);
        assertEquals(Set.of("order.xml"), sftp.files("/inbox"), "the delete never happened: the file stays for the next round");
        round(rt);
        assertEquals(List.of("order.xml", "order.xml"), files(seen), "the next round delivers it again");
        assertNotEquals(seen.get(0).id(), seen.get(1).id(), "a new message: nothing recognises the file delivered before");
        assertEquals(Set.of(), sftp.files("/inbox"), "accepted again, deleted this time");
        assertEquals(2, sftp.sessionsCreated(), "the dropped session was replaced by a new one");
    }

    @Test
    void anIoExceptionWhileReadingEndsTheRoundAndDropsTheSession() {
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.drop("/inbox", "f2.xml", Buffer.buffer("<two/>"));
        sftp.onNextReading((session, path) -> { throw new IOException("the test failed the read of " + path); });
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of(), files(seen), "the failed read ended the round: nothing delivered, f2 not taken");
        assertEquals(Set.of("f1.xml", "f2.xml"), sftp.files("/inbox"), "both files stay");
        assertTrue(soon(() -> sftp.sessionsClosed() == 1), "the session was dropped after the IOException (the server kept it open)");
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml"), files(seen), "the next round, on a new session, delivers both");
        assertEquals("<one/>", seen.get(0).body().whole().toString(), "intact");
        assertEquals(2, sftp.sessionsCreated(), "one new session");
    }

    // ---- size, age, error and move directories -----------------------------------------------------------------------

    @Test
    void aFileAboveMaxBodyBytesGoesToErrorDirUnread() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "big.bin", Buffer.buffer("x".repeat(101)));
        sftp.drop("/inbox", "exact.bin", Buffer.buffer("y".repeat(100)));
        SpyBlobStore spy = new SpyBlobStore(rt.blobs());
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(new SftpComponent(spy, rt.ids(), rt.timer(), rt.clock()), inbox(Map.of("maxBodyBytes", 100, "errorDir", "/error")), accepting(seen));
        round(rt);
        round(rt);   // whether a final refusal ends its round or not, both files have been taken by now
        assertEquals(List.of("exact.bin"), files(seen), "a file of exactly maxBodyBytes is delivered, the one above it is not");
        assertEquals(Set.of("big.bin"), sftp.files("/error"), "refused finally: moved to the error directory");
        assertEquals("x".repeat(101), sftp.read("/error/big.bin"), "intact");
        assertEquals(Set.of(), sftp.files("/inbox"));
        assertFalse(sftp.opened("/inbox/big.bin"), "the listed size refused it: never opened, never read: " + sftp.events);
        assertEquals(List.of(BlobKey.body(seen.get(0).id(), 0)), spy.keys(), "the BlobStore saw only the delivered file's body");
    }

    @Test
    void aFileThatGrowsPastMaxBodyBytesWhileItIsReadIsRefusedFinally() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "grows.bin", Buffer.buffer("a".repeat(60)));
        sftp.onNextOpening((session, path) -> Files.writeString(sftp.local(path), "b".repeat(60), StandardOpenOption.APPEND));   // listed at 60
        SpyBlobStore spy = new SpyBlobStore(rt.blobs());
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(new SftpComponent(spy, rt.ids(), rt.timer(), rt.clock()), inbox(Map.of("maxBodyBytes", 100, "errorDir", "/error")), accepting(seen));
        round(rt);
        assertEquals(List.of(), files(seen), "never delivered: the bytes read passed maxBodyBytes");
        assertEquals(1, spy.writes.size(), "the download streamed into the BlobStore: " + spy.writes);
        assertEquals(100L, spy.writes.get(0).maxSize(), "bounded by maxBodyBytes, never by more");
        assertEquals(Set.of("grows.bin"), sftp.files("/error"), "BodyTooLarge is a final refusal: moved to the error directory");
        assertEquals(120, sftp.read("/error/grows.bin").length(), "intact");
        assertBefore(sftp.events, "close /inbox/grows.bin", "move /inbox/grows.bin ",
                "the abandoned download closed its handle before the file was moved, never moved while held open");
    }

    @Test
    void aFinalNackMovesToErrorDir() {
        sftp.mkdir("/error");
        sftp.drop("/inbox", "bad.xml", Buffer.buffer("<bad/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("errorDir", "/error")), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Nack("the schema says no", false));
        });
        round(rt);
        assertEquals(List.of("bad.xml"), files(seen));
        assertEquals(Set.of(), sftp.files("/inbox"), "a final Nack moves the file out of the inbox");
        assertEquals(Set.of("bad.xml"), sftp.files("/error"), "into the error directory");
        assertEquals("<bad/>", sftp.read("/error/bad.xml"), "intact");
        round(rt);
        assertEquals(1, seen.size(), "never delivered again");
    }

    @Test
    void afterSuccessMoveRenamesIntoMoveTo() {
        sftp.mkdir("/done");
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("afterSuccess", "move", "moveTo", "/done")), accepting(seen));
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<first/>"));
        round(rt);
        assertEquals(Set.of("x.xml"), sftp.files("/done"), "accepted: renamed into moveTo under its name");
        assertEquals("<first/>", sftp.read("/done/x.xml"));
        assertEquals(Set.of(), sftp.files("/inbox"));
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<second/>"));
        round(rt);
        assertEquals(2, seen.size(), "the second file of that name was delivered: " + files(seen));
        String taken = "x.xml." + seen.get(1).id();
        assertEquals(Set.of("x.xml", taken), sftp.files("/done"), "moveTo/x.xml was taken: the second goes to x.xml.<its message id>");
        assertEquals("<first/>", sftp.read("/done/x.xml"), "the first is not overwritten");
        assertEquals("<second/>", sftp.read("/done/" + taken));
        assertEquals(Set.of(), sftp.files("/inbox"));
        assertTrue(sftp.events.stream().noneMatch(ev -> ev.startsWith("remove ")), "moved, never deleted: " + sftp.events);
    }

    @Test
    void aYoungFileWaitsForMinAge() {
        Instant mtime = FakeRuntime.T0.minusSeconds(10);   // the fake clock stands at T0: ten seconds old
        sftp.drop("/inbox", "young.xml", Buffer.buffer("<young/>"), mtime);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("minAgeMs", 60_000, "intervalMs", 20_000)), accepting(seen));
        int youngRounds = 0;
        for (int i = 0; i < 10 && seen.isEmpty(); i++) {
            round(rt);
            Duration age = Duration.between(mtime, rt.clock().instant());
            if (age.compareTo(Duration.ofMinutes(1)) < 0) {
                assertEquals(List.of(), files(seen), "a round at age " + age + " leaves a file younger than minAgeMs alone");
                youngRounds++;
            } else {
                assertEquals(List.of("young.xml"), files(seen), "the first round at age " + age + " (minAgeMs reached) delivers it");
            }
        }
        assertTrue(youngRounds >= 2, "rounds ran while the file was young: " + youngRounds);
        assertEquals(List.of("young.xml"), files(seen), "delivered once old enough");
    }

    // ---- what a round takes ------------------------------------------------------------------------------------------

    @Test
    void filesWithTheSameMtimeAreDeliveredByName() {
        Instant same = EmbeddedSftp.FIRST_MTIME;
        for (String name : List.of("b.xml", "c.xml", "a.xml")) sftp.drop("/inbox", name, Buffer.buffer(name), same);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("a.xml", "b.xml", "c.xml"), files(seen), "(mtime, name): one mtime, so by name");
    }

    @Test
    void aRoundTakesAtMostMaxFilesPerPoll() {
        for (String name : List.of("f1.xml", "f2.xml", "f3.xml")) sftp.drop("/inbox", name, Buffer.buffer(name));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("maxFilesPerPoll", 2)), accepting(seen));
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml"), files(seen), "two files per round, the oldest");
        assertEquals(Set.of("f3.xml"), sftp.files("/inbox"));
        round(rt);
        assertEquals(List.of("f1.xml", "f2.xml", "f3.xml"), files(seen), "the rest in the next round");
    }

    @Test
    void onlyRegularFilesMatchingTheGlobAreTaken() throws IOException {
        sftp.drop("/inbox", "a.xml", Buffer.buffer("<a/>"));
        sftp.drop("/inbox", "b.txt", Buffer.buffer("not matched"));
        Path sub = sftp.mkdir("/inbox/sub.xml");   // a directory whose name matches
        Files.writeString(sub.resolve("c.xml"), "<c/>");   // not recursive
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("glob", "*.xml")), accepting(seen));
        round(rt);
        round(rt);
        assertEquals(List.of("a.xml"), files(seen), "the glob matches the file name, regular files only, not recursive");
        assertEquals(Set.of("b.txt"), sftp.files("/inbox"), "the file the glob does not match stays");
        assertTrue(Files.isRegularFile(sub.resolve("c.xml")), "the subdirectory is left alone");
    }

    // ---- the partner, the session, the host key -----------------------------------------------------------------------

    @Test
    void aWrongHostKeyIsRefused() {
        sftp.drop("/inbox", "secret.xml", Buffer.buffer("<secret/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        Consumer refused = started(component, inbox(Map.of("hostKey", EmbeddedSftp.fingerprint(EmbeddedSftp.OTHER_KEY))), accepting(seen));
        assertEquals(Health.Status.UP, health(component), "running, and no round has failed yet");
        brokenRound(rt, refused);
        assertEquals(Health.Status.DOWN, health(component), "the round could not connect: down");
        assertTrue(sftp.sessionsCreated() >= 1, "the consumer did connect: the refusal is the host key's");
        assertEquals(0, sftp.passwordChecks(), "the server was refused before the login: the password never left the node");
        assertEquals(List.of(), files(seen));
        assertEquals(Set.of("secret.xml"), sftp.files("/inbox"));

        FakeRuntime other = FakeRuntime.create();   // the same endpoint with the server's own key is served
        SftpComponent good = component(other);
        started(good, inbox(Map.of()), accepting(seen));
        round(other);
        assertEquals(List.of("secret.xml"), files(seen), "the right fingerprint logs in and delivers");
        assertTrue(sftp.passwordChecks() >= 1, "and logs in with the password");
        assertEquals(Health.Status.UP, health(good));
    }

    @Test
    void aServerRestartBreaksTheConsumerAndAFreshOneServesOnceItIsBack() throws Exception {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        Inbound in = inbox(Map.of());
        Consumer first = started(component, in, accepting(seen));
        sftp.drop("/inbox", "before.xml", Buffer.buffer("<before/>"));
        round(rt);
        assertEquals(List.of("before.xml"), files(seen));
        assertEquals(Health.Status.UP, health(component));
        int port = sftp.port();
        sftp.close();   // the partner goes down
        brokenRound(rt, first);
        assertEquals(Health.Status.DOWN, health(component), "the broken consumer's last round could not connect");
        sftp = EmbeddedSftp.start(root, port, EmbeddedSftp.HOST_KEY);   // back: same address, same key, same files
        sftp.drop("/inbox", "after.xml", Buffer.buffer("<after/>"));
        fresh(component, first, in, accepting(seen));
        round(rt);
        assertEquals(List.of("before.xml", "after.xml"), files(seen), "a fresh consumer of the same endpoint connects again");
        assertEquals(Health.Status.UP, health(component), "up again once a round succeeded");
        assertEquals(Set.of(), sftp.files("/inbox"));
    }

    @Test
    void startCompletesAtOnceWhileThePartnerIsDown() throws IOException {
        Inbound in = inbox(Map.of());
        sftp.close();   // nothing listens there any more
        SftpComponent component = component(rt);
        Consumer consumer = component.consumer(in, accepting(new CopyOnWriteArrayList<>()));
        consumers.add(consumer);
        Future<Void> start = consumer.start();
        assertTrue(start.succeeded(), "start schedules round 0 and completes at once: a partner down never fails a deploy: " + start);
        brokenRound(rt, consumer);
        assertEquals(Health.Status.DOWN, health(component), "the round could not connect");
    }

    @Test
    void aDirectoryThatCannotBeListedBreaksTheConsumerAndTurnsHealthDown() throws Exception {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        SftpComponent component = component(rt);
        Inbound missing = sftp.endpoint("sftp-in", "/missing", Map.of());
        Consumer first = started(component, missing, accepting(seen));
        brokenRound(rt, first);
        assertEquals(Health.Status.DOWN, health(component), "the last round could not list its directory");
        assertTrue(soon(() -> sftp.sessionsClosed() == sftp.sessionsCreated()), "the break dropped the session");
        sftp.mkdir("/missing");
        sftp.drop("/missing", "late.xml", Buffer.buffer("<late/>"));
        fresh(component, first, missing, accepting(seen));
        round(rt);
        assertEquals(List.of("late.xml"), files(seen));
        assertEquals(Health.Status.UP, health(component), "listed and delivered: up again");
    }

    @Test
    void oneSessionIsReusedAcrossRoundsAndStopClosesIt() throws Exception {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = started(component(rt), inbox(Map.of()), accepting(seen));
        assertInstanceOf(SftpComponent.SftpConsumer.class, consumer);
        sftp.drop("/inbox", "one.xml", Buffer.buffer("<one/>"));
        round(rt);
        assertEquals(Duration.ofSeconds(10), rt.timer().nextDelay(), "the next round intervalMs (default 10 s) after the round ended");
        sftp.drop("/inbox", "two.xml", Buffer.buffer("<two/>"));
        round(rt);
        round(rt);
        assertEquals(List.of("one.xml", "two.xml"), files(seen));
        assertBefore(sftp.events, "close /inbox/one.xml", "remove /inbox/one.xml", "an accepted file's handle is closed before its delete");
        assertEquals(1, sftp.sessionsCreated(), "one SSH session for every round of the consumer");
        consumers.remove(consumer);
        consumer.stop().await(10, TimeUnit.SECONDS);
        assertEquals(0, rt.timer().pending(), "stop cancelled the next round");
        assertTrue(soon(() -> sftp.sessionsClosed() == 1), "stop closed the session");
    }

    @Test
    void theMinaIoPoolHasAtMostTwoThreads() {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        sftp.drop("/inbox", "one.xml", Buffer.buffer("<one/>"));
        round(rt);
        assertEquals(List.of("one.xml"), files(seen), "a session is open");
        Map<String, Long> perClient = Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                .filter(n -> n.startsWith("sshd-SshClient[") && n.contains("-nio2-thread-"))
                .collect(Collectors.groupingBy(n -> n.substring(0, n.indexOf(']') + 1), Collectors.counting()));
        assertFalse(perClient.isEmpty(), "a MINA client names its NIO2 threads sshd-SshClient[..]-nio2-thread-N: "
                + Thread.getAllStackTraces().keySet().stream().map(Thread::getName).filter(n -> n.startsWith("sshd-")).toList());
        perClient.forEach((client, threads) -> assertTrue(threads <= 2,
                client + " runs " + threads + " NIO2 threads: the transport's I/O pool is sized to the minimum (D-076), not to the cores"));
    }

    // ---- what the handler sees ----------------------------------------------------------------------------------------

    @Test
    void propsCarryNamePathAndMtime() {
        sftp.drop("/inbox", "order-1.xml", Buffer.buffer("<order id=\"1\"/>"), Instant.parse("2025-12-31T23:00:00Z"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("flow", "orders/sftp-intake")), accepting(seen));
        round(rt);
        assertEquals(1, seen.size());
        Envelope e = seen.get(0);
        assertEquals("order-1.xml", e.get(SftpComponent.FILE).orElse(null), "sftp.file: the name");
        assertEquals("/inbox/order-1.xml", e.get(SftpComponent.PATH).orElse(null), "sftp.path: the full remote path");
        assertEquals("2025-12-31T23:00:00Z", e.get(SftpComponent.MTIME).orElse(null), "sftp.mtime: the server's mtime, ISO-8601");
        assertEquals(Set.of("sftp.file", "sftp.path", "sftp.mtime"), e.props().asMap().keySet(),
                "these three: no key to recognise the file by, no size prop (body().size())");
        assertEquals(Map.of(), e.headers().asMap(), "no headers");
        assertEquals("<order id=\"1\"/>", e.body().whole().toString());
        assertEquals("orders/sftp-intake", e.flow(), "the flow option names the envelope's flow");
        assertEquals("m-1", e.id(), "the id from the injected IdGenerator");
        assertEquals(BlobKey.body(e.id(), 0), e.body().key(), "the body stored under (id, 0)");
    }

    @Test
    void theProducerIsRefusedAtBuild() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> component(rt).producer(new Outbound(inbox(Map.of()).connection())));
        assertEquals("sftp has no producer before Phase 4", refused.getMessage());
    }

    /** The connection form takes the server and the login; the flow form takes which files a round takes and what becomes of them. */
    @Test
    void settingsOutsideTheFormsAreRefused() {
        Settings connection = SftpComponent.FORMS.connection();
        Settings flow = SftpComponent.FORMS.flow();
        Map<String, Object> base = Map.of("host", "sftp", "user", "rail8", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        assertValid(connection, base, "host, user, password and hostKey");
        assertValid(connection, with(without(base, "password"), "privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n"),
                "a private key instead of the password");
        assertValid(connection, with(with(with(base, "port", 2222), "connectTimeoutMs", 5_000), "ioTimeoutMs", 10_000), "every connection setting");
        assertValid(connection, with(base, "hostKey", "anything"), "the host key is a secret: its form is checked at each login");
        assertRefused(connection, without(base, "hostKey"), "no hostKey: never accept-all");
        assertRefused(connection, with(base, "privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----"), "both password and privateKey");
        assertRefused(connection, without(base, "password"), "neither password nor privateKey");
        assertValid(connection, without(base, "user"), "no user: credentials give it; with a plain password or key the consumer's build refuses");
        assertRefused(connection, without(base, "host"), "no host");
        assertRefused(connection, with(base, "glob", "*.xml"), "a flow setting is not the connection's");
        assertRefused(connection, with(base, "pasword", "typo"), "strict: a setting the form does not know");

        Map<String, Object> every = Map.of("dir", "/inbox", "glob", "*.xml", "intervalMs", 5_000, "maxFilesPerPoll", 10, "minAgeMs", 30_000,
                "afterSuccess", "move", "moveTo", "/done", "errorDir", "/error");
        assertValid(flow, every, "every flow setting");
        assertValid(flow, Map.of(), "none: the login directory, every file, deleted after success");
        assertValid(flow, Map.of("afterSuccess", "delete"), "afterSuccess delete");
        assertRefused(flow, Map.of("afterSuccess", "move"), "move without moveTo");
        assertRefused(flow, Map.of("afterSuccess", "copy"), "afterSuccess is delete or move");
        assertRefused(flow, Map.of("host", "sftp"), "a connection setting is not the flow's");
        assertRefused(flow, Map.of("dir", 7), "dir as a number");
    }

    @Test
    void aSenderWithoutAConnectionIsRefusedNamingItsFlow() {
        Inbound none = new Inbound("orders/sftp-intake", Optional.empty(), Map.of("dir", "/inbox"), 1024);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> component(rt).consumer(none, accepting(new CopyOnWriteArrayList<>())));
        assertEquals("flow 'orders/sftp-intake': an sftp sender polls through a connection, and none is named", refused.getMessage());
        IllegalArgumentException noMoveTo = assertThrows(IllegalArgumentException.class,
                () -> component(rt).consumer(inbox(Map.of("afterSuccess", "move", "flow", "orders/sftp-intake")), accepting(new CopyOnWriteArrayList<>())));
        assertEquals("flow 'orders/sftp-intake': the setting 'moveTo' is required when afterSuccess is move", noMoveTo.getMessage());
    }

    // ---- the connection names the server, the flow the directory ---------------------------------------------------------

    private List<String> deliveredBy(FakeRuntime on, Inbound cfg, SftpComponent component) {
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component, cfg, accepting(seen));
        round(on);
        return files(seen);
    }

    @Test
    void theConnectionsHostAndPortNameTheServer() {
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        SftpComponent component = component(rt);
        assertEquals(List.of("real.xml"), deliveredBy(rt, inbox(Map.of()), component), "the connection's host and port");
        assertEquals(Health.Status.UP, health(component));
        assertEquals(Set.of(), sftp.files("/inbox"), "and the delivered file was deleted");

        sftp.drop("/inbox", "kept.xml", Buffer.buffer("<kept/>"));
        for (Map<String, Object> elsewhere : List.<Map<String, Object>>of(Map.of("host", "no-such-host.invalid"), Map.of("port", 1))) {
            FakeRuntime other = FakeRuntime.create();
            SftpComponent unreachable = component(other);
            List<Envelope> none = new CopyOnWriteArrayList<>();
            brokenRound(other, started(unreachable, inbox(elsewhere), accepting(none)));
            assertEquals(List.of(), files(none), elsewhere + ": nothing delivered");
            assertEquals(Health.Status.DOWN, health(unreachable), elsewhere + ": the round could not connect");
        }
        assertEquals(Set.of("kept.xml"), sftp.files("/inbox"));
    }

    @Test
    void theDirSettingNamesThePolledDirectory() {
        sftp.mkdir("/other");
        sftp.drop("/other", "decoy.xml", Buffer.buffer("<decoy/>"));
        sftp.drop("/inbox", "real.xml", Buffer.buffer("<real/>"));
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of()), accepting(seen));
        round(rt);
        assertEquals(List.of("real.xml"), files(seen));
        assertEquals("/inbox/real.xml", seen.get(0).get(SftpComponent.PATH).orElse(null));
        assertEquals(Set.of("decoy.xml"), sftp.files("/other"), "another directory is never touched");
    }

    /** Without {@code dir} a round lists the directory the server logs the user into (here the server's root). */
    @Test
    void withoutADirTheLoginDirectoryIsPolled() {
        sftp.drop("/", "home.xml", Buffer.buffer("<home/>"));
        sftp.drop("/inbox", "elsewhere.xml", Buffer.buffer("<elsewhere/>"));
        Map<String, Object> noDir = new HashMap<>();
        noDir.put("dir", null);
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(noDir), accepting(seen));
        round(rt);
        assertEquals(List.of("home.xml"), files(seen), "the login directory's file, not the subdirectory's");
        assertEquals("/home.xml", seen.get(0).get(SftpComponent.PATH).orElse(null), "its path under the login directory the server names");
        assertEquals(Set.of(), sftp.files("/"), "accepted: deleted from the login directory");
        assertEquals(Set.of("elsewhere.xml"), sftp.files("/inbox"));
    }

    @Test
    void portsOutsideOneTo65535AndAnEmptyHostAreRefusedByTheConnectionForm() {
        Settings settings = SftpComponent.FORMS.connection();
        Map<String, Object> base = Map.of("host", "sftp", "user", "rail8", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        assertValid(settings, with(base, "port", 1), "port 1, the lowest");
        assertValid(settings, with(base, "port", 65535), "port 65535, the highest");
        assertRefused(settings, with(base, "port", 0), "port 0");
        assertRefused(settings, with(base, "port", 70000), "port 70000");
        assertRefused(settings, with(base, "port", 65536), "port 65536");
        assertRefused(settings, with(base, "port", "22"), "port as text");
        assertRefused(settings, with(base, "host", 7), "host as a number");
        assertRefused(settings, with(base, "host", ""), "an empty host names no server");
    }

    // ---- the secrets: read at each login --------------------------------------------------------------------------------

    /** {@code in} with its connection's secrets replaced by {@code secrets}. */
    static Inbound withSecrets(Inbound in, Map<String, Secret> secrets) {
        Connection c = in.connection().orElseThrow();
        return new Inbound(in.flow(), Optional.of(new Connection(c.key(), c.values(), secrets)), in.settings(), in.maxBodyBytes());
    }

    /** A secret giving {@code value}, each read counted in {@code reads}. */
    static Secret counted(String ref, String value, java.util.concurrent.atomic.AtomicInteger reads) {
        return Secret.of(ref, () -> {
            reads.incrementAndGet();
            return value;
        });
    }

    /**
     * No secret is read while the consumer is built or started; each login reads the password and the host key anew: a round
     * after a dropped session logs in again and reads them again.
     */
    @Test
    void theSecretsAreReadAtEachLoginNeverWhileTheConsumerIsBuilt() {
        java.util.concurrent.atomic.AtomicInteger passwordReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hostKeyReads = new java.util.concurrent.atomic.AtomicInteger();
        Inbound in = withSecrets(inbox(Map.of()), Map.of(
                "password", counted("secret://sftp-in.password", EmbeddedSftp.PASSWORD, passwordReads),
                "hostKey", counted("secret://sftp-in.hostkey", EmbeddedSftp.fingerprint(EmbeddedSftp.HOST_KEY), hostKeyReads)));
        sftp.drop("/inbox", "f1.xml", Buffer.buffer("<one/>"));
        sftp.onNextReading((session, path) -> { throw new IOException("the test failed the read of " + path); });
        Consumer consumer = component(rt).consumer(in, accepting(new CopyOnWriteArrayList<>()));
        assertEquals(List.of(0, 0), List.of(passwordReads.get(), hostKeyReads.get()), "built: nothing read");
        consumers.add(consumer);
        consumer.start().await();
        assertEquals(List.of(0, 0), List.of(passwordReads.get(), hostKeyReads.get()), "started: nothing read");
        round(rt);
        assertEquals(List.of(1, 1), List.of(passwordReads.get(), hostKeyReads.get()), "the first login read each once");
        assertTrue(soon(() -> sftp.sessionsClosed() == 1), "the failed read dropped the session");
        round(rt);
        assertEquals(2, sftp.sessionsCreated(), "a second login");
        assertEquals(List.of(2, 2), List.of(passwordReads.get(), hostKeyReads.get()), "the second login read each again");
    }

    /**
     * A host key that is no {@code SHA256:} fingerprint fails the login before anything is sent: the round breaks the consumer
     * with a failure naming the connection and the setting {@code hostKey}, never its value.
     */
    @Test
    void aHostKeyThatIsNoFingerprintFailsTheLoginNamingTheSettingNeverItsValue() {
        for (String wrong : List.of("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA", "sha256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU", "SHA256:", "SHA256:abc def")) {
            FakeRuntime on = FakeRuntime.create();
            Inbound in = withSecrets(inbox(Map.of()), Map.of("password", Secret.of("secret://sftp-in.password", () -> EmbeddedSftp.PASSWORD),
                    "hostKey", Secret.of("secret://sftp-in.hostkey", () -> wrong)));
            Throwable broke = brokenRound(on, started(component(on), in, accepting(new CopyOnWriteArrayList<>())));
            StringBuilder chain = new StringBuilder();
            for (Throwable t = broke; t != null; t = t.getCause()) chain.append(t.getMessage()).append(" | ");
            assertTrue(chain.toString().contains("connection 'sftp-in': the setting 'hostKey' must be a SHA256: fingerprint"), wrong + ": " + chain);
            if (!wrong.equals("SHA256:")) assertFalse(chain.toString().contains(wrong), wrong + ": the value is never quoted: " + chain);
        }
        assertEquals(0, sftp.sessionsCreated(), "refused before connecting");
        assertEquals(0, sftp.passwordChecks(), "the password never left the node");
    }

    static Map<String, Object> with(Map<String, Object> options, String key, Object value) {
        Map<String, Object> m = new HashMap<>(options);
        m.put(key, value);
        return m;
    }

    static Map<String, Object> without(Map<String, Object> options, String key) {
        Map<String, Object> m = new HashMap<>(options);
        m.remove(key);
        return m;
    }

    static void assertValid(Settings settings, Map<String, Object> options, String what) {
        List<Settings.Problem> problems = settings.check(options, Direction.IN);
        assertTrue(problems.isEmpty(), what + ": valid, but refused with " + problems);
    }

    static void assertRefused(Settings settings, Map<String, Object> options, String what) {
        assertFalse(settings.check(options, Direction.IN).isEmpty(), what + ": refused by settings(), but valid: " + options);
    }

    // ---- the node holds the lease of the place a sender polls -----------------------------------------------------------

    /**
     * Every sftp sender polls: {@code polledPlace} names the connection, its server and the directory and glob of the flow —
     * the host lower-cased, an IPv6 one bracketed, the port 22 by default, {@code .} for the login directory. Answered from the
     * use alone: nothing connected.
     */
    @Test
    void everySenderPollsThePlaceItsConnectionDirAndGlobName() {
        SftpComponent component = component(rt);
        assertEquals(Optional.of("sftp-in sftp://127.0.0.1:" + sftp.port() + " /inbox *"), component.polledPlace(inbox(Map.of())), "the defaults");
        assertEquals(Optional.of("sftp-in sftp://127.0.0.1:" + sftp.port() + " /inbox *.xml"),
                component.polledPlace(inbox(Map.of("afterSuccess", "move", "moveTo", "/done", "glob", "*.xml"))), "moving, filtered");
        Map<String, Object> noDir = new HashMap<>();
        noDir.put("dir", null);
        noDir.put("port", null);
        noDir.put("host", "Files.Example");
        assertEquals(Optional.of("sftp-in sftp://files.example:22 . *"), component.polledPlace(inbox(noDir)), "lower-cased, port 22, the login directory");
        assertEquals(Optional.of("sftp-in sftp://[fe80::1]:2222 /inbox *"), component.polledPlace(inbox(Map.of("host", "FE80::1", "port", 2222))),
                "an IPv6 host bracketed, lower-cased");
        assertEquals(Optional.of("sftp-in sftp://[::1]:22 /inbox *"), component.polledPlace(inbox(Map.of("host", "[::1]", "port", 22))),
                "a bracketed host is not bracketed twice");
        assertEquals(Optional.of("partner-b sftp://127.0.0.1:" + sftp.port() + " /inbox *"), component.polledPlace(sftp.endpoint("partner-b", "/inbox", Map.of())),
                "another connection to the same directory: another place");
        assertEquals(0, sftp.sessionsCreated(), "answered from the use alone: nothing connected");
    }

    /** A move whose name is taken in {@code moveTo} is suffixed with the id of the message the file was delivered as. */
    @Test
    void aTakenMoveNameIsSuffixedWithTheMessageId() {
        sftp.mkdir("/done");
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        started(component(rt), inbox(Map.of("afterSuccess", "move", "moveTo", "/done")), accepting(seen));
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<first/>"));
        round(rt);
        assertEquals(Set.of("x.xml"), sftp.files("/done"), "fixture: moveTo/x.xml is taken now");
        sftp.drop("/inbox", "x.xml", Buffer.buffer("<second/>"));
        round(rt);
        assertEquals(2, seen.size(), "the file uploaded again was delivered again: " + files(seen));
        String second = seen.get(1).id();
        assertEquals(Set.of("x.xml", "x.xml." + second), sftp.files("/done"), "the taken name is suffixed with the second message's id");
        assertEquals("<second/>", sftp.read("/done/x.xml." + second));
        assertEquals(Set.of(), sftp.files("/inbox"));
    }
}
