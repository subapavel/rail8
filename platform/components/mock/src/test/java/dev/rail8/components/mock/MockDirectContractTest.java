package dev.rail8.components.mock;

import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.api.Timer;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;

import java.util.Map;

/** The mock passes the same suite as the real components, standing in for the {@code direct} scheme of calls between flows. */
class MockDirectContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new MockComponent("direct", rt.blobs(), rt.timer());
    }

    @Override protected Component component(Timer timer) {
        return new MockComponent("direct", FakeRuntime.create().blobs(), timer);
    }

    /** A connection keyed by the endpoint's id and holding its config: the mock connects both directions, direct too. */
    @Override protected Inbound inbound() {
        return Uses.in("fulfil-in", FLOW, MAX_BYTES, Map.of("address", "/contract"));
    }
}
