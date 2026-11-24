package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link AccessContract} for {@link PgAccess}: the nodes share one Postgres database, emptied before every case. */
@Timeout(120)
class PgAccessIT extends AccessContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        pool.query("truncate audit, sessions, tokens, devices, role_assignments, login_limits, users restart identity").execute().await();
        pool.query("update admin_bridge set open = true").execute().await();
        pool.query("update token_ceiling set max_days = 365").execute().await();
    }

    @Override
    Access node(Clock clock, PasswordHash hash, HashPlaces places, Optional<String> adminToken) {
        return new PgAccess(pool, clock, hash, places, new SecureRandom(), adminToken);
    }

    @Override
    void closeBridge() {
        pool.query("update admin_bridge set open = false").execute().await();
    }

    @Override
    List<JsonObject> audit() {
        List<JsonObject> rows = new ArrayList<>();
        for (Row r : pool.query("select at, actor, action, subject, detail, user_id, via, credential from audit order by id").execute().await()) {
            rows.add(new JsonObject().put("at", r.getOffsetDateTime(0).toInstant().toString()).put("actor", r.getString(1))
                    .put("action", r.getString(2)).put("subject", r.getString(3)).put("detail", r.getJsonObject(4))
                    .put("user", r.getLong(5)).put("via", r.getString(6)).put("credential", r.getString(7)));
        }
        return rows;
    }

    @Test
    void theListOfUsersTakesAFixedNumberOfRoundTrips() {
        AtomicInteger trips = new AtomicInteger();
        Pool counted = (Pool) Proxy.newProxyInstance(Pool.class.getClassLoader(), new Class<?>[] {Pool.class}, (proxy, method, args) -> {
            if (method.getName().equals("preparedQuery") || method.getName().equals("query")) trips.incrementAndGet();
            try {
                return method.invoke(pool, args);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        });
        Access node = new PgAccess(counted, clockA, hash, new HashPlaces(), new SecureRandom(), Optional.of(KEY));
        Actor by = key(a).acting(Action.USER_CREATE, PackageRef.ANY, REMOTE);
        a.createUser(by, "first", Kind.TECHNICAL, Set.of(Role.OPERATOR));
        node.users();
        int few = trips.getAndSet(0);
        for (int i = 0; i < 20; i++) a.createUser(by, "technical-" + i, Kind.TECHNICAL, Set.of(Role.OPERATOR));
        node.users();
        assertEquals(few, trips.get(), "as many round trips for 21 users as for 1");
    }

    @Override
    void limitNames(int count) {
        pool.preparedQuery("insert into login_limits (name, device, failures, last_failure_at)"
                + " select 'limited-' || g, '', 1, $1 from generate_series(0, $2 - 1) g").execute(Tuple.of(T0.atOffset(ZoneOffset.UTC), count)).await();
    }
}
