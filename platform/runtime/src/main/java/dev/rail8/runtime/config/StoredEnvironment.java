package dev.rail8.runtime.config;

import dev.rail8.api.Health;
import dev.rail8.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@link Environment} of a node over the configuration store. It reads the store at its start, at each notice on
 * {@code rail8_config}, after each {@code LISTEN} acknowledged and at each tick of {@value #TICK_SECONDS} s; one read runs at a
 * time, and a notice during it makes one more. Each read that finds a change swaps in a new immutable snapshot and calls the
 * listeners. After each read the node writes into its row of {@code nodes} the generation every listener has taken: a listener
 * that throws has not taken its snapshot, and is called again with it at each read until it takes it, while the row keeps the
 * generation before. So the row tells a change committed from a change this node holds. A write of the row that fails is tried
 * again at the next read.
 *
 * <p>An object is left out of the snapshot, with its reason, when its binding does not verify, when its format is one this
 * node does not read, or when it is mocked while the environment does not allow mocks. A node that cannot read the store
 * keeps its last good snapshot; {@link #health()} is {@code DEGRADED} after {@value #STALE_SECONDS} s without a read, and while
 * a binding fails.
 *
 * @since 0.4
 */
public final class StoredEnvironment implements Environment {

    static final long TICK_SECONDS = 5;
    static final long STALE_SECONDS = 30;
    /** The time between two reads when no notice comes. */
    public static final Duration TICK = Duration.ofSeconds(TICK_SECONDS);
    /** The time without a read after which the node is {@code DEGRADED}. */
    public static final Duration STALE = Duration.ofSeconds(STALE_SECONDS);

    private static final int MAX_NAMED = ConfigObjects.MAX_REFUSED;
    private static final Pattern DELAY = Pattern.compile("([0-9]{1,5})(ms|s)");
    private static final String NOT_VERIFIED = "its binding does not verify: save and activate it again";
    private static final String MOCKS_REFUSED = "it is mocked and the environment does not allow mocks";
    private static final System.Logger LOG = System.getLogger(StoredEnvironment.class.getName());

    private final String name;
    private final ConfigStore store;
    private final String node;
    private final LongSupplier epoch;
    private final Clock clock;
    private final Timer timer;
    private final Consumer<String> warn;
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile Snapshot held = Snapshot.empty();
    private volatile Instant lastGood;
    private volatile int unverified;
    /** What the node holds of the store, touched only by the one read that runs. */
    private ConfigStore.Held holding = ConfigStore.Held.nothing();
    private final Map<Ref, ConfigStore.InForce> bodies = new HashMap<>();
    /** The objects refused for a binding that does not verify, each with the key id its WARNING named. */
    private Map<Ref, String> warned = Map.of();
    private boolean failing;
    /** The listeners that have not taken {@link #held}, touched only by the one read that runs. */
    private List<Consumer<Snapshot>> untaken = List.of();
    /** The last snapshot every listener took, with the stamp it was read at: what the node reports it holds. */
    private Snapshot taken = Snapshot.empty();
    private long takenGeneration = ConfigStore.Held.nothing().generation();
    private long takenStamp = ConfigStore.Held.nothing().stamp();
    /** The generation the untaken listeners were last given. */
    private long takenAttempt = ConfigStore.Held.nothing().generation();

    private Promise<Void> queued;
    private boolean reading;
    private boolean closed;
    private Timer.Cancellable tick;
    private volatile Future<Void> ticked = Future.succeededFuture();

    /**
     * The environment {@code name} of node {@code node} over {@code store}; {@code epoch} gives the node's epoch at each report.
     * Nothing is read before {@link #start()}.
     */
    public StoredEnvironment(String name, ConfigStore store, String node, LongSupplier epoch, Clock clock, Timer timer, Consumer<String> warn) {
        this.name = Objects.requireNonNull(name, "name");
        this.store = Objects.requireNonNull(store, "store");
        this.node = Objects.requireNonNull(node, "node");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** The environment of a node, its warnings logged. */
    public static StoredEnvironment of(String name, ConfigStore store, String node, LongSupplier epoch, Clock clock, Timer timer) {
        return new StoredEnvironment(name, store, node, epoch, clock, timer, text -> LOG.log(System.Logger.Level.WARNING, text));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Snapshot current() {
        return held;
    }

    @Override
    public void onActivation(Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Reads the whole store and writes the node's row, on the caller's virtual thread, then arms the tick: the node calls it
     * before it restores its packages and takes any work.
     *
     * @throws RuntimeException the store could not be read
     */
    public void start() {
        synchronized (lock) {
            reading = true;
        }
        boolean more;
        try {
            once();
        } finally {
            synchronized (lock) {
                more = queued != null;   // a notice during this read: it runs one more
                reading = more;
            }
        }
        if (more) Thread.ofVirtual().name("rail8-config-read").start(this::drain);
        arm();
    }

    /** Stops the tick. */
    public void close() {
        synchronized (lock) {
            closed = true;
            if (tick != null) tick.cancel();
        }
    }

    /**
     * A change may have happened: a notice, or a {@code LISTEN} acknowledged. Completes once a read that started after this call
     * has ended, and fails when that read failed; a writer awaits it so that its change holds on this node when it answers.
     */
    public Future<Void> notice() {
        Promise<Void> mine;
        boolean begin;
        synchronized (lock) {
            if (queued != null) return queued.future();
            queued = Promise.promise();
            mine = queued;
            begin = !reading;
            reading = true;
        }
        if (begin) Thread.ofVirtual().name("rail8-config-read").start(this::drain);
        return mine.future();
    }

    /** The read the last tick started: tests wait on it after they move the clock. */
    Future<Void> ticked() {
        return ticked;
    }

    /**
     * {@code DEGRADED} after {@value #STALE_SECONDS} s without a read of the store, naming its last read, and while the binding of
     * an object in force does not verify.
     */
    public Health health() {
        Instant last = lastGood;
        if (last == null || !last.plus(STALE).isAfter(clock.instant())) {
            return Health.degraded("the configuration store could not be read for " + STALE_SECONDS + " s: this node sends by what it read "
                    + (last == null ? "never" : "at " + last));
        }
        int failed = unverified;
        if (failed > 0) {
            return Health.degraded(failed + " objects of the configuration do not verify and are left out: save and activate each again");
        }
        return Health.up();
    }

    private void arm() {
        synchronized (lock) {
            if (closed) return;
            tick = timer.schedule(TICK, () -> {
                arm();
                ticked = notice();
            });
        }
    }

    private void drain() {
        while (true) {
            Promise<Void> next;
            synchronized (lock) {
                next = queued;
                queued = null;
                if (next == null) {
                    reading = false;
                    return;
                }
            }
            try {
                once();
                next.complete();
            } catch (RuntimeException failure) {
                next.fail(failure);
            }
        }
    }

    /** One read: the store against what the node holds, a new snapshot when it changed, and the node's report when it differs. */
    private void once() {
        ConfigStore.State state;
        try {
            state = store.state(holding);
        } catch (RuntimeException failure) {
            if (!failing) {
                failing = true;
                warn.accept("the configuration store could not be read: this node sends by what it read "
                        + (lastGood == null ? "never" : "at " + lastGood) + " and reads again at each tick; " + failure.getClass().getName());
            }
            throw failure;
        }
        failing = false;
        lastGood = clock.instant();
        boolean same = state.generation() == holding.generation() && state.stamp() == holding.stamp();
        if (!same) {
            if (holding.generation() >= 0 && state.generation() <= holding.generation()) {
                warn.accept("the configuration store went back to an earlier state: generation " + state.generation() + ", where this node held "
                        + holding.generation() + "; the node holds what it read now");
            }
            swap(state);
        } else if (!untaken.isEmpty()) {
            tell(untaken);
        }
        report();
    }

    private void swap(ConfigStore.State state) {
        for (Ref gone : state.gone()) bodies.remove(gone);
        for (ConfigStore.InForce f : state.changed()) bodies.put(f.ref(), f);
        Map<Ref, ConfigStore.Holding> objects = new HashMap<>();
        bodies.forEach((ref, f) -> objects.put(ref, new ConfigStore.Holding(f.activation(), f.stopped())));
        holding = new ConfigStore.Held(state.generation(), state.stamp(), objects);

        Map<String, ConnectionState> connections = new HashMap<>();
        Map<String, String> instanceNames = new HashMap<>();
        Map<Ref, String> leftOut = new LinkedHashMap<>();
        for (Ref ref : state.refused().objects()) leftOut.put(ref, NOT_VERIFIED);
        int count = state.refused().count();
        List<ConfigStore.InForce> sorted = new ArrayList<>(bodies.values());
        sorted.sort(Comparator.comparing((ConfigStore.InForce f) -> f.ref().kind()).thenComparing(f -> f.ref().key()));
        for (ConfigStore.InForce f : sorted) {
            Optional<String> reason = take(f, state.mocksAllowed(), connections, instanceNames);
            if (reason.isEmpty()) continue;
            count++;
            if (leftOut.size() < MAX_NAMED) leftOut.put(f.ref(), reason.get());
        }
        unverified = state.refused().count();
        warnUnverified(state);
        held = new Snapshot(state.generation(), state.mocksAllowed(), connections, instanceNames, leftOut, count);
        tell(List.copyOf(listeners));
    }

    /**
     * Gives {@link #held} to each of {@code those}, in order; those that throw stay untaken, with one WARNING for the generation.
     * Once none is left, {@link #held} is what the node reports it holds.
     */
    private void tell(List<Consumer<Snapshot>> those) {
        Snapshot now = held;
        boolean again = !untaken.isEmpty() && now.generation() == takenAttempt;
        List<Consumer<Snapshot>> failed = new ArrayList<>();
        RuntimeException first = null;
        for (Consumer<Snapshot> listener : those) {
            try {
                listener.accept(now);
            } catch (RuntimeException failure) {
                failed.add(listener);
                if (first == null) first = failure;
            }
        }
        untaken = List.copyOf(failed);
        takenAttempt = now.generation();
        if (first != null && !again) {
            warn.accept(failed.size() + " listeners of the configuration did not take generation " + now.generation() + " ("
                    + first.getClass().getName() + "): this node reports generation " + takenGeneration + " as held and calls them again at each read");
        }
        if (failed.isEmpty()) {
            taken = now;
            takenGeneration = holding.generation();
            takenStamp = holding.stamp();
        }
    }

    /** Takes {@code f} into the snapshot's maps; the reason it is left out when it is. */
    private static Optional<String> take(ConfigStore.InForce f, boolean mocksAllowed, Map<String, ConnectionState> connections,
                                         Map<String, String> instanceNames) {
        if (f.format() != ConfigFiles.STANDARD.newest(f.ref().kind())) return Optional.of("its format " + f.format() + " is not one this node reads");
        JsonObject body = f.body();
        if (f.ref().kind() == Ref.Kind.SYSTEM) {
            instanceNames.put(f.ref().key(), body.getString("instanceName"));
            return Optional.empty();
        }
        String adapter = body.getString("adapter");
        if (body.getValue("mock") instanceof JsonObject mock) {
            if (!mocksAllowed) return Optional.of(MOCKS_REFUSED);
            connections.put(f.ref().key(), new ConnectionState.Mocked(adapter, f.activation(), behaviour(mock), f.stopped()));
            return Optional.empty();
        }
        Map<String, Object> values = new TreeMap<>();
        if (body.getValue("values") instanceof JsonObject v) {
            for (Map.Entry<String, Object> e : v) {
                values.put(e.getKey(), e.getValue() instanceof JsonArray a ? List.copyOf(a.getList()) : e.getValue());
            }
        }
        connections.put(f.ref().key(), new ConnectionState.Values(adapter, f.activation(), values, f.stopped()));
        return Optional.empty();
    }

    /** A stored mock {@code {reply, delay?, fail?}} as its behaviour. */
    static ConnectionState.MockBehaviour behaviour(JsonObject mock) {
        ConnectionState.MockBehaviour.Reply reply = "empty".equals(mock.getString("reply"))
                ? ConnectionState.MockBehaviour.Reply.EMPTY : ConnectionState.MockBehaviour.Reply.ECHO;
        Duration delay = Duration.ZERO;
        Matcher m = DELAY.matcher(mock.getString("delay", ""));
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            delay = m.group(2).equals("ms") ? Duration.ofMillis(n) : Duration.ofSeconds(n);
        }
        Optional<ConnectionState.MockBehaviour.Failure> fail = switch (mock.getString("fail", "")) {
            case "retryable" -> Optional.of(ConnectionState.MockBehaviour.Failure.RETRYABLE);
            case "invalid" -> Optional.of(ConnectionState.MockBehaviour.Failure.INVALID);
            default -> Optional.empty();
        };
        return new ConnectionState.MockBehaviour(reply, delay, fail);
    }

    /** A WARNING once for each object newly refused for a binding that does not verify, naming its key and the key's id. */
    private void warnUnverified(ConfigStore.State state) {
        Map<Ref, String> now = new HashMap<>();
        for (Ref ref : state.refused().objects()) {
            String keyId = state.refusedKeys().getOrDefault(ref, "none");
            now.put(ref, keyId);
            if (!keyId.equals(warned.get(ref))) {
                warn.accept("the version in force of " + ref + ", bound under the key " + keyId + ", does not verify: this node leaves it"
                        + " out; save and activate it again");
            }
        }
        warned = now;
    }

    /**
     * Writes what the node holds, the last generation every listener took, into its row after each read that succeeded, so the
     * row says when the node last read the store; a failure is a WARNING, tried again at the next read.
     */
    private void report() {
        Snapshot now = taken;
        try {
            store.holds(node, epoch.getAsLong(), takenGeneration, takenStamp,
                    new ConfigStore.Refused(List.copyOf(now.leftOut().keySet()), now.leftOutCount()));
        } catch (RuntimeException failure) {
            warn.accept("what this node holds of the configuration could not be written to its row: the next read writes it again; "
                    + failure.getClass().getName());
        }
    }

    @Override
    public String toString() {
        return "StoredEnvironment[" + name + ", " + node + "]";
    }
}
