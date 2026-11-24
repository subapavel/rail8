package dev.rail8.runtime.access;

import dev.rail8.runtime.pg.PgTx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * {@link Access} in Postgres: the tables {@code users}, {@code role_assignments}, {@code sessions}, {@code tokens},
 * {@code token_ceiling}, {@code login_limits} and {@code admin_bridge}, and the {@code audit}. Every request is authenticated from Postgres and nothing is cached, so a block, a
 * logout or a role taken away holds on every node of the pool for every request admitted after it commits. The changes that
 * can lower the number of administrators take the pool's transaction advisory lock {@code (296, 0)} first.
 */
public final class PgAccess extends Accounts {

    private static final String USER_COLUMNS = "u.id, u.name, u.kind, u.password, u.first_password, u.password_set_at, u.generation,"
            + " u.created_at, u.created_by, u.blocked_at";
    private static final String SESSION_COLUMNS = "s.id, s.user_id, encode(s.fingerprint, 'hex') as fingerprint, s.first_password,"
            + " s.created_at, s.last_seen_at, s.expires_at";
    private static final String TOKEN_COLUMNS = "t.id as t_id, t.user_id as t_user_id, t.name as t_name,"
            + " encode(t.fingerprint, 'hex') as t_fingerprint, t.created_at as t_created_at, t.created_by as t_created_by,"
            + " t.expires_at as t_expires_at, t.last_used_at as t_last_used_at, t.revoked_at as t_revoked_at";
    private static final String CLAIM = "insert into login_limits as l (name, device, failures, last_failure_at) values ($1, $2, 1, $3)"
            + " on conflict (name, device) do update"
            + " set failures = least(l.failures + 1, 1000000), last_failure_at = greatest(l.last_failure_at, $3)"
            + " where l.failures < 5"
            + " or l.last_failure_at + least(power(2, least(l.failures, 14) - 5), 300) * interval '1 second' <= $3"
            + " returning failures";
    private static final String COUNT_REFUSAL = "insert into login_limits as l (name, device, failures, last_failure_at)"
            + " values ('*', '*', 1, $1) on conflict (name, device) do update"
            + " set failures = case when l.last_failure_at < $1 then 1 else least(l.failures + 1, 1000000) end,"
            + " last_failure_at = greatest(l.last_failure_at, $1) returning failures";

    private final Pool pool;

