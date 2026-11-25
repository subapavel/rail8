/**
 * The secret store: secrets of three kinds sealed in Postgres under a key from the node's environment, held opened in each
 * node's memory, and brought up to date on every node of the pool by a notice and a tick, without a restart. {@code SecretStore}
 * is its one entry point: the reads the resolver makes, the writes of the admin API, the list, the record of uses and the
 * binding for the configuration of an environment.
 *
 * @since 0.1
 */
package dev.rail8.runtime.secret;
