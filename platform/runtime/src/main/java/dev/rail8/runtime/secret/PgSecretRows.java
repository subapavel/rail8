package dev.rail8.runtime.secret;

import dev.rail8.runtime.refusal.RefusalCode;

import dev.rail8.api.Secret;
import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** The secret store's rows in Postgres: the tables {@code secret_store}, {@code secrets}, {@code secret_uses} and {@code nodes}. */
final class PgSecretRows implements SecretRows {

    /** The class of the transaction locks of the names: {@code (297, hashtext(scope || chr(31) || name))}. */
    static final int NAME_LOCKS = 297;

    private static final String SCOPE = "'" + SecretStore.SCOPE + "'";
    private static final String HEAD = "select generation, stamp from secret_store";
    private static final String LOCK_STORE = HEAD + " for update";
    private static final String SHARE_STORE = HEAD + " for share";
    /** What Postgres answers when a lock was not had within {@code lock_timeout}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String REPEATABLE = "set transaction isolation level repeatable read, read only";
    private static final String META = "select name, kind, format, key_id, nonce, length(sealed), created_at, changed_at, changed_by";
    private static final String ROWS = META + " from secrets where scope = " + SCOPE + " order by name";
    private static final String SNAPSHOT = META + ", case when length(sealed) > $1 then null else sealed end from secrets where scope = "
            + SCOPE + " order by name";
    private static final String ROW = META + ", sealed from secrets where scope = " + SCOPE + " and name = $1";
    private static final String UPSERT = "insert into secrets (scope, name, kind, format, key_id, nonce, sealed, created_at, changed_at, changed_by)"
            + " values (" + SCOPE + ", $1, $2, $3, $4, $5, $6, $7, $8, $9) on conflict (scope, name) do update set kind = excluded.kind,"
            + " format = excluded.format, key_id = excluded.key_id, nonce = excluded.nonce, sealed = excluded.sealed,"
            + " created_at = excluded.created_at, changed_at = excluded.changed_at, changed_by = excluded.changed_by";
    private static final String DELETE = "delete from secrets where scope = " + SCOPE + " and name = $1";
    private static final String USES_OF = "select name, user_kind, user_id, kinds from secret_uses where scope = " + SCOPE + " and name = $1";
    private static final String USES = "select name, user_kind, user_id, kinds from secret_uses where scope = " + SCOPE + " order by name";
    private static final String UNKNOWN = "select package from deployments where not secret_uses_known order by package";
    private static final String LIVE = "select node, epoch, secret_epoch, secret_key, secret_key_previous from nodes"
            + " where lease_until >= now() order by node";
    private static final String JOINED = "update nodes set secret_key = $2, secret_key_previous = $3, secret_epoch = $4"
            + " where node = $1 and epoch = $4 and lease_until is not null";
    private static final String CLEAR_PREVIOUS = "update nodes set secret_key_previous = null where node = $1";
    /** The way of an act of the node itself, as the audit names it. */
    static final String LOCAL = "local";

    private static final String AUDIT = "insert into audit (at, actor, action, subject, detail, user_id, via, credential)"
            + " values (now(), $1, $2, $3, $4, $5, $6, $7)";
    private static final String CHANGED = "update secret_store set generation = generation + 1, stamp = floor(random() * 9.2e18)::bigint"
            + " returning generation, stamp";
    private static final String NOTIFY = "select pg_notify('" + SecretStore.CHANNEL + "', $1)";

    private static final String KEYS = "select distinct hashtext(" + SCOPE + " || chr(31) || n) k from unnest($1::text[]) n order by k";
    private static final String LOCK_NAME = "select pg_advisory_xact_lock(" + NAME_LOCKS + ", $1)";
    private static final String USED_BEFORE = "select user_kind, user_id, name from secret_uses where scope = " + SCOPE
            + " and (user_kind, user_id) in (select * from unnest($1::text[], $2::text[]))";
    private static final String KINDS_OF = "select name, kind from secrets where scope = " + SCOPE + " and name = any($1::text[])";
    private static final String DELETE_USES = "delete from secret_uses where scope = " + SCOPE + " and user_kind = $1 and user_id = $2";
    private static final String INSERT_USE = "insert into secret_uses (scope, name, user_kind, user_id, kinds) values (" + SCOPE + ", $1, $2, $3, $4)";
    /** The uses of package $1's flows and draining flows: kind, user, name, kinds. */
    private static final String PACKAGE_USES = "select user_kind, user_id, name, kinds from secret_uses where scope = " + SCOPE
            + " and user_kind in ('flow', 'drain') and starts_with(user_id, $1 || '/') order by user_id, name";

