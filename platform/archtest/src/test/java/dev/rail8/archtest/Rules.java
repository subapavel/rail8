package dev.rail8.archtest;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.onlyHaveDependenciesWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The module boundaries of CLAUDE.md and ARCHITECTURE §12, as ArchUnit rules. Shared by
 * {@link ModuleBoundaryTest} (the production tree passes) and {@link ModuleBoundaryViolationTest}
 * (each rule fails on its deliberate violation). Changing a rule is an architecture change:
 * finding first (CLAUDE.md).
 */
final class Rules {

    private Rules() {}

    static final String API = "dev.rail8.api..";
    static final String CORE = "dev.rail8.core..";
    static final String RUNTIME = "dev.rail8.runtime..";
    static final String COMPONENTS = "dev.rail8.components..";
    static final String STEPS = "dev.rail8.steps..";
    static final String TESTKIT = "dev.rail8.testkit..";
    static final String CLI = "dev.rail8.cli..";
    /** The node's shared HTTP port. */
    static final String INGRESS = "dev.rail8.ingress..";
    static final String SIM = "dev.rail8.sim..";
    /** The mapping engine under {@code mapping/}, every package of it. */
    static final String MAPPING_ENGINE = "dev.rail8.mapping..";
    /** The engine's embedding API: a package of the engine the mapping step may use. */
    static final String MAPPING_EMBED = "dev.rail8.mapping.embed";
    /** The mapping studio, the browser's operations on bytes: the other package of the engine the mapping step may use. */
    static final String MAPPING_STUDIO = "dev.rail8.mapping.studio";
    static final String MAPPING_STEP = "dev.rail8.steps.mapping..";

    /**
     * The whole Vert.x surface api and core may touch (D-020): the four types CLAUDE.md names
     * plus the chosen subset of the Vert.x types their signatures expose (javap on vertx-core
     * 5.1.10; Context, Expectation, JsonObject/JsonArray deliberately left out).
     */
    static final Set<String> VERTX_ALLOWLIST = Set.of(
            "io.vertx.core.Future", "io.vertx.core.CompositeFuture", "io.vertx.core.Promise",
            "io.vertx.core.Completable", "io.vertx.core.AsyncResult", "io.vertx.core.Handler",
            "io.vertx.core.buffer.Buffer",
            "io.vertx.core.streams.ReadStream", "io.vertx.core.streams.WriteStream",
            "io.vertx.core.streams.StreamBase", "io.vertx.core.streams.Pipe");

    /** The plugin contract: the one api package that may name {@link #VERTX} and {@link #HTTP_REQUEST}. */
    static final String PLUGIN_CONTRACT = "dev.rail8.api.plugin..";

    /** The node's Vert.x, which the plugin contract hands to a plugin's components. */
    static final String VERTX = "io.vertx.core.Vertx";

    /** A request on the node's HTTP port, which the plugin contract hands to the consumer that mounted its path. */
    static final String HTTP_REQUEST = "io.vertx.core.http.HttpServerRequest";

    /** Client libraries of the five stores; only runtime may depend on them (CLAUDE.md). */
    static final String[] STORE_CLIENT_PACKAGES = {
            "io.vertx.pgclient..", "io.vertx.sqlclient..", "io.vertx.jdbcclient..", "org.postgresql..",
            "org.flywaydb..", "com.zaxxer.hikari..",
            "io.vertx.kafka..", "org.apache.kafka..", "io.confluent..",
            "software.amazon.awssdk..", "com.amazonaws..", "io.minio..",
            "io.vertx.redis..", "io.lettuce..", "redis.clients..",
            "com.clickhouse.."};

    /**
     * The JDK = every class the runtime image provides ({@code jrt:} source), minus JDBC:
     * java.sql / javax.sql are a JDK-only route to Postgres, which only runtime may take.
     */
    private static final DescribedPredicate<JavaClass> JDK = describe("the JDK (jrt:, without java.sql/javax.sql)", c -> {
        JavaClass base = c.isArray() ? c.getBaseComponentType() : c;   // byte[] etc. carry no source of their own
        if (base.isPrimitive()) return true;
        String p = base.getPackageName();
        boolean fromRuntimeImage = base.getSource().map(s -> "jrt".equals(s.getUri().getScheme())).orElse(false);
        return fromRuntimeImage && !p.startsWith("java.sql") && !p.startsWith("javax.sql");
    });

    private static final DescribedPredicate<JavaClass> VERTX_ALLOWED =
            describe("the allowed Vert.x types " + VERTX_ALLOWLIST, c -> VERTX_ALLOWLIST.contains(c.getName()));

