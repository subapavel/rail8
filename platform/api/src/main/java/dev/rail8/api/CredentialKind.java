package dev.rail8.api;

/**
 * The kinds of credential a caller can present to prove which system it is: a name and a password, an API key, a client
 * certificate, an OAuth2 token. An adapter declares the kinds its consumers read from callers ({@link Component#reads()}).
 *
 * @since 0.4
 */
public enum CredentialKind { BASIC, API_KEY, CERTIFICATE, OAUTH2 }
