package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.flow.AdapterUse;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.key.Keys;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.Environment;
import dev.rail8.runtime.deploy.DeploymentStore.Configuration;
import dev.rail8.runtime.deploy.DeploymentStore.ConnectionUse;
import dev.rail8.runtime.deploy.DeploymentStore.DeployedSystem;
import dev.rail8.runtime.deploy.DeploymentStore.Reached;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The checks of a deploy of what names systems and connections: the package's system files, its flows of {@code schemaVersion:
 * 2}, the pool as it would be after the deploy, and the configuration of the environment. Every problem is one line naming the
 * flow, the step, the key, the field and the environment, never a value of a connection.
 */
final class NamedChecks {

    /** The most systems one installation defines. */
    static final int MAX_INSTALLATION_SYSTEMS = 4096;
    /** What a probe connection's server fields hold: a polled place is compared within one connection, never with its values. */
    private static final String PROBE = "connection";

    /**
     * What a deploy reads of the pool and of the environment, under the pool's lock.
     *
     * @param environment the environment's name
     * @param snapshot    the configuration the node holds now
     * @param systems     the systems the pool's committed records define
     * @param uses        the connections the pool's deployed and retiring flows reach
     */
    record Pooled(String environment, Environment.Snapshot snapshot, List<DeployedSystem> systems, List<ConnectionUse> uses) {
        Pooled {
            systems = List.copyOf(systems);
            uses = List.copyOf(uses);
        }
    }

    private NamedChecks() {}

    /**
     * The problems of the flow {@code id} of {@code schemaVersion: 2} on its own: each use's adapter on this node and serving its
     * direction, a connection named exactly when the adapter connects in it, and its settings against the adapter's flow form.
     */
    static List<String> flow(String id, FlowDefinition def, Map<String, Component> components) {
        List<String> problems = new ArrayList<>();
        for (AdapterUse use : def.uses()) {
            String where = "flow '" + id + "' " + use.label();
            String direction = use.direction().name().toLowerCase(Locale.ROOT);
            Component component = components.get(use.adapter());
            if (component == null) {
                problems.add(where + ": no adapter '" + use.adapter() + "' on this node");
                continue;
            }
            Forms forms = component.forms();
            if (!forms.serves(use.direction())) {
                problems.add(where + ": the adapter '" + use.adapter() + "' does not serve the " + direction + " direction");
                continue;
            }
            boolean connects = forms.connects(use.direction());
            if (connects && use.connection().isEmpty()) {
                problems.add(where + ": the adapter '" + use.adapter() + "' reaches a connection in the " + direction + " direction: name it");
            } else if (!connects && use.connection().isPresent()) {
                problems.add(where + ": the adapter '" + use.adapter() + "' reaches no connection in the " + direction + " direction: name none");
            }
            for (Settings.Problem p : forms.flow().check(use.settings(), use.direction())) {
                problems.add(where + " settings." + field(p.path()) + ": " + p.message());
            }
        }
        return problems;
    }

    /**
     * The place the sender of {@code def}, through {@code component}, polls within its connection; empty when it does not poll.
     * Asked of a connection that holds no value of the environment: two senders on one connection poll one place when their
     * settings name one.
     *
     * @throws RuntimeException the adapter cannot say which place it polls: it threw, or answered null
     */
    static Optional<String> polled(Component component, FlowDefinition def, AdapterUse sender) {
        Settings form = component.forms().connection();
        Map<String, Object> values = new LinkedHashMap<>(form.defaults(Direction.IN));
        for (String server : form.servers()) values.putIfAbsent(server, PROBE);
        Connection probe = new Connection(sender.connection().orElseThrow(), values, Map.of());
        Inbound in = new Inbound(def.id(), Optional.of(probe), sender.settings(), def.maxBodyBytes());
        return Objects.requireNonNull(component.polledPlace(in), "polledPlace answered null");
    }