    private static final DescribedPredicate<JavaClass> THE_VERTX = describe(VERTX + " and " + HTTP_REQUEST,
            c -> VERTX.equals(c.getName()) || HTTP_REQUEST.equals(c.getName()));

    static final ArchRule API_DEPENDS_ON_NO_OTHER_MODULE = noClasses().that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, INGRESS)
            .because("api is the bottom of the dependency direction api <- core <- runtime");

    /**
     * The entry of a caller ({@code Entry}), the one api class beyond the plugin contract that may name more of Vert.x: the
     * node's Vert.x and the context, in its body only, to complete on its caller's context.
     */
    static final String ENTRY = "dev.rail8.api.Entry";
    static final Set<String> ENTRY_VERTX = Set.of(VERTX, "io.vertx.core.Context");

    /** The class {@code className} may name the JDK, api, the allowlist and {@code extra}, and nothing else. */
    static ArchRule namesOnly(String className, Set<String> extra) {
        return classes().that().haveFullyQualifiedName(className)
                .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED)
                        .or(describe("the Vert.x types " + extra.stream().sorted().toList(), c -> extra.contains(c.getName()))))
                .because(className + " alone among api's classes names " + extra.stream().sorted().toList() + ", and nothing more of Vert.x")
                .allowEmptyShould(false);
    }

    /**
     * No public or protected member of the class {@code className} names a Vert.x type beyond the allowlist in its signature:
     * its fields' types, its methods' return and parameter types, its constructors' parameter types, and their type
     * arguments.
     */
    static ArchRule signaturesNameOnlyTheAllowlist(String className) {
        return classes().that().haveFullyQualifiedName(className)
                .should(new ArchCondition<JavaClass>("name no Vert.x type beyond " + VERTX_ALLOWLIST + " in a public or protected signature") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        for (JavaMember m : c.getMembers()) {
                            if (!m.getModifiers().contains(JavaModifier.PUBLIC) && !m.getModifiers().contains(JavaModifier.PROTECTED)) continue;
                            List<JavaClass> named = new ArrayList<>();   // every type a signature names, type arguments too
                            if (m instanceof JavaField f) named.addAll(f.getType().getAllInvolvedRawTypes());
                            if (m instanceof JavaCodeUnit u) {
                                named.addAll(u.getReturnType().getAllInvolvedRawTypes());
                                u.getParameterTypes().forEach(t -> named.addAll(t.getAllInvolvedRawTypes()));
                            }
                            for (JavaClass t : named) {
                                JavaClass base = t.isArray() ? t.getBaseComponentType() : t;
                                if (base.getPackageName().startsWith("io.vertx") && !VERTX_ALLOWLIST.contains(base.getName())) {
                                    events.add(SimpleConditionEvent.violated(m, m.getFullName() + " names " + base.getName() + " in its signature"));
                                }
                            }
                        }
                    }
                })
                .because("a plugin compiles against api's public signatures, which name no more of Vert.x than the allowlist")
                .allowEmptyShould(false);
    }

    static final ArchRule ENTRY_SURFACE = namesOnly(ENTRY, ENTRY_VERTX);
    static final ArchRule ENTRY_SIGNATURES = signaturesNameOnlyTheAllowlist(ENTRY);

    static final ArchRule API_SURFACE = classes().that().resideInAPackage(API).and().resideOutsideOfPackage(PLUGIN_CONTRACT)
            .and().doNotHaveFullyQualifiedName(ENTRY)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED))
            .because("api uses the JDK plus Vert.x Future/Buffer/ReadStream/WriteStream and nothing else (CLAUDE.md, ARCHITECTURE §12)");

    static final ArchRule PLUGIN_SURFACE = classes().that().resideInAPackage(PLUGIN_CONTRACT)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API)).or(VERTX_ALLOWED).or(THE_VERTX))
            .because("a plugin's components run on the node's Vert.x and take requests on its HTTP port: " + VERTX + " and " + HTTP_REQUEST
                    + " are the types the plugin contract adds to api's surface");

    static final ArchRule NOTHING_ELSE_NAMES_THE_PLUGIN_CONTRACT = noClasses().that().resideInAnyPackage(API, CORE)
            .and().resideOutsideOfPackage(PLUGIN_CONTRACT)
            .should().dependOnClassesThat().resideInAPackage(PLUGIN_CONTRACT)
            .because("the rest of api and core never reach " + VERTX + ", not even through the plugin contract");

    static final ArchRule CORE_DEPENDS_ON_API_ONLY = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, INGRESS)
            .because("core depends on api only (ARCHITECTURE §12)");

    static final ArchRule CORE_SURFACE = classes().that().resideInAPackage(CORE)
            .should().onlyDependOnClassesThat(JDK.or(resideInAnyPackage(API, CORE)).or(VERTX_ALLOWED))
            .because("core uses the JDK, api and the same Vert.x allowlist as api (CLAUDE.md)");

    static final ArchRule COMPONENTS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(COMPONENTS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, STEPS, INGRESS)
            .because("components depend on api only (CLAUDE.md)");

    static final ArchRule STEPS_DEPEND_ON_API_ONLY = noClasses().that().resideInAPackage(STEPS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, TESTKIT, CLI, COMPONENTS, INGRESS)
            .because("steps depend on api only (CLAUDE.md)");

    static final ArchRule TESTKIT_DEPENDS_ON_CORE_AND_API = noClasses().that().resideInAPackage(TESTKIT)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, CLI, INGRESS)
            .because("testkit depends on core (and api) only (ARCHITECTURE §12)");

    static final ArchRule INGRESS_DEPENDS_ON_API_ONLY = noClasses().that().resideInAPackage(INGRESS)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, RUNTIME, COMPONENTS, STEPS, TESTKIT, CLI, SIM)
            .because("the shared HTTP port is api only: the node runs it, and so do the tests of a component, a shipped one or a plugin's");

    static final ArchRule NOTHING_DEPENDS_ON_CLI = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(CLI)
            .should().dependOnClassesThat().resideInAPackage(CLI)
            .because("cli is the outermost module: it may depend on every module and nothing depends on it (F-16, D-079)");

    static final ArchRule SIM_DEPENDS_ON_API_CORE_TESTKIT = noClasses().that().resideInAPackage(SIM)
            .should().dependOnClassesThat().resideInAnyPackage(RUNTIME, COMPONENTS, STEPS, CLI)
            .because("the simulation runs the real core over api, core and testkit only (F-36, D-143)");

    static final ArchRule NOTHING_DEPENDS_ON_SIM = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(SIM)
            .should().dependOnClassesThat().resideInAPackage(SIM)
            .because("rail8-sim is test infrastructure at the top beside cli: never shipped, nothing depends on it (F-36, D-143)");

    static final ArchRule ONLY_THE_MAPPING_STEP_DEPENDS_ON_THE_MAPPING_ENGINE = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackages(MAPPING_ENGINE, MAPPING_STEP)
            .should().dependOnClassesThat().resideInAPackage(MAPPING_ENGINE)
            .because("the mapping step is the only platform module that depends on the mapping engine");

    static final ArchRule THE_MAPPING_STEP_USES_ONLY_THE_EMBEDDING_API_AND_THE_STUDIO = noClasses().that().resideInAPackage(MAPPING_STEP)
            .should().dependOnClassesThat(resideInAPackage(MAPPING_ENGINE)
                    .and(DescribedPredicate.not(resideInAnyPackage(MAPPING_EMBED, MAPPING_STUDIO))))
            .because("the mapping step reaches the engine through its embedding API, " + MAPPING_EMBED + ", and its studio, "
                    + MAPPING_STUDIO + ", and nothing else of it");

    static final ArchRule ONLY_RUNTIME_TOUCHES_STORE_CLIENTS = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(STORE_CLIENT_PACKAGES)
            .because("only runtime touches the Postgres, Kafka, S3, Valkey and ClickHouse clients (CLAUDE.md)");

    /** JGit, the node's git client. */
    static final String JGIT = "org.eclipse.jgit..";

    static final ArchRule ONLY_RUNTIME_TOUCHES_JGIT = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAPackage(JGIT)
            .because("only runtime reads and writes git repositories; the CLI reads them through runtime's PackageRepositories and "
                    + "writes an adapter's first commit through runtime's FirstCommit");

    static final ArchRule NO_CYCLES = slices().matching("dev.rail8.(*)..").should().beFreeOfCycles()
            .because("a cycle between modules would break the dependency direction");

    static final ArchRule RUNTIME_NAMES_NO_COMPONENT_OR_STEP = noClasses().that().resideInAPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(COMPONENTS, STEPS)
            .because("the node finds its components and steps, the shipped ones among them, as modules on its class path and "
                    + "reaches them through the SPI; no runtime class names one");

    /** The platform's mock, which {@code rail8 test} puts in place of every partner. */
    static final String MOCK = "dev.rail8.components.mock..";

    /** The http component, whose answer to a delivery {@code rail8 test} compares with a case's expected status. */
    static final String HTTP_COMPONENT = "dev.rail8.components.http.HttpComponent";

    private static final DescribedPredicate<JavaClass> A_MODULE_THE_CLI_MAY_NAME = describe("the mock or " + HTTP_COMPONENT,
            c -> resideInAPackage(MOCK).test(c) || c.getName().equals(HTTP_COMPONENT));

    static final ArchRule CLI_NAMES_NO_COMPONENT_OR_STEP = noClasses().that().resideInAPackage(CLI)
            .should().dependOnClassesThat(resideInAnyPackage(COMPONENTS, STEPS).and(DescribedPredicate.not(A_MODULE_THE_CLI_MAY_NAME)))
            .because("the CLI finds a node's components and steps as the node does, as modules on its class path; it names only "
                    + "the mock it tests with and the http component's answer to a delivery");

    /** The command line's one way to a node's admin API, which decides the URL, plain HTTP and the trusted certificates first. */
    static final String ADMIN_API = "dev.rail8.cli.AdminApi";

    static final ArchRule ONLY_THE_ADMIN_API_MAKES_AN_HTTP_CLIENT = noClasses().that().resideInAPackage(CLI)
            .and().doNotHaveFullyQualifiedName(ADMIN_API)
            .should().dependOnClassesThat().haveFullyQualifiedName("java.net.http.HttpClient")
            .because("a command reaches a node only through " + ADMIN_API + ", which refuses plain HTTP to a host other than loopback "
                    + "before the token or a password is sent");

    static final ArchRule RUNTIME_NEVER_NAMES_THE_MOCK = noClasses().that().resideInAPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAPackage("dev.rail8.components.mock..")
            .because("a deployed node refuses a mock binding (ARCHITECTURE §11); no runtime class, Main included, names one (F-4)");

    /** Who acted, as an audit row records it. */
    static final String ACTOR = "dev.rail8.core.audit.Actor";

    /** The admin API's decision point; its {@code acting} gives a route the actor of an act it allowed. */
    static final String CALLER = "dev.rail8.runtime.access.Caller";

    private static final DescribedPredicate<JavaConstructorCall> AN_ACTOR_MADE_OUTSIDE_ACTING = describe(
            "a constructor of " + ACTOR + " called outside " + CALLER + ".acting",
            call -> call.getTargetOwner().getName().equals(ACTOR)
                    && !(call.getOriginOwner().getName().equals(CALLER) && call.getOrigin().getName().equals("acting")));

    static final ArchRule ONLY_THE_DECISION_POINT_MAKES_AN_ACTOR = noClasses().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(SIM)
            .should().callConstructorWhere(AN_ACTOR_MADE_OUTSIDE_ACTING)
            .because("an audit row names who acted only once the permission check allowed the act: Caller.acting is the one way to "
                    + "an Actor; the simulation, which has no admin API, names its operator itself");

    /** The one package that may open a raw TCP socket with Vert.x's {@code io.vertx.core.net.Net*} types (F-35, CORE-5a). */
    static final String PEER_LINK = "dev.rail8.runtime.journal.peer..";

    /**
     * {@code io.vertx.core.net.Net*} (NetServer, NetClient, NetSocket, their options): the classes the peer link opens its sockets
     * with. When the rule was added (CORE-5a, grep of every src/main) no other production class used one — the admin listener
     * and the http doors are Vert.x HTTP servers, the store clients their own libraries — so the peer link is the only user
     * listed; a new raw-socket user is an architecture change: finding first.
     */
    static final DescribedPredicate<JavaClass> VERTX_NET = describe("io.vertx.core.net.Net*",
            c -> c.getPackageName().equals("io.vertx.core.net") && c.getSimpleName().startsWith("Net"));

    /**
     * The one use of a {@code Net*} type outside the peer link: the node's Postgres pools are given their transport options, the
     * idle timeout among them, as a {@code NetClientOptions}. The options open no socket; the Postgres client opens its own
     * connections with them. Only this class may use only this type.
     */
    static final String POOLS = "dev.rail8.runtime.node.Node";
    static final String POOL_TRANSPORT = "io.vertx.core.net.NetClientOptions";

    static final ArchRule ONLY_THE_PEER_LINK_OPENS_RAW_TCP = classes().that().resideInAPackage("dev.rail8..")
            .and().resideOutsideOfPackage(PEER_LINK)
            .should(onlyHaveDependenciesWhere(describe("the target is no " + VERTX_NET.getDescription() + ", but for " + POOLS + " on " + POOL_TRANSPORT,
                    d -> !VERTX_NET.test(d.getTargetClass())
                            || d.getOriginClass().getName().equals(POOLS) && d.getTargetClass().getName().equals(POOL_TRANSPORT))))
            .because("nothing but the Component SPI and the runtime's own listeners opens a socket; the node-to-node journal link is "
                    + "the one raw TCP user, and the Postgres pools' transport options open none");
}
