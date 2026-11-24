package dev.rail8.runtime.access;

import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * {@link Access} in memory, for tests: the same rules as {@link PgAccess} over a {@link Store} that several of them may share,
 * as the nodes of a pool share Postgres. A transaction runs on a copy of the store under its lock and replaces it only when it
 * returns.
 */
public final class MemoryAccess extends Accounts {

    private final Store store;
    private final Optional<Caller> fixed;

    /**
     * An access over {@code store}.
     *
     * @param adminToken the node's {@code RAIL8_ADMIN_TOKEN}: the emergency key in its form, the shared token otherwise
     */
    public MemoryAccess(Store store, Clock clock, PasswordHash hash, HashPlaces places, RandomGenerator random, Optional<String> adminToken) {
        this(store, clock, hash, places, random, adminToken, Optional.empty());
    }

    private MemoryAccess(Store store, Clock clock, PasswordHash hash, HashPlaces places, RandomGenerator random, Optional<String> adminToken,
                         Optional<Caller> fixed) {
        super(clock, hash, places, random, adminToken);
        this.store = Objects.requireNonNull(store, "store");
        this.fixed = fixed;
    }

    /** An access over a new store with the bridge open and no user: a node of today's admin API with {@code adminToken}. */
    public static MemoryAccess bridge(Optional<String> adminToken) {
        return new MemoryAccess(new Store(), Clock.systemUTC(), new Argon2id(new SecureRandom()), new HashPlaces(), new SecureRandom(),
                adminToken);
    }

    static MemoryAccess fixed(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        return new MemoryAccess(new Store(), Clock.systemUTC(), new Argon2id(new SecureRandom()), new HashPlaces(), new SecureRandom(),
                Optional.empty(), Optional.of(caller));
    }

    @Override
    public Caller authenticate(Optional<String> authorization, Optional<String> declaredActor) {
        return fixed.isPresent() ? fixed.get() : super.authenticate(authorization, declaredActor);
    }

    /** The store this access keeps its users in. */
    public Store store() {
        return store;
    }

    @Override
    protected <T> T query(Function<Tx, T> work) {
        return transaction(work);
    }

    @Override
    protected <T> T transaction(Function<Tx, T> work) {
        store.lock.lock();
        try {
            State copy = store.state.copy();
            T result = work.apply(copy);
            store.state = copy;
            return result;
        } finally {
            store.lock.unlock();
        }
    }

    /** The users, sessions, tokens, login limits, bridge marker and audit of one installation, in memory. */
    public static final class Store {

        private final ReentrantLock lock = new ReentrantLock();
        private State state = new State();

