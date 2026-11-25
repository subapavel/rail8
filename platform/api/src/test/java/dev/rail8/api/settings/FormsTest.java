package dev.rail8.api.settings;

import dev.rail8.api.settings.Forms.Reach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.rail8.api.settings.Direction.IN;
import static dev.rail8.api.settings.Direction.OUT;
import static dev.rail8.api.settings.Forms.Reach.CONNECTION;
import static dev.rail8.api.settings.Forms.Reach.NO_CONNECTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of an adapter's two forms: each test builds one adapter's forms that break one rule, and the refusal names the
 * field or the direction; and the JSON form the admin API serves, with its text keys.
 */
class FormsTest {

    /** A drop-like adapter that polls in from a connection and sends out to one: the forms every case below breaks once. */
    static final Settings CONNECTION_FORM = Settings.of(
            Field.text("url", "URL", "Where the system is.").required(),
            Field.secret("password", "Password", "The login's password.").tab(Tab.SECURITY));
    static final Settings FLOW_FORM = Settings.of(
            Field.text("dir", "Directory", "Where the files are.").in(),
            Field.text("path", "Path", "Where a call goes, below the url.").out().expression(Escape.PATH));
    static final Map<Direction, Reach> BOTH = Map.of(IN, CONNECTION, OUT, CONNECTION);

    static void refused(Executable build, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        assertEquals(expected, e.getMessage());
    }

    @Test
    void theFormsThatBreakNoRuleBuild() {
        Forms forms = assertDoesNotThrow(() -> new Forms(CONNECTION_FORM, FLOW_FORM, BOTH));
        assertTrue(forms.connects(IN));
        assertTrue(forms.serves(OUT));
    }

    @Test
    void anAdapterServesADirection() {
        refused(() -> new Forms(CONNECTION_FORM, FLOW_FORM, Map.of()), "an adapter serves at least one direction");
    }

    @Test
    void aNameIsInOneFormOnly() {
        Settings flow = Settings.of(Field.text("url", "URL", "Again.").out());
        refused(() -> new Forms(CONNECTION_FORM, flow, BOTH), "field 'url': is in both forms");
    }

    @Test
    void aSecretBelongsToTheConnectionForm() {
        Settings flow = Settings.of(Field.secret("token", "Token", "A key.").out());
        refused(() -> new Forms(CONNECTION_FORM, flow, BOTH), "field 'token': a secret belongs to the connection form");
    }

    @Test
    void anExpressionServesTheOutDirectionOfTheFlowFormOnly() {
        Settings both = Settings.of(Field.text("path", "Path", "Where.").expression(Escape.PATH));
        refused(() -> new Forms(CONNECTION_FORM, both, BOTH), "field 'path': an expression serves the out direction only");
        Settings in = Settings.of(Field.text("path", "Path", "Where.").in().expression(Escape.PATH));
        refused(() -> new Forms(CONNECTION_FORM, in, BOTH), "field 'path': an expression serves the out direction only");
        Settings connection = Settings.of(Field.text("url", "URL", "Where.").out().expression(Escape.NONE), Field.text("host", "Host", "Who.").in());
        refused(() -> new Forms(connection, Settings.of(), BOTH), "field 'url': an expression belongs to the flow form");
    }

    @Test
    void theNamesThePlatformSetsAreNoField() {
        for (String name : List.of("flow", "maxBodyBytes", "maxReplyBytes", "allowedHosts")) {
            Settings flow = Settings.of(Field.integer(name, "Mine", "An adapter's own.").out());
            refused(() -> new Forms(CONNECTION_FORM, flow, BOTH), "field '" + name + "': the platform sets it");
            Settings connection = Settings.of(Field.text("url", "URL", "Where."), Field.integer(name, "Mine", "An adapter's own.").out());
            refused(() -> new Forms(connection, Settings.of(), BOTH), "field '" + name + "': the platform sets it");
        }
        assertEquals(Set.of("flow", "maxBodyBytes", "maxReplyBytes", "allowedHosts"), Forms.PLATFORM_FIELDS);
    }

