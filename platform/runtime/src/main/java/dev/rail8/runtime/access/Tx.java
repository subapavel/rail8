package dev.rail8.runtime.access;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The store of {@link Accounts}: the statements of one transaction, or of none. Every instant comes from the node's clock;
 * the store never reads its own.
 */
interface Tx {

    /** How a user's row is read. */
    enum Lock { NONE, SHARE, UPDATE }

    /** A user's row. */
    record UserRow(long id, String name, Kind kind, Optional<String> password, boolean firstPassword, Optional<Instant> passwordSetAt,
                   long generation, Instant createdAt, String createdBy, Optional<Instant> blockedAt) {}

    /** A session's row; {@code fingerprint} as 64 lowercase hex digits. */
    record SessionRow(String id, long user, String fingerprint, boolean firstPassword, Instant createdAt, Instant lastSeenAt,
                      Instant expiresAt) {}

    /** A session with its user and the user's roles, as one read finds them. */
    record SessionOfUser(SessionRow session, UserRow user, Set<Role> roles) {}

    /** A row of the login limit. */
    record Limit(int failures, Instant lastFailureAt, Optional<Instant> auditedAt) {}

    /** An audit row. */
    record AuditRow(Instant at, String actor, String action, String subject, JsonObject detail, OptionalLong user, String via,
                    Optional<String> credential) {}

    /** Whether the bridge's marker is open. */
    boolean bridgeOpen();

    Optional<UserRow> user(String name, Lock lock);

    Optional<UserRow> user(long id, Lock lock);

    /** Every user, by name. */
    List<UserRow> users();

    long userCount();

    /** The roles {@code user} holds, by role. */
    List<Access.Grant> grants(long user);

    /** The number of unblocked persons who hold the administrator role. */
    long unblockedAdministrators();

    /** The pool's lock of the changes that can lower the number of administrators, held to the transaction's end. */
    void lockAdministrators();

    /** A new user's id; empty when the name is taken. */
    OptionalLong insertUser(String name, Kind kind, Optional<String> password, boolean firstPassword, Optional<Instant> passwordSetAt,
                            Instant createdAt, String createdBy);

    void grant(long user, Role role, Instant at, String by, String via);

    void revoke(long user, Role role);

    /** The password replaced, its first-password flag set, its time {@code at}, the generation raised. */
    void password(long user, String stored, boolean first, Instant at);

    /** The stored hash replaced by one with the current parameters, only while it is still {@code old}. */
    void rehash(long user, String old, String stored);

    /** Blocked at {@code at} (the generation raised), or unblocked when empty. */
    void blocked(long user, Optional<Instant> at);

    void raiseGeneration(long user);

    Optional<SessionOfUser> session(String id);

    /** Every session row of {@code user}, ended ones included. */
    List<SessionRow> sessions(long user);

    void insertSession(SessionRow row);

    /** Every session of {@code user} but {@code except} deleted: their number. */
    int endSessions(long user, Optional<String> except);

    /** The session deleted: whether it was there. */
    boolean endSession(String id);

    /** The session's last request at {@code at}, if later than the one it has. */
    void touch(String id, Instant at);

    /** The session no longer one of a first password. */
    void passwordChanged(String id);

    /**
     * One attempt claimed on the row {@code (name, device)}: its failures with this one, or empty when the row is waiting. The
     * first 5 failures are free; after them each attempt waits 1, 2, 4 and so on, up to 300 seconds, after the last failure.
     */
    OptionalInt claim(String name, String device, Instant at);

    Optional<Limit> limit(String name, String device, boolean forUpdate);

    /** The failures up to a success's own claim forgiven: {@code failures = greatest(failures - claimed, 0)}. */
    void forgive(String name, String device, int claimed);

    /** The number of names the login limit counts. */
    long limitedNames();

    /** Every row of {@code name} deleted. */
    void forgetLimits(String name);

    void limitAudited(String name, String device, Instant at);

    /** One more refused login's audit row counted in the hour starting {@code hour}: the count with it. */
    int countRefusalAudit(Instant hour);

    void audit(AuditRow row);
}
