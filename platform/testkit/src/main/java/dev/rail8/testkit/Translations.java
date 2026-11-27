package dev.rail8.testkit;

import dev.rail8.api.settings.Forms;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The checks of the texts a component declares and of its translations: every English text is an ICU message, and each
 * file of translations, {@code META-INF/rail8/texts/<scheme>/<language>.json} in the jar or the directory of classes the
 * component's class comes from, is one flat JSON object of strings whose keys the component declares, each an ICU message
 * with the same arguments as its English: the same names, each of the same type. The sub-messages of a {@code plural} or a
 * {@code select} may differ, as a language's plural categories do.
 */
final class Translations {

    private static final String ROOT = "META-INF/rail8/texts/";

    private Translations() {}

    /** The English of each text key {@code forms} declare for the adapter of {@code scheme}, the platform's tabs aside. */
    static Map<String, String> english(String scheme, Forms forms) {
        Map<String, Object> json = forms.toJson(scheme);
        Map<String, String> english = new LinkedHashMap<>();
        for (Object each : (List<?>) json.get("tabs")) {
            Map<?, ?> tab = (Map<?, ?>) each;
            if (!Boolean.TRUE.equals(tab.get("common"))) english.put((String) tab.get("labelKey"), (String) tab.get("label"));
        }
        for (String form : List.of("connection", "flow")) {
            for (Object each : (List<?>) json.get(form)) {
                Map<?, ?> field = (Map<?, ?>) each;
                english.put((String) field.get("labelKey"), (String) field.get("label"));
                english.put((String) field.get("helpKey"), (String) field.get("help"));
                if (field.get("choices") instanceof List<?> choices) {
                    for (Object c : choices) english.put((String) ((Map<?, ?>) c).get("labelKey"), (String) ((Map<?, ?>) c).get("label"));
                }
            }
        }
        return english;
    }

    /** What is wrong with the texts of {@code scheme} and the translations at {@code at}, each naming the scheme, the language and the key. */
    static List<String> problems(String scheme, Forms forms, Optional<Path> at) {
        Map<String, String> english = english(scheme, forms);
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, String>> arguments = new LinkedHashMap<>();
        english.forEach((key, text) -> {
            try {
                arguments.put(key, IcuArguments.of(text));
            } catch (IllegalArgumentException refused) {
                problems.add("scheme " + scheme + ", English, key " + key + ": " + refused.getMessage());
            }
        });
        Map<String, String> files;
        try {
            files = at.isEmpty() ? Map.of() : files(at.get(), ROOT + scheme + "/");
        } catch (IOException | UncheckedIOException failed) {
            problems.add("scheme " + scheme + ": the translations in " + at.get() + " cannot be read: " + failed);
            return problems;
        }
        files.forEach((name, content) -> {
            String language = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
            String where = "scheme " + scheme + ", language " + language;
            JsonObject bundle;
            try {
                bundle = new JsonObject(content);
            } catch (DecodeException | ClassCastException notAnObject) {
                problems.add(where + ": " + ROOT + scheme + "/" + name + " is not one flat JSON object of strings");
                return;
            }
            for (Map.Entry<String, Object> e : bundle) {
                String key = e.getKey();
                if (!english.containsKey(key)) {
                    problems.add(where + ", key " + key + ": the component declares no such key");
                } else if (!(e.getValue() instanceof String message)) {
                    problems.add(where + ", key " + key + ": the translation is no string");
                } else {
                    try {
                        Map<String, String> translated = IcuArguments.of(message);
                        Map<String, String> expected = arguments.get(key);
                        if (expected != null && !new TreeMap<>(expected).equals(new TreeMap<>(translated))) {
                            problems.add(where + ", key " + key + ": the arguments " + new TreeMap<>(translated) + " are not the English's " + new TreeMap<>(expected));
                        }
                    } catch (IllegalArgumentException refused) {
                        problems.add(where + ", key " + key + ": " + refused.getMessage());
                    }
                }
            }
        });
        return problems;
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

    /** The files directly under {@code prefix} at {@code at}, a jar or a directory of classes, by name, as text. */
    private static Map<String, String> files(Path at, String prefix) throws IOException {
        Map<String, String> files = new TreeMap<>();
        if (Files.isDirectory(at)) {
            Path dir = at.resolve(prefix);
            if (!Files.isDirectory(dir)) return files;
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path file : entries.filter(Files::isRegularFile).toList()) files.put(file.getFileName().toString(), Files.readString(file));
            }
        } else if (Files.isRegularFile(at)) {
            try (JarFile jar = new JarFile(at.toFile(), false)) {
                for (Enumeration<JarEntry> all = jar.entries(); all.hasMoreElements(); ) {
                    JarEntry e = all.nextElement();
                    String rest = e.getName().startsWith(prefix) ? e.getName().substring(prefix.length()) : "";
                    if (e.isDirectory() || rest.isEmpty() || rest.contains("/")) continue;
                    try (InputStream in = jar.getInputStream(e)) {
                        files.put(rest, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return files;
    }
}