    @Test
    void aFieldServesOnlyTheDirectionsTheAdapterServes() {
        refused(() -> new Forms(CONNECTION_FORM, FLOW_FORM, Map.of(IN, CONNECTION)), "field 'url': serves the out direction, which the adapter does not serve");
        Settings in = Settings.of(Field.text("url", "URL", "Where.").in());
        refused(() -> new Forms(in, FLOW_FORM, Map.of(IN, CONNECTION)), "field 'path': serves the out direction, which the adapter does not serve");
        refused(() -> new Forms(Settings.NONE, Settings.of(Field.text("dir", "Directory", "Where.").in()), Map.of(OUT, CONNECTION)),
                "field 'dir': serves the in direction, which the adapter does not serve");
    }

    @Test
    void aDirectionReachesAConnectionExactlyWhenAFieldOfTheConnectionFormServesIt() {
        Settings out = Settings.of(Field.text("url", "URL", "Where.").out());
        refused(() -> new Forms(out, FLOW_FORM, BOTH), "the in direction reaches a connection, but no field of the connection form serves it");
        refused(() -> new Forms(CONNECTION_FORM, FLOW_FORM, Map.of(IN, CONNECTION, OUT, NO_CONNECTION)),
                "the out direction reaches no connection, but a field of the connection form serves it");
        refused(() -> new Forms(Settings.of(), FLOW_FORM, Map.of(IN, NO_CONNECTION, OUT, CONNECTION)),
                "the out direction reaches a connection, but no field of the connection form serves it");
        assertDoesNotThrow(() -> new Forms(out, FLOW_FORM, Map.of(IN, NO_CONNECTION, OUT, CONNECTION)));
        assertDoesNotThrow(() -> new Forms(Settings.of(), FLOW_FORM, Map.of(IN, NO_CONNECTION, OUT, NO_CONNECTION)));
    }

    @Test
    void anUndescribedConnectionFormIsNotJudgedForItsReach() {
        Forms forms = assertDoesNotThrow(() -> new Forms(Settings.NONE, Settings.NONE, Map.of(IN, CONNECTION, OUT, NO_CONNECTION)));
        assertTrue(forms.connects(IN));
        assertFalse(forms.connects(OUT));
        assertTrue(forms.serves(OUT));
    }

    @Test
    void theReachIsCopiedAndImmutable() {
        Map<Direction, Reach> reach = new EnumMap<>(Map.of(IN, CONNECTION));
        Forms forms = new Forms(Settings.NONE, Settings.NONE, reach);
        reach.put(OUT, CONNECTION);
        assertFalse(forms.serves(OUT));
        assertFalse(forms.connects(OUT));
        assertThrows(UnsupportedOperationException.class, () -> forms.reach().put(OUT, CONNECTION));
    }

