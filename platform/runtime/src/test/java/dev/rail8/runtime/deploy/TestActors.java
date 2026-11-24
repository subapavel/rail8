package dev.rail8.runtime.deploy;

import dev.rail8.core.audit.Actor;

import java.util.Optional;
import java.util.OptionalLong;

/** Actors for tests that act without the admin API's permission check. */
public final class TestActors {

    private TestActors() {}

    /** An actor of {@code name} without an account, declared on the loopback API. */
    public static Actor named(String name) {
        return new Actor(name, OptionalLong.empty(), "local", Optional.empty(), "127.0.0.1");
    }
}
