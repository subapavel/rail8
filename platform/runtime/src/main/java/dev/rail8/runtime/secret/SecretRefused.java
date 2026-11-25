package dev.rail8.runtime.secret;

import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * A refused write or read of the secret store: its HTTP status, its stable code, an English text and the values the text names,
 * which the UI translates by the code. No refusal holds a secret's value.
 */
public final class SecretRefused extends Exception {

    private final int status;
    private final String code;
    private final JsonObject values;

    SecretRefused(int status, String code, String text, JsonObject values) {
        super(text, null, false, false);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
        this.values = values.copy();
    }

    SecretRefused(int status, String code, String text) {
        this(status, code, text, new JsonObject());
    }

    /** The HTTP status of the answer. */
    public int status() {
        return status;
    }

    /** The stable code, such as {@code exists} or {@code keys-differ}. */
    public String code() {
        return code;
    }

    /** The values the text names. */
    public JsonObject values() {
        return values.copy();
    }

    /** The answer's body: {@code {"error", "code", "values"}}. */
    public JsonObject body() {
        return new JsonObject().put("error", getMessage()).put("code", code).put("values", values.copy());
    }

    /** A refusal of {@code field} with 422 {@code invalid}: it names the field and never echoes the value. */
    static SecretRefused invalid(String field, String text) {
        return new SecretRefused(422, "invalid", field + ": " + text, new JsonObject().put("field", field));
    }
}