    @Test
    void theJsonFormHasTheReachBothFormsTheirTextKeysAndTheRulesOfBoth() {
        Tab polling = Tab.of("polling", "Polling");
        Tab files = Tab.of("files", "Files");
        Settings connection = Settings.of(
                        Field.text("host", "Host", "The server.").required().in(),
                        Field.secret("password", "Password", "The password.").tab(Tab.SECURITY).in(),
                        Field.secret("privateKey", "Private key", "The key.").tab(Tab.SECURITY).in(),
                        Field.integer("retries", "Retries", "How often.").tab(files).in())
                .exactlyOne("password", "privateKey");
        Settings flow = Settings.of(
                        Field.choice("afterSuccess", "After success", "What then.", Choice.of("delete", "Delete"), Choice.of("move", "Move"))
                                .tab(polling).in(),
                        Field.text("moveTo", "Move to", "Where to.").visibleWhen("afterSuccess", "move").tab(files).in(),
                        Field.text("path", "Path", "Below.").out().expression(Escape.QUERY),
                        Field.text("a", "A", "One.").out(), Field.text("b", "B", "Two.").out())
                .exactlyOne("a", "b");
        Forms forms = new Forms(connection, flow, Map.of(OUT, NO_CONNECTION, IN, CONNECTION));
        Map<String, Object> json = forms.toJson("drop");

        assertEquals(List.of("format", "reach", "described", "tabs", "connection", "flow", "rules"), List.copyOf(json.keySet()));
        assertEquals(2, json.get("format"));
        assertEquals(Forms.FORMAT, json.get("format"));
        assertEquals(Map.of("in", "connection", "out", "none"), json.get("reach"));
        assertEquals(List.of("in", "out"), List.copyOf(((Map<?, ?>) json.get("reach")).keySet()), "directions in order");
        assertEquals(Map.of("connection", true, "flow", true), json.get("described"));
        assertEquals(List.of(
                Map.of("id", "general", "label", "General", "labelKey", "rail8.tab.general", "common", true),
                Map.of("id", "files", "label", "Files", "labelKey", "drop.tab.files", "common", false),
                Map.of("id", "polling", "label", "Polling", "labelKey", "drop.tab.polling", "common", false),
                Map.of("id", "retry", "label", "Retry", "labelKey", "rail8.tab.retry", "common", true),
                Map.of("id", "recording", "label", "Recording", "labelKey", "rail8.tab.recording", "common", true),
                Map.of("id", "security", "label", "Security", "labelKey", "rail8.tab.security", "common", true)),
                json.get("tabs"), "the connection form's own tabs first, then the flow form's");

        List<?> connectionFields = (List<?>) json.get("connection");
        assertEquals(List.of("host", "password", "privateKey", "retries"), connectionFields.stream().map(f -> ((Map<?, ?>) f).get("name")).toList());
        assertEquals(Map.of("name", "host", "type", "text", "label", "Host", "labelKey", "drop.field.host.label", "help", "The server.",
                "helpKey", "drop.field.host.help", "tab", "general", "required", true, "directions", List.of("in")), connectionFields.get(0));
        assertEquals(List.of("name", "type", "label", "labelKey", "help", "helpKey", "tab", "required", "directions"),
                List.copyOf(((Map<?, ?>) connectionFields.get(0)).keySet()), "each text key follows its text");

        List<?> flowFields = (List<?>) json.get("flow");
        Map<?, ?> afterSuccess = (Map<?, ?>) flowFields.get(0);
        assertEquals("drop.field.afterSuccess.label", afterSuccess.get("labelKey"));
        assertEquals("drop.field.afterSuccess.help", afterSuccess.get("helpKey"));
        assertEquals(List.of(Map.of("value", "delete", "label", "Delete", "labelKey", "drop.field.afterSuccess.choice.delete"),
                Map.of("value", "move", "label", "Move", "labelKey", "drop.field.afterSuccess.choice.move")), afterSuccess.get("choices"));
        assertEquals("query", ((Map<?, ?>) flowFields.get(2)).get("expression"));
        assertEquals(Map.of("field", "afterSuccess", "values", List.of("move")), ((Map<?, ?>) flowFields.get(1)).get("visibleWhen"));

        assertEquals(List.of(Map.of("kind", "exactlyOne", "fields", List.of("password", "privateKey")),
                Map.of("kind", "exactlyOne", "fields", List.of("a", "b"))), json.get("rules"), "the connection form's rules first");
    }

    @Test
    void theJsonFormOfUndescribedFormsSaysSoAndIsImmutable() {
        Map<String, Object> json = new Forms(Settings.NONE, Settings.of(), Map.of(OUT, CONNECTION)).toJson("mock");
        assertEquals(Map.of("out", "connection"), json.get("reach"));
        assertEquals(Map.of("connection", false, "flow", true), json.get("described"));
        assertEquals(List.of(), json.get("connection"));
        assertEquals(List.of(), json.get("flow"));
        assertEquals(List.of(), json.get("rules"));
        assertEquals(json, new Forms(Settings.NONE, Settings.of(), Map.of(OUT, CONNECTION)).toJson("mock"), "the same every time");
        assertThrows(UnsupportedOperationException.class, () -> json.put("format", 1));
        assertThrows(UnsupportedOperationException.class, () -> cast(json.get("reach")).put("in", "none"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
