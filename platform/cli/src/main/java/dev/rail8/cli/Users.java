package dev.rail8.cli;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The commands that make the first users of the admin API:
 * <ul>
 *   <li>{@code rail8 user add <name> [--role <role>] [--admin <url>]}: {@code POST <admin>/admin/users} with the body
 *       {@code {"name": "<name>", "kind": "person", "roles": ["<role>"]}} (no role without {@code --role}),
 *       {@code Content-Type: application/json} and {@code Authorization: Bearer $RAIL8_ADMIN_TOKEN}: the emergency key, or during
 *       the bridge the shared token. The admin URL is {@code --admin}, else RAIL8_ADMIN, else {@code http://localhost:8081}, reached
 *       through {@link AdminApi}: plain HTTP only to loopback unless RAIL8_ADMIN_TLS=off.
 *       Prints the first password the node answers, alone on its line; exit 0 on a 2xx, 1 on any other status (the node's
 *       answer printed) or a node that cannot be reached, 2 on a usage error or without RAIL8_ADMIN_TOKEN (nothing sent).</li>
 *   <li>{@code rail8 key new}: prints a new emergency key, {@code rail8k_} and 43 base64url characters of 256 random bits, to
 *       set as the node's RAIL8_ADMIN_TOKEN; sends nothing.</li>
 * </ul>
 */
final class Users {

    private static final Duration ANSWER = Duration.ofSeconds(30);
    private static final int KEY_BYTES = 32;

    private Users() {}

    /**
     * The arguments of {@code rail8 user add} after {@code user add}.
     *
     * @param name the user's name, as the node judges it
     * @param role {@code --role}, when given
     * @param admin {@code --admin}, when given
     */
    record Add(String name, Optional<String> role, Optional<String> admin) {}

    /** {@code rail8 user <args>}: {@code add} is the one subcommand. */
    static int user(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length < 2 || !args[1].equals("add")) throw new Cli.Usage("user: the subcommand is add");
        return add(add(args), env, out, err);
    }

    /**
     * {@code rail8 key <args>}: {@code new}, the one subcommand, takes no argument and prints {@code rail8k_} and the base64url,
     * without padding, of 32 bytes of the system's strong random source.
     */
    static int key(String[] args, PrintStream out) {
        if (args.length != 2 || !args[1].equals("new")) throw new Cli.Usage("key: the subcommand is new, without arguments");
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        out.println("rail8k_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        return 0;
    }

    /** The arguments {@code args} give after {@code user add}; a {@link Cli.Usage} unless they are a name and each option at most once. */
    static Add add(String[] args) {
        List<String> positional = new ArrayList<>();
        String role = null;
        String admin = null;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--role") || arg.equals("--admin")) {
                if (i + 1 == args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) throw new Cli.Usage("user add: " + arg + " needs a value");
                if ((arg.equals("--role") ? role : admin) != null) throw new Cli.Usage("user add: " + arg + " is given twice");
                if (arg.equals("--role")) role = args[++i];
                else admin = args[++i];
            } else if (arg.startsWith("--")) {
                throw new Cli.Usage("user add: unknown option " + arg);
            } else if (arg.isBlank()) {
                throw new Cli.Usage("user add: a blank argument");
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) throw new Cli.Usage("user add: the user's name is needed");
        if (positional.size() > 1) throw new Cli.Usage("user add: one name, not also '" + positional.get(1) + "'");
        return new Add(positional.getFirst(), Optional.ofNullable(role), Optional.ofNullable(admin));
    }

    static int add(Add add, Map<String, String> env, PrintStream out, PrintStream err) {
        String key = AdminApi.key("user add", env);
        AdminApi admin = AdminApi.of("user add", add.admin(), env);
        JsonArray roles = new JsonArray();
        add.role().ifPresent(roles::add);
        byte[] body = new JsonObject().put("name", add.name()).put("kind", "person").put("roles", roles).encode().getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> answer;
        try {
            answer = admin.post("/admin/users", body, Optional.of(key), ANSWER);
        } catch (AdminApi.NoAnswer unreachable) {
            err.println("rail8 user add: " + unreachable.getMessage());
            return 1;
        }
        if (answer.statusCode() / 100 != 2) {
            out.println(answer.body());
            return 1;
        }
        String firstPassword;
        try {
            firstPassword = new JsonObject(answer.body()).getString("firstPassword");
        } catch (DecodeException | ClassCastException notAnObject) {
            firstPassword = null;
        }
        if (firstPassword == null) {
            err.println("rail8 user add: the node answered " + answer.statusCode() + " without a first password");
            return 1;
        }
        out.println(firstPassword);
        return 0;
    }
}
