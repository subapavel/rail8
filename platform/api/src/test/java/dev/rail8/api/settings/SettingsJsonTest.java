package dev.rail8.api.settings;

import dev.rail8.api.Secret;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The JSON form of a description, as the admin API serves it and the UI draws it: the platform's common tabs around the
 * adapter's own, every field flat with its tab, and optional keys only where the field sets them.
 */
class SettingsJsonTest {

    static Map<String, Object> tab(String id, String label, boolean common) {
        return Map.of("id", id, "label", label, "common", common);
    }

    static final List<Map<String, Object>> COMMON_ONLY = List.of(tab("general", "General", true), tab("retry", "Retry", true),
            tab("recording", "Recording", true), tab("security", "Security", true));

    @Test
    void anUndescribedComponentHasTheCommonTabsAndNoField() {
        assertEquals(Map.of("described", false, "tabs", COMMON_ONLY, "fields", List.of(), "rules", List.of()), Settings.NONE.toJson());
    }

    @Test
    void ownTabsComeBetweenGeneralAndRetryInTheOrderFieldsFirstUseThem() {
        Tab files = Tab.of("files", "Files");
        Tab polling = Tab.of("polling", "Polling");
        Settings s = Settings.of(Field.text("a", "A", "a").tab(polling), Field.text("b", "B", "b").tab(Tab.SECURITY), Field.text("c", "C", "c").tab(files),
                Field.text("d", "D", "d").tab(polling), Field.text("e", "E", "e"));
        assertEquals(List.of(tab("general", "General", true), tab("polling", "Polling", false), tab("files", "Files", false),
                tab("retry", "Retry", true), tab("recording", "Recording", true), tab("security", "Security", true)), s.toJson().get("tabs"));
    }

    @Test
    void everyFieldWithWhatItSets() {
        Settings s = Settings.of(
                        Field.text("host", "Host", "The server.").pattern("[a-z.]+").byDefault("localhost"),
                        Field.integer("port", "Port", "The port.").range(1, 65535).byDefault(22),
                        Field.integer("least", "Least", "At least one.").min(1),
                        Field.secret("password", "Password", "The password.").tab(Tab.SECURITY),
                        Field.secret("privateKey", "Private key", "The key.", Secret.Kind.KEY_PAIR, Secret.Kind.VALUE).tab(Tab.SECURITY),
                        Field.choice("afterSuccess", "After success", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete").in(),
                        Field.text("moveTo", "Move to", "Where to.").required().visibleWhen("afterSuccess", "move").in(),
                        Field.bool("tls", "Tls", "Whether TLS is on.").byDefault(false).out(),
                        Field.text("ca", "Ca", "The CA.").visibleWhen("tls", true).out())
                .exactlyOne("password", "privateKey");
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "host", "type", "text", "label", "Host", "help", "The server.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "pattern", "[a-z.]+", "default", "localhost"),
                Map.of("name", "port", "type", "integer", "label", "Port", "help", "The port.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "min", 1L, "max", 65535L, "default", 22L),
                Map.of("name", "least", "type", "integer", "label", "Least", "help", "At least one.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "min", 1L),
                Map.of("name", "password", "type", "secret", "label", "Password", "help", "The password.", "tab", "security", "required", false, "directions", List.of("in", "out"),
                        "kinds", List.of("value")),
                Map.of("name", "privateKey", "type", "secret", "label", "Private key", "help", "The key.", "tab", "security", "required", false, "directions", List.of("in", "out"),
                        "kinds", List.of("key-pair", "value")),
                Map.of("name", "afterSuccess", "type", "choice", "label", "After success", "help", "What then.", "tab", "general", "required", false, "directions", List.of("in"),
                        "choices", List.of(Map.of("value", "delete", "label", "Delete"), Map.of("value", "move", "label", "Move")), "default", "delete"),
                Map.of("name", "moveTo", "type", "text", "label", "Move to", "help", "Where to.", "tab", "general", "required", true, "directions", List.of("in"),
                        "visibleWhen", Map.of("field", "afterSuccess", "values", List.of("move"))),
                Map.of("name", "tls", "type", "boolean", "label", "Tls", "help", "Whether TLS is on.", "tab", "general", "required", false, "directions", List.of("out"),
                        "default", false),
                Map.of("name", "ca", "type", "text", "label", "Ca", "help", "The CA.", "tab", "general", "required", false, "directions", List.of("out"),
                        "visibleWhen", Map.of("field", "tls", "values", List.of(true))));
        assertEquals(Map.of("described", true, "tabs", COMMON_ONLY, "fields", fields,
                "rules", List.of(Map.of("kind", "exactlyOne", "fields", List.of("password", "privateKey")))), s.toJson());
    }

    @Test
    void theJsonFormIsImmutableAndTheSameEveryTime() {
        Settings s = Settings.of(Field.text("host", "Host", "The server."));
        assertEquals(s.toJson(), s.toJson());
        assertThrows(UnsupportedOperationException.class, () -> s.toJson().put("described", false));
    }

    @Test
    void theLastDirectionChosenWins() {
        assertEquals(List.of("out"), ((Map<?, ?>) ((List<?>) Settings.of(Field.text("url", "Url", "u").in().out()).toJson().get("fields")).get(0)).get("directions"));
    }
}
