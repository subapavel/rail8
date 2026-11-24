package dev.rail8.runtime.admin;

import dev.rail8.runtime.access.Role;
import dev.rail8.runtime.access.Who;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The table of actions of {@code docs/USERS-AND-ROLES.md}, read from the document as it is: each action's id, its routes as
 * {@code <METHOD> <path>} with each parameter as {@code {name}}, and what grants it; and the actions that existed before users.
 *
 * @param rows the rows, in the table's order, by id
 * @param beforeUsers the ids of the actions that existed before users did
 */
record ActionsTable(Map<String, Row> rows, Set<String> beforeUsers) {

    static final Path DOC = Path.of("../../docs/USERS-AND-ROLES.md");

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern ROUTE = Pattern.compile("(GET|POST|DELETE) (/\\S+)");
    private static final Pattern METHOD = Pattern.compile("GET|POST|DELETE");

    /**
     * One row.
     *
     * @param id the action's id
     * @param routes its routes; none for an action of a store without routes yet
     * @param grantedBy the text of the column "Granted by"
     */
    record Row(String id, Set<String> routes, String grantedBy) {

        /** The roles the column names. */
        Set<Role> roles() {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (String part : grantedBy.split(",")) {
                Role.byId(part.strip().replace(" logged in", "")).ifPresent(roles::add);
            }
            return roles;
        }

        /** Whether it needs a person logged in: "logged in", or "a session". */
        boolean needsSession() {
            return grantedBy.contains("logged in") || grantedBy.equals("a session");
        }

        /** Whether the emergency key may do it: "the key". */
        boolean emergencyKey() {
            return grantedBy.contains("the key");
        }

        /** Who grants it: every caller, a person, a session, or the roles. */
        Who who() {
            if (grantedBy.startsWith("every caller")) return Who.EVERY;
            if (grantedBy.startsWith("a person")) return Who.PERSON;
            if (grantedBy.equals("a session")) return Who.SESSION;
            return Who.ROLES;
        }
    }

    /** The table as the document has it now. */
    static ActionsTable read() {
        List<String> lines;
        try {
            lines = Files.readAllLines(DOC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = lines.indexOf("## Actions");
        if (start < 0) throw new IllegalStateException(DOC + " has no section ## Actions");
        Map<String, Row> rows = new LinkedHashMap<>();
        Set<String> beforeUsers = new LinkedHashSet<>();
        Set<String> previous = Set.of();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
            String line = lines.get(i);
            if (line.startsWith("| `")) {
                String[] cells = line.split("\\|");
                String id = cells[1].strip().replace("`", "");
                String routesCell = cells[2].strip();
                Set<String> routes = routesCell.startsWith("the same route") ? previous : routes(routesCell);
                rows.put(id, new Row(id, routes, cells[3].strip()));
                previous = routes;
            } else if (line.startsWith("The actions that exist before step 1 are")) {
                StringBuilder paragraph = new StringBuilder(line);
                for (int j = i + 1; j < lines.size() && !lines.get(j).isBlank(); j++) paragraph.append(' ').append(lines.get(j));
                Matcher m = CODE.matcher(paragraph);
                while (m.find()) beforeUsers.add(m.group(1));
            }
        }
        if (rows.isEmpty() || beforeUsers.isEmpty()) throw new IllegalStateException(DOC + ": no table of actions, or no actions before users");
        return new ActionsTable(rows, beforeUsers);
    }

    /**
     * The routes a cell names: {@code GET /a} a route; a bare {@code /b} the same method as the route before; {@code /c} after a
     * route {@code /a/x} its sibling {@code /a/c}; a bare method one more method of the next route. Anything else is text.
     */
    private static Set<String> routes(String cell) {
        Set<String> routes = new LinkedHashSet<>();
        List<String> methods = new ArrayList<>();
        String method = null;
        String path = null;
        Matcher m = CODE.matcher(cell);
        while (m.find()) {
            String code = m.group(1);
            Matcher route = ROUTE.matcher(code);
            if (route.matches()) {
                method = route.group(1);
                path = route.group(2);
                methods.add(method);
                for (String each : methods) routes.add(each + " " + path);
                methods.clear();
            } else if (METHOD.matcher(code).matches()) {
                methods.add(code);
            } else if (code.startsWith("/admin/")) {
                path = code;
                routes.add(method + " " + path);
            } else if (code.startsWith("/") && path != null) {
                path = path.substring(0, path.lastIndexOf('/')) + code;
                routes.add(method + " " + path);
            }
        }
        return routes;
    }

    /** Every route of every row. */
    Set<String> routes() {
        Set<String> all = new LinkedHashSet<>();
        rows.values().forEach(r -> all.addAll(r.routes()));
        return all;
    }

    /** The rows that name {@code route}, in the table's order. */
    List<Row> of(String route) {
        return rows.values().stream().filter(r -> r.routes().contains(route)).toList();
    }

    /** The ids in the table's order. */
    List<String> ids() {
        return new ArrayList<>(rows.keySet());
    }

    @Override
    public String toString() {
        return Arrays.toString(rows.values().toArray());
    }
}
