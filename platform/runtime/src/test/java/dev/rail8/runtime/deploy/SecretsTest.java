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
 * {@link Secrets} resolves a reference {@code secret://<name>}, name {@code [a-z0-9][a-z0-9._-]*}: on a node from its secret
 * store alone, whose first join imports the node's {@code RAIL8_SECRET_<NAME>} variables as {@code <name>} in lower case with
 * {@code _} as {@code -}; off the node its placeholder twin (the CLI's dry build) answers every well-formed reference with one
 * fixed value. Both refuse a malformed one.
 */
class SecretsTest {

    static final String KEY = "RAIL8_SECRETS_KEY";
    static final String KEY_VALUE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    /** The node's resolver over a store whose first join imported {@code env}. */
    static Secrets imported(Map<String, String> env) {
        FakeRuntime rt = FakeRuntime.create();
        Map<String, String> all = new HashMap<>(env);
        all.put(KEY, KEY_VALUE);
        SecretStore store = new MemorySecretStores().start("node-1", all, rt.clock(), rt.timer());
        return Secrets.fromEnv(all).withStore(store);
    }

    /** A variable is imported as its name below the prefix in lower case, each {@code _} as {@code -}. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "RAIL8_SECRET_PARTNER_TOKEN, secret://partner-token",
            "RAIL8_SECRET_DB_MAIN_PW,    secret://db-main-pw",
            "RAIL8_SECRET_0KEY_X,        secret://0key-x",
            "RAIL8_SECRET_A,             secret://a"})
    void aVariableIsImportedUnderItsMappedName(String variable, String reference) {
        Map<String, String> env = new HashMap<>();
        env.put(variable, "the-value-of-" + variable);
        env.put(variable.substring("RAIL8_SECRET_".length()), "decoy: no prefix");
        assertEquals(Optional.of("the-value-of-" + variable), imported(env).resolve(reference));
    }

    /** A name the store does not hold is an absent value, whatever the variables, and a variable set to the empty string is not imported. */
    @Test
    void missingIsAbsent() {
        Secrets secrets = imported(Map.of("RAIL8_SECRET_OTHER", "other-value", "PARTNER_TOKEN", "unprefixed-value",
                "RAIL8_SECRET_PARTNER", "prefix-of-the-name", "RAIL8_SECRET_EMPTY", ""));
        assertEquals(Optional.empty(), secrets.resolve("secret://partner-token"));
        assertEquals(Optional.empty(), secrets.resolve("secret://empty"), "an empty variable is not imported");
        assertEquals(Optional.of("other-value"), secrets.resolve("secret://other"), "fixture: the env is imported");
        assertEquals(Optional.of("secret 'secret://partner-token' is not set: enter it in the secret store"),
                secrets.problem("secret://partner-token", EnumSet.of(Secret.Kind.VALUE)));
    }

    /** A node's resolver never reads the placeholder: before its store joins, no reference is set. */
    @Test
    void thePlaceholderNeverAnswersOnANodesSecrets() {
        Secrets node = Secrets.fromEnv(Map.of(KEY, KEY_VALUE, "RAIL8_SECRET_DB", "variable-value"));
        assertEquals(Optional.empty(), node.resolve("secret://db"));
        assertEquals(Optional.empty(), node.resolve("secret://anything"));
        assertTrue(node.problem("secret://db", EnumSet.of(Secret.Kind.VALUE)).isPresent());
        assertThrows(IllegalArgumentException.class, () -> node.handle("secret://db"));
        assertNotEquals(Secrets.placeholder(), node);
        assertNotEquals(Secrets.placeholder(), Secrets.fromEnv(Map.of()));
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

    /**
     * What the node holds for its secrets never shows a value or a path in its text, and its equality holds the variables' names
     * only.
     */
    @Test
    void aValueNeverShowsInTheNodeConfigOrTheResolver() {
        String value = "s3cr3t-value-0815";
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", "/tmp/rail8-secrets-test");
        env.put("RAIL8_SECRET_PARTNER_TOKEN", value);
        env.put("RAIL8_SECRET_TLS_FILE", "/run/secrets/the-tls-path");
        Secrets secrets = Secrets.fromEnv(env);
        assertEquals(List.of("RAIL8_SECRET_PARTNER_TOKEN", "RAIL8_SECRET_TLS_FILE"), secrets.imports().names(), "the checks below are not vacuous");

        for (String text : List.of(String.valueOf(secrets), String.valueOf(secrets.imports()), String.valueOf(NodeConfig.from(env)))) {
            assertFalse(text.contains(value) || text.contains("the-tls-path"), text);
        }
        Map<String, String> other = new HashMap<>(env);
        other.put("RAIL8_SECRET_PARTNER_TOKEN", "another-value");
        assertEquals(secrets.imports(), Secrets.fromEnv(other).imports(), "equal by the variables' names");
        assertEquals(secrets.imports().hashCode(), Secrets.fromEnv(other).imports().hashCode());
        assertEquals(secrets, Secrets.fromEnv(other));
    }

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
     * On a node the store is the only source: its row is read, as its kind; after a delete no variable takes over. A handle
     * follows the store at each read, and a name of another kind than its place takes is a problem naming both kinds.
     */
    @Test
    void theStoreIsTheOnlySourceAndADeleteLeavesNoValue() {
        FakeRuntime rt = FakeRuntime.create();
        Map<String, String> env = Map.of(KEY, KEY_VALUE, "RAIL8_SECRET_PARTNER_URL", "from-the-variable");
        SecretStore store = new MemorySecretStores().start("node-1", env, rt.clock(), rt.timer());
        Secrets secrets = Secrets.fromEnv(env).withStore(store);
        Secret handle = secrets.handle("secret://partner-url");
        assertEquals("from-the-variable", handle.value(), "imported at the first join");

        MemorySecretStores.overwriteValue(store, "partner-url", "from-the-store");
        assertEquals(Optional.of("from-the-store"), secrets.resolve("secret://partner-url"));
        assertEquals("from-the-store", handle.value(), "the handle reads the store now");
        MemorySecretStores.delete(store, "partner-url");
        assertThrows(IllegalStateException.class, handle::value, "no variable takes over");

        MemorySecretStores.createCredentials(store, "db", "app", "pw-0815");
        assertEquals(new Secret.Credentials("app", "pw-0815"), secrets.handle("secret://db", Set.of(Secret.Kind.CREDENTIALS, Secret.Kind.KEY_PAIR)).credentials());
        assertEquals(Optional.of("secret 'secret://db' is a secret of kind credentials in the secret store, taken here as [value]"),
                secrets.problem("secret://db", EnumSet.of(Secret.Kind.VALUE)));
        assertEquals(Optional.empty(), secrets.resolve("secret://db"), "no plain value of a name the store holds as credentials");
        for (Secret.Kind kind : Secret.Kind.values()) {
            assertEquals(Optional.of("secret 'secret://other' is not set: enter it in the secret store"), secrets.problem("secret://other", EnumSet.of(kind)));
        }
    }
}
