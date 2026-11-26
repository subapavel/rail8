package dev.rail8.cli;

import dev.rail8.api.Ack;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Envelope;
import dev.rail8.api.Expressions;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.StepResources;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.components.mock.Behaviour;
import dev.rail8.components.mock.MockComponent;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.key.Keys;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.Environment;
import dev.rail8.runtime.deploy.DeployRefusedException;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Secrets;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.schemas.Documents;
import dev.rail8.schemas.Package;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code rail8 test <dir> [--deps <dir>] [--plugins <dir>]}. First what {@code rail8 validate} checks, in the environment {@code ci}
 * — the package's problems, its dependencies' closure and the dry build over a node's modules and its plugins — and what only a test
 * run asks of its test documents: a {@code recorded}
 * or {@code script} behaviour (not implemented yet), {@code expect.status} on a flow whose from endpoint is not http, an
 * {@code xpath} that does not compile; any problem is printed as validate prints it and no case runs (exit 1).
 *
 * <p>Then every {@code kind: test} document in path order, its cases in order, one at a time, each on a world of its own: a
 * fresh fake runtime (clock, timer, ids, blobs), one {@link MockComponent} per scheme, a plugin's included, bound to the document's
 * {@code mocks} — a case's entry for an endpoint or a connection replaces the document's whole; a test of a flow of
 * {@code schemaVersion: 2} keys them by connection key, and its node's environment serves every connection a flow names
 * through the mock of its adapter —, the real {@code direct} component (the direct
 * module's, built over the case's runtime and the run's one Vert.x) in front of
 * the {@code direct} mock ({@link Calls}: a call to a flow of the package or of a dependency runs that flow, unless the case's
 * mocks name its endpoint), in-memory stores, and a node
 * ({@link Deployments}) of environment {@code ci} over a temporary data directory, deleted after the case, to which the
 * dependencies' closure validate resolved is deployed leaves first, then the package, each with no origin
 * ({@code Deployments.deploy(name, folder)}: no archive, no record) from a copy of its folder that the run makes once, before
 * its first case, in a temporary directory it owns and deletes when it ends: an edit of a package folder during the run changes
 * nothing in it, and a case reads its fixtures and its mocks' files from that copy too. The case's {@code send} — the fixture's bytes and
 * its {@code headers} — is {@link MockComponent#ingest ingested} on the flow's from endpoint, or, on a real {@code direct} one,
 * {@linkplain Calls#ingest sent through a direct producer} as a caller at depth 0 would; a case without one is fed by that
 * endpoint's mock, whose {@code files} or {@code messages} its consumer polls from its start (the package's cross-check refuses
 * a case that neither sends nor is fed). The case settles on virtual time ({@link VirtualTime}: settled = the answer
 * complete — a fed case has none —, no file of the from endpoint {@linkplain MockComponent#undelivered undelivered}, no
 * work-queue row that can still run, of any flow the case's message reached, and no delivery of the node still
 * {@linkplain Deployments#inFlight in flight} — an at-most-once run waiting on a timer, a retry's backoff say, answered Accepted
 * at once and left no row —, on a quiet world: a stopped key's rows wait for an operator, who never comes in a test) and its {@code expect} is evaluated ({@link Expectations}; {@code steps} and {@code keys}
 * read the first message the from endpoint delivered; {@code sent}, {@code retries} and {@code dlq} count every flow's sends and
 * rows, a real call between flows a send too); then the node is closed, on virtual time too.
 *
 * <p>The report, on stdout: {@code PASS|FAIL <flow>: <case> (flow time <d>)} per case, after a FAIL what failed, each on an
 * indented line — the failed keys ({@link Expectations}), or {@code did not settle: <what>}, {@code send refused: <why>} (the
 * message never reached the flow) or {@code deploy refused: <why>} — and last {@code N cases: P passed, F failed}. Nothing on it
 * depends on wall time: the same package prints the same bytes on every run. Exit 0 when every case passed; 1 on a failed
 * case, a problem, or a package without a test document.
 */
final class FlowTests {

    /** How long one wait on virtual time — a case's, its node's close — may take in wall time. */
    static final Duration WALL_GUARD = Duration.ofSeconds(60);
    /**
     * How long a case's world stays quiet, in wall time, before its clock moves ({@link Quiescence}): a virtual thread blocked on a
     * monitor of the node's own (a worker's, a delivery's) is neither mounted nor queued until the JDK's unblocker thread queues
     * it again, a hand-over of microseconds this outlasts.
     */
    static final Duration STEADY = Duration.ofMillis(1);
    /** The environment a test runs in: a node that loads mocks (not deployed). */
    private static final String ENV = "ci";
    /** The node id of each case's node: a DNS label, named nowhere in the output. */
    private static final String NODE_ID = "rail8-test";
    /** Behaviours the schema takes and this runner does not run yet. */
    private static final List<String> NOT_YET = List.of("recorded", "script");
    /** The answer of a case without a send, fed by its from endpoint's mock: there is none to wait for, and no Ack. */
    private static final Future<Ack> FED = Future.succeededFuture();
    /** The scheme of calls between flows, which run for real in a test ({@link Calls}). */
    private static final String DIRECT = "direct";

    private final Package pkg;
    private final Path root;
    private final List<String> schemes;
    private final Plugins plugins;
    private final Map<String, Function<Envelope, Optional<String>>> xpaths = new HashMap<>();
    /** The run's copy of the package folder. */
    private Path copy;
    /** The run's copies of the dependencies' folders, leaves first. */
    private List<Copy> dependencies;
    private Vertx vertx;
    /**
     * The forms of each adapter of the run, by scheme, with a connection form that describes nothing, as the mock's: the mocks of
     * a test of {@code schemaVersion: 2} take them, so each use reaches a connection, and its settings are checked, as on a node.
     */
    private Map<String, Forms> forms;
    /** The adapter of each connection a flow of {@code schemaVersion: 2} of the package or of a dependency names, by key. */
    private final Map<String, String> adapters;

    private FlowTests(Package pkg, Path root, List<String> schemes, Plugins plugins, Map<String, String> adapters) {
        this.pkg = pkg;
        this.root = root;
        this.schemes = schemes;
        this.plugins = plugins;
        this.adapters = Map.copyOf(adapters);
    }

    /**
     * One test document: its path from the package root, its flow, its {@code mocks} and its cases, and whether it is of
     * {@code schemaVersion: 2}, which keys its mocks by connection key and its calls by {@code direct:<address>}.
     */
    private record Doc(String path, String flow, JsonObject mocks, List<JsonObject> cases, boolean byConnection) {}

    /** The run's copy of a dependency's folder and the name it is deployed as. */
    private record Copy(String name, Path dir) {}

    /** How a case ended: passed or not, at what flow time, and the indented lines that say what failed. */
    private record Verdict(boolean passed, Duration flowTime, List<String> why) {
        static Verdict failed(Duration flowTime, String why) {
            return new Verdict(false, flowTime, List.of(why));
        }
    }

    static int run(Cli.Options options, Plugins plugins, PrintStream out) throws IOException {
        Path dir = options.dir();
        try (Dependencies deps = Dependencies.of(options)) {   // the closure's folders are its own until the copies are made
            Validate.Checked checked = Validate.check(dir, ENV, deps, plugins);
            if (!checked.problems().isEmpty()) return problems(checked.problems(), out);
            Package pkg = checked.pkg().orElseThrow();
            FlowTests run = new FlowTests(pkg, dir.toAbsolutePath().normalize(), checked.schemes().stream().sorted().toList(), plugins,
                    adapters(pkg, checked.dependencies()));
            List<Doc> docs = run.docs(dir);
            List<String> problems = run.problems(docs);
            if (!problems.isEmpty()) return problems(problems, out);
            if (docs.isEmpty()) {
                out.println("no test document in package " + pkg.name() + ": nothing to run");
                return 1;
            }
            Path copies = Files.createTempDirectory("rail8-test-packages-");
            try {
                return run.cases(docs, dir, checked.dependencies(), copies, out);
            } finally {
                deleteTree(copies);
            }
        }
    }

    /**
     * Copies the package folder {@code dir} and each dependency's into {@code copies}, then plays every case of {@code docs} and
     * prints the report; the exit code.
     */
    private int cases(List<Doc> docs, Path dir, List<Dependencies.Found> found, Path copies, PrintStream out) throws IOException {
        copy = copy(dir, copies.resolve("package"));
        List<Copy> deps = new ArrayList<>();
        for (Dependencies.Found dep : found) deps.add(new Copy(dep.pin().name(), copy(dep.dir(), copies.resolve("dependency-" + deps.size()))));
        dependencies = List.copyOf(deps);
        int passed = 0;
        int cases = 0;
        vertx = Vertx.vertx();   // the one each case's components are built over; it runs nothing of theirs
        try {
            FakeRuntime rt = FakeRuntime.create();
            forms = plugins.components(new ComponentContext(vertx, rt.blobs(), rt.ids(), rt.timer(), rt.clock(), HttpIngress.NONE)).stream()
                    .collect(Collectors.toMap(Component::scheme, c -> new Forms(Settings.NONE, c.forms().flow(), c.forms().reach())));   // built, never started
            for (Doc doc : docs) {
                for (JsonObject c : doc.cases()) {
                    Verdict verdict = play(doc, c);
                    out.println((verdict.passed() ? "PASS " : "FAIL ") + doc.flow() + ": " + c.getString("name") + " (flow time " + duration(verdict.flowTime()) + ")");
                    verdict.why().forEach(line -> out.println("  " + line));
                    cases++;
                    if (verdict.passed()) passed++;
                }
            }
        } finally {
            vertx.close().await();
        }
        out.println(cases + " cases: " + passed + " passed, " + (cases - passed) + " failed");
        return passed == cases ? 0 : 1;
    }

    /** The adapter of each connection the flows of {@code schemaVersion: 2} of {@code pkg} and of {@code closure} name, by key. */
    private static Map<String, String> adapters(Package pkg, List<Dependencies.Found> closure) throws IOException {
        Map<String, String> adapters = new TreeMap<>();
        List<Package> packages = new ArrayList<>(List.of(pkg));
        for (Dependencies.Found found : closure) packages.add(Package.load(found.dir()));
        for (Package p : packages) {
            for (JsonObject flow : p.flows().values()) {
                if (Package.schemaVersion(flow) != 2) continue;
                if (flow.getValue("from") instanceof JsonObject from && from.getValue("connection") instanceof String key
                        && from.getValue("adapter") instanceof String adapter) {
                    adapters.putIfAbsent(key, adapter);
                }
                for (Package.WrittenStep step : Package.writtenSteps(flow)) {
                    JsonObject entry = step.entry();
                    if ("to".equals(entry.getValue("type")) && entry.getValue("connection") instanceof String key && entry.getValue("adapter") instanceof String adapter) {
                        adapters.putIfAbsent(key, adapter);
                    }
                }
            }
        }
        return adapters;
    }

    /**
     * The environment of a case's node: every connection of {@link #adapters} set for its adapter with no value — its mock
     * describes none —, and every system of one with an instance name, so each send finds its connection served.
     */
    private Environment environment() {
        Map<String, ConnectionState> connections = new TreeMap<>();
        Map<String, String> names = new TreeMap<>();
        adapters.forEach((key, adapter) -> {
            connections.put(key, new ConnectionState.Values(adapter, 1, Map.of(), Optional.empty()));
            names.put(Keys.systemOf(key), NODE_ID);   // a malformed key is one of the package's problems: no case runs
        });
        return Environment.fixed(ENV, new Environment.Snapshot(1, false, connections, names, Map.of(), 0));
    }

    private static int problems(List<String> problems, PrintStream out) {
        problems.forEach(p -> out.println("problem: " + p));
        return 1;
    }

    /** Every test document of the package, in path order. */
    private List<Doc> docs(Path dir) {
        List<Doc> docs = new ArrayList<>();
        for (Package.Artifact a : pkg.artifacts()) {
            if (!a.kind().equals("test")) continue;
            JsonObject d = Documents.read(dir.resolve(a.path()));
            JsonArray listed = d.getJsonArray("cases");
            List<JsonObject> cases = new ArrayList<>();
            for (int i = 0; i < listed.size(); i++) cases.add(listed.getJsonObject(i));
            docs.add(new Doc(a.path(), d.getString("flow"), d.getJsonObject("mocks", new JsonObject()), List.copyOf(cases), Package.schemaVersion(d) == 2));
        }
        return docs;
    }

    /**
     * What only a test run asks of the (schema-valid) test documents, in path and case order; each {@code xpath} of an
     * {@code expect.sent} is compiled on the way, once, for the cases.
     */
    private List<String> problems(List<Doc> docs) {
        List<String> problems = new ArrayList<>();
        Expressions xpath = plugins.languages().stream().filter(l -> l.language().equals("xpath")).findFirst().orElseThrow();
        FakeRuntime rt = FakeRuntime.create();
        StepResources resources = new StepResources(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), rt.blobs().treeLimit(), root);
        for (Doc doc : docs) {
            notYet(problems, doc.path() + ": ", doc.mocks());
            String from = from(doc.flow());
            String scheme = sender(doc.flow());
            for (JsonObject c : doc.cases()) {
                String where = doc.path() + ": case '" + c.getString("name") + "' ";
                notYet(problems, where, c.getJsonObject("mocks", new JsonObject()));
                JsonObject expect = c.getJsonObject("expect");
                if (expect.containsKey("status") && !scheme.equals("http")) {
                    problems.add(where + "expects status, the http door's answer, but flow '" + doc.flow() + "' comes from endpoint '" + from + "' of scheme " + scheme);
                }
                JsonObject sent = expect.getJsonObject("sent", new JsonObject());
                for (String endpoint : sent.fieldNames()) {
                    String expression = sent.getJsonObject(endpoint).getString("xpath");
                    if (expression == null || xpaths.containsKey(expression)) continue;
                    try {
                        xpaths.put(expression, xpath.value(expression, resources));
                    } catch (IllegalArgumentException wrong) {
                        problems.add(where + "sent." + endpoint + ".xpath does not compile: " + Cli.oneLine(wrong.getMessage()));
                    }
                }
            }
        }
        return problems;
    }

    private static void notYet(List<String> problems, String where, JsonObject mocks) {
        for (String endpoint : mocks.fieldNames()) {
            JsonObject behaviour = mocks.getJsonObject(endpoint);
            NOT_YET.stream().filter(behaviour::containsKey).forEach(b -> problems.add(where + "mock '" + endpoint + "': " + b + " is not implemented yet"));
        }
    }

    /**
     * What the mocks key the sender of the flow {@code flow} by: its from endpoint, or for a flow of {@code schemaVersion: 2} its
     * connection; for a sender of {@code schemaVersion: 2} without one, {@code direct:<address>} when it is fed by calls, else
     * the flow's qualified id.
     */
    private String from(String flow) {
        JsonObject doc = pkg.flows().get(flow);
        JsonObject from = doc.getJsonObject("from");
        if (Package.schemaVersion(doc) != 2) return from.getString("endpoint");
        if (from.getString("connection") != null) return from.getString("connection");
        if (DIRECT.equals(from.getString("adapter"))) return Calls.BY_ADDRESS + from.getJsonObject("settings", new JsonObject()).getString("address");
        return pkg.name() + "/" + flow;
    }

    /** The scheme of the sender of the flow {@code flow}: its from endpoint's, or the adapter of a flow of {@code schemaVersion: 2}. */
    private String sender(String flow) {
        JsonObject doc = pkg.flows().get(flow);
        return Package.schemaVersion(doc) == 2 ? doc.getJsonObject("from").getString("adapter") : scheme(from(flow));
    }

    /** The scheme of the endpoint {@code key}, or the adapter of the connection {@code key}; {@code direct} for a call's key. */
    private String scheme(String key) {
        if (key.startsWith(Calls.BY_ADDRESS)) return DIRECT;
        JsonObject endpoint = pkg.endpoints().get(key);
        return endpoint != null ? endpoint.getString("scheme") : adapters.get(key);
    }

    /**
     * The real {@code direct} component, built by the module {@code direct} alone over the case's {@code rt} and {@code timer}; the
     * other modules and the plugins are not built again.
     */
    private Component direct(FakeRuntime rt, Timer timer) {
        return plugins.components(DIRECT, new ComponentContext(vertx, rt.blobs(), rt.ids(), timer, rt.clock(), HttpIngress.NONE)).stream()
                .filter(c -> c.scheme().equals(DIRECT)).findFirst().orElseThrow();
    }

    /**
     * Runs the case {@code c} of {@code doc} on a world of its own (see the class comment). The node is closed and the data
     * directory deleted whatever happens after they were made.
     *
     * @throws IOException the data directory cannot be made, written or deleted, or the send's fixture read: the tool's failure
     */
    private Verdict play(Doc doc, JsonObject c) throws IOException {
        FakeRuntime rt = FakeRuntime.create();
        Watched watched = new Watched();
        Timer timer = watched.timer(rt.timer());
        Map<String, MockComponent> mocks = new TreeMap<>();
        for (String s : schemes) {
            mocks.put(s, doc.byConnection() && forms.containsKey(s) ? new MockComponent(s, rt.blobs(), timer, rt.ids(), forms.get(s))
                    : new MockComponent(s, rt.blobs(), timer, rt.ids()));
        }
        JsonObject behaviours = doc.mocks().copy();
        c.getJsonObject("mocks", new JsonObject()).forEach(entry -> behaviours.put(entry.getKey(), entry.getValue()));   // whole entries
        for (String endpoint : behaviours.fieldNames()) {
            mocks.get(scheme(endpoint)).bind(endpoint, Behaviour.from(behaviours.getJsonObject(endpoint).getMap(), copy));
        }
        Calls calls = mocks.containsKey(DIRECT) ? new Calls(direct(rt, timer), doc.byConnection(), mocks.get(DIRECT),
                behaviours.fieldNames().stream().filter(endpoint -> scheme(endpoint).equals(DIRECT)).collect(Collectors.toSet()), rt.blobs(), rt.ids()) : null;
        String from = from(doc.flow());
        String scheme = sender(doc.flow());
        Door door = calls != null && scheme.equals(DIRECT) ? calls : Door.of(mocks.get(scheme));
        String sends = c.getString("send");
        byte[] send = sends == null ? null : Files.readAllBytes(copy.resolve(sends));
        Map<String, String> headers = new LinkedHashMap<>();
        JsonObject given = c.getJsonObject("headers", new JsonObject());
        given.fieldNames().forEach(name -> headers.put(name, given.getString(name)));

        InMemoryMessageStore messages = new InMemoryMessageStore();
        InMemoryDlq dlq = new InMemoryDlq();
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock(), dlq);   // a keyed message parks where an unkeyed one does
        Map<String, Component> components = new LinkedHashMap<>(mocks);
        if (calls != null) components.put(DIRECT, calls);
        IntSupplier sent = () -> sends(mocks.values(), calls).size();
        Path data = Files.createTempDirectory("rail8-test-");
        try {
            NodeConfig config = NodeConfig.from(Map.of("RAIL8_ENV", ENV, "RAIL8_NODE_ID", NODE_ID, "RAIL8_DATA_DIR", data.toString()))
                    .withSecrets(Secrets.placeholder());   // no secret off the node: every reference resolves to the placeholder
            Deployments node = new Deployments(config, new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), components), components,
                    rt.clock(), timer, plugins.steps(), plugins.languages(), messages, dlq, queue, Meter.system(), environment());
            try {
                for (Copy dep : dependencies) node.deploy(dep.name(), dep.dir());   // leaves first
                node.deploy(pkg.name(), copy);
                Future<Ack> answer = send == null ? FED : door.ingest(from, new Headers(headers), Buffer.buffer(send));
                BooleanSupplier answered = done(answer);   // the answer's monitor stays out of the sampling: read through this only
                VirtualTime.Outcome outcome = new VirtualTime(rt.clock(), rt.timer(),
                        Quiescence.ofJvm(watched::inside, STEADY, world(watched, rt.clock(), door, from, sent, answered)), WALL_GUARD)
                        .settle(() -> answered.getAsBoolean() && door.undelivered(from).isEmpty() && queue.moving() == 0 && node.inFlight() == 0);
                if (!outcome.settled()) {
                    return Verdict.failed(outcome.flowTime(), "did not settle: " + outcome.reason() + " (answer complete: " + answered.getAsBoolean()
                            + ", undelivered files: " + door.undelivered(from).size() + ", work queue items that can still run: " + queue.moving() + ", deliveries in flight: " + node.inFlight() + ")");
                }
                List<Envelope> taken = door.delivered(from);
                if (answer.failed() && taken.isEmpty()) return Verdict.failed(outcome.flowTime(), "send refused: " + Cli.oneLine(answer.cause().getMessage()));
                Optional<String> first = taken.stream().findFirst().map(Envelope::id);   // none: a feed whose glob matched no file
                Expectations.Observed observed = new Expectations.Observed(first, answer.result(), answer.cause(), messages, dlq.size(),
                        sends(mocks.values(), calls), endpoint -> calls != null && scheme(endpoint).equals(DIRECT) ? calls.sentTo(endpoint)
                        : mocks.get(scheme(endpoint)).sentTo(endpoint), endpoint -> calls != null && scheme(endpoint).equals(DIRECT) ? List.of()
                        : mocks.get(scheme(endpoint)).callsTo(endpoint).stream().map(Call::settings).toList(), xpaths);
                List<String> failed = Expectations.failed(c.getJsonObject("expect"), observed);
                return new Verdict(failed.isEmpty(), outcome.flowTime(), failed);
            } catch (DeployRefusedException refused) {
                return Verdict.failed(Duration.ZERO, "deploy refused: " + Cli.oneLine(refused.getMessage()));
            } finally {
                close(node, rt, watched, door, from, sent);
            }
        } finally {
            deleteTree(data);
        }
    }

    /**
     * The fingerprint of a case's world that {@link Quiescence} compares: the calls into {@code watched}'s timer, the moves of
     * {@code clock} from now on — every advance, a zero one too, starts the steady wait afresh — its instant, {@code door}'s
     * deliveries on {@code from} and the files remaining there, the number of {@code sends} (every mock's, and the real calls
     * between flows) and {@code done} (the case's answer, the node's close). It takes no monitor a virtual thread takes, as a
     * store's size or a Vert.x future's {@code isComplete()} would: a thread blocked on one would leave its carrier unseen. The
     * remaining files are read without one: the inbox's current immutable list through a volatile field, found by a
     * {@code ConcurrentHashMap} get ({@link MockComponent#remaining}).
     */
    static Supplier<List<Object>> world(Watched watched, FakeClock clock, Door door, String from, IntSupplier sends, BooleanSupplier done) {
        AtomicLong moves = new AtomicLong();
        clock.onChange(moves::incrementAndGet);
        return () -> List.of(watched.calls(), moves.get(), clock.instant(), door.delivered(from).size(), door.remaining(from).size(),
                sends.getAsInt(), done.getAsBoolean());
    }

    /** Every send of the case: each mock's, in each mock's order, then the real calls between flows ({@code calls}, if any). */
    private static List<Envelope> sends(Collection<MockComponent> mocks, Calls calls) {
        Stream<Envelope> called = calls == null ? Stream.empty() : calls.sent().stream();
        return Stream.concat(mocks.stream().flatMap(m -> m.sent().stream()), called).toList();
    }

    /**
     * Where a case's message comes in, on its from endpoint: the endpoint's mock ({@link #of}) or a real {@code direct} endpoint
     * ({@link Calls}). Each read takes no lock.
     */
    interface Door {
        /** Hands the case's send to the consumer on {@code endpoint}; its answer. */
        Future<Ack> ingest(String endpoint, Headers headers, Buffer bytes);

        /** What the handler of the consumer on {@code endpoint} was given, in order. */
        List<Envelope> delivered(String endpoint);

        /** The files on {@code endpoint} still to be delivered ({@link MockComponent#undelivered}). */
        Collection<String> undelivered(String endpoint);

        /** The files still on {@code endpoint} ({@link MockComponent#remaining}). */
        Collection<String> remaining(String endpoint);

        /** {@code mock}'s endpoints. */
        static Door of(MockComponent mock) {
            return new Door() {
                @Override public Future<Ack> ingest(String endpoint, Headers headers, Buffer bytes) { return mock.ingest(endpoint, headers, bytes); }

                @Override public List<Envelope> delivered(String endpoint) { return mock.delivered(endpoint); }

                @Override public Collection<String> undelivered(String endpoint) { return mock.undelivered(endpoint); }

                @Override public Collection<String> remaining(String endpoint) { return mock.remaining(endpoint); }
            };
        }
    }

    /**
     * Whether {@code future} has completed, succeeded or failed, read without its monitor (Vert.x 5.1.10 {@code isComplete()} is
     * {@code synchronized}): a flag the listener registered here sets.
     */
    static BooleanSupplier done(Future<?> future) {
        AtomicBoolean done = new AtomicBoolean();
        future.onComplete(completed -> done.set(true));
        return done::get;
    }

    /**
     * Closes {@code node} on a virtual thread of its own while {@code rail8-clock} drives the clock: its stops are bounded by
     * the node's stop deadline on the case's timer. The case is over and reported; how the close ends — a delivery or a consumer
     * that did not stop within the deadline when the case did not settle, a close still waiting at the wall guard — changes
     * nothing of it.
     */
    private static void close(Deployments node, FakeRuntime rt, Watched watched, Door door, String from, IntSupplier sends) {
        CompletableFuture<Void> closed = CompletableFuture.runAsync(node::close, task -> Thread.ofVirtual().name("rail8-test-close").start(task));
        new VirtualTime(rt.clock(), rt.timer(), Quiescence.ofJvm(watched::inside, STEADY, world(watched, rt.clock(), door, from, sends, closed::isDone)),
                WALL_GUARD).settle(closed::isDone);
    }

    /**
     * The calls into a case's timer: each schedule and each cancel counted as it returns (a fingerprint of the case's world), and
     * the threads inside one. The timer's monitor is the clock thread's while it fires due tasks, so a virtual thread a task woke
     * may block on it and leave its carrier: inside a call, it is busy all the same.
     */
    static final class Watched {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicInteger inside = new AtomicInteger();

        Timer timer(Timer timer) {
            return (delay, task) -> {
                Timer.Cancellable scheduled = call(() -> timer.schedule(delay, task));
                return () -> call(scheduled::cancel);
            };
        }

        long calls() { return calls.get(); }

        int inside() { return inside.get(); }

        private <T> T call(Supplier<T> call) {
            inside.incrementAndGet();
            try {
                return call.get();
            } finally {
                calls.incrementAndGet();   // before the thread leaves: a sample that sees it out sees its call
                inside.decrementAndGet();
            }
        }
    }

    /**
     * The directories and regular files of {@code from}, by their paths below it, copied into {@code to}, a directory made here: a
     * link to a file is copied as the file, a linked directory as an empty one, and
     * {@code .git} at the root — a clone's repository, or a worktree's pointer to one — not at all. Returns {@code to}.
     */
    static Path copy(Path from, Path to) throws IOException {
        Path git = from.resolve(".git");
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (dir.equals(git)) return FileVisitResult.SKIP_SUBTREE;   // not read at all
                Files.createDirectories(target(dir));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.equals(git)) return FileVisitResult.CONTINUE;
                if (Files.isDirectory(file)) Files.createDirectories(target(file));   // a linked directory: not followed
                else if (Files.isRegularFile(file)) Files.copy(file, target(file));   // a link to a file: the file
                return FileVisitResult.CONTINUE;
            }

            private Path target(Path p) {
                return to.resolve(from.relativize(p).toString());
            }
        });
        return to;
    }

    static void deleteTree(Path dir) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : paths) Files.delete(p);
    }

    /**
     * {@code d} in the largest of {@code m}, {@code s}, {@code ms} that divides it exactly, {@code 0ms} for none; a flow time
     * never reaches an hour ({@link VirtualTime#HORIZON}).
     */
    static String duration(Duration d) {
        long ms = d.toMillis();
        if (ms != 0 && ms % 60_000 == 0) return ms / 60_000 + "m";
        if (ms != 0 && ms % 1_000 == 0) return ms / 1_000 + "s";
        return ms + "ms";
    }
}
