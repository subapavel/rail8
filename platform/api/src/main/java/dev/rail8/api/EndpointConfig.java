package dev.rail8.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * One endpoint file as the node reads it: its id, its URI ({@code <scheme>://<id>}) and its {@code config}, the override of
 * its environment applied. An adapter never receives it: the node splits it by the adapter's
 * {@link dev.rail8.api.settings.Forms} into a {@link Connection} whose key is the endpoint's id and the settings in the
 * flow.
 *
 * @param name the endpoint's id
 * @param uri {@code <scheme>://<id>}
 * @param options the endpoint's config
 * @since 0.1
 */
public record EndpointConfig(String name, URI uri, Map<String, Object> options) {
    public EndpointConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uri, "uri");
        options = Map.copyOf(options);
    }

    public String scheme() {
        return uri.getScheme();
    }
}
