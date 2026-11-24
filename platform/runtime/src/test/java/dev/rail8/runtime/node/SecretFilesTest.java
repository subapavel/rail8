package dev.rail8.runtime.node;

import dev.rail8.runtime.deploy.Secrets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node's secret variables can each be given as a file, {@code X_FILE} beside {@code X}: read once into {@code X}, one trailing
 * line end dropped. Both forms at once, a path that is not absolute, a file that is missing, unreadable, empty, too large or not
 * UTF-8 text refuse the configuration, naming the variable and never the value.
 */
class SecretFilesTest {

    static final String VALUE = "s3cr3t-value-0815";
    static final int LIMIT = Math.toIntExact(SecretFiles.MAX_BYTES);

    @TempDir
    Path dir;

    static Map<String, String> minimal() {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "ci");
        env.put("RAIL8_NODE_ID", "node-1");
        return env;
    }

    Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** Every secret variable of the node takes its file form; the configuration holds the files' contents. */
    @Test
    void everySecretIsReadFromItsFile() throws IOException {
        Map<String, String> env = minimal();
        env.put("RAIL8_PG_PASSWORD_FILE", file("pg", "pg-pass\n").toString());
        env.put("RAIL8_ADMIN_TOKEN_FILE", file("admin", "admin-token\n").toString());
        env.put("RAIL8_GIT_BASE", "https://git.example.com/");
        env.put("RAIL8_GIT_USER", "rail8");
        env.put("RAIL8_GIT_TOKEN_FILE", file("git", "git-token").toString());
        env.put("RAIL8_VALKEY_URI_FILE", file("valkey", "redis://user:pw@valkey:6379\n").toString());
        env.put("RAIL8_SECRET_PARTNER_TOKEN_FILE", file("partner", "partner-token\n").toString());

        NodeConfig c = NodeConfig.from(env);

        assertAll(
                () -> assertEquals("pg-pass", c.pg().password()),
                () -> assertEquals(Optional.of("admin-token"), c.admin().token()),
                () -> assertEquals("git-token", c.git().orElseThrow().credentials().orElseThrow().token()),
                () -> assertEquals(Optional.of(URI.create("redis://user:pw@valkey:6379")), c.leases().valkey()),
                () -> assertEquals(Optional.of("partner-token"), c.secrets().resolve("secret://partner-token")),
                () -> assertEquals(Optional.empty(), c.secrets().resolve("secret://partner-token-file"),
                        "the file form's own key is gone: no path is held as a secret's value"));
    }

    /** One trailing \n or \r\n is dropped, nothing else: a second line end, inner lines and spaces stay. */
    @Test
    void oneTrailingLineEndIsDropped() throws IOException {
        Map<String, String> cases = Map.of(
                "a\n", "a",
                "a\r\n", "a",
                "a\n\n", "a\n",
                " a b \n", " a b ",
                "line1\nline2\n", "line1\nline2",
                "a\r", "a\r",
                "a", "a");
        int i = 0;
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Path f = file("case" + i++, c.getKey());
            Map<String, String> resolved = SecretFiles.resolve(Map.of("RAIL8_SECRET_X_FILE", f.toString()));
            assertEquals(Map.of("RAIL8_SECRET_X", c.getValue()), resolved, "content " + c.getKey().replace("\r", "\\r").replace("\n", "\\n"));
        }
    }

    /** A value given both ways is refused, naming both variables and neither value. */
    @Test
    void bothFormsAreRefused() throws IOException {
        Map<String, String> env = minimal();
        env.put("RAIL8_PG_PASSWORD", VALUE);
        env.put("RAIL8_PG_PASSWORD_FILE", file("pg", VALUE).toString());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env));
        assertTrue(refused.getMessage().contains("RAIL8_PG_PASSWORD and RAIL8_PG_PASSWORD_FILE are both set"), refused.getMessage());
        assertFalse(refused.getMessage().contains(VALUE), refused.getMessage());
    }

    /** A variable set to the empty string counts as not set, in either form. */
    @Test
    void anEmptyVariableCountsAsNotSet() throws IOException {
        Map<String, String> fromFile = SecretFiles.resolve(Map.of("RAIL8_ADMIN_TOKEN", "", "RAIL8_ADMIN_TOKEN_FILE", file("t", "token").toString()));
        assertEquals(Map.of("RAIL8_ADMIN_TOKEN", "token"), fromFile);

        Map<String, String> plain = SecretFiles.resolve(Map.of("RAIL8_ADMIN_TOKEN", "token", "RAIL8_ADMIN_TOKEN_FILE", ""));
        assertEquals(Map.of("RAIL8_ADMIN_TOKEN", "token"), plain);
    }

    /** A missing file is refused at start, naming the variable and the path. */
    @Test
    void aMissingFileIsRefused() {
        Path missing = dir.resolve("absent");
        IllegalArgumentException refused = refused("RAIL8_GIT_TOKEN_FILE", missing.toString());
        assertEquals("RAIL8_GIT_TOKEN_FILE names a file that does not exist: " + missing, refused.getMessage());
    }

    /** A path that cannot be read as a file, a directory here, is refused, naming the variable and the path. */
    @Test
    void anUnreadableFileIsRefused() {
        IllegalArgumentException refused = refused("RAIL8_GIT_TOKEN_FILE", dir.toString());
        assertEquals("RAIL8_GIT_TOKEN_FILE names a file that cannot be read: " + dir, refused.getMessage());
    }

    /** An empty file, or one that holds only a line end, is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\r\n"})
    void anEmptyFileIsRefused(String content) throws IOException {
        Path f = file("empty", content);
        IllegalArgumentException refused = refused("RAIL8_VALKEY_URI_FILE", f.toString());
        assertEquals("RAIL8_VALKEY_URI_FILE names an empty file: " + f, refused.getMessage());
    }

    /** A file of exactly the limit is read; one byte more is refused. */
    @Test
    void aFileLargerThanTheLimitIsRefused() throws IOException {
        Path atLimit = file("at-limit", "a".repeat(LIMIT));
        assertEquals(SecretFiles.MAX_BYTES, SecretFiles.resolve(Map.of("RAIL8_SECRET_KEY_FILE", atLimit.toString())).get("RAIL8_SECRET_KEY").length());

        Path tooLarge = file("too-large", "a".repeat(LIMIT + 1));
        IllegalArgumentException refused = refused("RAIL8_SECRET_KEY_FILE", tooLarge.toString());
        assertEquals("RAIL8_SECRET_KEY_FILE names a file of more than 65536 bytes: " + tooLarge, refused.getMessage());
    }

    /** A path that is not absolute is refused naming the variable only: it may be a secret given under the wrong name. */
    @ParameterizedTest
    @ValueSource(strings = {VALUE, "relative/path", "nul\u0000byte"})
    void aPathThatIsNotAbsoluteIsRefused(String raw) {
        IllegalArgumentException refused = refused("RAIL8_SECRET_DB_FILE", raw);
        assertEquals("RAIL8_SECRET_DB_FILE must be the absolute path of a file (the value is not shown)", refused.getMessage());
    }

    /** Content that is not UTF-8 is refused without showing it. */
    @Test
    void contentThatIsNotUtf8IsRefused() throws IOException {
        Path f = Files.write(dir.resolve("latin1"), (VALUE + "\u00e9").getBytes(StandardCharsets.ISO_8859_1));
        IllegalArgumentException refused = refused("RAIL8_SECRET_DB_FILE", f.toString());
        assertEquals("RAIL8_SECRET_DB_FILE names a file that is not UTF-8 text (the content is not shown): " + f, refused.getMessage());
    }

    /** No refusal shows the value or the file's content, nor does a cause of it. */
    @Test
    void noRefusalShowsAValue() throws IOException {
        List<Map<String, String>> envs = List.of(
                Map.of("RAIL8_ADMIN_TOKEN", VALUE, "RAIL8_ADMIN_TOKEN_FILE", file("both", VALUE).toString()),
                Map.of("RAIL8_ADMIN_TOKEN_FILE", VALUE),
                Map.of("RAIL8_ADMIN_TOKEN_FILE", file("large", VALUE.repeat(LIMIT / VALUE.length() + 1)).toString()),
                Map.of("RAIL8_ADMIN_TOKEN_FILE", Files.write(dir.resolve("bad"), (VALUE + "\u00e9").getBytes(StandardCharsets.ISO_8859_1)).toString()));
        for (Map<String, String> env : envs) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SecretFiles.resolve(env));
            for (Throwable t = refused; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains(VALUE), "the refusal of " + env.keySet() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Only the listed variables and {@code RAIL8_SECRET_<NAME>} have a file form: {@code RAIL8_JOURNAL_KEY_FILE} stays for the
     * journal's key source, {@code RAIL8_SECRET_FILE} is the plain variable of {@code secret://file}, and another
     * {@code _FILE} variable is kept as it is.
     */
    @Test
    void onlyTheNodesSecretsHaveAFileForm() throws IOException {
        Map<String, String> env = Map.of(
                "RAIL8_JOURNAL_KEY_FILE", "/run/secrets/journal-key",
                "RAIL8_SECRET_FILE", "the value of secret://file",
                "RAIL8_PG_USER_FILE", "/run/secrets/pg-user",
                "RAIL8_SECRET_DB_FILE_FILE", file("db-file", "db-file-value\n").toString());

        Map<String, String> resolved = SecretFiles.resolve(env);

        assertEquals(Map.of(
                "RAIL8_JOURNAL_KEY_FILE", "/run/secrets/journal-key",
                "RAIL8_SECRET_FILE", "the value of secret://file",
                "RAIL8_PG_USER_FILE", "/run/secrets/pg-user",
                "RAIL8_SECRET_DB_FILE", "db-file-value"), resolved);
        assertEquals(Optional.of("db-file-value"), Secrets.fromEnv(resolved).resolve("secret://db-file"),
                "a secret whose variable ends in _FILE is given as its file form");
    }

    private static IllegalArgumentException refused(String key, String value) {
        Map<String, String> env = minimal();
        env.put(key, value);
        return assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env));
    }
}
