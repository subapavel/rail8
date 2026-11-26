package dev.rail8.examples.webhook;

import dev.rail8.api.BlobStore;
import dev.rail8.api.Component;
import dev.rail8.api.Inbound;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.testkit.ComponentContract;
import dev.rail8.testkit.RecordingComponent;

/**
 * The mock of the webhook scheme, the testkit's {@link RecordingComponent} reading the credentials the component reads, passes
 * the same contract suite as the component:
 * a test of code that sends to or receives from a webhook endpoint runs against it, with no socket.
 */
class WebhookMockContractTest extends ComponentContract {

    @Override protected Component component() { return new RecordingComponent(WebhookComponent.SCHEME).reading(HttpIngress.READS); }

    @Override protected Component component(Timer timer) { return new RecordingComponent(WebhookComponent.SCHEME, timer).reading(HttpIngress.READS); }

    /** The mock stores nothing: what it delivers is what its producer was sent. */
    @Override protected Component component(BlobStore blobs) { return component(); }

    @Override protected Inbound inbound() { return RecordingComponent.inbound("in"); }
}
