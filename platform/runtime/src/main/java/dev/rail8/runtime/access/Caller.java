package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who made an admin request, as {@link Access#authenticate} found it, and the one decision point every admin route asks.
 *
 * @param user the user's id, or empty for a caller without an account
 * @param name the user's name; for a caller without an account the name the audit records ({@code emergency-key}, or the
 *             declared actor of the shared token and the open loopback API)
 * @param kind the account's kind, {@link Kind#NONE} without one
 * @param roles the roles the user holds now, read with this request
 * @param via how the caller proved who it is
 * @param credential the id of the session or the token, never its secret
 * @param firstPassword whether the session was opened with a first password
 */
public record Caller(OptionalLong user, String name, Kind kind, Set<Role> roles, Via via, Optional<String> credential, boolean firstPassword) {

    /** The emergency key's name in the audit. */
    public static final String EMERGENCY_KEY = "emergency-key";

    private static final Set<Action> FIRST_PASSWORD = Set.of(Action.SELF_READ, Action.SELF_PASSWORD, Action.SELF_LOGOUT);

    public Caller {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        roles = Set.copyOf(roles);
        Objects.requireNonNull(via, "via");
        Objects.requireNonNull(credential, "credential");
    }

    /** The emergency key: no account, no role. */
    public static Caller emergencyKey() {
        return new Caller(OptionalLong.empty(), EMERGENCY_KEY, Kind.NONE, Set.of(), Via.EMERGENCY_KEY, Optional.empty(), false);
    }

    /** The shared token or the open loopback API ({@code via}), named in the audit by {@code actor}. */
    public static Caller bridge(Via via, String actor) {
        if (via != Via.SHARED_TOKEN && via != Via.LOCAL) throw new IllegalArgumentException("not a caller of the bridge: " + via);
        return new Caller(OptionalLong.empty(), actor, Kind.NONE, Set.of(), via, Optional.empty(), false);
    }

    /**
     * Whether this caller may do {@code action} on {@code pkg}, which is taken and ignored. In this order: the shared token may
     * do the actions that existed before users and the emergency key's, and the open loopback API those that existed before
     * users and the ones every caller may do (a caller of the bridge exists only while the bridge is open); the emergency key
     * only its own; a session of a first password only reads itself, changes the password and logs out; a token nothing that
     * needs a session; the caller's own actions by who the caller is; every other one by the caller's roles.
     */
    public boolean may(Action action, PackageRef pkg) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(pkg, "pkg");
        return switch (via) {
            case SHARED_TOKEN -> action.beforeStepOne() || action.emergencyKey();
            case LOCAL -> action.beforeStepOne() || action.who() == Who.EVERY;
            case EMERGENCY_KEY -> action.emergencyKey();
            case SESSION, TOKEN -> {
                if (firstPassword && !FIRST_PASSWORD.contains(action)) yield false;
                if (via == Via.TOKEN && action.needsSession()) yield false;
                yield switch (action.who()) {
                    case EVERY -> true;
                    case PERSON -> kind == Kind.PERSON;
                    case SESSION -> via == Via.SESSION;
                    case ROLES -> action.roles().stream().anyMatch(roles::contains);
                };
            }
        };
    }

    /**
     * {@link #may}, else a 403: {@code password-change-required} for a session of a first password, {@code session-required}
     * for a token of an action that needs a session, {@code not-allowed} otherwise; each names the roles that grant it.
     */
    public void require(Action action, PackageRef pkg) {
        if (may(action, pkg)) return;
        boolean account = via == Via.SESSION || via == Via.TOKEN;
        if (account && firstPassword) {
            throw AccessRefused.forbidden("password-change-required", "change the first password before " + action.id(), action.roles());
        }
        if (via == Via.TOKEN && action.needsSession()) {
            throw AccessRefused.forbidden("session-required", action.id() + " needs a person logged in, not a token", action.roles());
        }
        throw AccessRefused.forbidden("not-allowed", action.id() + " is not allowed to " + name + needs(action), action.roles());
    }

    /**
     * {@link #require}, then the audit's actor of the act: this caller's name, user, via and credential, and {@code remote}, the
     * address the request came from. The only way to get an {@link Actor}.
     */
    public Actor acting(Action action, PackageRef pkg, String remote) {
        require(action, pkg);
        return new Actor(name, user, via.id(), credential, remote);
    }

    /** Every action this caller may do, in the enum's order. */
    public List<Action> actions() {
        return Arrays.stream(Action.values()).filter(a -> may(a, PackageRef.ANY)).toList();
    }

    private static String needs(Action action) {
        if (action.roles().isEmpty()) return "";
        return ": it needs the role " + action.roles().stream().map(Role::id).sorted().collect(Collectors.joining(" or "));
    }
}
