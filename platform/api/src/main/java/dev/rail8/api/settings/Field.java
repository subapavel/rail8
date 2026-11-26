package dev.rail8.api.settings;

import dev.rail8.api.Secret;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One setting of an adapter: its name, its type, a label and a help text in English, and what the form and the check make
 * of it. A field is built from a factory ({@link #text}, {@link #secret}, {@link #integer}, {@link #bool},
 * {@link #choice}) and refined with withers; each wither returns a new field and checks it whole, so a field that cannot
 * be right throws {@link IllegalArgumentException} naming it while the description is built.
 *
 * <p>By default a field is optional, has no default, sits on {@link Tab#GENERAL}, serves both directions, is always shown
 * and takes no template. The empty string is no value: a text or secret set to {@code ""} is refused, never taken for
 * absent.
 *
 * <p>The keys of a field's texts are derived from the adapter's scheme and never written:
 * {@code <scheme>.field.<name>.label}, {@code <scheme>.field.<name>.help} and, for each choice,
 * {@code <scheme>.field.<name>.choice.<value>}. The description at {@code GET /admin/components} carries them beside the
 * English texts, and a translation of the adapter's texts uses them.
 *
 * @since 0.1
 */
public final class Field {

    /**
     * What a field holds. {@code TEXT} and {@code SECRET} are strings; a secret is shown masked, has no default, and either
     * may be a reference {@code secret://<name>} that the node resolves after the check; a secret that does not take a plain
     * value ({@link Field#kinds}) is a reference only. {@code INTEGER} is a whole number,
     * {@code BOOLEAN} true or false, {@code CHOICE} one of a fixed list of strings. The JSON form names each in lower case.
     */
    enum Type {
        TEXT("text"), SECRET("secret"), INTEGER("integer"), BOOLEAN("boolean"), CHOICE("choice");

        private final String wire;

        Type(String wire) { this.wire = wire; }
    }

    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");
    private static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");
    private static final int MAX_LABEL = 64;
    private static final int MAX_HELP = 1000;
    private static final int MAX_PATTERN = 1000;
    private static final int MAX_CHOICES = 64;
    private static final int MAX_SHOWN_FOR = 64;

    private final Type type;
    private final String name;
    private final String label;
    private final String help;
    private final boolean required;
    private final Object byDefault;
    private final Tab tab;
    private final Set<Direction> directions;
    private final String shownBy;
    private final List<Object> shownFor;
    private final Long min;
    private final Long max;
    private final String pattern;
    private final Pattern compiled;
    private final List<Choice> choiceList;
    private final List<String> choices;
    private final Escape escape;
    /** The kinds a secret field takes, in the order it names them; empty for any other field. */
    private final Set<Secret.Kind> kinds;
    private final boolean server;

    private Field(Type type, String name, String label, String help, boolean required, Object byDefault, Tab tab, Set<Direction> directions,
                  String shownBy, List<Object> shownFor, Long min, Long max, String pattern, List<Choice> choiceList, Escape escape,
                  Set<Secret.Kind> kinds, boolean server) {
        this.type = type;
        this.name = name;
        this.label = label;
        this.help = help;
        this.required = required;
        this.tab = tab;
        this.directions = directions;
        this.shownBy = shownBy;
        this.shownFor = shownFor;
        this.min = min;
        this.max = max;
        this.pattern = pattern;
        this.choiceList = choiceList;
        this.choices = choiceList == null ? null : choiceList.stream().map(Choice::value).toList();
        this.escape = escape;
        this.kinds = kinds;
        this.server = server;
        this.compiled = validate();
        this.byDefault = byDefault == null ? null : normalDefault(byDefault);
    }

    private static Field of(Type type, String name, String label, String help, List<Choice> choices) {
        return of(type, name, label, help, choices, Set.of());
    }

    private static Field of(Type type, String name, String label, String help, List<Choice> choices, Set<Secret.Kind> kinds) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(help, "help");
        return new Field(type, name, label, help, false, null, Tab.GENERAL, EnumSet.allOf(Direction.class), null, null, null, null, null, choices, null, kinds, false);
    }

    /** A string; {@code label} is what the form names it, 1 to 64 characters, and {@code help} what it says of it, 1 to 1000. */
    public static Field text(String name, String label, String help) { return of(Type.TEXT, name, label, help, null); }

    /**
     * A string shown masked, never with a default; usually a reference {@code secret://<name>}. A secret field belongs to the
     * connection form only: its value reaches the adapter as a handle, {@link dev.rail8.api.Connection#secret}. It takes a
     * plain value only: {@code secret(name, label, help, Kind.VALUE)}.
     */
    public static Field secret(String name, String label, String help) { return secret(name, label, help, Secret.Kind.VALUE); }

    /**
     * A secret field that takes a secret of each of {@code kinds}, in the order given: the settings description serves them
     * as {@code "kinds"}, and {@code rail8 validate} and {@code rail8 test} try their placeholders in that order. Its value
     * is a reference {@code secret://<name>} to a secret of one of them, or, only when {@code kinds} holds
     * {@link Secret.Kind#VALUE}, a plain value written in place. The handle the adapter gets has the kind of the secret the
     * reference names when the flow is built.
     *
     * @throws IllegalArgumentException naming the field when {@code kinds} is empty or names a kind twice
     * @since 0.4
     */
    public static Field secret(String name, String label, String help, Secret.Kind... kinds) {
        Objects.requireNonNull(kinds, "kinds");
        Set<Secret.Kind> taken = new LinkedHashSet<>();
        for (Secret.Kind k : kinds) {
            if (!taken.add(Objects.requireNonNull(k, "kind"))) {
                throw new IllegalArgumentException("field '" + name + "': the kind " + k.id() + " is named twice");
            }
        }
        if (taken.isEmpty()) throw new IllegalArgumentException("field '" + name + "': a secret field takes at least one kind");
        return of(Type.SECRET, name, label, help, null, Collections.unmodifiableSet(taken));
    }

    /** A whole number. */
    public static Field integer(String name, String label, String help) { return of(Type.INTEGER, name, label, help, null); }

    /** True or false. */
    public static Field bool(String name, String label, String help) { return of(Type.BOOLEAN, name, label, help, null); }

    /** One of {@code choices}: 1 to 64, no value twice. */
    public static Field choice(String name, String label, String help, Choice... choices) {
        return of(Type.CHOICE, name, label, help, List.copyOf(List.of(choices)));
    }

    private Field with(boolean required, Object byDefault, Tab tab, Set<Direction> directions, String shownBy, List<Object> shownFor,
                       Long min, Long max, String pattern) {
        return new Field(type, name, label, help, required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern, choiceList, escape, kinds, server);
    }

    /** A field the endpoint must set; it has no default. While hidden by {@link #visibleWhen} it is not required. */
    public Field required() { return with(true, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /**
     * The value the endpoint gets when it leaves the field out, which must fit the field. A whole number is kept as a
     * {@code long}.
     */
    public Field byDefault(Object value) {
        Objects.requireNonNull(value, "value");
        return with(required, value, tab, directions, shownBy, shownFor, min, max, pattern);
    }

    /** The tab the form shows the field on. */
    public Field tab(Tab tab) {
        Objects.requireNonNull(tab, "tab");
        return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern);
    }

    /** A setting of in endpoints only; the last of {@code in()} and {@link #out()} wins. */
    public Field in() { return with(required, byDefault, tab, EnumSet.of(Direction.IN), shownBy, shownFor, min, max, pattern); }

    /** A setting of out endpoints only; the last of {@link #in()} and {@code out()} wins. */
    public Field out() { return with(required, byDefault, tab, EnumSet.of(Direction.OUT), shownBy, shownFor, min, max, pattern); }

    /**
     * Shown only while {@code field}, a choice or a boolean declared earlier in the description, has one of {@code values}
     * (1 to 64). Visibility is transitive: a field shown by a hidden field is hidden too. A hidden field is never required
     * and never refused for being set, but a value it is given must still fit it.
     */
    public Field visibleWhen(String field, Object... values) {
        Objects.requireNonNull(field, "field");
        return with(required, byDefault, tab, directions, field, List.copyOf(List.of(values)), min, max, pattern);
    }

    /** The least whole number allowed. */
    public Field min(long min) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /** The greatest whole number allowed. */
    public Field max(long max) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /** The least and the greatest whole number allowed. */
    public Field range(long min, long max) { return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern); }

    /**
     * A Java regular expression of at most 1000 characters that a text must match whole. A well-formed secret reference
     * stands in for the value and is not matched.
     */
    public Field pattern(String regex) {
        Objects.requireNonNull(regex, "regex");
        return with(required, byDefault, tab, directions, shownBy, shownFor, min, max, regex);
    }

    /**
     * A text field of the flow form, serving only the out direction, whose value may be a template that the node renders for
     * each send, escaping what it puts in by {@code escape}. Until the node renders templates, the value reaches the
     * adapter as written.
     *
     * @throws IllegalArgumentException naming the field when it is not text
     * @since 0.4
     */
    public Field expression(Escape escape) {
        Objects.requireNonNull(escape, "escape");
        return new Field(type, name, label, help, required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern, choiceList, escape, kinds, server);
    }

    /**
     * A field of the connection form that names the server the connection connects to: a URL, a host, a port. Only such
     * fields count as the server a connection's secrets go to, by their effective values; a path, a time limit, a proxy or
     * a trust never does. {@link Forms} takes it on a text or an integer field of the connection form that is no secret, and
     * refuses a connection form that holds a secret field and no such field. The description serves it as
     * {@code "server": true}.
     *
     * @since 0.4
     */
    public Field server() {
        return new Field(type, name, label, help, required, byDefault, tab, directions, shownBy, shownFor, min, max, pattern, choiceList, escape, kinds, true);
    }

    /**
     * Whether the field names the server the connection connects to ({@link #server()}).
     *
     * @since 0.4
     */
    public boolean isServer() { return server; }

    /** The key of the setting. */
    public String name() { return name; }

    /** What the form names the field, in English. */
    public String label() { return label; }

    /**
     * The kinds of secret a secret field takes, in the order it names them; empty for any other field.
     *
     * @since 0.4
     */
    public Set<Secret.Kind> kinds() { return kinds; }

    /** How a template in it is escaped, when it takes one ({@link #expression}). */
    public java.util.Optional<Escape> escape() { return java.util.Optional.ofNullable(escape); }

    boolean isSecret() { return type == Type.SECRET; }

    Type type() { return type; }

    boolean isRequired() { return required; }

    boolean hasDefault() { return byDefault != null; }

    Object byDefault() { return byDefault; }

    Tab tab() { return tab; }

    boolean serves(Direction d) { return directions.contains(d); }

    Set<Direction> directions() { return directions; }

    String shownBy() { return shownBy; }

    List<Object> shownFor() { return shownFor; }

    List<String> choices() { return choices; }

    private IllegalArgumentException refused(String why) {
        return new IllegalArgumentException("field '" + name + "': " + why);
    }

    /** Checks everything a field knows alone and returns its compiled pattern, if any. */
    private Pattern validate() {
        if (!NAME.matcher(name).matches()) throw refused("a name is a lower-case letter, then at most 63 letters or digits");
        if (label.isBlank() || label.length() > MAX_LABEL) throw refused("a label of 1 to 64 characters");
        if (help.isBlank() || help.length() > MAX_HELP) throw refused("a help text of 1 to 1000 characters");
        if (type == Type.CHOICE && !validChoices()) throw refused("a choice of 1 to 64 values, none twice");
        if (escape != null && type != Type.TEXT) throw refused("an expression is for text");
        if ((min != null || max != null) && type != Type.INTEGER) throw refused("min and max are for whole numbers");
        if (min != null && max != null && min > max) throw refused("min " + min + " is above max " + max);
        Pattern p = null;
        if (pattern != null) {
            if (type != Type.TEXT) throw refused("a pattern is for text");
            if (pattern.length() > MAX_PATTERN) throw refused("a pattern of at most 1000 characters");
            try {
                p = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw refused("the pattern does not compile: " + pattern);
            }
        }
        if (shownFor != null && (shownFor.isEmpty() || shownFor.size() > MAX_SHOWN_FOR)) {
            throw refused("visible when '" + shownBy + "' is one of 1 to 64 values");
        }
        return p;
    }

    private boolean validChoices() {
        return !choices.isEmpty() && choices.size() <= MAX_CHOICES && new HashSet<>(choices).size() == choices.size();
    }

    /** The default as the field keeps it, once it is known to fit. */
    private Object normalDefault(Object value) {
        if (type == Type.SECRET) throw refused("a secret has no default");
        if (required) throw refused("a required field has no default");
        if (type == Type.TEXT && !(value instanceof String s && !s.isEmpty())) throw refused("the default must be text that is not empty");
        Wrong problem = problem(value, false);
        if (problem != null) throw refused("the default " + problem.message());
        return type == Type.INTEGER ? wholeNumber(value) : value;
    }

    /** What is wrong with a value: its stable code and the form's words. */
    record Wrong(String code, String message) {}

    /**
     * What is wrong with {@code value} for this field, or null when it fits. A well-formed secret reference fits a text or a
     * secret when {@code references} is true.
     */
    Wrong problem(Object value, boolean references) {
        return switch (type) {
            case TEXT, SECRET -> {
                if (!(value instanceof String s)) yield new Wrong(Settings.Problem.TYPE, "must be text");
                if (s.isEmpty()) yield new Wrong(Settings.Problem.TYPE, "must not be empty");
                if (type == Type.SECRET && !kinds.contains(Secret.Kind.VALUE) && !REFERENCE.matcher(s).matches()) {
                    yield new Wrong(Settings.Problem.TYPE, "must name a secret, secret://<name>, of kind " + ids(kinds) + ": it takes no plain value");
                }
                if (compiled == null || (references && REFERENCE.matcher(s).matches())) yield null;
                yield compiled.matcher(s).matches() ? null : new Wrong(Settings.Problem.PATTERN, "must match " + pattern);
            }
            case INTEGER -> {
                Long n = wholeNumber(value);
                if (n != null && (min == null || n >= min) && (max == null || n <= max)) yield null;
                String code = n == null ? Settings.Problem.TYPE : min != null && n < min ? Settings.Problem.MIN : Settings.Problem.MAX;
                yield new Wrong(code, "must be a whole number" + bounds());
            }
            case BOOLEAN -> value instanceof Boolean ? null : new Wrong(Settings.Problem.TYPE, "must be true or false");
            case CHOICE -> value instanceof String s && choices.contains(s) ? null
                    : new Wrong(Settings.Problem.CHOICE, "must be one of " + String.join(", ", choices));
        };
    }

    private static String ids(Set<Secret.Kind> kinds) {
        return String.join(" or ", kinds.stream().map(Secret.Kind::id).toList());
    }

    private String bounds() {
        if (min != null && max != null) return " from " + min + " to " + max;
        if (min != null) return " of at least " + min;
        if (max != null) return " of at most " + max;
        return "";
    }

    /** {@code value} as a long when it is a whole number a long holds, else null. */
    static Long wholeNumber(Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case BigInteger b -> b.bitLength() < Long.SIZE ? b.longValue() : null;
            case BigDecimal b -> {
                try {
                    yield b.longValueExact();
                } catch (ArithmeticException e) {
                    yield null;
                }
            }
            case Double d -> wholeDouble(d);
            case Float f -> wholeDouble(f.doubleValue());
            case null, default -> null;
        };
    }

    private static Long wholeDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) return null;
        if (d < -0x1p63 || d >= 0x1p63) return null;
        return (long) d;
    }

    /** The field as the JSON form carries it: the keys every field has, then the ones it sets. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.wire);
        m.put("label", label);
        m.put("help", help);
        m.put("tab", tab.id());
        m.put("required", required);
        List<String> dirs = new ArrayList<>();
        for (Direction d : directions) dirs.add(d.name().toLowerCase(Locale.ROOT));
        m.put("directions", List.copyOf(dirs));
        if (pattern != null) m.put("pattern", pattern);
        if (byDefault != null) m.put("default", byDefault);
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        if (choiceList != null) {
            List<Object> list = new ArrayList<>();
            for (Choice c : choiceList) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("value", c.value());
                one.put("label", c.label());
                list.add(Collections.unmodifiableMap(one));
            }
            m.put("choices", List.copyOf(list));
        }
        if (escape != null) m.put("expression", escape.name().toLowerCase(Locale.ROOT));
        if (type == Type.SECRET) m.put("kinds", kinds.stream().map(Secret.Kind::id).toList());
        if (server) m.put("server", true);
        if (shownBy != null) {
            Map<String, Object> when = new LinkedHashMap<>();
            when.put("field", shownBy);
            when.put("values", shownFor);
            m.put("visibleWhen", Collections.unmodifiableMap(when));
        }
        return Collections.unmodifiableMap(m);
    }
}
