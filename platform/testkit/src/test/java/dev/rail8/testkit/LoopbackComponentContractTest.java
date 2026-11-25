package dev.rail8.testkit;

import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.api.Timer;

import java.util.Map;
import java.util.Optional;

/** The testkit's own component passes the contract suite every real and mock component must pass. */
class LoopbackComponentContractTest extends ComponentContract {

    @Override protected Component component() { return new LoopbackComponent(); }

    @Override protected Component component(Timer timer) { return new LoopbackComponent(timer); }

    @Override protected Inbound inbound() {
        return new Inbound(FLOW, Optional.empty(), Map.of(), MAX_BYTES);
    }
}
