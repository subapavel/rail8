package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which command sends which credential. {@code rail8 deploy} sends RAIL8_TOKEN, else a shared token in RAIL8_ADMIN_TOKEN, else
 * nothing, and never a value of the emergency key's form ({@code rail8k_…}): with one, nothing is sent and the exit is 2.
 * {@code rail8 user add} and the first request of {@code rail8 bootstrap} send RAIL8_ADMIN_TOKEN and never RAIL8_TOKEN. The node
 * is a {@link StubAdmin}.
 */
class CredentialChoiceTest {

    static final String KEY = "rail8k_" + "K".repeat(43);
    static final String TOKEN = "rail8t_0123456789abcdef_" + "T".repeat(43);
    static final String SHARED = "a-shared-token-of-the-bridge";

    final List<StubAdmin> stubs = new ArrayList<>();
    @TempDir Path work;

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubAdmin::close);
    }

    StubAdmin stub() throws IOException {
        StubAdmin s = new StubAdmin();
        stubs.add(s);
        return s;
    }

    static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    /** The Authorization header of the one request {@code rail8 deploy} sent with {@code env}. */
    List<String> deploySends(Map<String, String> env) throws IOException {
        StubAdmin admin = stub();
        Ran ran = run(env, "deploy", "demo", "HEAD", "--expect", "any", "--admin", admin.url());
        assertEquals(0, ran.exit(), ran.toString());
        return admin.only().header("authorization");
    }

    @Test
    void deploySendsRail8TokenBeforeAnythingInRail8AdminToken() throws Exception {
        assertEquals(List.of("Bearer " + TOKEN), deploySends(env("RAIL8_TOKEN", TOKEN)));
        assertEquals(List.of("Bearer " + TOKEN), deploySends(env("RAIL8_TOKEN", TOKEN, "RAIL8_ADMIN_TOKEN", KEY)), "the key stays unsent");
        assertEquals(List.of("Bearer " + TOKEN), deploySends(env("RAIL8_TOKEN", TOKEN, "RAIL8_ADMIN_TOKEN", SHARED)));
    }

    @Test
    void duringTheBridgeDeployWithoutRail8TokenSendsTheSharedToken() throws Exception {
        assertEquals(List.of("Bearer " + SHARED), deploySends(env("RAIL8_ADMIN_TOKEN", SHARED)));
        assertEquals(List.of("Bearer " + SHARED), deploySends(env("RAIL8_TOKEN", "  ", "RAIL8_ADMIN_TOKEN", SHARED)), "a blank RAIL8_TOKEN is none");
        assertEquals(List.of(), deploySends(env()), "nothing set, no Authorization header");
    }

    /** A value of the key's form, in either variable, stops deploy before it sends anything, and the value is never printed. */
    @Test
    void deployNeverSendsAValueOfTheKeysForm() throws Exception {
        StubAdmin admin = stub();
        for (Map<String, String> env : List.of(env("RAIL8_ADMIN_TOKEN", KEY), env("RAIL8_TOKEN", KEY), env("RAIL8_TOKEN", "rail8k_short"),
                env("RAIL8_TOKEN", KEY, "RAIL8_ADMIN_TOKEN", SHARED))) {
            Ran ran = run(env, "deploy", "demo", "HEAD", "--expect", "any", "--admin", admin.url());
            assertEquals(2, ran.exit(), env.keySet() + ": " + ran);
            assertTrue(ran.err().contains("emergency key"), ran.toString());
            for (String value : env.values()) assertFalse(ran.out().contains(value) || ran.err().contains(value), "a value is never printed: " + ran);
        }
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }

    @Test
    void userAddSendsRail8AdminTokenAndNeverRail8Token() throws Exception {
        StubAdmin admin = stub().answer(201, "{\"name\":\"alice\",\"firstPassword\":\"pw\"}");

        Ran ran = run(env("RAIL8_ADMIN_TOKEN", KEY, "RAIL8_TOKEN", TOKEN), "user", "add", "alice", "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of("Bearer " + KEY), admin.only().header("authorization"));
        Ran without = run(env("RAIL8_TOKEN", TOKEN), "user", "add", "alice", "--admin", admin.url());
        assertEquals(2, without.exit(), "RAIL8_TOKEN does not stand in for the key: " + without);
        assertEquals(1, admin.requests().size(), "nothing more sent");
    }

    @Test
    void bootstrapSendsRail8AdminTokenFirstAndNeverRail8Token() throws Exception {
        StubAdmin admin = BootstrapCommandTest.node(stub());

        Ran ran = run(env("RAIL8_ADMIN_TOKEN", KEY, "RAIL8_TOKEN", TOKEN, "RAIL8_ADMIN", admin.url()),
                "bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", work.resolve("token").toString());

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(List.of("Bearer " + KEY), admin.requests().getFirst().header("authorization"));
        assertTrue(admin.requests().stream().noneMatch(r -> r.header("authorization").contains("Bearer " + TOKEN)), "RAIL8_TOKEN is never sent");
        Ran without = run(env("RAIL8_TOKEN", TOKEN, "RAIL8_ADMIN", admin.url()),
                "bootstrap", "--admin", "alice", "--deploy-account", "ci-deploy", "--token-file", work.resolve("token").toString());
        assertEquals(2, without.exit(), "RAIL8_TOKEN does not stand in for the key: " + without);
    }
}
