package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The users of the admin API, their sessions and the acts on them. Every method blocks: call it on a virtual thread. A
 * refusal is an {@link AccessRefused}; every act that changes something writes its audit row in the same transaction, with
 * the {@link Actor} that {@link Caller#acting} gave the route.
 */
public interface Access {

    /** The longest name, in characters, a caller of the bridge may declare in {@code X-Rail8-Actor}. */
    int ACTOR_MAX = 128;

    /**
     * The caller of one admin request, from its {@code Authorization} header, read from the store with nothing cached: a session,
     * the emergency key, or while the bridge is open the shared token and, on a node without a token, a request without a
     * credential. {@code declaredActor} is the {@code X-Rail8-Actor} header, which only the bridge's callers are named by.
     *
     * @throws AccessRefused 401 {@code no-credential}, {@code unknown-credential}, {@code session-ended} or {@code blocked}; 400
     *                       for a caller of the bridge whose declared name is above {@link #ACTOR_MAX} characters
     */
    Caller authenticate(Optional<String> authorization, Optional<String> declaredActor);

    /**
     * Whether {@code authorization} presents a session's credential, {@code Bearer rail8s_<id>_<secret>}, by its form alone:
     * nothing is read from the store.
     */
    static boolean presentsSession(Optional<String> authorization) {
        return authorization.filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .flatMap(h -> Credentials.sessionId(h.substring(7).strip())).isPresent();
    }

    /**
     * A login: a new session of {@code name} when {@code password} is theirs, counted on the name's row of the login limit.
     *
     * @param remote the address the login came from, for the audit
     * @throws AccessRefused 401 {@code login-refused} with one text for every reason, 429 {@code too-many-attempts}, 503
     *                       {@code busy}
     */
    Login login(String name, String password, String remote);

    /**
     * {@code by}'s own password changed from {@code current} to {@code next}: the same session goes on without a first
     * password's restriction, every other session of the user ends and the generation is raised.
     *
     * @throws AccessRefused 400 for a password the rules refuse, 403 {@code wrong-password}, 429, 503 {@code busy}
     */
    void changePassword(Actor by, String current, String next);

    /** {@code by}'s session ends. */
    void logout(Actor by);

    /** When the session of {@code who} ends at the latest; empty for any other caller. */
    Optional<Instant> expiresAt(Caller who);

    /** Every user, by name, with their roles, block and live sessions. */
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
     */
    record Login(String session, Instant expiresAt, long idleSeconds, Caller caller) {}

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
     */
    record User(String name, Kind kind, List<Grant> roles, Optional<Instant> blockedAt, boolean firstPassword, Instant createdAt,
                String createdBy, List<LiveSession> sessions) {}

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
}
