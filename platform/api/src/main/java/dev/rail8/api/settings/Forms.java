package dev.rail8.api.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The two forms of an adapter and, for each direction it serves, whether that direction reaches a connection. The
 * connection form holds what differs per environment and a connection administrator sets: where the system is and how to
 * log in. The flow form holds what a flow's author decides: a path, a directory. Each field of either form serves the in
 * direction, the out direction or both, so the UI draws four places from them. A direction absent from {@code reach} is
 * one the adapter does not serve.
 *
 * <p>Which directions connect is declared, never derived from the fields: adding a field never changes where a flow may
 * send. The node refuses to load an adapter whose out direction reaches no connection, unless it is one of the platform's
 * internal adapters, so a flow sends only through a connection.
 *
 * <p>The constructor refuses, naming the field, and so the adapter does not load: a field name used in both forms; a
 * {@link Field#secret} in the flow form; an {@link Field#expression} in the connection form or on a field serving the in
 * direction; a {@link Field#server} in the flow form, on a secret field or on a field that is neither text nor an integer;
 * a connection form that holds a secret field and no {@link Field#server} field, since a secret is sent to a server; a
 * field named {@code flow}, {@code maxBodyBytes}, {@code maxReplyBytes} or {@code allowedHosts}, which are
 * the platform's; a direction that reaches a connection with no field of the connection form serving it, or one that does
 * not with such a field; a field of either form serving a direction the adapter does not serve; and no direction served
 * at all. A {@link Field#visibleWhen} or an {@link Settings#exactlyOne} names fields of its own form only, since
 * {@link Settings#of} checks them there. A form that describes nothing ({@link Settings#NONE}) has no field to break a
 * rule: an undescribed connection form takes, on a direction that connects, every value the flow form does not describe.
 *
 * @param connection the connection form
 * @param flow the flow form
 * @param reach for each direction the adapter serves, whether it reaches a connection
 * @since 0.4
 */
public record Forms(Settings connection, Settings flow, Map<Direction, Reach> reach) {

    /**
     * The version of the description {@link #toJson} gives. A key or type added later that a reader may ignore keeps it;
     * a change to what a description accepts raises it. A reader that meets a field type it does not know offers a raw
     * editor for it and makes no claim on its value.
     */
    public static final int FORMAT = 2;

    /** The scheme the platform's own texts use in their keys; no plugin's adapter has it. */
    public static final String PLATFORM = "rail8";

    /** The names the platform sets itself, which no field of either form has. */
    public static final Set<String> PLATFORM_FIELDS = Set.of("flow", "maxBodyBytes", "maxReplyBytes", "allowedHosts");

    /**
     * Whether a direction reaches a connection: {@code CONNECTION}, its values come from a connection of the environment;
     * {@code NO_CONNECTION}, the flow's settings are all there is.
     *
     * @since 0.4
     */
    public enum Reach { CONNECTION, NO_CONNECTION }

    /** @throws IllegalArgumentException naming the field or the direction that breaks a rule of the class comment */
    public Forms {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(reach, "reach");
        if (reach.isEmpty()) throw new IllegalArgumentException("an adapter serves at least one direction");
        reach = Collections.unmodifiableMap(new EnumMap<>(reach));
        for (Field f : flow.fields()) {
            if (connection.field(f.name()) != null) throw refused(f, "is in both forms");
            if (f.isSecret()) throw refused(f, "a secret belongs to the connection form");
            if (f.escape().isPresent() && f.serves(Direction.IN)) throw refused(f, "an expression serves the out direction only");
            if (f.isServer()) throw refused(f, "the server belongs to the connection form");
        }
        for (Field f : connection.fields()) {
            if (f.escape().isPresent()) throw refused(f, "an expression belongs to the flow form");
            if (f.isServer() && f.isSecret()) throw refused(f, "a secret names no server");
            if (f.isServer() && f.type() != Field.Type.TEXT && f.type() != Field.Type.INTEGER) {
                throw refused(f, "a server is named by a text or an integer field");
            }
        }
        List<String> secrets = connection.secrets();
        if (!secrets.isEmpty() && connection.fields().stream().noneMatch(Field::isServer)) {
            throw new IllegalArgumentException("field '" + secrets.getFirst() + "': a secret goes to a server, but no field of the "
                    + "connection form names one (Field.server())");
        }
        for (Settings form : List.of(connection, flow)) {
            for (Field f : form.fields()) {
                if (PLATFORM_FIELDS.contains(f.name())) throw refused(f, "the platform sets it");
                for (Direction d : f.directions()) {
                    if (!reach.containsKey(d)) throw refused(f, "serves the " + wire(d) + " direction, which the adapter does not serve");
                }
            }
        }
        for (Map.Entry<Direction, Reach> e : reach.entrySet()) {
            Objects.requireNonNull(e.getValue(), "reach");
            if (!connection.described()) continue;
            boolean served = connection.fields().stream().anyMatch(f -> f.serves(e.getKey()));
            if (e.getValue() == Reach.CONNECTION && !served) {
                throw new IllegalArgumentException("the " + wire(e.getKey()) + " direction reaches a connection, but no field of the connection form serves it");
            }
            if (e.getValue() == Reach.NO_CONNECTION && served) {
                throw new IllegalArgumentException("the " + wire(e.getKey()) + " direction reaches no connection, but a field of the connection form serves it");
            }
        }
    }

    private static IllegalArgumentException refused(Field f, String why) {
        return new IllegalArgumentException("field '" + f.name() + "': " + why);
    }

    private static String wire(Direction d) {
        return d.name().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code direction} reaches a connection. */
    public boolean connects(Direction direction) {
        return reach.get(direction) == Reach.CONNECTION;
    }

    /** Whether the adapter serves {@code direction} at all. */
    public boolean serves(Direction direction) {
        return reach.containsKey(direction);
    }

    /**
     * The description as {@code GET /admin/components} serves it for the adapter of {@code scheme}, immutable:
     * {@code format} ({@link #FORMAT}); {@code reach}, for each direction served ({@code in}, {@code out}),
     * {@code "connection"} or {@code "none"}; {@code described}, for {@code connection} and {@code flow}, whether that form
     * describes its fields; {@code tabs}, each {@code id}, {@code label}, {@code labelKey} and {@code common}, general first,
     * then the adapter's own in the order the fields of the connection form and then of the flow form first use them, then
     * retry, recording and security; {@code connection} and {@code flow}, the fields of each form as
     * {@link Settings#toJson} gives them, each with {@code labelKey} and {@code helpKey}, and each choice with its
     * {@code labelKey}; and {@code rules}, those of both forms. A key is {@code <scheme>.field.<name>.label},
     * {@code <scheme>.field.<name>.help}, {@code <scheme>.field.<name>.choice.<value>} or {@code <scheme>.tab.<id>}; the
     * platform's tabs use {@value #PLATFORM} in place of the scheme.
     */
    public Map<String, Object> toJson(String scheme) {
        Objects.requireNonNull(scheme, "scheme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        Map<String, Object> directions = new LinkedHashMap<>();
        reach.forEach((d, r) -> directions.put(wire(d), r == Reach.CONNECTION ? "connection" : "none"));
        m.put("reach", Collections.unmodifiableMap(directions));
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("connection", connection.described());
        described.put("flow", flow.described());
        m.put("described", Collections.unmodifiableMap(described));
        List<Tab> tabs = new ArrayList<>();
        tabs.add(Tab.GENERAL);
        for (Settings form : List.of(connection, flow)) {
            for (Field f : form.fields()) if (!f.tab().common() && !tabs.contains(f.tab())) tabs.add(f.tab());
        }
        tabs.addAll(Tab.COMMON.subList(1, Tab.COMMON.size()));
        List<Object> tabJson = new ArrayList<>();
        for (Tab t : tabs) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", t.id());
            one.put("label", t.label());
            one.put("labelKey", (t.common() ? PLATFORM : scheme) + ".tab." + t.id());
            one.put("common", t.common());
            tabJson.add(Collections.unmodifiableMap(one));
        }
        m.put("tabs", List.copyOf(tabJson));
        m.put("connection", keyed(scheme, connection));
        m.put("flow", keyed(scheme, flow));
        List<Object> rules = new ArrayList<>();
        rules.addAll((List<?>) connection.toJson().get("rules"));
        rules.addAll((List<?>) flow.toJson().get("rules"));
        m.put("rules", List.copyOf(rules));
        return Collections.unmodifiableMap(m);
    }

    private static List<Object> keyed(String scheme, Settings form) {
        List<Object> fields = new ArrayList<>();
        for (Object each : (List<?>) form.toJson().get("fields")) {
            Map<String, Object> f = new LinkedHashMap<>(cast(each));
            String at = scheme + ".field." + f.get("name");
            Map<String, Object> keyed = new LinkedHashMap<>();
            f.forEach((k, v) -> {
                keyed.put(k, v);
                if (k.equals("label")) keyed.put("labelKey", at + ".label");
                if (k.equals("help")) keyed.put("helpKey", at + ".help");
            });
            if (keyed.get("choices") instanceof List<?> choices) {
                List<Object> list = new ArrayList<>();
                for (Object c : choices) {
                    Map<String, Object> choice = new LinkedHashMap<>(cast(c));
                    choice.put("labelKey", at + ".choice." + choice.get("value"));
                    list.add(Collections.unmodifiableMap(choice));
                }
                keyed.put("choices", List.copyOf(list));
            }
            fields.add(Collections.unmodifiableMap(keyed));
        }
        return List.copyOf(fields);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
