package dev.rail8.runtime.access;

import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * The node's two places to hash a password, with no queue. The shared place takes every hash; the second only those that
 * may take either place: a login that presents a valid device credential, a password change, a new person and a reset. So a
 * flood of logins from unknown browsers cannot keep everything else out.
 */
public final class HashPlaces {

    private final Semaphore shared = new Semaphore(1);
    private final Semaphore second = new Semaphore(1);

    /** A place held until it is closed. */
    public interface Place extends AutoCloseable {
        @Override void close();
    }

    /** A free place: the shared one, or with {@code either} the second when the shared is taken; empty when none is free. */
    public Optional<Place> take(boolean either) {
        if (shared.tryAcquire()) return Optional.of(shared::release);
        if (either && second.tryAcquire()) return Optional.of(second::release);
        return Optional.empty();
    }
}
