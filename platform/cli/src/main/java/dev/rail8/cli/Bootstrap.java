package dev.rail8.cli;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * {@code rail8 bootstrap --admin <name> --deploy-account <name> --token-file <path>}: makes the first users of a stack that starts
 * itself, through the admin API alone, at RAIL8_ADMIN, else {@code http://localhost:8081}, reached through {@link AdminApi}:
 * <ol>
 *   <li>{@code POST /admin/users} of the person {@code --admin} with the role {@code administrator}, with
 *       {@code Authorization: Bearer $RAIL8_ADMIN_TOKEN}: the emergency key, or during the bridge the shared token;</li>
 *   <li>{@code POST /admin/sessions} with the first password the node answered, without a bearer;</li>
 *   <li>{@code POST /admin/me/password} in that session, to a password generated here (32 characters of lowercase base32,
 *       160 random bits, never containing the name), which is then printed, alone on its line, once;</li>
 *   <li>in that session, {@code POST /admin/users} of the technical account {@code --deploy-account} with the role
 *       {@code developer}, and {@code POST /admin/users/<account>/tokens} of its token {@value #TOKEN_NAME}, for the node's
 *       default days;</li>
 *   <li>the token written alone, without a line break, to {@code --token-file}: through a file of its own in the same folder,
 *       readable and writable by its owner only where the file system has POSIX permissions, moved over the path;</li>
 *   <li>{@code POST /admin/me/logout}, which ends the session.</li>
 * </ol>
 * Exit 0 once all of it is done; 1 when the node refuses a step (the step, the status and the node's answer on the error stream)
 * or cannot be reached, or when the file cannot be written, and nothing after that step is done; 2 on a usage error, without
 * RAIL8_ADMIN_TOKEN, or when the token file's folder does not exist, with nothing sent. No password or key is a fixed value.
 */
final class Bootstrap {

    /** The name of the deploy account's token. */
    static final String TOKEN_NAME = "bootstrap";
    private static final Duration ANSWER = Duration.ofSeconds(30);
    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int PASSWORD_LENGTH = 32;
    /** The most passwords drawn before one is found that does not contain the name; a name has at least two characters. */
    private static final int DRAWS_MAX = 100;

    private Bootstrap() {}

    /**
     * The arguments of {@code rail8 bootstrap}.
     *
     * @param admin         the administrator's name, as the node judges it
     * @param deployAccount the technical account's name, as the node judges it
     * @param tokenFile     where the technical account's token is written
     */
    record Arguments(String admin, String deployAccount, Path tokenFile) {}

    /** The arguments {@code args} give after {@code bootstrap}; a {@link Cli.Usage} unless each of the three options is given once. */
    static Arguments arguments(String[] args) {
        String admin = null;
        String account = null;
        String file = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.equals("--admin") && !arg.equals("--deploy-account") && !arg.equals("--token-file")) {
                throw new Cli.Usage("bootstrap: unknown argument " + arg + "; it takes --admin, --deploy-account and --token-file");
            }
            if (i + 1 == args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) throw new Cli.Usage("bootstrap: " + arg + " needs a value");
            String value = args[++i];
            String before = switch (arg) {
                case "--admin" -> admin;
                case "--deploy-account" -> account;
                default -> file;
            };
            if (before != null) throw new Cli.Usage("bootstrap: " + arg + " is given twice");
            switch (arg) {
                case "--admin" -> admin = value;
                case "--deploy-account" -> account = value;
                default -> file = value;
            }
        }
        if (admin == null || account == null || file == null) throw new Cli.Usage("bootstrap: --admin, --deploy-account and --token-file are all needed");
        if (admin.equals(account)) throw new Cli.Usage("bootstrap: the administrator and the deploy account need two names");
        Path path;
        try {
            path = Path.of(file).toAbsolutePath();
        } catch (InvalidPathException notAPath) {
            throw new Cli.Usage("bootstrap: --token-file is not a path: " + file);
        }
        return new Arguments(admin, account, path);
    }

    static int run(Arguments arguments, Map<String, String> env, PrintStream out, PrintStream err) {
        String key = AdminApi.key("bootstrap", env);
        AdminApi admin = AdminApi.of("bootstrap", Optional.empty(), env);
        Path folder = arguments.tokenFile().getParent();
        if (folder == null || !Files.isDirectory(folder)) throw new Cli.Usage("bootstrap: the folder of --token-file does not exist: " + folder);
        if (Files.isDirectory(arguments.tokenFile())) throw new Cli.Usage("bootstrap: --token-file is a folder: " + arguments.tokenFile());
        try {
            JsonObject created = call(admin, "the administrator's creation", "/admin/users", 201, Optional.of(key), new JsonObject()
                    .put("name", arguments.admin()).put("kind", "person").put("roles", new JsonArray().add("administrator")));
            String first = member(created, "firstPassword", "the administrator's creation");
            JsonObject login = call(admin, "the login", "/admin/sessions", 201, Optional.empty(),
                    new JsonObject().put("name", arguments.admin()).put("password", first));
            Optional<String> session = Optional.of(member(login, "session", "the login"));
            String password = password(arguments.admin(), new SecureRandom());
            call(admin, "the password change", "/admin/me/password", 204, session, new JsonObject().put("current", first).put("next", password));
            out.println(password);
            call(admin, "the deploy account's creation", "/admin/users", 201, session, new JsonObject()
                    .put("name", arguments.deployAccount()).put("kind", "technical").put("roles", new JsonArray().add("developer")));
            JsonObject issued = call(admin, "the deploy account's token", "/admin/users/" + arguments.deployAccount() + "/tokens", 201, session,
                    new JsonObject().put("name", TOKEN_NAME));
            write(arguments.tokenFile(), member(issued, "token", "the deploy account's token"));
            call(admin, "the logout", "/admin/me/logout", 204, session, null);
            return 0;
        } catch (Failed failed) {
            err.println("rail8 bootstrap: " + failed.getMessage());
            return 1;
        }
    }

    /**
     * A password for {@code name}: {@value #PASSWORD_LENGTH} characters of lowercase base32 from {@code random}, drawn again while
     * it contains the name in any letter case, so the node's rules take it.
     */
    static String password(String name, RandomGenerator random) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (int draw = 0; draw < DRAWS_MAX; draw++) {
            StringBuilder out = new StringBuilder(PASSWORD_LENGTH);
            for (int i = 0; i < PASSWORD_LENGTH; i++) out.append(BASE32.charAt(random.nextInt(BASE32.length())));
            if (!out.toString().contains(lower)) return out.toString();
        }
        throw new IllegalStateException("no password without the name '" + name + "' in " + DRAWS_MAX + " draws");
    }

    /**
     * {@code POST path} with {@code body} ({@code null} for none) and {@code bearer}; its answer as an object, empty for a 204.
     *
     * @throws Failed the node answered another status than {@code expected}, or was not reached
     */
    private static JsonObject call(AdminApi admin, String step, String path, int expected, Optional<String> bearer, JsonObject body) throws Failed {
        byte[] bytes = body == null ? new byte[0] : body.encode().getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> answer;
        try {
            answer = admin.post(path, bytes, bearer, ANSWER);
        } catch (AdminApi.NoAnswer unreachable) {
            throw new Failed(step + ": " + unreachable.getMessage());
        }
        if (answer.statusCode() != expected) throw new Failed(step + ": the node answered " + answer.statusCode() + ": " + Cli.oneLine(answer.body()));
        if (expected == 204) return new JsonObject();
        try {
            return new JsonObject(answer.body());
        } catch (DecodeException notAnObject) {
            throw new Failed(step + ": the node answered " + answer.statusCode() + " without a JSON object");
        }
    }

    /** The text member {@code name} of {@code answer}. */
    private static String member(JsonObject answer, String name, String step) throws Failed {
        if (answer.getValue(name) instanceof String text && !text.isEmpty()) return text;
        throw new Failed(step + ": the node's answer holds no " + name);
    }

    /** {@code token} written to {@code file} through a file of its own in the same folder, its owner's alone where POSIX allows it. */
    private static void write(Path file, String token) throws Failed {
        Path folder = file.getParent();
        Path written = null;
        try {
            written = folder.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? Files.createTempFile(folder, ".rail8-token-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(folder, ".rail8-token-", ".tmp");
            Files.writeString(written, token, StandardCharsets.UTF_8);
            Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unwritable) {
            throw new Failed("the token file " + file + " could not be written: " + Cli.oneLine(unwritable.toString()));
        } finally {
            if (written != null) {
                try {
                    Files.deleteIfExists(written);
                } catch (IOException ignored) {   // the move took it, or the folder refuses it; the failure is reported above
                }
            }
        }
    }

    /** A step that did not go through; its message names the step and never a password, key or token. */
    private static final class Failed extends Exception {
        Failed(String message) {
            super(message, null, false, false);
        }
    }
}
