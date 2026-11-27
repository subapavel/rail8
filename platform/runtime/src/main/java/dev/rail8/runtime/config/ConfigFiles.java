package dev.rail8.runtime.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import dev.rail8.api.settings.Settings;
import dev.rail8.schemas.Schemas;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The files of an environment's configuration, one per object, in YAML: what the export writes and the import reads. A file is
 * {@code schemaVersion}, {@code kind} ({@code environment.connection} or {@code environment.system}), {@code key}, then the
 * object's body: a connection's {@code adapter}, {@code mock} and {@code values}, then {@code secrets}, which is written for the
 * reader and never read; a system's {@code instanceName}. Its path is {@code connection/<key>.yaml} or {@code system/<key>.yaml}.
 *
 * <p>Each kind has a JSON Schema for every format it reads and a chain of upgrades, one {@link FormatUpgrade} from each format
 * to the next: a file or a stored body of an older format is read in the newest one. A file of a newer format, or of a kind the
 * node does not know, is a problem {@code format} naming the newest format of each kind it reads.
 */
final class ConfigFiles {

    /** The most bytes a file holds, as UTF-8: twice a body, so every body a store accepts has a file. */
    static final long MAX_FILE_BYTES = 128L * 1024;

    private static final String ALLOWED_HOSTS = "allowedHosts";
    /** The members of a file around its body. */
    private static final Set<String> ENVELOPE = Set.of("schemaVersion", "kind", "key", "secrets");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML_IN = new ObjectMapper(YAMLFactory.builder()
            .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper YAML_OUT = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .build());

    /**
     * One kind of file: its name in a file's {@code kind}, the kind of object it holds, the schema of each format it reads (1 to
     * the newest), and its chain of upgrades, from format 1 to the newest, one step from each format.
     */
    record FileKind(String name, Ref.Kind kind, Map<Integer, Function<JsonObject, Schemas.Report>> schemas, List<FormatUpgrade> upgrades) {
        FileKind {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            schemas = Map.copyOf(schemas);
            upgrades = List.copyOf(upgrades);
            for (int format = 1; format <= schemas.size(); format++) {
                if (!schemas.containsKey(format)) throw new IllegalArgumentException(name + ": a schema for each format from 1, not " + schemas.keySet());
            }
            if (upgrades.size() != schemas.size() - 1) throw new IllegalArgumentException(name + ": one upgrade from each format but the newest");
            for (int i = 0; i < upgrades.size(); i++) {
                if (upgrades.get(i).from() != i + 1) throw new IllegalArgumentException(name + ": the upgrades from format 1 in order");
            }
        }

        /** The newest format of the kind, the one the node writes. */
        int newest() {
            return schemas.size();
        }
    }

    /** The kinds this node reads: a connection, a system and a credential, each of format 1. */
    static final ConfigFiles STANDARD = new ConfigFiles(List.of(
            new FileKind("environment.connection", Ref.Kind.CONNECTION, Map.of(1, Schemas.ENVIRONMENT_CONNECTION::validate), List.of()),
            new FileKind("environment.system", Ref.Kind.SYSTEM, Map.of(1, Schemas.ENVIRONMENT_SYSTEM::validate), List.of()),
            new FileKind("environment.credential", Ref.Kind.CREDENTIAL, Map.of(1, Schemas.ENVIRONMENT_CREDENTIAL::validate), List.of())));

    private final Map<String, FileKind> byName = new TreeMap<>();
    private final Map<Ref.Kind, FileKind> byKind = new EnumMap<>(Ref.Kind.class);

    ConfigFiles(List<FileKind> kinds) {
        for (FileKind k : kinds) {
            if (byName.put(k.name(), k) != null || byKind.put(k.kind(), k) != null) throw new IllegalArgumentException("one file kind per kind: " + k.name());
        }
    }

    /**
     * A file as the import read it: its path; the object its kind and key name, once both are read; its body in the newest format,
     * once the whole file is read; and its problems, each {@code {path, code, text}} with the values it names, never a value of
     * the file.
     */
    record Read(String path, Optional<Ref> ref, Optional<JsonObject> body, List<JsonObject> problems) {
        Read {
            Objects.requireNonNull(ref, "ref");
            body = body.map(JsonObject::copy);
            problems = List.copyOf(problems);
        }
    }