    /** How long a write waits for its locks before it is 503 {@code busy}. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private final Pool pool;
    private final Duration lockWait;

    PgSecretRows(Pool pool, Duration lockWait) {
        this.pool = pool;
        this.lockWait = lockWait;
    }

    @Override
    public Future<Head> head() {
        return pool.query(HEAD).execute().map(rows -> head(rows.iterator().next()));
    }

    @Override
    public Future<Snapshot> snapshot(int maxRow) {
        return pool.withTransaction(tx -> tx.query(REPEATABLE).execute().compose(v -> tx.query(HEAD).execute())
                .compose(head -> tx.preparedQuery(SNAPSHOT).execute(Tuple.of(maxRow)).map(rows -> {
                    List<Meta> metas = new ArrayList<>();
                    Map<String, byte[]> sealed = new HashMap<>();
                    for (Row r : rows) {
                        Meta m = meta(r);
                        metas.add(m);
                        Buffer s = r.getBuffer(9);
                        if (s != null) sealed.put(m.name(), s.getBytes());
                    }
                    return new Snapshot(head(head.iterator().next()), metas, sealed);
                })));
    }

    /**
     * {@inheritDoc} Each lock is waited for at most {@link #lockWait}; past it the write fails with 503 {@code busy}.
     */
    @Override
    public <T> Future<T> write(Collection<String> names, Function<Tx, Future<T>> work) {
        return pool.<T>withTransaction(tx -> tx.query("set local lock_timeout = " + lockWait.toMillis()).execute()
                .compose(v -> lockNames(tx, names)).compose(v -> tx.query(LOCK_STORE).execute())
                .compose(head -> work.apply(new PgTx(tx, head(head.iterator().next())))))
                .recover(failure -> Future.failedFuture(failure instanceof PgException pg && LOCK_NOT_AVAILABLE.equals(pg.getSqlState())
                        ? new SecretRefused(503, RefusalCode.BUSY, "another change of the secret store holds its locks: try again", new JsonObject())
                        : failure));
    }

    @Override
    public Future<List<NodeKeys>> sharedNodes(SqlConnection tx) {
        return tx.query(SHARE_STORE).execute().compose(v -> tx.query(LIVE).execute()).map(PgSecretRows::nodes);
    }

    @Override
    public Future<List<NodeKeys>> liveNodes() {
        return pool.query(LIVE).execute().map(PgSecretRows::nodes);
    }

    @Override
    public Future<Listing> listing() {
        return pool.withTransaction(tx -> tx.query(REPEATABLE).execute()
                .compose(v -> tx.query(ROWS).execute()).compose(rows -> tx.query(USES).execute()
                        .compose(uses -> tx.query(LIVE).execute().compose(nodes -> tx.query(UNKNOWN).execute().map(unknown -> {
                            List<String> packages = new ArrayList<>();
                            unknown.forEach(r -> packages.add(r.getString(0)));
                            return new Listing(metas(rows), uses(uses), nodes(nodes), packages);
                        })))));
    }

    /** The work of one write on {@code tx}, under the store's row lock. */
    private record PgTx(SqlConnection tx, Head head) implements Tx {

        @Override public SqlConnection connection() {
            return tx;
        }

        @Override public Future<List<NodeKeys>> liveNodes() {
            return tx.query(LIVE).execute().map(PgSecretRows::nodes);
        }

        @Override public Future<List<Meta>> rows() {
            return tx.query(ROWS).execute().map(PgSecretRows::metas);
        }

        @Override public Future<Optional<Stored>> row(String name) {
            return tx.preparedQuery(ROW).execute(Tuple.of(name)).map(rows -> {
                if (rows.size() == 0) return Optional.empty();
                Row r = rows.iterator().next();
                return Optional.of(new SecretRows.Stored(meta(r), r.getBuffer(9).getBytes()));
            });
        }

        @Override public Future<Void> upsert(SecretRows.Stored row) {
            Meta m = row.meta();
            return tx.preparedQuery(UPSERT).execute(Tuple.tuple().addString(m.name()).addString(m.kind().id()).addShort((short) m.format())
                    .addBuffer(Buffer.buffer(m.keyId())).addBuffer(Buffer.buffer(m.nonce())).addBuffer(Buffer.buffer(row.sealed()))
                    .addOffsetDateTime(time(m.createdAt())).addOffsetDateTime(time(m.changedAt())).addString(m.changedBy())).mapEmpty();
        }

