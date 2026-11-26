/**
 * The configuration of an environment: per connection and per system, a draft, a version in force bound under the secret
 * store's key, the secret administrator's consent to where the draft sends its secrets, a connection's stop, and a history.
 * {@code ConfigStore} is the store's contract and {@code ConfigObjects} its rules, over Postgres or over rows in memory;
 * {@code Servers} computes a connection's servers, the pairs of secret and server it sends, and the digest of the pairs that
 * need consent. No node reads the store yet.
 *
 * @since 0.4
 */
package dev.rail8.runtime.config;
