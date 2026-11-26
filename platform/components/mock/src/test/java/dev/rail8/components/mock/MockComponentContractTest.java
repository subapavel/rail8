package dev.rail8.components.mock;

import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;

import java.util.Map;

/** The mock passes the same suite as the real components, standing in for the {@code http} scheme. */
class MockComponentContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new MockComponent("http", rt.blobs(), rt.timer());
    }

    /** P2-15b: the mock's sends are cut on the timer it is built with. */
    @Override protected Component component(dev.rail8.api.Timer timer) {
        return new MockComponent("http", FakeRuntime.create().blobs(), timer);
    }

    /** A connection keyed by the endpoint's id and holding its config, as the node hands the mock every use. */
    @Override protected Inbound inbound() {
        return Uses.in("partner-domestic", FLOW, MAX_BYTES, Map.of("url", "http://localhost:8081/echo"));
    }

    /** The cookies of the node's UI, which the consumer keeps out of a flow. */
    @Override protected java.util.Set<String> platformCookies() {
        return MockComponent.PLATFORM_COOKIES;
    }
}
