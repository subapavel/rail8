package dev.rail8.api.violation;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/** Counter-fixture: an api class that names the Vert.x context in a body only; its public signature names Future alone. */
public final class ApiHidesAContext {
    private ApiHidesAContext() {}

    public static Future<Void> onTheCallersContext() {
        Context context = Vertx.currentContext();
        return context == null ? Future.succeededFuture() : Future.future(p -> context.runOnContext(v -> p.complete()));
    }
}