    /**
     * The checks of package {@code name}'s systems and of its flows of {@code schemaVersion: 2} ({@code flows}, by local id)
     * across the pool and the environment; each problem a line in {@code problems}. Without {@code pooled} (a validate, a
     * restore, a follow) only what the package holds is checked. Answers what the deploy's record writes, with
     * {@code pooled}; null without.
     */
    static Configuration check(String name, Map<String, JsonObject> systemFiles, Map<String, FlowDefinition> flows, Map<String, Component> components,
                               Secrets secrets, Optional<Pooled> pooled, List<String> problems) {
        Map<String, DeployedSystem> candidate = new TreeMap<>();
        systemFiles.forEach((key, doc) -> {
            JsonObject definition = doc.copy();
            definition.remove("schemaVersion");
            definition.remove("kind");
            candidate.put(key, new DeployedSystem(name, key, definition));
        });
        candidate.values().forEach(sys -> systemProblems(sys, components, problems));
        // the pool as it would be after this deploy: the package's earlier systems replaced by its candidate's
        Map<String, DeployedSystem> after = new TreeMap<>();
        pooled.ifPresent(p -> p.systems().stream().filter(sys -> !sys.deployment().equals(name)).forEach(sys -> {
            DeployedSystem mine = candidate.get(sys.key());
            if (mine != null) {
                problems.add("system '" + sys.key() + "' is defined by the package '" + sys.deployment() + "': a system key is defined by one "
                        + "deployed package, and this package '" + name + "' defines it too");
            }
            after.put(sys.key(), sys);
        }));
        candidate.forEach(after::putIfAbsent);
        if (after.size() > MAX_INSTALLATION_SYSTEMS) {
            problems.add("the installation would define " + after.size() + " systems, more than the " + MAX_INSTALLATION_SYSTEMS + " it takes");
        }
        firstSegments(candidate, after, problems);
        List<ConnectionUse> uses = new ArrayList<>();
        flows.forEach((id, def) -> {
            for (AdapterUse use : def.uses()) {
                if (use.connection().isEmpty()) continue;
                String key = use.connection().get();
                String where = "flow '" + id + "' " + use.label() + " connection '" + key + "'";
                Optional<String> adapter = adapterOf(key, candidate, after);
                boolean known = candidate.containsKey(Keys.systemOf(key)) || pooled.isPresent();
                if (known && adapter.isEmpty()) {
                    problems.add(where + ": no deployed package and not this one defines it: deploy the package of its system first");
                } else if (adapter.isPresent() && !adapter.get().equals(use.adapter())) {
                    problems.add(where + ": its system file names the adapter '" + adapter.get() + "', the step '" + use.adapter() + "'");
                }
                Component component = components.get(use.adapter());
                Optional<String> polled = Optional.empty();
                if (use.direction() == Direction.IN && component != null) {
                    try {
                        polled = polled(component, def, use);
                    } catch (RuntimeException unanswered) {
                        problems.add(where + ": the adapter '" + use.adapter() + "' cannot say which place it polls: "
                                + Objects.toString(unanswered.getMessage(), unanswered.toString()));
                    }
                }
                uses.add(new ConnectionUse(name, def.id(), use.step(), key, use.adapter(), use.direction() == Direction.IN ? "in" : "out", false, polled));
            }
        });
        twoSenders(name, uses, pooled.map(Pooled::uses).orElse(List.of()), problems);
        if (pooled.isEmpty()) return null;
        dropped(name, candidate, pooled.get().systems().stream().filter(sys -> sys.deployment().equals(name)).toList(), pooled.get().uses(), problems);
        return environment(name, candidate.values().stream().toList(), uses, flows, components, secrets, pooled.get(), problems);
    }

    /** The lines of an undeploy of {@code name} that would leave a connection of its systems named by another package's flow. */
    static List<String> undeployProblems(String name, List<DeployedSystem> systems, List<ConnectionUse> uses) {
        List<String> problems = new ArrayList<>();
        dropped(name, Map.of(), systems.stream().filter(sys -> sys.deployment().equals(name)).toList(), uses, problems);
        return problems;
    }

