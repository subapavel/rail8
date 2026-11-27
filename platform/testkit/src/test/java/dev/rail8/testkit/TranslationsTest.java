package dev.rail8.testkit;

import dev.rail8.api.settings.Choice;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.api.settings.Tab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suite's check of a component's texts and translations: it passes for translations that keep the keys and arguments
 * of the English, and fails naming the scheme, the language and the key for each that does not.
 */
class TranslationsTest {

    static final Forms FORMS = new Forms(Settings.NONE, Settings.of(
            Field.text("dir", "Directory", "Up to {count, number} files are read from it.").in(),
            Field.choice("after", "After", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete")
                    .tab(Tab.of("polling", "Polling")).in(),
            Field.text("broken", "Broken {", "Its label is no ICU message.").in()),
            Map.of(Direction.IN, Forms.Reach.NO_CONNECTION));

    static final Forms GOOD_FORMS = new Forms(Settings.NONE, Settings.of(
            Field.text("dir", "Directory", "Up to {count, number} files are read from it.").in(),
            Field.choice("after", "After", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete")
                    .tab(Tab.of("polling", "Polling")).in()),
            Map.of(Direction.IN, Forms.Reach.NO_CONNECTION));

    @TempDir Path tmp;

    Optional<Path> classes(String language, String json) throws IOException {
        Path file = tmp.resolve("META-INF/rail8/texts/drop/" + language + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return Optional.of(tmp);
    }

    @Test
    void theEnglishOfEachKeyIsItsLabelHelpChoiceOrOwnTab() {
        assertEquals(Map.of("drop.tab.polling", "Polling", "drop.field.dir.label", "Directory", "drop.field.dir.help", "Up to {count, number} files are read from it.",
                "drop.field.after.label", "After", "drop.field.after.help", "What then.", "drop.field.after.choice.delete", "Delete",
                "drop.field.after.choice.move", "Move"), Translations.english("drop", GOOD_FORMS));
    }

    @Test
    void translationsThatKeepTheKeysAndArgumentsPass() throws IOException {
        Optional<Path> at = classes("cs", """
                {"drop.field.dir.label": "Adresář", "drop.field.dir.help": "Čte se z něj až {count, number} souborů.",
                 "drop.tab.polling": "Dotazování", "drop.field.after.choice.move": "<b>Přesunout</b>"}""");
        assertEquals(List.of(), Translations.problems("drop", GOOD_FORMS, at));
        assertEquals(List.of(), Translations.problems("drop", GOOD_FORMS, Optional.empty()), "no translations at all");
    }

    @Test
    void anEnglishTextThatIsNoIcuMessageFailsNamingItsKey() {
        List<String> problems = Translations.problems("drop", FORMS, Optional.empty());
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("scheme drop, English, key drop.field.broken.label: no ICU message"), problems.toString());
    }

    @Test
    void aKeyTheComponentDoesNotDeclareFails() throws IOException {
        List<String> problems = Translations.problems("drop", GOOD_FORMS, classes("de", "{\"drop.field.port.label\": \"Port\"}"));
        assertEquals(List.of("scheme drop, language de, key drop.field.port.label: the component declares no such key"), problems);
        problems = Translations.problems("drop", GOOD_FORMS, classes("de", "{\"rail8.tab.general\": \"Allgemein\"}"));
        assertEquals(List.of("scheme drop, language de, key rail8.tab.general: the component declares no such key"), problems, "the platform's are not the component's");
    }

    @Test
    void anArgumentRenamedFails() throws IOException {
        List<String> problems = Translations.problems("drop", GOOD_FORMS, classes("cs", "{\"drop.field.dir.help\": \"Čte se z něj až {n, number} souborů.\"}"));
        assertEquals(List.of("scheme drop, language cs, key drop.field.dir.help: the arguments {n=number} are not the English's {count=number}"), problems);
    }

    @Test
    void anArgumentOfAnotherTypeFails() throws IOException {
        List<String> problems = Translations.problems("drop", GOOD_FORMS, classes("ru", """
                {"drop.field.dir.help": "Читается до {count, plural, one {# файла} other {# файлов}}."}"""));
        assertEquals(List.of("scheme drop, language ru, key drop.field.dir.help: the arguments {count=plural} are not the English's {count=number}"), problems);
    }

    @Test
    void aTranslationThatIsNoIcuMessageFails() throws IOException {
        List<String> problems = Translations.problems("drop", GOOD_FORMS, classes("cs", "{\"drop.field.dir.label\": \"Adresář {\"}"));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("scheme drop, language cs, key drop.field.dir.label: no ICU message"), problems.toString());
    }

    @Test
    void aFileThatIsNoFlatObjectOfStringsFails() throws IOException {
        assertEquals(List.of("scheme drop, language cs: META-INF/rail8/texts/drop/cs.json is not one flat JSON object of strings"),
                Translations.problems("drop", GOOD_FORMS, classes("cs", "[]")));
        assertEquals(List.of("scheme drop, language cs, key drop.field.dir.label: the translation is no string"),
                Translations.problems("drop", GOOD_FORMS, classes("cs", "{\"drop.field.dir.label\": 1}")));
    }

    @Test
    void theTranslationsOfAJarAreChecked() throws IOException {
        Path jar = tmp.resolve("drop.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/rail8/texts/drop/cs.json"));
            out.write("{\"drop.field.port.label\": \"Port\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertEquals(List.of("scheme drop, language cs, key drop.field.port.label: the component declares no such key"),
                Translations.problems("drop", GOOD_FORMS, Optional.of(jar)));
    }
}
