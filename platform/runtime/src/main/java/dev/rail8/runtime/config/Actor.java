package dev.rail8.runtime.config;

import java.util.Objects;

/**
 * Who writes the configuration: the audit's actor, and whether the caller also holds {@code config.consent}, which the route
 * takes from the decision point. An activation by an actor that holds it consents by the same act to the pairs of secret and
 * server its draft needs.
 *
 * @param who      the audit's actor: name, user id, way, credential and address
 * @param consents whether the caller holds {@code config.consent}
 * @since 0.4
 */
public record Actor(dev.rail8.core.audit.Actor who, boolean consents) {

    public Actor {
        Objects.requireNonNull(who, "who");
    }
}
