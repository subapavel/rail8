package dev.rail8.runtime.plugin;

import dev.rail8.api.settings.Choice;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.api.settings.Tab;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A component's translations are the files {@code META-INF/rail8/texts/<scheme>/<language>.json} where its class comes from,
 * a jar or a directory of classes; a file that breaks a rule is left out with a WARNING naming it and why, and the rest are
 * still read.
 */
class ComponentTextsTest {

    static final Forms FORMS = new Forms(Settings.of(
            Field.text("host", "Host", "The server.").in().server()),
            Settings.of(Field.choice("after", "After", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move"))
                    .byDefault("delete").tab(Tab.of("polling", "Polling")).in()),
            Map.of(Direction.IN, Forms.Reach.CONNECTION));

    @TempDir Path tmp;
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    final Logger logger = Logger.getLogger(ComponentTexts.class.getName());
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void listen() {
        logger.addHandler(capture);
    }

    @AfterEach
    void stopListening() {
        logger.removeHandler(capture);
    }

    Path classes(Map<String, String> files) throws IOException {
        Path dir = tmp.resolve("classes");
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path file = dir.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, f.getValue());
        }
        return dir;
    }

    List<String> warnings() {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    @Test
    void theKeysOfAComponentAreItsFieldsChoicesAndOwnTabsAndThePlatformsAreItsCommonTabs() {
        assertEquals(Set.of("drop.tab.polling", "drop.field.host.label", "drop.field.host.help", "drop.field.after.label",
                "drop.field.after.help", "drop.field.after.choice.delete", "drop.field.after.choice.move"), ComponentTexts.declared("drop", FORMS));
        assertEquals(Set.of("rail8.tab.general", "rail8.tab.retry", "rail8.tab.recording", "rail8.tab.security"),
                ComponentTexts.declared(Forms.PLATFORM, new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.NO_CONNECTION))));
    }

    @Test
    void eachLanguageOfADirectoryOfClassesIsReadAndAnotherSchemesAreNot() throws IOException {
        Path at = classes(Map.of(
                "META-INF/rail8/texts/drop/cs.json", "{\"drop.field.host.label\": \"Server\", \"drop.tab.polling\": \"Dotazování\"}",
                "META-INF/rail8/texts/drop/pt-BR.json", "{\"drop.field.host.label\": \"Servidor\"}",
                "META-INF/rail8/texts/other/de.json", "{\"other.field.host.label\": \"Host\"}"));
        Map<String, JsonObject> read = ComponentTexts.bundles("drop", at, ComponentTexts.declared("drop", FORMS));
        assertEquals(Set.of("cs", "pt-BR"), read.keySet());
        assertEquals("Dotazování", read.get("cs").getString("drop.tab.polling"));
        assertEquals(List.of(), warnings());
    }

    @Test
    void eachLanguageOfAJarIsRead() throws IOException {
        Path jar = tmp.resolve("drop.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/rail8/texts/drop/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/rail8/texts/drop/de.json"));
            out.write("{\"drop.field.after.choice.move\": \"Verschieben\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/rail8/texts/drop/deeper/cs.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Map<String, JsonObject> read = ComponentTexts.bundles("drop", jar, ComponentTexts.declared("drop", FORMS));
        assertEquals(Set.of("de"), read.keySet());
        assertEquals("Verschieben", read.get("de").getString("drop.field.after.choice.move"));
    }

    /**
     * A file over 256 KiB, one that is not a flat object of strings, one with a key the component does not declare and one
     * whose name is no language are each left out with a WARNING naming the file and why; the good one is still read.
     */
    @Test
    void aBadFileIsLeftOutWithAWarningAndTheOthersAreStillRead() throws IOException {
        String big = "{\"drop.field.host.label\": \"" + "x".repeat((int) ComponentTexts.MAX_BYTES) + "\"}";
        Path at = classes(Map.of(
                "META-INF/rail8/texts/drop/cs.json", "{\"drop.field.host.label\": \"Server\"}",
                "META-INF/rail8/texts/drop/de.json", big,
                "META-INF/rail8/texts/drop/ru.json", "{\"drop.field.host.label\": {\"nested\": \"no\"}}",
                "META-INF/rail8/texts/drop/fr.json", "[\"drop.field.host.label\"]",
                "META-INF/rail8/texts/drop/pl.json", "{\"drop.field.port.label\": \"Port\"}",
                "META-INF/rail8/texts/drop/it.json", "{\"rail8.tab.general\": \"Generale\"}",
                "META-INF/rail8/texts/drop/cz_CZ.json", "{\"drop.field.host.label\": \"Server\"}"));
        Map<String, JsonObject> read = ComponentTexts.bundles("drop", at, ComponentTexts.declared("drop", FORMS));
        assertEquals(Set.of("cs"), read.keySet(), "only the good file");
        List<String> warnings = warnings();
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/de.json") && w.contains("over " + ComponentTexts.MAX_BYTES + " bytes")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/ru.json") && w.contains("no string")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/fr.json") && w.contains("not one flat JSON object")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/pl.json") && w.contains("declares no key 'drop.field.port.label'")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/it.json") && w.contains("declares no key 'rail8.tab.general'")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("drop/cz_CZ.json") && w.contains("no language")), warnings.toString());
    }

    @Test
    void aFileOfExactly256KiBIsRead() throws IOException {
        String head = "{\"drop.field.host.label\": \"";
        String tail = "\"}";
        String exact = head + "x".repeat((int) ComponentTexts.MAX_BYTES - head.length() - tail.length()) + tail;
        Path at = classes(Map.of("META-INF/rail8/texts/drop/cs.json", exact));
        assertEquals(Set.of("cs"), ComponentTexts.bundles("drop", at, ComponentTexts.declared("drop", FORMS)).keySet());
    }

    @Test
    void aFileThatIsNoUtf8IsLeftOut() throws IOException {
        Path at = classes(Map.of());
        Path file = at.resolve("META-INF/rail8/texts/drop/cs.json");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write("{\"drop.field.host.label\": \"".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[] {(byte) 0xC3, (byte) 0x28});
            out.write("\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(Map.of(), ComponentTexts.bundles("drop", at, ComponentTexts.declared("drop", FORMS)));
        assertTrue(warnings().getFirst().contains("UTF-8"), warnings().toString());
    }

    @Test
    void atMost64FilesAreReadAScheme() throws IOException {
        Map<String, String> files = new java.util.HashMap<>();
        for (int i = 0; i < ComponentTexts.MAX_BUNDLES + 1; i++) {
            String language = "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            files.put("META-INF/rail8/texts/drop/" + language + ".json", "{}");
        }
        Path at = classes(files);
        Map<String, JsonObject> read = ComponentTexts.bundles("drop", at, ComponentTexts.declared("drop", FORMS));
        assertEquals(ComponentTexts.MAX_BUNDLES, read.size());
        assertTrue(!read.containsKey("cm") && read.containsKey("cl"), "the first 64 in name order: " + read.keySet());
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().getFirst().contains("drop/cm.json") && warnings().getFirst().contains("at most 64"), warnings().toString());
    }

    /**
     * A file past the first 64 is never read: here the 65th entry of a jar holds compressed data that cannot be inflated, so
     * reading it fails and would leave the whole scheme in English.
     */
    @Test
    void aFilePastThe64thIsNeverRead() throws IOException {
        Path jar = tmp.resolve("many.jar");
        String last = "META-INF/rail8/texts/drop/zz.json";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < ComponentTexts.MAX_BUNDLES; i++) {
                out.putNextEntry(new JarEntry("META-INF/rail8/texts/drop/" + (char) ('a' + i / 26) + (char) ('a' + i % 26) + ".json"));
                out.write("{}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(last));
            out.write("{\"drop.field.host.label\": \"Server\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        // the first byte of the entry's data, after its local header, becomes a deflate block of the reserved type
        byte[] bytes = Files.readAllBytes(jar);
        int header = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(last) - 30;
        int extra = (bytes[header + 28] & 0xFF) | (bytes[header + 29] & 0xFF) << 8;
        bytes[header + 30 + last.length() + extra] = (byte) 0xFF;
        Files.write(jar, bytes);

        Map<String, JsonObject> read = ComponentTexts.bundles("drop", jar, ComponentTexts.declared("drop", FORMS));
        assertEquals(ComponentTexts.MAX_BUNDLES, read.size(), warnings().toString());
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().getFirst().contains("drop/zz.json") && warnings().getFirst().contains("at most 64"), warnings().toString());
    }

    /** The platform's own translations ship in the node's jar, under {@code rail8}, for the UI's languages other than English. */
    @Test
    void thePlatformsOwnTranslationsAreRead() {
        ComponentTexts texts = ComponentTexts.read(Map.of());
        assertTrue(texts.knows(Forms.PLATFORM));
        for (String language : List.of("de", "cs", "ru")) {
            assertEquals(Set.of("rail8.tab.general", "rail8.tab.retry", "rail8.tab.recording", "rail8.tab.security"),
                    texts.of(Forms.PLATFORM, language).orElseThrow().fieldNames(), language);
        }
        assertEquals("Obecné", texts.of(Forms.PLATFORM, "cs").orElseThrow().getString("rail8.tab.general"));
        assertTrue(texts.of(Forms.PLATFORM, "en").isEmpty(), "English is the code's own");
        assertEquals(List.of(), warnings());
    }
}
