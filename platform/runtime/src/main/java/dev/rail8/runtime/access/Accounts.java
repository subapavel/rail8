package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link Access} over a {@link Tx}: the order of every act, its rules and its audit rows, the same for every store. A store
 * gives the statements of one transaction ({@link #transaction}) or of none ({@link #query}).
 *
 * <p>The node's {@code RAIL8_ADMIN_TOKEN} is the emergency key when it has the key's form ({@code rail8k_} and 43 base64url
 * characters), and the shared token otherwise, which works only while the bridge's marker is open.
 */
abstract class Accounts implements Access {

    static final Duration SESSION_LIFE = Duration.ofHours(12);
    static final Duration IDLE = Duration.ofHours(2);
    static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    static final Duration FIRST_PASSWORD_LIFE = Duration.ofDays(7);
    static final Duration REFUSED_AUDIT_EVERY = Duration.ofHours(1);
    static final int SESSIONS_PER_USER = 10;
    static final int USERS_MAX = 10_000;
    static final int NAMES_MAX = 100_000;
    static final int REFUSED_AUDIT_PER_HOUR = 1_000;
    static final String UNKNOWN_ACTOR = "unknown";

    private static final System.Logger LOG = System.getLogger(Accounts.class.getName());
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9._-]{1,63}$");
    private static final String NAME_ROW = "";

    private final Clock clock;
    private final PasswordHash hash;
    private final HashPlaces places;
    private final RandomGenerator random;
    private final Optional<String> key;
    private final Optional<String> shared;
    private final AtomicReference<Instant> fullWarned = new AtomicReference<>(Instant.MIN);

    /**
     * @param clock the node's clock, for every instant the store keeps
     * @param hash the hash of passwords
     * @param places the node's places to hash
     * @param random the source of ids, secrets, salts and first passwords: a {@code SecureRandom} in a node
     * @param adminToken the node's {@code RAIL8_ADMIN_TOKEN}
     */
    Accounts(Clock clock, PasswordHash hash, HashPlaces places, RandomGenerator random, Optional<String> adminToken) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hash = Objects.requireNonNull(hash, "hash");
        this.places = Objects.requireNonNull(places, "places");
        this.random = Objects.requireNonNull(random, "random");
        this.key = adminToken.filter(Credentials::isKey).map(Credentials::fingerprint);
        this.shared = adminToken.filter(t -> !Credentials.isKey(t)).map(Credentials::fingerprint);
    }

    /** {@code work} on the store with no transaction: each statement on its own. */
    protected abstract <T> T query(Function<Tx, T> work);

    /** {@code work} in one transaction, rolled back when it throws. */
    protected abstract <T> T transaction(Function<Tx, T> work);

    @Override
    public Caller authenticate(Optional<String> authorization, Optional<String> declaredActor) {
        if (authorization.isEmpty()) {
            if (key.isEmpty() && shared.isEmpty() && query(Tx::bridgeOpen)) return Caller.bridge(Via.LOCAL, declared(declaredActor));
            throw AccessRefused.unauthenticated("no-credential", "the admin API needs a credential: Authorization: Bearer <credential>");
        }
        String header = authorization.get();
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) throw unknownCredential();
        String presented = header.substring(7).strip();
        String fingerprint = Credentials.fingerprint(presented);
        if (key.isPresent() && Credentials.isKey(presented) && Credentials.same(fingerprint, key.get())) return Caller.emergencyKey();
        if (shared.isPresent() && Credentials.same(fingerprint, shared.get())) {
            if (query(Tx::bridgeOpen)) return Caller.bridge(Via.SHARED_TOKEN, declared(declaredActor));
            throw unknownCredential();
        }
        Optional<String> id = Credentials.sessionId(presented);
        if (id.isEmpty()) throw unknownCredential();
        Optional<Tx.SessionOfUser> found = query(tx -> tx.session(id.get()));
        boolean same = Credentials.same(fingerprint, found.map(f -> f.session().fingerprint()).orElse(Credentials.DUMMY_FINGERPRINT));
        if (found.isEmpty() || !same) throw unknownCredential();
        Tx.SessionRow session = found.get().session();
        Tx.UserRow user = found.get().user();
        Instant now = clock.instant();
        if (!live(session, now)) throw AccessRefused.unauthenticated("session-ended", "the session ended: log in again");
        if (user.blockedAt().isPresent()) throw AccessRefused.unauthenticated("blocked", "the user is blocked");
        if (!now.isBefore(session.lastSeenAt().plus(TOUCH_EVERY))) query(tx -> { tx.touch(session.id(), now); return null; });
        return new Caller(OptionalLong.of(user.id()), user.name(), user.kind(), found.get().roles(), Via.SESSION,
                Optional.of(session.id()), session.firstPassword());
    }

    /** The name a caller of the bridge declared, stripped; {@code unknown} when none; above {@link #ACTOR_MAX} characters a 400. */
    private static String declared(Optional<String> declaredActor) {
        String actor = declaredActor.map(String::strip).orElse("");
        if (actor.length() > ACTOR_MAX) throw AccessRefused.of(400, "X-Rail8-Actor is at most " + ACTOR_MAX + " characters, was " + actor.length());
        return actor.isEmpty() ? UNKNOWN_ACTOR : actor;
    }

    @Override
    public Login login(String name, String password, String remote) {
        String typed = name.toLowerCase(Locale.ROOT);
        String given = Passwords.normalize(password);
        Optional<Tx.UserRow> user = query(tx -> tx.user(typed, Tx.Lock.NONE));
        HashPlaces.Place place = places.take(false).orElseThrow(AccessRefused::busy);
        try (place) {
            Instant now = clock.instant();
            // read for every name, known or not, so a known name's login takes the same steps as an unknown one's
            boolean full = query(tx -> tx.limitedNames() >= NAMES_MAX && tx.limit(typed, NAME_ROW, false).isEmpty());
            if (user.isEmpty() && full) {
                hash.matches(given, hash.dummy());
                warnFull(now);
                throw loginRefused();
            }
            int claimed = claim(typed, now);
            Optional<Tx.UserRow> person = user.filter(u -> u.kind() == Kind.PERSON && u.blockedAt().isEmpty() && u.password().isPresent());
            boolean matched = hash.matches(given, person.flatMap(Tx.UserRow::password).orElse(hash.dummy())) && person.isPresent();
            if (!matched) throw refusedLogin(typed, user, claimed, refusal(user), remote, now);
            Tx.UserRow u = person.get();
            if (u.firstPassword() && u.passwordSetAt().map(at -> !now.isBefore(at.plus(FIRST_PASSWORD_LIFE))).orElse(true)) {
                throw refusedLogin(typed, user, claimed, "first-password-expired", remote, now);
            }
            String stored = u.password().orElseThrow();
            Optional<String> rehashed = hash.current(stored) ? Optional.empty() : Optional.of(hash.hash(given));
            String id = Credentials.newId(random);
            String credential = Credentials.SESSION + id + "_" + Credentials.newSecret(random);
            Tx.SessionRow row = new Tx.SessionRow(id, u.id(), Credentials.fingerprint(credential), u.firstPassword(), now, now,
                    now.plus(SESSION_LIFE));
            Optional<Set<Role>> roles = transaction(tx -> {
                Optional<Tx.UserRow> locked = tx.user(u.id(), Tx.Lock.SHARE);
                if (locked.isEmpty() || locked.get().generation() != u.generation() || locked.get().blockedAt().isPresent()) {
                    return Optional.<Set<Role>>empty();
                }
                List<Tx.SessionRow> alive = tx.sessions(u.id()).stream().filter(s -> live(s, now))
                        .sorted(Comparator.comparing(Tx.SessionRow::createdAt)).toList();
                for (int i = 0; i <= alive.size() - SESSIONS_PER_USER; i++) tx.endSession(alive.get(i).id());
                tx.insertSession(row);
                tx.forgive(typed, NAME_ROW, claimed);
                tx.audit(new Tx.AuditRow(now, u.name(), "login", "user/" + u.name(),
                        new JsonObject().put("firstPassword", u.firstPassword()).put("remote", remote), OptionalLong.of(u.id()),
                        Via.SESSION.id(), Optional.of(id)));
                return Optional.of(roles(tx.grants(u.id())));
            });
            if (roles.isEmpty()) throw refusedLogin(typed, user, claimed, "account-changed", remote, now);
            rehashed.ifPresent(h -> query(tx -> { tx.rehash(u.id(), stored, h); return null; }));
            Caller caller = new Caller(OptionalLong.of(u.id()), u.name(), Kind.PERSON, roles.get(), Via.SESSION, Optional.of(id),
                    u.firstPassword());
            return new Login(credential, row.expiresAt(), IDLE.toSeconds(), caller);
        }
    }

    @Override
    public void changePassword(Actor by, String current, String next) {
        long userId = by.user().orElseThrow(() -> AccessRefused.of(400, "only a user has a password"));
        String session = by.credential().orElseThrow(() -> AccessRefused.of(400, "a password is changed in a session"));
        String given = Passwords.normalize(current);
        String chosen = Passwords.normalize(next);
        Tx.UserRow user = query(tx -> tx.user(userId, Tx.Lock.NONE)).orElseThrow(Accounts::sessionEnded);
        Passwords.refusal(chosen, user.name(), given).ifPresent(why -> { throw AccessRefused.of(400, why); });
        HashPlaces.Place place = places.take(true).orElseThrow(AccessRefused::busy);
        try (place) {
            Instant now = clock.instant();
            int claimed = claim(user.name(), now);
            if (!(hash.matches(given, user.password().orElse(hash.dummy())) && user.password().isPresent())) {
                throw refusal(user.name(), Optional.of(user), claimed, "wrong-password", by.remote(), now,
                        AccessRefused.forbidden("wrong-password", "the current password is wrong", Set.of()));
            }
            String stored = hash.hash(chosen);
            transaction(tx -> {
                if (tx.user(userId, Tx.Lock.UPDATE).isEmpty()) throw sessionEnded();
                if (tx.session(session).isEmpty()) throw sessionEnded();
                tx.password(userId, stored, false, now);
                int ended = tx.endSessions(userId, Optional.of(session));
                tx.passwordChanged(session);
                tx.forgive(user.name(), NAME_ROW, claimed);
                tx.audit(row(by, now, "password.change", "user/" + user.name(), new JsonObject().put("sessions", ended)));
                return null;
            });
        }
    }

    @Override
    public void logout(Actor by) {
        String session = by.credential().orElseThrow(() -> AccessRefused.of(400, "only a session logs out"));
        Instant now = clock.instant();
        transaction(tx -> {
            if (tx.endSession(session)) tx.audit(row(by, now, "logout", "user/" + by.name(), new JsonObject()));
            return null;
        });
    }

    @Override
    public Optional<Instant> expiresAt(Caller who) {
        if (who.via() != Via.SESSION || who.credential().isEmpty()) return Optional.empty();
        return query(tx -> tx.session(who.credential().get())).map(s -> s.session().expiresAt());
    }

    @Override
    public List<User> users() {
        Instant now = clock.instant();
        return query(tx -> tx.users().stream().map(u -> view(tx, u, now)).toList());
    }

    @Override
    public Optional<User> user(String name) {
        Instant now = clock.instant();
        return query(tx -> tx.user(name, Tx.Lock.NONE).map(u -> view(tx, u, now)));
    }

    @Override
    public Optional<String> createUser(Actor by, String name, Kind kind, Set<Role> roles) {
        if (!NAME.matcher(name).matches()) throw AccessRefused.of(400, "a name matches ^[a-z][a-z0-9._-]{1,63}$: " + name);
        if (kind == Kind.NONE) throw AccessRefused.of(400, "a user is a person or a technical account");
        if (kind == Kind.TECHNICAL && roles.contains(Role.ADMINISTRATOR)) throw technicalAdministrator();
        Optional<String> first = kind == Kind.PERSON ? Optional.of(Passwords.first(random)) : Optional.empty();
        Optional<String> stored = first.map(this::hashOnAPlace);
        Instant now = clock.instant();
        transaction(tx -> {
            if (tx.userCount() >= USERS_MAX) throw AccessRefused.of(409, "the installation has " + USERS_MAX + " users, the most it holds");
            long id = tx.insertUser(name, kind, stored, first.isPresent(), first.map(f -> now), now, by.name())
                    .orElseThrow(() -> AccessRefused.of(409, "the name " + name + " is taken"));
            for (Role role : sorted(roles)) tx.grant(id, role, now, by.name(), by.via());
            tx.audit(row(by, now, "user.create", "user/" + name, new JsonObject().put("kind", kind.id()).put("roles", ids(roles))));
            return null;
        });
        return first;
    }

    @Override
    public RolesChanged changeRoles(Actor by, String name, Set<Role> add, Set<Role> remove) {
        if (add.stream().anyMatch(remove::contains)) throw AccessRefused.of(400, "a role is either added or removed");
        Instant now = clock.instant();
        return transaction(tx -> {
            if (remove.contains(Role.ADMINISTRATOR)) tx.lockAdministrators();
            Tx.UserRow user = tx.user(name, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(name));
            if (add.contains(Role.ADMINISTRATOR) && user.kind() != Kind.PERSON) throw technicalAdministrator();
            Set<Role> held = roles(tx.grants(user.id()));
            Set<Role> added = difference(add, held);
            Set<Role> removed = EnumSet.noneOf(Role.class);
            remove.stream().filter(held::contains).forEach(removed::add);
            if (removed.contains(Role.ADMINISTRATOR) && user.blockedAt().isEmpty() && tx.unblockedAdministrators() <= 1) {
                throw lastAdministrator();
            }
            for (Role role : added) tx.grant(user.id(), role, now, by.name(), by.via());
            for (Role role : removed) tx.revoke(user.id(), role);
            if (!added.isEmpty() || !removed.isEmpty()) {
                tx.audit(row(by, now, "user.roles", "user/" + name, new JsonObject().put("added", ids(added)).put("removed", ids(removed))
                        .put("self", by.user().equals(OptionalLong.of(user.id())))));
            }
            return new RolesChanged(name, roles(tx.grants(user.id())), Set.copyOf(added), Set.copyOf(removed));
        });
    }

    @Override
    public void block(Actor by, String name) {
        Instant now = clock.instant();
        transaction(tx -> {
            tx.lockAdministrators();
            Tx.UserRow user = tx.user(name, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(name));
            if (by.user().equals(OptionalLong.of(user.id()))) throw AccessRefused.of(409, "nobody blocks themselves");
            if (user.blockedAt().isPresent()) return null;
            boolean administrator = user.kind() == Kind.PERSON && roles(tx.grants(user.id())).contains(Role.ADMINISTRATOR);
            if (administrator && tx.unblockedAdministrators() <= 1) throw lastAdministrator();
            tx.blocked(user.id(), Optional.of(now));
            int ended = tx.endSessions(user.id(), Optional.empty());
            tx.audit(row(by, now, "user.block", "user/" + name, new JsonObject().put("sessions", ended)));
            return null;
        });
    }

    @Override
    public void unblock(Actor by, String name) {
        Instant now = clock.instant();
        transaction(tx -> {
            Tx.UserRow user = tx.user(name, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(name));
            if (user.blockedAt().isEmpty()) return null;
            tx.blocked(user.id(), Optional.empty());
            tx.audit(row(by, now, "user.unblock", "user/" + name, new JsonObject()));
            return null;
        });
    }

    @Override
    public String resetPassword(Actor by, String name) {
        Tx.UserRow user = query(tx -> tx.user(name, Tx.Lock.NONE)).orElseThrow(() -> noSuchUser(name));
        if (user.kind() != Kind.PERSON) throw AccessRefused.of(409, "a technical account has no password");
        String first = Passwords.first(random);
        String stored = hashOnAPlace(first);
        Instant now = clock.instant();
        transaction(tx -> {
            Tx.UserRow locked = tx.user(name, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(name));
            tx.password(locked.id(), stored, true, now);
            int ended = tx.endSessions(locked.id(), Optional.empty());
            tx.forgetLimits(name);
            tx.audit(row(by, now, "user.password-reset", "user/" + name, new JsonObject().put("sessions", ended)));
            return null;
        });
        return first;
    }

    @Override
    public void endSessions(Actor by, String name) {
        Instant now = clock.instant();
        transaction(tx -> {
            Tx.UserRow user = tx.user(name, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(name));
            tx.raiseGeneration(user.id());
            int ended = tx.endSessions(user.id(), Optional.empty());
            tx.audit(row(by, now, "session.end", "user/" + name, new JsonObject().put("sessions", ended)));
            return null;
        });
    }

    @Override
    public void keyUsed(Caller who, String method, String path, String remote, Optional<Action> action, boolean allowed) {
        LOG.log(System.Logger.Level.WARNING, () -> "the " + (who.via() == Via.EMERGENCY_KEY ? "emergency key" : "shared token, as the emergency key,")
                + " was used: " + method + " " + path + " from " + remote + (allowed ? "" : ", refused"));
        JsonObject detail = new JsonObject().put("method", method).put("path", path).put("remote", remote).put("allowed", allowed)
                .put("action", action.map(Action::id).orElse(null));
        Instant now = clock.instant();
        try {
            query(tx -> {
                tx.audit(new Tx.AuditRow(now, who.name(), "emergency-key.use", Caller.EMERGENCY_KEY, detail, OptionalLong.empty(), who.via().id(),
                        Optional.empty()));
                return null;
            });
        } catch (RuntimeException failed) {
            throw AccessRefused.unaudited("the use of the emergency key was not written to the audit, so nothing ran: " + failed.getMessage());
        }
    }

    @Override
    public void bodyViewed(Actor by, String message, int step) {
        Instant now = clock.instant();
        try {
            query(tx -> {
                tx.audit(row(by, now, "message.body.view", "message/" + message, new JsonObject().put("step", step)));
                return null;
            });
        } catch (RuntimeException failed) {
            throw AccessRefused.unaudited("the view of the body was not written to the audit, so nothing is sent: " + failed.getMessage());
        }
    }

    @Override
    public boolean emergencyKey() {
        return key.isPresent();
    }

    /** Whether {@code session} is live at {@code now}: before its absolute end, and seen within the idle time. */
    static boolean live(Tx.SessionRow session, Instant now) {
        return now.isBefore(session.expiresAt()) && !now.isAfter(session.lastSeenAt().plus(IDLE));
    }

    /** One attempt claimed on the name's row, else 429 with the seconds until the row takes another. */
    private int claim(String name, Instant now) {
        OptionalInt claimed = query(tx -> tx.claim(name, NAME_ROW, now));
        if (claimed.isPresent()) return claimed.getAsInt();
        Optional<Tx.Limit> row = query(tx -> tx.limit(name, NAME_ROW, false));
        Duration left = row.map(l -> Duration.between(now, l.lastFailureAt().plus(wait(l.failures())))).orElse(Duration.ZERO);
        long seconds = left.toSeconds() + (left.toNanosPart() > 0 ? 1 : 0);   // rounded up: an attempt then is counted
        throw AccessRefused.tooManyAttempts(Math.max(1, seconds));
    }

    /** The wait after the last of {@code failures}, as the claim reckons it. */
    static Duration wait(int failures) {
        if (failures < 5) return Duration.ZERO;
        return Duration.ofSeconds(Math.min(1L << (Math.min(failures, 14) - 5), 300));
    }

    /** The reason a login of {@code user} is refused when the password did not match. */
    private static String refusal(Optional<Tx.UserRow> user) {
        if (user.isEmpty()) return "unknown-name";
        if (user.get().kind() != Kind.PERSON) return "technical";
        if (user.get().blockedAt().isPresent()) return "blocked";
        return "wrong-password";
    }

    private AccessRefused refusedLogin(String typed, Optional<Tx.UserRow> user, int failures, String reason, String remote, Instant now) {
        return refusal(typed, user, failures, reason, remote, now, loginRefused());
    }

    /**
     * {@code answer}, after the refused attempt's audit row: the first failure of a run, then at most one an hour per row of the
     * login limit, carrying the run's failures so far; the installation writes at most 1,000 an hour.
     */
    private AccessRefused refusal(String typed, Optional<Tx.UserRow> user, int failures, String reason, String remote, Instant now,
                                  AccessRefused answer) {
        String actor = actorOf(typed);
        transaction(tx -> {
            Optional<Tx.Limit> row = tx.limit(typed, NAME_ROW, true);
            boolean due = failures == 1 || row.flatMap(Tx.Limit::auditedAt).map(at -> !now.isBefore(at.plus(REFUSED_AUDIT_EVERY))).orElse(true);
            if (!due || tx.countRefusalAudit(now.truncatedTo(ChronoUnit.HOURS)) > REFUSED_AUDIT_PER_HOUR) return null;
            tx.limitAudited(typed, NAME_ROW, now);
            tx.audit(new Tx.AuditRow(now, actor, "login.refused", "user/" + actor,
                    new JsonObject().put("reason", reason).put("failures", failures).put("remote", remote),
                    user.map(u -> OptionalLong.of(u.id())).orElse(OptionalLong.empty()), Via.SESSION.id(), Optional.empty()));
            return null;
        });
        return answer;
    }

    /** A name as typed, in the audit: lowercased, cut to 64 characters, control characters replaced. */
    static String actorOf(String typed) {
        String cut = typed.codePointCount(0, typed.length()) > 64 ? typed.substring(0, typed.offsetByCodePoints(0, 64)) : typed;
        String clean = cut.codePoints().map(c -> Character.isISOControl(c) ? '?' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return clean.isBlank() ? "?" : clean;
    }

    private void warnFull(Instant now) {
        Instant last = fullWarned.get();
        if (now.isBefore(last.plus(Duration.ofHours(1))) || !fullWarned.compareAndSet(last, now)) return;
        LOG.log(System.Logger.Level.WARNING, "the login limit counts " + NAMES_MAX + " names, the most it holds: a login of an unknown name"
                + " is refused without a row");
    }

    private String hashOnAPlace(String password) {
        try (HashPlaces.Place place = places.take(true).orElseThrow(AccessRefused::busy)) {
            return hash.hash(password);
        }
    }

    private static Tx.AuditRow row(Actor by, Instant at, String action, String subject, JsonObject detail) {
        return new Tx.AuditRow(at, by.name(), action, subject, detail.put("remote", by.remote()), by.user(), by.via(), by.credential());
    }

    private static User view(Tx tx, Tx.UserRow u, Instant now) {
        List<LiveSession> sessions = tx.sessions(u.id()).stream().filter(s -> live(s, now))
                .sorted(Comparator.comparing(Tx.SessionRow::createdAt))
                .map(s -> new LiveSession(s.id(), s.createdAt(), s.lastSeenAt(), s.expiresAt())).toList();
        return new User(u.name(), u.kind(), tx.grants(u.id()), u.blockedAt(), u.firstPassword(), u.createdAt(), u.createdBy(), sessions);
    }

    private static Set<Role> roles(List<Grant> grants) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        grants.forEach(g -> roles.add(g.role()));
        return roles;
    }

    private static Set<Role> difference(Set<Role> a, Set<Role> b) {
        Set<Role> out = EnumSet.noneOf(Role.class);
        a.stream().filter(r -> !b.contains(r)).forEach(out::add);
        return out;
    }

    private static List<Role> sorted(Set<Role> roles) {
        List<Role> out = new ArrayList<>(roles);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static JsonArray ids(Set<Role> roles) {
        return new JsonArray(sorted(roles).stream().map(Role::id).collect(Collectors.toList()));
    }

    private static AccessRefused unknownCredential() {
        return AccessRefused.unauthenticated("unknown-credential", "the credential is not known");
    }

    private static AccessRefused sessionEnded() {
        return AccessRefused.unauthenticated("session-ended", "the session ended: log in again");
    }

    private static AccessRefused loginRefused() {
        return AccessRefused.unauthenticated("login-refused", "the name or the password is wrong");
    }

    private static AccessRefused noSuchUser(String name) {
        return AccessRefused.of(404, "no user " + name);
    }

    private static AccessRefused technicalAdministrator() {
        return AccessRefused.of(409, "a technical account never holds the administrator role");
    }

    private static AccessRefused lastAdministrator() {
        return AccessRefused.of(409, "at least one unblocked person holds the administrator role");
    }
}
