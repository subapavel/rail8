package dev.rail8.runtime.access;

import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** {@link AccessContract} for {@link MemoryAccess}: the nodes share one {@link MemoryAccess.Store}. */
class MemoryAccessTest extends AccessContract {

    final MemoryAccess.Store store = new MemoryAccess.Store();

    @Override
    Access node(Clock clock, PasswordHash hash, HashPlaces places, Optional<String> adminToken) {
        return new MemoryAccess(store, clock, hash, places, new SecureRandom(), adminToken);
    }

    @Override
    void closeBridge() {
        store.closeBridge();
    }

    @Override
    List<JsonObject> audit() {
        return store.audit();
    }
}