        @Override public Future<Void> delete(String name) {
            return tx.preparedQuery(DELETE).execute(Tuple.of(name)).mapEmpty();
        }

        @Override public Future<List<Use>> uses(String name) {
            return tx.preparedQuery(USES_OF).execute(Tuple.of(name)).map(PgSecretRows::uses);
        }

        @Override public Future<List<String>> unknownRecords() {
            return tx.query(UNKNOWN).execute().map(rows -> {
                List<String> packages = new ArrayList<>();
                rows.forEach(r -> packages.add(r.getString(0)));
                return packages;
            });
        }

        @Override public Future<Boolean> joined(String node, long epoch, byte[] key, byte[] previous) {
            return tx.preparedQuery(JOINED).execute(Tuple.of(node, key == null ? null : Buffer.buffer(key), previous == null ? null : Buffer.buffer(previous),
                    epoch)).map(rows -> rows.rowCount() == 1);
        }

        @Override public Future<Void> clearPrevious(String node) {
            return tx.preparedQuery(CLEAR_PREVIOUS).execute(Tuple.of(node)).mapEmpty();
        }

        @Override public Future<Void> audit(Actor who, String action, String subject, JsonObject detail) {
            return tx.preparedQuery(AUDIT).execute(Tuple.of(who.name(), action, subject, detail, who.userOrNull(), who.via(), who.credential().orElse(null)))
                    .mapEmpty();
        }

        @Override public Future<Void> auditByNode(String node, String action, String subject, JsonObject detail) {
            return tx.preparedQuery(AUDIT).execute(Tuple.of(node, action, subject, detail, null, LOCAL, null)).mapEmpty();
        }

        @Override public Future<Void> auditLocal(String actor, String action, String subject, JsonObject detail) {
            return tx.preparedQuery(AUDIT).execute(Tuple.of(actor, action, subject, detail, null, LOCAL, null)).mapEmpty();
        }

