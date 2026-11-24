package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;
import dev.rail8.core.record.Page;
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
import java.util.Map;
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
 * <p>A token lives at most the installation's ceiling, 1 to 365 days: lowering the ceiling shortens the live tokens longer than
 * it, raising it lengthens none, so a token's {@code expires_at} is its only expiry.
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
    static final int TOKENS_PER_USER = 20;
    static final int TOKEN_MAX_DAYS = 365;
    static final int TOKEN_DEFAULT_DAYS = 90;
    static final Duration USE_EVERY = Duration.ofHours(1);
    static final Duration DEVICE_LIFE = Duration.ofDays(90);
    static final int DEVICES_PER_USER = 10;
    static final String UNKNOWN_ACTOR = "unknown";

    private static final System.Logger LOG = System.getLogger(Accounts.class.getName());
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9._-]{1,63}$");
    private static final Pattern TOKEN_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern TOKEN_ID = Pattern.compile("^[0-9a-f]{16}$");
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
        String presented = Credentials.presented(authorization.get()).orElseThrow(Accounts::unknownCredential);
        String fingerprint = Credentials.fingerprint(presented);
        if (key.isPresent() && Credentials.isKey(presented) && Credentials.same(fingerprint, key.get())) return Caller.emergencyKey();
        if (shared.isPresent() && Credentials.same(fingerprint, shared.get())) {
            if (query(Tx::bridgeOpen)) return Caller.bridge(Via.SHARED_TOKEN, declared(declaredActor));
            throw unknownCredential();
        }
        Optional<String> token = Credentials.tokenId(presented);
        if (token.isPresent()) return byToken(token.get(), fingerprint);
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

    /** The caller of the token {@code id} whose credential has {@code fingerprint}. */
    private Caller byToken(String id, String fingerprint) {
        Optional<Tx.TokenOfUser> found = query(tx -> tx.token(id));
        boolean same = Credentials.same(fingerprint, found.map(f -> f.token().fingerprint()).orElse(Credentials.DUMMY_FINGERPRINT));
        if (found.isEmpty() || !same) throw unknownCredential();
        Tx.TokenRow token = found.get().token();
        Tx.UserRow user = found.get().user();
        Instant now = clock.instant();
        if (token.revokedAt().isPresent()) throw AccessRefused.unauthenticated("token-revoked", "the token was revoked");
        if (!now.isBefore(token.expiresAt())) throw AccessRefused.unauthenticated("token-expired", "the token expired");
        if (user.blockedAt().isPresent()) throw AccessRefused.unauthenticated("blocked", "the user is blocked");
        Instant before = now.minus(USE_EVERY);
        if (token.lastUsedAt().map(at -> !at.isAfter(before)).orElse(true)) query(tx -> { tx.used(id, now, before); return null; });
        return new Caller(OptionalLong.of(user.id()), user.name(), user.kind(), found.get().roles(), Via.TOKEN, Optional.of(id), false);
    }

    /** The name a caller of the bridge declared, stripped; {@code unknown} when none; above {@link #ACTOR_MAX} characters a 400. */
    private static String declared(Optional<String> declaredActor) {
        String actor = declaredActor.map(String::strip).orElse("");
        if (actor.length() > ACTOR_MAX) throw AccessRefused.of(400, "X-Rail8-Actor is at most " + ACTOR_MAX + " characters, was " + actor.length());
        return actor.isEmpty() ? UNKNOWN_ACTOR : actor;
    }

    @Override
    public Login login(String name, String password, Optional<String> device, String remote) {
        String typed = name.toLowerCase(Locale.ROOT);
        String given = Passwords.normalize(password);
        Optional<String> deviceId = device.flatMap(Credentials::deviceId);
        Tx.LoginRows read = query(tx -> tx.loginRows(typed, deviceId));
        Optional<Tx.UserRow> user = read.user();
        Optional<Tx.DeviceRow> known = deviceId.isEmpty() ? Optional.empty() : valid(read, device.get(), clock.instant());
        String counted = known.map(Tx.DeviceRow::id).orElse(NAME_ROW);
        HashPlaces.Place place = places.take(known.isPresent()).orElseThrow(AccessRefused::busy);
        try (place) {
            Instant now = clock.instant();
            // read for every name, known or not, so a known name's login takes the same steps as an unknown one's
            boolean full = query(tx -> tx.limitedNames() >= NAMES_MAX && tx.limit(typed, NAME_ROW, false).isEmpty());
            if (user.isEmpty() && full) {
                hash.matches(given, hash.dummy());
                warnFull(now);
                throw loginRefused();
            }
            int claimed = claim(typed, counted, now);
            Optional<Tx.UserRow> person = user.filter(u -> u.kind() == Kind.PERSON && u.blockedAt().isEmpty() && u.password().isPresent());
            boolean matched = hash.matches(given, person.flatMap(Tx.UserRow::password).orElse(hash.dummy())) && person.isPresent();
            if (!matched) throw refusedLogin(typed, counted, user, claimed, refusal(user), remote, now);
            Tx.UserRow u = person.get();
            if (u.firstPassword() && u.passwordSetAt().map(at -> !now.isBefore(at.plus(FIRST_PASSWORD_LIFE))).orElse(true)) {
                throw refusedLogin(typed, counted, user, claimed, "first-password-expired", remote, now);
            }
            String stored = u.password().orElseThrow();
            Optional<String> rehashed = hash.current(stored) ? Optional.empty() : Optional.of(hash.hash(given));
            String id = Credentials.newId(random);
            String credential = Credentials.SESSION + id + "_" + Credentials.newSecret(random);
            Tx.SessionRow row = new Tx.SessionRow(id, u.id(), Credentials.fingerprint(credential), u.firstPassword(), now, now,
                    now.plus(SESSION_LIFE));
            boolean issues = device.isPresent() && !u.firstPassword();
            Optional<Made> made = transaction(tx -> {
                Optional<Tx.UserRow> locked = tx.user(u.id(), issues ? Tx.Lock.UPDATE : Tx.Lock.SHARE);
                if (locked.isEmpty() || locked.get().generation() != u.generation() || locked.get().blockedAt().isPresent()) {
                    return Optional.<Made>empty();
                }
                List<Tx.SessionRow> alive = tx.sessions(u.id()).stream().filter(s -> live(s, now))
                        .sorted(Comparator.comparing(Tx.SessionRow::createdAt)).toList();
                for (int i = 0; i <= alive.size() - SESSIONS_PER_USER; i++) tx.endSession(alive.get(i).id());
                tx.insertSession(row);
                Optional<NewDevice> issued = issues ? issueDevice(tx, u, known) : Optional.empty();
                boolean replaced = known.isPresent() && issued.isPresent();
                if (replaced) tx.forgetLimit(typed, counted);
                else tx.forgive(typed, counted, claimed);
                JsonObject detail = new JsonObject().put("firstPassword", u.firstPassword()).put("remote", remote);
                known.ifPresent(d -> detail.put("device", d.id()));
                issued.ifPresent(d -> detail.put("issued", d.id()));
                tx.audit(new Tx.AuditRow(now, u.name(), "login", "user/" + u.name(), detail, OptionalLong.of(u.id()), Via.SESSION.id(),
                        Optional.of(id)));
                return Optional.of(new Made(roles(tx.grants(u.id())), issued.map(NewDevice::credential)));
            });
            if (made.isEmpty()) throw refusedLogin(typed, counted, user, claimed, "account-changed", remote, now);
            rehashed.ifPresent(h -> query(tx -> { tx.rehash(u.id(), stored, h); return null; }));
            Caller caller = new Caller(OptionalLong.of(u.id()), u.name(), Kind.PERSON, made.get().roles(), Via.SESSION, Optional.of(id),
                    u.firstPassword());
            return new Login(credential, row.expiresAt(), IDLE.toSeconds(), caller, made.get().device());
        }
    }

    /** What a login's transaction made: the user's roles, and the device credential it issued. */
    private record Made(Set<Role> roles, Optional<String> device) {}

    /** A device just issued: its id, and its credential, shown once. */
    private record NewDevice(String id, String credential) {}

    /**
     * The device a login presented when it is valid: its fingerprint matches the row's (compared with the dummy when there is
     * no row), the row is the user's, at the user's generation, and {@code now} is before its expiry. Anything else is none.
     */
    private static Optional<Tx.DeviceRow> valid(Tx.LoginRows read, String presented, Instant now) {
        boolean same = Credentials.same(Credentials.fingerprint(presented),
                read.device().map(Tx.DeviceRow::fingerprint).orElse(Credentials.DUMMY_FINGERPRINT));
        return read.device().filter(d -> same && read.user().isPresent() && d.user() == read.user().get().id()
                && d.generation() == read.user().get().generation() && now.isBefore(d.expiresAt()));
    }

    /**
     * A new device of {@code user} in {@code tx}, which holds the user's row {@code for update}; its instants come from the
     * clock read now, after the lock. When the login presented {@code known} and another login has replaced or ended it
     * meanwhile, nothing is issued: the first replacement wins. Otherwise {@code known} ends, so does every dead device of the
     * user (another generation, or expired) and the oldest live ones until one place is left of {@link #DEVICES_PER_USER}.
     */
    private Optional<NewDevice> issueDevice(Tx tx, Tx.UserRow user, Optional<Tx.DeviceRow> known) {
        Instant at = clock.instant();
        List<Tx.DeviceRow> held = tx.devices(user.id());
        if (known.isPresent() && held.stream().noneMatch(d -> d.id().equals(known.get().id()))) return Optional.empty();
        List<Tx.DeviceRow> live = new ArrayList<>();
        for (Tx.DeviceRow d : held) {
            boolean dead = d.generation() != user.generation() || !at.isBefore(d.expiresAt());
            if (dead || known.map(k -> k.id().equals(d.id())).orElse(false)) tx.endDevice(d.id());
            else live.add(d);
        }
        live.sort(Comparator.comparing(Tx.DeviceRow::createdAt).thenComparing(Tx.DeviceRow::id));
        for (int i = 0; i <= live.size() - DEVICES_PER_USER; i++) tx.endDevice(live.get(i).id());
        String id = Credentials.newId(random);
        String credential = Credentials.DEVICE + id + "_" + Credentials.newSecret(random);
        tx.insertDevice(new Tx.DeviceRow(id, user.id(), Credentials.fingerprint(credential), user.generation(), at, at.plus(DEVICE_LIFE)));
        return Optional.of(new NewDevice(id, credential));
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
        if (who.credential().isEmpty()) return Optional.empty();
        String id = who.credential().get();
        return switch (who.via()) {
            case SESSION -> query(tx -> tx.session(id)).map(s -> s.session().expiresAt());
            case TOKEN -> query(tx -> tx.token(id)).map(t -> t.token().expiresAt());
            case EMERGENCY_KEY, SHARED_TOKEN, LOCAL -> Optional.empty();
        };
    }

    @Override
    public List<User> users() {
        Instant now = clock.instant();
        return query(tx -> {
            List<Tx.UserRow> rows = tx.users();
            Map<Long, List<Grant>> grants = tx.grants();
            Map<Long, List<Tx.SessionRow>> sessions = tx.sessions(now, now.minus(IDLE)).stream()
                    .collect(Collectors.groupingBy(Tx.SessionRow::user));
            Map<String, List<Token>> tokens = tx.liveTokens(OptionalLong.empty(), now, Optional.empty()).stream()
                    .collect(Collectors.groupingBy(Token::user));
            return rows.stream().map(u -> view(u, grants.getOrDefault(u.id(), List.of()), sessions.getOrDefault(u.id(), List.of()),
                    tokens.getOrDefault(u.name(), List.of()), now)).toList();
        });
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
    public Issued issueOwnToken(Actor by, String password, String name, OptionalInt days) {
        long userId = by.user().orElseThrow(() -> AccessRefused.of(400, "only a person issues their own token"));
        String session = by.credential().orElseThrow(() -> AccessRefused.of(400, "a personal token is issued in a session"));
        checkToken(name, days);
        String given = Passwords.normalize(password);
        Tx.UserRow user = query(tx -> tx.user(userId, Tx.Lock.NONE)).orElseThrow(Accounts::sessionEnded);
        HashPlaces.Place place = places.take(true).orElseThrow(AccessRefused::busy);
        try (place) {
            Instant now = clock.instant();
            int claimed = claim(user.name(), now);
            if (!(hash.matches(given, user.password().orElse(hash.dummy())) && user.password().isPresent())) {
                throw refusal(user.name(), Optional.of(user), claimed, "wrong-password", by.remote(), now,
                        AccessRefused.forbidden("wrong-password", "the password is wrong", Set.of()));
            }
            // forgiven on its own, so a 409 of the issue below does not undo the forgive of a right password
            transaction(tx -> {
                unchanged(tx, userId, session, user);
                tx.forgive(user.name(), NAME_ROW, claimed);
                return null;
            });
            return transaction(tx -> issue(tx, by, unchanged(tx, userId, session, user), name, days, now));
        }
    }

    /**
     * The user's row taken {@code for update}, while {@code session} is live by the clock read after the lock and the account is
     * as {@code read} found it before its password was checked.
     *
     * @throws AccessRefused 401 {@code session-ended}, 409 for an account changed meanwhile
     */
    private Tx.UserRow unchanged(Tx tx, long userId, String session, Tx.UserRow read) {
        Tx.UserRow locked = tx.user(userId, Tx.Lock.UPDATE).orElseThrow(Accounts::sessionEnded);
        Instant now = clock.instant();
        if (tx.session(session).filter(s -> live(s.session(), now)).isEmpty()) throw sessionEnded();
        if (locked.generation() != read.generation()) {
            throw AccessRefused.of(409, "the account changed while the password was checked: try again");
        }
        return locked;
    }

    @Override
    public Issued issueToken(Actor by, String user, String name, OptionalInt days) {
        checkToken(name, days);
        Instant now = clock.instant();
        return transaction(tx -> {
            Tx.UserRow account = tx.user(user, Tx.Lock.UPDATE).orElseThrow(() -> noSuchUser(user));
            if (account.kind() != Kind.TECHNICAL) throw AccessRefused.of(409, "a person issues their own tokens");
            if (account.blockedAt().isPresent()) throw AccessRefused.of(409, user + " is blocked");
            return issue(tx, by, account, name, days, now);
        });
    }

    /**
     * A token of {@code user} issued in {@code tx}, which holds the lock of the user's row; the ceiling's row is taken after it,
     * so an issue and a change of the ceiling follow each other.
     */
    private Issued issue(Tx tx, Actor by, Tx.UserRow user, String name, OptionalInt days, Instant now) {
        int life = life(days, tx.ceiling(Tx.Lock.SHARE));
        List<Tx.TokenRow> unrevoked = tx.unrevokedTokens(user.id());
        Optional<Tx.TokenRow> named = unrevoked.stream().filter(t -> t.name().equals(name)).findFirst();
        if (named.isPresent() && now.isBefore(named.get().expiresAt())) {
            throw AccessRefused.of(409, user.name() + " holds a live token named " + name);
        }
        if (unrevoked.stream().filter(t -> now.isBefore(t.expiresAt())).count() >= TOKENS_PER_USER) {
            throw AccessRefused.of(409, user.name() + " holds " + TOKENS_PER_USER + " live tokens, the most a user holds");
        }
        named.ifPresent(t -> tx.revokeToken(t.id(), now, by.name()));
        String id = Credentials.newId(random);
        String token = Credentials.TOKEN + id + "_" + Credentials.newSecret(random);
        Instant expiresAt = now.plus(Duration.ofDays(life));
        tx.insertToken(new Tx.TokenRow(id, user.id(), name, Credentials.fingerprint(token), now, by.name(), expiresAt, Optional.empty(),
                Optional.empty()));
        JsonObject detail = new JsonObject().put("token", id).put("name", name).put("expiresAt", expiresAt.toString());
        named.ifPresent(t -> detail.put("replaced", t.id()));
        tx.audit(row(by, now, "token.issue", "user/" + user.name(), detail));
        return new Issued(id, name, expiresAt, token);
    }

    /** A 400 for a token's name not of the form, or for days outside 1 to the ceiling as it is now. */
    private void checkToken(String name, OptionalInt days) {
        if (!TOKEN_NAME.matcher(name).matches()) throw AccessRefused.of(400, "a token's name matches ^[a-z0-9][a-z0-9-]{0,63}$: " + name);
        life(days, query(tx -> tx.ceiling(Tx.Lock.NONE)));
    }

    /** A token's life in days: {@code days}, or the default under {@code ceiling}; outside 1 to the ceiling a 400. */
    private static int life(OptionalInt days, int ceiling) {
        int life = days.orElse(ceiling(ceiling).defaultDays());
        if (life < 1 || life > ceiling) {
            throw AccessRefused.of(400, "a token lives 1 to " + ceiling + " days, the installation's ceiling, not " + life);
        }
        return life;
    }

    @Override
    public Tokens ownTokens(Caller who) {
        return listed(OptionalLong.of(who.user().orElseThrow(() -> AccessRefused.of(400, "only a user holds tokens"))), Optional.empty());
    }

    @Override
    public Tokens tokens(Optional<Duration> expiringWithin) {
        if (expiringWithin.filter(w -> w.isNegative() || w.isZero() || w.compareTo(Duration.ofDays(TOKEN_MAX_DAYS)) > 0).isPresent()) {
            throw AccessRefused.of(400, "expiringWithin is above zero and at most P365D, not " + expiringWithin.get());
        }
        return listed(OptionalLong.empty(), expiringWithin);
    }

    /** The live tokens of {@code owner}, or of everyone, with the ceiling read first: a lowering between the two reads shows under the old one. */
    private Tokens listed(OptionalLong owner, Optional<Duration> expiringWithin) {
        Instant now = clock.instant();
        return query(tx -> {
            Ceiling ceiling = ceiling(tx.ceiling(Tx.Lock.NONE));
            return new Tokens(tx.liveTokens(owner, now, expiringWithin.map(now::plus)), ceiling);
        });
    }

    @Override
    public void revokeOwnToken(Actor by, String id) {
        revoke(by, id, OptionalLong.of(by.user().orElseThrow(() -> noSuchToken(id))));
    }

    @Override
    public void revokeToken(Actor by, String id) {
        revoke(by, id, OptionalLong.empty());
    }

    /** The token {@code id} revoked, when it is {@code owner}'s or, without one, whoever's. */
    private void revoke(Actor by, String id, OptionalLong owner) {
        if (!TOKEN_ID.matcher(id).matches()) throw noSuchToken(id);
        Instant now = clock.instant();
        transaction(tx -> {
            Tx.TokenOfUser found = tx.token(id).filter(t -> owner.isEmpty() || owner.getAsLong() == t.user().id())
                    .orElseThrow(() -> noSuchToken(id));
            if (tx.revokeToken(id, now, by.name())) {
                tx.audit(row(by, now, "token.revoke", "user/" + found.user().name(),
                        new JsonObject().put("token", id).put("name", found.token().name())));
            }
            return null;
        });
    }

    @Override
    public Ceiling changeCeiling(Actor by, int maxDays) {
        if (maxDays < 1 || maxDays > TOKEN_MAX_DAYS) throw AccessRefused.of(400, "the ceiling is 1 to " + TOKEN_MAX_DAYS + " days, not " + maxDays);
        Instant now = clock.instant();
        return transaction(tx -> {
            int from = tx.ceiling(Tx.Lock.UPDATE);
            if (from != maxDays) {
                int shortened = maxDays < from ? tx.shortenTokens(maxDays) : 0;
                tx.setCeiling(maxDays);
                tx.audit(row(by, now, "token.ceiling", "token-ceiling",
                        new JsonObject().put("from", from).put("to", maxDays).put("shortened", shortened)));
            }
            return ceiling(maxDays);
        });
    }

    /** The ceiling of {@code maxDays} with its default: 90 days, or the ceiling when lower. */
    static Ceiling ceiling(int maxDays) {
        return new Ceiling(maxDays, Math.min(TOKEN_DEFAULT_DAYS, maxDays));
    }

    @Override
    public void keyUsed(Caller who, String method, String path, String remote, Optional<Action> action, boolean allowed) {
        String shown = Credentials.redacted(path);
        LOG.log(System.Logger.Level.WARNING, () -> "the " + (who.via() == Via.EMERGENCY_KEY ? "emergency key" : "shared token, as the emergency key,")
                + " was used: " + method + " " + shown + " from " + remote + (allowed ? "" : ", refused"));
        JsonObject detail = new JsonObject().put("method", method).put("path", shown).put("remote", remote).put("allowed", allowed)
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
    public Page<Audited> messageAudit(String message, Optional<String> after, int limit) {
        Page.requireLimit(limit);
        OptionalLong before = after.isEmpty() ? OptionalLong.empty() : OptionalLong.of(auditId(after.get()));
        List<Tx.AuditedRow> rows = query(tx -> tx.audit("message/" + message, before, limit + 1));
        List<Audited> page = rows.stream().limit(limit).map(a -> new Audited(a.id(), a.row().at(), a.row().action(), a.row().actor(),
                a.row().via(), a.row().credential(), a.row().detail())).toList();
        // the limit + 1st row: another page exists, from the last row of this one
        return new Page<>(page, rows.size() > limit ? Optional.of(Page.cursor(Long.toString(page.getLast().id()))) : Optional.empty());
    }

    /** The audit row's id a cursor of {@link #messageAudit} carries; any other text is an IllegalArgumentException. */
    private static long auditId(String cursor) {
        try {
            return Long.parseLong(Page.position(cursor));
        } catch (NumberFormatException garbled) {
            throw new IllegalArgumentException("not a cursor of the audit of a message", garbled);
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
        return claim(name, NAME_ROW, now);
    }

    /** One attempt claimed on the row {@code (name, device)}, else 429 with the seconds until the row takes another. */
    private int claim(String name, String device, Instant now) {
        OptionalInt claimed = query(tx -> tx.claim(name, device, now));
        if (claimed.isPresent()) return claimed.getAsInt();
        Optional<Tx.Limit> row = query(tx -> tx.limit(name, device, false));
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

    private AccessRefused refusedLogin(String typed, String device, Optional<Tx.UserRow> user, int failures, String reason, String remote,
                                       Instant now) {
        return refusal(typed, device, user, failures, reason, remote, now, loginRefused());
    }

    private AccessRefused refusal(String typed, Optional<Tx.UserRow> user, int failures, String reason, String remote, Instant now,
                                  AccessRefused answer) {
        return refusal(typed, NAME_ROW, user, failures, reason, remote, now, answer);
    }

    /**
     * {@code answer}, after the refused attempt's audit row: the first failure of a run, then at most one an hour per row of the
     * login limit, carrying the run's failures so far and the device of a device's row; the installation writes at most 1,000
     * an hour. The row is only marked as audited, never inserted: a row a reset deleted meanwhile writes as a first failure.
     */
    private AccessRefused refusal(String typed, String device, Optional<Tx.UserRow> user, int failures, String reason, String remote,
                                  Instant now, AccessRefused answer) {
        String actor = actorOf(typed);
        transaction(tx -> {
            Optional<Tx.Limit> row = tx.limit(typed, device, true);
            boolean due = failures == 1 || row.flatMap(Tx.Limit::auditedAt).map(at -> !now.isBefore(at.plus(REFUSED_AUDIT_EVERY))).orElse(true);
            if (!due || tx.countRefusalAudit(now.truncatedTo(ChronoUnit.HOURS)) > REFUSED_AUDIT_PER_HOUR) return null;
            tx.limitAudited(typed, device, now);
            JsonObject detail = new JsonObject().put("reason", reason).put("failures", failures).put("remote", remote);
            if (!device.equals(NAME_ROW)) detail.put("device", device);
            tx.audit(new Tx.AuditRow(now, actor, "login.refused", "user/" + actor, detail,
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
        return view(u, tx.grants(u.id()), tx.sessions(u.id()), tx.liveTokens(OptionalLong.of(u.id()), now, Optional.empty()), now);
    }

    /** The view of {@code u} from its grants, its session rows (the ended ones left out here) and its live tokens. */
    private static User view(Tx.UserRow u, List<Grant> grants, List<Tx.SessionRow> sessions, List<Token> tokens, Instant now) {
        List<LiveSession> live = sessions.stream().filter(s -> live(s, now))
                .sorted(Comparator.comparing(Tx.SessionRow::createdAt))
                .map(s -> new LiveSession(s.id(), s.createdAt(), s.lastSeenAt(), s.expiresAt())).toList();
        return new User(u.name(), u.kind(), grants, u.blockedAt(), u.firstPassword(), u.createdAt(), u.createdBy(), live, tokens);
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

    private static AccessRefused noSuchToken(String id) {
        return AccessRefused.of(404, "no token " + id);
    }

    private static AccessRefused technicalAdministrator() {
        return AccessRefused.of(409, "a technical account never holds the administrator role");
    }

    private static AccessRefused lastAdministrator() {
        return AccessRefused.of(409, "at least one unblocked person holds the administrator role");
    }
}
