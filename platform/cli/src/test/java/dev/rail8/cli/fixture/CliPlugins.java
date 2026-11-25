package dev.rail8.cli.fixture;

import dev.rail8.api.Ack;
import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.ComponentContext;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Plugins for the CLI's tests, written into a jar with every class of this file ({@link #classes()}) and loaded from it, so
 * the CLI runs its own copies.
 */
public final class CliPlugins {

    private CliPlugins() {}

    /** This class and every class nested in it: what each fixture jar holds. */
    public static Class<?>[] classes() {
        return Stream.concat(Stream.of(CliPlugins.class), Stream.of(CliPlugins.class.getDeclaredClasses())).toArray(Class<?>[]::new);
    }

    /**
     * The plugin {@code acme}: the component {@code acme}, whose endpoints need a {@code url}, and the step {@code drop}, which
     * ends its message's run where it stands, so nothing after it is sent.
     */
    public static final class Acme implements Plugin {
        @Override public String name() { return "acme"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new AcmeComponent()); }
        @Override public List<StepFactory> steps() { return List.of(new Drop()); }
    }

    /** A plugin whose step's type is the shipped {@code xslt}. */
    public static final class ShippedStepType implements Plugin {
        @Override public String name() { return "shipped-step"; }
        @Override public String api() { return API; }
        @Override public List<StepFactory> steps() { return List.of(new Drop("xslt")); }
    }

    /** A plugin whose component's scheme is the shipped {@code http}. */
    public static final class ShippedScheme implements Plugin {
        @Override public String name() { return "shipped-scheme"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new AcmeComponent("http")); }
    }

    /** A plugin whose component declares the HTTP prefix {@code /rail8}, which the platform keeps for itself. */
    public static final class ReservedPrefix implements Plugin {
        @Override public String name() { return "reserved-prefix"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Hook("/rail8")); }
    }

    /** A plugin whose component declares the HTTP prefix {@code /http}, the shipped http component's. */
    public static final class ShippedPrefix implements Plugin {
        @Override public String name() { return "shipped-prefix"; }
        @Override public String api() { return API; }
        @Override public List<Component> components(ComponentContext context) { return List.of(new Hook("/http")); }
    }

    /** The component {@code hook}: in endpoints at an {@code address} under its HTTP prefix; it builds no consumer or producer. */
    public static final class Hook implements Component {
        private final String prefix;

        public Hook(String prefix) {
            this.prefix = prefix;
        }

        @Override public String scheme() { return "hook"; }

        @Override public Forms forms() { return new Forms(Settings.NONE, Settings.of(HttpIngress.ADDRESS), Map.of(Direction.IN, Forms.Reach.NO_CONNECTION)); }

        @Override public Optional<String> httpPrefix() { return Optional.of(prefix); }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
            throw new UnsupportedOperationException("hook builds no consumer");
        }

        @Override public Producer producer(Outbound out) { throw new UnsupportedOperationException("hook only takes requests"); }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /**
     * The component {@code acme}: out endpoints whose connection has a {@code url} and nothing else; its producer answers every
     * send with the envelope sent.
     */
    public static final class AcmeComponent implements Component {
        private final String scheme;

        public AcmeComponent() {
            this("acme");
        }

        public AcmeComponent(String scheme) {
            this.scheme = scheme;
        }

        @Override public String scheme() { return scheme; }

        @Override public Forms forms() {
            return new Forms(Settings.of(Field.text("url", "Address", "Where it sends.").out().required()), Settings.NONE,
                    Map.of(Direction.OUT, Forms.Reach.CONNECTION));
        }

        @Override public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
            throw new UnsupportedOperationException("acme only sends");
        }

        @Override public Producer producer(Outbound out) { return new Echo(); }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** A producer that answers every send with the envelope sent. */
    public static final class Echo implements Producer {
        @Override public Future<Envelope> send(Envelope e, Call call) { return Future.succeededFuture(e); }
        @Override public Future<Void> close() { return Future.succeededFuture(); }
    }

    /** The step {@code drop}: its stage hands its envelope back without running the rest. */
    public record Drop(String type) implements StepFactory {
        public Drop() {
            this("drop");
        }

        @Override public String configSchema() { return "{\"type\": \"object\"}"; }
        @Override public Stage create(StepConfig step, StepResources resources) { return (e, rest) -> e; }
    }
}
