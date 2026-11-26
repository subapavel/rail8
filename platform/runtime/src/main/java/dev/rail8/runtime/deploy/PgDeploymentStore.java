package dev.rail8.runtime.deploy;

import dev.rail8.api.Secret;
import dev.rail8.core.audit.Actor;
import dev.rail8.runtime.secret.SecretStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@link DeploymentStore} over the {@code deployments} and {@code deploy_pool} tables: one row per package and the pool's
 * generation, shared by the pool's nodes. A save is an upsert that replaces every column, the pins included (a JSON array of
 * {@code {"name", "version", "content", "repository", "commit"}}); a read is sorted here, by {@link String#compareTo}, since the
 * database's collation (en_US ignores a dash) is not the order {@code Deployments} lists its packages in. The audit row of a
 * deploy or an undeploy goes into the {@code audit} table, stamped with the database's {@code now()}: a save's or a delete's as
 * the last statement of its transaction, a refused deploy's on {@code pool}.
 *
 * <p>The flows the pool retires are rows of {@code retiring}, one per flow, each with a copy of the record it ran under: a save
 * marks each flow the replaced row ran at-least-once and the new one does not, by an {@code insert ... select} from the old row
 * before the upsert, and deletes the marks of the flows the new row runs at-least-once; a delete marks every flow the row ran
 * at-least-once. {@link #endMarks} runs on {@code pool}, one transaction per mark, so it never holds one mark's row while it waits
 * for another's and cannot deadlock with a save that revives two of them. In each: the flow's exclusive admission lock
 * {@code (295, hashtext(flow))} ({@code 0x0127}), taken without waiting, and in a later statement — its snapshot taken after the
 * lock, under READ COMMITTED — the mark deleted when no {@code work} row of the flow is left. A write of a message holds that lock
 * shared from before its admission check to its commit, so a mark never ends between a message's admission and its commit.
 *
 * <p>The pool's lock is the session advisory lock {@code (294, 0)} ({@code 0x0126}), taken with {@code pg_try_advisory_lock} on
 * a connection of its own, opened for the one holder and never pooled: its writes run on that connection, and closing it frees
 * the lock, so a node that vanishes frees it once the server sees the connection gone. Reads run on {@code pool}.
 *
 * <p>What each node runs are rows of {@code node_packages}, written on {@code pool}. A node's write is one transaction: its row of
 * {@code nodes} locked {@code for key share} — a join of the node, which locks that row {@code for update}, waits for the write,
 * while a renew or a release does not — and read to carry the epoch written with and a lease that was not released; then the node's
 * rows of every epoch deleted with those of every node whose epoch moved on or whose lease was released, and its new rows inserted.
 * {@link #listings} reads the records and the rows of the nodes whose lease holds by the database clock, of their current epoch, in
 * one read-only transaction at repeatable read: both reads see one snapshot.
 *
 * @since 0.1
 */
public final class PgDeploymentStore implements DeploymentStore {

    private static final String READ = "select p.generation, (select coalesce(array_agg(r.flow order by r.flow), '{}') from retiring r) as marked, "
            + "d.package, d.version, d.repository, d.commit, d.content, d.deployed_at, d.dependencies, d.at_least_once "
            + "from deploy_pool p left join deployments d on true";
    private static final String TRY_LOCK = "select pg_try_advisory_lock(294, 0)";
    private static final String UNLOCK = "select pg_advisory_unlock(294, 0)";
    private static final String RAISE = "update deploy_pool set generation = generation + 1 where generation = $1 returning generation";
    private static final String SAVE = "insert into deployments (package, version, repository, commit, content, deployed_at, dependencies, at_least_once) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8) "
            + "on conflict (package) do update set version = excluded.version, repository = excluded.repository, commit = excluded.commit, "
            + "content = excluded.content, deployed_at = excluded.deployed_at, dependencies = excluded.dependencies, at_least_once = excluded.at_least_once";
    private static final String DELETE = "delete from deployments where package = $1";
    /** Marks each flow package $1's row runs at-least-once that is not in $2, with a copy of the row. */
    private static final String MARK = "insert into retiring (flow, package, version, repository, commit, content, dependencies, deployed_at, marked_at) "
            + "select f, d.package, d.version, d.repository, d.commit, d.content, d.dependencies, d.deployed_at, now() "
            + "from deployments d cross join unnest(d.at_least_once) as u(f) where d.package = $1 and not (f = any($2::text[])) on conflict (flow) do nothing";
    /** Ends the marks of the flows in $1: a new row runs them at-least-once again. */
    private static final String REVIVE = "delete from retiring where flow = any($1::text[])";
    private static final String MARKED = "select flow from retiring order by flow";
    private static final String TRY_FLOW = "select pg_try_advisory_xact_lock(295, hashtext($1))";
    private static final String END = "delete from retiring where flow = $1 and not exists (select 1 from work where flow = $1)";
    private static final String AUDIT = "insert into audit (at, actor, action, subject, detail, user_id, via, credential)"
            + " values (now(), $1, $2, $3, $4, $5, $6, $7)";

    /** Whether the writing node's row carries the epoch $2 with a lease not released; locked so that a join of the node waits. */
    private static final String OWN = "select epoch = $2 and lease_until is not null from nodes where node = $1 for key share";
    /** The rows of node $1, of every epoch, and those of every node whose epoch moved on or whose lease was released. */
    private static final String SWEEP = "delete from node_packages p where p.node = $1 or not exists "
            + "(select 1 from nodes n where n.node = p.node and n.epoch = p.epoch and n.lease_until is not null)";
    private static final String LIST = "insert into node_packages (node, epoch, package, version, repository, commit, deployed_at, state, behind) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8, $9)";
    private static final String SNAPSHOT = "set transaction isolation level repeatable read, read only";
    /** The records, as {@link #READ} reads them, one more than a read of the listings takes: so a pool that holds more shows. */
    private static final String RECORDS = READ + " limit " + (LISTINGS_MAX + 1);
    /** Each node whose lease holds by the database clock, with each of its rows of its current epoch; one row with no package for a node that wrote none. */
    private static final String LISTED = "select n.node, p.package, p.version, p.repository, p.commit, p.deployed_at, p.state, p.behind "
            + "from nodes n left join node_packages p on p.node = n.node and p.epoch = n.epoch where n.lease_until >= now() limit " + (LISTINGS_MAX + 1);

    /** The flows of package $1 marked to drain. */
    private static final String DRAINING = "select flow from retiring where package = $1";
    /** Marks package $1's record as one whose secret uses are known. */
    private static final String KNOWN = "update deployments set secret_uses_known = true where package = $1";
    /** Whether the record of package $1 with content $2 still has its secret uses to write; locked for the backfill's transaction. */
    private static final String UNKNOWN = "select not secret_uses_known from deployments where package = $1 and content = $2 for update";

    private static final String SYSTEMS = "select deployment, system, definition from deployment_systems order by system";
    private static final String CONNECTION_USES = "select deployment, flow, step, connection, adapter, direction, address, polled"
            + " from deployment_connection_uses order by deployment, flow, step, connection, direction";
    /** The configuration's generation, locked for share: an activation, which locks it for update, waits for the deploy's commit. */
    private static final String CONFIG_GENERATION = "select generation from config_store for share";
    private static final String REACHED = "select key, activation, stopped_at is not null as stopped from config_objects"
            + " where kind = 'connection' and key = any($1::text[]) and body is not null";
    private static final String NAMED = "select key from config_objects where kind = 'system' and key = any($1::text[]) and body is not null";
    private static final String CHECKED = "update deployments set config_generation = $2 where package = $1";
    /** The uses kept under the qualified ids of the flows in $1, which a new row runs at-least-once again. */
    private static final String USES_REVIVED = "delete from deployment_connection_uses where deployment = any($1::text[])";
    /** The uses of package $1's flows marked to retire move under each flow's qualified id, until its mark ends. */
    private static final String USES_RETIRE = "update deployment_connection_uses set deployment = flow"
            + " where deployment = $1 and flow in (select flow from retiring where package = $1)";
    private static final String USES_DELETE = "delete from deployment_connection_uses where deployment = $1";
    private static final String USE = "insert into deployment_connection_uses (deployment, flow, step, connection, adapter, direction, address, polled)"
            + " values ($1, $2, $3, $4, $5, $6, $7, $8)";
    private static final String SYSTEMS_DELETE = "delete from deployment_systems where deployment = $1";
    private static final String SYSTEM = "insert into deployment_systems (deployment, system, definition) values ($1, $2, $3)";

    private final Pool pool;
    private final Supplier<Future<SqlConnection>> connect;
    /** Where the record of secret uses is written; null: no record of uses is kept. */
    private final SecretStore secrets;

    /**
     * A store that keeps no record of secret uses.
     *
     * @param pool where the reads and the audit rows of the deploys that wrote no record go
     * @param connect opens a fresh connection for one holder of the pool's lock, its own to close
     */
    public PgDeploymentStore(Pool pool, Supplier<Future<SqlConnection>> connect) {
        this(pool, connect, null);
    }

    /**
     * As the constructor above, with the record of the secrets each deployed flow uses written through {@code secrets} in the
     * transaction of each save and delete.
     */
    public PgDeploymentStore(Pool pool, Supplier<Future<SqlConnection>> connect, SecretStore secrets) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.connect = Objects.requireNonNull(connect, "connect");
        this.secrets = secrets;
    }

    @Override
    public Future<Boolean> backfillUses(String name, String content, Map<String, Map<String, Set<Secret.Kind>>> uses) {
        if (secrets == null) return Future.succeededFuture(true);
        return connect.get().compose(conn -> conn.query(TRY_LOCK).execute().compose(taken -> {
            if (!taken.iterator().next().getBoolean(0)) return Future.succeededFuture(false);
            Future<Boolean> written = conn.begin().compose(tx -> conn.preparedQuery(UNKNOWN).execute(Tuple.of(name, content)).compose(rows -> {
                if (!rows.iterator().hasNext() || !rows.iterator().next().getBoolean(0)) return Future.<Void>succeededFuture();
                return knownUses(conn, name, uses);
            }).compose(done -> tx.commit().map(true), failed -> tx.rollback().transform(rolledBack -> Future.<Boolean>failedFuture(failed))));
            return written.eventually(() -> conn.query(UNLOCK).execute());
        }).eventually(conn::close));
    }

    /**
     * The record of {@code name}'s uses replaced, its flows marked to drain now keeping theirs as {@code drain} rows, and marked
     * known, in {@code conn}'s transaction; failed, writing nothing, on a problem.
     */
    private Future<Void> knownUses(SqlConnection conn, String name, Map<String, Map<String, Set<Secret.Kind>>> uses) {
        return replaced(conn, name, uses).compose(problems -> problems.isEmpty()
                ? conn.preparedQuery(KNOWN).execute(Tuple.of(name)).<Void>mapEmpty()
                : Future.failedFuture(new SecretUsesRefused(problems)));
    }

    /** {@link SecretStore#replacePackageUses} of {@code name} with {@code uses}, and the package's flows marked to drain now. */
    private Future<List<String>> replaced(SqlConnection conn, String name, Map<String, Map<String, Set<Secret.Kind>>> uses) {
        return conn.preparedQuery(DRAINING).execute(Tuple.of(name)).compose(rows -> {
            Set<String> draining = new TreeSet<>();
            rows.forEach(r -> draining.add(r.getString(0)));
            return secrets.replacePackageUses(conn, name, uses, draining);
        });
    }

    @Override
    public Future<Snapshot> read() {
        return pool.query(READ).execute().map(PgDeploymentStore::snapshot);
    }

    @Override
    public Future<List<DeployedSystem>> systems() {
        return pool.query(SYSTEMS).execute().map(rows -> {
            List<DeployedSystem> systems = new ArrayList<>();
            for (Row r : rows) systems.add(new DeployedSystem(r.getString("deployment"), r.getString("system"), (JsonObject) r.getValue("definition")));
            return systems;
        });
    }

    @Override
    public Future<List<ConnectionUse>> connectionUses() {
        return pool.query(CONNECTION_USES).execute().map(rows -> {
            List<ConnectionUse> uses = new ArrayList<>();
            for (Row r : rows) {
                uses.add(new ConnectionUse(r.getString("deployment"), r.getString("flow"), r.getString("step"), r.getString("connection"),
                        r.getString("adapter"), r.getString("direction"), r.getBoolean("address"), Optional.ofNullable(r.getString("polled"))));
            }
            return uses;
        });
    }

    @Override
    public Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages) {
        List<Tuple> rows = packages.stream().map(p -> Tuple.of(node, epoch, p.name(), p.version(), p.repository(), p.commit(),
                p.deployedAt().atOffset(ZoneOffset.UTC), p.state().name(), p.behind().toArray(String[]::new))).toList();
        return pool.withTransaction(tx -> tx.preparedQuery(OWN).execute(Tuple.of(node, epoch)).compose(own -> {
            if (!own.iterator().hasNext() || !own.iterator().next().getBoolean(0)) return Future.succeededFuture(false);
            Future<?> swept = tx.preparedQuery(SWEEP).execute(Tuple.of(node));
            return swept.compose(deleted -> rows.isEmpty() ? Future.succeededFuture() : tx.preparedQuery(LIST).executeBatch(rows)).map(true);
        }));
    }

    @Override
    public Future<Listings> listings() {
        return pool.withTransaction(tx -> tx.query(SNAPSHOT).execute().compose(set -> tx.query(RECORDS).execute())
                .compose(records -> tx.query(LISTED).execute().compose(rows -> listings(snapshot(records).records(), rows))));
    }

    /** The listings of {@code records} and the rows {@link #LISTED} read; failed, naming the limit, when they are more than it. */
    private static Future<Listings> listings(List<DeploymentRecord> records, RowSet<Row> rows) {
        if ((long) records.size() + rows.size() > LISTINGS_MAX) return Future.failedFuture(new IllegalStateException("the pool holds more than " + LISTINGS_MAX
                + " records and rows of what each node runs together, the most one read takes"));
        SortedMap<String, List<NodePackage>> nodes = new TreeMap<>();
        for (Row r : rows) {
            List<NodePackage> packages = nodes.computeIfAbsent(r.getString("node"), node -> new ArrayList<>());
            if (r.getString("package") == null) continue;   // a node that wrote none
            packages.add(new NodePackage(r.getString("package"), r.getString("version"), r.getString("repository"), r.getString("commit"),
                    r.getOffsetDateTime("deployed_at").toInstant(), DeployedPackage.State.valueOf(r.getString("state")), List.of(r.getArrayOfStrings("behind"))));
        }
        nodes.values().forEach(packages -> packages.sort(Comparator.comparing(NodePackage::name)));
        return Future.succeededFuture(new Listings(records, nodes));
    }

    @Override
    public Future<Optional<PoolLock>> lock() {
        return connect.get().compose(conn -> conn.query(TRY_LOCK).execute().compose(taken -> {
            if (!taken.iterator().next().getBoolean(0)) return conn.close().map(Optional.<PoolLock>empty());
            return conn.query(READ).execute().map(rows -> Optional.<PoolLock>of(new Held(conn, snapshot(rows))));
        }).recover(failed -> conn.close().transform(closed -> Future.failedFuture(failed))));
    }

    @Override
    public Future<Void> endMarks() {
        return pool.query(MARKED).execute().compose(marks -> {
            Future<Void> ended = Future.succeededFuture();
            for (Row mark : marks) {
                String flow = mark.getString(0);
                ended = ended.compose(n -> pool.withTransaction(tx -> tx.preparedQuery(TRY_FLOW).execute(Tuple.of(flow)).compose(locked -> {
                    if (!locked.iterator().next().getBoolean(0)) return Future.<Void>succeededFuture();   // a write of a message for it is open
                    return tx.preparedQuery(END).execute(Tuple.of(flow)).compose(deleted -> {
                        if (deleted.rowCount() == 0) return Future.<Void>succeededFuture();
                        Future<Void> uses = tx.preparedQuery(USES_DELETE).execute(Tuple.of(flow)).mapEmpty();
                        return secrets == null ? uses : uses.compose(gone -> secrets.endDrain(tx, flow));
                    });
                })));
            }
            return ended;
        });
    }

    @Override
    public Future<Void> audit(Actor by, String repository, JsonObject detail) {
        return pool.preparedQuery(AUDIT).execute(audit(by, "deploy", "repository/" + repository, detail)).mapEmpty();
    }

    /** The parameters of {@link #AUDIT}: who acted, the action, its subject and its detail. */
    private static Tuple audit(Actor by, String action, String subject, JsonObject detail) {
        return Tuple.of(by.name(), action, subject, detail, by.userOrNull(), by.via(), by.credential().orElse(null));
    }

    /** The generation of a {@link #READ} and its records, sorted by name; no row means no {@code deploy_pool} row: a failure. */
    private static Snapshot snapshot(RowSet<Row> rows) {
        Long generation = null;
        List<String> marked = List.of();
        List<DeploymentRecord> records = new ArrayList<>();
        for (Row r : rows) {
            generation = r.getLong("generation");
            marked = List.of(r.getArrayOfStrings("marked"));
            if (r.getString("package") == null) continue;   // the pool's row alone: no record
            List<DeploymentRecord.Pin> pins = new ArrayList<>();
            for (Object o : r.getJsonArray("dependencies")) {
                JsonObject p = (JsonObject) o;
                pins.add(new DeploymentRecord.Pin(p.getString("name"), p.getString("version"), p.getString("content"), p.getString("repository"),
                        p.getString("commit")));
            }
            records.add(new DeploymentRecord(r.getString("package"), r.getString("version"), r.getString("repository"), r.getString("commit"),
                    r.getString("content"), r.getOffsetDateTime("deployed_at").toInstant(), pins, List.of(r.getArrayOfStrings("at_least_once"))));
        }
        if (generation == null) throw new IllegalStateException("the table deploy_pool has no row: the database is not migrated to V27");
        records.sort(Comparator.comparing(DeploymentRecord::name));
        return new Snapshot(generation, records, marked);
    }

    /** The pool's lock on its own connection: each write one transaction on it. */
    private final class Held implements PoolLock {
        private final SqlConnection conn;
        private final Snapshot snapshot;
        /** The generation the next write raises: the one read, then the one the last write committed. */
        private volatile long generation;

        Held(SqlConnection conn, Snapshot snapshot) {
            this.conn = conn;
            this.snapshot = snapshot;
            this.generation = snapshot.generation();
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public Future<Long> save(DeploymentRecord r, Actor by, JsonObject detail) {
            return save(r, null, by, detail);
        }

        @Override
        public Future<Long> save(DeploymentRecord r, Map<String, Map<String, Set<Secret.Kind>>> uses, Actor by, JsonObject detail) {
            return save(r, uses, null, by, detail);
        }

        @Override
        public Future<Long> save(DeploymentRecord r, Map<String, Map<String, Set<Secret.Kind>>> uses, Configuration configuration, Actor by,
                                 JsonObject detail) {
            JsonArray pins = new JsonArray();
            for (DeploymentRecord.Pin p : r.dependencies()) {
                pins.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("content", p.content()).put("repository", p.repository())
                        .put("commit", p.commit()));
            }
            String[] runs = r.atLeastOnce().toArray(String[]::new);
            return write(List.of(Map.entry(MARK, Tuple.of(r.name(), (Object) runs)), Map.entry(REVIVE, Tuple.of((Object) runs)),
                    Map.entry(SAVE, Tuple.of(r.name(), r.version(), r.repository(), r.commit(), r.content(), r.deployedAt().atOffset(ZoneOffset.UTC), pins, runs)),
                    Map.entry(AUDIT, audit(by, "deploy", "repository/" + r.repository(), detail))),
                    conn -> {
                        Future<Void> written = configuration == null ? Future.succeededFuture() : configuration(conn, r, configuration);
                        return secrets == null || uses == null ? written : written.compose(done -> knownUses(conn, r.name(), uses));
                    });
        }

        /**
         * The package's systems and connection uses replaced and its generation written, under the configuration's lock for share;
         * when the configuration's generation moved since the check, each reached connection and system compared again first.
         */
        private Future<Void> configuration(SqlConnection conn, DeploymentRecord r, Configuration c) {
            String[] runs = r.atLeastOnce().toArray(String[]::new);
            return conn.query(CONFIG_GENERATION).execute().compose(head -> {
                long now = head.iterator().next().getLong(0);
                return now == c.generation() ? Future.<Void>succeededFuture() : compared(conn, c);
            }).compose(same -> conn.preparedQuery(CHECKED).execute(Tuple.of(r.name(), c.generation())))
                    .compose(done -> conn.preparedQuery(USES_REVIVED).execute(Tuple.of((Object) runs)))
                    .compose(done -> conn.preparedQuery(USES_RETIRE).execute(Tuple.of(r.name())))
                    .compose(done -> conn.preparedQuery(USES_DELETE).execute(Tuple.of(r.name())))
                    .compose(done -> conn.preparedQuery(SYSTEMS_DELETE).execute(Tuple.of(r.name())))
                    .compose(done -> c.systems().isEmpty() ? Future.<Void>succeededFuture() : conn.preparedQuery(SYSTEM).executeBatch(c.systems().stream()
                            .map(sys -> Tuple.of(r.name(), sys.key(), sys.definition())).toList()).mapEmpty())
                    .compose(done -> c.uses().isEmpty() ? Future.<Void>succeededFuture() : conn.preparedQuery(USE).executeBatch(c.uses().stream()
                            .map(u -> Tuple.of(r.name(), u.flow(), u.step(), u.connection(), u.adapter(), u.direction(), u.address(), u.polled().orElse(null)))
                            .toList()).mapEmpty());
        }

        /** Fails with {@link ConfigurationChanged} when a reached connection or system is not what the check read. */
        private Future<Void> compared(SqlConnection conn, Configuration c) {
            String[] keys = c.connections().keySet().toArray(String[]::new);
            String[] systems = c.named().keySet().toArray(String[]::new);
            return conn.preparedQuery(REACHED).execute(Tuple.of((Object) keys)).compose(reached -> conn.preparedQuery(NAMED).execute(Tuple.of((Object) systems))
                    .compose(named -> {
                        Map<String, Reached> now = new TreeMap<>();
                        for (Row row : reached) now.put(row.getString("key"), new Reached(row.getLong("activation"), row.getBoolean("stopped")));
                        Set<String> inForce = new TreeSet<>();
                        for (Row row : named) inForce.add(row.getString("key"));
                        List<String> changed = new ArrayList<>();
                        new TreeMap<>(c.connections()).forEach((key, read) -> {
                            if (!read.equals(now.get(key))) changed.add("connection '" + key + "' changed while the deploy checked it: deploy again");
                        });
                        new TreeMap<>(c.named()).forEach((key, had) -> {
                            if (had != inForce.contains(key)) changed.add("system '" + key + "' changed while the deploy checked it: deploy again");
                        });
                        return changed.isEmpty() ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(new ConfigurationChanged(changed));
                    }));
        }

        @Override
        public Future<Long> delete(String name, Actor by, JsonObject detail) {
            return write(List.of(Map.entry(MARK, Tuple.of(name, (Object) new String[0])), Map.entry(DELETE, Tuple.of(name)),
                    Map.entry(USES_RETIRE, Tuple.of(name)), Map.entry(USES_DELETE, Tuple.of(name)), Map.entry(SYSTEMS_DELETE, Tuple.of(name)),
                    Map.entry(AUDIT, audit(by, "undeploy", "package/" + name, detail))),
                    secrets == null ? null : conn -> replaced(conn, name, Map.of()).<Void>mapEmpty());
        }

        /**
         * Each of {@code statements}, in order, then {@code last} when given, with the generation raised, in one transaction; a
         * generation that moved writes nothing, and neither does a lock that was freed: its connection is closed, and the client's
         * throw is the write's failure; nor does a {@code last} that fails.
         */
        private Future<Long> write(List<Map.Entry<String, Tuple>> statements, Function<SqlConnection, Future<Void>> last) {
            long expected = generation;
            return Future.<Void>succeededFuture().compose(start -> conn.begin()).compose(tx -> conn.preparedQuery(RAISE).execute(Tuple.of(expected)).compose(raised -> {
                if (raised.rowCount() == 0) {
                    return Future.<Long>failedFuture(new IllegalStateException("the pool's generation is no longer " + expected));
                }
                long next = raised.iterator().next().getLong(0);
                Future<Void> done = Future.succeededFuture();
                for (Map.Entry<String, Tuple> st : statements) done = done.compose(previous -> conn.preparedQuery(st.getKey()).execute(st.getValue()).mapEmpty());
                if (last != null) done = done.compose(previous -> last.apply(conn));
                return done.map(next);
            }).compose(next -> tx.commit().map(next), failed -> tx.rollback().transform(rolledBack -> Future.<Long>failedFuture(failed))))
                    .onSuccess(next -> generation = next);
        }

        @Override
        public Future<Void> close() {
            return Future.<Void>succeededFuture().compose(start -> conn.query(UNLOCK).execute()).transform(unlocked -> conn.close()).otherwiseEmpty();
        }
    }
}