    /**
     * @param pool the node's Postgres pool
     * @param clock the node's clock, for every instant these tables keep
     * @param hash the hash of passwords
     * @param places the node's places to hash
     * @param random the source of ids, secrets, salts and first passwords
     * @param adminToken the node's {@code RAIL8_ADMIN_TOKEN}: the emergency key in its form, the shared token otherwise
     */
    public PgAccess(Pool pool, Clock clock, PasswordHash hash, HashPlaces places, RandomGenerator random, Optional<String> adminToken) {
        super(clock, hash, places, random, adminToken);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    protected <T> T query(Function<Tx, T> work) {
        return work.apply(new Statements(pool));
    }

    @Override
    protected <T> T transaction(Function<Tx, T> work) {
        SqlConnection conn = PgTx.acquire(pool).await();
        try {
            Transaction tx = conn.begin().await();
            T result;
            try {
                result = work.apply(new Statements(conn));
            } catch (RuntimeException failed) {
                tx.rollback().otherwiseEmpty().await();
                throw failed;
            }
            tx.commit().await();
            return result;
        } finally {
            conn.close();
        }
    }

    /** The statements, on the pool (each on its own) or on one connection in a transaction. */
    private record Statements(SqlClient sql) implements Tx {

        private RowSet<Row> run(String statement, Tuple args) {
            return sql.preparedQuery(statement).execute(args).await();
        }

        private static OffsetDateTime at(Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }

        private static Instant instant(Row r, String column) {
            return r.getOffsetDateTime(column).toInstant();
        }

        private static Optional<Instant> maybe(Row r, String column) {
            return Optional.ofNullable(r.getOffsetDateTime(column)).map(OffsetDateTime::toInstant);
        }

        private static UserRow user(Row r) {
            return new UserRow(r.getLong("id"), r.getString("name"), Kind.ofAccount(r.getString("kind")).orElseThrow(),
                    Optional.ofNullable(r.getString("password")), r.getBoolean("first_password"), maybe(r, "password_set_at"),
                    r.getLong("generation"), instant(r, "created_at"), r.getString("created_by"), maybe(r, "blocked_at"));
        }

        private static SessionRow session(Row r) {
            return new SessionRow(r.getString("id"), r.getLong("user_id"), r.getString("fingerprint"), r.getBoolean("first_password"),
                    instant(r, "created_at"), instant(r, "last_seen_at"), instant(r, "expires_at"));
        }

        private static TokenRow token(Row r) {
            return new TokenRow(r.getString("t_id"), r.getLong("t_user_id"), r.getString("t_name"), r.getString("t_fingerprint"),
                    instant(r, "t_created_at"), r.getString("t_created_by"), instant(r, "t_expires_at"), maybe(r, "t_last_used_at"),
                    maybe(r, "t_revoked_at"));
        }

        private static Set<Role> roles(Row r) {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String role : r.getArrayOfStrings("roles")) roles.add(Role.byId(role).orElseThrow());
            return roles;
        }

        private static <T> Optional<T> first(RowSet<Row> rows, Function<Row, T> map) {
            var it = rows.iterator();
            return it.hasNext() ? Optional.of(map.apply(it.next())) : Optional.empty();
        }

        private static String lock(Lock lock) {
            return switch (lock) {
                case NONE -> "";
                case SHARE -> " for share";
                case UPDATE -> " for update";
            };
        }

        @Override public boolean bridgeOpen() {
            return first(run("select open from admin_bridge", Tuple.tuple()), r -> r.getBoolean(0)).orElse(false);
        }

        @Override public Optional<UserRow> user(String name, Lock lock) {
            return first(run("select " + USER_COLUMNS + " from users u where u.name = $1" + lock(lock), Tuple.of(name)), Statements::user);
        }

        @Override public Optional<UserRow> user(long id, Lock lock) {
            return first(run("select " + USER_COLUMNS + " from users u where u.id = $1" + lock(lock), Tuple.of(id)), Statements::user);
        }

        @Override public List<UserRow> users() {
            List<UserRow> out = new ArrayList<>();
            run("select " + USER_COLUMNS + " from users u order by u.name", Tuple.tuple()).forEach(r -> out.add(user(r)));
            return out;
        }

        @Override public long userCount() {
            return run("select count(*) from users", Tuple.tuple()).iterator().next().getLong(0);
        }

        @Override public List<Access.Grant> grants(long user) {
            List<Access.Grant> out = new ArrayList<>();
            run("select role, granted_at, granted_by, granted_via from role_assignments where user_id = $1 and scope = 'installation'",
                    Tuple.of(user)).forEach(r -> out.add(new Access.Grant(Role.byId(r.getString("role")).orElseThrow(), instant(r, "granted_at"),
                    r.getString("granted_by"), r.getString("granted_via"))));
            out.sort(Comparator.comparing(Access.Grant::role));
            return out;
        }

        @Override public long unblockedAdministrators() {
            return run("select count(*) from users u join role_assignments r on r.user_id = u.id where r.role = 'administrator'"
                    + " and r.scope = 'installation' and u.kind = 'person' and u.blocked_at is null", Tuple.tuple()).iterator().next().getLong(0);
        }

        @Override public void lockAdministrators() {
            run("select pg_advisory_xact_lock(296, 0)", Tuple.tuple());
        }

        @Override public OptionalLong insertUser(String name, Kind kind, Optional<String> password, boolean firstPassword,
                                                 Optional<Instant> passwordSetAt, Instant createdAt, String createdBy) {
            RowSet<Row> rows = run("insert into users (name, kind, source, password, first_password, password_set_at, created_at, created_by)"
                    + " values ($1, $2, 'rail8', $3, $4, $5, $6, $7) on conflict (name) do nothing returning id",
                    Tuple.of(name, kind.id(), password.orElse(null), firstPassword, passwordSetAt.map(Statements::at).orElse(null),
                            at(createdAt), createdBy));
            return first(rows, r -> r.getLong(0)).map(OptionalLong::of).orElse(OptionalLong.empty());
        }

        @Override public void grant(long user, Role role, Instant at, String by, String via) {
            run("insert into role_assignments (user_id, role, scope, granted_at, granted_by, granted_via)"
                    + " values ($1, $2, 'installation', $3, $4, $5) on conflict do nothing", Tuple.of(user, role.id(), at(at), by, via));
        }

        @Override public void revoke(long user, Role role) {
            run("delete from role_assignments where user_id = $1 and role = $2 and scope = 'installation'", Tuple.of(user, role.id()));
        }

        @Override public void password(long user, String stored, boolean first, Instant at) {
            run("update users set password = $2, first_password = $3, password_set_at = $4, generation = generation + 1 where id = $1",
                    Tuple.of(user, stored, first, at(at)));
        }

        @Override public void rehash(long user, String old, String stored) {
            run("update users set password = $3 where id = $1 and password = $2", Tuple.of(user, old, stored));
        }

        @Override public void blocked(long user, Optional<Instant> at) {
            if (at.isPresent()) run("update users set blocked_at = $2, generation = generation + 1 where id = $1", Tuple.of(user, at(at.get())));
            else run("update users set blocked_at = null where id = $1", Tuple.of(user));
        }

        @Override public void raiseGeneration(long user) {
            run("update users set generation = generation + 1 where id = $1", Tuple.of(user));
        }

        @Override public Optional<SessionOfUser> session(String id) {
            return first(run("select " + SESSION_COLUMNS + ", u.id as uid, u.name, u.kind, u.password, u.first_password as user_first_password,"
                    + " u.password_set_at, u.generation, u.created_at as user_created_at, u.created_by, u.blocked_at,"
                    + " array(select r.role from role_assignments r where r.user_id = u.id and r.scope = 'installation') as roles"
                    + " from sessions s join users u on u.id = s.user_id where s.id = $1", Tuple.of(id)), r -> {
                Set<Role> roles = roles(r);
                UserRow user = new UserRow(r.getLong("uid"), r.getString("name"), Kind.ofAccount(r.getString("kind")).orElseThrow(),
                        Optional.ofNullable(r.getString("password")), r.getBoolean("user_first_password"), maybe(r, "password_set_at"),
                        r.getLong("generation"), instant(r, "user_created_at"), r.getString("created_by"), maybe(r, "blocked_at"));
                return new SessionOfUser(session(r), user, roles);
            });
        }

        @Override public List<SessionRow> sessions(long user) {
            List<SessionRow> out = new ArrayList<>();
            run("select " + SESSION_COLUMNS + " from sessions s where s.user_id = $1", Tuple.of(user)).forEach(r -> out.add(session(r)));
            return out;
        }

        @Override public void insertSession(SessionRow row) {
            run("insert into sessions (id, user_id, fingerprint, first_password, created_at, last_seen_at, expires_at)"
                    + " values ($1, $2, decode($3, 'hex'), $4, $5, $6, $7)", Tuple.of(row.id(), row.user(), row.fingerprint(), row.firstPassword(),
                    at(row.createdAt()), at(row.lastSeenAt()), at(row.expiresAt())));
        }

        @Override public int endSessions(long user, Optional<String> except) {
            return run("delete from sessions where user_id = $1 and ($2::text is null or id <> $2)", Tuple.of(user, except.orElse(null)))
                    .rowCount();
        }

        @Override public boolean endSession(String id) {
            return run("delete from sessions where id = $1", Tuple.of(id)).rowCount() > 0;
        }

        @Override public void touch(String id, Instant at) {
            run("update sessions set last_seen_at = $2 where id = $1 and last_seen_at < $2", Tuple.of(id, at(at)));
        }

        @Override public void passwordChanged(String id) {
            run("update sessions set first_password = false where id = $1", Tuple.of(id));
        }

        @Override public Optional<TokenOfUser> token(String id) {
            return first(run("select " + USER_COLUMNS + ", " + TOKEN_COLUMNS + ","
                    + " array(select r.role from role_assignments r where r.user_id = u.id and r.scope = 'installation') as roles"
                    + " from tokens t join users u on u.id = t.user_id where t.id = $1", Tuple.of(id)),
                    r -> new TokenOfUser(token(r), user(r), roles(r)));
        }

        @Override public List<TokenRow> unrevokedTokens(long user) {
            List<TokenRow> out = new ArrayList<>();
            run("select " + TOKEN_COLUMNS + " from tokens t where t.user_id = $1 and t.revoked_at is null", Tuple.of(user))
                    .forEach(r -> out.add(token(r)));
            return out;
        }

        @Override public List<Access.Token> liveTokens(OptionalLong user, Instant now, Optional<Instant> until) {
            List<Access.Token> out = new ArrayList<>();
            run("select t.id, u.name as user_name, t.name, t.created_at, t.created_by, t.expires_at, t.last_used_at"
                    + " from tokens t join users u on u.id = t.user_id"
                    + " where t.revoked_at is null and t.expires_at > $1 and ($2::bigint is null or t.user_id = $2)"
                    + " and ($3::timestamptz is null or t.expires_at <= $3) order by t.expires_at, t.id",
                    Tuple.of(at(now), user.isPresent() ? user.getAsLong() : null, until.map(Statements::at).orElse(null)))
                    .forEach(r -> out.add(new Access.Token(r.getString("id"), r.getString("user_name"), r.getString("name"),
                            instant(r, "created_at"), r.getString("created_by"), instant(r, "expires_at"), maybe(r, "last_used_at"))));
            return out;
        }

        @Override public void insertToken(TokenRow row) {
            run("insert into tokens (id, user_id, name, fingerprint, created_at, created_by, expires_at)"
                    + " values ($1, $2, $3, decode($4, 'hex'), $5, $6, $7)", Tuple.of(row.id(), row.user(), row.name(), row.fingerprint(),
                    at(row.createdAt()), row.createdBy(), at(row.expiresAt())));
        }

        @Override public boolean revokeToken(String id, Instant at, String by) {
            return run("update tokens set revoked_at = $2, revoked_by = $3 where id = $1 and revoked_at is null", Tuple.of(id, at(at), by))
                    .rowCount() > 0;
        }

        @Override public void used(String id, Instant at, Instant before) {
            run("update tokens set last_used_at = $2 where id = $1 and (last_used_at is null or last_used_at <= $3)",
                    Tuple.of(id, at(at), at(before)));
        }

        @Override public int ceiling(Lock lock) {
            return first(run("select max_days from token_ceiling" + lock(lock), Tuple.tuple()), r -> r.getInteger(0))
                    .orElseThrow(() -> new IllegalStateException("token_ceiling has no row: the migration's row was deleted"));
        }

        @Override public void setCeiling(int maxDays) {
            run("update token_ceiling set max_days = $1", Tuple.of(maxDays));
        }

        @Override public int shortenTokens(int maxDays) {
            return run("update tokens set expires_at = created_at + make_interval(hours => $1)"
                    + " where revoked_at is null and expires_at > created_at + make_interval(hours => $1)", Tuple.of(maxDays * 24)).rowCount();
        }

        @Override public OptionalInt claim(String name, String device, Instant at) {
            return first(run(CLAIM, Tuple.of(name, device, at(at))), r -> r.getInteger(0)).map(OptionalInt::of).orElse(OptionalInt.empty());
        }

        @Override public Optional<Limit> limit(String name, String device, boolean forUpdate) {
            return first(run("select failures, last_failure_at, audited_at from login_limits where name = $1 and device = $2"
                    + (forUpdate ? " for update" : ""), Tuple.of(name, device)),
                    r -> new Limit(r.getInteger("failures"), instant(r, "last_failure_at"), maybe(r, "audited_at")));
        }

        @Override public void forgive(String name, String device, int claimed) {
            run("update login_limits set failures = greatest(failures - $3, 0) where name = $1 and device = $2", Tuple.of(name, device, claimed));
        }

        @Override public long limitedNames() {
            return run("select count(*) from login_limits where device = ''", Tuple.tuple()).iterator().next().getLong(0);
        }

        @Override public void forgetLimits(String name) {
            run("delete from login_limits where name = $1", Tuple.of(name));
        }

        @Override public void limitAudited(String name, String device, Instant at) {
            run("update login_limits set audited_at = $3 where name = $1 and device = $2", Tuple.of(name, device, at(at)));
        }

        @Override public int countRefusalAudit(Instant hour) {
            return run(COUNT_REFUSAL, Tuple.of(at(hour))).iterator().next().getInteger(0);
        }

        @Override public void audit(AuditRow row) {
            run("insert into audit (at, actor, action, subject, detail, user_id, via, credential) values ($1, $2, $3, $4, $5, $6, $7, $8)",
                    Tuple.of(at(row.at()), row.actor(), row.action(), row.subject(), row.detail(),
                            row.user().isPresent() ? row.user().getAsLong() : null, row.via(), row.credential().orElse(null)));
        }
    }
}
