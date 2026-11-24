package dev.rail8.runtime.admin;

import dev.rail8.runtime.access.Action;
import dev.rail8.runtime.access.Role;
import dev.rail8.runtime.access.Who;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code /admin} route is mounted with its actions, the {@link Action} enum is the table of actions of
 * {@code docs/USERS-AND-ROLES.md}, and every route lets each kind of caller through or refuses it as that table says: a session
 * of each role, a session of a first password, a token of each role and of a technical account, the emergency key, and with the
 * bridge's marker open and closed the shared token and the open loopback API. A refusal is 403 with the code the decision point names and the roles that grant the action.
 */
class AdminServerAccessTest extends AdminAccessFixture {

    /** The routes of the table that a later step mounts: the Audit screen's. */
    static final Set<String> LATER = Set.of("GET /admin/audit");

    /** What a first password may do, by the decision point's rule: read itself, change the password and log out. */
    static final Set<String> FIRST_PASSWORD = Set.of("self.read", "self.password", "self.logout");

    /** A value for each parameter of a path: nothing it names exists, so an act let through finds nothing to act on. */
    static final Map<String, String> VALUES = Map.of("name", "nobody", "id", "m-404", "n", "1", "seq", "999999", "flow", "shop",
            "plugin", "demo", "path", "ping");

    static final Pattern PARAM = Pattern.compile(":(\\w+)");

    final ActionsTable table = ActionsTable.read();

    /** The route of a mounted path as the table writes it: {@code :name} as {@code {name}}, a module's service as its pattern. */
    static String asInTable(HttpMethod method, String path) {
        String written = path.startsWith("/admin/modules/") ? "/admin/modules/{plugin}/{path}" : PARAM.matcher(path).replaceAll("{$1}");
        return method.name() + " " + written;
    }

    /** The routes of {@code /admin} the server mounted with actions, each with its actions, and the names of those without. */
    record Walked(Map<String, List<Action>> routes, List<String> unchecked) {}

    Walked walk() {
        Map<String, List<Action>> routes = new TreeMap<>();
        List<String> unchecked = new ArrayList<>();
        for (Route route : keyServer.router().getRoutes()) {
            String path = route.getPath();
            List<Action> actions = route.getMetadata(AdminServer.ACTIONS);
            String name = route.getMetadata(AdminServer.UNCHECKED);
            if (path == null || !path.startsWith("/admin")) {
                assertTrue(actions == null && name == null, "a route outside /admin carries no action: " + path);
                continue;
            }
            assertTrue(actions == null ^ name == null, "a route of /admin carries its actions or is one of the three: " + route.methods() + " " + path);
            if (name != null) {
                unchecked.add(name);
                continue;
            }
            assertFalse(route.methods().isEmpty(), "a route with actions names its method: " + path);
            for (HttpMethod method : route.methods()) {
                List<Action> before = routes.putIfAbsent(asInTable(method, path), actions);
                if (before != null) assertEquals(before, actions, "the routes of one path carry the same actions: " + method + " " + path);
            }
        }
        return new Walked(routes, unchecked);
    }

    @Test
    void everyRouteOfAdminIsMountedWithTheActionsTheTableGivesIt() {
        Walked walked = walk();

        assertEquals(List.of("browser guard", "login", "authentication"), walked.unchecked(), "the three routes without an action, in this order");
        walked.routes().forEach((route, actions) -> assertEquals(table.of(route).stream().map(ActionsTable.Row::id).toList(),
                actions.stream().map(Action::id).toList(), "the actions of " + route + " are the table's"));
        Set<String> unmounted = new TreeSet<>(table.routes());
        unmounted.removeAll(walked.routes().keySet());
        assertEquals(new TreeSet<>(LATER), unmounted, "every route of the table is mounted but those of later steps");
    }

    @Test
    void theActionEnumIsTheTable() {
        assertEquals(table.ids(), Arrays.stream(Action.values()).map(Action::id).toList(), "the same actions, in the same order");
        for (Action action : Action.values()) {
            ActionsTable.Row row = table.rows().get(action.id());
            assertEquals(row.roles(), action.roles(), action.id() + ": the roles");
            assertEquals(row.needsSession(), action.needsSession(), action.id() + ": a person logged in");
            assertEquals(row.emergencyKey(), action.emergencyKey(), action.id() + ": the key");
            assertEquals(row.who(), action.who(), action.id() + ": who grants it");
            assertEquals(table.beforeUsers().contains(action.id()), action.beforeStepOne(), action.id() + ": before users");
        }
    }

    /** A kind of caller: its credential and the port it calls, which rows of the table let it through, and the code of a refusal. */
    record Kind(String name, int port, Optional<String> bearer, Predicate<ActionsTable.Row> allowed, Function<ActionsTable.Row, String> refusal) {
        Kind(String name, int port, Optional<String> bearer, Predicate<ActionsTable.Row> allowed, String refusal) {
            this(name, port, bearer, allowed, r -> refusal);
        }
    }

    /** A token's refusal: {@code session-required} for an action that needs a person logged in, {@code not-allowed} otherwise. */
    static final Function<ActionsTable.Row, String> TOKEN_REFUSAL = r -> r.needsSession() ? "session-required" : "not-allowed";

