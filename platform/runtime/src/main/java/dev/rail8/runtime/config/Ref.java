package dev.rail8.runtime.config;

import dev.rail8.core.key.Keys;

import java.util.Locale;
import java.util.Objects;

/**
 * An object of the configuration: its kind and its key, read through the one parser of keys.
 *
 * @param kind a connection or a system
 * @param key  a connection key of two or three segments, or a system key of one or two
 * @since 0.4
 */
public record Ref(Kind kind, String key) {

    /** The kinds of objects the store holds. */
    public enum Kind {
        CONNECTION, SYSTEM;

        /** The kind as the tables, the audit and the answers name it: {@code connection} or {@code system}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The kind {@code id} names. */
        static Kind of(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    /** @throws IllegalArgumentException naming the key and the rule of keys it breaks */
    public Ref {
        Objects.requireNonNull(kind, "kind");
        key = kind == Kind.CONNECTION ? Keys.connection(key) : Keys.system(key);
    }

    /** The connection {@code key}. */
    public static Ref connection(String key) {
        return new Ref(Kind.CONNECTION, key);
    }

    /** The system {@code key}. */
    public static Ref system(String key) {
        return new Ref(Kind.SYSTEM, key);
    }

    /** The subject of the object's audit rows: {@code config/<kind>/<key>}. */
    String subject() {
        return "config/" + kind.id() + "/" + key;
    }

    @Override
    public String toString() {
        return kind.id() + " " + key;
    }
}
