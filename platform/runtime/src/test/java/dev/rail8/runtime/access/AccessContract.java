package dev.rail8.runtime.access;

import dev.rail8.core.audit.Actor;
import dev.rail8.core.record.Page;
import dev.rail8.testkit.FakeClock;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link Access} does, over one store that two nodes share, as the nodes of a pool share Postgres. Node A's clock
 * stands at {@link #T0}, node B's 20 seconds behind; both hold the emergency key {@link #KEY} and hash with {@link FakeHash}.
 * A subclass gives the nodes over its store, closes the bridge's marker and reads the audit.
 */
abstract class AccessContract {

    static final Instant T0 = Instant.parse("2026-03-02T10:00:00Z");
    static final String KEY = "rail8k_" + "k".repeat(43);
    static final String SHARED = "the-shared-token-of-today";
    static final String REMOTE = "10.0.0.7";
    static final String NEW_PASSWORD = "a password of my own";
    static final String NAME_ROW = "";   // the device of a name's own row of the login limit

    FakeClock clockA;
    FakeClock clockB;
    FakeHash hash;
    Access a;
    Access b;

    /** A node of the case's installation: its clock, hash and places, and its {@code RAIL8_ADMIN_TOKEN}, over the shared store. */
    abstract Access node(Clock clock, PasswordHash hash, HashPlaces places, Optional<String> adminToken);

    /** Closes the bridge's marker of the shared store, as the cut does. */
    abstract void closeBridge();

    /** Every audit row, oldest first, as {@code {at, actor, action, subject, detail, user, via, credential}}. */
    abstract List<JsonObject> audit();

    /** The login limit filled with {@code count} names {@code limited-0}, {@code limited-1} and so on, each failed once at {@link #T0}. */
    abstract void limitNames(int count);

    @BeforeEach
    void nodes() {
        clockA = FakeClock.at(T0);
        clockB = FakeClock.at(T0.minusSeconds(20));
        hash = new FakeHash();
        a = node(clockA, hash, new HashPlaces(), Optional.of(KEY));
        b = node(clockB, hash, new HashPlaces(), Optional.of(KEY));
    }

    // --- helpers

    static Optional<String> bearer(String credential) {
        return Optional.of("Bearer " + credential);
    }

    static Caller key(Access node) {
        return node.authenticate(bearer(KEY), Optional.empty());
    }

    /**
     * A person {@code name} with {@code roles}, made on A: the first password. The emergency key makes a person with no role or
     * an administrator; for any other role the case's own administrator {@code keeper} makes them.
     */
    String person(String name, Role... roles) {
        Set<Role> held = Set.of(roles);
        Caller by = held.isEmpty() || held.equals(Set.of(Role.ADMINISTRATOR)) ? key(a) : keeper();
        return a.createUser(by.acting(Action.USER_CREATE, PackageRef.ANY, REMOTE), name, Kind.PERSON, held).orElseThrow();
    }

    private Caller keeper;

    /** An administrator named {@code keeper}, made by the emergency key on the case's first call, in a session on A. */
    Caller keeper() {
        if (keeper == null) keeper = changed(a, "keeper", person("keeper", Role.ADMINISTRATOR));
        return keeper;
    }

    /** {@code name} logged in on {@code node} with {@code password}: the session's credential. */
    static String login(Access node, String name, String password) {
        return node.login(name, password, Optional.empty(), REMOTE).session();
    }

    /** {@code name}'s first password changed to {@link #NEW_PASSWORD} in a session on {@code node}: the session's caller now. */
    static Caller changed(Access node, String name, String first) {
        String session = login(node, name, first);
        Caller who = node.authenticate(bearer(session), Optional.empty());
        node.changePassword(who.acting(Action.SELF_PASSWORD, PackageRef.ANY, REMOTE), first, NEW_PASSWORD);
        return node.authenticate(bearer(session), Optional.empty());
    }

    /** A person {@code name} with {@code roles} whose password is {@link #NEW_PASSWORD}, logged in on A: the session's credential. */
    String session(String name, Role... roles) {
        changed(a, name, person(name, roles));
        return login(a, name, NEW_PASSWORD);
    }

    static AccessRefused refused(int status, String code, Executable act) {
        AccessRefused refused = assertThrows(AccessRefused.class, act);
        assertEquals(status, refused.status(), refused.getMessage());
        assertEquals(code, refused.code().code(), refused.getMessage());
        return refused;
    }

    List<JsonObject> audit(String action) {
        return audit().stream().filter(r -> r.getString("action").equals(action)).toList();
    }

    /** The number of names the login limit counts. */
    long limited() {
        return ((Accounts) a).query(Tx::limitedNames);
    }

    /** {@link #keeper()}'s actor of {@code action}. */
    Actor admin(Action action) {
        return keeper().acting(action, PackageRef.ANY, REMOTE);
    }

    /** A technical account {@code name} with {@code roles}, made by {@link #keeper()} on A. */
    void technical(String name, Role... roles) {
        a.createUser(admin(Action.USER_CREATE), name, Kind.TECHNICAL, Set.of(roles));
    }

    /** A token {@code name} of the technical account {@code user} that lives {@code days}, issued by {@link #keeper()} on A. */
    Access.Issued issue(String user, String name, int days) {
        return a.issueToken(admin(Action.TOKEN_ISSUE), user, name, OptionalInt.of(days));
    }

    static List<String> ids(Access.Tokens tokens) {
        return tokens.tokens().stream().map(Access.Token::id).toList();
    }

    /** The ids of the live tokens of {@code user}, as an administrator sees them on A. */
    List<String> held(String user) {
        return a.user(user).orElseThrow().tokens().stream().map(Access.Token::id).toList();
    }

    /** The failures the login limit counts for {@code name}, or empty when it has no row. */
    Optional<Integer> failures(String name) {
        return failures(name, NAME_ROW);
    }

    /** The failures the login limit counts on the row {@code (name, device)}, or empty when it has no row. */
    Optional<Integer> failures(String name, String device) {
        return ((Accounts) a).query(tx -> tx.limit(name, device, false)).map(Tx.Limit::failures);
    }

    /** {@code name} logged in on {@code node}, asking for a device and presenting {@code device}: the device it was issued. */
    static String device(Access node, String name, String password, String device) {
        return node.login(name, password, Optional.of(device), REMOTE).device().orElseThrow();
    }

    /** The id of a device credential. */
    static String idOf(String device) {
        return Credentials.deviceId(device).orElseThrow();
    }

    /** Every device row of {@code user}, dead ones included. */
    List<Tx.DeviceRow> devices(String user) {
        Accounts accounts = (Accounts) a;
        long id = accounts.query(tx -> tx.user(user, Tx.Lock.NONE)).orElseThrow().id();
        return accounts.query(tx -> tx.devices(id));
    }

    /**
     * {@code during}, while a login of an unknown name holds {@code node}'s shared place on a latch in its hash; the login
     * ends after it.
     */
    void holdingTheSharedPlace(Access node, Runnable during) throws InterruptedException {
        CountDownLatch hashing = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        hash.duringNextMatch(() -> {
            hashing.countDown();
            try {
                assertTrue(done.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                node.login("holder", "a password", Optional.empty(), REMOTE);
            } catch (AccessRefused refused) {
                // the unknown name is refused once its hash ends
            }
        });
        assertTrue(hashing.await(20, TimeUnit.SECONDS));
        try {
            during.run();
        } finally {
            done.countDown();
            holder.join();
        }
    }

    // --- the bridge and the emergency key

    @Test
    void theOpenLoopbackAPIHoldsOnlyOnANodeWithoutATokenWhileTheBridgeIsOpen() {
        Access open = node(clockA, hash, new HashPlaces(), Optional.empty());
        Caller ops = open.authenticate(Optional.empty(), Optional.of(" ops-anna "));
        assertEquals(Via.LOCAL, ops.via());
        assertEquals("ops-anna", ops.name(), "the declared name, stripped");
        assertEquals("unknown", open.authenticate(Optional.empty(), Optional.empty()).name());
        assertTrue(ops.may(Action.MESSAGE_READ, PackageRef.ANY) && ops.may(Action.PACKAGE_DEPLOY, PackageRef.ANY), "today's actions");
        assertFalse(ops.may(Action.USER_CREATE, PackageRef.ANY), "none of the users'");
        refused(400, "bad-request", () -> open.authenticate(Optional.empty(), Optional.of("x".repeat(129))));
        assertEquals(128, open.authenticate(Optional.empty(), Optional.of("x".repeat(128))).name().length());

        refused(401, "no-credential", () -> a.authenticate(Optional.empty(), Optional.empty()));   // a node with a key: no open API
        closeBridge();
        refused(401, "no-credential", () -> open.authenticate(Optional.empty(), Optional.of("ops")));
    }

    @Test
    void theSharedTokenHoldsWhileTheBridgeIsOpen() {
        Access node = node(clockA, hash, new HashPlaces(), Optional.of(SHARED));
        Caller ops = node.authenticate(bearer(SHARED), Optional.of("ops-anna"));
        assertEquals(List.of(Via.SHARED_TOKEN, "ops-anna"), List.of(ops.via(), ops.name()));
        assertTrue(ops.may(Action.PACKAGE_DEPLOY, PackageRef.ANY) && ops.may(Action.USER_CREATE, PackageRef.ANY),
                "today's actions and the key's");
        assertFalse(ops.may(Action.ROLES_CHANGE, PackageRef.ANY));
        assertFalse(node.emergencyKey());
        refused(401, "no-credential", () -> node.authenticate(Optional.empty(), Optional.empty()));
        refused(401, "unknown-credential", () -> node.authenticate(bearer(SHARED + "x"), Optional.empty()));
        refused(401, "unknown-credential", () -> node.authenticate(Optional.of("Basic " + SHARED), Optional.empty()));

        closeBridge();
        refused(401, "unknown-credential", () -> node.authenticate(bearer(SHARED), Optional.empty()));
    }

    @Test
    void aTokenOfTheKeysFormIsTheEmergencyKeyAndNothingMore() {
        Caller k = key(a);
        assertEquals(List.of(Via.EMERGENCY_KEY, Caller.EMERGENCY_KEY), List.of(k.via(), k.name()));
        assertTrue(a.emergencyKey());
        assertEquals(List.of(Action.USER_READ, Action.USER_CREATE, Action.ADMINISTRATORS_CHANGE, Action.USER_BLOCK,
                Action.USER_PASSWORD_RESET, Action.SESSION_END, Action.TOKEN_READ, Action.TOKEN_REVOKE, Action.SELF_READ), k.actions());
        refused(401, "unknown-credential", () -> a.authenticate(bearer("rail8k_" + "j".repeat(43)), Optional.empty()));
        closeBridge();
        assertEquals(Via.EMERGENCY_KEY, key(b).via(), "the key outlives the bridge, on every node");
    }

    // --- users

    @Test
    void theEmergencyKeyMakesTheFirstAdministrator() {
        String first = person("alice", Role.ADMINISTRATOR);
        assertTrue(first.matches("[a-z2-7]{20}"), "20 characters of lowercase base32: " + first);

        Access.User alice = a.user("alice").orElseThrow();
        assertEquals(Kind.PERSON, alice.kind());
        assertTrue(alice.firstPassword());
        assertEquals(List.of(new Access.Grant(Role.ADMINISTRATOR, T0, Caller.EMERGENCY_KEY, "emergency-key")), alice.roles());
        assertEquals(List.of("alice"), b.users().stream().map(Access.User::name).toList(), "every node reads the store");
        JsonObject row = audit("user.create").getFirst();
        assertEquals(List.of(Caller.EMERGENCY_KEY, "user/alice", "emergency-key"), List.of(row.getString("actor"), row.getString("subject"),
                row.getString("via")));
        assertEquals(new JsonObject().put("kind", "person").put("roles", new JsonArray().add("administrator")).put("remote", REMOTE),
                row.getJsonObject("detail"));
        assertFalse(audit().toString().contains(first), "never a password in the audit");

        Actor by = key(a).acting(Action.USER_CREATE, PackageRef.ANY, REMOTE);
        refused(409, "name-taken", () -> a.createUser(by, "alice", Kind.PERSON, Set.of()));
        refused(400, "bad-user-name", () -> a.createUser(by, "Alice", Kind.PERSON, Set.of()));
        refused(400, "bad-user-name", () -> a.createUser(by, "a", Kind.PERSON, Set.of()));
        refused(409, "technical-administrator", () -> a.createUser(by, "ci-deploy", Kind.TECHNICAL, Set.of(Role.ADMINISTRATOR)));
        assertEquals(Optional.empty(), a.createUser(by, "ci-deploy", Kind.TECHNICAL, Set.of()), "a technical account has no password");
    }

    @Test
    void aFirstPasswordOnlyReadsItselfChangesItAndLogsOut() {
        String first = person("anna", Role.ADMINISTRATOR);
        String other = login(b, "anna", first);
        String session = login(a, "anna", first);
        Caller anna = a.authenticate(bearer(session), Optional.empty());
        assertTrue(anna.firstPassword());
        assertEquals(List.of(Action.SELF_READ, Action.SELF_PASSWORD, Action.SELF_LOGOUT), anna.actions());
        AccessRefused needs = refused(403, "password-change-required", () -> anna.require(Action.USER_READ, PackageRef.ANY));
        assertEquals(Set.of(Role.ADMINISTRATOR), needs.needs());

        Actor self = anna.acting(Action.SELF_PASSWORD, PackageRef.ANY, REMOTE);
        refused(400, "password-length", () -> a.changePassword(self, first, "too short"));
        refused(400, "password-contains-name", () -> a.changePassword(self, first, "with anna inside"));
        refused(400, "password-unchanged", () -> a.changePassword(self, first, first));
        refused(403, "wrong-password", () -> a.changePassword(self, "not the first password", NEW_PASSWORD));
        a.changePassword(self, first, NEW_PASSWORD);

        Caller after = a.authenticate(bearer(session), Optional.empty());
        assertFalse(after.firstPassword(), "the same session goes on without the restriction");
        assertTrue(after.may(Action.USER_READ, PackageRef.ANY));
        refused(401, "unknown-credential", () -> b.authenticate(bearer(other), Optional.empty()));
        assertFalse(a.user("anna").orElseThrow().firstPassword());
        JsonObject row = audit("password.change").getFirst();
        assertEquals(List.of("anna", "session", session.substring(7, 23)), List.of(row.getString("actor"), row.getString("via"),
                row.getString("credential")), "the audit names the session's user, its via and its id");
        assertEquals(1, row.getJsonObject("detail").getInteger("sessions"), "the other session ended");
        refused(401, "login-refused", () -> a.login("anna", first, Optional.empty(), REMOTE));
        assertFalse(login(b, "anna", NEW_PASSWORD).isEmpty());
    }

    @Test
    void aFirstPasswordExpiresAfterSevenDays() {
        String first = person("anna");
        clockA.advance(Duration.ofDays(7));
        refused(401, "login-refused", () -> a.login("anna", first, Optional.empty(), REMOTE));
        assertEquals("first-password-expired", audit("login.refused").getLast().getJsonObject("detail").getString("reason"));
    }

    @Test
    void theListOfUsersShowsEachUsersOwnView() {
        session("anna", Role.DEVELOPER, Role.OPERATOR);
        technical("ci-deploy", Role.OPERATOR);
        Access.Issued pipeline = issue("ci-deploy", "pipeline", 30);
        issue("ci-deploy", "short", 1);
        a.revokeToken(admin(Action.TOKEN_REVOKE), issue("ci-deploy", "gone", 30).id());
        technical("ci-idle");
        person("bob");
        session("carol", Role.MESSAGE_BODIES);
        a.block(admin(Action.USER_BLOCK), "carol");
        clockA.advance(Duration.ofDays(1));
        String idle = login(a, "anna", NEW_PASSWORD);
        clockA.advance(Duration.ofHours(1));
        String live = login(a, "anna", NEW_PASSWORD);
        String ended = login(a, "anna", NEW_PASSWORD);
        clockA.advance(Accounts.IDLE.minusMinutes(30));
        a.logout(a.authenticate(bearer(ended), Optional.empty()).acting(Action.SELF_LOGOUT, PackageRef.ANY, REMOTE));

        List<Access.User> users = a.users();
        assertEquals(List.of("anna", "bob", "carol", "ci-deploy", "ci-idle", "keeper"), users.stream().map(Access.User::name).toList());
        assertEquals(users.stream().map(u -> a.user(u.name()).orElseThrow()).toList(), users, "the list shows each user's own view");
        Access.User annaView = users.getFirst();
        assertEquals(List.of(Role.DEVELOPER, Role.OPERATOR), annaView.roles().stream().map(Access.Grant::role).toList());
        assertEquals(List.of(live.substring(7, 23)), annaView.sessions().stream().map(Access.LiveSession::id).toList(),
                "the expired, idle and ended sessions are left out: " + idle.substring(7, 23));
        assertEquals(List.of(pipeline.id()), users.get(3).tokens().stream().map(Access.Token::id).toList(),
                "the expired and revoked tokens are left out");
        assertEquals(List.of(), users.get(4).roles());
        assertEquals(List.of(), users.get(4).tokens());
    }

    @Test
    void theListOfUsersReadsAFixedNumberOfStatements() {
        many(2);
        int few = statements(Access::users);
        many(10);
        assertEquals(few, statements(Access::users), "as many statements for 25 users as for 5");
    }

    /** {@code count} more persons with a role and a session, and as many technical accounts with a role and a token. */
    private void many(int count) {
        int from = a.users().size();
        for (int i = from; i < from + count; i++) {
            session("person-" + i, Role.DEVELOPER);
            technical("technical-" + i, Role.OPERATOR);
            issue("technical-" + i, "pipeline", 30);
        }
    }

    /** The calls of the store's statements that {@code work} makes on A. */
    private int statements(Consumer<Access> work) {
        Accounts node = (Accounts) a;
        AtomicInteger calls = new AtomicInteger();
        Function<Tx, Tx> counted = tx -> (Tx) Proxy.newProxyInstance(Tx.class.getClassLoader(), new Class<?>[] {Tx.class}, (proxy, method, args) -> {
            calls.incrementAndGet();
            try {
                return method.invoke(tx, args);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        });
        Accounts counting = new Accounts(clockA, hash, new HashPlaces(), new SecureRandom(), Optional.of(KEY)) {
            @Override protected <T> T query(Function<Tx, T> w) {
                return node.query(tx -> w.apply(counted.apply(tx)));
            }

            @Override protected <T> T transaction(Function<Tx, T> w) {
                return node.transaction(tx -> w.apply(counted.apply(tx)));
            }
        };
        work.accept(counting);
        return calls.get();
    }

    // --- logins

    @Test
    void aLoginRefusalIsTheSameForEveryReasonAndCostsOneHash() {
        String first = person("anna", Role.ADMINISTRATOR);
        Caller anna = changed(a, "anna", first);
        person("bob");
        a.createUser(key(a).acting(Action.USER_CREATE, PackageRef.ANY, REMOTE), "ci-deploy", Kind.TECHNICAL, Set.of());
        a.block(anna.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "bob");

        List<String> texts = new ArrayList<>();
        for (String[] attempt : new String[][] {{"nobody", "a password"}, {"anna", "a wrong password"}, {"ci-deploy", "a password"},
                {"bob", "whatever"}}) {
            int before = hash.matches.get();
            texts.add(refused(401, "login-refused", () -> a.login(attempt[0], attempt[1], Optional.empty(), REMOTE)).getMessage());
            assertEquals(before + 1, hash.matches.get(), attempt[0] + ": one hash, whatever the reason");
        }
        assertEquals(1, Set.copyOf(texts).size(), "one text for every reason: " + texts);
        assertEquals(List.of("unknown-name", "wrong-password", "technical", "blocked"),
                audit("login.refused").stream().map(r -> r.getJsonObject("detail").getString("reason")).toList());
        JsonObject unknown = audit("login.refused").getFirst();
        assertEquals(List.of("nobody", "user/nobody", "session"), List.of(unknown.getString("actor"), unknown.getString("subject"),
                unknown.getString("via")));
        assertEquals(null, unknown.getValue("user"), "an unknown name has no user");
        assertTrue(audit("login.refused").get(1).getValue("user") != null, "a known name names its user");

        String session = login(a, "ANNA", NEW_PASSWORD);
        JsonObject login = audit("login").getLast();
        assertEquals(List.of("anna", "user/anna", "session", session.substring(7, 23)), List.of(login.getString("actor"),
                login.getString("subject"), login.getString("via"), login.getString("credential")), "a name in any case; the session's id");
    }

    @Test
    void theFirstFiveFailuresAreFreeThenEachWaits() {
        person("anna");
        for (int i = 1; i <= 5; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        int before = hash.matches.get();
        AccessRefused wait = refused(429, "too-many-attempts", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        assertEquals(1, wait.retryAfter().getAsLong());
        refused(429, "too-many-attempts", () -> b.login("anna", "wrong password", Optional.empty(), REMOTE));
        assertEquals(before, hash.matches.get(), "a refused attempt hashes nothing");

        clockA.advance(Duration.ofSeconds(1));
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));   // the sixth failure: the next waits 2 s
        clockA.advance(Duration.ofMillis(1500));
        assertEquals(1, refused(429, "too-many-attempts", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE)).retryAfter().getAsLong(),
                "half a second left: one second, rounded up");

        assertEquals(List.of(1), audit("login.refused").stream().map(r -> r.getJsonObject("detail").getInteger("failures")).toList(),
                "the first failure of a run is written, then at most one an hour");
        clockA.advance(Duration.ofHours(1));
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        assertEquals(List.of(1, 7), audit("login.refused").stream().map(r -> r.getJsonObject("detail").getInteger("failures")).toList(),
                "an hour later, one more, with the failures since");
    }

    @Test
    void aNodeWhoseClockIsBehindDoesNotShortenAWait() {
        person("anna");
        for (int i = 1; i <= 4; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        refused(401, "login-refused", () -> b.login("anna", "wrong password", Optional.empty(), REMOTE));   // the fifth, on B, 20 s behind
        clockA.advance(Duration.ofMillis(500));
        refused(429, "too-many-attempts", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
    }

    @Test
    void aSuccessForgivesOnlyTheFailuresUpToItsOwnClaim() {
        String first = person("anna");
        for (int i = 1; i <= 3; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        // while the success hashes, B counts a fifth failure after its claim, the fourth
        hash.duringNextMatch(() -> refused(401, "login-refused", () -> b.login("anna", "wrong password", Optional.empty(), REMOTE)));
        login(a, "anna", first);

        for (int i = 1; i <= 4; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        refused(429, "too-many-attempts", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));   // B's failure stood: 1 + 4
    }

    @Test
    void aLoginWhoseUserChangedWhileItHashedIsRefused() {
        String first = person("anna");
        hash.duringNextMatch(() -> a.endSessions(key(a).acting(Action.SESSION_END, PackageRef.ANY, REMOTE), "anna"));
        refused(401, "login-refused", () -> a.login("anna", first, Optional.empty(), REMOTE));
        assertEquals("account-changed", audit("login.refused").getLast().getJsonObject("detail").getString("reason"));
        assertEquals(List.of(), a.user("anna").orElseThrow().sessions(), "no session was made");
        assertFalse(login(a, "anna", first).isEmpty(), "the next login holds");
    }

    @Test
    void aLoginHeldWhileTheOtherNodeResetsThePasswordMakesNoSession() {
        changed(a, "anna", person("anna"));
        String wrong = refused(401, "login-refused", () -> a.login("anna", "a wrong password", Optional.empty(), REMOTE)).getMessage();
        hash.duringNextMatch(() -> b.resetPassword(key(b).acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "anna"));
        AccessRefused held = refused(401, "login-refused", () -> a.login("anna", NEW_PASSWORD, Optional.of(""), REMOTE));
        assertEquals(wrong, held.getMessage(), "the one text of a refused login");
        assertEquals("account-changed", audit("login.refused").getLast().getJsonObject("detail").getString("reason"));
        assertEquals(List.of(), a.user("anna").orElseThrow().sessions(), "no session was made");
        assertEquals(List.of(), devices("anna"), "and no device");
    }

    /**
     * The login limit counts at most 100,000 names, a login in flight among them once it claimed. With that login holding the
     * last name, an unknown name is refused without a row, hashed as any other; a known name and a name that has its row
     * already are counted still.
     */
    @Test
    void theCapOfNamesHoldsWithALoginInFlight() {
        person("anna");
        limitNames(Accounts.NAMES_MAX - 1);
        hash.duringNextMatch(() -> {
            assertEquals(Accounts.NAMES_MAX, limited(), "the login in flight claimed the last name");
            int hashed = hash.matches.get();
            refused(401, "login-refused", () -> b.login("ghost", "a password", Optional.empty(), REMOTE));
            assertEquals(hashed + 1, hash.matches.get(), "the unknown name is hashed as any other");
            assertEquals(Optional.empty(), failures("ghost"), "and refused without a row");
            refused(401, "login-refused", () -> b.login("anna", "wrong password", Optional.empty(), REMOTE));
            refused(401, "login-refused", () -> b.login("limited-1", "a password", Optional.empty(), REMOTE));
        });
        refused(401, "login-refused", () -> a.login("in-flight", "a password", Optional.empty(), REMOTE));
        assertEquals(List.of(Optional.of(1), Optional.of(1), Optional.of(2)), List.of(failures("in-flight"), failures("anna"), failures("limited-1")));
        assertEquals(Accounts.NAMES_MAX + 1, limited(), "the cap passed by the known name alone");
    }

    /** The installation writes at most 1,000 {@code login.refused} rows an hour; the refusals beyond them are not written. */
    @Test
    void atMostAThousandRefusedLoginsAreWrittenAnHour() {
        for (int i = 0; i <= 1000; i++) {
            String name = "guess-" + i;
            refused(401, "login-refused", () -> a.login(name, "a password", Optional.empty(), REMOTE));
        }
        assertEquals(1000, audit("login.refused").size(), "the thousand and first is not written");
        clockA.advance(Duration.ofHours(1));
        refused(401, "login-refused", () -> a.login("guess-of-the-next-hour", "a password", Optional.empty(), REMOTE));
        assertEquals("user/guess-of-the-next-hour", audit("login.refused").getLast().getString("subject"), "the next hour writes again");
    }

    @Test
    void aLoginWithOlderParametersHashesAgain() {
        String first = person("anna");
        Accounts accounts = (Accounts) a;
        long id = accounts.query(tx -> tx.user("anna", Tx.Lock.NONE)).orElseThrow().id();
        accounts.transaction(tx -> { tx.password(id, "old$" + first, true, T0); return null; });
        login(a, "anna", first);
        assertEquals(Optional.of(FakeHash.PREFIX + first), accounts.query(tx -> tx.user("anna", Tx.Lock.NONE)).orElseThrow().password());
    }

    @Test
    void aLoginWithoutADeviceTakesOnlyTheSharedPlace() throws Exception {
        HashPlaces places = new HashPlaces();
        Access node = node(clockA, hash, places, Optional.of(KEY));
        String first = person("anna", Role.ADMINISTRATOR);
        Caller anna = changed(node, "anna", first);
        person("bob");
        CountDownLatch hashing = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        hash.duringNextMatch(() -> {
            hashing.countDown();
            try {
                assertTrue(done.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        AtomicReference<Throwable> held = new AtomicReference<>();
        Thread login = Thread.ofVirtual().start(() -> {
            try {
                node.login("anna", NEW_PASSWORD, Optional.empty(), REMOTE);
            } catch (Throwable t) {
                held.set(t);
            }
        });
        assertTrue(hashing.await(20, TimeUnit.SECONDS));
        int before = hash.matches.get();
        refused(503, "busy", () -> node.login("bob", "a password", Optional.empty(), REMOTE));
        assertEquals(before, hash.matches.get(), "nothing hashed");
        assertFalse(node.resetPassword(anna.acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "bob").isEmpty(),
                "a reset takes the second place");
        done.countDown();
        login.join();
        assertEquals(null, held.get(), "the login that held the shared place went through");
    }

    // --- devices

    /**
     * Someone keeps failing anna's name into its wait and holds the node's shared place with a login on the latch: a login
     * without a valid device is 503 with nothing counted or hashed, while anna's browser hashes on the second place, gets in
     * and is issued a new device. The name's row still waits.
     */
    @Test
    void aFloodDoesNotKeepOutAKnownBrowser() throws Exception {
        HashPlaces places = new HashPlaces();
        Access node = node(clockA, hash, places, Optional.of(KEY));
        changed(a, "anna", person("anna"));
        String known = device(a, "anna", NEW_PASSWORD, "");
        for (int i = 1; i <= 5; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        refused(429, "too-many-attempts", () -> a.login("anna", NEW_PASSWORD, Optional.of(""), REMOTE));
        holdingTheSharedPlace(node, () -> {
            int before = hash.matches.get();
            refused(503, "busy", () -> node.login("anna", NEW_PASSWORD, Optional.of(""), REMOTE));
            assertEquals(before, hash.matches.get(), "nothing hashed");
            assertEquals(Optional.of(5), failures("anna"), "nothing counted");
            Access.Login in = node.login("anna", NEW_PASSWORD, Optional.of(known), REMOTE);
            assertEquals("anna", in.caller().name());
            assertTrue(in.device().filter(d -> !d.equals(known)).isPresent(), "a new device");
        });
        refused(429, "too-many-attempts", () -> a.login("anna", NEW_PASSWORD, Optional.empty(), REMOTE));
    }

    /**
     * A stolen device buys only its own row's 5 free failures and waits, the same on both nodes, and leaves the name's row alone;
     * it dies at the user's next login from that browser.
     */
    @Test
    void aStolenDeviceBuysOnlyItsOwnRowsGuesses() {
        changed(a, "anna", person("anna"));
        String stolen = device(a, "anna", NEW_PASSWORD, "");
        Optional<Integer> name = failures("anna");
        String text = refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE)).getMessage();
        for (int i = 1; i <= 5; i++) {
            assertEquals(text, refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(stolen), REMOTE)).getMessage());
        }
        assertEquals(1, refused(429, "too-many-attempts", () -> a.login("anna", "wrong password", Optional.of(stolen), REMOTE)).retryAfter()
                .getAsLong());
        refused(429, "too-many-attempts", () -> b.login("anna", "wrong password", Optional.of(stolen), REMOTE));
        assertEquals(Optional.of(5), failures("anna", idOf(stolen)));
        assertEquals(name.map(n -> n + 1), failures("anna"), "the name's row counted only the one failure without the device");
        assertEquals(idOf(stolen), audit("login.refused").getLast().getJsonObject("detail").getString("device"), "the refusal names the device");

        clockA.advance(Duration.ofSeconds(1));
        String next = device(a, "anna", NEW_PASSWORD, stolen);
        assertEquals(name.map(n -> n + 1), failures("anna"), "a device's success leaves the name's row alone");
        assertEquals(Optional.empty(), failures("anna", idOf(stolen)), "the replaced device's row is gone");
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(stolen), REMOTE));
        assertEquals(name.map(n -> n + 2), failures("anna"), "the stolen copy now counts as none");
        assertEquals(Optional.empty(), failures("anna", idOf(stolen)));
        assertEquals(List.of(idOf(next)), devices("anna").stream().map(Tx.DeviceRow::id).toList());
    }

    /**
     * A reset ends every device of the user, on either node: each one then counts on the name's row and is 503 while the shared
     * place is held; the first password's login gets no device, the next full login a new one. A block, the user's own password
     * change and an end of sessions end them too.
     */
    @Test
    void aResetEndsEveryDevice() throws Exception {
        HashPlaces places = new HashPlaces();
        Access node = node(clockA, hash, places, Optional.of(KEY));
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        changed(a, "anna", person("anna"));
        String one = device(a, "anna", NEW_PASSWORD, "");
        String two = device(b, "anna", NEW_PASSWORD, "");
        String first = b.resetPassword(key(b).acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "anna");
        holdingTheSharedPlace(node, () -> {
            refused(503, "busy", () -> node.login("anna", first, Optional.of(one), REMOTE));
            refused(503, "busy", () -> node.login("anna", first, Optional.of(two), REMOTE));
        });
        for (String ended : List.of(one, two)) {
            Optional<Integer> before = failures("anna");
            refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(ended), REMOTE));
            assertEquals(Optional.of(before.orElse(0) + 1), failures("anna"), "counted on the name's row");
        }
        String session = a.login("anna", first, Optional.of(one), REMOTE).session();
        assertEquals(Optional.empty(), a.login("anna", first, Optional.of(""), REMOTE).device(), "a first password gets no device");
        Caller anna = a.authenticate(bearer(session), Optional.empty());
        a.changePassword(anna.acting(Action.SELF_PASSWORD, PackageRef.ANY, REMOTE), first, NEW_PASSWORD);
        String three = device(a, "anna", NEW_PASSWORD, one);

        String held = three;
        String password = NEW_PASSWORD;
        for (String ender : List.of("a block", "a password change", "an end of sessions")) {
            switch (ender) {
                case "a block" -> {
                    a.block(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "anna");
                    a.unblock(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "anna");
                }
                case "a password change" -> {
                    Caller self = a.authenticate(bearer(login(a, "anna", password)), Optional.empty());
                    a.changePassword(self.acting(Action.SELF_PASSWORD, PackageRef.ANY, REMOTE), password, "another password of mine");
                    password = "another password of mine";
                }
                default -> a.endSessions(root.acting(Action.SESSION_END, PackageRef.ANY, REMOTE), "anna");
            }
            String gone = held;
            Optional<Integer> before = failures("anna");
            refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(gone), REMOTE));
            assertEquals(Optional.of(before.orElse(0) + 1), failures("anna"), ender + " ended the device: it counts on the name's row");
            held = device(a, "anna", password, gone);
            assertTrue(devices("anna").stream().noneMatch(d -> d.id().equals(idOf(gone))), "the ended device's row went at the next issue");
        }
    }

    /** A device is issued only to a full login that asks; it is kept as its fingerprint, and the audit names it by its id. */
    @Test
    void aDeviceIsIssuedOnlyToAFullLoginThatAsks() {
        String first = person("anna");
        assertEquals(Optional.empty(), a.login("anna", first, Optional.of(""), REMOTE).device(), "a first password gets none");
        changed(a, "anna", first);
        assertEquals(Optional.empty(), a.login("anna", NEW_PASSWORD, Optional.empty(), REMOTE).device(), "a login that does not ask gets none");
        assertEquals(List.of(), devices("anna"));
        String issued = device(a, "anna", NEW_PASSWORD, "");
        assertTrue(issued.matches("rail8d_[0-9a-f]{16}_[A-Za-z0-9_-]{43}"), issued);
        Tx.DeviceRow row = devices("anna").getFirst();
        assertEquals(List.of(idOf(issued), Credentials.fingerprint(issued), T0, T0.plus(Duration.ofDays(90))),
                List.of(row.id(), row.fingerprint(), row.createdAt(), row.expiresAt()), "only its fingerprint, for 90 days");
        JsonObject login = audit("login").getLast().getJsonObject("detail");
        assertEquals(idOf(issued), login.getString("issued"));
        assertFalse(login.containsKey("device"), "it presented none");
        String next = device(a, "anna", NEW_PASSWORD, issued);
        JsonObject replaced = audit("login").getLast().getJsonObject("detail");
        assertEquals(List.of(idOf(issued), idOf(next)), List.of(replaced.getString("device"), replaced.getString("issued")));
        assertFalse(audit().toString().contains(issued.substring(24)) || audit().toString().contains(next.substring(24)), "never a secret");
    }

    /**
     * A malformed value, a real id with a wrong secret, another user's valid device and a session credential each behave as no
     * device: the name's row, the shared place only, and a new device on success. The other user's device stays valid.
     */
    @Test
    void aDeviceThatIsNotTheUsersValidOneCountsAsNone() throws Exception {
        HashPlaces places = new HashPlaces();
        Access node = node(clockA, hash, places, Optional.of(KEY));
        changed(a, "anna", person("anna"));
        changed(a, "bob", person("bob"));
        String annas = device(a, "anna", NEW_PASSWORD, "");
        String bobs = device(a, "bob", NEW_PASSWORD, "");
        String session = login(a, "anna", NEW_PASSWORD);
        List<String> others = List.of("rail8d_not-a-device", annas.substring(0, 24) + "w".repeat(43), bobs, session);
        holdingTheSharedPlace(node, () -> {
            for (String other : others) refused(503, "busy", () -> node.login("anna", NEW_PASSWORD, Optional.of(other), REMOTE));
        });
        for (String other : others) {
            Optional<Integer> before = failures("anna");
            refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(other), REMOTE));
            assertEquals(Optional.of(before.orElse(0) + 1), failures("anna"), other + " counts on the name's row");
            assertFalse(audit("login.refused").getLast().getJsonObject("detail").containsKey("device"));
            Access.Login in = a.login("anna", NEW_PASSWORD, Optional.of(other), REMOTE);
            assertTrue(in.device().isPresent(), "a new device on success");
            assertFalse(audit("login").getLast().getJsonObject("detail").containsKey("device"), "it presented none that counts");
        }
        assertTrue(devices("anna").stream().anyMatch(d -> d.id().equals(idOf(annas))), "anna's device, presented with a wrong secret, stays");
        refused(401, "login-refused", () -> a.login("bob", "wrong password", Optional.of(bobs), REMOTE));
        assertEquals(Optional.of(1), failures("bob", idOf(bobs)), "bob's device stays valid");
    }

    /** A device is valid until 90 days after its issue, by the clock of the node that judges it. */
    @Test
    void aDeviceLivesNinetyDays() {
        changed(a, "anna", person("anna"));
        String issued = device(a, "anna", NEW_PASSWORD, "");
        clockA.set(T0.plus(Duration.ofDays(90)).minusMillis(1));
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(issued), REMOTE));
        assertEquals(Optional.of(1), failures("anna", idOf(issued)), "valid at 90 days less a millisecond");
        clockA.set(T0.plus(Duration.ofDays(90)));
        Optional<Integer> before = failures("anna");
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(issued), REMOTE));
        assertEquals(Optional.of(before.orElse(0) + 1), failures("anna"), "dead at exactly 90 days");
        device(a, "anna", NEW_PASSWORD, issued);
        assertTrue(devices("anna").stream().noneMatch(d -> d.id().equals(idOf(issued))), "the dead device went at the next issue");
    }

    /** The eleventh device ends the oldest, dead devices go at an issue, and a user never holds more than 10 rows. */
    @Test
    void theEleventhDeviceEndsTheOldest() {
        changed(a, "anna", person("anna"));
        List<String> held = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clockA.advance(Duration.ofSeconds(1));
            held.add(device(a, "anna", NEW_PASSWORD, ""));
        }
        assertEquals(10, devices("anna").size());
        clockA.advance(Duration.ofSeconds(1));
        String eleventh = device(a, "anna", NEW_PASSWORD, "");
        List<String> ids = devices("anna").stream().map(Tx.DeviceRow::id).sorted().toList();
        List<String> expected = new ArrayList<>(held.subList(1, 10).stream().map(AccessContract::idOf).toList());
        expected.add(idOf(eleventh));
        assertEquals(expected.stream().sorted().toList(), ids, "the oldest ended");

        a.endSessions(key(a).acting(Action.SESSION_END, PackageRef.ANY, REMOTE), "anna");
        assertEquals(10, devices("anna").size(), "dead rows stay until the next issue");
        String after = device(a, "anna", NEW_PASSWORD, "");
        assertEquals(List.of(idOf(after)), devices("anna").stream().map(Tx.DeviceRow::id).toList(), "every dead one went");
    }

    /**
     * Two nodes issuing a user's tenth device at once leave ten. The two logins' hashes meet and leave together, so both go on to
     * issue at once, while the user holds no session to end; ten users, one round each.
     */
    @Test
    void twoNodesIssuingTheTenthDeviceAtOnceLeaveTen() throws Exception {
        for (int round = 0; round < 10; round++) {
            String name = "dora-" + round;
            a.logout(changed(a, name, person(name)).acting(Action.SELF_LOGOUT, PackageRef.ANY, REMOTE));
            for (int i = 0; i < 9; i++) {   // each session ends, so the two logins below race only on the devices
                Access.Login held = a.login(name, NEW_PASSWORD, Optional.of(""), REMOTE);
                a.logout(a.authenticate(bearer(held.session()), Optional.empty()).acting(Action.SELF_LOGOUT, PackageRef.ANY, REMOTE));
            }
            CountDownLatch second = new CountDownLatch(1);
            CyclicBarrier bothHashing = new CyclicBarrier(2);
            Runnable meet = () -> {
                try {
                    bothHashing.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            hash.duringNextMatch(() -> {
                hash.duringNextMatch(meet);
                second.countDown();
                meet.run();
            });
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread onA = Thread.ofVirtual().start(() -> {
                try {
                    device(a, name, NEW_PASSWORD, "");
                } catch (Throwable t) {
                    failed.set(t);
                }
            });
            Thread onB = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(second.await(20, TimeUnit.SECONDS));
                    device(b, name, NEW_PASSWORD, "");
                } catch (Throwable t) {
                    failed.set(t);
                }
            });
            onA.join();
            onB.join();
            assertEquals(null, failed.get(), "round " + round);
            assertEquals(10, devices(name).size(), "round " + round);
        }
    }

    /**
     * Two logins presenting one device at once: the first replacement wins. The other login gets its session and no device, and
     * ends no other device.
     */
    @Test
    void theFirstOfTwoLoginsPresentingOneDeviceReplacesIt() {
        changed(a, "anna", person("anna"));
        String other = device(a, "anna", NEW_PASSWORD, "");
        String shared = device(a, "anna", NEW_PASSWORD, "");
        AtomicReference<Access.Login> second = new AtomicReference<>();
        hash.duringNextMatch(() -> second.set(b.login("anna", NEW_PASSWORD, Optional.of(shared), REMOTE)));
        Access.Login first = a.login("anna", NEW_PASSWORD, Optional.of(shared), REMOTE);
        String successor = second.get().device().orElseThrow();
        assertEquals(Optional.empty(), first.device(), "the later one gets no device");
        a.authenticate(bearer(first.session()), Optional.empty());
        assertEquals(List.of(idOf(other), idOf(successor)).stream().sorted().toList(),
                devices("anna").stream().map(Tx.DeviceRow::id).sorted().toList(), "no other device ended");
        JsonObject loser = audit("login").getLast().getJsonObject("detail");
        assertEquals(idOf(shared), loser.getString("device"));
        assertFalse(loser.containsKey("issued"));
    }

    /**
     * A refusal on a device's row is written once an hour, naming the device. One whose row a reset deleted during the hash is
     * written as a first failure, and inserts no row.
     */
    @Test
    void aRefusalOnADevicesRowIsWrittenOnceAnHourAndInsertsNoRow() {
        changed(a, "anna", person("anna"));
        String issued = device(a, "anna", NEW_PASSWORD, "");
        int before = audit("login.refused").size();
        for (int i = 0; i < 3; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(issued), REMOTE));
        assertEquals(before + 1, audit("login.refused").size(), "the first failure of the run");
        clockA.advance(Duration.ofHours(1));
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(issued), REMOTE));
        assertEquals(List.of(4, idOf(issued)), List.of(audit("login.refused").getLast().getJsonObject("detail").getInteger("failures"),
                audit("login.refused").getLast().getJsonObject("detail").getString("device")), "an hour later, one more");

        int written = audit("login.refused").size();
        hash.duringNextMatch(() -> b.resetPassword(key(b).acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "anna"));
        refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(issued), REMOTE));
        assertEquals(written + 1, audit("login.refused").size(), "the row the reset deleted writes as a first failure");
        assertEquals(Optional.empty(), failures("anna", idOf(issued)), "and no row is inserted");
    }

    /** After many replacing logins, the name has no row of the login limit for a device it no longer holds. */
    @Test
    void aReplacedDevicesRowOfTheLimitGoes() {
        changed(a, "anna", person("anna"));
        String held = device(a, "anna", NEW_PASSWORD, "");
        List<String> replaced = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String now = held;
            refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.of(now), REMOTE));
            assertEquals(Optional.of(1), failures("anna", idOf(now)));
            replaced.add(now);
            held = device(a, "anna", NEW_PASSWORD, now);
        }
        for (String gone : replaced) assertEquals(Optional.empty(), failures("anna", idOf(gone)), "a replaced device's row is deleted");
        assertEquals(1, devices("anna").size());
    }

    // --- sessions

    @Test
    void aSessionEndsAfterTwoHoursIdleAndTwelveHoursAfterItsStart() {
        String session = session("anna");
        assertEquals("anna", b.authenticate(bearer(session), Optional.empty()).name(), "on any node");
        clockA.advance(Duration.ofHours(2));
        assertEquals("anna", a.authenticate(bearer(session), Optional.empty()).name(), "two hours idle, not more");
        assertEquals(Optional.of(T0.plus(Duration.ofHours(12))), a.expiresAt(a.authenticate(bearer(session), Optional.empty())));
        clockA.advance(Duration.ofHours(2).plusSeconds(1));
        refused(401, "session-ended", () -> a.authenticate(bearer(session), Optional.empty()));

        String busy = login(a, "anna", NEW_PASSWORD);
        Instant start = clockA.instant();
        for (int i = 0; i < 11; i++) {
            clockA.advance(Duration.ofHours(1));
            a.authenticate(bearer(busy), Optional.empty());
        }
        clockA.set(start.plus(Duration.ofHours(12)).minusMillis(1));
        a.authenticate(bearer(busy), Optional.empty());
        clockA.advance(Duration.ofMillis(1));
        refused(401, "session-ended", () -> a.authenticate(bearer(busy), Optional.empty()));
        assertEquals(Optional.empty(), a.expiresAt(key(a)), "no session, no end");
    }

    @Test
    void aSessionEndsWithItsLogoutItsUsersBlockOrAnEndOfItsSessions() {
        String admin = session("root", Role.ADMINISTRATOR);
        Caller root = a.authenticate(bearer(admin), Optional.empty());
        String one = session("anna");
        String two = login(a, "anna", NEW_PASSWORD);
        Caller anna = a.authenticate(bearer(one), Optional.empty());

        b.authenticate(bearer(one), Optional.empty());
        b.authenticate(bearer(two), Optional.empty());
        a.logout(anna.acting(Action.SELF_LOGOUT, PackageRef.ANY, REMOTE));
        refused(401, "unknown-credential", () -> a.authenticate(bearer(one), Optional.empty()));
        refused(401, "unknown-credential", () -> b.authenticate(bearer(one), Optional.empty()));
        assertEquals(List.of("anna", "user/anna"), List.of(audit("logout").getFirst().getString("actor"), audit("logout").getFirst().getString("subject")));

        a.endSessions(root.acting(Action.SESSION_END, PackageRef.ANY, REMOTE), "anna");
        refused(401, "unknown-credential", () -> a.authenticate(bearer(two), Optional.empty()));
        refused(401, "unknown-credential", () -> b.authenticate(bearer(two), Optional.empty()));
        assertEquals(2, audit("session.end").getFirst().getJsonObject("detail").getInteger("sessions"),
                "the session the password was changed in and the second");

        String three = login(a, "anna", NEW_PASSWORD);
        b.authenticate(bearer(three), Optional.empty());
        a.block(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "anna");
        refused(401, "unknown-credential", () -> a.authenticate(bearer(three), Optional.empty()));
        refused(401, "unknown-credential", () -> b.authenticate(bearer(three), Optional.empty()));
        refused(401, "login-refused", () -> a.login("anna", NEW_PASSWORD, Optional.empty(), REMOTE));
        assertTrue(a.user("anna").orElseThrow().blockedAt().isPresent());
        a.unblock(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "anna");
        assertFalse(login(a, "anna", NEW_PASSWORD).isEmpty());
        refused(409, "block-self", () -> a.block(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "root"));
        refused(404, "no-user", () -> a.block(root.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "nobody"));
    }

    @Test
    void theEleventhSessionEndsTheOldest() {
        String oldest = session("anna");
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            clockA.advance(Duration.ofSeconds(1));
            sessions.add(login(a, "anna", NEW_PASSWORD));
        }
        refused(401, "unknown-credential", () -> a.authenticate(bearer(oldest), Optional.empty()));
        for (String s : sessions) a.authenticate(bearer(s), Optional.empty());
        assertEquals(10, a.user("anna").orElseThrow().sessions().size());
    }

    @Test
    void aResetGivesAFirstPasswordEndsEverySessionAndForgetsTheLimit() {
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        String session = session("anna");
        for (int i = 0; i < 5; i++) refused(401, "login-refused", () -> a.login("anna", "wrong password", Optional.empty(), REMOTE));
        String first = a.resetPassword(root.acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "anna");
        refused(401, "unknown-credential", () -> a.authenticate(bearer(session), Optional.empty()));
        assertTrue(a.authenticate(bearer(login(a, "anna", first)), Optional.empty()).firstPassword(), "no wait: the limit's rows are gone");
        a.createUser(root.acting(Action.USER_CREATE, PackageRef.ANY, REMOTE), "ci-deploy", Kind.TECHNICAL, Set.of());
        refused(409, "technical-has-no-password", () -> a.resetPassword(root.acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "ci-deploy"));
        refused(404, "no-user", () -> a.resetPassword(root.acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "nobody"));
        assertEquals(1, audit("user.password-reset").size());
    }

    // --- roles

    @Test
    void rolesChangeByDifference() {
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        Actor by = root.acting(Action.ROLES_CHANGE, PackageRef.ANY, REMOTE);
        person("bob", Role.OPERATOR);

        Access.RolesChanged added = a.changeRoles(by, "bob", Set.of(Role.DEVELOPER, Role.OPERATOR), Set.of());
        assertEquals(List.of(Set.of(Role.DEVELOPER, Role.OPERATOR), Set.of(Role.DEVELOPER), Set.of()),
                List.of(added.roles(), added.added(), added.removed()));
        Access.RolesChanged removed = b.changeRoles(by, "bob", Set.of(), Set.of(Role.OPERATOR, Role.MESSAGE_BODIES));
        assertEquals(List.of(Set.of(Role.DEVELOPER), Set.of(), Set.of(Role.OPERATOR)), List.of(removed.roles(), removed.added(), removed.removed()));
        assertEquals(new JsonObject().put("added", new JsonArray()).put("removed", new JsonArray().add("operator")).put("self", false)
                .put("remote", REMOTE), audit("user.roles").getLast().getJsonObject("detail"));
        int rows = audit().size();
        a.changeRoles(by, "bob", Set.of(Role.DEVELOPER), Set.of(Role.OPERATOR));
        assertEquals(rows, audit().size(), "a change that changes nothing writes nothing");

        refused(400, "bad-request", () -> a.changeRoles(by, "bob", Set.of(Role.OPERATOR), Set.of(Role.OPERATOR)));
        refused(404, "no-user", () -> a.changeRoles(by, "nobody", Set.of(Role.OPERATOR), Set.of()));
        a.createUser(by, "ci-deploy", Kind.TECHNICAL, Set.of(Role.DEVELOPER));
        refused(409, "technical-administrator", () -> a.changeRoles(by, "ci-deploy", Set.of(Role.ADMINISTRATOR), Set.of()));
        Access.Grant grant = a.user("bob").orElseThrow().roles().getFirst();
        assertEquals(List.of(Role.DEVELOPER, "root", "session"), List.of(grant.role(), grant.grantedBy(), grant.grantedVia()));
    }

    @Test
    void aRoleTakenAwayIsRefusedAtTheOtherNodesNextRequest() {
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        String bob = session("bob", Role.OPERATOR);
        b.authenticate(bearer(bob), Optional.empty()).require(Action.MESSAGE_RETRY, PackageRef.ANY);
        a.changeRoles(root.acting(Action.ROLES_CHANGE, PackageRef.ANY, REMOTE), "bob", Set.of(), Set.of(Role.OPERATOR));
        Caller next = b.authenticate(bearer(bob), Optional.empty());
        refused(403, "not-allowed", () -> next.require(Action.MESSAGE_RETRY, PackageRef.ANY));
    }

    @Test
    void theLastUnblockedAdministratorStays() {
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        refused(409, "last-administrator", () -> a.changeRoles(root.acting(Action.ADMINISTRATORS_CHANGE, PackageRef.ANY, REMOTE), "root", Set.of(),
                Set.of(Role.ADMINISTRATOR)));
        refused(409, "last-administrator", () -> a.block(key(a).acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), "root"));
    }

    /**
     * Two administrators, each on a node of their own, block each other at once (even rounds) or take each other's role (odd
     * rounds): one of the two acts is refused, so one unblocked administrator is left. Each round starts with its own two as the
     * only ones, the emergency key having blocked the one the round before left.
     */
    @Test
    void twoAdministratorsActingOnEachOtherAtOnceLeaveOne() throws Exception {
        for (int round = 0; round < 10; round++) {
            String one = "one-" + round;
            String two = "two-" + round;
            Caller x = a.authenticate(bearer(session(one, Role.ADMINISTRATOR)), Optional.empty());
            Caller y = b.authenticate(bearer(session(two, Role.ADMINISTRATOR)), Optional.empty());
            for (Access.User u : a.users()) {
                if (!u.name().equals(one) && !u.name().equals(two) && administrator(u)) {
                    a.block(key(a).acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), u.name());
                }
            }
            boolean block = round % 2 == 0;
            CountDownLatch start = new CountDownLatch(1);
            Thread first = Thread.ofVirtual().start(() -> attempt(start, () -> act(a, x, two, block)));
            Thread second = Thread.ofVirtual().start(() -> attempt(start, () -> act(b, y, one, block)));
            start.countDown();
            first.join();
            second.join();
            assertEquals(1, a.users().stream().filter(AccessContract::administrator).count(), "round " + round + ": one administrator is left");
        }
    }

    private static boolean administrator(Access.User u) {
        return u.blockedAt().isEmpty() && u.roles().stream().anyMatch(g -> g.role() == Role.ADMINISTRATOR);
    }

    private static void act(Access node, Caller by, String other, boolean block) {
        if (block) node.block(by.acting(Action.USER_BLOCK, PackageRef.ANY, REMOTE), other);
        else node.changeRoles(by.acting(Action.ADMINISTRATORS_CHANGE, PackageRef.ANY, REMOTE), other, Set.of(), Set.of(Role.ADMINISTRATOR));
    }

    private static void attempt(CountDownLatch start, Runnable act) {
        try {
            assertTrue(start.await(20, TimeUnit.SECONDS));
            act.run();
        } catch (AccessRefused lastAdministrator) {
            assertEquals(409, lastAdministrator.status(), lastAdministrator.getMessage());
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tokens

    @Test
    void aPersonIssuesTheirOwnTokenWithThePasswordAskedAgain() {
        String session = session("anna", Role.OPERATOR);
        Actor self = a.authenticate(bearer(session), Optional.empty()).acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        Access.Issued issued = a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty());
        assertTrue(issued.token().matches("rail8t_[0-9a-f]{16}_[A-Za-z0-9_-]{43}"), issued.token());
        assertEquals(List.of(issued.token().substring(7, 23), "laptop", T0.plus(Duration.ofDays(90))),
                List.of(issued.id(), issued.name(), issued.expiresAt()), "the id from the token; 90 days by default");

        Caller cli = b.authenticate(bearer(issued.token()), Optional.empty());
        assertEquals(List.of(Via.TOKEN, "anna", Kind.PERSON, Set.of(Role.OPERATOR), Optional.of(issued.id()), false),
                List.of(cli.via(), cli.name(), cli.kind(), cli.roles(), cli.credential(), cli.firstPassword()), "on any node");
        assertTrue(cli.may(Action.MESSAGE_RETRY, PackageRef.ANY), "the user's roles");
        assertEquals(Optional.of(issued.expiresAt()), b.expiresAt(cli));

        JsonObject row = audit("token.issue").getFirst();
        assertEquals(List.of("anna", "user/anna", "session", session.substring(7, 23)), List.of(row.getString("actor"),
                row.getString("subject"), row.getString("via"), row.getString("credential")));
        assertEquals(new JsonObject().put("token", issued.id()).put("name", "laptop").put("expiresAt", issued.expiresAt().toString())
                .put("remote", REMOTE), row.getJsonObject("detail"));
        assertFalse(audit().toString().contains(issued.token()), "never a token in the audit");
        assertEquals(Optional.of(Credentials.fingerprint(issued.token())),
                ((Accounts) a).query(tx -> tx.token(issued.id())).map(t -> t.token().fingerprint()), "only its fingerprint is kept");

        assertEquals(T0.plus(Duration.ofDays(365)), a.issueOwnToken(self, NEW_PASSWORD, "a-year", OptionalInt.of(365)).expiresAt());
        refused(400, "token-life", () -> a.issueOwnToken(self, NEW_PASSWORD, "beyond", OptionalInt.of(366)));
        refused(400, "token-life", () -> a.issueOwnToken(self, NEW_PASSWORD, "never", OptionalInt.of(0)));
        refused(400, "bad-token-name", () -> a.issueOwnToken(self, NEW_PASSWORD, "Laptop", OptionalInt.empty()));
        refused(400, "bad-token-name", () -> a.issueOwnToken(self, NEW_PASSWORD, "-laptop", OptionalInt.empty()));
        refused(409, "token-name-taken", () -> b.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty()));
        assertEquals(2, audit("token.issue").size());
    }

    @Test
    void aWrongPasswordIsRefusedAndCountedOnTheLoginLimit() {
        String session = session("anna");
        Actor self = a.authenticate(bearer(session), Optional.empty()).acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        for (int i = 1; i <= 5; i++) {
            refused(403, "wrong-password", () -> a.issueOwnToken(self, "a wrong password", "laptop", OptionalInt.empty()));
        }
        assertEquals(Optional.of(5), failures("anna"));
        assertEquals("wrong-password", audit("login.refused").getFirst().getJsonObject("detail").getString("reason"));
        assertEquals("anna", a.authenticate(bearer(session), Optional.empty()).name(), "the session goes on");
        refused(429, "too-many-attempts", () -> b.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty()));
        refused(429, "too-many-attempts", () -> a.login("anna", NEW_PASSWORD, Optional.empty(), REMOTE));

        clockA.advance(Duration.ofSeconds(1));
        a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty());
        assertEquals(Optional.of(0), failures("anna"), "a success forgives the failures up to its claim");
    }

    @Test
    void aTokenIsNotIssuedWhenTheAccountChangedWhileThePasswordWasChecked() {
        Caller anna = a.authenticate(bearer(session("anna")), Optional.empty());
        Actor self = anna.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        String other = "another password of mine";
        hash.duringNextMatch(() -> b.changePassword(anna.acting(Action.SELF_PASSWORD, PackageRef.ANY, REMOTE), NEW_PASSWORD, other));
        refused(409, "account-changed", () -> a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty()));
        hash.duringNextMatch(() -> b.endSessions(key(b).acting(Action.SESSION_END, PackageRef.ANY, REMOTE), "anna"));
        refused(401, "session-ended", () -> a.issueOwnToken(self, other, "laptop", OptionalInt.empty()));
        assertEquals(List.of(), a.ownTokens(anna).tokens(), "no token was made");
        assertEquals(List.of(), audit("token.issue"));
    }

    @Test
    void aSessionIdlePastItsTimeMakesNoToken() {
        Caller anna = a.authenticate(bearer(session("anna")), Optional.empty());
        Actor self = anna.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        hash.duringNextMatch(() -> clockA.advance(Duration.ofHours(2).plusMillis(1)));   // the session's row is still there
        refused(401, "session-ended", () -> a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty()));
        assertEquals(List.of(), a.ownTokens(anna).tokens(), "no token was made");
    }

    @Test
    void aResetBetweenTheHashAndTheForgiveForgivesNothing() {
        Caller anna = a.authenticate(bearer(session("anna")), Optional.empty());
        Actor self = anna.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        hash.duringNextMatch(() -> {
            b.resetPassword(key(b).acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, REMOTE), "anna");   // deletes the name's limit row
            ((Accounts) b).query(tx -> tx.claim("anna", NAME_ROW, clockB.instant()));   // a wrong first password on the new row
        });
        refused(401, "session-ended", () -> a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty()));
        assertEquals(Optional.of(1), failures("anna"), "the failure after the reset stays counted");
    }

    @Test
    void anAdministratorLoggedInIssuesTheTokensOfATechnicalAccount() {
        technical("ci-deploy", Role.DEVELOPER);
        Access.Issued issued = issue("ci-deploy", "pipeline", 30);
        assertEquals(T0.plus(Duration.ofDays(30)), issued.expiresAt());
        Caller ci = b.authenticate(bearer(issued.token()), Optional.empty());
        assertEquals(List.of(Via.TOKEN, "ci-deploy", Kind.TECHNICAL, Set.of(Role.DEVELOPER)), List.of(ci.via(), ci.name(), ci.kind(), ci.roles()));
        assertTrue(ci.may(Action.PACKAGE_DEPLOY, PackageRef.ANY));
        assertFalse(ci.may(Action.SELF_TOKENS, PackageRef.ANY), "a technical account manages no tokens of its own");
        JsonObject row = audit("token.issue").getFirst();
        assertEquals(List.of("keeper", "user/ci-deploy"), List.of(row.getString("actor"), row.getString("subject")));

        Actor root = admin(Action.TOKEN_ISSUE);
        refused(409, "person-issues-own-tokens", () -> a.issueToken(root, "keeper", "laptop", OptionalInt.empty()));   // a person issues their own
        refused(404, "no-user", () -> a.issueToken(root, "nobody", "pipeline", OptionalInt.empty()));
        refused(409, "token-name-taken", () -> b.issueToken(root, "ci-deploy", "pipeline", OptionalInt.empty()));
        refused(400, "bad-token-name", () -> a.issueToken(root, "ci-deploy", "x".repeat(65), OptionalInt.empty()));
        a.block(admin(Action.USER_BLOCK), "ci-deploy");
        refused(409, "user-blocked", () -> a.issueToken(root, "ci-deploy", "another", OptionalInt.empty()));
    }

    @Test
    void anAdministratorsTokenReadsButNeverChangesAnAccount() {
        Caller root = a.authenticate(bearer(session("root", Role.ADMINISTRATOR)), Optional.empty());
        Access.Issued issued = a.issueOwnToken(root.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE), NEW_PASSWORD, "cli",
                OptionalInt.empty());
        Caller token = b.authenticate(bearer(issued.token()), Optional.empty());
        assertTrue(token.may(Action.USER_READ, PackageRef.ANY) && token.may(Action.TOKEN_READ, PackageRef.ANY)
                && token.may(Action.SELF_TOKENS, PackageRef.ANY));
        for (Action change : List.of(Action.USER_CREATE, Action.ROLES_CHANGE, Action.USER_BLOCK, Action.SESSION_END, Action.TOKEN_ISSUE,
                Action.TOKEN_REVOKE, Action.TOKEN_CEILING, Action.SELF_TOKEN_ISSUE, Action.SELF_PASSWORD, Action.SELF_LOGOUT)) {
            refused(403, "session-required", () -> token.require(change, PackageRef.ANY));
        }
    }

    @Test
    void aTokenExpiresAndANewOneTakesItsName() {
        technical("ci-deploy");
        Access.Issued old = issue("ci-deploy", "pipeline", 1);
        clockA.advance(Duration.ofDays(1).minusMillis(1));
        a.authenticate(bearer(old.token()), Optional.empty());
        refused(409, "token-name-taken", () -> issue("ci-deploy", "pipeline", 1));
        clockA.advance(Duration.ofMillis(1));
        refused(401, "token-expired", () -> a.authenticate(bearer(old.token()), Optional.empty()));

        Access.Issued next = issue("ci-deploy", "pipeline", 1);
        refused(401, "token-revoked", () -> a.authenticate(bearer(old.token()), Optional.empty()));
        assertEquals(old.id(), audit("token.issue").getLast().getJsonObject("detail").getString("replaced"));
        assertEquals(List.of(next.id()), held("ci-deploy"));
    }

    @Test
    void aUserHoldsAtMostTwentyLiveTokens() {
        technical("ci-deploy");
        issue("ci-deploy", "t-0", 1);
        List<Access.Issued> held = new ArrayList<>();
        for (int i = 1; i < 20; i++) held.add(issue("ci-deploy", "t-" + i, 90));
        refused(409, "tokens-full", () -> issue("ci-deploy", "t-20", 90));
        clockA.advance(Duration.ofDays(1));
        issue("ci-deploy", "t-20", 90);   // t-0 expired: it no longer counts
        refused(409, "tokens-full", () -> issue("ci-deploy", "t-21", 90));
        a.revokeToken(admin(Action.TOKEN_REVOKE), held.getFirst().id());
        issue("ci-deploy", "t-21", 90);
        assertEquals(20, held("ci-deploy").size());
    }

    /** Two nodes each issue a token of one account at once while it holds 19: one of the two is refused. */
    @Test
    void twoNodesIssuingTheTwentiethTokenAtOnceLeaveTwenty() throws Exception {
        Actor root = admin(Action.TOKEN_ISSUE);
        for (int round = 0; round < 5; round++) {
            String user = "ci-" + round;
            technical(user);
            for (int i = 0; i < 19; i++) issue(user, "t-" + i, 90);
            CountDownLatch start = new CountDownLatch(1);
            Thread first = Thread.ofVirtual().start(() -> attempt(start, () -> a.issueToken(root, user, "on-a", OptionalInt.empty())));
            Thread second = Thread.ofVirtual().start(() -> attempt(start, () -> b.issueToken(root, user, "on-b", OptionalInt.empty())));
            start.countDown();
            first.join();
            second.join();
            assertEquals(20, held(user).size(), "round " + round);
        }
    }

    @Test
    void aRevokedTokenIsRefusedOnEveryNode() {
        Caller anna = a.authenticate(bearer(session("anna")), Optional.empty());
        Actor self = anna.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE);
        Access.Issued laptop = a.issueOwnToken(self, NEW_PASSWORD, "laptop", OptionalInt.empty());
        Access.Issued phone = a.issueOwnToken(self, NEW_PASSWORD, "phone", OptionalInt.empty());
        b.authenticate(bearer(laptop.token()), Optional.empty());

        Actor own = b.authenticate(bearer(phone.token()), Optional.empty()).acting(Action.SELF_TOKENS, PackageRef.ANY, REMOTE);
        a.revokeOwnToken(own, laptop.id());
        refused(401, "token-revoked", () -> a.authenticate(bearer(laptop.token()), Optional.empty()));
        refused(401, "token-revoked", () -> b.authenticate(bearer(laptop.token()), Optional.empty()));
        b.revokeOwnToken(own, laptop.id());
        assertEquals(1, audit("token.revoke").size(), "a token revoked once is in the audit once");
        JsonObject row = audit("token.revoke").getFirst();
        assertEquals(List.of("anna", "user/anna", "token", phone.id()), List.of(row.getString("actor"), row.getString("subject"),
                row.getString("via"), row.getString("credential")));
        assertEquals(new JsonObject().put("token", laptop.id()).put("name", "laptop").put("remote", REMOTE), row.getJsonObject("detail"));

        Actor bob = b.authenticate(bearer(session("bob")), Optional.empty()).acting(Action.SELF_TOKENS, PackageRef.ANY, REMOTE);
        refused(404, "no-token", () -> b.revokeOwnToken(bob, phone.id()));
        refused(404, "no-token", () -> a.revokeOwnToken(own, "0123456789abcdef"));
        refused(404, "no-token", () -> a.revokeOwnToken(own, "not an id"));
        a.authenticate(bearer(phone.token()), Optional.empty());

        b.revokeToken(key(b).acting(Action.TOKEN_REVOKE, PackageRef.ANY, REMOTE), phone.id());
        refused(401, "token-revoked", () -> a.authenticate(bearer(phone.token()), Optional.empty()));
        assertEquals(Caller.EMERGENCY_KEY, audit("token.revoke").getLast().getString("actor"));
        refused(404, "no-token", () -> a.revokeToken(admin(Action.TOKEN_REVOKE), "0123456789abcdef"));
    }

    @Test
    void aBlockedUsersTokensAreRefusedWhileTheBlockLasts() {
        technical("ci-deploy");
        Access.Issued ci = issue("ci-deploy", "pipeline", 90);
        a.block(admin(Action.USER_BLOCK), "ci-deploy");
        refused(401, "blocked", () -> b.authenticate(bearer(ci.token()), Optional.empty()));
        a.unblock(admin(Action.USER_BLOCK), "ci-deploy");
        assertEquals("ci-deploy", b.authenticate(bearer(ci.token()), Optional.empty()).name());
    }

    @Test
    void aLoweredCeilingShortensLiveTokensAndARaisedOneLengthensNone() {
        technical("ci-deploy");
        assertEquals(new Access.Ceiling(365, 90), a.tokens(Optional.empty()).ceiling());
        Access.Issued year = issue("ci-deploy", "year", 365);
        clockA.advance(Duration.ofDays(10));
        Access.Issued hundred = issue("ci-deploy", "hundred", 100);
        issue("ci-deploy", "five", 5);
        clockA.advance(Duration.ofDays(10));
        clockB.advance(Duration.ofDays(20));
        Access.Issued week = issue("ci-deploy", "week", 7);
        Actor root = admin(Action.TOKEN_CEILING);

        assertEquals(new Access.Ceiling(15, 15), a.changeCeiling(root, 15));
        refused(401, "token-expired", () -> a.authenticate(bearer(year.token()), Optional.empty()));   // older than the ceiling
        refused(401, "token-expired", () -> b.authenticate(bearer(year.token()), Optional.empty()));
        Caller live = b.authenticate(bearer(hundred.token()), Optional.empty());
        assertEquals(Optional.of(T0.plus(Duration.ofDays(25))), b.expiresAt(live), "its issue plus the ceiling");
        Access.Tokens listed = b.tokens(Optional.empty());
        assertEquals(List.of(hundred.id(), week.id()), ids(listed));
        assertEquals(List.of(T0.plus(Duration.ofDays(25)), T0.plus(Duration.ofDays(27))),
                listed.tokens().stream().map(Access.Token::expiresAt).toList(), "a token shorter than the ceiling keeps its end");
        assertEquals(new Access.Ceiling(15, 15), listed.ceiling());
        refused(400, "token-life", () -> issue("ci-deploy", "sixteen", 16));
        assertEquals(clockA.instant().plus(Duration.ofDays(15)),
                a.issueToken(admin(Action.TOKEN_ISSUE), "ci-deploy", "default", OptionalInt.empty()).expiresAt(), "the default is the ceiling below 90");

        assertEquals(new Access.Ceiling(365, 90), a.changeCeiling(root, 365));
        refused(401, "token-expired", () -> a.authenticate(bearer(year.token()), Optional.empty()));   // a raised ceiling revives nothing
        assertEquals(Optional.of(T0.plus(Duration.ofDays(25))), a.expiresAt(a.authenticate(bearer(hundred.token()), Optional.empty())));
        int rows = audit().size();
        a.changeCeiling(root, 365);
        assertEquals(rows, audit().size(), "the same ceiling writes nothing");
        refused(400, "ceiling-range", () -> a.changeCeiling(root, 0));
        refused(400, "ceiling-range", () -> a.changeCeiling(root, 366));
        assertEquals(List.of(new JsonObject().put("from", 365).put("to", 15).put("shortened", 2).put("remote", REMOTE),
                new JsonObject().put("from", 15).put("to", 365).put("shortened", 0).put("remote", REMOTE)),
                audit("token.ceiling").stream().map(r -> r.getJsonObject("detail")).toList(), "a token that lives less than the ceiling keeps its end");
        assertEquals(List.of("keeper", "token-ceiling"), List.of(audit("token.ceiling").getFirst().getString("actor"),
                audit("token.ceiling").getFirst().getString("subject")));
    }

    /** Node b is 20 s behind node a: a token that a lowering ends is refused on b too, though b's clock still sees it live. */
    @Test
    void aLoweringEndsATokenOnANodeBehindToo() {
        technical("ci-deploy");
        Access.Issued month = issue("ci-deploy", "month", 30);
        clockA.advance(Duration.ofDays(30).plusSeconds(10));
        clockB.advance(Duration.ofDays(30).plusSeconds(10));
        refused(401, "token-expired", () -> a.authenticate(bearer(month.token()), Optional.empty()));
        assertEquals("ci-deploy", b.authenticate(bearer(month.token()), Optional.empty()).name(), "live by node b's clock");

        a.changeCeiling(admin(Action.TOKEN_CEILING), 10);
        refused(401, "token-expired", () -> b.authenticate(bearer(month.token()), Optional.empty()));
        assertEquals(1, audit("token.ceiling").getFirst().getJsonObject("detail").getInteger("shortened"), "counted, though expired on a");
    }

    @Test
    void theListsShowTheLiveTokensSoonestFirstWithTheCeiling() {
        Caller anna = a.authenticate(bearer(session("anna")), Optional.empty());
        Access.Issued laptop = a.issueOwnToken(anna.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, REMOTE), NEW_PASSWORD, "laptop",
                OptionalInt.of(30));
        technical("ci-deploy");
        Access.Issued pipeline = issue("ci-deploy", "pipeline", 10);
        Access.Issued nightly = issue("ci-deploy", "nightly", 60);
        a.revokeToken(admin(Action.TOKEN_REVOKE), issue("ci-deploy", "gone", 5).id());

        Access.Tokens all = b.tokens(Optional.empty());
        assertEquals(List.of(pipeline.id(), laptop.id(), nightly.id()), ids(all));
        assertEquals(new Access.Ceiling(365, 90), all.ceiling());
        assertEquals(List.of(pipeline.id(), laptop.id()), ids(a.tokens(Optional.of(Duration.ofDays(30)))),
                "within 30 days, its end included");
        assertEquals(List.of(new Access.Token(laptop.id(), "anna", "laptop", T0, "anna", T0.plus(Duration.ofDays(30)), Optional.empty())),
                b.ownTokens(anna).tokens());
        assertEquals(List.of(pipeline.id(), nightly.id()), a.user("ci-deploy").orElseThrow().tokens().stream().map(Access.Token::id).toList());
        refused(400, "bad-request", () -> a.tokens(Optional.of(Duration.ZERO)));
        refused(400, "bad-request", () -> a.tokens(Optional.of(Duration.ofDays(365).plusSeconds(1))));
        refused(400, "tokens-need-user", () -> a.ownTokens(key(a)));   // only a user holds tokens
    }

    @Test
    void aTokensLastUseIsWrittenAtMostOnceAnHour() {
        technical("ci-deploy");
        Access.Issued ci = issue("ci-deploy", "pipeline", 90);
        assertEquals(Optional.empty(), lastUsed(ci));
        a.authenticate(bearer(ci.token()), Optional.empty());
        assertEquals(Optional.of(T0), lastUsed(ci));
        clockA.advance(Duration.ofMinutes(59));
        clockB.advance(Duration.ofHours(1));   // 20 s short of the hour
        a.authenticate(bearer(ci.token()), Optional.empty());
        b.authenticate(bearer(ci.token()), Optional.empty());
        assertEquals(Optional.of(T0), lastUsed(ci), "within the hour, on either node");
        clockA.advance(Duration.ofMinutes(1));
        a.authenticate(bearer(ci.token()), Optional.empty());
        assertEquals(Optional.of(T0.plus(Duration.ofHours(1))), lastUsed(ci));
        Instant onB = clockB.instant();
        ((Accounts) b).query(tx -> { tx.used(ci.id(), onB, onB.minus(Duration.ofHours(1))); return null; });
        assertEquals(Optional.of(T0.plus(Duration.ofHours(1))), lastUsed(ci), "node b read the token before node a wrote its use");
    }

    private Optional<Instant> lastUsed(Access.Issued token) {
        return a.tokens(Optional.empty()).tokens().stream().filter(t -> t.id().equals(token.id())).findFirst()
                .orElseThrow().lastUsedAt();
    }

    // --- the audit before the act

    @Test
    void aUseOfTheKeyAndAViewOfABodyAreWrittenFirst() {
        a.keyUsed(key(a), "POST", "/admin/users", REMOTE, Optional.of(Action.USER_CREATE), true);
        a.keyUsed(key(a), "GET", "/admin/nowhere", REMOTE, Optional.empty(), false);
        List<JsonObject> uses = audit("emergency-key.use");
        assertEquals(List.of(Caller.EMERGENCY_KEY, Caller.EMERGENCY_KEY, "emergency-key"), List.of(uses.getFirst().getString("actor"),
                uses.getFirst().getString("subject"), uses.getFirst().getString("via")));
        assertEquals(new JsonObject().put("method", "POST").put("path", "/admin/users").put("remote", REMOTE).put("allowed", true)
                .put("action", "user.create"), uses.getFirst().getJsonObject("detail"));
        assertEquals(null, uses.getLast().getJsonObject("detail").getValue("action"));
        assertEquals(false, uses.getLast().getJsonObject("detail").getBoolean("allowed"));

        String session = session("anna", Role.MESSAGE_BODIES);
        Caller anna = b.authenticate(bearer(session), Optional.empty());
        b.bodyViewed(anna.acting(Action.MESSAGE_BODY, PackageRef.ANY, REMOTE), "m-1", 2);
        JsonObject view = audit("message.body.view").getFirst();
        assertEquals(List.of("anna", "message/m-1", "session", session.substring(7, 23)), List.of(view.getString("actor"),
                view.getString("subject"), view.getString("via"), view.getString("credential")));
        assertEquals(new JsonObject().put("step", 2).put("remote", REMOTE), view.getJsonObject("detail"));
        assertTrue(view.getValue("user") != null, "the session's user");
    }

    @Test
    void theAuditOfAMessageListsItsRowsNewestFirstAPageAtATime() {
        String session = session("anna", Role.MESSAGE_BODIES);
        Actor anna = b.authenticate(bearer(session), Optional.empty()).acting(Action.MESSAGE_BODY, PackageRef.ANY, REMOTE);
        a.bodyViewed(anna, "m-1", 1);
        clockA.advance(Duration.ofSeconds(1));
        a.bodyViewed(anna, "m-1", 2);
        a.bodyViewed(anna, "m-1", 3);   // the same instant as step 2's: the later row first
        a.bodyViewed(anna, "m-2", 1);
        b.bodyViewed(anna, "m-1", 9);   // node B's clock is behind: the oldest row

        Page<Access.Audited> all = a.messageAudit("m-1", Optional.empty(), Page.MAX_LIMIT);
        assertEquals(List.of(3, 2, 1, 9), all.items().stream().map(r -> r.detail().getInteger("step")).toList(), "by instant, then id, newest first");
        assertEquals(Optional.empty(), all.next());
        Access.Audited newest = all.items().getFirst();
        assertEquals(List.of("message.body.view", "anna", "session", Optional.of(session.substring(7, 23)), T0.plusSeconds(1)),
                List.of(newest.action(), newest.actor(), newest.via(), newest.credential(), newest.at()));
        assertEquals(new JsonObject().put("step", 3).put("remote", REMOTE), newest.detail());
        assertEquals(4, all.items().stream().map(Access.Audited::id).distinct().count(), "each row its own id");

        Page<Access.Audited> first = a.messageAudit("m-1", Optional.empty(), 3);
        assertEquals(List.of(3, 2, 1), first.items().stream().map(r -> r.detail().getInteger("step")).toList());
        Page<Access.Audited> second = b.messageAudit("m-1", first.next(), 3);
        assertEquals(List.of(9), second.items().stream().map(r -> r.detail().getInteger("step")).toList(), "the next page goes on from the last row");
        assertEquals(Optional.empty(), second.next());

        assertEquals(1, a.messageAudit("m-2", Optional.empty(), 10).items().size(), "only the message's own rows");
        assertEquals(List.of(), a.messageAudit("m-3", Optional.empty(), 10).items(), "a message with no rows: an empty page");
        for (int limit : List.of(0, Page.MAX_LIMIT + 1)) {
            assertThrows(IllegalArgumentException.class, () -> a.messageAudit("m-1", Optional.empty(), limit), "limit " + limit);
        }
        assertThrows(IllegalArgumentException.class, () -> a.messageAudit("m-1", Optional.of(Page.cursor("x")), 10), "a cursor not handed out");
        assertThrows(IllegalArgumentException.class, () -> a.messageAudit("m-1", Optional.of("!!!"), 10), "a cursor that is not base64url");
    }

    @Test
    void theWholeAuditIsNarrowedByUserActionAndTimeNewestFirstAPageAtATime() {
        Actor anna = a.authenticate(bearer(session("anna", Role.MESSAGE_BODIES)), Optional.empty()).acting(Action.MESSAGE_BODY, PackageRef.ANY, REMOTE);
        Actor dirk = a.authenticate(bearer(session("dirk", Role.MESSAGE_BODIES)), Optional.empty()).acting(Action.MESSAGE_BODY, PackageRef.ANY, REMOTE);
        Instant t1 = T0.plusSeconds(60);
        Instant t2 = T0.plusSeconds(120);
        Instant t3 = T0.plusSeconds(180);
        clockA.advance(Duration.ofSeconds(60));
        a.bodyViewed(anna, "m-1", 1);
        clockA.advance(Duration.ofSeconds(60));
        a.bodyViewed(dirk, "m-1", 2);
        clockA.advance(Duration.ofSeconds(60));
        a.bodyViewed(anna, "m-2", 3);
        Function<Access.AuditFilter, List<Integer>> views = filter -> a.audit(filter, Optional.empty(), Page.MAX_LIMIT).items().stream()
                .map(r -> r.detail().getInteger("step")).toList();
        Optional<String> viewed = Optional.of("message.body.view");

        assertEquals(List.of(3, 1), views.apply(new Access.AuditFilter(Optional.of("anna"), viewed, Optional.empty(), Optional.empty())),
                "anna's views, newest first");
        List<Access.Audited> annas = a.audit(new Access.AuditFilter(Optional.of("anna"), Optional.empty(), Optional.empty(), Optional.empty()),
                Optional.empty(), Page.MAX_LIMIT).items();
        assertTrue(annas.stream().allMatch(r -> r.actor().equals("anna")), "only what anna did: " + annas);
        assertTrue(annas.stream().anyMatch(r -> r.action().equals("login")), "her logins too: " + annas);
        Access.Audited newest = annas.getFirst();
        assertEquals(List.of("message.body.view", "message/m-2", "session", t3), List.of(newest.action(), newest.subject(), newest.via(), newest.at()));
        assertEquals(List.of(), views.apply(new Access.AuditFilter(Optional.of("nobody"), Optional.empty(), Optional.empty(), Optional.empty())),
                "a name no user has: no rows");

        assertEquals(List.of(3, 2), views.apply(new Access.AuditFilter(Optional.empty(), viewed, Optional.of(t2), Optional.empty())), "from included");
        assertEquals(List.of(1), views.apply(new Access.AuditFilter(Optional.empty(), viewed, Optional.empty(), Optional.of(t2))), "to left out");
        assertEquals(List.of(2, 1), views.apply(new Access.AuditFilter(Optional.empty(), viewed, Optional.of(t1), Optional.of(t3))));
        List<Access.Audited> before = a.audit(new Access.AuditFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(t1)),
                Optional.empty(), Page.MAX_LIMIT).items();
        assertTrue(before.stream().map(Access.Audited::action).toList().containsAll(List.of("user.create", "login", "password.change")),
                "every action: " + before);

        Access.AuditFilter all = new Access.AuditFilter(Optional.empty(), viewed, Optional.empty(), Optional.empty());
        Page<Access.Audited> first = a.audit(all, Optional.empty(), 2);
        assertEquals(List.of(3, 2), first.items().stream().map(r -> r.detail().getInteger("step")).toList());
        Page<Access.Audited> second = b.audit(all, first.next(), 2);
        assertEquals(List.of(1), second.items().stream().map(r -> r.detail().getInteger("step")).toList(), "the next page goes on from the last row");
        assertEquals(Optional.empty(), second.next());

        assertThrows(IllegalArgumentException.class, () -> new Access.AuditFilter(Optional.empty(), Optional.empty(), Optional.of(t2), Optional.of(t2)),
                "from not before to");
        for (int limit : List.of(0, Page.MAX_LIMIT + 1)) assertThrows(IllegalArgumentException.class, () -> a.audit(all, Optional.empty(), limit), "limit " + limit);
        assertThrows(IllegalArgumentException.class, () -> a.audit(all, Optional.of(Page.cursor("x")), 10), "a cursor not handed out");
    }

    @Test
    void aCredentialPastedIntoAPathIsAuditedWithoutItsSecret() {
        String secret = "s3cr-et_" + "x".repeat(35);
        a.keyUsed(key(a), "POST", "/admin/tokens/rail8t_0123456789abcdef_" + secret + "/revoke", REMOTE, Optional.of(Action.TOKEN_REVOKE), true);
        a.keyUsed(key(a), "GET", "/admin/rail8s_00112233445566ff_" + secret + "/rail8k_" + secret, REMOTE, Optional.empty(), false);
        a.keyUsed(key(a), "GET", "/admin/rail8d_00112233445566aa_" + secret, REMOTE, Optional.empty(), false);
        assertEquals(List.of("/admin/tokens/rail8t_0123456789abcdef_***/revoke", "/admin/rail8s_00112233445566ff_***/rail8k_***",
                        "/admin/rail8d_00112233445566aa_***"),
                audit("emergency-key.use").stream().map(r -> r.getJsonObject("detail").getString("path")).toList());
    }
}
