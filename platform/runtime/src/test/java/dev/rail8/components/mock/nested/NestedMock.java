package dev.rail8.components.mock.nested;

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
 * Test fixture of the node tests: a class in a package below the mock's ({@code dev.rail8.components.mock..}) bound to
 * {@code sftp} — refused by a deployed node like one in the mock's package itself.
 */
public final class NestedMock implements Component {

    @Override public String scheme() { return "sftp"; }

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
