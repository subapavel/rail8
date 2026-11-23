package dev.rail8.runtime.cache;

import dev.rail8.api.Cache;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Timer;
import dev.rail8.core.cache.Caches;
import dev.rail8.core.cache.InMemoryCache;
import dev.rail8.runtime.journal.ChunkedAead;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;

/**
 * The node's cache for custom code in Valkey, shared by the nodes of a pool: a token one node loads serves every node.
 *
 * <p><b>On the node.</b> Each scope sits behind a scope of an {@link InMemoryCache}, the front, which checks the key and the
 * thread, loads a key once at a time on the node and catches a loader that asks for its own key. While Valkey answers, the
 * front keeps nothing: what it hands its callers comes from Valkey or from the one load that ran.
 *
 * <p><b>On Valkey.</b> The keys of a scope start with {@code rail8:cache:{<tenant>/<package>}:}, one hash tag for a package,
 * then:
 * <ul>
 *   <li>{@code v:<step type>:<key>}, a hash: {@code x}, the value's expiry in epoch milliseconds on the writer's clock, and
 *       {@code c}, the sealed value; its PX is the value's ttl, so Valkey drops it on its own;</li>
 *   <li>{@code l:<step type>:<key>}, the key's lock: {@code run <owner>} while a node loads it, {@code failed <owner>} for one
 *       lease after that load failed; its PX is the lease;</li>
 *   <li>{@code z}, the package's entries by their expiry on Valkey's clock, and {@code n}, each entry's bytes and their total
 *       under {@code #}.</li>
 * </ul>
 * The step type is URL-encoded, so it holds no {@code :}, and the key comes last. An owner is the node's id and an id of its
 * own. Each change is one script, which Valkey runs with nothing in between.
 *
 * <p><b>A get.</b> A value whose expiry has not passed on this node's clock is a hit. Otherwise the caller takes the lock and
 * runs its loader, renewing the lock every {@link #RENEW} while the loader runs. The value is written only while the lock
 * still holds its owner, so a load whose key was removed, or whose lease lapsed, keeps nothing. A caller that finds the lock
 * held by another node asks again, first after {@link #FIRST_POLL}, doubling to {@link #LAST_POLL}, until the value is there
 * or the lock is free; after {@link #MAX_WAIT} on its clock it fails. A lock left by a node that stopped lapses within
 * {@link #LEASE}. When the load it waited for failed, it fails with an {@link IllegalStateException} naming the key.
 *
 * <p><b>The share.</b> A package keeps at most {@link #SHARE_ENTRIES} entries and {@link #SHARE_BYTES} bytes (each entry's
 * value key, expiry and sealed value). A write first forgets up to 64 entries that Valkey's clock says have expired; then,
 * while the package is over its share, the entry nearest its expiry, other than the one written, goes.
 *
 * <p><b>Sealing.</b> A value is AES-256-GCM under a key {@link ChunkedAead#derive derived} from the master key, which the
 * nodes of a pool share, with a fresh salt per value. Its AAD is the value key's name and its expiry, so a value moved to
 * another key, or given a later expiry, does not open; such a value is logged once and loaded again.
 *
 * <p><b>Valkey down.</b> Every call waits at most {@link #CALL} on the injected timer. A call that fails runs the load on this
 * node and keeps the value in the front for at most {@link #OUTAGE_KEEP}, so the calls after it do not wait on Valkey. One
 * warning is logged when Valkey stops answering, one line when it answers again. A remove that does not reach Valkey is
 * logged; the value then ages out on its ttl.
 *
 * @since 0.1
 */
public final class ValkeyCache implements Caches {

