package dev.rail8.runtime.config;

import dev.rail8.runtime.refusal.RefusalCode;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.Optional;

/**
 * A refused read or write of the configuration: its HTTP status, its stable code, an English text, the flat values the text
 * names, which the UI translates by the code, the detail a caller acts on (the lines of {@code incomplete}, the pairs of
 * {@code consent-needed}, the uses and their digest of {@code in-use}), and the object as it is now when the refusal answers
 * with it. No refusal holds a secret's value.
 *
 * @since 0.4
 */
public final class ConfigRefusal extends RuntimeException {

    private final int status;
    private final RefusalCode code;
    private final JsonObject values;
    private final JsonObject detail;
    private final transient ConfigStore.ObjectView object;

    ConfigRefusal(int status, RefusalCode code, String text, JsonObject values, JsonObject detail, ConfigStore.ObjectView object) {
        super(text, null, false, false);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
        this.values = values.copy();
        this.detail = detail.copy();
        this.object = object;
    }

    ConfigRefusal(int status, RefusalCode code, String text, JsonObject values) {
        this(status, code, text, values, new JsonObject(), null);
    }

    /** The HTTP status of the answer. */
    public int status() {
        return status;
    }

    /** The stable code, such as {@code changed} or {@code consent-needed}. */
    public String code() {
        return code.code();
    }

    /** The values the text names. */
    public JsonObject values() {
        return values.copy();
    }

    /** What the caller acts on: lines, pairs, uses and digests; empty when the refusal has none. */
    public JsonObject detail() {
        return detail.copy();
    }

    /** The object as it is now, when the refusal answers with it: a 412 and a 404 do. */
    public Optional<ConfigStore.ObjectView> object() {
        return Optional.ofNullable(object);
    }

    /** The answer's body: {@code {"error", "code", "values"}}, the detail's fields, and {@code object} when there is one. */
    public JsonObject body() {
        JsonObject body = new JsonObject().put("error", getMessage()).put("code", code.code()).put("values", values.copy());
        detail.forEach(e -> body.put(e.getKey(), e.getValue()));
        if (object != null) body.put("object", object.json());
        return body;
    }
}