    /**
     * Reads the file {@code text} at {@code path}: at most {@value #MAX_FILE_BYTES} bytes ({@code too-large}), a YAML mapping
     * ({@code yaml}, naming the line and column only), of a kind and format this node reads ({@code format}), valid against its
     * format's schema ({@code schema}, one problem per refusal), a key the parser of keys takes ({@code key}), and at the path
     * its kind and key give ({@code path}). Its body is then upgraded to the newest format.
     */
    Read read(String path, String text) {
        List<JsonObject> problems = new ArrayList<>();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            problems.add(problem(path, "too-large", "a file holds at most " + MAX_FILE_BYTES + " bytes").put("max", MAX_FILE_BYTES));
            return new Read(path, Optional.empty(), Optional.empty(), problems);
        }
        JsonObject doc;
        try {
            Object parsed = YAML_IN.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                problems.add(problem(path, "yaml", "a file is a YAML mapping"));
                return new Read(path, Optional.empty(), Optional.empty(), problems);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> keyed = (Map<String, Object>) map;
            doc = new JsonObject(keyed);
        } catch (JsonProcessingException wrong) {
            JsonLocation at = wrong.getLocation();
            String where = at == null ? "" : " at line " + at.getLineNr() + ", column " + at.getColumnNr();
            problems.add(problem(path, "yaml", "the file is not valid YAML" + where));
            return new Read(path, Optional.empty(), Optional.empty(), problems);
        }
        FileKind kind = doc.getValue("kind") instanceof String name ? byName.get(name) : null;
        Object version = doc.getValue("schemaVersion");
        if (kind == null || version instanceof Integer v && v > kind.newest()) {
            JsonObject newest = new JsonObject();
            byName.values().forEach(k -> newest.put(k.name(), k.newest()));
            String what = kind == null ? "its kind is none this node reads" : "its schemaVersion is newer than this node reads";
            problems.add(problem(path, "format", what + ": it reads " + readable(newest)).put("newest", newest));
            return new Read(path, Optional.empty(), Optional.empty(), problems);
        }
        int format = version instanceof Integer v && v >= 1 ? v : kind.newest();
        Schemas.Report report = kind.schemas().get(format).apply(doc);
        for (Schemas.Problem p : report.problems()) {
            problems.add(problem(path, "schema", (p.path().isEmpty() ? "the file" : p.path()) + ": " + p.message()).put("at", p.path()));
        }
        Optional<Ref> ref = Optional.empty();
        if (doc.getValue("key") instanceof String key) {
            try {
                ref = Optional.of(new Ref(kind.kind(), key));
            } catch (IllegalArgumentException refused) {
                problems.add(problem(path, "key", refused.getMessage()));
            }
        }
        ref.filter(r -> !path(r).equals(path)).ifPresent(r -> problems.add(problem(path, "path", "the file of " + r + " is at " + path(r)
                + ", not at " + path).put("expected", path(r))));
        if (!report.valid() || ref.isEmpty()) return new Read(path, ref, Optional.empty(), problems);
        JsonObject body = new JsonObject();
        doc.forEach(e -> {
            if (!ENVELOPE.contains(e.getKey())) body.put(e.getKey(), e.getValue());
        });
        return new Read(path, ref, Optional.of(upgrade(kind, format, body)), problems);
    }

    /** The newest format of {@code kind}, the one the node writes. */
    int newest(Ref.Kind kind) {
        return byKind.get(kind).newest();
    }

    /**
     * {@code body}, a stored body of {@code kind} in format {@code format}, in the newest format.
     *
     * @throws IllegalStateException a format newer than this node reads, which a release never writes while a node of the release
     *                               before it runs
     */
    JsonObject upgrade(Ref.Kind kind, int format, JsonObject body) {
        FileKind k = byKind.get(kind);
        if (format < 1 || format > k.newest()) throw new IllegalStateException("a body of " + k.name() + " of format " + format + ": this node reads 1 to " + k.newest());
        return upgrade(k, format, body);
    }

    private static JsonObject upgrade(FileKind kind, int format, JsonObject body) {
        if (format == kind.newest()) return body.copy();
        try {
            JsonNode node = JSON.readTree(body.encode());
            for (FormatUpgrade step : kind.upgrades().subList(format - 1, kind.upgrades().size())) node = step.apply(node);
            return new JsonObject(JSON.writeValueAsString(node));
        } catch (JsonProcessingException impossible) {   // the text of a JsonObject, and a tree Jackson made
            throw new IllegalStateException(impossible);
        }
    }

    /** The path of the file of {@code ref}: {@code connection/<key>.yaml}, {@code system/<key>.yaml} or {@code credential/<key>.yaml}. */
    static String path(Ref ref) {
        return ref.kind().id() + "/" + ref.key() + ".yaml";
    }

    /**
     * The text of the file of {@code ref} whose body in force is {@code body}, of the newest format: its members in the order of
     * the format; a connection's values in the order of its adapter's {@code form}, without the fields at their defaults, then
     * {@code allowedHosts}, then any value the form does not know; and {@code secrets} when it names any.
     */
    String text(Ref ref, JsonObject body, Optional<Settings> form, JsonObject secrets) {
        FileKind kind = byKind.get(ref.kind());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", kind.newest());
        doc.put("kind", kind.name());
        doc.put("key", ref.key());
        if (ref.kind() == Ref.Kind.SYSTEM) {
            doc.put("instanceName", body.getString("instanceName"));
        } else if (ref.kind() == Ref.Kind.CREDENTIAL) {
            for (String field : List.of("type", "user", "password", "apiKey")) if (body.containsKey(field)) doc.put(field, body.getString(field));
        } else {
            JsonObject shown = form.map(f -> withoutDefaults(body, f)).orElse(body);
            doc.put("adapter", shown.getString("adapter"));
            if (shown.getValue("mock") instanceof JsonObject mock) doc.put("mock", plain(mock));
            JsonObject values = shown.getValue("values") instanceof JsonObject v ? v : new JsonObject();
            Map<String, Object> ordered = new LinkedHashMap<>();
            for (String name : form.map(ConfigFiles::fieldNames).orElse(List.of())) {
                if (values.containsKey(name)) ordered.put(name, plain(values.getValue(name)));
            }
            if (values.containsKey(ALLOWED_HOSTS)) ordered.put(ALLOWED_HOSTS, plain(values.getValue(ALLOWED_HOSTS)));
            new TreeMap<>(values.getMap()).forEach((name, value) -> ordered.putIfAbsent(name, plain(value)));
            doc.put("values", ordered);
            if (!secrets.isEmpty()) doc.put("secrets", plain(secrets));
        }
        try {
            return YAML_OUT.writeValueAsString(doc);
        } catch (JsonProcessingException impossible) {   // maps, lists, texts, numbers and booleans
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * A connection's {@code body} without the values at the default of their field in {@code form}: a field at its default is
     * neither exported nor told apart from one not set.
     */
    static JsonObject withoutDefaults(JsonObject body, Settings form) {
        JsonObject copy = body.copy();
        if (!(copy.getValue("values") instanceof JsonObject values)) return copy;
        Map<String, Object> defaults = new HashMap<>();
        if (form.toJson().get("fields") instanceof List<?> fields) {
            for (Object f : fields) {
                Map<?, ?> field = (Map<?, ?>) f;
                if (field.containsKey("default")) defaults.put((String) field.get("name"), field.get("default"));
            }
        }
        for (String name : List.copyOf(values.fieldNames())) {
            if (defaults.containsKey(name) && Jcs.write(defaults.get(name)).equals(Jcs.write(values.getValue(name)))) values.remove(name);
        }
        return copy;
    }

    private static List<String> fieldNames(Settings form) {
        List<String> names = new ArrayList<>();
        if (form.toJson().get("fields") instanceof List<?> fields) for (Object f : fields) names.add((String) ((Map<?, ?>) f).get("name"));
        return names;
    }

    /** {@code value} as maps, lists and scalars, which the YAML writer takes. */
    private static Object plain(Object value) {
        if (value instanceof JsonObject o) {
            Map<String, Object> m = new LinkedHashMap<>();
            o.forEach(e -> m.put(e.getKey(), plain(e.getValue())));
            return m;
        }
        if (value instanceof JsonArray a) {
            List<Object> l = new ArrayList<>();
            a.forEach(v -> l.add(plain(v)));
            return l;
        }
        if (value instanceof Map<?, ?> m) return plain(new JsonObject(new LinkedHashMap<>(castMap(m))));
        if (value instanceof List<?> l) return plain(new JsonArray(new ArrayList<>(l)));
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String readable(JsonObject newest) {
        List<String> each = new ArrayList<>();
        newest.forEach(e -> each.add(e.getKey() + " up to format " + e.getValue()));
        return String.join(", ", each);
    }

    /** A problem of the file at {@code path}: its code and text. */
    static JsonObject problem(String path, String code, String text) {
        return new JsonObject().put("path", path).put("code", code).put("text", text);
    }
}
