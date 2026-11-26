package dev.rail8.testkit;

import dev.rail8.api.settings.Forms;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The rule that an adapter's connection form only adds: stored bodies, exports and consents outlive a release, so a
 * connection field keeps its name, its type, its mark {@code server()}, its default and its range, never becomes
 * required, and a new field is optional. The form is checked against the one recorded in the adapter's test resources,
 * {@code forms/<scheme>.json}: the description {@link Forms#toJson} gave when the file was written. Only a new adapter,
 * which is a new scheme, writes the file again.
 *
 * <p>A connection form that describes nothing ({@link dev.rail8.api.settings.Settings#NONE}) takes every value, so it
 * narrows nothing and needs no recorded form; the platform's mock of a scheme is one.
 */
final class RecordedForm {

    private RecordedForm() {}

    /** Where the recorded form of {@code scheme} is, on the test's class path and under {@code src/test/resources}. */
    static String path(String scheme) {
        return "forms/" + scheme + ".json";
    }

    /**
     * Requires the connection form of {@code forms} to only add to the form of {@code scheme} recorded on the class path of
     * {@code test}; fails naming each field and the rule it breaks, or, with no file recorded, the file to write and what to
     * write in it.
     */
    static void assertOnlyAdds(Class<?> test, String scheme, Forms forms) {
        if (!forms.connection().described()) return;
        JsonObject current = new JsonObject(new JsonObject(forms.toJson(scheme)).encode());
        String path = path(scheme);
        JsonObject recorded;
        try (InputStream in = test.getResourceAsStream("/" + path)) {
            if (in == null) {
                fail("no recorded form: write src/test/resources/" + path + " with the description the adapter serves, once, for a new "
                        + "adapter:\n" + current.encodePrettily());
                return;
            }
            recorded = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | DecodeException e) {
            fail("the recorded form " + path + " cannot be read: " + e.getMessage());
            return;
        }
        assertEquals(List.of(), breaks(recorded, current), "the connection form only adds to the one recorded in " + path);
    }

    /**
     * Each way the connection form of {@code current} breaks the rule against {@code recorded}, both descriptions as
     * {@link Forms#toJson} gives them: a field gone, of another type, with {@code server} gained or lost, with its default
     * changed, gained or lost, become required, with a narrower range, another pattern or a choice gone; a new field that is
     * required; a connection form that describes its fields where the recorded one took every value. Empty when it only adds.
     */
    static List<String> breaks(JsonObject recorded, JsonObject current) {
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(current, "current");
        List<String> broken = new ArrayList<>();
        JsonObject described = recorded.getJsonObject("described", new JsonObject());
        if (!described.getBoolean("connection", true)) {
            broken.add("the connection form describes its fields, but the recorded one described none and took every value");
            return broken;
        }
        Map<String, JsonObject> was = byName(recorded.getJsonArray("connection", new JsonArray()));
        Map<String, JsonObject> now = byName(current.getJsonArray("connection", new JsonArray()));
        for (Map.Entry<String, JsonObject> e : was.entrySet()) {
            String name = e.getKey();
            JsonObject before = e.getValue();
            JsonObject after = now.get(name);
            if (after == null) {
                broken.add("field '" + name + "': is gone; a field is never removed or renamed");
                continue;
            }
            if (!Objects.equals(before.getString("type"), after.getString("type"))) {
                broken.add("field '" + name + "': has another type, " + after.getString("type") + " for " + before.getString("type"));
            }
            boolean serverBefore = before.getBoolean("server", false);
            boolean serverAfter = after.getBoolean("server", false);
            if (serverBefore != serverAfter) {
                broken.add("field '" + name + "': " + (serverAfter ? "gained" : "lost") + " server()");
            }
            Object defaultBefore = before.getValue("default");
            Object defaultAfter = after.getValue("default");
            if (defaultBefore == null && defaultAfter != null) broken.add("field '" + name + "': gained a default");
            else if (defaultBefore != null && defaultAfter == null) broken.add("field '" + name + "': lost its default");
            else if (defaultBefore != null && !defaultBefore.equals(defaultAfter)) broken.add("field '" + name + "': its default changed");
            if (!before.getBoolean("required", false) && after.getBoolean("required", false)) {
                broken.add("field '" + name + "': became required");
            }
            Long minBefore = before.getLong("min");
            Long minAfter = after.getLong("min");
            if (minAfter != null && (minBefore == null || minAfter > minBefore)) broken.add("field '" + name + "': its range is narrower, min " + minAfter);
            Long maxBefore = before.getLong("max");
            Long maxAfter = after.getLong("max");
            if (maxAfter != null && (maxBefore == null || maxAfter < maxBefore)) broken.add("field '" + name + "': its range is narrower, max " + maxAfter);
            String patternAfter = after.getString("pattern");
            if (patternAfter != null && !patternAfter.equals(before.getString("pattern"))) {
                broken.add("field '" + name + "': its pattern is another, which may be narrower");
            }
            List<Object> choicesAfter = values(after.getJsonArray("choices", new JsonArray()));
            for (Object choice : values(before.getJsonArray("choices", new JsonArray()))) {
                if (!choicesAfter.contains(choice)) broken.add("field '" + name + "': the choice '" + choice + "' is gone");
            }
        }
        for (Map.Entry<String, JsonObject> e : now.entrySet()) {
            if (!was.containsKey(e.getKey()) && e.getValue().getBoolean("required", false)) {
                broken.add("field '" + e.getKey() + "': is new and required; a new field is optional or has a default");
            }
        }
        return broken;
    }

    private static Map<String, JsonObject> byName(JsonArray fields) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonObject f = fields.getJsonObject(i);
            m.put(f.getString("name"), f);
        }
        return m;
    }

    private static List<Object> values(JsonArray choices) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) values.add(choices.getJsonObject(i).getValue("value"));
        return values;
    }
}
