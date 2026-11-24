package dev.rail8.runtime.access;

/** What grants an action: the caller's roles, or for the caller's own actions who the caller is. */
public enum Who {
    /** A caller who holds one of the action's roles. */
    ROLES,
    /** Every caller, the emergency key included. */
    EVERY,
    /** A person, through a session or a token. */
    PERSON,
    /** A caller through a session. */
    SESSION
}