        /** Closes the bridge's marker, as the cut does. */
        public void closeBridge() {
            lock.lock();
            try {
                state.bridgeOpen = false;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Every audit row written, oldest first, each as {@code {at, actor, action, subject, detail, user, via, credential}}.
         */
        public List<JsonObject> audit() {
            lock.lock();
            try {
                return state.audit.stream().map(a -> new JsonObject().put("at", a.at().toString()).put("actor", a.actor())
                        .put("action", a.action()).put("subject", a.subject()).put("detail", a.detail().copy())
                        .put("user", a.user().isPresent() ? a.user().getAsLong() : null).put("via", a.via())
                        .put("credential", a.credential().orElse(null))).toList();
            } finally {
                lock.unlock();
            }
        }
    }

    /** One copy of the store's tables. */
    private static final class State implements Tx {

        private boolean bridgeOpen = true;
        private long nextUser = 1;
        private final Map<Long, UserRow> users = new HashMap<>();
        private final Map<Long, Map<Role, Access.Grant>> grants = new HashMap<>();
        private final Map<String, SessionRow> sessions = new HashMap<>();
        private final Map<String, TokenRow> tokens = new HashMap<>();
        private int ceiling = 365;
        private final Map<List<String>, Limit> limits = new HashMap<>();
        private final List<AuditRow> audit = new ArrayList<>();

        State copy() {
            State c = new State();
            c.bridgeOpen = bridgeOpen;
            c.nextUser = nextUser;
            c.users.putAll(users);
            grants.forEach((user, held) -> c.grants.put(user, new EnumMap<>(held)));
            c.sessions.putAll(sessions);
            c.tokens.putAll(tokens);
            c.ceiling = ceiling;
            c.limits.putAll(limits);
            c.audit.addAll(audit);
            return c;
        }

        private UserRow existing(long id) {
            return Objects.requireNonNull(users.get(id), "user " + id);
        }

        private void put(UserRow u) {
            users.put(u.id(), u);
        }

        @Override public boolean bridgeOpen() {
            return bridgeOpen;
        }

        @Override public Optional<UserRow> user(String name, Lock lock) {
            return users.values().stream().filter(u -> u.name().equals(name)).findFirst();
        }

        @Override public Optional<UserRow> user(long id, Lock lock) {
            return Optional.ofNullable(users.get(id));
        }

        @Override public List<UserRow> users() {
            return users.values().stream().sorted(Comparator.comparing(UserRow::name)).toList();
        }

        @Override public long userCount() {
            return users.size();
        }

        @Override public List<Access.Grant> grants(long user) {
            return new ArrayList<>(new TreeMap<>(grants.getOrDefault(user, Map.of())).values());
        }

        @Override public long unblockedAdministrators() {
            return users.values().stream().filter(u -> u.kind() == Kind.PERSON && u.blockedAt().isEmpty()
                    && grants.getOrDefault(u.id(), Map.of()).containsKey(Role.ADMINISTRATOR)).count();
        }

        @Override public void lockAdministrators() {
            // the store's lock already serializes every transaction
        }

        @Override public OptionalLong insertUser(String name, Kind kind, Optional<String> password, boolean firstPassword,
                                                 Optional<Instant> passwordSetAt, Instant createdAt, String createdBy) {
            if (user(name, Lock.NONE).isPresent()) return OptionalLong.empty();
            long id = nextUser++;
            put(new UserRow(id, name, kind, password, firstPassword, passwordSetAt, 0, createdAt, createdBy, Optional.empty()));
            return OptionalLong.of(id);
        }

        @Override public void grant(long user, Role role, Instant at, String by, String via) {
            grants.computeIfAbsent(user, u -> new EnumMap<>(Role.class)).putIfAbsent(role, new Access.Grant(role, at, by, via));
        }

        @Override public void revoke(long user, Role role) {
            grants.getOrDefault(user, new EnumMap<>(Role.class)).remove(role);
        }

        @Override public void password(long user, String stored, boolean first, Instant at) {
            UserRow u = existing(user);
            put(new UserRow(u.id(), u.name(), u.kind(), Optional.of(stored), first, Optional.of(at), u.generation() + 1, u.createdAt(),
                    u.createdBy(), u.blockedAt()));
        }

        @Override public void rehash(long user, String old, String stored) {
            UserRow u = existing(user);
            if (!u.password().equals(Optional.of(old))) return;
            put(new UserRow(u.id(), u.name(), u.kind(), Optional.of(stored), u.firstPassword(), u.passwordSetAt(), u.generation(),
                    u.createdAt(), u.createdBy(), u.blockedAt()));
        }

        @Override public void blocked(long user, Optional<Instant> at) {
            UserRow u = existing(user);
            put(new UserRow(u.id(), u.name(), u.kind(), u.password(), u.firstPassword(), u.passwordSetAt(),
                    u.generation() + (at.isPresent() ? 1 : 0), u.createdAt(), u.createdBy(), at));
        }

        @Override public void raiseGeneration(long user) {
            UserRow u = existing(user);
            put(new UserRow(u.id(), u.name(), u.kind(), u.password(), u.firstPassword(), u.passwordSetAt(), u.generation() + 1,
                    u.createdAt(), u.createdBy(), u.blockedAt()));
        }

        @Override public Optional<SessionOfUser> session(String id) {
            SessionRow s = sessions.get(id);
            if (s == null) return Optional.empty();
            Set<Role> roles = EnumSet.noneOf(Role.class);
            roles.addAll(grants.getOrDefault(s.user(), Map.of()).keySet());
            return Optional.of(new SessionOfUser(s, existing(s.user()), roles));
        }

        @Override public List<SessionRow> sessions(long user) {
            return sessions.values().stream().filter(s -> s.user() == user).toList();
        }

        @Override public void insertSession(SessionRow row) {
            sessions.put(row.id(), row);
        }

        @Override public int endSessions(long user, Optional<String> except) {
            List<String> ended = sessions.values().stream().filter(s -> s.user() == user && !except.equals(Optional.of(s.id())))
                    .map(SessionRow::id).toList();
            ended.forEach(sessions::remove);
            return ended.size();
        }

        @Override public boolean endSession(String id) {
            return sessions.remove(id) != null;
        }

        @Override public void touch(String id, Instant at) {
            SessionRow s = sessions.get(id);
            if (s == null || !s.lastSeenAt().isBefore(at)) return;
            sessions.put(id, new SessionRow(s.id(), s.user(), s.fingerprint(), s.firstPassword(), s.createdAt(), at, s.expiresAt()));
        }

        @Override public void passwordChanged(String id) {
            SessionRow s = sessions.get(id);
            if (s == null) return;
            sessions.put(id, new SessionRow(s.id(), s.user(), s.fingerprint(), false, s.createdAt(), s.lastSeenAt(), s.expiresAt()));
        }

        @Override public Optional<TokenOfUser> token(String id) {
            TokenRow t = tokens.get(id);
            if (t == null) return Optional.empty();
            Set<Role> roles = EnumSet.noneOf(Role.class);
            roles.addAll(grants.getOrDefault(t.user(), Map.of()).keySet());
            return Optional.of(new TokenOfUser(t, existing(t.user()), roles));
        }

        @Override public List<TokenRow> unrevokedTokens(long user) {
            return tokens.values().stream().filter(t -> t.user() == user && t.revokedAt().isEmpty()).toList();
        }

        @Override public List<Access.Token> liveTokens(OptionalLong user, Instant now, Optional<Instant> until) {
            return tokens.values().stream()
                    .filter(t -> t.revokedAt().isEmpty() && t.expiresAt().isAfter(now) && (user.isEmpty() || user.getAsLong() == t.user())
                            && until.map(u -> !t.expiresAt().isAfter(u)).orElse(true))
                    .sorted(Comparator.comparing(TokenRow::expiresAt).thenComparing(TokenRow::id))
                    .map(t -> new Access.Token(t.id(), existing(t.user()).name(), t.name(), t.createdAt(), t.createdBy(), t.expiresAt(),
                            t.lastUsedAt())).toList();
        }

        @Override public void insertToken(TokenRow row) {
            tokens.put(row.id(), row);
        }

        @Override public boolean revokeToken(String id, Instant at, String by) {
            TokenRow t = tokens.get(id);
            if (t == null || t.revokedAt().isPresent()) return false;
            tokens.put(id, new TokenRow(t.id(), t.user(), t.name(), t.fingerprint(), t.createdAt(), t.createdBy(), t.expiresAt(), t.lastUsedAt(),
                    Optional.of(at)));
            return true;
        }

        @Override public void used(String id, Instant at, Instant before) {
            TokenRow t = tokens.get(id);
            if (t == null || t.lastUsedAt().filter(last -> last.isAfter(before)).isPresent()) return;
            tokens.put(id, new TokenRow(t.id(), t.user(), t.name(), t.fingerprint(), t.createdAt(), t.createdBy(), t.expiresAt(), Optional.of(at),
                    t.revokedAt()));
        }

        @Override public int ceiling(Lock lock) {
            return ceiling;
        }

        @Override public void setCeiling(int maxDays) {
            ceiling = maxDays;
        }

        @Override public int shortenTokens(int maxDays, Instant now) {
            int shortened = 0;
            for (TokenRow t : List.copyOf(tokens.values())) {
                Instant end = t.createdAt().plus(Duration.ofDays(maxDays));
                if (t.revokedAt().isPresent() || !t.expiresAt().isAfter(now) || !t.expiresAt().isAfter(end)) continue;
                tokens.put(t.id(), new TokenRow(t.id(), t.user(), t.name(), t.fingerprint(), t.createdAt(), t.createdBy(), end, t.lastUsedAt(),
                        t.revokedAt()));
                shortened++;
            }
            return shortened;
        }

        @Override public OptionalInt claim(String name, String device, Instant at) {
            List<String> key = List.of(name, device);
            Limit l = limits.get(key);
            if (l == null) {
                limits.put(key, new Limit(1, at, Optional.empty()));
                return OptionalInt.of(1);
            }
            if (l.failures() >= 5 && l.lastFailureAt().plus(Accounts.wait(l.failures())).isAfter(at)) return OptionalInt.empty();
            Limit claimed = new Limit(Math.min(l.failures() + 1, 1_000_000), later(l.lastFailureAt(), at), l.auditedAt());
            limits.put(key, claimed);
            return OptionalInt.of(claimed.failures());
        }

        private static Instant later(Instant a, Instant b) {
            return a.isAfter(b) ? a : b;
        }

        @Override public Optional<Limit> limit(String name, String device, boolean forUpdate) {
            return Optional.ofNullable(limits.get(List.of(name, device)));
        }

        @Override public void forgive(String name, String device, int claimed) {
            limits.computeIfPresent(List.of(name, device), (k, l) -> new Limit(Math.max(l.failures() - claimed, 0), l.lastFailureAt(), l.auditedAt()));
        }

        @Override public long limitedNames() {
            return limits.keySet().stream().filter(k -> k.get(1).isEmpty()).count();
        }

        @Override public void forgetLimits(String name) {
            limits.keySet().removeIf(k -> k.get(0).equals(name));
        }

        @Override public void limitAudited(String name, String device, Instant at) {
            limits.computeIfPresent(List.of(name, device), (k, l) -> new Limit(l.failures(), l.lastFailureAt(), Optional.of(at)));
        }

        @Override public int countRefusalAudit(Instant hour) {
            List<String> key = List.of("*", "*");
            Limit l = limits.get(key);
            Limit counted = l == null ? new Limit(1, hour, Optional.empty())
                    : new Limit(l.lastFailureAt().isBefore(hour) ? 1 : Math.min(l.failures() + 1, 1_000_000), later(l.lastFailureAt(), hour),
                    l.auditedAt());
            limits.put(key, counted);
            return counted.failures();
        }

        @Override public void audit(AuditRow row) {
            audit.add(new AuditRow(row.at(), row.actor(), row.action(), row.subject(), row.detail().copy(), row.user(), row.via(),
                    row.credential()));
        }
    }
}
