package dev.rail8.testkit;

import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Timer;

import java.util.Map;
import java.util.Optional;

/**
 * A component whose consumer address is assigned only at start (an ephemeral port) passes the contract suite: the endpoint
 * cannot name the address, so every producer is built for a connection holding the started consumer's address.
 */
class EphemeralAddressContractTest extends ComponentContract {

    @Override protected Component component() { return new EphemeralAddressComponent(); }

    @Override protected Component component(Timer timer) { return new EphemeralAddressComponent(timer); }

    @Override protected Inbound inbound() {
        return new Inbound(FLOW, Optional.empty(), Map.of(), MAX_BYTES);   // no address: none exists before start
    }

    @Override protected Outbound outbound(Consumer started) {
        return reaching(started);
    }

    /** The outbound whose connection holds the address {@code started} listens on. */
    static Outbound reaching(Consumer started) {
        String address = ((EphemeralAddressComponent.EphemeralConsumer) started).address();
        return new Outbound(Optional.of(new Connection("in", Map.of("address", address), Map.of())));
    }
}