    @Test
    void everyCallerIsLetThroughOrRefusedAsTheTableSays() {
        String root = root();
        Map<Role, String> sessions = new LinkedHashMap<>();
        for (Role role : EnumSet.complementOf(EnumSet.of(Role.ADMINISTRATOR))) {
            sessions.put(role, changed(role.id(), person(root, role.id(), role.id())));
        }
        String plain = changed("plain", person(root, "plain"));
        String first = login("fresh", person(root, "fresh", Arrays.stream(Role.values()).map(Role::id).toArray(String[]::new)));
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(Role.ADMINISTRATOR.id(), token(root, "cli"));
        sessions.forEach((role, session) -> tokens.put(role.id(), token(session, "cli")));
        String plainToken = token(plain, "cli");
        Set<Role> technicalRoles = EnumSet.complementOf(EnumSet.of(Role.ADMINISTRATOR));
        String ci = technical(root, "ci-deploy", technicalRoles.stream().map(Role::id).toArray(String[]::new));
        Set<String> before = table.beforeUsers();

        List<Kind> kinds = new ArrayList<>();
        kinds.add(new Kind("administrator", key, Optional.of(root), sessionOf(Set.of(Role.ADMINISTRATOR)), "not-allowed"));
        sessions.forEach((role, session) -> kinds.add(new Kind(role.id(), key, Optional.of(session), sessionOf(Set.of(role)), "not-allowed")));
        kinds.add(new Kind("a person without a role", key, Optional.of(plain), sessionOf(Set.of()), "not-allowed"));
        kinds.add(new Kind("a first password of every role", key, Optional.of(first), r -> FIRST_PASSWORD.contains(r.id()), "password-change-required"));
        tokens.forEach((role, token) -> kinds.add(new Kind("a token of the role " + role, key, Optional.of(token),
                tokenOf(Set.of(Role.byId(role).orElseThrow()), true), TOKEN_REFUSAL)));
        kinds.add(new Kind("a token of a person without a role", key, Optional.of(plainToken), tokenOf(Set.of(), true), TOKEN_REFUSAL));
        kinds.add(new Kind("a token of a technical account of every role it may hold", key, Optional.of(ci), tokenOf(technicalRoles, false),
                TOKEN_REFUSAL));
        kinds.add(new Kind("the emergency key", key, Optional.of(KEY), ActionsTable.Row::emergencyKey, "not-allowed"));
        kinds.add(new Kind("the shared token", shared, Optional.of(SHARED), r -> before.contains(r.id()) || r.emergencyKey(), "not-allowed"));
        kinds.add(new Kind("the open loopback API", open, Optional.empty(), r -> before.contains(r.id()) || r.who() == Who.EVERY, "not-allowed"));

        // the logout last: it ends the session it is called with
        List<String> routes = walk().routes().keySet().stream().sorted(Comparator.comparing((String r) -> r.equals("POST /admin/me/logout"))).toList();
        for (Kind kind : kinds) {
            for (String route : routes) {
                List<ActionsTable.Row> rows = table.of(route);
                boolean allowed = rows.stream().anyMatch(kind.allowed());
                Answer answer = call(kind, route);
                String said = kind.name() + ", " + route + ": " + answer.status() + " " + answer.body();
                assertFalse(answer.status() == 401, said);
                if (allowed) {
                    assertFalse(refused(answer), "let through by the table: " + said);
                } else {
                    assertTrue(refused(answer), "refused by the table: " + said);
                    assertEquals(kind.refusal().apply(rows.getFirst()), answer.code(), said);
                    assertEquals(ids(rows.getFirst().roles()), answer.json().getJsonArray("needs"), "the roles that grant it: " + said);
                }
            }
        }

        store.closeBridge();
        for (Kind bridge : List.of(new Kind("the shared token", shared, Optional.of(SHARED), r -> false, "unknown-credential"),
                new Kind("the open loopback API", open, Optional.empty(), r -> false, "no-credential"))) {
            for (String route : routes) {
                Answer answer = call(bridge, route);
                String said = bridge.name() + " with the marker closed, " + route + ": " + answer.status() + " " + answer.body();
                assertEquals(401, answer.status(), said);
                assertEquals(bridge.refusal().apply(table.of(route).getFirst()), answer.code(), said);
            }
        }
        Answer key = call(new Kind("the emergency key", this.key, Optional.of(KEY), r -> true, ""), "GET /admin/users");
        assertEquals(200, key.status(), "the key stays once the marker is closed: " + key.body());
    }

    /** A session of a person who holds {@code roles}, past the first password: by the table, the roles and every self action. */
    static Predicate<ActionsTable.Row> sessionOf(Set<Role> roles) {
        return r -> r.who() != Who.ROLES || r.roles().stream().anyMatch(roles::contains);
    }

    /** A token of a user who holds {@code roles}, a person's when {@code person}: by the table, nothing that needs a session. */
    static Predicate<ActionsTable.Row> tokenOf(Set<Role> roles, boolean person) {
        return r -> !r.needsSession() && switch (r.who()) {
            case EVERY -> true;
            case PERSON -> person;
            case SESSION -> false;
            case ROLES -> r.roles().stream().anyMatch(roles::contains);
        };
    }

    /** A refusal at the door: 403 with one of the decision point's codes. */
    static boolean refused(Answer answer) {
        return answer.status() == 403 && Set.of("not-allowed", "session-required", "password-change-required").contains(answer.code());
    }

    /** {@code route} with each parameter filled from {@link #VALUES}, a POST with the body {@code {}}. */
    static Answer call(Kind kind, String route) {
        String[] parts = route.split(" ", 2);
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(parts[1]);
        String path = m.replaceAll(r -> VALUES.get(r.group(1)));
        return send(kind.port(), parts[0], path, kind.bearer(), parts[0].equals("POST") ? "{}" : null);
    }

    static JsonArray ids(Set<Role> roles) {
        return new JsonArray(roles.stream().map(Role::id).sorted().collect(Collectors.toList()));
    }
}
