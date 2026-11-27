package dev.rail8.core.flow;

import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.StepConfig;
import dev.rail8.testkit.FakeServices;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link FlowSecrets} replaces: each handle's current value and its earlier values within their limits, the oldest dropped
 * first and a current value never; a plain value as text, URL-encoded and JSON-escaped; credentials by their password and the
 * base64 of {@code user:password}; a key pair by its private key in base64, whole and line by line; and the plain values the
 * flow's endpoints hold. A secret of a kind the step does not take is refused.
 */
class FlowSecretsTest {

    final Map<String, Object> store = new HashMap<>();

    /** A source over {@link #store}: each handle reads the store at each use. */
    final SecretSource source = (reference, kinds) -> {
        Object v = store.get(reference);
        if (v == null) throw new IllegalArgumentException("secret '" + reference + "' is not set");
        Secret.Kind kind = v instanceof Secret.Credentials ? Secret.Kind.CREDENTIALS : v instanceof Secret.KeyPair ? Secret.Kind.KEY_PAIR : Secret.Kind.VALUE;
        if (!kinds.contains(kind)) throw new IllegalArgumentException("secret '" + reference + "' is of kind " + kind.id());
        return switch (kind) {
            case VALUE -> Secret.of(reference, () -> (String) store.get(reference));
            case CREDENTIALS -> Secret.credentials(reference, () -> (Secret.Credentials) store.get(reference));
            case KEY_PAIR -> Secret.keyPair(reference, () -> (Secret.KeyPair) store.get(reference));
            case VERIFIER -> throw new IllegalStateException("this store holds no verifier");
        };
    };

    static StepConfig step(String reference) {
        return new StepConfig(1, "sign", "sign", false, Map.of("key", reference));
    }

    Secret take(FlowSecrets secrets, String reference, Secret.Kind... kinds) {
        Services services = secrets.services(step(reference), Services.NONE.cache());
        return kinds.length == 0 ? services.secret(step(reference), "key") : services.secret(step(reference), "key", kinds);
    }

    @Test
    void aValueFromBeforeAnOverwriteIsStillScrubbed() {
        store.put("secret://k", "old-value-1234");
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        Secret k = take(secrets, "secret://k");
        assertEquals("old-value-1234", k.value());
        store.put("secret://k", "new-value-5678");
        assertEquals("failed with secret://k and secret://k", secrets.scrubber().apply("failed with old-value-1234 and new-value-5678"));
    }

    @Test
    void theLimitsDropTheOldestEarlierValueAndNeverACurrentOne() {
        store.put("secret://k", "value-0");
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        Secret k = take(secrets, "secret://k");
        for (int i = 0; i <= FlowSecrets.EARLIER_PER_HANDLE + 1; i++) {
            store.put("secret://k", "value-" + i);
            k.value();
        }
        String scrubbed = secrets.scrubber().apply("value-0 value-1 value-2 value-" + (FlowSecrets.EARLIER_PER_HANDLE + 1));
        assertEquals("value-0 secret://k secret://k secret://k", scrubbed, "eight values before the current one: seven kept, the oldest dropped");

        String big = "x".repeat((int) (FlowSecrets.EARLIER_BYTES / 2));
        store.put("secret://a", "a" + big);
        store.put("secret://b", "b" + big);
        FlowSecrets flow = new FlowSecrets(source, Map.of());
        Secret a = take(flow, "secret://a");
        Secret b = take(flow, "secret://b");
        a.value();
        b.value();
        store.put("secret://a", "a-now");
        store.put("secret://b", "b-now");
        a.value();
        b.value();   // two earlier values of half the budget each, plus their prefixes: the oldest goes
        String text = flow.scrubber().apply("[" + "a" + big + "][" + "b" + big + "][a-now][b-now]");
        assertTrue(text.startsWith("[a" + big + "]"), "the oldest earlier value is dropped");
        assertTrue(text.endsWith("[secret://b][secret://a][secret://b]"), text.substring(text.length() - 60));
    }

