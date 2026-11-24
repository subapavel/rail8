package dev.rail8.cli;

import io.vertx.core.json.JsonObject;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code rail8 deploy <repository> <commit|ref> --expect <commit|none|any> [--admin URL]} and
 * {@code rail8 deploy <repository> <commit|ref> --check [--expect <commit|none|any>] [--admin URL]}: {@code POST <admin>/admin/deploy},
 * with {@code ?check=true} for {@code --check}, the body {@code {"repository": "<repository>", "commit": "<commit|ref>",
 * "expect": "<state>"}}, {@code expect} left out of a check that does not give it, through {@link AdminApi}: the admin URL is
 * {@code --admin}, else RAIL8_ADMIN, else {@code http://localhost:8081}; plain HTTP only to loopback unless RAIL8_ADMIN_TLS=off;
 * the token is RAIL8_ADMIN_TOKEN, taken from the environment only. The expected state is what the caller wrote: the command
 * fills in no default and never reads the state itself. The node judges the repository, the commit and the expected state: a
 * ref ({@code HEAD}, {@code main}) only in dev and ci. Prints the node's answer body; exit 0 on a 2xx, 1 on any other status (a
 * redirect is not followed, a 412 for another state is one) or a node that cannot be reached, 2 on a usage error (nothing
 * sent).
 */
final class Deploy {

    /** A deploy answers once its flows started or it was refused; the node bounds that by its stop deadline, this bounds the wait. */
    private static final Duration ANSWER = Duration.ofMinutes(5);

    private Deploy() {}

    /**
     * The arguments of {@code rail8 deploy} after its name ({@code args[0]}).
     *
     * @param repository the package repository's name, as the node reads it under its base
     * @param commit a commit id, or a ref the node resolves in dev and ci
     * @param check {@code --check}: the node answers what the deploy would do and changes nothing
     * @param expect {@code --expect}: the state the deploy expects to replace, as the caller wrote it; always given unless
     *               {@code check}
     * @param admin {@code --admin}, when given
     */
    record Arguments(String repository, String commit, boolean check, Optional<String> expect, Optional<String> admin) {}

    /**
     * The arguments {@code args} give; a {@link Cli.Usage} unless they are a repository, a commit, and the options at most once
     * each, {@code --expect} among them unless {@code --check} is.
     */
    static Arguments arguments(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean check = false;
        String expect = null;
        String admin = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--check")) {
                if (check) throw new Cli.Usage("deploy: --check is given twice");
                check = true;
            } else if (arg.equals("--expect")) {
                if (i + 1 == args.length || args[i + 1].isBlank()) throw new Cli.Usage("deploy: --expect needs a value: the commit the pool records, none or any");
                if (expect != null) throw new Cli.Usage("deploy: --expect is given twice");
                expect = args[++i];
            } else if (arg.equals("--admin")) {
                if (i + 1 == args.length || args[i + 1].isBlank()) throw new Cli.Usage("deploy: --admin needs a value");
                if (admin != null) throw new Cli.Usage("deploy: --admin is given twice");
                admin = args[++i];
            } else if (arg.startsWith("--")) {
                throw new Cli.Usage("deploy: unknown option " + arg);
            } else if (arg.isBlank()) {
                throw new Cli.Usage("deploy: a blank argument");
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 2) throw new Cli.Usage("deploy: the repository and the commit are both needed");
        if (positional.size() > 2) throw new Cli.Usage("deploy: a repository and a commit, not also '" + positional.get(2) + "'");
        if (expect == null && !check) {
            throw new Cli.Usage("deploy: --expect names the state the deploy replaces: the commit the pool records (--check answers it as replaces), "
                    + "none for a package the pool does not run, or any to replace whatever it runs");
        }
        return new Arguments(positional.get(0), positional.get(1), check, Optional.ofNullable(expect), Optional.ofNullable(admin));
    }

    static int run(Arguments arguments, Map<String, String> env, PrintStream out, PrintStream err) {
        AdminApi admin = AdminApi.of("deploy", arguments.admin(), env);
        JsonObject request = new JsonObject().put("repository", arguments.repository()).put("commit", arguments.commit());
        arguments.expect().ifPresent(expect -> request.put("expect", expect));
        byte[] body = request.encode().getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> answer;
        try {
            answer = admin.post("/admin/deploy" + (arguments.check() ? "?check=true" : ""), body, ANSWER);
        } catch (AdminApi.NoAnswer unreachable) {
            err.println("rail8 deploy: " + unreachable.getMessage());
            return 1;
        }
        out.println(answer.body());
        return answer.statusCode() / 100 == 2 ? 0 : 1;
    }
}
