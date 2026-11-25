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
}
