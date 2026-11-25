package dev.rail8.runtime.node;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;

import java.util.Map;
import java.util.function.Function;

/**
 * A component of any scheme in a package that is neither the mock's nor the testkit's: what a node in a deployed
 * environment must accept — unless its scheme is {@code mock}. The node builds no endpoint before deploy (P1-09d), so
 * consumer and producer are refused; health is up.
 */
public final class PlainComponent implements Component {

    private final String scheme;

    public PlainComponent(String scheme) { this.scheme = scheme; }

    @Override public String scheme() { return scheme; }

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

    @Override public String toString() { return "PlainComponent[" + scheme + "]"; }
}
