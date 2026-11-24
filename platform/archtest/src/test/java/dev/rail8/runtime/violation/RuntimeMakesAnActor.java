package dev.rail8.runtime.violation;

import dev.rail8.core.audit.Actor;

import java.util.Optional;
import java.util.OptionalLong;

/** Deliberate violation: a runtime class makes the audit's actor itself, without the permission check. */
public final class RuntimeMakesAnActor {
    private RuntimeMakesAnActor() {}
    public static Actor anyone() { return new Actor("anyone", OptionalLong.empty(), "local", Optional.empty(), "127.0.0.1"); }
}
