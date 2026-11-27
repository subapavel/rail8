package dev.rail8.runtime.config;

import dev.rail8.core.key.Keys;

import java.util.Locale;
import java.util.Objects;

/**
 * An object of the configuration: its kind and its key, read through the one parser of keys.
 *
 * @param kind a connection, a system or a credential
 * @param key  a connection or credential key of two or three segments, or a system key of one or two
 * @since 0.4
 */
public record Ref(Kind kind, String key) {

    /** The kinds of objects the store holds. */
    public enum Kind {
        CONNECTION, SYSTEM, CREDENTIAL;

        /** The kind as the tables, the audit and the answers name it: {@code connection}, {@code system} or {@code credential}. */
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
        key = switch (kind) {
            case CONNECTION -> Keys.connection(key);
            case SYSTEM -> Keys.system(key);
            case CREDENTIAL -> Keys.credential(key);
        };
    }

    /** The connection {@code key}. */
    public static Ref connection(String key) {
        return new Ref(Kind.CONNECTION, key);
    }

    /** The system {@code key}. */
    public static Ref system(String key) {
        return new Ref(Kind.SYSTEM, key);
    }

    /** The credential {@code key}. */
    public static Ref credential(String key) {
        return new Ref(Kind.CREDENTIAL, key);
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
