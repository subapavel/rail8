package dev.rail8.runtime.plugin;

import dev.rail8.api.Component;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The translations of the texts each component declares for its forms, and of the platform's own, read once, when the node
 * builds its components. A component's translations are files of the jar, or the directory of classes, its class comes from:
 * {@code META-INF/rail8/texts/<scheme>/<language>.json}, each one flat JSON object of the component's text keys to ICU
 * messages. The platform's own, the keys under {@value Forms#PLATFORM}, are files of the same form in this module's jar, under
 * {@code META-INF/rail8/texts/rail8/}.
 *
 * <p>At most {@value #MAX_BUNDLES} files a scheme are read, in name order. A file is left out, with a WARNING naming it and
 * why, when its name is no language of {@link #LANGUAGE} followed by {@code .json}, when it is over {@value #MAX_BYTES} bytes,
 * is no UTF-8, is not one flat JSON object of strings, or holds a key its component does not declare. Its component still
 * loads and shows its English. No message is parsed as ICU: the contract suite and the UI do that.
 *
 * @since 0.4
 */
public final class ComponentTexts {

    /** The most bytes one file of translations may hold. */
    public static final long MAX_BYTES = 256L * 1024;
    /** The most files of translations read for one scheme. */
    public static final int MAX_BUNDLES = 64;
    /** A language: two or three lower-case letters, then perhaps a dash and a region of two upper-case letters. */
    public static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Z]{2})?");
    /** Where the files of a scheme are, under the root of a jar or a directory of classes: this, the scheme and a slash. */
    static final String ROOT = "META-INF/rail8/texts/";

    private static final System.Logger LOG = System.getLogger(ComponentTexts.class.getName());
    /** The keys of the platform's own texts: those of its common tabs. */
    private static final Set<String> PLATFORM_KEYS = declared(Forms.PLATFORM,
            new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.NO_CONNECTION)));

    /** Each scheme's translations, by language: the files' objects as read. */
    private final Map<String, Map<String, JsonObject>> bundles;

    private ComponentTexts(Map<String, Map<String, JsonObject>> bundles) {
        this.bundles = bundles;
    }

    /**
     * The translations of every component of {@code components}, by scheme, each read from where its class comes from, and the
     * platform's own, under {@value Forms#PLATFORM}.
     */
    public static ComponentTexts read(Map<String, Component> components) {
        Map<String, Map<String, JsonObject>> all = new HashMap<>();
        components.forEach((scheme, component) -> all.put(scheme, location(component.getClass())
                .map(at -> bundles(scheme, at, declared(scheme, component.forms()))).orElse(Map.of())));
        all.put(Forms.PLATFORM, location(ComponentTexts.class).map(at -> bundles(Forms.PLATFORM, at, PLATFORM_KEYS)).orElse(Map.of()));
        return new ComponentTexts(Map.copyOf(all));
    }

    /** Whether the node serves a component of {@code scheme}, or {@code scheme} is the platform's own. */
    public boolean knows(String scheme) {
        return bundles.containsKey(scheme);
    }

    /** The translations of {@code scheme} into {@code language}: a flat object of text keys to ICU messages; empty when none. */
    public Optional<JsonObject> of(String scheme, String language) {
        return Optional.ofNullable(bundles.getOrDefault(scheme, Map.of()).get(language)).map(JsonObject::copy);
    }

    /**
     * The text keys {@code forms} declare for the adapter of {@code scheme}: each field's label and help, each choice's label,
     * each of the adapter's own tabs; never the platform's, under {@value Forms#PLATFORM}, unless {@code scheme} is it.
     */
    static Set<String> declared(String scheme, Forms forms) {
        Map<String, Object> json = forms.toJson(scheme);
        Set<String> keys = new LinkedHashSet<>();
        for (Object tab : (List<?>) json.get("tabs")) {
            String key = (String) ((Map<?, ?>) tab).get("labelKey");
            if (key.startsWith(scheme + ".")) keys.add(key);
        }
        for (String form : List.of("connection", "flow")) {
            for (Object each : (List<?>) json.get(form)) {
                Map<?, ?> field = (Map<?, ?>) each;
                keys.add((String) field.get("labelKey"));
                keys.add((String) field.get("helpKey"));
                if (field.get("choices") instanceof List<?> choices) {
                    for (Object c : choices) keys.add((String) ((Map<?, ?>) c).get("labelKey"));
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    /** The jar, or the directory of classes, {@code type} was loaded from; empty when that is no file. */
    static Optional<Path> location(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL url = source == null ? null : source.getLocation();
        if (url == null || !"file".equals(url.getProtocol())) return Optional.empty();
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException noPath) {
            return Optional.empty();
        }
    }

    /**
     * The translations of {@code scheme} found at {@code at}, a jar or a directory of classes, by language: each file that keeps
     * the rules of the class comment and holds only keys of {@code declared}; each other one left out with a WARNING.
     */
    static Map<String, JsonObject> bundles(String scheme, Path at, Set<String> declared) {
        String prefix = ROOT + scheme + "/";
        Map<String, JsonObject> found = new TreeMap<>();
        try {
            List<Read> files = Files.isDirectory(at) ? fromDirectory(at, prefix) : Files.isRegularFile(at) ? fromJar(at, prefix) : List.of();
            int taken = 0;
            for (Read file : files) {
                String where = at.getFileName() + "!/" + prefix + file.name();
                if (taken == MAX_BUNDLES) {
                    LOG.log(System.Logger.Level.WARNING, "the translations " + where + " are left out: a scheme has at most " + MAX_BUNDLES + " files of translations");
                    continue;
                }
                taken++;
                String name = file.name();
                String language = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
                Optional<String> refused = !name.endsWith(".json") || !LANGUAGE.matcher(language).matches()
                        ? Optional.of("its name is no language of " + LANGUAGE.pattern() + " followed by .json")
                        : file.bytes() == null ? Optional.of("it is over " + MAX_BYTES + " bytes") : Optional.empty();
                JsonObject bundle = null;
                if (refused.isEmpty()) {
                    try {
                        bundle = new JsonObject(utf8(file.bytes()));
                        refused = refusal(bundle, declared);
                    } catch (DecodeException | CharacterCodingException | ClassCastException notAnObject) {
                        refused = Optional.of("it is not one flat JSON object of strings in UTF-8");
                    }
                }
                if (refused.isPresent()) {
                    LOG.log(System.Logger.Level.WARNING, "the translations " + where + " are left out, and scheme " + scheme + " shows its English there: " + refused.get());
                } else {
                    found.put(language, bundle);
                }
            }
        } catch (IOException | UncheckedIOException failed) {
            LOG.log(System.Logger.Level.WARNING, "the translations of scheme " + scheme + " in " + at + " cannot be read, so it shows its English: " + failed);
            return Map.of();
        }
        return Collections.unmodifiableMap(found);
    }

    /** Why {@code bundle} is left out: a value that is no string, or a key that is not {@code declared}; empty when neither. */
    private static Optional<String> refusal(JsonObject bundle, Set<String> declared) {
        for (Map.Entry<String, Object> e : bundle) {
            if (!(e.getValue() instanceof String)) return Optional.of("it is not one flat JSON object of strings: the value of '" + e.getKey() + "' is no string");
            if (!declared.contains(e.getKey())) return Optional.of("its component declares no key '" + e.getKey() + "'");
        }
        return Optional.empty();
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** A file of translations: its name, and its bytes, or null when it is over {@link #MAX_BYTES}. */
    private record Read(String name, byte[] bytes) {}

    private static List<Read> fromDirectory(Path classes, String prefix) throws IOException {
        Path dir = classes.resolve(prefix);
        if (!Files.isDirectory(dir)) return List.of();
        List<Read> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    files.add(new Read(file.getFileName().toString(), bounded(in)));
                }
            }
        }
        return files;
    }

    private static List<Read> fromJar(Path jar, String prefix) throws IOException {
        List<Read> files = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> all = file.entries(); all.hasMoreElements(); ) {
                JarEntry e = all.nextElement();
                String rest = e.getName().startsWith(prefix) ? e.getName().substring(prefix.length()) : "";
                if (!e.isDirectory() && !rest.isEmpty() && !rest.contains("/")) entries.add(e);
            }
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry e : entries) {
                try (InputStream in = file.getInputStream(e)) {
                    files.add(new Read(e.getName().substring(prefix.length()), bounded(in)));
                }
            }
        }
        return files;
    }

    /** The bytes of {@code in}, or null when it holds more than {@link #MAX_BYTES}. */
    private static byte[] bounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
        return bytes.length > MAX_BYTES ? null : bytes;
    }
}