        @Override public Future<Head> changed() {
            return tx.query(CHANGED).execute().map(rows -> PgSecretRows.head(rows.iterator().next()))
                    .compose(head -> tx.preparedQuery(NOTIFY).execute(Tuple.of(Long.toString(head.generation()))).map(head));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- uses

    /**
     * {@link SecretStore#replaceUses}'s rules for every user of {@code users}, in {@code tx}: the names' locks in ascending order of
     * their keys, the checks against the rows, then the rows replaced; nothing written with a problem.
     */
    static Future<List<String>> replaceUses(SqlConnection tx, List<SecretStore.Uses> users) {
        String[] kinds = users.stream().map(SecretStore.Uses::userKind).toArray(String[]::new);
        String[] ids = users.stream().map(SecretStore.Uses::userId).toArray(String[]::new);
        return tx.preparedQuery(USED_BEFORE).execute(Tuple.tuple().addArrayOfString(kinds).addArrayOfString(ids)).compose(before -> {
            Set<String> names = new TreeSet<>();
            before.forEach(r -> names.add(r.getString(2)));
            users.forEach(u -> names.addAll(u.uses().keySet()));
            return lockNames(tx, names).compose(v -> tx.preparedQuery(KINDS_OF).execute(Tuple.of((Object) names.toArray(String[]::new))));
        }).compose(stored -> {
            Map<String, Secret.Kind> kindOf = new HashMap<>();
            stored.forEach(r -> kindOf.put(r.getString(0), kind(r.getString(1))));
            List<String> problems = new ArrayList<>();
            for (SecretStore.Uses u : users) {
                if (u.userKind().equals("drain")) continue;   // a draining flow's uses were checked when it was deployed
                new TreeMap<>(u.uses()).forEach((name, takes) -> {
                    String who = u.userKind() + " '" + u.userId() + "'";
                    if (name.startsWith(SecretStore.RESERVED) && !u.userKind().equals("platform")) {
                        problems.add(who + " uses secret://" + name + ": names under " + SecretStore.RESERVED + " belong to the platform");
                        return;
                    }
                    Secret.Kind kind = kindOf.get(name);
                    if (kind == null) {
                        problems.add(who + " uses secret://" + name + ", which is not set: enter it in the secret store");
                    } else if (!takes.contains(kind)) {
                        problems.add(who + " takes secret://" + name + " as " + takes.stream().sorted().map(Secret.Kind::id).toList()
                                + ", but the secret store holds it as " + kind.id());
                    }
                });
            }
            if (!problems.isEmpty()) return Future.succeededFuture(problems);
            List<Tuple> deletes = users.stream().map(u -> Tuple.of(u.userKind(), u.userId())).toList();
            List<Tuple> inserts = new ArrayList<>();
            for (SecretStore.Uses u : users) {
                new TreeMap<>(u.uses()).forEach((name, takes) -> inserts.add(Tuple.of(name, u.userKind(), u.userId(),
                        takes.stream().sorted().map(Secret.Kind::id).toArray(String[]::new))));
            }
            Future<?> deleted = deletes.isEmpty() ? Future.succeededFuture() : tx.preparedQuery(DELETE_USES).executeBatch(deletes);
            return deleted.compose(v -> inserts.isEmpty() ? Future.succeededFuture() : tx.preparedQuery(INSERT_USE).executeBatch(inserts))
                    .map(v -> List.<String>of());
        });
    }

    /**
     * The uses of {@code pkg}'s flows ({@code flow}) and draining flows ({@code drain}): by kind, then by the user's qualified id,
     * the names it uses with their kinds.
     */
    static Future<Map<String, Map<String, Map<String, Set<Secret.Kind>>>>> packageUses(SqlConnection tx, String pkg) {
        return tx.preparedQuery(PACKAGE_USES).execute(Tuple.of(pkg)).map(rows -> {
            Map<String, Map<String, Map<String, Set<Secret.Kind>>>> uses = new TreeMap<>();
            uses.put("flow", new TreeMap<>());
            uses.put("drain", new TreeMap<>());
            for (Row r : rows) {
                Set<Secret.Kind> kinds = EnumSet.noneOf(Secret.Kind.class);
                for (String id : r.getArrayOfStrings(3)) kinds.add(kind(id));
                uses.get(r.getString(0)).computeIfAbsent(r.getString(1), u -> new TreeMap<>()).put(r.getString(2), Set.copyOf(kinds));
            }
            return uses;
        });
    }

    /** The transaction lock of each of {@code names}, in ascending order of its key, each key once. */
    private static Future<Void> lockNames(SqlClient tx, Collection<String> names) {
        if (names.isEmpty()) return Future.succeededFuture();
        return tx.preparedQuery(KEYS).execute(Tuple.of((Object) names.toArray(String[]::new))).compose(keys -> {
            Future<Void> chain = Future.succeededFuture();
            for (Row r : keys) {
                int k = r.getInteger(0);
                chain = chain.compose(v -> tx.preparedQuery(LOCK_NAME).execute(Tuple.of(k)).mapEmpty());
            }
            return chain;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------- rows

    private static Head head(Row r) {
        return new Head(r.getLong(0), r.getLong(1));
    }

    private static Meta meta(Row r) {
        return new Meta(r.getString(0), kind(r.getString(1)), r.getShort(2), r.getBuffer(3).getBytes(), r.getBuffer(4).getBytes(), r.getInteger(5),
                r.getOffsetDateTime(6).toInstant(), r.getOffsetDateTime(7).toInstant(), r.getString(8));
    }

    private static List<Meta> metas(RowSet<Row> rows) {
        List<Meta> metas = new ArrayList<>();
        rows.forEach(r -> metas.add(meta(r)));
        return metas;
    }

    private static List<Use> uses(RowSet<Row> rows) {
        List<Use> uses = new ArrayList<>();
        for (Row r : rows) {
            Set<Secret.Kind> kinds = EnumSet.noneOf(Secret.Kind.class);
            for (String id : r.getArrayOfStrings(3)) kinds.add(kind(id));
            uses.add(new Use(r.getString(0), r.getString(1), r.getString(2), Set.copyOf(kinds)));
        }
        return uses;
    }

    private static List<NodeKeys> nodes(RowSet<Row> rows) {
        List<NodeKeys> nodes = new ArrayList<>();
        for (Row r : rows) {
            Buffer key = r.getBuffer(3);
            Buffer previous = r.getBuffer(4);
            nodes.add(new NodeKeys(r.getString(0), r.getLong(1), r.getLong(2), key == null ? null : key.getBytes(),
                    previous == null ? null : previous.getBytes()));
        }
        return nodes;
    }

    static Secret.Kind kind(String id) {
        for (Secret.Kind k : Secret.Kind.values()) if (k.id().equals(id)) return k;
        throw new IllegalArgumentException("not a kind of secret: " + id);
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

}
