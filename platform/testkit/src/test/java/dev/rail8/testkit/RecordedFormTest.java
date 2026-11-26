package dev.rail8.testkit;

import dev.rail8.api.Secret;
import dev.rail8.api.settings.Choice;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check that a connection form only adds to the one recorded: each fake adapter below breaks one rule against the
 * recorded form of {@code drop}, and the check names the field and the rule; a form that only adds passes.
 */
class RecordedFormTest {

    static final Map<Direction, Forms.Reach> REACH = Map.of(Direction.IN, Forms.Reach.CONNECTION);

    /** The fields of the recorded form, by name, so that each case below changes one. */
    static Map<String, Field> fields() {
        Map<String, Field> m = new LinkedHashMap<>();
        m.put("host", Field.text("host", "Host", "The server.").required().in().server());
        m.put("port", Field.integer("port", "Port", "The port.").range(1, 65535).byDefault(22).in().server());
        m.put("mode", Field.choice("mode", "Mode", "How.", Choice.of("binary", "Binary"), Choice.of("ascii", "ASCII")).byDefault("binary").in());
        m.put("user", Field.text("user", "User", "Who.").pattern("[a-z]+").in());
        m.put("password", Field.secret("password", "Password", "The password.", Secret.Kind.VALUE).in());
        return m;
    }

    /**
     * The connection form recorded in {@code forms/partner.json} for the contract self-tests: a host and a port naming the
     * server, and the secrets their connections hold, {@code password}, {@code login} and {@code key}, each of one kind; its
     * fields serve the in direction only when {@code inOnly}, else both, and the port's default is {@code port}.
     */
    static Settings partner(boolean inOnly, long port) {
        java.util.function.UnaryOperator<Field> served = f -> inOnly ? f.in() : f;
        return Settings.of(
                served.apply(Field.text("host", "Host", "The server.").server()),
                served.apply(Field.integer("port", "Port", "The port.").byDefault(port).server()),
                served.apply(Field.secret("password", "Password", "The password.", Secret.Kind.VALUE)),
                served.apply(Field.secret("login", "Login", "The credentials.", Secret.Kind.CREDENTIALS)),
                served.apply(Field.secret("key", "Key", "The key pair.", Secret.Kind.KEY_PAIR)));
    }

    static JsonObject json(Map<String, Field> fields) {
        Forms forms = new Forms(Settings.of(fields.values().toArray(Field[]::new)), Settings.NONE, REACH);
        return new JsonObject(new JsonObject(forms.toJson("drop")).encode());
    }

    static final JsonObject RECORDED = json(fields());

    static List<String> breaks(String field, Field changed) {
        Map<String, Field> m = fields();
        if (changed == null) m.remove(field);
        else m.put(field, changed);
        return RecordedForm.breaks(RECORDED, json(m));
    }

    @Test
    void theRecordedFormAndOneThatOnlyAddsPass() {
        assertEquals(List.of(), RecordedForm.breaks(RECORDED, RECORDED));
        assertEquals(List.of(), breaks("timeoutMs", Field.integer("timeoutMs", "Timeout", "How long.").byDefault(1000).in()), "a new field with a default");
        assertEquals(List.of(), breaks("proxy", Field.text("proxy", "Proxy", "Through which.").in()), "a new optional field");
        assertEquals(List.of(), breaks("port", Field.integer("port", "Port", "The port.").range(0, 70000).byDefault(22).in().server()), "a wider range");
        assertEquals(List.of(), breaks("user", Field.text("user", "User", "Someone else's words.").in()), "a pattern removed, a help text changed");
        assertEquals(List.of(), breaks("mode", Field.choice("mode", "Mode", "How.", Choice.of("binary", "Binary"), Choice.of("ascii", "ASCII"),
                Choice.of("auto", "Auto")).byDefault("binary").in()), "a choice added");
        assertEquals(List.of(), breaks("host", Field.text("host", "Host", "The server.").in().server()), "a field no longer required");
    }

