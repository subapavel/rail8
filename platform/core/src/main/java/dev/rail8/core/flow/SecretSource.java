package dev.rail8.core.flow;

import dev.rail8.api.Secret;

import java.util.Set;

/**
 * Where the steps of a flow take their secrets: a reference, {@code secret://<name>}, to a live handle that reads its value at
 * each use. A source that cannot hand one out refuses with an {@link IllegalArgumentException} naming the reference and where
 * the node looks for it, never a value.
 */
@FunctionalInterface
public interface SecretSource {

    /**
     * The handle of {@code reference}, a secret of one of {@code kinds}.
     *
     * @throws IllegalArgumentException the reference is malformed or reserved, it is not set, or its secret is of a kind
     *                                  {@code kinds} does not name; the message names the reference, never a value
     */
    Secret handle(String reference, Set<Secret.Kind> kinds);

    /**
     * The handle of {@code reference} for an endpoint's secret field that takes {@code kinds}, whose reference the deploy checked
     * when it read the endpoint: by default {@link #handle}; a source may hand out a live handle without checking it again, so a
     * secret deleted since fails the handle's reads rather than the build.
     *
     * @throws IllegalArgumentException as {@link #handle} does
     */
    default Secret endpoint(String reference, Set<Secret.Kind> kinds) {
        return handle(reference, kinds);
    }

    /**
     * Whether this source hands out placeholders rather than the node's secrets, as {@code rail8 validate} and {@code rail8 test}
     * do: then a producer's endpoint is built with each kind its secret fields take, in their order, until one builds.
     */
    default boolean placeholders() {
        return false;
    }
}
