package dev.rail8.api.violation;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/** Deliberate violation: an api class other than the entry names the Vert.x context. */
public final class ApiNamesAContext {
    private ApiNamesAContext() {}

    public static Context current() { return Vertx.currentContext(); }

    public static Future<Context> later() { return Future.succeededFuture(Vertx.currentContext()); }
}