    /** The key rules of a system and its connections, and each connection's adapter known here and connecting in some direction. */
    private static void systemProblems(DeployedSystem sys, Map<String, Component> components, List<String> problems) {
        try {
            Keys.system(sys.key());
        } catch (IllegalArgumentException refused) {
            problems.add("system '" + sys.key() + "': " + refused.getMessage());
            return;
        }
        if (!(sys.definition().getValue("connections") instanceof JsonObject connections)) return;
        for (String connection : new TreeMap<>(connections.getMap()).keySet()) {
            String where = "system '" + sys.key() + "' connection '" + connection + "'";
            try {
                Keys.connection(sys.key() + "/" + connection);
            } catch (IllegalArgumentException refused) {
                problems.add(where + ": " + refused.getMessage());
                continue;
            }
            String adapter = sys.adapter(connection).orElse("");
            Component component = components.get(adapter);
            if (component == null) problems.add(where + ": no adapter '" + adapter + "' on this node");
            else if (!component.forms().connects(Direction.IN) && !component.forms().connects(Direction.OUT)) {
                problems.add(where + ": the adapter '" + adapter + "' reaches a connection in no direction");
            }
        }
    }

    /** A first segment is a partner or a system without a partner across the installation, never both. */
    private static void firstSegments(Map<String, DeployedSystem> candidate, Map<String, DeployedSystem> after, List<String> problems) {
        Map<String, String> plain = new TreeMap<>();     // a system without a partner: its key, by its package
        Map<String, String> partners = new TreeMap<>();  // a partner: one of its systems' keys
        after.values().forEach(sys -> {
            if (sys.key().contains("/")) partners.putIfAbsent(sys.key().substring(0, sys.key().indexOf('/')), sys.key());
            else plain.put(sys.key(), sys.deployment());
        });
        for (DeployedSystem sys : candidate.values()) {
            String first = sys.key().contains("/") ? sys.key().substring(0, sys.key().indexOf('/')) : sys.key();
            if (sys.key().contains("/") && plain.containsKey(first)) {
                problems.add("system '" + sys.key() + "': its partner '" + first + "' is a system without a partner too, of the package '"
                        + plain.get(first) + "': a first segment is a partner or a system, never both");
            } else if (!sys.key().contains("/") && partners.containsKey(first)) {
                problems.add("system '" + sys.key() + "': it is the partner of the system '" + partners.get(first)
                        + "' too: a first segment is a partner or a system, never both");
            }
        }
    }

    /** The adapter the connection {@code key} has in the system that defines it after the deploy; empty when none does. */
    private static Optional<String> adapterOf(String key, Map<String, DeployedSystem> candidate, Map<String, DeployedSystem> after) {
        String system = Keys.systemOf(key);
        String connection = key.substring(system.length() + 1);
        DeployedSystem sys = candidate.containsKey(system) ? candidate.get(system) : after.get(system);
        return sys == null ? Optional.empty() : sys.adapter(connection);
    }

    /**
     * No two senders poll one connection at one place: in this package, or this package and a deployed flow of another. A
     * retiring flow, whose uses the pool keeps under its own id, only drains its work and polls nothing.
     */
    private static void twoSenders(String name, List<ConnectionUse> mine, List<ConnectionUse> pool, List<String> problems) {
        Map<String, String> taken = new LinkedHashMap<>();   // by connection and place: the flow that polls it
        for (ConnectionUse u : pool) {
            boolean retiring = u.deployment().equals(u.flow());
            if (u.polled().isPresent() && !u.deployment().equals(name) && !retiring) {
                taken.putIfAbsent(u.connection() + " " + u.polled().get(), u.flow());
            }
        }
        for (ConnectionUse u : mine) {
            if (u.polled().isEmpty()) continue;
            String other = taken.putIfAbsent(u.connection() + " " + u.polled().get(), u.flow());
            if (other != null) {
                problems.add("flow '" + u.flow() + "' from connection '" + u.connection() + "': it polls the place the flow '" + other
                        + "' polls: two senders never poll one place of a connection");
            }
        }
    }

