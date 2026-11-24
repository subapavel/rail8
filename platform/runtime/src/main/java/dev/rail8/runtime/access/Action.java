package dev.rail8.runtime.access;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static dev.rail8.runtime.access.Role.ADMINISTRATOR;
import static dev.rail8.runtime.access.Role.DEVELOPER;
import static dev.rail8.runtime.access.Role.MESSAGE_BODIES;
import static dev.rail8.runtime.access.Role.OPERATOR;
import static dev.rail8.runtime.access.Role.SECRET_ADMINISTRATOR;

/**
 * Every action of the admin API: its id, the roles that grant it, whether it needs a person logged in (a session, never a
 * token), whether the emergency key may do it, whether it existed before users did (the shared token and the open loopback
 * API keep those while the bridge is open), and who grants the caller's own actions.
 */
public enum Action {
    MESSAGE_READ("message.read", Who.ROLES, false, false, true, OPERATOR),
    MESSAGE_BODY("message.body", Who.ROLES, false, false, true, MESSAGE_BODIES),
    MESSAGE_RETRY("message.retry", Who.ROLES, false, false, true, OPERATOR),
    MESSAGE_CANCEL("message.cancel", Who.ROLES, false, false, true, OPERATOR),
    WORK_RELEASE("work.release", Who.ROLES, false, false, true, OPERATOR),
    WORK_DISCARD("work.discard", Who.ROLES, false, false, true, OPERATOR),
    DEPLOYMENT_READ("deployment.read", Who.ROLES, false, false, true, DEVELOPER, OPERATOR),
    PACKAGE_DEPLOY("package.deploy", Who.ROLES, false, false, true, DEVELOPER),
    PACKAGE_UNDEPLOY("package.undeploy", Who.ROLES, false, false, true, DEVELOPER),
    MODULE_CALL("module.call", Who.ROLES, false, false, true, DEVELOPER),
    USER_READ("user.read", Who.ROLES, false, true, false, ADMINISTRATOR),
    USER_CREATE("user.create", Who.ROLES, true, true, false, ADMINISTRATOR),
    ROLES_CHANGE("roles.change", Who.ROLES, true, false, false, ADMINISTRATOR),
    ADMINISTRATORS_CHANGE("administrators.change", Who.ROLES, true, true, false, ADMINISTRATOR),
    USER_BLOCK("user.block", Who.ROLES, true, true, false, ADMINISTRATOR),
    USER_PASSWORD_RESET("user.password-reset", Who.ROLES, true, true, false, ADMINISTRATOR),
    SESSION_END("session.end", Who.ROLES, true, true, false, ADMINISTRATOR),
    TOKEN_READ("token.read", Who.ROLES, false, true, false, ADMINISTRATOR),
    TOKEN_ISSUE("token.issue", Who.ROLES, true, false, false, ADMINISTRATOR),
    TOKEN_REVOKE("token.revoke", Who.ROLES, true, true, false, ADMINISTRATOR),
    TOKEN_CEILING("token.ceiling", Who.ROLES, true, false, false, ADMINISTRATOR),
    AUDIT_READ("audit.read", Who.ROLES, false, false, false, ADMINISTRATOR),
    SECRET_LIST("secret.list", Who.ROLES, false, false, false, DEVELOPER, SECRET_ADMINISTRATOR),
    SECRET_WRITE("secret.write", Who.ROLES, false, false, false, SECRET_ADMINISTRATOR),
    SECRET_DELETE("secret.delete", Who.ROLES, false, false, false, SECRET_ADMINISTRATOR),
    SELF_READ("self.read", Who.EVERY, false, true, false),
    SELF_PASSWORD("self.password", Who.PERSON, true, false, false),
    SELF_LOGOUT("self.logout", Who.SESSION, true, false, false),
    SELF_TOKENS("self.tokens", Who.PERSON, false, false, false),
    SELF_TOKEN_ISSUE("self.token-issue", Who.PERSON, true, false, false);

    private final String id;
    private final Who who;
    private final boolean needsSession;
    private final boolean emergencyKey;
    private final boolean beforeStepOne;
    private final Set<Role> roles;

    Action(String id, Who who, boolean needsSession, boolean emergencyKey, boolean beforeStepOne, Role... roles) {
        this.id = id;
        this.who = who;
        this.needsSession = needsSession;
        this.emergencyKey = emergencyKey;
        this.beforeStepOne = beforeStepOne;
        this.roles = roles.length == 0 ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(roles)));
    }

    /** The action's id, as the audit, the answers and the design name it. */
    public String id() {
        return id;
    }

    /** The roles that grant it; empty for the caller's own actions, which {@link #who()} grants. */
    public Set<Role> roles() {
        return roles;
    }

    /** Whether it needs a person logged in: a token of a user who holds the role is refused {@code session-required}. */
    public boolean needsSession() {
        return needsSession;
    }

    /** Whether the emergency key may do it. */
    public boolean emergencyKey() {
        return emergencyKey;
    }

    /** Whether it existed before users did: the shared token and the open loopback API keep it while the bridge is open. */
    public boolean beforeStepOne() {
        return beforeStepOne;
    }

    /** What grants it: the roles, or for the caller's own actions who the caller is. */
    public Who who() {
        return who;
    }
}