    /** The most entries a package keeps. */
    static final long SHARE_ENTRIES = 1_000;
    /** The most bytes a package keeps. */
    static final long SHARE_BYTES = 8L * 1024 * 1024;
    /** How long a lock lasts without a renewal. */
    static final Duration LEASE = Duration.ofSeconds(10);
    /** How often a node renews the lock of a load it runs. */
    static final Duration RENEW = Duration.ofSeconds(3);
    /** The first pause of a caller waiting for another node's load. */
    static final Duration FIRST_POLL = Duration.ofMillis(10);
    /** The longest pause of a caller waiting for another node's load. */
    static final Duration LAST_POLL = Duration.ofMillis(200);
    /** The longest a caller waits for another node's load. */
    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    /** The longest a call to Valkey may take. */
    static final Duration CALL = Duration.ofSeconds(1);
    /** The longest the front keeps a value while Valkey does not answer. */
    static final Duration OUTAGE_KEEP = Duration.ofMinutes(1);
    /** The namespace of the cache in Valkey. */
    static final String PREFIX = "rail8:cache:";

    /** A tenant or a package: the characters of a package name, so the hash tag is never ambiguous. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final String INFO = "rail8 cache v1";
    private static final int SALT_BYTES = ChunkedAead.SALT_BYTES;
    /** Stripes of the count of removes: a load whose stripe saw a remove since it began keeps nothing. */
    private static final int STRIPES = 256;
    /** How long the package's bookkeeping outlives its last write: every entry has gone by then. */
    private static final Duration BOOKKEEPING = Cache.MAX_TTL.plus(LEASE);
    private static final System.Logger LOG = System.getLogger(ValkeyCache.class.getName());

    /**
     * KEYS[1] the value, KEYS[2] the lock; ARGV[1] now on the node's clock (ms), ARGV[2] the owner, ARGV[3] the lease (ms),
     * ARGV[4] the owner the caller waited for ('' none), ARGV[5] '1' to pass over a kept value. {'hit', x, c}, {'failed'},
     * {'wait', owner}, or {'run'} with the lock taken.
     */
    private static final String READ = """
            if ARGV[5] == '0' then
              local kept = redis.call('HMGET', KEYS[1], 'x', 'c')
              if kept[2] and tonumber(kept[1]) > tonumber(ARGV[1]) then return {'hit', kept[1], kept[2]} end
            end
            local held = redis.call('GET', KEYS[2])
            if held then
              if held == 'failed ' .. ARGV[4] then return {'failed'} end
              if string.sub(held, 1, 4) == 'run ' then return {'wait', string.sub(held, 5)} end
            end
            redis.call('SET', KEYS[2], 'run ' .. ARGV[2], 'PX', ARGV[3])
            return {'run'}""";

    /** KEYS[1] the lock; ARGV[1] the owner, ARGV[2] the lease (ms): 1 renewed, 0 the lock is not the owner's. */
    private static final String RENEW_LOCK = """
            if redis.call('GET', KEYS[1]) == 'run ' .. ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0""";

    /** KEYS[1] the lock; ARGV[1] the owner, ARGV[2] '1' the load failed, ARGV[3] the lease (ms): 1 ended, 0 not the owner's. */
    private static final String END = """
            if redis.call('GET', KEYS[1]) ~= 'run ' .. ARGV[1] then return 0 end
            if ARGV[2] == '1' then
              redis.call('SET', KEYS[1], 'failed ' .. ARGV[1], 'PX', ARGV[3])
            else
              redis.call('DEL', KEYS[1])
            end
            return 1""";

