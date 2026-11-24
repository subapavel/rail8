package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The users of the admin API, their sessions and tokens, and the acts on them. Every method blocks: call it on a virtual thread. A
 * refusal is an {@link AccessRefused}; every act that changes something writes its audit row in the same transaction, with
 * the {@link Actor} that {@link Caller#acting} gave the route.
 */
public interface Access {

    /** The longest name, in characters, a caller of the bridge may declare in {@code X-Rail8-Actor}. */
    int ACTOR_MAX = 128;

    /**
     * The caller of one admin request, from its {@code Authorization} header, read from the store with nothing cached: a session,
     * the emergency key, or while the bridge is open the shared token and, on a node without a token, a request without a
     * credential. {@code declaredActor} is the {@code X-Rail8-Actor} header, which only the bridge's callers are named by. A
     * token's {@code last_used_at} is written when it is empty or an hour old. Each node judges an expiry by its own clock, so a
     * node behind takes a token for as long as its clock lags. A token answers {@code token-expired} until retention deletes
     * its row, {@code RAIL8_AUDIT_MAX_AGE} after its stored expiry, and {@code unknown-credential} after.
     *
     * @throws AccessRefused 401 {@code no-credential}, {@code unknown-credential}, {@code session-ended}, {@code token-revoked},
     *                       {@code token-expired} or {@code blocked}; 400
     *                       for a caller of the bridge whose declared name is above {@link #ACTOR_MAX} characters
     */
    Caller authenticate(Optional<String> authorization, Optional<String> declaredActor);

    /**
     * Whether {@code authorization} presents a session's credential, {@code Bearer rail8s_<id>_<secret>}, by its form alone, read
     * as {@link #authenticate} reads it: nothing is read from the store.
     */
    static boolean presentsSession(Optional<String> authorization) {
        return authorization.flatMap(Credentials::presented).flatMap(Credentials::sessionId).isPresent();
    }

    /** Whether {@code authorization} presents a named token, {@code Bearer rail8t_<id>_<secret>}, by its form alone. */
    static boolean presentsToken(Optional<String> authorization) {
        return authorization.flatMap(Credentials::presented).flatMap(Credentials::tokenId).isPresent();
    }

    /**
     * {@code path} with the secret of every credential of Rail8's forms in it replaced by {@code ***}, a session's and a
     * token's id kept: a path is logged and audited as it came, and a credential pasted into it must not be.
     */
    static String redacted(String path) {
        return Credentials.redacted(path);
    }

    /**
     * A login: a new session of {@code name} when {@code password} is theirs. The password decides alone; a device only picks
     * the row of the login limit and the place to hash. A login that presents a valid device of the user is counted on that
     * device's row and may take the node's second place to hash; any other is counted on the name's row and takes only the
     * shared place. A full login that asks for a device, past a first password, is issued a new one, valid 90 days, and the
     * device it presented ends; a user holds at most 10, the oldest ending first.
     *
     * @param device empty: the login asks for no device (the command line's). Present: it asks, and the value is the device
     *               credential the browser holds, {@code ""} for none (the UI server's). A value that is not a valid device of
     *               the user counts as none, silently.
     * @param remote the address the login came from, for the audit
     * @throws AccessRefused 401 {@code login-refused} with one text for every reason, 429 {@code too-many-attempts} with the
     *                       {@code Retry-After} of the row it was counted on, 503 {@code busy}
     */
    Login login(String name, String password, Optional<String> device, String remote);

    /**
     * {@code by}'s own password changed from {@code current} to {@code next}: the same session goes on without a first
     * password's restriction, every other session of the user ends and the generation is raised.
     *
     * @throws AccessRefused 400 for a password the rules refuse, 403 {@code wrong-password}, 429, 503 {@code busy}
     */
    void changePassword(Actor by, String current, String next);

    /** {@code by}'s session ends. */
    void logout(Actor by);

    /** When the session or the token of {@code who} ends at the latest; empty for any other caller. */
    Optional<Instant> expiresAt(Caller who);

    /** Every user, by name, with their roles, block, live sessions and live tokens. */
    List<User> users();

    /** The user of {@code name}. */
    Optional<User> user(String name);

    /**
     * A new user with {@code roles}: for a person, the first password, shown once.
     *
     * @throws AccessRefused 400 for a bad name or kind, 409 for a name taken, a technical administrator or one user too many
     */
    Optional<String> createUser(Actor by, String name, Kind kind, Set<Role> roles);

    /**
     * {@code name}'s roles changed by difference, under the lock of the user's row: {@code add} the roles to give,
     * {@code remove} those to take.
     *
     * @throws AccessRefused 400 for a role in both, 404, 409 for a technical administrator or the last administrator removed
     */
    RolesChanged changeRoles(Actor by, String name, Set<Role> add, Set<Role> remove);

    /**
     * {@code name} blocked: every session ends in the same transaction.
     *
     * @throws AccessRefused 404, 409 for oneself or the last administrator
     */
    void block(Actor by, String name);

    /** {@code name} unblocked. */
    void unblock(Actor by, String name);

    /**
     * {@code name}'s password replaced by a first password, shown once: every session ends and the login limit's rows of the
     * name are deleted.
     *
     * @throws AccessRefused 404, 409 for a technical account, 503 {@code busy}
     */
    String resetPassword(Actor by, String name);

    /** Every session of {@code name} ends. */
    void endSessions(Actor by, String name);

    /**
     * A token of {@code by}'s own, issued in {@code by}'s session with {@code password} asked again and counted on the name's
     * row of the login limit. It lives {@code days}, or {@link Ceiling#defaultDays} when left out. A token of the user's that
     * has the same name and has expired is revoked in the same transaction.
     *
     * @throws AccessRefused 400 for a name not of the form or days outside 1 to the ceiling, 401 {@code session-ended}, 403
     *                       {@code wrong-password}, 409 for a live token of the name, the 20 live tokens the user holds or an
     *                       account changed while the password was checked, 429, 503 {@code busy}
     */
    Issued issueOwnToken(Actor by, String password, String name, OptionalInt days);

    /**
     * A token of the technical account {@code user}, as {@link #issueOwnToken} without the password.
     *
     * @throws AccessRefused 400, 404 for no such user, 409 for a person, a blocked account, a live token of the name or the 20
     *                       live tokens the account holds
     */
    Issued issueToken(Actor by, String user, String name, OptionalInt days);

    /** The live tokens of {@code who}, a user, soonest expiry first, and the ceiling. */
    Tokens ownTokens(Caller who);

    /**
     * The live tokens of every user, soonest expiry first, and the ceiling; with {@code expiringWithin}, only those that expire
     * within it. The ceiling is read before the tokens, so no token listed lies past the ceiling shown.
     *
     * @throws AccessRefused 400 for an {@code expiringWithin} not above zero or above 365 days
     */
    Tokens tokens(Optional<Duration> expiringWithin);

    /**
     * {@code by}'s own token {@code id} revoked; one already revoked stays as it is.
     *
     * @throws AccessRefused 404 for an id not of the form, unknown, or of another user's token
     */
    void revokeOwnToken(Actor by, String id);

    /**
     * The token {@code id} revoked, whoever holds it; one already revoked stays as it is.
     *
     * @throws AccessRefused 404 for an id not of the form or unknown
     */
    void revokeToken(Actor by, String id);

    /**
     * The installation's ceiling set to {@code maxDays}. Lowering it rewrites every unrevoked token whose expiry lies past its
     * issue plus {@code maxDays} to that, whether or not this node's clock already sees it expired, so a node behind refuses it
     * too; its audit row counts the tokens rewritten. Raising it lengthens none.
     *
     * @throws AccessRefused 400 for {@code maxDays} outside 1 to 365
     */
    Ceiling changeCeiling(Actor by, int maxDays);

    /**
     * The audit row of a use of the emergency key, or of the shared token for one of the key's actions: the method, the path,
     * the remote address, the action asked at the door, and whether it was allowed. Written before anything runs.
     *
     * @throws AccessRefused 503 when the row was not written
     */
    void keyUsed(Caller who, String method, String path, String remote, Optional<Action> action, boolean allowed);

    /**
     * The audit row of a view of the body of {@code message} after {@code step}, written before any byte of it is sent.
     *
     * @throws AccessRefused 503 when the row was not written
     */
    void bodyViewed(Actor by, String message, int step);

    /** Whether the node holds an emergency key. */
    boolean emergencyKey();

    /** An access that authenticates every request as {@code caller}; everything else in memory, for tests of routes. */
    static Access fixed(Caller caller) {
        return MemoryAccess.fixed(caller);
    }

    /**
     * A new session.
     *
     * @param session the credential, shown once
     * @param expiresAt when it ends at the latest
     * @param idleSeconds how long it lasts without a request
     * @param caller who it is
     * @param device the device credential this login issued, shown once and never stored; empty when the login did not ask,
     *               was of a first password, or found that another login had replaced the device it presented
     */
    record Login(String session, Instant expiresAt, long idleSeconds, Caller caller, Optional<String> device) {}

    /**
     * A change of roles.
     *
     * @param name the user's name
     * @param roles the roles the user holds now
     * @param added the roles given that were not held
     * @param removed the roles taken that were held
     */
    record RolesChanged(String name, Set<Role> roles, Set<Role> added, Set<Role> removed) {}

    /**
     * A user as an administrator sees them; never a secret.
     *
     * @param name the name
     * @param kind person or technical
     * @param roles the roles with who granted each
     * @param blockedAt when they were blocked, empty when not
     * @param firstPassword whether their password is a first password
     * @param createdAt when the account was made
     * @param createdBy who made it
     * @param sessions the live sessions
     * @param tokens the live tokens, soonest expiry first
     */
    record User(String name, Kind kind, List<Grant> roles, Optional<Instant> blockedAt, boolean firstPassword, Instant createdAt,
                String createdBy, List<LiveSession> sessions, List<Token> tokens) {}

    /**
     * A role held.
     *
     * @param role the role
     * @param grantedAt when
     * @param grantedBy by whom
     * @param grantedVia how they proved who they were
     */
    record Grant(Role role, Instant grantedAt, String grantedBy, String grantedVia) {}

    /**
     * A live session, by its id.
     *
     * @param id the id, never the secret
     * @param createdAt when it was opened
     * @param lastSeenAt its last request, within a minute
     * @param expiresAt when it ends at the latest
     */
    record LiveSession(String id, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {}

    /**
     * A token just issued.
     *
     * @param id its id
     * @param name its name
     * @param expiresAt when it expires
     * @param token the credential, shown once and never stored
     */
    record Issued(String id, String name, Instant expiresAt, String token) {}

    /**
     * A live token, by its id; never the secret.
     *
     * @param id the id
     * @param user its user's name
     * @param name its name
     * @param createdAt when it was issued
     * @param createdBy who issued it
     * @param expiresAt when it expires, a lowered ceiling counted
     * @param lastUsedAt its last request, within an hour; empty before the first
     */
    record Token(String id, String user, String name, Instant createdAt, String createdBy, Instant expiresAt, Optional<Instant> lastUsedAt) {}

    /**
     * Live tokens and the ceiling.
     *
     * @param tokens the tokens, soonest expiry first
     * @param ceiling the installation's ceiling
     */
    record Tokens(List<Token> tokens, Ceiling ceiling) {}

    /**
     * The installation's ceiling of a token's life.
     *
     * @param maxDays the longest a token lives, in days
     * @param defaultDays the life of a token issued without one: 90 days, or {@code maxDays} when lower
     */
    record Ceiling(int maxDays, int defaultDays) {}
}
