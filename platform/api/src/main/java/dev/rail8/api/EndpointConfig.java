package dev.rail8.api;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The configuration a component receives for one endpoint, checked at deploy time against the component's
 * {@link Component#settings()}, or its {@link Component#configSchema()} when it describes none.
 *
 * @param name endpoint name inside the flow
 * @param uri scheme-specific address ({@code http://…}, {@code sftp://…}, {@code mock://…})
 * @param options the remaining, scheme-specific settings from the flow config
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

    /** The endpoint's name, its URI and the names of its options, sorted; never an option's value, which may be a secret. */
    @Override
    public String toString() {
        return "EndpointConfig[name=" + name + ", uri=" + uri + ", options=" + new TreeSet<>(options.keySet()) + "]";
    }
}
