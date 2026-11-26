package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 config} against a {@link StubAdmin}: each command's request, the files of an export written under its folder and
 * nowhere else, the files of an import read from its folder within the limits of one call, and the exit codes, 0 on a 2xx, 1 on
 * a refusal or no answer, 2 on a usage error with nothing sent.
 */
class ConfigCommandTest {

    static final String TOKEN = "config-test-token-5c1d";
    static final Map<String, String> ENV = Map.of("RAIL8_TOKEN", TOKEN);

    final List<StubAdmin> stubs = new ArrayList<>();
    @TempDir
    Path dir;

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubAdmin::close);
    }

    StubAdmin stub() throws IOException {
        StubAdmin s = new StubAdmin();
        stubs.add(s);
        return s;
    }

    static JsonObject json(byte[] body) {
        return new JsonObject(new String(body, StandardCharsets.UTF_8));
    }

    Ran config(StubAdmin admin, String... args) {
        String[] all = new String[args.length + 3];
        all[0] = "config";
        System.arraycopy(args, 0, all, 1, args.length);
        all[args.length + 1] = "--admin";
        all[args.length + 2] = admin.url();
        Ran ran = run(ENV, all);
        assertFalse(ran.out().contains(TOKEN) || ran.err().contains(TOKEN), "the token is never printed: " + ran);
        return ran;
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void getReadsTheObjectWithTheTokenAndPrintsIt() throws Exception {
        StubAdmin admin = stub().answer(200, "{\"etag\":\"e1\"}");
        Ran ran = config(admin, "get", "connection", "acme/erp/rest");
        assertEquals(0, ran.exit(), ran.toString());
        StubAdmin.Request get = admin.only();
        assertEquals("GET", get.method());
        assertEquals("/admin/config/connections/acme/erp/rest", get.path());
        assertEquals(List.of("Bearer " + TOKEN), get.header("authorization"));
        assertTrue(ran.out().contains("\"etag\":\"e1\""), ran.toString());
        assertEquals(0, config(admin, "get", "system", "a b").exit());
        assertEquals("/admin/config/systems/a%20b", admin.requests().getLast().path(), "a character outside a key's is encoded, for the node to refuse");
    }

    @Test
    void eachChangeSendsWhatItsCallerGaveAndNothingElse() throws Exception {
        StubAdmin admin = stub();
        assertEquals(0, config(admin, "consent", "connection", "acme/api", "--expect", "e1", "--digest", "d1").exit());
        assertEquals(0, config(admin, "activate", "system", "acme", "--expect", "e2", "--note", "commit 1").exit());
        assertEquals(0, config(admin, "activate", "connection", "acme/api", "--expect", "e3", "--confirm", "c3").exit());
        assertEquals(0, config(admin, "stop", "acme/api", "--expect", "r1").exit());
        assertEquals(0, config(admin, "start", "acme/api", "--expect", "r2").exit());
        assertEquals(0, config(admin, "mocks", "allow", "--expect", "false").exit());
        assertEquals(0, config(admin, "mocks", "refuse", "--expect", "true", "--confirm", "c4").exit());
        List<StubAdmin.Request> sent = admin.requests();
        assertEquals(List.of("/admin/config/connections/acme/api/-/consent", "/admin/config/systems/acme/-/activate",
                "/admin/config/connections/acme/api/-/activate", "/admin/config/connections/acme/api/-/stop", "/admin/config/connections/acme/api/-/start",
                "/admin/config/-/mocks", "/admin/config/-/mocks"), sent.stream().map(StubAdmin.Request::path).toList());
        assertEquals(List.of(new JsonObject().put("expect", "e1").put("digest", "d1"), new JsonObject().put("expect", "e2").put("note", "commit 1"),
                new JsonObject().put("expect", "e3").put("confirm", "c3"), new JsonObject().put("expect", "r1"), new JsonObject().put("expect", "r2"),
                new JsonObject().put("expect", false).put("allowed", true), new JsonObject().put("expect", true).put("allowed", false).put("confirm", "c4")),
                sent.stream().map(r -> json(r.body())).toList());
        assertTrue(sent.stream().allMatch(r -> r.method().equals("POST") && r.header("content-type").equals(List.of("application/json"))));
    }

    @Test
    void aRefusalExitsOneWithItsCodeAndAUsageErrorTwoWithNothingSent() throws Exception {
        StubAdmin admin = stub().answer(409, "{\"error\":\"the connection acme/api is used by a flow\",\"code\":\"in-use\",\"values\":{}}");
        Ran inUse = config(admin, "activate", "connection", "acme/api", "--expect", "e1");
        assertEquals(1, inUse.exit(), inUse.toString());
        assertTrue(inUse.out().contains("in-use"), "the answer is printed: " + inUse);
        assertTrue(inUse.err().contains("409 in-use: the connection acme/api is used by a flow"), inUse.toString());
        int sent = admin.requests().size();

        for (String[] wrong : List.of(new String[] {"activate", "connection", "acme/api"}, new String[] {"consent", "system", "acme", "--expect", "e", "--digest", "d"},
                new String[] {"mocks", "allow", "--expect", "yes"}, new String[] {"mocks", "maybe", "--expect", "true"}, new String[] {"import", dir.toString()},
                new String[] {"import", dir.toString(), "--check", "--expect", "x"}, new String[] {"import", dir.toString(), "--check", "--note", "n"},
                new String[] {"export", dir.toString(), "--kind", "partner"}, new String[] {"get", "connection"}, new String[] {"stop", "acme/api"},
                new String[] {"activate", "connection", "acme/api", "--expect", "e", "--expect", "f"}, new String[] {"frobnicate"}, new String[] {})) {
            Ran ran = config(admin, wrong);
            assertEquals(2, ran.exit(), List.of(wrong) + ": " + ran);
        }
        assertEquals(sent, admin.requests().size(), "a usage error sends nothing");
        stubs.forEach(StubAdmin::close);
        Ran away = config(admin, "get", "connection", "acme/api");
        assertEquals(1, away.exit(), away.toString());
        assertTrue(away.err().contains("no answer"), away.toString());
    }

    @Test
    void anExportWritesEachFileUnderItsFolderAndNothingWhenOneWouldLandOutsideIt() throws Exception {
        StubAdmin admin = stub().answer(200, new JsonObject().put("files", new JsonArray()
                .add(new JsonObject().put("path", "connection/acme/api.yaml").put("text", "key: acme/api\n"))
                .add(new JsonObject().put("path", "system/acme.yaml").put("text", "key: acme\n"))).put("refused", new JsonArray()).encode());
        Path out = dir.resolve("config/test");
        Ran ran = config(admin, "export", out.toString(), "--kind", "connection", "--prefix", "acme/");
        assertEquals(0, ran.exit(), ran.toString());
        assertEquals("kind=connection&prefix=acme%2F", admin.only().query());
        assertEquals("/admin/config/export", admin.only().path());
        assertEquals("key: acme/api\n", Files.readString(out.resolve("connection/acme/api.yaml")));
        assertEquals("key: acme\n", Files.readString(out.resolve("system/acme.yaml")));
        assertEquals(new JsonArray().add("connection/acme/api.yaml").add("system/acme.yaml"), new JsonObject(ran.out().strip()).getJsonArray("files"));

        Path elsewhere = dir.resolve("elsewhere");
        admin.answer(200, new JsonObject().put("files", new JsonArray().add(new JsonObject().put("path", "connection/a/b.yaml").put("text", "x\n"))
                .add(new JsonObject().put("path", "../escaped.yaml").put("text", "x\n"))).put("refused", new JsonArray()).encode());
        Ran escaped = config(admin, "export", elsewhere.toString());
        assertEquals(1, escaped.exit(), escaped.toString());
        assertFalse(Files.exists(elsewhere.resolve("connection/a/b.yaml")), "all or none");
        assertFalse(Files.exists(dir.resolve("escaped.yaml")));

        admin.answer(200, new JsonObject().put("files", new JsonArray()).put("refused", new JsonArray().add(new JsonObject().put("kind", "connection")
                .put("key", "acme/api"))).encode());
        Ran refused = config(admin, "export", elsewhere.toString());
        assertEquals(1, refused.exit(), refused.toString());
        assertTrue(refused.err().contains("acme/api"), refused.toString());
    }

    @Test
    void anImportSendsTheFolderInTheScopeAndPrintsEachProblemOfARefusal() throws Exception {
        write(dir.resolve("connection/acme/api.yaml"), "key: acme/api\n");
        write(dir.resolve("connection/zeta/api.yaml"), "key: zeta/api\n");
        write(dir.resolve("system/acme.yaml"), "key: acme\n");
        write(dir.resolve("odd.yaml"), "key: odd\n");
        write(dir.resolve("README.md"), "not a file of the configuration\n");
        write(dir.resolve(".git/config.yaml"), "hidden\n");
        write(dir.resolve("connection/.draft.yaml"), "hidden\n");
        StubAdmin admin = stub().answer(200, "{\"expect\":\"abc\"}");
        Ran check = config(admin, "import", dir.toString(), "--kind", "connection", "--prefix", "acme/", "--check");
        assertEquals(0, check.exit(), check.toString());
        StubAdmin.Request sent = admin.only();
        assertEquals("/admin/config/import", sent.path());
        assertEquals("check=true", sent.query());
        JsonObject body = json(sent.body());
        assertEquals(new JsonObject().put("kind", "connection").put("prefix", "acme/"), body.getJsonObject("scope"));
        assertEquals(new JsonArray().add(new JsonObject().put("path", "connection/acme/api.yaml").put("text", "key: acme/api\n"))
                .add(new JsonObject().put("path", "odd.yaml").put("text", "key: odd\n")), body.getJsonArray("files"),
                "a file at another path goes, for the node to name its problem");
        assertNull(body.getValue("expect"));

        admin.answer(422, new JsonObject().put("error", "the import has 1 problems, so nothing is written").put("code", "problems").put("values", new JsonObject())
                .put("problems", new JsonArray().add(new JsonObject().put("path", "odd.yaml").put("code", "path").put("text", "the file is at the wrong path"))).encode());
        Ran write = config(admin, "import", dir.toString(), "--expect", "abc", "--note", "commit 9");
        assertEquals(1, write.exit(), write.toString());
        assertTrue(write.err().contains("422 problems"), write.toString());
        assertTrue(write.err().contains("  odd.yaml: path: the file is at the wrong path"), write.toString());
        JsonObject written = json(admin.requests().getLast().body());
        assertNull(admin.requests().getLast().query());
        assertEquals("abc", written.getString("expect"));
        assertEquals("commit 9", written.getString("note"));
        assertEquals(new JsonObject(), written.getJsonObject("scope"));
        assertEquals(4, written.getJsonArray("files").size());
    }

    @Test
    void anImportBeyondOneCallOrThroughALinkSendsNothing() throws Exception {
        StubAdmin admin = stub();
        for (int i = 0; i <= Config.MAX_FILES; i++) write(dir.resolve("connection/acme/c" + i + ".yaml"), "key: acme/c" + i + "\n");
        Ran many = config(admin, "import", dir.toString(), "--check");
        assertEquals(1, many.exit(), many.toString());
        assertTrue(many.err().contains("--kind or --prefix"), many.toString());
        assertEquals(0, config(admin, "import", dir.toString(), "--prefix", "acme/c1", "--check").exit(), "a prefix narrows it to one call");

        Path big = dir.resolve("big");
        for (int i = 0; i < 40; i++) write(big.resolve("connection/acme/b" + i + ".yaml"), "x".repeat(110 * 1024));
        Ran large = config(admin, "import", big.toString(), "--check");
        assertEquals(1, large.exit(), large.toString());

        Path linked = dir.resolve("linked");
        write(linked.resolve("connection/acme/a.yaml"), "key: acme/a\n");
        Files.createSymbolicLink(linked.resolve("connection/acme/b.yaml"), linked.resolve("connection/acme/a.yaml"));
        Ran link = config(admin, "import", linked.toString(), "--check");
        assertEquals(1, link.exit(), link.toString());
        assertTrue(link.err().contains("symbolic link"), link.toString());
        assertEquals(1, admin.requests().size(), "only the narrowed import was sent");
    }
}
