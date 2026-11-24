package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.testkit.Ports;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 deploy <repository> <commit|ref> (--expect <commit|none|any> | --check [--expect <commit|none|any>]) [--admin URL]}
 * POSTs {@code {"repository", "commit", "expect"}} to {@code <admin>/admin/deploy}, with {@code ?check=true} for {@code --check},
 * whose body has no {@code expect} unless it is given, as {@code Content-Type: application/json}; the
 * bearer token comes from RAIL8_ADMIN_TOKEN in the environment only and no header is sent without it; the admin URL is
 * {@code --admin}, else RAIL8_ADMIN, else {@code http://localhost:8081}. It prints the node's answer body and exits 0 on a 2xx,
 * 1 on any other status or an unreachable node, 2 on a usage error (nothing sent then). The node is a {@link StubAdmin} on an
 * ephemeral loopback port.
 */
class DeployCommandTest {

    static final String TOKEN = "deploy-test-token-7f3a";
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String RECORDED = "89abcdef0123456789abcdef0123456789abcdef";
    static final String ANSWER = "{\"package\":\"demo\",\"flows\":[\"demo/invoice-sync\"]}";

    final List<StubAdmin> stubs = new ArrayList<>();

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

    // --- the request

    /**
     * The token in the environment: one POST to /admin/deploy, no query, Content-Type application/json,
     * {@code Authorization: Bearer <token>}, the body the repository, the commit and the expected state and nothing else; the
     * answer's body is printed, the token never.
     */
    @Test
    void postsTheRepositoryAndCommitWithBearerFromEnvAndPrintsTheAnswer() throws Exception {
        StubAdmin admin = stub().answer(200, ANSWER);

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", TOKEN), "deploy", "demo", COMMIT, "--expect", RECORDED, "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        StubAdmin.Request post = admin.only();
        assertEquals("POST", post.method());
        assertEquals("/admin/deploy", post.path());
        assertNull(post.query(), "no --check, no query");
        assertEquals(List.of("application/json"), post.header("content-type"));
        assertEquals(List.of("Bearer " + TOKEN), post.header("authorization"));
        assertEquals(new JsonObject().put("repository", "demo").put("commit", COMMIT).put("expect", RECORDED), json(post.body()));
        assertTrue(ran.out().contains(ANSWER), "the answer's body is printed: " + ran);
        assertFalse(ran.out().contains(TOKEN) || ran.err().contains(TOKEN), "the token is never printed: " + ran);
    }

    @Test
    void noTokenNoHeader() throws Exception {
        StubAdmin admin = stub();

        Ran ran = run(Map.of(), "deploy", "demo", COMMIT, "--expect", "none", "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of(), admin.only().header("authorization"), "no RAIL8_ADMIN_TOKEN, no Authorization header");
    }

    /**
     * {@code --check}, before or after the arguments, adds {@code check=true} and needs no {@code --expect}: without it, the body has
     * no {@code expect}; with it, the body has it. A ref goes as it is given: the node resolves it.
     */
    @Test
    void checkAsksForTheCheckAndARefGoesAsGiven() throws Exception {
        StubAdmin admin = stub();

        Ran after = run(Map.of(), "deploy", "shop", "HEAD", "--check", "--admin", admin.url());
        Ran before = run(Map.of(), "deploy", "--check", "shop", "refs/tags/v1", "--admin", admin.url());
        Ran expecting = run(Map.of(), "deploy", "shop", "HEAD", "--expect", "none", "--check", "--admin", admin.url());

        assertEquals(0, after.exit(), after.toString());
        assertEquals(0, before.exit(), before.toString());
        assertEquals(0, expecting.exit(), expecting.toString());
        List<StubAdmin.Request> posts = admin.requests();
        assertEquals(3, posts.size(), "one POST per run");
        for (StubAdmin.Request post : posts) {
            assertEquals("/admin/deploy", post.path());
            assertEquals("check=true", post.query());
        }
        assertEquals(new JsonObject().put("repository", "shop").put("commit", "HEAD"), json(posts.get(0).body()));
        assertEquals(new JsonObject().put("repository", "shop").put("commit", "refs/tags/v1"), json(posts.get(1).body()));
        assertEquals(new JsonObject().put("repository", "shop").put("commit", "HEAD").put("expect", "none"), json(posts.get(2).body()));
    }

    /**
     * {@code --expect}, before or after the arguments, goes as it is given, {@code none} and {@code any} as well as a commit, and so
     * does a value of another shape: the node judges it. The command line fills in no default and reads no state.
     */
    @Test
    void theExpectedStateGoesAsGiven() throws Exception {
        StubAdmin admin = stub();
        List<String> given = List.of("none", "any", RECORDED, "NONE");

        for (String expect : given) {
            Ran ran = run(Map.of(), "deploy", "--expect", expect, "demo", COMMIT, "--admin", admin.url());
            assertEquals(0, ran.exit(), expect + ": " + ran);
        }

        List<StubAdmin.Request> posts = admin.requests();
        assertEquals(given.size(), posts.size(), "one POST per run, and no read of the state before it");
        for (int i = 0; i < given.size(); i++) {
            assertNull(posts.get(i).query(), "a deploy, not a check");
            assertEquals(new JsonObject().put("repository", "demo").put("commit", COMMIT).put("expect", given.get(i)), json(posts.get(i).body()));
        }
    }

    /** Without {@code --admin} the URL is RAIL8_ADMIN; {@code --admin} wins over it. */
    @Test
    void theAdminUrlIsTheFlagElseRail8Admin() throws Exception {
        StubAdmin fromEnv = stub();
        StubAdmin fromFlag = stub();

        Ran byEnv = run(Map.of("RAIL8_ADMIN", fromEnv.url()), "deploy", "demo", COMMIT, "--expect", "none");
        Ran byFlag = run(Map.of("RAIL8_ADMIN", fromEnv.url()), "deploy", "demo", COMMIT, "--expect", "none", "--admin", fromFlag.url());

        assertEquals(0, byEnv.exit(), byEnv.toString());
        assertEquals(0, byFlag.exit(), byFlag.toString());
        assertEquals(1, fromEnv.requests().size(), "the run without --admin went to RAIL8_ADMIN, the other did not");
        assertEquals("/admin/deploy", fromEnv.only().path());
        assertEquals("/admin/deploy", fromFlag.only().path(), "--admin wins over RAIL8_ADMIN");
    }

    // --- the answer

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {200, 201, 202, 204})
    void every2xxExits0(int status) throws Exception {
        StubAdmin admin = stub().answer(status, status == 204 ? "" : ANSWER);

        Ran ran = run(Map.of(), "deploy", "demo", COMMIT, "--expect", "none", "--admin", admin.url());

        assertEquals(0, ran.exit(), status + ": " + ran);
        assertEquals(1, admin.requests().size());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {302, 400, 401, 403, 404, 409, 412, 422, 500, 503})
    void non2xxExit1(int status) throws Exception {
        String body = "{\"error\":\"refused with " + status + "\",\"problems\":[\"flows/x.yaml at '/steps': p\"]}";
        StubAdmin admin = stub().answer(status, body);

        Ran ran = run(Map.of(), "deploy", "demo", COMMIT, "--expect", "none", "--admin", admin.url());

        assertEquals(1, ran.exit(), status + ": " + ran);
        assertTrue(ran.out().contains(body), "the answer's body is printed: " + ran);
        assertEquals(1, admin.requests().size(), "sent once, never retried");
    }

    @Test
    void unreachableExit1() throws Exception {
        Ran ran = run(Map.of(), "deploy", "demo", COMMIT, "--expect", "none", "--admin", "http://127.0.0.1:" + Ports.free());

        assertEquals(1, ran.exit(), ran.toString());
        assertFalse(ran.err().isBlank(), "stderr says the node could not be reached: " + ran);
    }

    // --- usage: exit 2, nothing sent

    static Stream<List<String>> deployUsageErrors() {
        return Stream.of(List.of("deploy"), List.of("deploy", "demo"), List.of("deploy", "demo", COMMIT, "extra", "--expect", "none"),
                List.of("deploy", "demo", COMMIT, "--expect", "none", "--admin"), List.of("deploy", "demo", COMMIT, "--expect", "none", "--admin", " "),
                List.of("deploy", "demo", COMMIT, "--check", "--check"), List.of("deploy", "demo", COMMIT, "--expect", "none", "--token", TOKEN),
                List.of("deploy", "demo", COMMIT, "--expect", "none", "--bogus", "x"), List.of("deploy", "demo", COMMIT, "--expect", "none", "--name", "shop"),
                List.of("deploy", " ", COMMIT, "--expect", "none"), List.of("deploy", "demo", COMMIT),
                List.of("deploy", "demo", COMMIT, "--expect"), List.of("deploy", "demo", COMMIT, "--expect", " "),
                List.of("deploy", "demo", COMMIT, "--expect", "none", "--expect", "any"));
    }

    /**
     * A usage error — no repository or no commit, an argument more, a deploy without {@code --expect}, {@code --admin} or
     * {@code --expect} without its value, an option given twice, an unknown option ({@code --token} among them: the token is
     * env-only), a blank argument — sends nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("deployUsageErrors")
    void deployUsageErrorsExit2AndSendNothing(List<String> args) throws Exception {
        StubAdmin admin = stub();

        Ran ran = run(Map.of("RAIL8_ADMIN", admin.url()), args.toArray(String[]::new));

        assertEquals(2, ran.exit(), ran.toString());
        assertEquals("", ran.out(), ran.toString());
        assertFalse(ran.err().isBlank(), "usage on stderr: " + ran);
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }
}
