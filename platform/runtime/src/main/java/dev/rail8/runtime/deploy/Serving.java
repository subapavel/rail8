package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.flow.EndpointUse;
import dev.rail8.runtime.config.ConnectionState;
import dev.rail8.runtime.config.Environment;
import dev.rail8.runtime.config.Ref;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a connection of one snapshot of the environment can serve a use of one adapter in one direction, and the
 * {@link Connection} it hands that adapter. A reason names the connection's fields and its stop, never a value.
 */
final class Serving {

    /** The field of a connection's values that the platform reads, never the adapter. */
    static final String ALLOWED_HOSTS = "allowedHosts";
    private static final String REFERENCE = "secret://";

    private Serving() {}

    /**
     * Why the connection {@code key} of {@code snapshot} cannot serve a use of {@code adapter} in {@code direction}: not set, left
     * out by this node (naming why), stopped (by whom and when), set for another adapter, incomplete (naming the fields), or a
     * secret its values name not set or not readable; empty when it can serve. A stop counts only when {@code stopCounts}: a
     * deploy through a stopped connection is not refused, its sends are.
     */
    static Optional<String> problem(Environment.Snapshot snapshot, String key, String adapter, Direction direction,
                                    Map<String, Component> components, Secrets secrets, boolean stopCounts) {
        Optional<ConnectionState> held = snapshot.connection(key);
        if (held.isEmpty()) {
            String why = snapshot.leftOut().get(Ref.connection(key));
            return Optional.of(why == null ? "not set" : "left out by this node: " + why);
        }
        ConnectionState state = held.get();
        if (stopCounts && state.stopped().isPresent()) {
            return Optional.of("stopped by " + state.stopped().get().by() + " at " + state.stopped().get().at());
        }
        if (!state.adapter().equals(adapter)) return Optional.of("set for the adapter '" + state.adapter() + "', not '" + adapter + "'");
        if (!(state instanceof ConnectionState.Values values)) {
            return Optional.of("left out by this node: it is mocked, and this node serves no mocked connection yet");
        }
        Component component = components.get(adapter);
        if (component == null) return Optional.of("left out by this node: no adapter '" + adapter + "' on this node");
        return lacks(component.forms(), direction, values.values(), secrets);
    }

    /**
     * What {@code values} lack for {@code direction} of an adapter with {@code forms}: the fields its connection form refuses, then
     * a secret a field names that cannot be read as a kind the field takes; empty when nothing.
     */
    static Optional<String> lacks(Forms forms, Direction direction, Map<String, Object> values, Secrets secrets) {
        String name = direction.name().toLowerCase(Locale.ROOT);
        if (!forms.connects(direction)) return Optional.of("set for an adapter that connects in no " + name + " direction");
        Settings form = forms.connection();
        Map<String, Object> checked = new LinkedHashMap<>(values);
        checked.remove(ALLOWED_HOSTS);
        List<String> fields = new ArrayList<>();
        Set<String> otherDirection = new HashSet<>();
        for (Settings.Problem p : form.check(checked, direction)) {
            String field = field(p.path());
            if (p.code().equals(Settings.Problem.DIRECTION)) otherDirection.add(field);
            else fields.add(field + " (" + p.code() + ")");
        }
        if (!fields.isEmpty()) return Optional.of("incomplete for the " + name + " direction: " + String.join(", ", fields));
        for (String field : form.secrets()) {
            if (otherDirection.contains(field) || !(checked.get(field) instanceof String s) || !s.startsWith(REFERENCE)) continue;
            Optional<String> problem = secrets.problem(s, form.kinds(field));
            if (problem.isPresent()) return Optional.of("not set: its field '" + field + "': " + problem.get());
        }
        return Optional.empty();
    }

    /**
     * The connection {@code key} with {@code values} as an adapter with {@code forms} gets it for {@code direction}: the connection
     * form's fields with their defaults, each secret field a live handle of the secret its reference names, which {@code handed}
     * is told.
     */
    static Connection connection(String key, String adapter, Forms forms, Direction direction, Map<String, Object> values, Secrets secrets,
                                 java.util.function.Consumer<String> handed) {
        Map<String, Object> options = new LinkedHashMap<>(values);
        options.remove(ALLOWED_HOSTS);
        EndpointConfig cfg = new EndpointConfig(key, URI.create(adapter + "://" + key), options);
        return EndpointUse.connection(forms, direction, cfg, (reference, kinds) -> {
            handed.accept(reference);
            return secrets.live(reference, kinds);
        }).orElseThrow();
    }

    /** The field a problem's JSON pointer names: {@code /name} unescaped. */
    private static String field(String pointer) {
        String name = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        return name.replace("~1", "/").replace("~0", "~");
    }
}
