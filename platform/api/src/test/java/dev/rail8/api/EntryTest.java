package dev.rail8.api;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entry over a gate: what it decides reaches the caller, on the caller's context. */
class EntryTest {

    static final Presented NOTHING = new Presented.Nothing(Presented.Via.of("", false));

    final Vertx vertx = Vertx.vertx();

    @AfterEach void close() {
        vertx.close().await();
    }

    @Test
    void calledOnAContextItsFutureCompletesOnThatContextWhereverTheGateDecides() throws Exception {
        Promise<Function<Envelope, Future<Ack>>> decided = Promise.promise();
        Entry entry = Entry.of(p -> decided.future());
        Context context = vertx.getOrCreateContext();
        CompletableFuture<Context> completedOn = new CompletableFuture<>();
        context.runOnContext(v -> entry.enter(NOTHING).onComplete(done -> completedOn.complete(Vertx.currentContext())));
        Thread.ofVirtual().start(() -> decided.complete(e -> Future.succeededFuture(new Ack.Accepted(e.id())))).join();   // decided off the context
        assertSame(context, completedOn.get(10, TimeUnit.SECONDS), "the caller may touch its request where the future completes");
    }

    @Test
    void aGateThatThrowsOrAnswersNullFailsTheEntry() {
        Future<Function<Envelope, Future<Ack>>> thrown = Entry.of(p -> { throw new IllegalStateException("broken gate"); }).enter(NOTHING);
        assertTrue(thrown.failed());
        assertInstanceOf(IllegalStateException.class, thrown.cause());
        Future<Function<Envelope, Future<Ack>>> nothing = Entry.of(p -> null).enter(NOTHING);
        assertInstanceOf(NullPointerException.class, nothing.cause());
    }

    @Test
    void aRefusalReachesTheCallerAsItIs() {
        Refused refused = new Refused(Refused.Reason.NOT_ALLOWED, Optional.empty());
        assertSame(refused, Entry.of(p -> Future.failedFuture(refused)).enter(NOTHING).cause());
    }
}
