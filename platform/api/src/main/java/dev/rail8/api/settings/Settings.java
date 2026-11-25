package dev.rail8.api.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One form of an adapter, described once for every reader: the node checks values with {@link #check}, the admin API
 * serves it within the adapter's {@link Forms} and the UI draws the form from that. An adapter has two, the connection
 * form and the flow form ({@link Forms}).
 *
 * <pre>{@code
 * static final Tab POLLING = Tab.of("polling", "Polling");
 * static final Settings CONNECTION = Settings.of(
 *         Field.text("host", "Host", "The server's name or address.").required(),
 *         Field.integer("port", "Port", "The server's port.").range(1, 65535).byDefault(22),
 *         Field.secret("password", "Password", "The password, or set privateKey.").tab(Tab.SECURITY),
 *         Field.secret("privateKey", "Private key", "The private key, or set password.").tab(Tab.SECURITY))
 *     .exactlyOne("password", "privateKey");
 * static final Settings FLOW = Settings.of(
 *         Field.choice("afterSuccess", "After success", "What happens to a delivered file.",
 *                 Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete").tab(POLLING).in(),
 *         Field.text("moveTo", "Move to", "Where a delivered file goes.").required().visibleWhen("afterSuccess", "move").tab(POLLING).in());
 * }</pre>
 *
 * <p>A description is checked while it is built and is finite: at most 256 fields, 16 tabs of its own and 16
 * {@link #exactlyOne} groups; a mistake throws {@link IllegalArgumentException} naming the field, and a component whose
 * description throws refuses the node's start. The node sets {@code flow} and {@code maxBodyBytes} on every in endpoint
 * itself, so no field serving in endpoints has either name.
 *
 * <p>{@link #NONE} is a form that describes nothing: {@link #check} accepts any values and the form offers a raw editor.
 *
 * @since 0.1
 */
public final class Settings {

    /** A form that describes nothing; {@link #check} has nothing to say about its values. */
    public static final Settings NONE = new Settings(false, List.of(), List.of());

    private static final int MAX_FIELDS = 256;
    private static final int MAX_OWN_TABS = 16;
    private static final int MAX_GROUPS = 16;
    /** What the node sets on every in endpoint: no field serving in endpoints has these names, and the check skips them there. */
    private static final Set<String> NODE_KEYS = Set.of("flow", "maxBodyBytes");

    /**
     * One broken rule of a form's values: {@code path} is the setting's JSON pointer within the values ({@code /port}), or
     * {@code ""} for a rule over several settings; {@code code} is stable, one of the constants below; {@code message} says
     * what is wrong in the form's English words, never quoting the value, so a secret set by mistake is not repeated.
     *
     * @since 0.1
     */
    public record Problem(String path, String code, String message) {
        /** A required field is left out while it is shown. */
        public static final String REQUIRED = "required";
        /** A key that no field of the form has. */
        public static final String UNKNOWN = "unknown";
        /** A whole number below the field's least. */
        public static final String MIN = "min";
        /** A whole number above the field's greatest. */
        public static final String MAX = "max";
        /** A text that does not match the field's pattern. */
        public static final String PATTERN = "pattern";
        /** A value that is none of the field's choices. */
        public static final String CHOICE = "choice";
        /** A value of another type than the field's, or an empty text. */
        public static final String TYPE = "type";
        /** A setting of a field that serves the other direction only. */
        public static final String DIRECTION = "direction";
        /** Not exactly one field of an {@link #exactlyOne} group is set. */
        public static final String EXACTLY_ONE = "exactlyOne";

        public Problem {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    private final boolean described;
    private final List<Field> fields;
    private final Map<String, Field> byName;
    private final List<List<String>> groups;
    private final Map<String, Object> json;

    private Settings(boolean described, List<Field> fields, List<List<String>> groups) {
        this.described = described;
        this.fields = fields;
        this.groups = groups;
        Map<String, Field> m = new HashMap<>();
        for (Field f : fields) m.put(f.name(), f);
        this.byName = m;
        this.json = buildJson();
    }

    /**
     * A description of {@code fields}, in the order the form shows them.
     *
     * @throws IllegalArgumentException naming the field when a name is used twice, a field serving in endpoints is named
     *                                  {@code flow} or {@code maxBodyBytes}, a {@link Field#visibleWhen} names no
     *                                  choice or boolean declared before it, serving at least the same directions, or a
     *                                  value it never has; naming the tab when one id has two labels; or when a limit
     *                                  is passed
     */
    public static Settings of(Field... fields) {
        List<Field> list = List.of(fields);
        if (list.size() > MAX_FIELDS) throw new IllegalArgumentException("a description holds at most 256 fields");
        Map<String, Field> before = new HashMap<>();
        Map<String, Tab> tabs = new LinkedHashMap<>();
        for (Field f : list) {
            if (before.containsKey(f.name())) throw new IllegalArgumentException("field '" + f.name() + "' is described twice");
            if (NODE_KEYS.contains(f.name()) && f.serves(Direction.IN)) {
                throw new IllegalArgumentException("field '" + f.name() + "': the node sets it on every in endpoint");
            }
            if (f.shownBy() != null) checkShownBy(f, before.get(f.shownBy()));
            Tab seen = tabs.putIfAbsent(f.tab().id(), f.tab());
            if (seen != null && !seen.equals(f.tab())) {
                throw new IllegalArgumentException("tab '" + seen.id() + "' has two labels: " + seen.label() + " and " + f.tab().label());
            }
            before.put(f.name(), f);
        }
        if (tabs.values().stream().filter(t -> !t.common()).count() > MAX_OWN_TABS) {
            throw new IllegalArgumentException("a description adds at most 16 tabs of its own");
        }
        return new Settings(true, list, List.of());
    }

    private static void checkShownBy(Field f, Field by) {
        String at = "field '" + f.name() + "': visible when '" + f.shownBy() + "'";
        if (by == null) throw new IllegalArgumentException(at + ", which is no field declared before it");
        if (by.type() != Field.Type.CHOICE && by.type() != Field.Type.BOOLEAN) {
            throw new IllegalArgumentException(at + ", which is neither a choice nor true or false");
        }
        for (Object v : f.shownFor()) {
            boolean has = by.type() == Field.Type.CHOICE ? v instanceof String s && by.choices().contains(s) : v instanceof Boolean;
            if (!has) throw new IllegalArgumentException(at + " is " + v + ", a value it never has");
        }
        if (!by.directions().containsAll(f.directions())) throw new IllegalArgumentException(at + ", which serves fewer directions");
    }

    /**
     * This description with one more rule: exactly one of {@code names} is set. Each is an optional field without a
     * default, always shown, serving the same directions as the others, and in no other group.
     *
     * @throws IllegalArgumentException naming the group and the field that breaks one of these, when fewer than two
     *                                  distinct names are given, or past 16 groups
     */
    public Settings exactlyOne(String... names) {
        List<String> group = List.of(names);
        String at = "exactly one of " + group + ": ";
        if (group.size() < 2 || new HashSet<>(group).size() != group.size()) throw new IllegalArgumentException(at + "at least two fields, none twice");
        if (groups.size() >= MAX_GROUPS) throw new IllegalArgumentException(at + "a description holds at most 16 groups");
        Field first = byName.get(group.get(0));
        for (String name : group) {
            Field f = byName.get(name);
            if (f == null) throw new IllegalArgumentException(at + "'" + name + "' is no field");
            if (f.isRequired()) throw new IllegalArgumentException(at + "'" + name + "' is required");
            if (f.hasDefault()) throw new IllegalArgumentException(at + "'" + name + "' has a default");
            if (f.shownBy() != null) throw new IllegalArgumentException(at + "'" + name + "' is shown only sometimes");
            if (!f.directions().equals(first.directions())) throw new IllegalArgumentException(at + "'" + name + "' serves other directions");
            if (groups.stream().anyMatch(g -> g.contains(name))) throw new IllegalArgumentException(at + "'" + name + "' is in another group already");
        }
        List<List<String>> more = new ArrayList<>(groups);
        more.add(group);
        return new Settings(described, fields, List.copyOf(more));
    }

    /** The fields in declaration order. */
    List<Field> fields() {
        return fields;
    }

    /** The field named {@code name}, if the form has one. */
    Field field(String name) {
        return byName.get(name);
    }

    /** Whether the form has a field named {@code name}. */
    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /** The default of every field serving {@code direction} that has one, by name; a whole number as a {@code long}. */
    public Map<String, Object> defaults(Direction direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : fields) if (f.hasDefault() && f.serves(direction)) m.put(f.name(), f.byDefault());
        return Collections.unmodifiableMap(m);
    }

    /** The names of the secret fields, in declaration order. */
    public List<String> secrets() {
        return fields.stream().filter(Field::isSecret).map(Field::name).toList();
    }

    /**
     * The kinds the secret field {@code name} takes, in the order it names them ({@link Field#kinds}); empty when the form has
     * no secret field of that name.
     *
     * @since 0.4
     */
    public Set<dev.rail8.api.Secret.Kind> kinds(String name) {
        Field f = byName.get(Objects.requireNonNull(name, "name"));
        return f == null ? Set.of() : f.kinds();
    }

    /** Whether the form describes its settings; false only for {@link #NONE}. */
    public boolean described() {
        return described;
    }

    /**
     * What is wrong with the {@code options} of an endpoint of {@code direction}; empty when nothing is. A key whose value
     * is null is absent. A {@code secret://} reference stands in for any text or secret and is resolved by the node after
     * the check; a secret field that does not take {@link dev.rail8.api.Secret.Kind#VALUE} takes a reference only. On an in endpoint {@code flow} and {@code maxBodyBytes} are the node's and are skipped.
     *
     * <p>The problems come in a fixed order: the fields in the order they are declared (one served by the other direction
     * only, a value that does not fit, a required field left out while shown), then keys no field has in name order, then
     * the {@link #exactlyOne} groups. An undescribed component ({@link #NONE}) has no problem.
     */
    public List<Problem> check(Map<String, ?> options, Direction direction) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(direction, "direction");
        if (!described) return List.of();
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        Set<String> shown = new HashSet<>();
        for (Field f : fields) {
            Object value = options.get(f.name());
            if (value != null) values.put(f.name(), value);
            else if (f.hasDefault()) values.put(f.name(), f.byDefault());
            String by = f.shownBy();
            Object control = by == null ? null : values.get(by);
            if (by == null || (shown.contains(by) && control != null && f.shownFor().contains(control))) shown.add(f.name());
        }
        for (Field f : fields) {
            if (direction == Direction.IN && NODE_KEYS.contains(f.name())) continue;
            Object value = options.get(f.name());
            String path = pointer(f.name());
            if (!f.serves(direction)) {
                if (value != null) problems.add(new Problem(path, Problem.DIRECTION, "a setting of " + other(direction) + " endpoints only"));
            } else if (value != null) {
                Field.Wrong problem = f.problem(value, true);
                if (problem != null) problems.add(new Problem(path, problem.code(), problem.message()));
            } else if (f.isRequired() && shown.contains(f.name())) {
                String by = f.shownBy();
                problems.add(new Problem(path, Problem.REQUIRED, by == null ? "is required" : "is required when " + by + " is " + values.get(by)));
            }
        }
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, ?> e : options.entrySet()) {
            String key = e.getKey();
            if (e.getValue() == null || byName.containsKey(key) || (direction == Direction.IN && NODE_KEYS.contains(key))) continue;
            unknown.add(key);
        }
        for (String key : unknown) problems.add(new Problem(pointer(key), Problem.UNKNOWN, "no such setting"));
        for (List<String> group : groups) {
            if (!byName.get(group.get(0)).serves(direction)) continue;
            long set = group.stream().filter(name -> options.get(name) != null).count();
            if (set != 1) problems.add(new Problem("", Problem.EXACTLY_ONE, "set exactly one of " + String.join(", ", group)));
        }
        return List.copyOf(problems);
    }

    private static String other(Direction d) {
        return d == Direction.IN ? "out" : "in";
    }

    private static String pointer(String key) {
        return "/" + key.replace("~", "~0").replace("/", "~1");
    }

    /**
     * The form as JSON, immutable and equal on every call: {@code described}, {@code tabs} (each {@code id}, {@code label}
     * and {@code common}: general, the adapter's own in the order fields first use them, then retry, recording and
     * security), {@code fields} in declaration order and {@code rules}. A field always has {@code name}, {@code type}
     * ({@code text}, {@code secret}, {@code integer}, {@code boolean} or {@code choice}), {@code label}, {@code help},
     * {@code tab} (an id), {@code required} and {@code directions} ({@code in}, {@code out} or both), and only what it sets
     * of {@code pattern}, {@code default}, {@code min}, {@code max}, {@code choices} (each {@code value} and {@code label}),
     * {@code expression} (its escape in lower case) and {@code visibleWhen} ({@code field} and {@code values}); a secret field
     * always has {@code kinds}, the ids of the kinds it takes in the order it names them ({@code ["value"]} for
     * {@link Field#secret(String, String, String)}). A rule is
     * {@code {"kind": "exactlyOne", "fields": [...]}}. {@link Forms#toJson} carries two of them with their text keys.
     */
    public Map<String, Object> toJson() {
        return json;
    }

    private Map<String, Object> buildJson() {
        List<Tab> tabs = new ArrayList<>();
        tabs.add(Tab.GENERAL);
        for (Field f : fields) if (!f.tab().common() && !tabs.contains(f.tab())) tabs.add(f.tab());
        tabs.addAll(Tab.COMMON.subList(1, Tab.COMMON.size()));
        List<Object> tabJson = new ArrayList<>();
        for (Tab t : tabs) tabJson.add(ordered("id", t.id(), "label", t.label(), "common", t.common()));
        List<Object> fieldJson = new ArrayList<>();
        for (Field f : fields) fieldJson.add(f.toJson());
        List<Object> rules = new ArrayList<>();
        for (List<String> g : groups) rules.add(ordered("kind", "exactlyOne", "fields", g));
        return ordered("described", described, "tabs", List.copyOf(tabJson), "fields", List.copyOf(fieldJson),
                "rules", List.copyOf(rules));
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String toString() {
        return "Settings" + json;
    }
}
