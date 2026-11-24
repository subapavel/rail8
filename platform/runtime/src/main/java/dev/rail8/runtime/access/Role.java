package dev.rail8.runtime.access;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** The five fixed roles. A user may hold several and they add up; no role implies another. Stored and answered by id. */
public enum Role {
    /** Users, their roles, technical accounts and their tokens, sessions, the audit. */
    ADMINISTRATOR("administrator"),
    /** Deploys and undeploys, sees the deployed configuration, calls the modules' services. */
    DEVELOPER("developer"),
    /** Monitoring, retry, cancel, release and discard. */
    OPERATOR("operator"),
    /** Sees and downloads a message's body after each step. */
    MESSAGE_BODIES("message-bodies"),
    /** Creates, overwrites and deletes secrets. */
    SECRET_ADMINISTRATOR("secret-administrator");

    private final String id;

    Role(String id) {
        this.id = id;
    }

    /** The role's id, as Postgres stores it and the admin API answers it. */
    public String id() {
        return id;
    }

    /** The role of {@code id}, or empty for an id that names none. */
    public static Optional<Role> byId(String id) {
        Objects.requireNonNull(id, "id");
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }
}
