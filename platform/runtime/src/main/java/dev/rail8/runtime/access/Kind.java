package dev.rail8.runtime.access;

import java.util.Objects;
import java.util.Optional;

/** What kind of account a caller acts through. */
public enum Kind {
    /** A person: a password, sessions, and tokens of their own. */
    PERSON("person"),
    /** A machine, such as {@code ci-deploy}: no password, tokens only. */
    TECHNICAL("technical"),
    /** No account: the emergency key, the shared token or the open loopback API. */
    NONE("none");

    private final String id;

    Kind(String id) {
        this.id = id;
    }

    /** The kind's id, as Postgres stores an account's kind. */
    public String id() {
        return id;
    }

    /** The kind of an account of {@code id} ({@code person} or {@code technical}), or empty for any other id. */
    public static Optional<Kind> ofAccount(String id) {
        Objects.requireNonNull(id, "id");
        return switch (id) {
            case "person" -> Optional.of(PERSON);
            case "technical" -> Optional.of(TECHNICAL);
            default -> Optional.empty();
        };
    }
}
