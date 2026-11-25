package dev.rail8.core.flow;

import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * An endpoint file of a flow of {@code schemaVersion: 1} as one use of the adapter's contract. Its config, the override of
 * its environment applied, is split by the adapter's {@link Forms}: the values of the connection form's fields serving
 * the endpoint's direction become a {@link Connection} whose key is the endpoint's id, a secret field's value a handle in
 * it ({@link #connection(Forms, Direction, EndpointConfig, SecretSource)}), and the values of the flow form's fields serving that direction the settings in the flow; both with the forms'
 * defaults filled in. A form that describes nothing takes what the other form does not describe: the connection form on
 * a direction that connects, else the flow form. A key that neither form takes is left out, and {@link #check} names it.
 *
 * <p>On an out endpoint whose direction connects, {@code maxBodyBytes} is the platform's: the reply's limit, handed in
 * each {@link Call}, never a field of either form. On an in endpoint {@code flow} and {@code maxBodyBytes} are the node's.
 */
public final class EndpointUse {

    /**
     * The reply's limit of an out endpoint whose direction connects: optional, the flow's {@code limits.maxBodyBytes} when
     * absent, and at least 1 when present, with the messages {@code http}'s field had.
     */
    static final Settings REPLY_LIMIT = Settings.of(Field.integer("maxBodyBytes", "Largest reply", "The largest reply body taken, in bytes.")
            .min(1).out());
    private static final String REPLY = "maxBodyBytes";
    /** What the node sets on every in endpoint. */
    private static final Set<String> NODE_KEYS = Set.of("flow", "maxBodyBytes");
    /** A reference to a secret, as a secret field's value. */
    private static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");

    /** The source of handles that are never read: a read fails naming the reference, never a value. */
    private static final SecretSource UNREAD = (reference, kinds) -> {
        Supplier<RuntimeException> unread = () -> new IllegalStateException(reference + " is not read here: only a consumer or a producer the flow runs reads it");
        return switch (kinds.iterator().next()) {
            case VALUE -> Secret.of(reference, () -> { throw unread.get(); });
            case CREDENTIALS -> Secret.credentials(reference, () -> { throw unread.get(); });
            case KEY_PAIR -> Secret.keyPair(reference, () -> { throw unread.get(); });
        };
    };

    private EndpointUse() {}

    /**
     * The sender {@code cfg} is, for the flow {@code flow} whose bodies are at most {@code maxBodyBytes}, to ask the adapter
     * about: a reference in a secret field is a handle of the field's first kind that no read may take.
     */
    public static Inbound inbound(Forms forms, EndpointConfig cfg, String flow, long maxBodyBytes) {
        return inbound(forms, cfg, flow, maxBodyBytes, UNREAD);
    }

    /**
     * The sender {@code cfg} is, for the flow {@code flow} whose bodies are at most {@code maxBodyBytes}, a reference in a
     * secret field a handle from {@code secrets}.
     */
    public static Inbound inbound(Forms forms, EndpointConfig cfg, String flow, long maxBodyBytes, SecretSource secrets) {
        return new Inbound(flow, connection(forms, Direction.IN, cfg, secrets), settings(forms, Direction.IN, cfg), maxBodyBytes);
    }

    /** What a producer of the out endpoint {@code cfg} is built for, to ask the adapter about: its handles are never read. */
    public static Outbound outbound(Forms forms, EndpointConfig cfg) {
        return outbound(forms, cfg, UNREAD);
    }

    /** What a producer of the out endpoint {@code cfg} is built for, a reference in a secret field a handle from {@code secrets}. */
    public static Outbound outbound(Forms forms, EndpointConfig cfg, SecretSource secrets) {
        return new Outbound(connection(forms, Direction.OUT, cfg, secrets));
    }

    /**
     * The call of step {@code step} of flow {@code flow} through the out endpoint {@code cfg}: its settings as written, and
     * as the reply's limit the endpoint's {@code maxBodyBytes} when its direction connects, else {@code flowMaxBodyBytes}.
     */
    public static Call call(Forms forms, EndpointConfig cfg, String flow, String step, long flowMaxBodyBytes) {
        long reply = flowMaxBodyBytes;
        if (forms.connects(Direction.OUT) && cfg.options().get(REPLY) instanceof Number n && n.longValue() > 0) reply = n.longValue();
        return new Call(flow, step, settings(forms, Direction.OUT, cfg), reply, Optional.empty());
    }

    /** The connection of {@code cfg} for {@code direction}, its handles never read; empty when that direction reaches none. */
    public static Optional<Connection> connection(Forms forms, Direction direction, EndpointConfig cfg) {
        return connection(forms, direction, cfg, UNREAD);
    }

    /**
     * The connection of {@code cfg} for {@code direction}; empty when that direction reaches none. A secret field that holds a
     * reference, {@code secret://<name>}, is the handle {@code secrets} gives for it and the kinds the field takes, in the
     * order the field names them; one that holds a literal is a handle of that plain value, named after the endpoint and the
     * field.
     *
     * @throws IllegalArgumentException {@code secrets} refuses a reference; the message names the endpoint, the field and the refusal
     */
    public static Optional<Connection> connection(Forms forms, Direction direction, EndpointConfig cfg, SecretSource secrets) {
        Objects.requireNonNull(secrets, "secrets");
        if (!forms.connects(direction)) return Optional.empty();
        Settings form = forms.connection();
        Map<String, Object> values = new LinkedHashMap<>(form.defaults(direction));
        Map<String, Secret> handles = new LinkedHashMap<>();
        List<String> secretFields = form.secrets();
        cfg.options().forEach((key, value) -> {
            if (value == null || side(forms, direction, key) != Side.CONNECTION) return;
            if (secretFields.contains(key)) handles.put(key, secret(cfg.name(), key, String.valueOf(value), form.kinds(key), secrets));
            else values.put(key, value);
        });
        return Optional.of(new Connection(cfg.name(), values, handles));
    }

    /** The settings in the flow of {@code cfg} for {@code direction}: the flow form's values, its defaults filled in. */
    public static Map<String, Object> settings(Forms forms, Direction direction, EndpointConfig cfg) {
        Map<String, Object> values = new LinkedHashMap<>(forms.flow().defaults(direction));
        cfg.options().forEach((key, value) -> {
            if (value != null && side(forms, direction, key) == Side.FLOW) values.put(key, value);
        });
        return values;
    }

    /**
     * What is wrong with {@code config}, an endpoint's config as written, for an endpoint of {@code direction}: the
     * problems of the connection form over its share, then those of the flow form over its share, then the reply's limit,
     * then the keys neither takes, in name order. A direction the adapter does not serve is one problem. A pattern of a
     * connection field is not checked here: it is a rule of a connection of an environment, and an endpoint's values keep
     * what they may hold today.
     */
    public static List<Settings.Problem> check(Forms forms, Direction direction, Map<String, ?> config) {
        Objects.requireNonNull(config, "config");
        if (!forms.serves(direction)) {
            return List.of(new Settings.Problem("", Settings.Problem.DIRECTION, "the adapter does not serve the " + direction.name().toLowerCase(Locale.ROOT) + " direction"));
        }
        Map<String, Object> connection = new LinkedHashMap<>();
        Map<String, Object> flow = new LinkedHashMap<>();
        Map<String, Object> reply = new LinkedHashMap<>();
        Set<String> unknown = new TreeSet<>();
        config.forEach((key, value) -> {
            if (value == null) return;
            switch (side(forms, direction, key)) {
                case CONNECTION -> connection.put(key, value);
                case FLOW -> flow.put(key, value);
                case REPLY -> reply.put(key, value);
                case NODE -> { }
                case NONE -> unknown.add(key);
            }
        });
        List<Settings.Problem> problems = new ArrayList<>();
        if (forms.connection().described() || forms.connects(direction)) {
            for (Settings.Problem p : forms.connection().check(connection, direction)) {
                if (!p.code().equals(Settings.Problem.PATTERN)) problems.add(p);
            }
        }
        if (forms.flow().described() || !forms.connects(direction)) problems.addAll(forms.flow().check(flow, direction));
        // An adapter that describes no connection took any settings before, so its reply limit is not judged.
        if (direction == Direction.OUT && forms.connects(Direction.OUT) && forms.connection().described()) {
            problems.addAll(REPLY_LIMIT.check(reply, Direction.OUT));
        }
        for (String key : unknown) problems.add(new Settings.Problem("/" + key.replace("~", "~0").replace("/", "~1"), Settings.Problem.UNKNOWN, "no such setting"));
        return List.copyOf(problems);
    }

    private enum Side { CONNECTION, FLOW, REPLY, NODE, NONE }

    /** Which share of an endpoint's config of {@code direction} {@code key} belongs to. */
    private static Side side(Forms forms, Direction direction, String key) {
        if (direction == Direction.IN && NODE_KEYS.contains(key)) return Side.NODE;
        if (direction == Direction.OUT && REPLY.equals(key) && forms.connects(Direction.OUT)) return Side.REPLY;
        Settings connection = forms.connection();
        Settings flow = forms.flow();
        if (connection.has(key)) return Side.CONNECTION;
        if (flow.has(key)) return Side.FLOW;
        if (!connection.described() && forms.connects(direction)) return Side.CONNECTION;
        if (!flow.described()) return Side.FLOW;
        return Side.NONE;
    }

    /** The handle of the secret field {@code field} of {@code endpoint} that holds {@code value}: a reference's from {@code secrets}. */
    private static Secret secret(String endpoint, String field, String value, Set<Secret.Kind> kinds, SecretSource secrets) {
        if (!REFERENCE.matcher(value).matches()) return handle(endpoint, field, value);
        try {
            return Objects.requireNonNull(secrets.handle(value, kinds.isEmpty() ? Set.of(Secret.Kind.VALUE) : kinds), "the secret source answered null");
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException("endpoint '" + endpoint + "': '" + field + "': " + refused.getMessage(), refused);
        }
    }

    /** A handle of the value an endpoint file gives a secret field, named after the endpoint and the field, never the value. */
    private static Secret handle(String endpoint, String field, String value) {
        String name = (endpoint + "." + field).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        if (!Character.isLetterOrDigit(name.charAt(0))) name = "e" + name;
        return Secret.of("secret://" + name, () -> value);
    }
}
