package dev.rail8.runtime.config;

import java.util.Objects;
import java.util.Optional;

/**
 * A credential in force as a node holds it: its type, a {@code basic} credential's user, and the name of the verifier its
 * password or key names. It holds no value a caller could send.
 *
 * @param type     {@code basic} or {@code api-key}
 * @param user     the user a {@code basic} caller presents; empty for {@code api-key}
 * @param verifier the name of the secret of kind {@code verifier}
 * @since 0.4
 */
public record CredentialState(String type, Optional<String> user, String verifier) {

    /** The type of a user and a password. */
    public static final String BASIC = "basic";
    /** The type of an API key. */
    public static final String API_KEY = "api-key";

    public CredentialState {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(verifier, "verifier");
        if (!type.equals(BASIC) && !type.equals(API_KEY)) throw new IllegalArgumentException("a credential's type is basic or api-key, not " + type);
        if (type.equals(BASIC) != user.isPresent()) throw new IllegalArgumentException("a basic credential, and only one, names a user");
    }
}
