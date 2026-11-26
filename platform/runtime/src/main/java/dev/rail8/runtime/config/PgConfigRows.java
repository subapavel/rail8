package dev.rail8.runtime.config;

import dev.rail8.api.Secret;
import dev.rail8.runtime.refusal.RefusalCode;
import dev.rail8.runtime.secret.SecretStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * The configuration's rows in Postgres: {@code config_store}, {@code config_objects}, {@code config_history}, read with the
 * deployed systems and their uses, the secret store's uses and audit. Each statement is awaited on the caller's virtual thread;
 * every lock is waited for at most {@link #LOCK_WAIT}, past which the write is 503 {@code busy}.
 */
final class PgConfigRows implements ConfigRows {

    /** How long a write waits for a lock. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    /** What Postgres answers when a lock was not had within {@code lock_timeout}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String COLUMNS = "kind, key, incarnation, revision, version, activation, format, body, key_id, tag, activated_at,"
            + " activated_by, draft_id, draft_format, draft, draft_at, draft_by, consent, consent_key_id, consent_tag, consent_at, consent_by,"
            + " run, stopped_at, stopped_by";
    private static final String HEAD = "select generation, stamp, mocks_allowed from config_store";
    private static final String SET_HEAD = "update config_store set generation = $1, stamp = $2, mocks_allowed = $3";
    private static final String NOTIFY = "select pg_notify('" + ConfigObjects.CHANNEL + "', $1)";
    private static final String ROW = "select " + COLUMNS + " from config_objects where kind = $1 and key = $2";
    private static final String PUT = "insert into config_objects (" + COLUMNS + ") values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,"
            + " $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25) on conflict (kind, key) do update set"
            + " incarnation = excluded.incarnation, revision = excluded.revision, version = excluded.version, activation = excluded.activation,"
            + " format = excluded.format, body = excluded.body, key_id = excluded.key_id, tag = excluded.tag, activated_at = excluded.activated_at,"
            + " activated_by = excluded.activated_by, draft_id = excluded.draft_id, draft_format = excluded.draft_format, draft = excluded.draft,"
            + " draft_at = excluded.draft_at, draft_by = excluded.draft_by, consent = excluded.consent, consent_key_id = excluded.consent_key_id,"
            + " consent_tag = excluded.consent_tag, consent_at = excluded.consent_at, consent_by = excluded.consent_by, run = excluded.run,"
            + " stopped_at = excluded.stopped_at, stopped_by = excluded.stopped_by";
    private static final String ROWS = "select " + COLUMNS + " from config_objects where kind = $1 and starts_with(key, $2) and key collate \"C\" > $3"
            + " order by key collate \"C\" limit $4";
    private static final String IN_FORCE = "select " + COLUMNS + " from config_objects where body is not null order by kind, key";
    private static final String COUNT = "select count(*) from config_objects where kind = $1";
    private static final String BODY_BYTES = "select coalesce(sum(octet_length(body::text)), 0) from config_objects"
            + " where body is not null and not (kind = $1 and key = $2)";
    private static final String USED = "select exists (select 1 from config_objects where incarnation = $1 or activation = $1 or draft_id = $1)"
            + " or exists (select 1 from config_history where incarnation = $1 or activation = $1)";
    private static final String HISTORY_COLUMNS = "id, kind, key, at, actor, user_id, via, credential, change, revision, generation, version,"
            + " format, body, incarnation, activation, key_id, tag, diff, note";
    private static final String ADD = "insert into config_history (kind, key, at, actor, user_id, via, credential, change, revision, generation,"
            + " version, format, body, incarnation, activation, key_id, tag, diff, note) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,"
            + " $12, $13, $14, $15, $16, $17, $18, $19) returning id";
    private static final String ACTIVATIONS = "select " + HISTORY_COLUMNS + " from config_history where kind = $1 and key = $2"
            + " and change = 'activate' order by id desc";
    private static final String VERSION = "select " + HISTORY_COLUMNS + " from config_history where kind = $1 and key = $2"
            + " and change = 'activate' and version = $3 order by id desc limit 1";
    private static final String HISTORY = "select " + HISTORY_COLUMNS + " from config_history where kind = $1 and key = $2 and id < $3"
            + " order by id desc limit $4";
    private static final String TRIM = """
            delete from config_history where id in (
                select id from config_history
                 where kind = $1 and key = $2
                   and id not in (select id from config_history where kind = $1 and key = $2 and change = 'activate' order by id desc limit $4)
                 order by id
                 limit greatest(0, (select count(*) from config_history where kind = $1 and key = $2) - $3))""";
    private static final String DEPLOYED_SYSTEM = "select definition from deployment_systems where system = $1";
    private static final String USES = "select flow, step, connection, adapter, direction, address from deployment_connection_uses"
            + " where connection = $1 order by flow, step, direction";
    private static final String USES_OF_SYSTEM = "select flow, step, connection, adapter, direction, address from deployment_connection_uses"
            + " where starts_with(connection, $1 || '/') and position('/' in substr(connection, length($1) + 2)) = 0 order by flow, step, direction";
    private static final String AUDIT = "insert into audit (at, actor, action, subject, detail, user_id, via, credential)"
            + " values ($1, $2, $3, $4, $5, $6, $7, $8)";
    private static final String SECRET_ACTS = "select at, actor, action, subject from audit where action in ('secret.overwrite', 'secret.delete')"
            + " and subject = any($1::text[]) and ($2::timestamptz is null or at >= $2) and ($3::timestamptz is null or at < $3)"
            + " order by at desc, id desc";
    private static final String BOUND_ELSEWHERE = "select " + COLUMNS + " from config_objects"
            + " where (tag is not null and key_id <> $1) or (consent_tag is not null and consent_key_id <> $1)"
            + " order by kind, key limit $2 for update skip locked";
    private static final String HISTORY_BOUND_ELSEWHERE = "select " + HISTORY_COLUMNS + " from config_history"
            + " where change = 'activate' and tag is not null and key_id <> $1 order by id limit $2 for update skip locked";
    private static final String BIND_HISTORY = "update config_history set key_id = $2, tag = $3 where id = $1";
    private static final String COUNT_UNDER = "select (select count(*) from config_objects where tag is not null and key_id = $1)"
            + " + (select count(*) from config_objects where consent_tag is not null and consent_key_id = $1)"
            + " + (select count(*) from config_history where tag is not null and key_id = $1)";

    private final Pool pool;
    private final SecretStore secrets;
    private final Duration lockWait;

    PgConfigRows(Pool pool, SecretStore secrets, Duration lockWait) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.lockWait = Objects.requireNonNull(lockWait, "lockWait");
    }

    @Override
    public <T> T read(Function<Tx, T> work) {
        return transaction(work);
    }

    @Override
    public <T> T write(Function<Tx, T> work) {
        try {
            return transaction(work);
        } catch (PgException pg) {
            if (LOCK_NOT_AVAILABLE.equals(pg.getSqlState())) {
                throw new ConfigRefusal(503, RefusalCode.BUSY, "another change of the configuration holds its locks: try again", new JsonObject());
            }
            throw pg;
        }
    }

    private <T> T transaction(Function<Tx, T> work) {
        SqlConnection connection = pool.getConnection().await();
        try {
            Transaction tx = connection.begin().await();
            T result;
            try {
                connection.query("set local lock_timeout = " + lockWait.toMillis()).execute().await();
                result = work.apply(new PgTx(connection));
            } catch (RuntimeException | Error failure) {
                tx.rollback().await();
                throw failure;
            }
            tx.commit().await();
            return result;
        } finally {
            connection.close();
        }
    }

    @Override
    public <T> Future<T> within(SqlConnection tx, Function<Tx, T> work) {
        Promise<T> done = Promise.promise();
        Thread.ofVirtual().name("rail8-config-bind").start(() -> {
            try {
                done.complete(work.apply(new PgTx(tx)));
            } catch (RuntimeException | Error failure) {
                done.fail(failure);
            }
        });
        return done.future();
    }

    @Override
    public void holds(String node, long epoch, long generation, long stamp, ConfigStore.Refused refused) {
        throw new UnsupportedOperationException("the nodes' columns of the configuration come with the node's read of the store");
    }

    @Override
    public String toString() {
        return "PgConfigRows";
    }

    /** One transaction on {@code c}. */
    private final class PgTx implements Tx {

        private final SqlConnection c;

        PgTx(SqlConnection c) {
            this.c = c;
        }

        private RowSet<Row> run(String sql, Tuple args) {
            return c.preparedQuery(sql).execute(args).await();
        }

        @Override
        public SqlConnection connection() {
            return c;
        }

        @Override
        public Head head(boolean lock) {
            Row r = run(HEAD + (lock ? " for update" : ""), Tuple.tuple()).iterator().next();
            return new Head(r.getLong(0), r.getLong(1), r.getBoolean(2));
        }

        @Override
        public void head(Head head) {
            run(SET_HEAD, Tuple.of(head.generation(), head.stamp(), head.mocksAllowed()));
        }

        @Override
        public void notifyGeneration(long generation) {
            run(NOTIFY, Tuple.of(Long.toString(generation)));
        }

        @Override
        public Optional<ConfigRows.ObjectRow> row(Ref ref, boolean lock) {
            RowSet<Row> found = run(ROW + (lock ? " for update" : ""), Tuple.of(ref.kind().id(), ref.key()));
            return found.size() == 0 ? Optional.empty() : Optional.of(object(found.iterator().next()));
        }

        @Override
        public void put(ConfigRows.ObjectRow r) {
            Tuple t = Tuple.tuple().addString(r.ref.kind().id()).addString(r.ref.key()).addLong(r.incarnation).addLong(r.revision)
                    .addInteger(r.version).addLong(r.activation).addValue(r.format == null ? null : r.format.shortValue()).addValue(r.body)
                    .addValue(buffer(r.keyId)).addValue(buffer(r.tag)).addValue(time(r.activatedAt)).addString(r.activatedBy)
                    .addLong(r.draftId).addValue(r.draftFormat == null ? null : r.draftFormat.shortValue()).addValue(r.draft)
                    .addValue(time(r.draftAt)).addString(r.draftBy).addValue(buffer(r.consent)).addValue(buffer(r.consentKeyId))
                    .addValue(buffer(r.consentTag)).addValue(time(r.consentAt)).addString(r.consentBy).addInteger(r.run)
                    .addValue(time(r.stoppedAt)).addString(r.stoppedBy);
            run(PUT, t);
        }

        @Override
        public List<ConfigRows.ObjectRow> rows(Ref.Kind kind, String prefix, String after, int limit) {
            List<ConfigRows.ObjectRow> rows = new ArrayList<>();
            run(ROWS, Tuple.of(kind.id(), prefix, after, limit)).forEach(r -> rows.add(object(r)));
            return rows;
        }

        @Override
        public List<ConfigRows.ObjectRow> inForce() {
            List<ConfigRows.ObjectRow> rows = new ArrayList<>();
            run(IN_FORCE, Tuple.tuple()).forEach(r -> rows.add(object(r)));
            return rows;
        }

        @Override
        public long count(Ref.Kind kind) {
            return run(COUNT, Tuple.of(kind.id())).iterator().next().getLong(0);
        }

        @Override
        public long bodyBytes(Ref except) {
            return run(BODY_BYTES, Tuple.of(except.kind().id(), except.key())).iterator().next().getLong(0);
        }

        @Override
        public boolean used(long id) {
            return run(USED, Tuple.of(id)).iterator().next().getBoolean(0);
        }

        @Override
        public long add(History h) {
            Tuple t = Tuple.tuple().addString(h.ref.kind().id()).addString(h.ref.key()).addValue(time(h.at)).addString(h.actor).addLong(h.userId)
                    .addString(h.via).addString(h.credential).addString(h.change).addLong(h.revision).addLong(h.generation).addInteger(h.version)
                    .addValue(h.format == null ? null : h.format.shortValue()).addValue(h.body).addLong(h.incarnation).addLong(h.activation)
                    .addValue(buffer(h.keyId)).addValue(buffer(h.tag)).addValue(h.diff).addString(h.note);
            return run(ADD, t).iterator().next().getLong(0);
        }

        @Override
        public List<History> activations(Ref ref) {
            return histories(run(ACTIVATIONS, Tuple.of(ref.kind().id(), ref.key())));
        }

        @Override
        public Optional<History> version(Ref ref, int version) {
            return histories(run(VERSION, Tuple.of(ref.kind().id(), ref.key(), version))).stream().findFirst();
        }

        @Override
        public List<History> history(Ref ref, OptionalLong before, int limit) {
            return histories(run(HISTORY, Tuple.of(ref.kind().id(), ref.key(), before.orElse(Long.MAX_VALUE), limit)));
        }

        @Override
        public void trim(Ref ref, int max, int keep) {
            run(TRIM, Tuple.of(ref.kind().id(), ref.key(), max, keep));
        }

        @Override
        public Optional<String> deployedAdapter(String connectionKey) {
            String system = connectionKey.substring(0, connectionKey.lastIndexOf('/'));
            String name = connectionKey.substring(connectionKey.lastIndexOf('/') + 1);
            RowSet<Row> found = run(DEPLOYED_SYSTEM, Tuple.of(system));
            if (found.size() == 0) return Optional.empty();
            Object definition = found.iterator().next().getValue(0);
            if (!(definition instanceof JsonObject d) || !(d.getValue("connections") instanceof JsonObject connections)
                    || !(connections.getValue(name) instanceof JsonObject connection)) {
                return Optional.empty();
            }
            return Optional.ofNullable(connection.getString("adapter"));
        }

        @Override
        public boolean deployed(String systemKey) {
            return run(DEPLOYED_SYSTEM, Tuple.of(systemKey)).size() > 0;
        }

        @Override
        public List<Use> uses(String connectionKey) {
            return uses(run(USES, Tuple.of(connectionKey)));
        }

        @Override
        public List<Use> usesOfSystem(String systemKey) {
            return uses(run(USES_OF_SYSTEM, Tuple.of(systemKey)));
        }

        private List<Use> uses(RowSet<Row> rows) {
            List<Use> uses = new ArrayList<>();
            rows.forEach(r -> uses.add(new Use(r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5))));
            return uses;
        }

        @Override
        public List<String> replaceUses(Ref ref, Map<String, Set<Secret.Kind>> uses) {
            return secrets.replaceUses(c, List.of(new SecretStore.Uses(ref.kind().id(), ref.key(), uses))).await();
        }

        @Override
        public Map<String, Secret.Kind> kinds(Set<String> names) {
            return secrets.kinds(c, names).await();
        }

        @Override
        public void audit(dev.rail8.core.audit.Actor who, Instant at, String action, String subject, JsonObject detail) {
            try {
                run(AUDIT, Tuple.of(time(at), who.name(), action, subject, detail, who.userOrNull(), who.via(), who.credential().orElse(null)));
            } catch (PgException failed) {
                if (LOCK_NOT_AVAILABLE.equals(failed.getSqlState())) throw failed;
                throw new ConfigRefusal(503, RefusalCode.AUDIT_UNAVAILABLE, "the audit row could not be written, so nothing was changed: try again",
                        new JsonObject());
            }
        }

        @Override
        public List<SecretAct> secretActs(Set<String> names, Optional<Instant> from, Optional<Instant> until) {
            String[] subjects = names.stream().map(n -> "secret/" + n).toArray(String[]::new);
            List<SecretAct> acts = new ArrayList<>();
            run(SECRET_ACTS, Tuple.tuple().addArrayOfString(subjects).addValue(from.map(PgConfigRows::time).orElse(null))
                    .addValue(until.map(PgConfigRows::time).orElse(null)))
                    .forEach(r -> acts.add(new SecretAct(r.getOffsetDateTime(0).toInstant(), r.getString(1), r.getString(2),
                            r.getString(3).substring("secret/".length()))));
            return acts;
        }

        @Override
        public List<ConfigRows.ObjectRow> boundElsewhere(byte[] keyId, int max) {
            List<ConfigRows.ObjectRow> rows = new ArrayList<>();
            run(BOUND_ELSEWHERE, Tuple.of(Buffer.buffer(keyId), max)).forEach(r -> rows.add(object(r)));
            return rows;
        }

        @Override
        public List<History> historyBoundElsewhere(byte[] keyId, int max) {
            return histories(run(HISTORY_BOUND_ELSEWHERE, Tuple.of(Buffer.buffer(keyId), max)));
        }

        @Override
        public void bindHistory(long id, byte[] keyId, byte[] tag) {
            run(BIND_HISTORY, Tuple.of(id, buffer(keyId), buffer(tag)));
        }

        @Override
        public long countUnder(byte[] keyId) {
            return run(COUNT_UNDER, Tuple.of(Buffer.buffer(keyId))).iterator().next().getLong(0);
        }
    }

    private static ConfigRows.ObjectRow object(Row r) {
        ConfigRows.ObjectRow o = new ConfigRows.ObjectRow();
        o.ref = new Ref(Ref.Kind.of(r.getString("kind")), r.getString("key"));
        o.incarnation = r.getLong("incarnation");
        o.revision = r.getLong("revision");
        o.version = r.getInteger("version");
        o.activation = r.getLong("activation");
        o.format = r.getInteger("format");
        o.body = json(r.getValue("body"));
        o.keyId = bytes(r.getBuffer("key_id"));
        o.tag = bytes(r.getBuffer("tag"));
        o.activatedAt = instant(r.getOffsetDateTime("activated_at"));
        o.activatedBy = r.getString("activated_by");
        o.draftId = r.getLong("draft_id");
        o.draftFormat = r.getInteger("draft_format");
        o.draft = json(r.getValue("draft"));
        o.draftAt = instant(r.getOffsetDateTime("draft_at"));
        o.draftBy = r.getString("draft_by");
        o.consent = bytes(r.getBuffer("consent"));
        o.consentKeyId = bytes(r.getBuffer("consent_key_id"));
        o.consentTag = bytes(r.getBuffer("consent_tag"));
        o.consentAt = instant(r.getOffsetDateTime("consent_at"));
        o.consentBy = r.getString("consent_by");
        o.run = r.getInteger("run");
        o.stoppedAt = instant(r.getOffsetDateTime("stopped_at"));
        o.stoppedBy = r.getString("stopped_by");
        return o;
    }

    private static List<History> histories(RowSet<Row> rows) {
        List<History> list = new ArrayList<>();
        for (Row r : rows) {
            History h = new History();
            h.id = r.getLong("id");
            h.ref = new Ref(Ref.Kind.of(r.getString("kind")), r.getString("key"));
            h.at = instant(r.getOffsetDateTime("at"));
            h.actor = r.getString("actor");
            h.userId = r.getLong("user_id");
            h.via = r.getString("via");
            h.credential = r.getString("credential");
            h.change = r.getString("change");
            h.revision = r.getLong("revision");
            h.generation = r.getLong("generation");
            h.version = r.getInteger("version");
            h.format = r.getInteger("format");
            h.body = json(r.getValue("body"));
            h.incarnation = r.getLong("incarnation");
            h.activation = r.getLong("activation");
            h.keyId = bytes(r.getBuffer("key_id"));
            h.tag = bytes(r.getBuffer("tag"));
            Object diff = r.getValue("diff");
            h.diff = diff instanceof JsonArray a ? a : new JsonArray();
            h.note = r.getString("note");
            list.add(h);
        }
        return list;
    }

    /** A jsonb value as an object: null for SQL null, JSON null or another kind of value. */
    private static JsonObject json(Object value) {
        return value instanceof JsonObject o ? o : null;
    }

    private static Buffer buffer(byte[] bytes) {
        return bytes == null ? null : Buffer.buffer(bytes);
    }

    private static byte[] bytes(Buffer buffer) {
        return buffer == null ? null : buffer.getBytes();
    }

    static OffsetDateTime time(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
