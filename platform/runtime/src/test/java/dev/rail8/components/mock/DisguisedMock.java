package dev.rail8.components.mock;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;

import java.util.Map;
import java.util.function.Function;

/**
 * Test fixture of the node tests (runtime test tree, not the mock module): a class in the mock's package bound to a real
 * scheme, {@code http} — a deployed node refuses it by its package although its scheme is not {@code mock} (D-057).
 */
public final class DisguisedMock implements Component {

    @Override public String scheme() { return "http"; }

    @Override public Forms forms() {
        return new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));
    }

    @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Producer producer(Outbound out) {
        throw new UnsupportedOperationException("a stand-in: no endpoint is built before deploy");
    }

    @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
}
