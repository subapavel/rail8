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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 bootstrap --admin <name> --deploy-account <name> --token-file <path>} against a {@link StubAdmin}: the six
 * requests in their order, each with its body and bearer; the generated password printed once; the token written to the file,
 * its owner's alone; a refused step stops it with exit 1; a usage error sends nothing.
 */
class BootstrapCommandTest {

    static final String KEY = "rail8k_" + "K".repeat(43);
    static final String FIRST = "abcdefghijklmnopqrst";
    static final String SESSION = "rail8s_00112233445566ff_" + "S".repeat(43);
    static final String DEPLOY_TOKEN = "rail8t_0011223344556677_" + "D".repeat(43);
    static final Pattern PASSWORD = Pattern.compile("[a-z2-7]{32}");

    final List<StubAdmin> stubs = new ArrayList<>();
    @TempDir Path work;

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubAdmin::close);
    }

    /** {@code stub} answering each of bootstrap's requests as a node would. */
    static StubAdmin node(StubAdmin stub) {
        return stub.answer("/admin/users", 201, "{\"name\":\"alice\",\"firstPassword\":\"" + FIRST + "\"}")
                .answer("/admin/sessions", 201, "{\"session\":\"" + SESSION + "\",\"expiresAt\":\"2026-11-25T00:00:00Z\",\"idleSeconds\":7200,\"me\":{}}")
                .answer("/admin/me/password", 204, "")
                .answer("/admin/users/ci-deploy/tokens", 201, "{\"id\":\"0011223344556677\",\"name\":\"bootstrap\",\"expiresAt\":\"2027-02-22T00:00:00Z\",\"token\":\"" + DEPLOY_TOKEN + "\"}")
                .answer("/admin/me/logout", 204, "");
    }

    StubAdmin stub() throws IOException {
        StubAdmin s = new StubAdmin();
        stubs.add(s);
        return node(s);
    }

    Ran bootstrap(StubAdmin admin, Path tokenFile) {
        return run(Map.of("RAIL8_ADMIN_TOKEN", KEY, "RAIL8_ADMIN", admin.url()),
                "bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", tokenFile.toString());
    }

    static JsonObject body(StubAdmin.Request request) {
        return request.body().length == 0 ? null : new JsonObject(new String(request.body(), StandardCharsets.UTF_8));
    }

    @Test
    void itMakesTheAdministratorLogsInChangesThePasswordAndIssuesTheDeployAccountsToken() throws Exception {
        StubAdmin admin = stub();
        Path tokenFile = work.resolve("deploy.token");

        Ran ran = bootstrap(admin, tokenFile);

        assertEquals(0, ran.exit(), ran.toString());
        List<StubAdmin.Request> sent = admin.requests();
        assertEquals(List.of("/admin/users", "/admin/sessions", "/admin/me/password", "/admin/users", "/admin/users/ci-deploy/tokens", "/admin/me/logout"),
                sent.stream().map(StubAdmin.Request::path).toList());
        assertTrue(sent.stream().allMatch(r -> r.method().equals("POST")), "every request is a POST");
        assertEquals(new JsonObject().put("name", "alice").put("kind", "person").put("roles", new JsonArray().add("administrator")), body(sent.get(0)));
        assertEquals(List.of("Bearer " + KEY), sent.get(0).header("authorization"), "the administrator is made with the key");
        assertEquals(new JsonObject().put("name", "alice").put("password", FIRST), body(sent.get(1)));
        assertEquals(List.of(), sent.get(1).header("authorization"), "the login carries no bearer");
        for (StubAdmin.Request inSession : sent.subList(2, 6)) {
            assertEquals(List.of("Bearer " + SESSION), inSession.header("authorization"), inSession.path() + " runs in the session");
        }
        String password = body(sent.get(2)).getString("next");
        assertEquals(FIRST, body(sent.get(2)).getString("current"));
        assertTrue(PASSWORD.matcher(password).matches(), "32 characters of lowercase base32: " + password);
        assertEquals(new JsonObject().put("name", "ci-deploy").put("kind", "technical").put("roles", new JsonArray().add("developer")), body(sent.get(3)));
        assertEquals(new JsonObject().put("name", "bootstrap"), body(sent.get(4)));
        assertEquals(password + "\n", ran.out(), "the new password alone, once");
        assertEquals(DEPLOY_TOKEN, Files.readString(tokenFile), "the token alone in the file");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile)), "the file is its owner's alone");
        try (var listed = Files.list(work)) {
            assertEquals(List.of(tokenFile), listed.toList(), "no file of its own is left behind");
        }
        for (String secret : List.of(KEY, FIRST, SESSION, DEPLOY_TOKEN)) {
            assertFalse(ran.out().contains(secret) || ran.err().contains(secret), "never printed: " + ran);
        }
    }

    @Test
    void anExistingTokenFileIsReplaced() throws Exception {
        Path tokenFile = Files.writeString(work.resolve("deploy.token"), "an older token");

        Ran ran = bootstrap(stub(), tokenFile);

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(DEPLOY_TOKEN, Files.readString(tokenFile));
    }

    @Test
    void eachRunGeneratesAnotherPassword() throws Exception {
        String one = bootstrap(stub(), work.resolve("one")).out();
        String two = bootstrap(stub(), work.resolve("two")).out();
        assertNotEquals(one, two);
    }

    /** A refused step: exit 1, the step and the node's answer on the error stream, nothing after it sent, no token file. */
    @Test
    void aRefusedStepStopsIt() throws Exception {
        StubAdmin admin = stub().answer("/admin/users", 409, "{\"error\":\"the name alice is taken\"}");
        Path tokenFile = work.resolve("deploy.token");

        Ran ran = bootstrap(admin, tokenFile);

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.err().contains("the administrator's creation") && ran.err().contains("409") && ran.err().contains("the name alice is taken"), ran.toString());
        assertEquals(1, admin.requests().size(), "nothing after the refused step");
        assertFalse(Files.exists(tokenFile));
    }

    /** A refusal after the password change: the password was printed, and the exit is 1. */
    @Test
    void aRefusalAfterThePasswordChangeLeavesThePasswordPrinted() throws Exception {
        StubAdmin admin = stub().answer("/admin/users/ci-deploy/tokens", 403, "{\"error\":\"forbidden\"}");

        Ran ran = bootstrap(admin, work.resolve("deploy.token"));

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(PASSWORD.matcher(ran.out().strip()).matches(), "the administrator can still log in: " + ran);
        assertTrue(ran.err().contains("the deploy account's token"), ran.toString());
        assertFalse(Files.exists(work.resolve("deploy.token")));
    }

    @Test
    void usageErrorsSendNothing() throws Exception {
        StubAdmin admin = stub();
        String file = work.resolve("t").toString();
        Map<String, String> env = Map.of("RAIL8_ADMIN_TOKEN", KEY, "RAIL8_ADMIN", admin.url());
        assertEquals(2, run(Map.of("RAIL8_ADMIN", admin.url()), "bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", file).exit(),
                "no RAIL8_ADMIN_TOKEN");
        List<List<String>> wrong = List.of(
                List.of("bootstrap"),
                List.of("bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy"),
                List.of("bootstrap", "--admin", "alice", "--token-file", file),
                List.of("bootstrap", "--admin", "alice", "--admin", "bob", "--deploy-account", "ci-deploy", "--token-file", file),
                List.of("bootstrap", "--admin", "--deploy-account", "ci-deploy", "--token-file", file),
                List.of("bootstrap", "--admin", "alice", "--deploy-account", "alice", "--token-file", file),
                List.of("bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", file, "extra"),
                List.of("bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", work.resolve("no/such/folder/t").toString()),
                List.of("bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", work.toString()));
        for (List<String> args : wrong) {
            Ran ran = run(env, args.toArray(String[]::new));
            assertEquals(2, ran.exit(), args + ": " + ran);
        }
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }

    /** A generated password never contains the name, which the node would refuse. */
    @Test
    void aPasswordNeverContainsTheName() {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) assertFalse(Bootstrap.password("ab", random).contains("ab"));
    }
}
