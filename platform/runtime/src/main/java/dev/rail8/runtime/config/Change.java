package dev.rail8.runtime.config;

import java.util.Map;
import java.util.Optional;

/**
 * A write of one object of the configuration, or the switch of mocks. Every write but a stop, a start and the switch of mocks
 * states the {@code etag} its author read as {@code expect}; a stop and a start state the {@code run} token; the switch of mocks
 * states the state its author read. A write whose {@code expect} differs is 412 {@code changed}.
 *
 * @since 0.4
 */
public sealed interface Change {

    /**
     * A draft of a connection's values. {@code values} are the fields of the adapter's connection form, a secret field as
     * {@code secret://<name>}, and the platform's {@code allowedHosts}, a list of hosts; while {@code mock} is present the
     * connection is mocked and its values are kept.
     */
    record Save(String expect, String adapter, Map<String, Object> values, Optional<Mock> mock) implements Change {
        public Save {
            values = values == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }
    }

    /**
     * The platform's built-in mock: {@code reply} is {@code echo} or {@code empty}; {@code delay}, as {@code <n>ms} or
     * {@code <n>s}, is at most 60 s; {@code fail} is {@code retryable} or {@code invalid}.
     */
    record Mock(String reply, Optional<String> delay, Optional<String> fail) { }

    /**
     * A draft of a credential: {@code fields} are its {@code type} and that type's fields, {@code basic} a {@code user} and a
     * {@code password}, {@code api-key} an {@code apiKey}, each secret as {@code secret://<name>} of a verifier.
     */
    record SaveCredential(String expect, Map<String, Object> fields) implements Change {
        public SaveCredential {
            fields = fields == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }
    }

    /** A draft that removes the object: {@code {removed: true}}, activated like any other change. */
    record Remove(String expect) implements Change { }

    /** A draft that copies version {@code fromVersion}, kept in the object's history, checked as any save. */
    record GoBack(String expect, int fromVersion) implements Change { }

    /** A draft of a system's instance name, 1 to 128 printable characters. */
    record SaveSystem(String expect, String instanceName) implements Change { }

    /** Drops the draft and the consent to it. */
    record Discard(String expect) implements Change { }

    /** The secret administrator's consent to the pairs of the draft whose digest is {@code digest}; empty withdraws it. */
    record Consent(String expect, Optional<String> digest) implements Change { }

    /**
     * Puts the draft in force. {@code confirm} is the digest of the uses the change would leave unserved, as the caller saw
     * them; {@code note}, at most 200 characters, is kept in the history and the audit.
     */
    record Activate(String expect, Optional<String> confirm, Optional<String> note) implements Change { }

    /** Stops a connection in force: every attempt that starts after it fails, naming the connection. */
    record Stop(String run) implements Change { }

    /** Starts a stopped connection again. */
    record Start(String run) implements Change { }

    /**
     * The switch of mocks: {@code expect} is whether the caller saw mocks allowed; turning them off while a used connection is
     * mocked in force needs {@code confirm}, the digest of those uses.
     */
    record Mocks(boolean expect, boolean allowed, Optional<String> confirm) implements Change { }
}
