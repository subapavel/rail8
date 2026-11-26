package dev.rail8.runtime.git;

import dev.rail8.api.Secret;
import dev.rail8.runtime.git.GitFailure.Reason;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JGit cache over https: the secret store's {@code rail8.git} read once for each operation, a changed one sent from the next
 * operation on and a deleted or unreadable one not sent; the token sent only to the base's host and kept out of every message, log
 * line and file of the cache, credentials refused, a redirect to another host refused before it is made, a silent server stopped by the timer on a fake clock,
 * and the cache's limit held while a fetch receives.
 */
@Timeout(120)
class GitCacheHttpsTest {

    private static final String USER = "rail8-reader";
    private static final String TOKEN = "glpat-Secret-Token-4711";
    private static final String ROTATED = "glpat-Rotated-Token-0815";

    @TempDir
    Path temp;

    private final FakeClock clock = FakeClock.at(Instant.parse("2026-11-19T08:00:00Z"));
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<String> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            StringWriter text = new StringWriter();
            text.append(record.getLoggerName()).append(' ').append(String.valueOf(record.getMessage()));
            if (record.getParameters() != null) for (Object parameter : record.getParameters()) text.append(' ').append(String.valueOf(parameter));
            if (record.getThrown() != null) record.getThrown().printStackTrace(new PrintWriter(text));
            synchronized (logged) {
                logged.add(text.toString());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private Level rootLevel;

    private GitSource source;
    private GitHttpsServer server;
    private String orders;

    @BeforeEach
    void start() throws Exception {
        Logger root = Logger.getLogger("");
        rootLevel = root.getLevel();
        root.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        root.addHandler(capture);

        source = new GitSource(temp.resolve("source"));
        source.commit("orders", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: orders\n")));
        orders = source.commit("orders", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: orders\nversion: 2\n")));
        server = GitHttpsServer.start(temp.resolve("source"), temp.resolve("keys"));
    }

    @AfterEach
    void stop() {
        server.close();
        Logger root = Logger.getLogger("");
        root.removeHandler(capture);
        root.setLevel(rootLevel);
    }

    /** What the secret store holds of {@code rail8.git}, as its platform read answers; each read counted. */
    private final AtomicReference<Optional<SecretStore.Held>> stored = new AtomicReference<>(Optional.empty());
    private final AtomicInteger reads = new AtomicInteger();

    private Optional<SecretStore.Held> read() {
        reads.incrementAndGet();
        return stored.get();
    }

    /** The store holds {@code rail8.git} with {@code token}. */
    private void store(String token) {
        stored.set(Optional.of(new SecretStore.Held(Secret.Kind.CREDENTIALS, "k1", new Secret.Credentials(USER, token))));
    }

    private PackageRepositories open(URI base, long maxBytes) {
        GitSettings settings = new GitSettings(base, "", Optional.of("refs/heads/main"), GitSettings.TIMEOUT_DEFAULT, maxBytes);
        return new GitCache(settings, temp.resolve("cache"), 4L << 20, true, timer, Set::of, this::read, server.clientTls().getSocketFactory()).open();
    }

    private GitCache cache() {
        GitSettings settings = new GitSettings(server.base("localhost"), "", Optional.of("refs/heads/main"), GitSettings.TIMEOUT_DEFAULT,
                GitSettings.MAX_BYTES_DEFAULT);
        return new GitCache(settings, temp.resolve("cache"), 4L << 20, true, timer, Set::of, this::read, server.clientTls().getSocketFactory());
    }

    private PackageRepositories open() {
        return open(server.base("localhost"), GitSettings.MAX_BYTES_DEFAULT);
    }

    /** The token and the forms a request would carry it in. */
    private static List<String> secrets() {
        return Stream.of(TOKEN, ROTATED).flatMap(token -> Stream.of(token,
                Base64.getEncoder().encodeToString((USER + ":" + token).getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))).toList();
    }

    private void assertNoSecretIn(String what, String text) {
        for (String secret : secrets()) assertFalse(text.contains(secret), what + " carries the token: " + text);
    }

    private void assertNoSecretLoggedOrCached() throws Exception {
        synchronized (logged) {
            for (String line : logged) assertNoSecretIn("a log line", line);
        }
        Path cache = temp.resolve("cache");
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> files = Files.walk(cache)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertNoSecretIn(cache.relativize(file).toString(), new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
    }

    private static GitFailure failure(Reason reason, PackageRepositoriesContract.GitFailureCall call) {
        GitFailure failure = assertThrows(GitFailure.class, call::run);
        assertEquals(reason, failure.reason(), failure.getMessage());
        return failure;
    }

    @Test
    void fetchesWithTheTokenAndKeepsItOutOfTheCacheAndTheLogs() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        Commit commit = open().forDeploy("orders", orders);
        Path out = Files.createDirectories(temp.resolve("out"));
        commit.write(out);
        assertEquals("name: orders\nversion: 2\n", Files.readString(out.resolve("package.yaml")));

        assertTrue(server.requests().stream().anyMatch(r -> r.authorization().isPresent()), "the token was sent");
        String config = Files.readString(temp.resolve("cache").resolve("orders.git").resolve("config"));
        assertFalse(config.contains("remote"), config);
        assertFalse(config.contains(USER), config);
        assertNoSecretLoggedOrCached();
    }

    /** The Authorization header that carries {@code token}. */
    private static String basic(String token) {
        return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    /** The Authorization headers the server saw from the request {@code from} on. */
    private List<String> sentFrom(int from) {
        return server.requests().subList(from, server.requests().size()).stream().flatMap(r -> r.authorization().stream()).toList();
    }

    @Test
    void aFetchAfterAnOverwriteSendsTheNewTokenWithoutARestartReadingTheStoreOnceForEachOperation() throws Exception {
        GitCache cache = cache();
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        assertEquals(orders, cache.open().forDeploy("orders", orders).id());
        assertEquals(1, reads.get(), "one read for the fetch, at its first request the host asked credentials for");
        assertEquals(Set.of(basic(TOKEN)), Set.copyOf(sentFrom(0)));

        String next = source.commit("orders", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: orders\nversion: 3\n")));
        String rotated = ROTATED;
        server.requireCredentials(USER, rotated);
        store(rotated);   // the secret administrator overwrites rail8.git
        int before = server.requests().size();
        assertEquals(next, cache.open().forDeploy("orders", next).id(), "the same cache fetches with the new token");
        assertEquals(Set.of(basic(rotated)), Set.copyOf(sentFrom(before)), "the second fetch sends the new token, never the old one");
        assertEquals(2, reads.get());

        before = server.requests().size();
        cache.check("orders");
        assertEquals(3, reads.get(), "a check is an operation of its own: one read");
        assertEquals(Set.of(basic(rotated)), Set.copyOf(sentFrom(before)));
        assertNoSecretLoggedOrCached();
    }

    @Test
    void aDeletedRail8GitSendsNoCredentialsAtTheNextOperation() throws Exception {
        GitCache cache = cache();
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        cache.check("orders");
        stored.set(Optional.empty());   // the secret administrator deletes rail8.git
        int before = server.requests().size();
        failure(Reason.CREDENTIALS_REFUSED, () -> cache.check("orders"));
        failure(Reason.CREDENTIALS_REFUSED, () -> cache.open().forDeploy("orders", orders));
        assertTrue(server.requests().size() > before, "the host was asked");
        assertEquals(List.of(), sentFrom(before), "no credentials once rail8.git is gone");
        assertNoSecretLoggedOrCached();
    }

    @Test
    void anUnreadableRail8GitSendsNothingAndNamesItInTheFailure() throws Exception {
        server.requireCredentials(USER, TOKEN);
        stored.set(Optional.of(new SecretStore.Held(Secret.Kind.CREDENTIALS, "k2", null)));   // under a key this node does not hold
        GitFailure fetch = failure(Reason.CREDENTIALS_UNREADABLE, () -> open().forDeploy("orders", orders));
        assertEquals("orders at " + orders + ": the secret store's rail8.git is unreadable", fetch.getMessage());
        GitFailure check = failure(Reason.CREDENTIALS_UNREADABLE, () -> cache().check("orders"));
        assertEquals("orders at refs/heads/main: the secret store's rail8.git is unreadable", check.getMessage());
        assertEquals(List.of(), sentFrom(0), "nothing was sent");
        assertNoSecretLoggedOrCached();
    }

    @Test
    void aHostThatAsksNoCredentialsIsAnsweredWithoutReadingTheStore() throws Exception {
        store(TOKEN);
        assertEquals(orders, open().forDeploy("orders", orders).id());
        assertEquals(0, reads.get(), "the pair is read only when the host asks for it");
        assertEquals(List.of(), sentFrom(0));
    }

    @Test
    void refusesCredentialsTheServerRefusesWithTheFormattersTextOnly() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store("glpat-wrong");
        GitFailure wrong = failure(Reason.CREDENTIALS_REFUSED, () -> open().forDeploy("orders", orders));
        assertEquals("orders at " + orders + ": credentials refused", wrong.getMessage());
        stored.set(Optional.empty());
        GitFailure none = failure(Reason.CREDENTIALS_REFUSED, () -> open().forDeploy("orders", orders));
        assertEquals("orders at " + orders + ": credentials refused", none.getMessage());
        assertNoSecretLoggedOrCached();
    }

    @Test
    void refusesARedirectToAnotherHostBeforeAnyRequestReachesIt() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        try (GitHttpsServer other = GitHttpsServer.start(temp.resolve("source"), temp.resolve("other-keys"))) {
            server.redirect("/group/orders/", other.base("127.0.0.1") + "orders/");
            GitFailure failure = failure(Reason.TRANSPORT_FAILED, () -> open().forDeploy("orders", orders));
            assertEquals("orders at " + orders + ": the transport failed", failure.getMessage());
            assertEquals(List.of(), other.requests(), "the other host saw no request, so no credentials");

            server.redirect("/group/orders/", other.base("localhost") + "orders/");
            failure(Reason.TRANSPORT_FAILED, () -> open().forDeploy("orders", orders));
            assertEquals(List.of(), other.requests(), "another port is another origin");
        }
        assertNoSecretLoggedOrCached();
    }

    @Test
    void followsARedirectOnTheSameHost() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        server.redirect("/group/moved/", server.base("localhost") + "orders/");
        assertEquals(orders, open().forDeploy("moved", orders).id());
        assertNoSecretLoggedOrCached();
    }

    @Test
    void answersNotFoundForARepositoryTheServerLacks() {
        failure(Reason.NOT_FOUND, () -> open().forDeploy("missing", orders));
    }

    @Test
    void stopsAFetchThatWaitsOnASilentServerWhenTheTimerFires() throws Exception {
        CountDownLatch arrived = server.hold();
        PackageRepositories repositories = open();
        CompletableFuture<GitFailure> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                repositories.forDeploy("orders", orders);
                result.complete(null);
            } catch (GitFailure failure) {
                result.complete(failure);
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
        arrived.await();
        clock.advance(GitSettings.TIMEOUT_DEFAULT);
        GitFailure failure = result.get();
        assertEquals(Reason.TIMED_OUT, failure == null ? null : failure.reason());
        assertEquals("orders at " + orders + ": timed out (RAIL8_GIT_TIMEOUT)", failure.getMessage());
    }

    @Test
    void anAbortStopsAFetchThatWaitsOnASilentServerWithNoTimerFiring() throws Exception {
        CountDownLatch arrived = server.hold();
        PackageRepositories repositories = open();
        CompletableFuture<GitFailure> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                repositories.forDeploy("orders", orders);
                result.complete(null);
            } catch (GitFailure failure) {
                result.complete(failure);
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
        arrived.await();
        repositories.abort();   // the clock stands: RAIL8_GIT_TIMEOUT never passes
        GitFailure failure = result.get();
        assertEquals(Reason.TIMED_OUT, failure == null ? null : failure.reason());
        GitFailure later = failure(Reason.TIMED_OUT, () -> repositories.recorded("orders", orders));
        assertEquals("orders at " + orders + ": timed out (RAIL8_GIT_TIMEOUT)", later.getMessage(), "a fetch after the abort fails at once");
    }

    @Test
    void theCheckOfTheCredentialsListsTheRefsWithTheTokenAndWritesNothing() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store(TOKEN);
        cache().check("orders");
        assertTrue(server.requests().stream().anyMatch(r -> r.authorization().isPresent()), "the token was sent");
        assertFalse(Files.exists(temp.resolve("cache")), "the check writes nothing into the cache");
        assertEquals(0, timer.pending(), "its deadline is cancelled once it answered");
        assertNoSecretLoggedOrCached();
    }

    @Test
    void theCheckOfTheCredentialsSeesThemRefusedWithTheFormattersTextOnly() throws Exception {
        server.requireCredentials(USER, TOKEN);
        store("glpat-wrong");
        GitFailure wrong = failure(Reason.CREDENTIALS_REFUSED, () -> cache().check("orders"));
        assertEquals("orders at refs/heads/main: credentials refused", wrong.getMessage());
        store(TOKEN);
        failure(Reason.NOT_FOUND, () -> cache().check("missing"));
        assertNoSecretLoggedOrCached();
    }

    @Test
    void theCheckOfTheCredentialsSeesAHostItCannotReach() {
        store(TOKEN);
        GitCache cache = cache();
        server.close();
        failure(Reason.TRANSPORT_FAILED, () -> cache.check("orders"));
    }

    @Test
    void theCheckOfTheCredentialsStopsAtItsOwnTimeout() throws Exception {
        CountDownLatch arrived = server.hold();
        GitCache cache = cache();
        CompletableFuture<GitFailure> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                cache.check("orders");
                result.complete(null);
            } catch (GitFailure failure) {
                result.complete(failure);
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
        arrived.await();
        clock.advance(GitSettings.CHECK_TIMEOUT_DEFAULT);   // RAIL8_GIT_CHECK_TIMEOUT, well before RAIL8_GIT_TIMEOUT
        GitFailure failure = result.get();
        assertEquals(Reason.TIMED_OUT, failure == null ? null : failure.reason());
    }

    @Test
    void holdsTheCacheLimitWhileTheFetchReceives() throws Exception {
        byte[] big = new byte[3 << 20];
        new Random(11).nextBytes(big);
        String large = source.commit("large", "main", Map.of("package.yaml", PackageRepositoriesContract.file("name: large\n"),
                "data.bin", new PackageRepositoriesContract.File(big)));
        GitFailure failure = failure(Reason.CACHE_FULL, () -> open(server.base("localhost"), 1L << 20).forDeploy("large", large));
        assertEquals("large at " + large + ": the git cache is full (RAIL8_GIT_MAX_BYTES)", failure.getMessage());
        long cached;
        try (Stream<Path> files = Files.walk(temp.resolve("cache"))) {
            cached = files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
        assertTrue(cached < 1L << 20, "the cache holds " + cached + " bytes");
    }
}
