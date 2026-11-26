package dev.rail8.runtime.config;

import dev.rail8.core.key.Keys;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;
import dev.rail8.runtime.store.Retention;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres store passes the {@link ConfigStoreContract}: the nodes' stores share one database, and SQL plays its writer.
 * Beside the contract: the secret store's acts merged into the history by their time, and the retention of the history and of
 * removed objects by age.
 */
class PgConfigStoreIT extends ConfigStoreContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;

    final List<String> snapshots = new ArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        sql("truncate config_objects, config_history, deployment_systems, deployment_connection_uses, secrets, secret_uses, audit, deployments cascade");
        sql("delete from nodes");
        sql("update secret_store set generation = 0, stamp = 1");
        sql("update config_store set generation = 0, stamp = 1, mocks_allowed = false");
        sql("insert into secret_uses values ('installation', 'rail8.git', 'platform', 'git', '{credentials}')");
    }

    @AfterEach
    void dropSnapshots() {
        for (String s : snapshots) sql("drop table " + s + "_objects, " + s + "_history, " + s + "_store");
    }

    static List<Row> sql(String sql, Object... args) {
        List<Row> rows = new ArrayList<>();
        pool.preparedQuery(sql).execute(Tuple.from(args)).await().forEach(rows::add);
        return rows;
    }

    @Override
    SecretStore secretStore(String node, Map<String, String> env, FakeClock clock, FakeTimer timer) {
        long epoch = sql("insert into nodes (node, epoch, lease_until) values ($1, 1, now() + interval '1 hour') on conflict (node) do update"
                + " set epoch = nodes.epoch + 1, lease_until = excluded.lease_until returning epoch", node).getFirst().getLong(0);
        SecretStore store = SecretStore.postgres(pool, StoreKey.fromEnv(env).orElseThrow(), node, clock, timer);
        await(store.start(epoch, SecretStore.Import.none()));
        return store;
    }

    @Override
    ConfigObjects configStore(SecretStore secrets, Function<String, Optional<ConfigObjects.Adapter>> adapters, Clock clock, RandomGenerator random,
                              Consumer<String> warn) {
        return new ConfigObjects(new PgConfigRows(pool, secrets, PgConfigRows.LOCK_WAIT), secrets, adapters, clock, random, warn);
    }

    @Override
    void deploySystem(String system, JsonObject definition) {
        sql("insert into deployment_systems values ('pkg', $1, $2)", system, definition);
    }

    @Override
    void use(String flow, String step, String connection, String adapter, String direction, boolean address) {
        sql("insert into deployment_connection_uses (deployment, flow, step, connection, adapter, direction, address) values ('pkg', $1, $2, $3, $4, $5, $6)",
                flow, step, connection, adapter, direction, address);
    }

    @Override
    void auditFails(boolean fails) {
        sql(fails ? "alter table audit add constraint no_audit_now check (false) not valid" : "alter table audit drop constraint no_audit_now");
    }

    @Override
    List<JsonObject> audit(String action) {
        return sql("select actor, subject, detail from audit where action = $1 order by id", action).stream()
                .map(r -> new JsonObject().put("actor", r.getString(0)).put("subject", r.getString(1)).put("detail", (JsonObject) r.getValue(2))).toList();
    }

    @Override
    void tamperBody(Ref ref, JsonObject body) {
        sql("update config_objects set body = $1 where kind = $2 and key = $3", body, ref.kind().id(), ref.key());
    }

    @Override
    void unbind(Ref ref) {
        sql("update config_objects set key_id = null, tag = null where kind = $1 and key = $2", ref.kind().id(), ref.key());
    }

    @Override
    void tamperDraft(Ref ref, JsonObject draft) {
        sql("update config_objects set draft = $1 where kind = $2 and key = $3", draft, ref.kind().id(), ref.key());
    }

    @Override
    void tamperHistoryBody(Ref ref, int version, JsonObject body) {
        int changed = sql("update config_history set body = $1 where kind = $2 and key = $3 and change = 'activate' and version = $4 returning 1",
                body, ref.kind().id(), ref.key(), version).size();
        assertEquals(1, changed);
    }

    @Override
    JsonObject consentOf(Ref ref) {
        Row r = sql("select consent, consent_key_id, consent_tag, consent_at, consent_by from config_objects where kind = $1 and key = $2",
                ref.kind().id(), ref.key()).getFirst();
        Base64.Encoder b64 = Base64.getEncoder();
        return new JsonObject().put("consent", b64.encodeToString(r.getBuffer(0).getBytes())).put("keyId", b64.encodeToString(r.getBuffer(1).getBytes()))
                .put("tag", r.getBuffer(2) == null ? null : b64.encodeToString(r.getBuffer(2).getBytes()))
                .put("at", r.getOffsetDateTime(3).toInstant().toString()).put("by", r.getString(4));
    }

    @Override
    void putConsent(Ref ref, JsonObject consent) {
        Base64.Decoder b64 = Base64.getDecoder();
        sql("update config_objects set consent = $1, consent_key_id = $2, consent_tag = $3, consent_at = $4, consent_by = $5 where kind = $6 and key = $7",
                Buffer.buffer(b64.decode(consent.getString("consent"))), Buffer.buffer(b64.decode(consent.getString("keyId"))),
                Buffer.buffer(b64.decode(consent.getString("tag"))), Instant.parse(consent.getString("at")).atOffset(ZoneOffset.UTC),
                consent.getString("by"), ref.kind().id(), ref.key());
    }

    @Override
    void copyIds(Ref from, Ref to) {
        sql("update config_objects t set incarnation = f.incarnation, draft_id = f.draft_id, activation = f.activation from config_objects f"
                + " where f.kind = $1 and f.key = $2 and t.kind = $3 and t.key = $4", from.kind().id(), from.key(), to.kind().id(), to.key());
    }

    @Override
    void deleteRow(Ref ref) {
        sql("delete from config_history where kind = $1 and key = $2", ref.kind().id(), ref.key());
        sql("delete from config_objects where kind = $1 and key = $2", ref.kind().id(), ref.key());
    }

    @Override
    Object snapshot() {
        String s = "config_snapshot_" + snapshots.size();
        sql("create table " + s + "_objects as select * from config_objects");
        sql("create table " + s + "_history as select * from config_history");
        sql("create table " + s + "_store as select * from config_store");
        snapshots.add(s);
        return s;
    }

    @Override
    void setBack(Object snapshot) {
        String s = (String) snapshot;
        sql("delete from config_history");
        sql("delete from config_objects");
        sql("insert into config_objects select * from " + s + "_objects");
        sql("insert into config_history overriding system value select * from " + s + "_history");
        sql("update config_store c set generation = s.generation, stamp = s.stamp, mocks_allowed = s.mocks_allowed from " + s + "_store s");
    }

    @Override
    int historyRows(Ref ref) {
        return sql("select count(*) from config_history where kind = $1 and key = $2", ref.kind().id(), ref.key()).getFirst().getInteger(0);
    }

    // ------------------------------------------------------------------------------------------------- only in Postgres

    @Test
    void aSecretOverwrittenBetweenTwoActivationsShowsBetweenThemInTheHistory() {
        Node a = node("a");
        partnerSecrets(a);
        inForce(a, API, PARTNER);
        values.add("the-new-token-value");
        await(a.secrets.overwrite(MemorySecretStores.ADMIN, "partner.token", new JsonObject().put("kind", "value").put("value", "the-new-token-value")));
        sql("update audit set at = $1 where action = 'secret.overwrite'", START.plusSeconds(30).atOffset(ZoneOffset.UTC));
        a.clock.advance(Duration.ofMinutes(1));
        inForce(a, API, with(PARTNER, "path", "/v2"));
        List<JsonObject> rows = history(a, API);
        assertEquals(List.of("activate", "save", "secret.overwrite", "activate", "save"),
                rows.stream().map(h -> h.getString("change")).toList(), rows.toString());
        assertEquals("partner.token", rows.get(2).getString("secret"));
        assertEquals("admin", rows.get(2).getString("actor"));
        List<JsonObject> first = new ArrayList<>();
        a.store.history(API, OptionalLong.empty(), 2).items().forEach(h -> first.add(h.json()));
        assertEquals(List.of("activate", "save"), first.stream().map(h -> h.getString("change")).toList(), "a page ends at its last row's time");
        String next = a.store.history(API, OptionalLong.empty(), 2).next().orElseThrow();
        List<String> second = a.store.history(API, OptionalLong.of(Long.parseLong(next)), 2).items().stream().map(h -> h.json().getString("change")).toList();
        assertEquals(List.of("secret.overwrite", "activate", "save"), second, "the act between the pages is on the later one");
    }

    @Test
    void retentionByAgeKeepsTheActivationsOfAQuietObjectAndDropsARemovedOne() {
        Node a = node("a");
        inForce(a, API, PLAIN);
        inForce(a, API, with(PLAIN, "path", "/v2"));
        write(a, CONNECTIONS, API, new Change.Stop(read(a, API).run()));
        inForce(a, FILES, PLAIN);
        write(a, CONNECTIONS, FILES, new Change.Remove(etag(a, FILES)));
        activate(a, FILES);
        FakeClock later = FakeClock.at(START.plus(Duration.ofDays(400)));
        Retention job = new Retention(pool, later, new FakeTimer(later), new Retention.Config(Duration.ofDays(7), Duration.ofDays(365),
                Duration.ofHours(1), 1000));
        Retention.Run run = await(job.runOnce());
        assertEquals(List.of("activate", "activate"), history(a, API).stream().map(h -> h.getString("change")).toList());
        assertEquals(3 + 4, run.configHistory(), "the two saves and the stop of the quiet object, and every row of the removed one");
        assertEquals(1, run.configObjects());
        assertThrows(ConfigRefusal.class, () -> a.store.read(FILES), "the removed object is gone");
        ConfigStore.ObjectView back = write(a, CONNECTIONS, API, new Change.GoBack(etag(a, API), 1));
        assertEquals(body(PLAIN), back.json().getJsonObject("draft").getJsonObject("body"));
        for (int v = 3; v <= 102; v++) {
            sql("insert into config_history (kind, key, at, actor, via, change, revision, version, diff) values ('connection', 'acme/api', $1, 'x', 'session',"
                    + " 'activate', 1, $2, '[]')", START.atOffset(ZoneOffset.UTC), v);
        }
        Retention.Run again = await(job.runOnce());
        assertEquals(2 + 1, again.configHistory(), "the activations with 100 newer ones, and the save of going back");
        assertTrue(sql("select 1 from config_history where key = 'acme/api' and version in (1, 2)").isEmpty());
        assertEquals(100, historyRows(API), "the 100 newest activations stay");
        assertEquals(2, read(a, API).json().getInteger("version"), "the object itself stays");
    }

    @Test
    void holdsIsNotWrittenToPostgresYet() {
        Node a = node("a");
        assertThrows(UnsupportedOperationException.class, () -> a.store.holds("a", 1, 0, 1, ConfigStore.Refused.none()));
    }

    @Test
    void theTablesCheckRefusesTheKeysTheParserRefuses() {
        List<String> keys = List.of("eshop", "acme/erp", "eshop/rest", "acme/erp/rest", "a/b/c/d", "", "Eshop", "1shop", "-shop", "e_shop", "e shop",
                "eshop/", "/eshop", "e//rest", "é", "eshop\n", "a".repeat(64), "a".repeat(65), "acme/" + "a".repeat(65), "rail8", "rail8/erp",
                "rail8/rest", "subscribers/erp/rest", "by-agreement", "acme/rail8", "acme/rail8/rail8", "rail8x/rest", "acme/by-agreement/subscribers");
        long incarnation = 1;
        for (String key : keys) {
            for (Ref.Kind kind : Ref.Kind.values()) {
                boolean parses;
                try {
                    if (kind == Ref.Kind.SYSTEM) Keys.system(key); else Keys.connection(key);
                    parses = true;
                } catch (IllegalArgumentException e) {
                    parses = false;
                }
                String state = null;
                try {
                    pool.preparedQuery("insert into config_objects (kind, key, incarnation, revision) values ($1, $2, $3, 0)")
                            .execute(Tuple.of(kind.id(), key, incarnation++)).await();
                } catch (PgException e) {
                    state = e.getSqlState();
                }
                assertEquals(parses ? null : "23514", state, "the " + kind.id() + " key '" + key + "'");
            }
        }
    }
}
