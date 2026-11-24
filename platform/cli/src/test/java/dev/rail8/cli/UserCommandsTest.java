package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail8 user add <name> [--role <role>] [--admin <url>]} POSTs {@code {name, kind: "person", roles}} to
 * {@code <admin>/admin/users} with {@code Authorization: Bearer $RAIL8_ADMIN_TOKEN} and prints the first password the node
 * answers; {@code rail8 key new} prints a new emergency key and sends nothing. The node is a {@link StubAdmin}.
 */
class UserCommandsTest {

    static final String KEY = "rail8k_" + "A".repeat(43);
    static final Pattern KEY_FORM = Pattern.compile("rail8k_[A-Za-z0-9_-]{43}");

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

    /** One POST to /admin/users with the key as the bearer; the body names a person and the role; the first password is printed. */
    @Test
    void userAddPostsAPersonWithTheRoleAndPrintsTheFirstPassword() throws Exception {
        StubAdmin admin = stub().answer(201, "{\"name\":\"alice\",\"firstPassword\":\"Tr7-first-pw\"}");

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", KEY), "user", "add", "alice", "--role", "administrator", "--admin", admin.url());

        assertEquals(0, ran.exit(), ran.toString());
        StubAdmin.Request post = admin.only();
        assertEquals("POST", post.method());
        assertEquals("/admin/users", post.path());
        assertEquals(List.of("application/json"), post.header("content-type"));
        assertEquals(List.of("Bearer " + KEY), post.header("authorization"));
        assertEquals(new JsonObject().put("name", "alice").put("kind", "person").put("roles", new JsonArray().add("administrator")),
                new JsonObject(new String(post.body(), StandardCharsets.UTF_8)));
        assertEquals("Tr7-first-pw\n", ran.out(), "the first password alone");
        assertFalse(ran.out().contains(KEY) || ran.err().contains(KEY), "the key is never printed: " + ran);
    }

    /** Without --role the person gets no role; the admin URL comes from RAIL8_ADMIN when --admin is not given. */
    @Test
    void userAddWithoutARoleAsksForNone() throws Exception {
        StubAdmin admin = stub().answer(201, "{\"name\":\"bob\",\"firstPassword\":\"pw\"}");

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", KEY, "RAIL8_ADMIN", admin.url() + "/"), "user", "add", "bob");

        assertEquals(0, ran.exit(), ran.toString());
        assertEquals(new JsonArray(), new JsonObject(new String(admin.only().body(), StandardCharsets.UTF_8)).getJsonArray("roles"));
    }

    /** A refusal of the node is printed as it answered, and the exit is 1. */
    @Test
    void aRefusalIsPrintedAndExitsOne() throws Exception {
        String refusal = "{\"error\":\"no role 'boss'\"}";
        StubAdmin admin = stub().answer(400, refusal);

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", KEY), "user", "add", "alice", "--role", "boss", "--admin", admin.url());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.out().contains(refusal), ran.toString());
    }

    /** Without RAIL8_ADMIN_TOKEN, with a wrong option or without a name: a usage error, nothing sent. */
    @Test
    void usageErrorsSendNothing() throws Exception {
        StubAdmin admin = stub();
        assertEquals(2, run(Map.of(), "user", "add", "alice", "--admin", admin.url()).exit(), "no RAIL8_ADMIN_TOKEN");
        List<List<String>> wrong = List.of(
                List.of("user", "add", "--admin", admin.url()),
                List.of("user", "add", "alice", "bob", "--admin", admin.url()),
                List.of("user", "add", "alice", "--role", "--admin", admin.url()),
                List.of("user", "add", "alice", "--role", "developer", "--role", "operator", "--admin", admin.url()),
                List.of("user", "add", "alice", "--kind", "technical", "--admin", admin.url()),
                List.of("user", "remove", "alice", "--admin", admin.url()),
                List.of("user"));
        for (List<String> args : wrong) {
            Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", KEY), args.toArray(String[]::new));
            assertEquals(2, ran.exit(), args + ": " + ran);
        }
        assertEquals(List.of(), admin.requests(), "nothing sent");
    }

    /** {@code rail8 key new} prints one key of the emergency key's form, a new one each time. */
    @Test
    void keyNewPrintsANewKeyOfItsForm() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Ran ran = run(Map.of(), "key", "new");
            assertEquals(0, ran.exit(), ran.toString());
            String key = ran.out().strip();
            assertTrue(KEY_FORM.matcher(key).matches(), "rail8k_ and 43 base64url characters: " + key);
            assertEquals(key + "\n", ran.out(), "the key alone");
            keys.add(key);
        }
        assertEquals(3, keys.size(), "a new key each time");
        assertEquals(2, run(Map.of(), "key", "old").exit(), "key knows new only");
        assertEquals(2, run(Map.of(), "key", "new", "extra").exit(), "key new takes no argument");
    }
}
