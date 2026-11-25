package dev.rail8.components.direct;

import dev.rail8.api.Call;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Inbound;
import dev.rail8.api.Timer;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.FakeRuntime;

import java.util.Map;
import java.util.Optional;

/**
 * The direct component passes the suite every component passes. Neither direction reaches a connection: the sender serves
 * an address of the flow's settings, and each call names the address of its receiver, so the one producer reaches two
 * receivers by two addresses.
 */
class DirectComponentContractTest extends ComponentContract {

    @Override protected Component component() {
        FakeRuntime rt = FakeRuntime.create();
        return new DirectComponent(rt.blobs(), rt.ids(), rt.timer());
    }

    @Override protected Component component(Timer timer) {
        FakeRuntime rt = FakeRuntime.create();
        return new DirectComponent(rt.blobs(), rt.ids(), timer);
    }

    @Override protected Inbound inbound() {
        return inbound(0);
    }

    @Override protected Inbound inbound(int n) {
        return new Inbound("fulfil", Optional.empty(), Map.of("address", address(n)), MAX_BYTES);
    }

    @Override protected Call call(Consumer started) {
        return call(0, started);
    }

    @Override protected Call call(int n, Consumer started) {
        return new Call(FLOW, "send", Map.of("address", address(n)), MAX_BYTES, Optional.empty());
    }

    private static String address(int n) {
        return "/contract/" + n;
    }
}
