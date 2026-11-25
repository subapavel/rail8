package dev.rail8.runtime.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Caller#refusal} gives, for every action and every kind of caller, exactly the refusal {@link Caller#require} throws. */
class CallerRefusalTest {

    private static final List<Caller> CALLERS = List.of(
            Caller.emergencyKey(),
            Caller.bridge(Via.SHARED_TOKEN, "ci"),
            Caller.bridge(Via.LOCAL, "unknown"),
            new Caller(OptionalLong.of(1), "alice", Kind.PERSON, Set.of(Role.OPERATOR), Via.SESSION, Optional.of("a"), false),
            new Caller(OptionalLong.of(1), "alice", Kind.PERSON, Set.of(Role.OPERATOR), Via.SESSION, Optional.of("a"), true),
            new Caller(OptionalLong.of(2), "root", Kind.PERSON, Set.of(Role.ADMINISTRATOR, Role.DEVELOPER), Via.TOKEN, Optional.of("b"), false),
            new Caller(OptionalLong.of(3), "ci-deploy", Kind.TECHNICAL, Set.of(Role.DEVELOPER), Via.TOKEN, Optional.of("c"), false));

    @Test
    void theRefusalOfAnActionIsWhatRequireThrows() {
        for (Caller caller : CALLERS) {
            for (Action action : Action.values()) {
                Optional<AccessRefused> refusal = caller.refusal(action, PackageRef.ANY);
                String what = caller.via() + " " + caller.name() + " " + action.id();
                assertEquals(caller.may(action, PackageRef.ANY), refusal.isEmpty(), what);
                if (refusal.isEmpty()) {
                    caller.require(action, PackageRef.ANY);
                    continue;
                }
                AccessRefused thrown = assertThrows(AccessRefused.class, () -> caller.require(action, PackageRef.ANY), what);
                assertEquals(403, refusal.get().status(), what);
                assertEquals(thrown.code(), refusal.get().code(), what);
                assertEquals(thrown.needs(), refusal.get().needs(), what);
                assertEquals(thrown.getMessage(), refusal.get().getMessage(), what);
                assertEquals(action.roles(), refusal.get().needs(), what);
            }
        }
    }

    @Test
    void everyActionIsEitherAllowedOrRefusedExactlyOnce() {
        for (Caller caller : CALLERS) {
            List<Action> refused = List.of(Action.values()).stream().filter(a -> caller.refusal(a, PackageRef.ANY).isPresent()).toList();
            assertEquals(Action.values().length, caller.actions().size() + refused.size(), caller.name());
            assertTrue(caller.actions().stream().noneMatch(refused::contains), caller.name());
        }
    }

    @Test
    void eachCodeComesFromWhoTheCallerIs() {
        Caller first = CALLERS.get(4);
        assertEquals("password-change-required", first.refusal(Action.MESSAGE_READ, PackageRef.ANY).orElseThrow().code().code());
        Caller token = CALLERS.get(5);
        assertEquals("session-required", token.refusal(Action.USER_CREATE, PackageRef.ANY).orElseThrow().code().code());
        Caller operator = CALLERS.get(3);
        assertEquals("not-allowed", operator.refusal(Action.PACKAGE_DEPLOY, PackageRef.ANY).orElseThrow().code().code());
        assertEquals(Set.of(Role.DEVELOPER), operator.refusal(Action.PACKAGE_DEPLOY, PackageRef.ANY).orElseThrow().needs());
    }
}
