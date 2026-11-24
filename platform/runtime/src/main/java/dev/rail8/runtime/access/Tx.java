package dev.rail8.runtime.access;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /** A token's row; {@code fingerprint} as 64 lowercase hex digits. */
    record TokenRow(String id, long user, String name, String fingerprint, Instant createdAt, String createdBy, Instant expiresAt,
                    Optional<Instant> lastUsedAt, Optional<Instant> revokedAt) {}

    /** A token with its user and the user's roles, as one read finds them. */
    record TokenOfUser(TokenRow token, UserRow user, Set<Role> roles) {}

    /** A device's row; {@code fingerprint} as 64 lowercase hex digits, {@code generation} the user's at its issue. */
    record DeviceRow(String id, long user, String fingerprint, long generation, Instant createdAt, Instant expiresAt) {}

    /** The user a login names and the device it presents, as one read finds them. */
    record LoginRows(Optional<UserRow> user, Optional<DeviceRow> device) {}

    /** A row of the login limit. */
    record Limit(int failures, Instant lastFailureAt, Optional<Instant> auditedAt) {}

    /** An audit row. */
    record AuditRow(Instant at, String actor, String action, String subject, JsonObject detail, OptionalLong user, String via,
                    Optional<String> credential) {}

    /** An audit row as read back, with its id. */
    record AuditedRow(long id, AuditRow row) {}

    /** Whether the bridge's marker is open. */
    boolean bridgeOpen();

    Optional<UserRow> user(String name, Lock lock);

    Optional<UserRow> user(long id, Lock lock);

    /** Every user, by name. */
    List<UserRow> users();

    long userCount();

    /** The roles {@code user} holds, by role. */
    List<Access.Grant> grants(long user);

    /** The roles of every user who holds one, by user id, each user's by role. */
    Map<Long, List<Access.Grant>> grants();

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

    /** The session rows of every user that expire after {@code now} and were last seen at {@code seenSince} or later. */
    List<SessionRow> sessions(Instant now, Instant seenSince);

    void insertSession(SessionRow row);

    /** Every session of {@code user} but {@code except} deleted: their number. */
    int endSessions(long user, Optional<String> except);

    /** The session deleted: whether it was there. */
    boolean endSession(String id);

    /** The session's last request at {@code at}, if later than the one it has. */
    void touch(String id, Instant at);

    /** The session no longer one of a first password. */
    void passwordChanged(String id);

    /** The user named {@code name} and, when given, the device {@code device}, in one statement: both from one snapshot. */
    LoginRows loginRows(String name, Optional<String> device);

    /** Every device row of {@code user}, dead ones included. */
    List<DeviceRow> devices(long user);

    void insertDevice(DeviceRow row);

    /** The device deleted. */
    void endDevice(String id);

    Optional<TokenOfUser> token(String id);

    /** The unrevoked tokens of {@code user}, expired ones included. */
    List<TokenRow> unrevokedTokens(long user);

    /**
     * The unrevoked tokens that expire after {@code now} and, when given, at {@code until} at the latest: of {@code user}, or of
     * every user when empty; soonest expiry first.
     */
    List<Access.Token> liveTokens(OptionalLong user, Instant now, Optional<Instant> until);

    void insertToken(TokenRow row);

    /** The token revoked at {@code at} by {@code by}, unless it already was: whether it was revoked now. */
    boolean revokeToken(String id, Instant at, String by);

    /** The token's last use at {@code at}, when it has none or its last was at {@code before} at the latest. */
    void used(String id, Instant at, Instant before);

    /** The installation's ceiling of a token's life, in days, its row read with {@code lock}. */
    int ceiling(Lock lock);

    void setCeiling(int maxDays);

    /**
     * Every unrevoked token that expires later than its issue plus {@code maxDays} made to expire then, expired already or not:
     * their number.
     */
    int shortenTokens(int maxDays);

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

    /** The row {@code (name, device)} of the login limit deleted. */
    void forgetLimit(String name, String device);

    void limitAudited(String name, String device, Instant at);

    /** One more refused login's audit row counted in the hour starting {@code hour}: the count with it. */
    int countRefusalAudit(Instant hour);

    void audit(AuditRow row);

    /**
     * The audit rows of {@code subject}, newest first: by {@code at}, then by id, both descending; at most {@code limit}. With
     * {@code before}, only those after the row of that id in this order, none when that row is gone.
     */
    List<AuditedRow> audit(String subject, OptionalLong before, int limit);
}
