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
 * {@link Secrets} resolves a reference {@code secret://<name>}, name {@code [a-z0-9][a-z0-9._-]*}, from the node's secret store
 * alone, and its placeholder twin (the CLI's dry build) answers every well-formed reference with one fixed value of its kind and
 * no store at all. Both refuse a malformed one. A {@code RAIL8_SECRET_<NAME>} variable — upper case, {@code .} and {@code -} as
 * {@code _} — is no source: it is only handed to the store's import, while the store never held its name.
 */
class SecretsTest {

    static final String KEY = "RAIL8_SECRETS_KEY";
    static final String KEY_VALUE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    /** {@code env} and the store's key, read by a node whose store is started on it. */
    private static Secrets onNode(Map<String, String> env) {
        Map<String, String> keyed = new HashMap<>(env);
        keyed.put(KEY, KEY_VALUE);
        FakeRuntime rt = FakeRuntime.create();
        return Secrets.fromEnv(keyed).withStore(new MemorySecretStores().start("node-1", keyed, rt.clock(), rt.timer()));
    }

    /** A reference's variable is its name upper-cased, {@code .} and {@code -} as {@code _}; decoys hold the unmapped spellings. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "secret://partner-token, RAIL8_SECRET_PARTNER_TOKEN, partner-token",
            "secret://db.main-pw,    RAIL8_SECRET_DB_MAIN_PW,    db.main-pw",
            "secret://0key_x,        RAIL8_SECRET_0KEY_X,        0key_x",
            "secret://a,             RAIL8_SECRET_A,             a"})
    void nameMapping(String reference, String variable, String name) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_SECRET_" + name, "decoy: the name as written");
        env.put("RAIL8_SECRET_" + name.toUpperCase(java.util.Locale.ROOT), "decoy: upper case, . and - kept");
        env.put(name, "decoy: the bare name");
        env.put(variable.substring("RAIL8_SECRET_".length()), "decoy: no prefix");
        env.put(variable, "the-value-of-" + variable);   // last: a decoy whose key happens to be the variable is overwritten

        assertEquals(variable, Secrets.variable(reference));
        assertEquals(Optional.of("the-value-of-" + variable), onNode(env).importable(reference));
    }

    /** A variable is no source: a name the store does not hold is absent, whatever variable is set. */
    @Test
    void aVariableAloneIsAbsent() {
        Secrets secrets = onNode(Map.of("RAIL8_SECRET_PARTNER_TOKEN", "the-variable-value"));

        assertEquals(Optional.empty(), secrets.resolve("secret://partner-token"));
        assertEquals(Optional.of("secret 'secret://partner-token' is not set: the secret store holds no such secret"),
                secrets.problem("secret://partner-token", EnumSet.of(Secret.Kind.VALUE)));
        assertThrows(IllegalArgumentException.class, () -> secrets.handle("secret://partner-token"));
    }

    /** Nothing is importable that is not set, set empty, or under another name — never another variable's value. */
    @Test
    void missingIsNotImportable() {
        Secrets secrets = onNode(Map.of(
                "RAIL8_SECRET_OTHER", "other-value",
                "PARTNER_TOKEN", "unprefixed-value",
                "RAIL8_SECRET_PARTNER", "prefix-of-the-name",
                "RAIL8_SECRET_EMPTY", ""));

        assertEquals(Optional.empty(), secrets.importable("secret://partner-token"));
        assertEquals(Optional.empty(), secrets.importable("secret://empty"), "a variable set empty counts as not set");
        assertEquals(Optional.of("other-value"), secrets.importable("secret://other"), "fixture: the env is read");
        assertEquals(Optional.empty(), Secrets.placeholder().importable("secret://other"), "the placeholder imports nothing");
        assertEquals(Optional.empty(), Secrets.fromEnv(Map.of("RAIL8_SECRET_OTHER", "other-value")).importable("secret://other"),
                "nothing before the store is joined");
    }

    /**
     * The variables a node warns of at its start: those set to a value whose name the store holds or held, by name, sorted; not
     * one set empty, not one of a name the store never held, and none off the node.
     */
    @Test
    void theImportedVariablesAreTheSetOnesOfANameTheStoreEverHeld() {
        Secrets secrets = Secrets.fromEnv(Map.of("RAIL8_SECRET_DB_MAIN", "x", "RAIL8_SECRET_A", "y", "RAIL8_SECRET_EMPTY", "", "RAIL8_SECRET_NEW", "z"));

        assertEquals(List.of("RAIL8_SECRET_A", "RAIL8_SECRET_DB_MAIN"), secrets.importedVariables(Set.of("db.main", "a", "empty", "gone")));
        assertEquals(List.of(), Secrets.placeholder().importedVariables(Set.of("a")));
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

    /** What the node holds for its secrets (D-081: the env map NodeConfig is built from) never shows a value in its text. */
    @Test
    void aValueNeverShowsInTheNodeConfigOrTheResolver() {
        String value = "s3cr3t-value-0815";
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", "/tmp/rail8-secrets-test");
        env.put("RAIL8_SECRET_PARTNER_TOKEN", value);
        Secrets secrets = onNode(env);
        assertEquals(Optional.of(value), secrets.importable("secret://partner-token"), "the value is there: the checks below are not vacuous");

        assertFalse(String.valueOf(secrets).contains(value), "Secrets' text: " + secrets);
        String config = String.valueOf(NodeConfig.from(env));
        assertFalse(config.contains(value), "NodeConfig's text: " + config);
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
     * On a node the store is the only source: its row is read, as its kind, and a handle follows it at each read until the name is
     * deleted; a name of another kind than its place takes is a problem naming both kinds. Once imported a name's variable is not
     * importable any more, and once deleted the store refuses to import it.
     */
    @Test
    void theStoreIsTheOnlySource() {
        FakeRuntime rt = FakeRuntime.create();
        Map<String, String> env = Map.of(KEY, KEY_VALUE, "RAIL8_SECRET_PARTNER_URL", "from-the-variable");
        SecretStore store = new MemorySecretStores().start("node-1", env, rt.clock(), rt.timer());
        Secrets secrets = Secrets.fromEnv(env).withStore(store);
        assertTrue(MemorySecretStores.importValue(store, "partner-url", "RAIL8_SECRET_PARTNER_URL", secrets.importable("secret://partner-url").orElseThrow()));
        Secret handle = secrets.handle("secret://partner-url");
        assertEquals("from-the-variable", handle.value(), "imported into the store");
        assertEquals(Optional.empty(), secrets.importable("secret://partner-url"), "the store holds it now");

        MemorySecretStores.overwriteValue(store, "partner-url", "from-the-store");
        assertEquals(Optional.of("from-the-store"), secrets.resolve("secret://partner-url"));
        assertEquals("from-the-store", handle.value(), "the handle reads the store now");
        MemorySecretStores.delete(store, "partner-url");
        assertThrows(IllegalStateException.class, handle::value, "deleted: no value, and the variable is not read");
        assertFalse(MemorySecretStores.importValue(store, "partner-url", "RAIL8_SECRET_PARTNER_URL", "from-the-variable"),
                "a deleted name is not imported again");
        assertEquals(Optional.empty(), secrets.resolve("secret://partner-url"));

        MemorySecretStores.createCredentials(store, "db", "app", "pw-0815");
        assertEquals(new Secret.Credentials("app", "pw-0815"), secrets.handle("secret://db", Set.of(Secret.Kind.CREDENTIALS, Secret.Kind.KEY_PAIR)).credentials());
        assertEquals(Optional.of("secret 'secret://db' is a secret of kind credentials in the secret store, taken here as [value]"),
                secrets.problem("secret://db", EnumSet.of(Secret.Kind.VALUE)));
        assertEquals(Optional.empty(), secrets.resolve("secret://db"), "no plain value of a name the store holds as credentials");
        assertEquals(Optional.of("secret 'secret://other' is not set: the secret store holds no such secret"),
                secrets.problem("secret://other", EnumSet.of(Secret.Kind.CREDENTIALS)));
    }
}
