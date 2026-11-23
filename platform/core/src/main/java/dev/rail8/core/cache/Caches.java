package dev.rail8.core.cache;

import dev.rail8.api.Cache;

/**
 * The node's cache for custom code: one {@link Cache} per tenant, package and step type. The node holds one and hands each
 * step the scope of its package and type. {@link InMemoryCache} keeps it in the node's memory; a node with Valkey shares it
 * with the other nodes there.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Caches {

    /**
     * The cache of the steps of {@code stepType} in package {@code packageName} of {@code tenant}. The same three names give the
     * same keys each time; scopes of other names never meet a key of this one. It does no I/O.
     */
    Cache scope(String tenant, String packageName, String stepType);
}
