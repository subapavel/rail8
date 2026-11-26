package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Entry;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Presented;
import dev.rail8.testkit.InMemoryBlobStore;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** The node's entry of one flow: every caller admitted, each message handed on once, and only a message of that flow. */
class EntriesTest {

    final Body body = new InMemoryBlobStore(dev.rail8.api.Hasher.sha256(), 1 << 20).write(BlobKey.body("m1", 0), Buffer.buffer("<a/>"), 100).await();
    final List<Envelope> delivered = new CopyOnWriteArrayList<>();
    final Entry entry = Entries.admitting("orders", e -> {
        delivered.add(e);
        return io.vertx.core.Future.succeededFuture(new Ack.Accepted(e.id()));
    });

    Envelope of(String flow) {
        return Envelope.ingress("m1", flow, Headers.empty(), body);
    }

    @Test
    void everyCallerIsAdmittedWithAHandlerOfItsOwn() {
        Presented.Via via = new Presented.Via("192.0.2.1", false, true, Optional.of("x"));
        Function<Envelope, io.vertx.core.Future<Ack>> one = entry.enter(new Presented.Basic("", "", via)).result();
        Function<Envelope, io.vertx.core.Future<Ack>> two = entry.enter(new Presented.Nothing(via)).result();
        assertNotSame(one, two);
        assertEquals(new Ack.Accepted("m1"), one.apply(of("orders")).result());
        assertEquals(new Ack.Accepted("m1"), two.apply(of("orders")).result());
        assertEquals(2, delivered.size());
    }

    @Test
    void aHandlerTakesOneMessageOfItsFlowOnce() {
        Function<Envelope, io.vertx.core.Future<Ack>> once = entry.enter(new Presented.Nothing(Presented.Via.of("", false))).result();
        assertInstanceOf(IllegalArgumentException.class, once.apply(of("invoices")).cause(), "a message of another flow");
        assertEquals(new Ack.Accepted("m1"), once.apply(of("orders")).result());
        assertInstanceOf(IllegalStateException.class, once.apply(of("orders")).cause(), "applied again");
        assertEquals(List.of("m1 orders"), delivered.stream().map(e -> e.id() + " " + e.flow()).toList(), "only the one message of its flow was handed on");
    }
}