    /**
     * KEYS[1] the value, KEYS[2] the lock, KEYS[3] the package's entries by expiry, KEYS[4] their bytes; ARGV[1] the owner,
     * ARGV[2] x, ARGV[3] c, ARGV[4] the ttl (ms), ARGV[5] the entry, ARGV[6] the prefix of the package's value keys, ARGV[7]
     * the most entries, ARGV[8] the most bytes, ARGV[9] the bookkeeping's ttl (ms): 1 kept, 0 the lock is not the owner's.
     */
    private static final String WRITE = """
            if redis.call('GET', KEYS[2]) ~= 'run ' .. ARGV[1] then return 0 end
            redis.call('DEL', KEYS[2])
            local function forget(m)
              local size = redis.call('HGET', KEYS[4], m)
              if size then
                redis.call('HDEL', KEYS[4], m)
                redis.call('HINCRBY', KEYS[4], '#', -tonumber(size))
              end
              redis.call('ZREM', KEYS[3], m)
            end
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            for _, m in ipairs(redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', now, 'LIMIT', 0, 64)) do forget(m) end
            forget(ARGV[5])
            local size = #KEYS[1] + #ARGV[2] + #ARGV[3]
            redis.call('HSET', KEYS[1], 'x', ARGV[2], 'c', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('ZADD', KEYS[3], now + tonumber(ARGV[4]), ARGV[5])
            redis.call('HSET', KEYS[4], ARGV[5], size)
            redis.call('HINCRBY', KEYS[4], '#', size)
            while redis.call('ZCARD', KEYS[3]) > tonumber(ARGV[7])
                or tonumber(redis.call('HGET', KEYS[4], '#') or '0') > tonumber(ARGV[8]) do
              local first = redis.call('ZRANGE', KEYS[3], 0, 1)
              local victim = first[1]
              if victim == ARGV[5] then victim = first[2] end
              if not victim then break end
              forget(victim)
              redis.call('DEL', ARGV[6] .. victim)
            end
            redis.call('PEXPIRE', KEYS[3], ARGV[9])
            redis.call('PEXPIRE', KEYS[4], ARGV[9])
            return 1""";

    /** KEYS[1] the value, KEYS[2] the lock, KEYS[3] the package's entries, KEYS[4] their bytes; ARGV[1] the entry. */
    private static final String REMOVE = """
            redis.call('DEL', KEYS[1], KEYS[2])
            local size = redis.call('HGET', KEYS[4], ARGV[1])
            if size then
              redis.call('HDEL', KEYS[4], ARGV[1])
              redis.call('HINCRBY', KEYS[4], '#', -tonumber(size))
            end
            redis.call('ZREM', KEYS[3], ARGV[1])
            return 1""";

    private final Redis redis;
    private final SecretKey master;
    private final String nodeId;
    private final Clock clock;
    private final Timer timer;
    private final IdGenerator ids;
    private final InMemoryCache front;
    private final AtomicLongArray removals = new AtomicLongArray(STRIPES);
    private final AtomicBoolean down = new AtomicBoolean();
    private final AtomicBoolean unopened = new AtomicBoolean();
    private final SecureRandom random = new SecureRandom();

    /**
     * A cache over {@code redis} that does no I/O yet.
     *
     * @param redis  the node's client: it stays the caller's, who closes it
     * @param master the master key the nodes of the pool share; the values are sealed under keys derived from it
     * @param nodeId the node's id, part of the owner of each lock it takes
     * @param clock  the expiry of the values and the bound of a wait
     * @param timer  the bound of each call, the pauses of a wait and the renewals of a lock
     * @param ids    the other part of the owner of each lock
     * @throws IllegalArgumentException when {@code master} is not an encodable AES key
     */
    public ValkeyCache(Redis redis, SecretKey master, String nodeId, Clock clock, Timer timer, IdGenerator ids) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.master = Objects.requireNonNull(master, "master");
        try {
            ChunkedAead.material(master, "the master key");
        } catch (InvalidKeyException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.front = new InMemoryCache(clock, InMemoryCache.MAX_ENTRIES, InMemoryCache.MAX_BYTES);
    }

