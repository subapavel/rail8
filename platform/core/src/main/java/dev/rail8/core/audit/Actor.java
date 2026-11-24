package dev.rail8.core.audit;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Who acted, as an audit row records it: {@code actor}, {@code user_id}, {@code via} and {@code credential}, and the client's
 * address in the row's detail. Only the admin API's permission check makes one, after it allowed the act.
 *
 * @param name the user's name; {@code emergency-key} for the emergency key; during the bridge the name the shared token or the
 *             open loopback API declared, {@code unknown} without one. Never blank
 * @param user the user's id; empty for the emergency key and the bridge's callers
 * @param via how the caller proved who it is: {@code session}, {@code token}, {@code emergency-key}, {@code shared-token} or
 *            {@code local}
 * @param credential the id of the caller's session or token, never its secret; empty for the others
 * @param remote the address the act came from
 * @since 0.1
 */
public record Actor(String name, OptionalLong user, String via, Optional<String> credential, String remote) {

    public Actor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(via, "via");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(remote, "remote");
        if (name.isBlank()) throw new IllegalArgumentException("an actor's name is not blank");
    }

    /** The user's id, or null for a caller without an account: as the audit's {@code user_id} takes it. */
    public Long userOrNull() {
        return user.isPresent() ? user.getAsLong() : null;
    }
}