    @Test
    void aReferenceTheScrubberPutInIsNeverSearchedAgain() {
        store.put("secret://token", "the-long-token-value");
        store.put("secret://short", "secret");
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        take(secrets, "secret://token");
        take(secrets, "secret://short");
        assertEquals("a secret://token and secret://short", secrets.scrubber().apply("a the-long-token-value and secret"));
    }

    @Test
    void theUrlEncodedAndJsonEscapedFormsAreScrubbed() {
        String value = "p@ss w\"rd/\\+&=";
        store.put("secret://k", value);
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        take(secrets, "secret://k");
        String url = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String json = "p@ss w\\\"rd/\\\\+&=";
        assertEquals("q=secret://k {\"k\":\"secret://k\"} secret://k", secrets.scrubber().apply("q=" + url + " {\"k\":\"" + json + "\"} " + value));
        assertEquals("a\\u0001\\n", FlowSecrets.jsonEscaped("a\u0001\n"));
        assertEquals("a\\u001F\\n", FlowSecrets.jsonEscaped("a\u001f\n", true));
    }

    /** A value with a control character is scrubbed from the JSON that Vert.x writes, whose escapes carry upper-case hex. */
    @Test
    void aValueEscapedByTheJsonOfVertxIsScrubbed() {
        String value = "to\u001fken";
        store.put("secret://k", value);
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        take(secrets, "secret://k");
        String json = new io.vertx.core.json.JsonObject().put("k", value).encode();
        assertEquals("{\"k\":\"secret://k\"}", secrets.scrubber().apply(json), json);
    }

    @Test
    void credentialsAreScrubbedByTheirPasswordAndTheirBasicForm() {
        store.put("secret://login", new Secret.Credentials("acme", "pa55-w0rd"));
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        Secret login = take(secrets, "secret://login", Secret.Kind.CREDENTIALS);
        assertEquals("pa55-w0rd", login.credentials().password());
        String basic = Base64.getEncoder().encodeToString("acme:pa55-w0rd".getBytes(StandardCharsets.UTF_8));
        assertEquals("Authorization: Basic secret://login, password secret://login, user acme",
                secrets.scrubber().apply("Authorization: Basic " + basic + ", password pa55-w0rd, user acme"));
    }

    @Test
    void aKeyPairIsScrubbedWholeAndLineByLine() {
        Secret.KeyPair pair = FakeServices.placeholderKeyPair();
        store.put("secret://tls", pair);
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        take(secrets, "secret://tls", Secret.Kind.KEY_PAIR);
        String base64 = Base64.getEncoder().encodeToString(pair.privateKey().getEncoded());
        String pem = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.privateKey().getEncoded());
        assertEquals("secret://tls", secrets.scrubber().apply(base64));
        String scrubbed = secrets.scrubber().apply("-----BEGIN PRIVATE KEY-----\n" + pem + "\n-----END PRIVATE KEY-----");
        for (String line : pem.split("\n")) assertFalse(scrubbed.contains(line), scrubbed);
    }

    @Test
    void theValuesTheEndpointsHoldAreScrubbedToo() {
        FlowSecrets secrets = new FlowSecrets(source, Map.of("secret://endpoint-password", "ep-s3cret"));
        assertEquals("login secret://endpoint-password", secrets.scrubber().apply("login ep-s3cret"));
    }

    @Test
    void aSecretOfAKindTheStepDoesNotTakeIsRefusedAndNoKindIsRefused() {
        store.put("secret://login", new Secret.Credentials("acme", "pa55"));
        FlowSecrets secrets = new FlowSecrets(source, Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> take(secrets, "secret://login"));
        assertTrue(e.getMessage().contains("step 'sign'") && e.getMessage().contains("credentials"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> secrets.services(step("secret://login"), Services.NONE.cache())
                .secret(step("secret://login"), "key", new Secret.Kind[0]));
    }
}