    /**
     * A system or a connection of package {@code name}'s {@code earlier} systems that the {@code candidate} drops, or whose
     * adapter it changes, while a flow of another package, or one of its own that retires, names it.
     */
    private static void dropped(String name, Map<String, DeployedSystem> candidate, List<DeployedSystem> earlier, List<ConnectionUse> uses,
                                List<String> problems) {
        Set<String> lines = new LinkedHashSet<>();
        for (ConnectionUse u : uses) {
            if (u.deployment().equals(name)) continue;   // this package's own flows are checked as the candidate names them
            String system = Keys.systemOf(u.connection());
            if (earlier.stream().noneMatch(sys -> sys.key().equals(system))) continue;   // another package's: not this one's to drop
            DeployedSystem now = candidate.get(system);
            String connection = u.connection().substring(system.length() + 1);
            String naming = "the flow '" + u.flow() + "' " + (u.step().equals(AdapterUse.SENDER) ? "from" : "step '" + u.step() + "'") + " names it";
            if (now == null) {
                lines.add("system '" + system + "' of the package '" + name + "' cannot go while " + naming);
            } else if (now.adapter(connection).isEmpty()) {
                lines.add("system '" + system + "' drops its connection '" + connection + "' while " + naming);
            } else if (!now.adapter(connection).get().equals(u.adapter())) {
                lines.add("system '" + system + "' changes the adapter of its connection '" + connection + "' to '" + now.adapter(connection).get()
                        + "' while " + naming + " with the adapter '" + u.adapter() + "'");
            }
        }
        problems.addAll(lines);
    }

    /**
     * The configuration of the environment as {@code pooled} holds it, for every connection the flows reach: set for its
     * adapter and complete for each direction it is used in, every secret it names set, and its system's instance name. Answers
     * what the record writes.
     */
    private static Configuration environment(String name, List<DeployedSystem> systems, List<ConnectionUse> uses, Map<String, FlowDefinition> flows,
                                             Map<String, Component> components, Secrets secrets, Pooled pooled, List<String> problems) {
        Environment.Snapshot snapshot = pooled.snapshot();
        Map<String, Reached> reached = new TreeMap<>();
        Map<String, Boolean> named = new TreeMap<>();
        Set<String> lines = new LinkedHashSet<>();
        flows.forEach((id, def) -> {
            for (AdapterUse use : def.uses()) {
                if (use.connection().isEmpty() || !components.containsKey(use.adapter())) continue;
                String key = use.connection().get();
                String where = "flow '" + id + "' " + use.label() + " connection '" + key + "' in the environment '" + pooled.environment() + "'";
                Optional<ConnectionState> state = snapshot.connection(key);
                state.ifPresent(s -> reached.put(key, new Reached(s.version(), s.stopped().isPresent())));
                Optional<String> problem = Serving.problem(snapshot, key, use.adapter(), use.direction(), components, secrets, false);
                problem.ifPresent(p -> lines.add(where + ": " + p));
                String system = Keys.systemOf(key);
                boolean hasName = snapshot.instanceName(system).isPresent();
                named.put(system, hasName);
                if (!hasName) lines.add("flow '" + id + "' " + use.label() + " system '" + system + "' has no instance name in the environment '"
                        + pooled.environment() + "'");
            }
        });
        problems.addAll(lines);
        return new Configuration(snapshot.generation(), systems, uses, reached, named);
    }

    /** The field a problem's JSON pointer names. */
    private static String field(String pointer) {
        String name = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        return name.replace("~1", "/").replace("~0", "~");
    }
}
