package dev.rail8.runtime.git;

import dev.rail8.api.Secret;
import dev.rail8.runtime.secret.SecretStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of {@link PackageRepositories} on JGit over {@code file://} repositories, and what only the JGit cache does. A
 * {@code file://} base never reads the store's {@code rail8.git}, which every cache here is given.
 */
class GitCacheTest extends PackageRepositoriesContract {

    private static final String TOKEN = "glpat-Secret-Token-4711";

    private GitSource git;
    /** The reads of the store's {@code rail8.git}, which holds a pair: a {@code file://} base never makes one. */
    private final AtomicInteger reads = new AtomicInteger();

    private Optional<SecretStore.Held> stored() {
        reads.incrementAndGet();
        return Optional.of(new SecretStore.Held(Secret.Kind.CREDENTIALS, "k1", new Secret.Credentials("rail8-reader", TOKEN)));
    }

    @AfterEach
    void neverReadTheCredentials() {
        assertEquals(0, reads.get(), "a file:// base reads no credentials");
    }

    @Override
    protected Source newSource() {
        git = new GitSource(temp.resolve("source"));
        return git;
    }

    @Override
    protected Cache cache(Options options) {
        GitSettings settings = new GitSettings(git.base(), options.path(), options.ref(), GitSettings.TIMEOUT_DEFAULT, options.maxBytes());
        GitCache cache = new GitCache(settings, temp.resolve("cache"), options.maxPackageBytes(), options.deployed(), timer, options.named(),
                this::stored);
        return cache::open;
    }

    @Test
    void aFileBaseWithRail8GitStoredReadsNoCredentialsAndWarnsOnceWithoutTheBaseOrTheToken() throws Exception {
        String first = git.commit("orders", "main", pkg("orders"));
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger logger = Logger.getLogger(GitCache.class.getName());
        logger.addHandler(capture);
        try {
            AtomicInteger told = new AtomicInteger();
            GitCache cache = new GitCache(new GitSettings(git.base()), temp.resolve("cache"), 1L << 20, false, timer, Set::of, () -> {
                told.incrementAndGet();
                return Optional.of(new SecretStore.Held(Secret.Kind.CREDENTIALS, "k1", new Secret.Credentials("rail8-reader", TOKEN)));
            });
            cache.credentialsChanged();   // the node's start
            cache.credentialsChanged();   // an overwrite
            int atStart = told.get();
            assertEquals(first, cache.open().forDeploy("orders", first).id());
            cache.check("orders");
            assertEquals(atStart, told.get(), "an operation reads no credentials");
            List<String> warnings = logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("rail8.git") && warnings.getFirst().contains("file://"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(git.base().toString()) || warnings.getFirst().contains(git.base().getPath()), "no base: " + warnings);
            assertFalse(warnings.getFirst().contains(TOKEN), warnings.getFirst());
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void keepsACommitItAnsweredThroughAForcePushAndACleanupOfTheCache() throws Exception {
        String first = git.commit("orders", "main", pkg("orders"));
        Cache cache = cache(Options.defaults());
        cache.open().forDeploy("orders", first);

        git.delete("orders");
        String rewritten = git.commit("orders", "main", Map.of("package.yaml", file("name: orders\nrewritten: true\n")));
        cache.open().forDeploy("orders", rewritten);
        try (FileRepository cached = new FileRepository(temp.resolve("cache").resolve("orders.git").toFile())) {
            assertNotNull(cached.exactRef("refs/rail8/kept/" + first));
            GC gc = new GC(cached);
            gc.setExpireAgeMillis(0);
            gc.gc().get();
        }
        git.delete("orders");

        cache.open().recorded("orders", first).write(Files.createDirectories(temp.resolve("out")));
        assertEquals("name: orders\n", Files.readString(temp.resolve("out").resolve("package.yaml")));
    }

    @Test
    void readsTheWorkingFoldersOfClonesUnderAFileBaseWithATemporaryCache() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("workspace").resolve("demo"));
        Files.writeString(folder.resolve("package.yaml"), "name: demo\n");
        Files.createDirectories(folder.resolve("flows"));
        Files.writeString(folder.resolve("flows").resolve("in.yaml"), "flow: in\n");
        PersonIdent who = new PersonIdent("Rail8 test", "test@rail8.invalid", Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC);
        String head;
        try (Git repo = Git.init().setDirectory(folder.toFile()).setInitialBranch("main").call()) {
            repo.add().addFilepattern(".").call();
            head = repo.commit().setMessage("first\n").setAuthor(who).setCommitter(who).setSign(false).call().name();
        }
        Files.writeString(folder.resolve("flows").resolve("in.yaml"), "flow: changed, not committed\n");

        GitCache cache = new GitCache(new GitSettings(temp.resolve("workspace").toUri()), Files.createTempDirectory(temp, "cli-cache"),
                1L << 20, false, timer, java.util.Set::of, Optional::empty);
        Commit commit = cache.open().forDeploy("demo", "HEAD");
        assertEquals(head, commit.id());
        Path out = Files.createDirectories(temp.resolve("out"));
        commit.write(out);
        assertEquals("flow: in\n", Files.readString(out.resolve("flows").resolve("in.yaml")), "the committed bytes, not the working folder's");
    }
}
