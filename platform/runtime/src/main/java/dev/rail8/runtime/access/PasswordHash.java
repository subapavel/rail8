package dev.rail8.runtime.access;

/**
 * The slow hash of passwords. A stored hash names its parameters, so a hash made with older ones still checks and is made
 * again at the next login.
 */
public interface PasswordHash {

    /** A new stored hash of {@code password}, with a fresh salt and the current parameters. */
    String hash(String password);

    /** Whether {@code password} is the one {@code stored} was made of. One hash, whatever the answer; false for a malformed one. */
    boolean matches(String password, String stored);

    /** Whether {@code stored} was made with the current parameters. */
    boolean current(String stored);

    /**
     * A stored hash with the current parameters that no password matches: checked against it, an unknown name, a technical
     * account and a blocked user cost the same time as a real one.
     */
    String dummy();
}
