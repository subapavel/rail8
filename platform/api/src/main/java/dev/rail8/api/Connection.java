package dev.rail8.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One connection as the environment holds it now, for one direction: the values of the fields of the adapter's connection
 * form that serve it, checked against that form, defaults filled in. {@code values} holds no secret: each secret field is
 * a handle in {@code secrets}, read at each use, so a changed secret reaches the adapter at its next login without a new
 * connection. Both maps are immutable copies, and {@link #toString} names the key and the fields, never a value.
 *
 * @param key the connection's key; for an endpoint file, the endpoint's id
 * @param values the value of each field that is set, by field name
 * @param secrets the handle of each secret field that is set, by field name
 * @since 0.4
 */
public record Connection(String key, Map<String, Object> values, Map<String, Secret> secrets) {

    public Connection {
        Objects.requireNonNull(key, "key");
        values = Map.copyOf(values);
        secrets = Map.copyOf(secrets);
    }

    /** The handle of the secret field {@code field}, if it is set. */
    public Optional<Secret> secret(String field) {
        return Optional.ofNullable(secrets.get(field));
    }

    /** The key and the names of the fields set, never a value. */
    @Override
    public String toString() {
        return "Connection[key=" + key + ", values=" + new TreeSet<>(values.keySet()) + ", secrets=" + new TreeSet<>(secrets.keySet()) + "]";
    }
}
