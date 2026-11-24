package dev.rail8.runtime.access;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A password hash for tests: {@code fake$<password>}, instant. It counts every match and may run a hook in the middle of the
 * next one, as another request would act while a login hashes. A stored hash without the prefix is one of older parameters.
 */
public final class FakeHash implements PasswordHash {

    public static final String PREFIX = "fake$";

    public final AtomicInteger matches = new AtomicInteger();
    private final AtomicReference<Runnable> next = new AtomicReference<>(() -> {});

    /** {@code hook} runs once, inside the next match, before it answers. */
    public void duringNextMatch(Runnable hook) {
        next.set(hook);
    }

    @Override
    public String hash(String password) {
        return PREFIX + password;
    }

    @Override
    public boolean matches(String password, String stored) {
        matches.incrementAndGet();
        next.getAndSet(() -> {}).run();
        return stored.equals(PREFIX + password) || stored.equals("old$" + password);
    }

    @Override
    public boolean current(String stored) {
        return stored.startsWith(PREFIX);
    }

    @Override
    public String dummy() {
        return PREFIX + "\u0000";
    }
}
