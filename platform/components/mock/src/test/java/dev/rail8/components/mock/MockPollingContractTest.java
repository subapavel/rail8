package dev.rail8.components.mock;

import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PollingConsumerContract;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.Map;
import java.util.Set;

/** The mock standing in for the {@code sftp} scheme passes the same polling suite as the real component (D-076). */
class MockPollingContractTest extends PollingConsumerContract {

    private final FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream into the store
    private static final String ENDPOINT = "sftp-in";
    private final MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());

    @Override protected Component component() { return sftp; }

    /** A connection keyed by the endpoint's id and holding its config, as the node hands the mock every use. */
    @Override protected Inbound inbound() {
        return Uses.in(ENDPOINT, "contract", 1_000_000, Map.of("user", "rail8", "password", "not-used-by-the-mock",
                "hostKey", "SHA256:bm90LXVzZWQtYnktdGhlLW1vY2s"));
    }

    @Override protected void drop(String name, ReadStream<Buffer> bytes) { sftp.drop(ENDPOINT, name, bytesOf(bytes)); }

    @Override protected Set<String> remaining() { return Set.copyOf(sftp.remaining(ENDPOINT)); }

    @Override protected void tick() { advanceToNextRound(rt.clock(), rt.timer()); }

    @Override protected void partnerDown(boolean down) { sftp.partnerDown(ENDPOINT, down); }
}
