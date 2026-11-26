package dev.rail8.cli;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code rail8 config}: the configuration of an environment through the node's admin API, {@code /admin/config}, as a pipeline
 * calls it. The admin URL is {@code --admin}, else RAIL8_ADMIN, else {@code http://localhost:8081}, and the bearer
 * {@link AdminApi#token}, as for {@code rail8 deploy}. Each command prints the node's answer body on stdout and exits 0 on a
 * 2xx, 1 on another status (with a line on stderr naming its code and each problem of an import) or a node that cannot be
 * reached, 2 on a usage error, nothing sent then. It never fills in an expected state, a confirmation or a digest: the caller
 * gives each as the node answered it.
 * <ul>
 *   <li>{@code get connection|system <key>}: the object as the node reads it, with its {@code etag} and {@code run};</li>
 *   <li>{@code export <dir> [--kind k] [--prefix p]}: the files of what is in force, one per object at its path under
 *       {@code <dir>}, each written over a file at that path; prints the paths, and exits 1 when the node refused a body;</li>
 *   <li>{@code import <dir> [--kind k] [--prefix p] (--check | --expect <sha256> [--note text])}: the {@code .yaml} files under
 *       {@code <dir>} (none whose name starts with a dot), those whose path names an object outside the scope left out; the
 *       dry run with {@code --check}, else the write of the drafts given the dry run's {@code expect};</li>
 *   <li>{@code consent connection <key> --expect <etag> --digest <sha256>}, {@code activate connection|system <key>
 *       --expect <etag> [--confirm <digest>] [--note text]}, {@code stop|start <key> --expect <run>}: one change of one
 *       object;</li>
 *   <li>{@code mocks allow|refuse --expect <true|false> [--confirm <digest>]}: the switch of mocks.</li>
 * </ul>
 */
final class Config {

    /** The most files one import sends, as the node takes them. */
    static final int MAX_FILES = 1_000;
    /** The most bytes of the body of one import, as the node takes it. */
    static final long MAX_BODY_BYTES = 4L * 1024 * 1024;
    private static final Duration ANSWER = Duration.ofMinutes(2);
    private static final Set<String> KINDS = Set.of("connection", "system");

    private Config() {}

    /** The command's words and options: the words in order, each option at most once. */
    private record Arguments(List<String> words, Map<String, String> options, Set<String> flags) {
        Optional<String> option(String name) {
            return Optional.ofNullable(options.get(name));
        }

        String required(String command, String name, String what) {
            return option(name).orElseThrow(() -> new Cli.Usage(command + ": " + name + " is needed: " + what));
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        String sub = args.length < 2 ? "" : args[1];
        String command = "config " + sub;
        return switch (sub) {
            case "get" -> {
                Arguments a = arguments(command, args, Set.of(), Set.of());
                Ref ref = ref(command, a, KINDS);
                yield call(command, a, env, out, err, api -> api.get(objectPath(ref), AdminApi.token(command, env), ANSWER));
            }
            case "export" -> export(command, arguments(command, args, Set.of("--kind", "--prefix"), Set.of()), env, out, err);
            case "import" -> importFolder(command, arguments(command, args, Set.of("--kind", "--prefix", "--expect", "--note"), Set.of("--check")),
                    env, out, err);
            case "consent" -> {
                Arguments a = arguments(command, args, Set.of("--expect", "--digest"), Set.of());
                Ref ref = ref(command, a, Set.of("connection"));
                JsonObject body = new JsonObject().put("expect", a.required(command, "--expect", "the etag the read answers"))
                        .put("digest", a.required(command, "--digest", "the digest of the consent the read shows"));
                yield post(command, a, env, out, err, objectPath(ref) + "/-/consent", body);
            }
            case "activate" -> {
                Arguments a = arguments(command, args, Set.of("--expect", "--confirm", "--note"), Set.of());
                Ref ref = ref(command, a, KINDS);
                JsonObject body = new JsonObject().put("expect", a.required(command, "--expect", "the etag the read or the import answers"));
                a.option("--confirm").ifPresent(c -> body.put("confirm", c));
                a.option("--note").ifPresent(n -> body.put("note", n));
                yield post(command, a, env, out, err, objectPath(ref) + "/-/activate", body);
            }
            case "stop", "start" -> {
                Arguments a = arguments(command, args, Set.of("--expect"), Set.of());
                if (a.words().size() != 1) throw new Cli.Usage(command + ": one connection key");
                Ref ref = new Ref("connection", a.words().getFirst());
                JsonObject body = new JsonObject().put("expect", a.required(command, "--expect", "the run the read answers"));
                yield post(command, a, env, out, err, objectPath(ref) + "/-/" + sub, body);
            }
            case "mocks" -> {
                Arguments a = arguments(command, args, Set.of("--expect", "--confirm"), Set.of());
                if (a.words().size() != 1 || !Set.of("allow", "refuse").contains(a.words().getFirst())) {
                    throw new Cli.Usage(command + ": allow or refuse");
                }
                String expect = a.required(command, "--expect", "true or false, whether the environment allows mocks now");
                if (!expect.equals("true") && !expect.equals("false")) throw new Cli.Usage(command + ": --expect is true or false, was '" + expect + "'");
                JsonObject body = new JsonObject().put("expect", Boolean.parseBoolean(expect)).put("allowed", a.words().getFirst().equals("allow"));
                a.option("--confirm").ifPresent(c -> body.put("confirm", c));
                yield post(command, a, env, out, err, "/admin/config/-/mocks", body);
            }
            case "" -> throw new Cli.Usage("config: get, export, import, consent, activate, stop, start or mocks");
            default -> throw new Cli.Usage("config: unknown command '" + sub + "'");
        };
    }

    /** An object of the configuration as the command names it: its kind, {@code connection} or {@code system}, and its key. */
    private record Ref(String kind, String key) { }

    private static Ref ref(String command, Arguments a, Set<String> kinds) {
        if (a.words().size() != 2 || !kinds.contains(a.words().getFirst())) {
            throw new Cli.Usage(command + ": " + String.join(" or ", kinds.stream().sorted().toList()) + ", then a key");
        }
        return new Ref(a.words().get(0), a.words().get(1));
    }

    /** {@code /admin/config/<kind>s/<key>}, each character of the key outside letters, digits, {@code -._~/} percent-encoded. */
    private static String objectPath(Ref ref) {
        StringBuilder path = new StringBuilder("/admin/config/").append(ref.kind()).append("s/");
        for (byte b : ref.key().getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~/".indexOf(c) >= 0)) path.append(c);
            else path.append('%').append(String.format("%02X", b & 0xff));
        }
        return path.toString();
    }

    /** The words and options after {@code config <sub>}: each option of {@code options} with a value, each of {@code flags} without. */
    private static Arguments arguments(String command, String[] args, Set<String> options, Set<String> flags) {
        List<String> words = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        Set<String> given = new HashSet<>();
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (flags.contains(arg)) {
                if (!given.add(arg)) throw new Cli.Usage(command + ": " + arg + " is given twice");
            } else if (options.contains(arg) || arg.equals("--admin")) {
                if (i + 1 == args.length || args[i + 1].isBlank()) throw new Cli.Usage(command + ": " + arg + " needs a value");
                if (values.putIfAbsent(arg, args[++i]) != null) throw new Cli.Usage(command + ": " + arg + " is given twice");
            } else if (arg.startsWith("--")) {
                throw new Cli.Usage(command + ": unknown option " + arg);
            } else if (arg.isBlank()) {
                throw new Cli.Usage(command + ": a blank argument");
            } else {
                words.add(arg);
            }
        }
        if (values.containsKey("--kind") && !KINDS.contains(values.get("--kind"))) {
            throw new Cli.Usage(command + ": --kind is connection or system, was '" + values.get("--kind") + "'");
        }
        return new Arguments(List.copyOf(words), Map.copyOf(values), Set.copyOf(given));
    }

    @FunctionalInterface
    private interface Call {
        HttpResponse<String> send(AdminApi api) throws AdminApi.NoAnswer;
    }

    private static int post(String command, Arguments a, Map<String, String> env, PrintStream out, PrintStream err, String path, JsonObject body) {
        Optional<String> token = AdminApi.token(command, env);
        return call(command, a, env, out, err, api -> api.post(path, body.encode().getBytes(StandardCharsets.UTF_8), token, ANSWER));
    }

    /** Sends {@code call} to the admin API, prints the answer's body and answers the exit code. */
    private static int call(String command, Arguments a, Map<String, String> env, PrintStream out, PrintStream err, Call call) {
        HttpResponse<String> answer = answer(command, a, env, err, call);
        if (answer == null) return 1;
        out.println(answer.body());
        return answered(command, answer, err) ? 0 : 1;
    }

    /** The answer of {@code call}, or null, said on {@code err}, when the node does not answer. */
    private static HttpResponse<String> answer(String command, Arguments a, Map<String, String> env, PrintStream err, Call call) {
        AdminApi api = AdminApi.of(command, a.option("--admin"), env);
        try {
            return call.send(api);
        } catch (AdminApi.NoAnswer unreachable) {
            err.println("rail8 " + command + ": " + unreachable.getMessage());
            return null;
        }
    }

    /** Whether {@code answer} is a 2xx; else a line on {@code err} with its status, code and text, and one for each problem. */
    private static boolean answered(String command, HttpResponse<String> answer, PrintStream err) {
        if (answer.statusCode() / 100 == 2) return true;
        JsonObject body;
        try {
            body = new JsonObject(answer.body());
        } catch (DecodeException | ClassCastException notAnObject) {
            err.println("rail8 " + command + ": refused, " + answer.statusCode());
            return false;
        }
        err.println("rail8 " + command + ": refused, " + answer.statusCode() + " " + body.getString("code", "") + ": "
                + Cli.oneLine(body.getString("error", "")));
        if (body.getValue("problems") instanceof JsonArray problems) {
            for (Object o : problems) {
                if (o instanceof JsonObject p) err.println("  " + p.getString("path", "") + ": " + p.getString("code", "") + ": " + Cli.oneLine(p.getString("text", "")));
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------- export

    private static int export(String command, Arguments a, Map<String, String> env, PrintStream out, PrintStream err) {
        if (a.words().size() != 1) throw new Cli.Usage(command + ": one directory to write the files into");
        Path dir = path(command, a.words().getFirst()).toAbsolutePath().normalize();
        if (Files.exists(dir) && !Files.isDirectory(dir)) throw new Cli.Usage(command + ": " + dir + " is not a directory");
        StringBuilder query = new StringBuilder();
        a.option("--kind").ifPresent(k -> query.append(query.isEmpty() ? '?' : '&').append("kind=").append(k));
        a.option("--prefix").ifPresent(p -> query.append(query.isEmpty() ? '?' : '&').append("prefix=").append(URLEncoder.encode(p, StandardCharsets.UTF_8)));
        Optional<String> token = AdminApi.token(command, env);
        HttpResponse<String> answer = answer(command, a, env, err, api -> api.get("/admin/config/export" + query, token, ANSWER));
        if (answer == null) return 1;
        if (!answered(command, answer, err)) {
            out.println(answer.body());
            return 1;
        }
        JsonObject export = new JsonObject(answer.body());
        List<Map.Entry<Path, String>> files = new ArrayList<>();
        JsonArray paths = new JsonArray();
        for (Object o : export.getJsonArray("files")) {
            JsonObject f = (JsonObject) o;
            Path target = dir.resolve(f.getString("path")).normalize();
            if (!target.startsWith(dir) || target.equals(dir)) {
                err.println("rail8 " + command + ": the node answered a file outside " + dir + ", so nothing is written: " + f.getString("path"));
                return 1;
            }
            files.add(Map.entry(target, f.getString("text")));
            paths.add(f.getString("path"));
        }
        try {
            for (Map.Entry<Path, String> f : files) {
                Files.createDirectories(f.getKey().getParent());
                Files.writeString(f.getKey(), f.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | UncheckedIOException failed) {
            err.println("rail8 " + command + ": " + Cli.oneLine(failed.toString()));
            return 1;
        }
        JsonArray refused = export.getJsonArray("refused");
        out.println(new JsonObject().put("files", paths).put("refused", refused).encode());
        for (Object o : refused) {
            JsonObject r = (JsonObject) o;
            err.println("rail8 " + command + ": the " + r.getString("kind") + " " + r.getString("key") + " is not exported: its body in force does"
                    + " not verify on the node");
        }
        return refused.isEmpty() ? 0 : 1;
    }

    // ---------------------------------------------------------------------------------------------------------------- import

    private static int importFolder(String command, Arguments a, Map<String, String> env, PrintStream out, PrintStream err) {
        if (a.words().size() != 1) throw new Cli.Usage(command + ": one directory of files to import");
        boolean check = a.flags().contains("--check");
        if (check == a.option("--expect").isPresent()) {
            throw new Cli.Usage(command + ": --check for the dry run, or --expect with the expect of the dry run for the write");
        }
        if (check && a.option("--note").isPresent()) throw new Cli.Usage(command + ": --note goes with the write, --expect");
        Path dir = path(command, a.words().getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) throw new Cli.Usage(command + ": no directory at " + dir);
        Optional<String> kind = a.option("--kind");
        Optional<String> prefix = a.option("--prefix");
        JsonArray files = new JsonArray();
        long bytes = 0;
        try (Stream<Path> walked = Files.walk(dir)) {
            for (Path p : walked.sorted().toList()) {
                if (p.equals(dir)) continue;
                List<String> parts = new ArrayList<>();
                for (Path part : dir.relativize(p)) parts.add(part.toString());
                if (parts.stream().anyMatch(part -> part.startsWith("."))) continue;
                String path = String.join("/", parts);
                if (Files.isSymbolicLink(p)) {
                    err.println("rail8 " + command + ": " + path + " is a symbolic link, which an import does not follow, so nothing is sent");
                    return 1;
                }
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".yaml")) continue;
                if (!inScope(path, kind, prefix)) continue;
                String text = Files.readString(p, StandardCharsets.UTF_8);
                files.add(new JsonObject().put("path", path).put("text", text));
                bytes += text.getBytes(StandardCharsets.UTF_8).length + path.length();
            }
        } catch (IOException | UncheckedIOException failed) {
            err.println("rail8 " + command + ": " + Cli.oneLine(failed.toString()));
            return 1;
        }
        JsonObject scope = new JsonObject();
        kind.ifPresent(k -> scope.put("kind", k));
        prefix.ifPresent(p -> scope.put("prefix", p));
        JsonObject body = new JsonObject().put("scope", scope).put("files", files);
        a.option("--expect").ifPresent(e -> body.put("expect", e));
        a.option("--note").ifPresent(n -> body.put("note", n));
        byte[] json = body.encode().getBytes(StandardCharsets.UTF_8);
        if (files.size() > MAX_FILES || json.length > MAX_BODY_BYTES) {
            err.println("rail8 " + command + ": " + files.size() + " files of " + bytes + " bytes are more than one import takes (" + MAX_FILES
                    + " files, " + MAX_BODY_BYTES + " bytes), so nothing is sent: import the folder by --kind or --prefix, each call all or none");
            return 1;
        }
        Optional<String> token = AdminApi.token(command, env);
        return call(command, a, env, out, err, api -> api.post("/admin/config/import" + (check ? "?check=true" : ""), json, token, ANSWER));
    }

    /**
     * Whether the file at {@code path} belongs to the scope: one at {@code <kind>/<key>.yaml} when the kind and the key's prefix
     * are the scope's; any other, which the node reports, always.
     */
    private static boolean inScope(String path, Optional<String> kind, Optional<String> prefix) {
        int slash = path.indexOf('/');
        if (slash < 0 || !KINDS.contains(path.substring(0, slash))) return true;
        String key = path.substring(slash + 1, path.length() - ".yaml".length());
        return kind.map(k -> k.equals(path.substring(0, slash))).orElse(true) && prefix.map(key::startsWith).orElse(true);
    }

    private static Path path(String command, String given) {
        try {
            return Path.of(given);
        } catch (InvalidPathException wrong) {
            throw new Cli.Usage(command + ": not a path: " + given);
        }
    }
}