    /**
     * @throws IllegalArgumentException naming the tenant or the package when it is not 1 to 64 lower-case letters, digits and
     *                                  hyphens, starting with a letter
     */
    @Override
    public Cache scope(String tenant, String packageName, String stepType) {
        for (String name : new String[]{Objects.requireNonNull(tenant, "tenant"), Objects.requireNonNull(packageName, "packageName")}) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a tenant or a package of the cache: 1 to 64 of a-z, 0-9 and -, starting with a letter");
            }
        }
        Objects.requireNonNull(stepType, "stepType");
        return new Scoped(new Scope(tenant, packageName, stepType), front.scope(tenant, packageName, stepType));
    }

    /** One scope's names on Valkey. */
    private record Scope(String tenant, String packageName, String stepType) {
        String base() {
            return PREFIX + "{" + tenant + "/" + packageName + "}:";
        }

        Keys keys(String key) {
            String entry = URLEncoder.encode(stepType, StandardCharsets.UTF_8) + ":" + key;
            String base = base();
            return new Keys(entry, base + "v:" + entry, base + "l:" + entry, base + "z", base + "n", base + "v:");
        }
    }

    /** The names of one key: its entry, its value, its lock, the package's entries and their bytes, the prefix of the values. */
    private record Keys(String entry, String value, String lock, String entries, String bytes, String values) {}

    private final class Scoped implements Cache {
        private final Scope scope;
        private final Cache local;

        Scoped(Scope scope, Cache local) {
            this.scope = scope;
            this.local = local;
        }

        @Override
        public String get(String key, Loader loader) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(loader, "loader");
            int stripe = stripe(scope, key);
            long seen = removals.get(stripe);   // before the front: a remove from here on is seen by the load
            return local.get(key, () -> shared(scope, key, loader, stripe, seen));
        }

        @Override
        public void remove(String key) {
            local.remove(key);   // checks the key and the thread; forgets the node's load under way and what an outage kept
            removals.incrementAndGet(stripe(scope, key));
            Keys k = scope.keys(key);
            try {
                call(eval(REMOVE, k.value(), k.lock(), k.entries(), k.bytes()).arg(k.entry()));
            } catch (Unreachable unreachable) {
                down(unreachable.getCause());
                LOG.log(System.Logger.Level.WARNING, "cache key '" + key + "' of " + scope.packageName() + ", " + scope.stepType()
                        + " was not removed on Valkey: it ages out on its ttl");
            }
        }

        /** The scope's package and step type, never a key or a value. */
        @Override
        public String toString() {
            return "ValkeyCache[" + scope.packageName() + ", " + scope.stepType() + "]";
        }
    }

    /**
     * The value of {@code key} through Valkey, for the front: with a ttl of zero, so the front keeps nothing, unless Valkey did
     * not answer.
     */
    private Cache.Entry shared(Scope scope, String key, Cache.Loader loader, int stripe, long seen) throws Exception {
        Keys k = scope.keys(key);
        String owner = nodeId + " " + ids.next();
        Instant giveUp = clock.instant().plus(MAX_WAIT);
        Duration pause = FIRST_POLL;
        String waited = "";
        boolean passOver = false;
        while (true) {
            Response read;
            try {
                read = call(eval(READ, k.value(), k.lock()).arg(clock.millis()).arg(owner).arg(LEASE.toMillis()).arg(waited)
                        .arg(passOver ? "1" : "0"));
            } catch (Unreachable unreachable) {
                down(unreachable.getCause());
                return alone(key, loader);
            }
            switch (read.get(0).toString()) {
                case "hit" -> {
                    Optional<String> value = open(k, read.get(1).toString(), read.get(2).toBytes());
                    if (value.isPresent()) return new Cache.Entry(value.get(), Duration.ZERO);
                    passOver = true;
                }
                case "failed" -> throw new IllegalStateException("the load of cache key '" + key + "' on another node, which this call waited for, failed");
                case "wait" -> {
                    if (!clock.instant().isBefore(giveUp)) {
                        throw new IllegalStateException("cache key '" + key + "' was still loading on another node after " + MAX_WAIT);
                    }
                    waited = read.get(1).toString();
                    passOver = false;   // the load waited for writes a value of its own
                    pause(pause);
                    pause = pause.multipliedBy(2).compareTo(LAST_POLL) < 0 ? pause.multipliedBy(2) : LAST_POLL;
                }
                case "run" -> {
                    return load(k, key, loader, owner, stripe, seen);
                }
                default -> throw new IllegalStateException("Valkey answered the cache's read of '" + key + "' with " + read.get(0));
            }
        }
    }

    /** Runs {@code loader} under the lock {@code owner} holds, and keeps its value on Valkey unless the key was removed meanwhile. */
    private Cache.Entry load(Keys k, String key, Cache.Loader loader, String owner, int stripe, long seen) throws Exception {
        Renewal renewal = new Renewal(k.lock(), owner);
        Cache.Entry entry;
        try {
            entry = loader.load();
            if (entry == null) throw new IllegalStateException("the loader of cache key '" + key + "' returned null");
        } catch (Throwable failure) {
            renewal.stop();
            end(k, owner, true);
            throw failure;
        }
        renewal.stop();
        if (!entry.ttl().isPositive() || removals.get(stripe) != seen) {
            end(k, owner, false);
            return new Cache.Entry(entry.value(), Duration.ZERO);
        }
        Instant now = clock.instant();
        long until = now.plus(entry.ttl()).toEpochMilli();   // in whole ms, never after the ttl has passed
        long px = Math.max(1, Math.ceilDiv(entry.ttl().toNanos(), 1_000_000L));
        String x = Long.toString(until);
        try {
            call(eval(WRITE, k.value(), k.lock(), k.entries(), k.bytes()).arg(owner).arg(x).arg(seal(k, x, entry.value())).arg(px)
                    .arg(k.entry()).arg(k.values()).arg(SHARE_ENTRIES).arg(SHARE_BYTES).arg(BOOKKEEPING.toMillis()));
        } catch (Unreachable unreachable) {
            down(unreachable.getCause());
            return new Cache.Entry(entry.value(), shorter(entry.ttl()));
        }
        return new Cache.Entry(entry.value(), Duration.ZERO);
    }

    /** Frees the lock {@code owner} holds, marking a failed load; Valkey out of reach is logged. */
    private void end(Keys k, String owner, boolean failed) {
        try {
            call(eval(END, k.lock()).arg(owner).arg(failed ? "1" : "0").arg(LEASE.toMillis()));
        } catch (Unreachable unreachable) {
            down(unreachable.getCause());
        }
    }

    /** The value of {@code key} while Valkey does not answer: loaded here, kept in the front for at most {@link #OUTAGE_KEEP}. */
    private static Cache.Entry alone(String key, Cache.Loader loader) throws Exception {
        Cache.Entry entry = loader.load();
        if (entry == null) throw new IllegalStateException("the loader of cache key '" + key + "' returned null");
        return new Cache.Entry(entry.value(), shorter(entry.ttl()));
    }

    private static Duration shorter(Duration ttl) {
        return ttl.compareTo(OUTAGE_KEEP) < 0 ? ttl : OUTAGE_KEEP;
    }

    private static int stripe(Scope scope, String key) {
        return Math.floorMod(Objects.hash(scope, key), STRIPES);
    }

    /** The salt, then {@code value} sealed under the key the salt derives, with the value key's name and {@code x} as AAD. */
    private byte[] seal(Keys k, String x, String value) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, salt, k, x);
            byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(salt, SALT_BYTES + sealed.length);
            System.arraycopy(sealed, 0, out, SALT_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException impossible) {   // AES-256-GCM is in every JDK, and the key was checked
            throw new IllegalStateException("the cache could not seal a value", impossible);
        }
    }

    /** The value {@code c} holds, or empty when it does not open: logged once. */
    private Optional<String> open(Keys k, String x, byte[] c) {
        try {
            if (c.length < SALT_BYTES + ChunkedAead.TAG_BYTES) throw new GeneralSecurityException("too short");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(c, SALT_BYTES), k, x);
            return Optional.of(new String(cipher.doFinal(c, SALT_BYTES, c.length - SALT_BYTES), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException notOpened) {
            if (unopened.compareAndSet(false, true)) {
                LOG.log(System.Logger.Level.WARNING, "a value of the cache of custom code on Valkey does not open (" + notOpened.getClass().getSimpleName()
                        + "): another master key, or a changed value; it is loaded again. This is logged once");
            }
            return Optional.empty();
        }
    }

    private Cipher cipher(int mode, byte[] salt, Keys k, String x) throws GeneralSecurityException {
        Cipher cipher = ChunkedAead.cipher(ChunkedAead.derive(master, salt, INFO, "the master key"), mode, salt, ChunkedAead.LAST);
        cipher.updateAAD((k.value() + "\0" + x).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static Request eval(String script, String... keys) {
        Request request = Request.cmd(Command.EVAL).arg(script).arg(keys.length);
        for (String key : keys) request.arg(key.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** {@code request}'s answer, within {@link #CALL} on the timer; a failure, a timeout or no answer is {@link Unreachable}. */
    private Response call(Request request) {
        CompletableFuture<Response> answer = new CompletableFuture<>();
        Timer.Cancellable deadline = timer.schedule(CALL, () -> answer.completeExceptionally(new TimeoutException("Valkey did not answer within " + CALL)));
        redis.send(request).onComplete(done -> {
            deadline.cancel();
            if (done.succeeded()) answer.complete(done.result());
            else answer.completeExceptionally(done.cause());
        });
        Response response;
        try {
            response = answer.get();
        } catch (ExecutionException failed) {
            throw new Unreachable(failed.getCause());
        } catch (InterruptedException interrupted) {   // thrown as await() throws it: undeclared, the flag cleared
            throw ValkeyCache.<RuntimeException>undeclared(interrupted);
        }
        if (response == null) throw new Unreachable(new IllegalStateException("Valkey gave no answer"));
        if (down.compareAndSet(true, false)) LOG.log(System.Logger.Level.INFO, "Valkey answers the cache of custom code again");
        return response;
    }

    private void down(Throwable cause) {
        if (down.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "Valkey does not answer the cache of custom code (" + cause + "): this node loads what its steps ask for "
                    + "and keeps it in its own memory for at most " + OUTAGE_KEEP + " until Valkey answers");
        }
    }

    /** Parks the caller for {@code pause} on the timer. */
    private void pause(Duration pause) {
        CompletableFuture<Void> due = new CompletableFuture<>();
        Timer.Cancellable wake = timer.schedule(pause, () -> due.complete(null));
        try {
            due.get();
        } catch (InterruptedException interrupted) {
            wake.cancel();
            throw ValkeyCache.<RuntimeException>undeclared(interrupted);
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The renewals of a lock while its owner's loader runs: each on the timer, sent without waiting for its answer. */
    private final class Renewal {
        private final String lock;
        private final String owner;
        private boolean stopped;
        private Timer.Cancellable next;

        Renewal(String lock, String owner) {
            this.lock = lock;
            this.owner = owner;
            schedule();
        }

        private synchronized void schedule() {
            if (!stopped) next = timer.schedule(RENEW, this::renew);
        }

        private void renew() {
            synchronized (this) {
                if (stopped) return;
            }
            redis.send(eval(RENEW_LOCK, lock).arg(owner).arg(LEASE.toMillis())).onComplete(done -> {
                // a lock that is no longer the owner's is not renewed again; a failed call is tried again at the next tick
                if (done.failed() || (done.result() != null && done.result().toLong() == 1)) schedule();
            });
        }

        synchronized void stop() {
            stopped = true;
            if (next != null) next.cancel();
        }
    }

    /** Valkey failed a call, did not answer it in time, or gave no answer. */
    private static final class Unreachable extends RuntimeException {
        Unreachable(Throwable cause) {
            super(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T undeclared(Throwable failure) throws T {
        throw (T) failure;
    }
}
