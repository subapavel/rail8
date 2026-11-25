package dev.rail8.runtime.deploy;

import dev.rail8.api.Secret;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-04 (D-081): {@link Secrets} resolves a reference {@code secret://<name>}, name {@code [a-z0-9][a-z0-9._-]*}, from the env map
 * it was built from — {@code RAIL8_SECRET_<NAME>}, upper case, {@code .} and {@code -} as {@code _} — and its placeholder twin
 * (the CLI's dry build) answers every well-formed reference with one fixed value and no env at all. Both refuse a malformed one.
 *
 * <p>The shape these cases assume (P2-04 test-author): {@code Secrets.fromEnv(Map<String, String>)}, {@code Secrets.placeholder()},
 * {@code Optional<String> resolve(String reference)} — empty when the variable is not set, an {@link IllegalArgumentException}
 * whose message names the reference when it is malformed.
 */
class SecretsTest {

    /** A reference's variable is its name upper-cased, {@code .} and {@code -} as {@code _}; decoys hold the unmapped spellings. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "secret://partner-token, RAIL8_SECRET_PARTNER_TOKEN, partner-token",
            "secret://db.main-pw,    RAIL8_SECRET_DB_MAIN_PW,    db.main-pw",
            "secret://0key_x,        RAIL8_SECRET_0KEY_X,        0key_x",
            "secret://a,             RAIL8_SECRET_A,             a"})
    void nameMapping(String reference, String variable, String name) {
        Map<String, String> env = new HashMap<>();
        env.put(variable, "the-value-of-" + variable);
        env.put("RAIL8_SECRET_" + name, "decoy: the name as written");
        env.put("RAIL8_SECRET_" + name.toUpperCase(java.util.Locale.ROOT), "decoy: upper case, . and - kept");
        env.put(name, "decoy: the bare name");
        env.put(variable.substring("RAIL8_SECRET_".length()), "decoy: no prefix");
        env.put(variable, "the-value-of-" + variable);   // last: a decoy whose key happens to be the variable is overwritten

        assertEquals(Optional.of("the-value-of-" + variable), Secrets.fromEnv(env).resolve(reference));
    }

    /** A variable that is not set is an absent value — never an empty string, never another variable's value. */
    @Test
    void missingIsAbsent() {
        Map<String, String> env = Map.of(
                "RAIL8_SECRET_OTHER", "other-value",
                "PARTNER_TOKEN", "unprefixed-value",
                "RAIL8_SECRET_PARTNER", "prefix-of-the-name");

        assertEquals(Optional.empty(), Secrets.fromEnv(env).resolve("secret://partner-token"));
        assertEquals(Optional.empty(), Secrets.fromEnv(Map.of()).resolve("secret://partner-token"));
    }

    /** {@code secret://} followed by anything that is not a name is a problem naming the reference, env or placeholder. */
    @ParameterizedTest
    @ValueSource(strings = {"secret://", "secret://Bad Name", "secret://Upper", "secret://-lead", "secret://.lead", "secret://_lead",
            "secret://a/b", "secret://a b", "secret://a:b"})
    void malformedIsAProblem(String reference) {
        Map<String, String> env = Map.of("RAIL8_SECRET_", "x", "RAIL8_SECRET_UPPER", "x", "RAIL8_SECRET_BAD_NAME", "x",
                "RAIL8_SECRET__LEAD", "x", "RAIL8_SECRET_A_B", "x");

        IllegalArgumentException fromEnv = assertThrows(IllegalArgumentException.class, () -> Secrets.fromEnv(env).resolve(reference),
                "the env-backed resolver refuses " + reference);
        assertTrue(String.valueOf(fromEnv.getMessage()).contains(reference), "the refusal names the reference: " + fromEnv.getMessage());
        IllegalArgumentException placeholder = assertThrows(IllegalArgumentException.class, () -> Secrets.placeholder().resolve(reference),
                "the placeholder checks the syntax too: " + reference);
        assertTrue(String.valueOf(placeholder.getMessage()).contains(reference), "the refusal names the reference: " + placeholder.getMessage());
    }

    /** The dry build's resolver needs no env: every well-formed reference gets one fixed, non-empty value — not the reference echoed. */
    @Test
    void placeholderResolvesWithoutEnv() {
        Optional<String> one = Secrets.placeholder().resolve("secret://partner-token");
        Optional<String> other = Secrets.placeholder().resolve("secret://db.main-pw");

        assertTrue(one.isPresent(), "a well-formed reference resolves without any variable: " + one);
        assertFalse(one.orElseThrow().isEmpty(), "the placeholder is not an empty string");
        assertEquals(one, other, "one fixed placeholder for every reference");
        assertNotEquals("secret://partner-token", one.orElseThrow(), "the placeholder is a value, not the reference handed back");
        assertEquals(Optional.of("https://secret.invalid/"), one, "the placeholder is a URL, so a url option validates (D-081)");
    }

    /** A variable that is set but empty counts as not set: absent, as a missing one. */
    @Test
    void anEmptyVariableIsAbsent() {
        Map<String, String> env = Map.of("RAIL8_SECRET_PARTNER_TOKEN", "", "RAIL8_SECRET_OTHER", "other-value");

        assertEquals(Optional.empty(), Secrets.fromEnv(env).resolve("secret://partner-token"));
        assertEquals(Optional.of("other-value"), Secrets.fromEnv(env).resolve("secret://other"), "fixture: the env is read");
    }

    /**
     * Where a missing secret is set: its variable; a variable that ends in {@code _FILE} below the prefix is read by a node as
     * the file form of another secret, so its value is given as a file, the variable with {@code _FILE} once more.
     */
    @ParameterizedTest
    @CsvSource({
            "secret://partner-token, RAIL8_SECRET_PARTNER_TOKEN",
            "secret://file, RAIL8_SECRET_FILE",
            "secret://profile, RAIL8_SECRET_PROFILE",
            "secret://db-file, 'RAIL8_SECRET_DB_FILE_FILE, the path of a file that holds it'",
            "secret://x.file, 'RAIL8_SECRET_X_FILE_FILE, the path of a file that holds it'",
    })
    void whereAMissingSecretIsSet(String reference, String where) {
        assertEquals(where, Secrets.where(reference));
    }

    /** What the node holds for its secrets (D-081: the env map NodeConfig is built from) never shows a value in its text. */
    @Test
    void aValueNeverShowsInTheNodeConfigOrTheResolver() {
        String value = "s3cr3t-value-0815";
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", "/tmp/rail8-secrets-test");
        env.put("RAIL8_SECRET_PARTNER_TOKEN", value);
        Secrets secrets = Secrets.fromEnv(env);
        assertEquals(Optional.of(value), secrets.resolve("secret://partner-token"), "the value is there: the checks below are not vacuous");

        assertFalse(String.valueOf(secrets).contains(value), "Secrets' text: " + secrets);
        String config = String.valueOf(NodeConfig.from(env));
        assertFalse(config.contains(value), "NodeConfig's text: " + config);
    }

    static final String KEY = "RAIL8_SECRETS_KEY";
    static final String KEY_VALUE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    /** A name under {@code rail8.} belongs to the platform: the node's resolver and the placeholder refuse it, naming the reference. */
    @ParameterizedTest
    @ValueSource(strings = {"secret://rail8.git", "secret://rail8.other"})
    void aPlatformNameIsRefused(String reference) {
        for (Secrets secrets : List.of(Secrets.fromEnv(Map.of("RAIL8_SECRET_RAIL8_GIT", "x")), Secrets.placeholder())) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> secrets.resolve(reference));
            assertTrue(refused.getMessage().contains(reference) && refused.getMessage().contains("rail8."), refused.getMessage());
            assertThrows(IllegalArgumentException.class, () -> secrets.handle(reference, EnumSet.allOf(Secret.Kind.class)));
            assertThrows(IllegalArgumentException.class, () -> secrets.problem(reference, EnumSet.of(Secret.Kind.VALUE)));
        }
    }

    /** The placeholder hands out each kind's placeholder: what {@code rail8 validate} and {@code rail8 test} read. */
    @Test
    void thePlaceholderOfEachKind() {
        Secrets p = Secrets.placeholder();
        assertEquals("https://secret.invalid/", p.handle("secret://a", EnumSet.of(Secret.Kind.VALUE)).value());
        assertEquals(new Secret.Credentials("rail8", "invalid"), p.handle("secret://a", EnumSet.of(Secret.Kind.CREDENTIALS)).credentials());
        Secret.KeyPair pair = p.handle("secret://a", EnumSet.of(Secret.Kind.KEY_PAIR)).keyPair();
        assertEquals("EC", pair.privateKey().getAlgorithm());
        assertEquals("CN=invalid", pair.certificates().getFirst().getSubjectX500Principal().getName());
    }

    /**
     * On a node the store wins over the variable: its row is read, as its kind; after a delete the variable reads again. A handle
     * follows the sources at each read, and a name of another kind than its place takes is a problem naming both kinds.
     */
    @Test
    void theStoreWinsAndADeleteBringsBackTheVariable() {
        FakeRuntime rt = FakeRuntime.create();
        Map<String, String> env = Map.of(KEY, KEY_VALUE, "RAIL8_SECRET_PARTNER_URL", "from-the-variable");
        SecretStore store = new MemorySecretStores().start("node-1", env, rt.clock(), rt.timer());
        Secrets secrets = Secrets.fromEnv(env).withStore(store);
        Secret handle = secrets.handle("secret://partner-url");
        assertEquals("from-the-variable", handle.value());

        MemorySecretStores.createValue(store, "partner-url", "from-the-store");
        assertEquals(Optional.of("from-the-store"), secrets.resolve("secret://partner-url"));
        assertEquals("from-the-store", handle.value(), "the handle reads the store now");
        MemorySecretStores.delete(store, "partner-url");
        assertEquals("from-the-variable", handle.value(), "the variable again");

        MemorySecretStores.createCredentials(store, "db", "app", "pw-0815");
        assertEquals(new Secret.Credentials("app", "pw-0815"), secrets.handle("secret://db", Set.of(Secret.Kind.CREDENTIALS, Secret.Kind.KEY_PAIR)).credentials());
        assertEquals(Optional.of("secret 'secret://db' is a secret of kind credentials in the secret store, taken here as [value]"),
                secrets.problem("secret://db", EnumSet.of(Secret.Kind.VALUE)));
        assertEquals(Optional.empty(), secrets.resolve("secret://db"), "no plain value of a name the store holds as credentials");
        assertEquals(Optional.of("secret 'secret://other' is not set (in the secret store, or RAIL8_SECRET_OTHER)"),
                secrets.problem("secret://other", EnumSet.of(Secret.Kind.VALUE)));
        assertEquals(Optional.of("secret 'secret://other' is not set (in the secret store, which alone holds [credentials])"),
                secrets.problem("secret://other", EnumSet.of(Secret.Kind.CREDENTIALS)));
    }
}