    @Test
    void eachRuleBrokenIsNamedWithItsField() {
        assertEquals(List.of("field 'user': is gone; a field is never removed or renamed"), breaks("user", null));
        assertEquals(List.of("field 'user': has another type, integer for text"), breaks("user", Field.integer("user", "User", "Who.").in()));
        assertEquals(List.of("field 'port': lost server()"), breaks("port", Field.integer("port", "Port", "The port.").range(1, 65535).byDefault(22).in()));
        assertEquals(List.of("field 'user': gained server()"), breaks("user", Field.text("user", "User", "Who.").pattern("[a-z]+").in().server()));
        assertEquals(List.of("field 'port': its default changed"), breaks("port", Field.integer("port", "Port", "The port.").range(1, 65535).byDefault(2222).in().server()));
        assertEquals(List.of("field 'user': gained a default"), breaks("user", Field.text("user", "User", "Who.").pattern("[a-z]+").byDefault("root").in()));
        assertEquals(List.of("field 'port': lost its default"), breaks("port", Field.integer("port", "Port", "The port.").range(1, 65535).in().server()));
        assertEquals(List.of("field 'user': became required"), breaks("user", Field.text("user", "User", "Who.").pattern("[a-z]+").required().in()));
        assertEquals(List.of("field 'port': its range is narrower, min 2"), breaks("port", Field.integer("port", "Port", "The port.").range(2, 65535).byDefault(22).in().server()));
        assertEquals(List.of("field 'port': its range is narrower, max 1024"), breaks("port", Field.integer("port", "Port", "The port.").range(1, 1024).byDefault(22).in().server()));
        assertEquals(List.of("field 'user': its pattern is another, which may be narrower"), breaks("user", Field.text("user", "User", "Who.").pattern("[a-z]{1,8}").in()));
        assertEquals(List.of("field 'mode': the choice 'ascii' is gone"), breaks("mode", Field.choice("mode", "Mode", "How.", Choice.of("binary", "Binary")).byDefault("binary").in()));
        assertEquals(List.of("field 'dir': is new and required; a new field is optional or has a default"), breaks("dir", Field.text("dir", "Directory", "Where.").required().in()));
    }

    @Test
    void aFormThatDescribesItsFieldsWhereTheRecordedOneTookEveryValueBreaksTheRule() {
        JsonObject undescribed = new JsonObject(new JsonObject(new Forms(Settings.NONE, Settings.NONE, REACH).toJson("drop")).encode());
        assertEquals(List.of("the connection form describes its fields, but the recorded one described none and took every value"),
                RecordedForm.breaks(undescribed, RECORDED));
    }

    @Test
    void aDescribedFormNeedsItsRecordedFileAndAnUndescribedOneNone() {
        Forms described = new Forms(Settings.of(fields().values().toArray(Field[]::new)), Settings.NONE, REACH);
        AssertionFailedError missing = assertThrows(AssertionFailedError.class, () -> RecordedForm.assertOnlyAdds(getClass(), "nothing", described));
        assertTrue(missing.getMessage().startsWith("no recorded form: write src/test/resources/forms/nothing.json"), missing.getMessage());
        assertTrue(missing.getMessage().contains("\"connection\""), "it shows what to write: " + missing.getMessage());
        assertDoesNotThrow(() -> RecordedForm.assertOnlyAdds(getClass(), "nothing", new Forms(Settings.NONE, Settings.NONE, REACH)));
        assertDoesNotThrow(() -> RecordedForm.assertOnlyAdds(getClass(), "drop", described), "forms/drop.json of the test resources");
        Map<String, Field> narrowed = fields();
        narrowed.remove("mode");
        Forms broken = new Forms(Settings.of(narrowed.values().toArray(Field[]::new)), Settings.NONE, REACH);
        AssertionFailedError gone = assertThrows(AssertionFailedError.class, () -> RecordedForm.assertOnlyAdds(getClass(), "drop", broken));
        assertTrue(gone.getMessage().contains("field 'mode': is gone"), gone.getMessage());
    }
}
