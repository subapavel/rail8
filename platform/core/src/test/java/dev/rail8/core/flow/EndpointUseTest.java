package dev.rail8.core.flow;

import dev.rail8.api.Call;
import dev.rail8.api.Connection;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Inbound;
import dev.rail8.api.Outbound;
import dev.rail8.api.Secret;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An endpoint file of {@code schemaVersion: 1} split by an adapter's forms: its connection (keyed by the endpoint's id, the
 * forms' defaults filled in, a secret as a handle), its settings in the flow, the reply's limit of a send, and the check of
 * the endpoint's config as written.
 */
class EndpointUseTest {

    /** An adapter like {@code http}: in takes requests at an address and reaches no connection; out sends to a connection. */
    static final Forms HTTP_LIKE = new Forms(
            Settings.of(
                    Field.text("url", "Address", "Scheme, host, port and a path prefix.").required().pattern("[^@]*").out(),
                    Field.integer("timeoutMs", "Timeout", "How long a send may take.").byDefault(30_000).range(1, 600_000).out(),
                    Field.secret("password", "Password", "The partner's password.").out()),
            Settings.of(
                    Field.text("address", "Address", "Where the node takes requests.").in(),
                    Field.text("path", "Path", "Joined to the url's path.").pattern("/.*").out(),
                    Field.integer("pageSize", "Page size", "Items per page.").byDefault(50).out()),
            Map.of(Direction.IN, Forms.Reach.NO_CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));

    /** An adapter that describes neither form and connects both ways, as the mock does. */
    static final Forms UNDESCRIBED = new Forms(Settings.NONE, Settings.NONE,
            Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));

    /** An internal adapter: out reaches no connection, its flow form has {@code address}. */
    static final Forms INTERNAL = new Forms(Settings.NONE,
            Settings.of(Field.text("address", "Address", "The flow called.").required().out()),
            Map.of(Direction.OUT, Forms.Reach.NO_CONNECTION));

    static EndpointConfig endpoint(String id, Map<String, Object> config) {
        return new EndpointConfig(id, URI.create("x://" + id), config);
    }

    static Map<String, Object> config(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void anOutEndpointSplitsIntoItsConnectionAndItsSettingsWithTheDefaultsFilledIn() {
        EndpointConfig cfg = endpoint("partner", config("url", "https://p.example/api", "path", "/orders", "maxBodyBytes", 1000));
        Outbound out = EndpointUse.outbound(HTTP_LIKE, cfg);
        Connection connection = out.connection().orElseThrow();
        assertEquals("partner", connection.key(), "the connection's key is the endpoint's id");
        assertEquals(Map.of("url", "https://p.example/api", "timeoutMs", 30_000L), connection.values(), "the connection form's values, its default filled in");
        assertEquals(Map.of(), connection.secrets());
        assertEquals(Map.of("path", "/orders", "pageSize", 50L), EndpointUse.settings(HTTP_LIKE, Direction.OUT, cfg), "the flow form's values, its default filled in");
        assertEquals(new Call("shop/orders", "send", Map.of("path", "/orders", "pageSize", 50L), 1000, Optional.empty()),
                EndpointUse.call(HTTP_LIKE, cfg, "shop/orders", "send", 4096));
    }

    @Test
    void anInEndpointOfADirectionWithoutAConnectionHasOnlyItsSettingsAndTheNodesKeysStayOut() {
        EndpointConfig cfg = endpoint("door", config("address", "/orders", "flow", "shop/orders", "maxBodyBytes", 10));
        Inbound in = EndpointUse.inbound(HTTP_LIKE, cfg, "shop/orders", 2048);
        assertEquals(new Inbound("shop/orders", Optional.empty(), Map.of("address", "/orders"), 2048), in,
                "no connection; the flow's own id and body limit, never the file's");
    }

    @Test
    void aSecretIsAHandleNamedAfterTheEndpointAndTheFieldAndNoTextShowsItsValue() {
        EndpointConfig cfg = endpoint("Partner_EU", config("url", "https://p.example", "password", "hunter2", "maxBodyBytes", 10));
        Connection connection = EndpointUse.outbound(HTTP_LIKE, cfg).connection().orElseThrow();
        Secret password = connection.secret("password").orElseThrow();
        assertEquals("secret://partner_eu.password", password.reference(), "the endpoint and the field, lower-cased, in a reference's letters");
        assertEquals("hunter2", password.value());
        assertFalse(connection.values().containsKey("password"), "a secret is never a plain value");
        assertFalse(connection.toString().contains("hunter2"), connection.toString());
        assertFalse(password.toString().contains("hunter2"), password.toString());
    }

    /** An adapter with a login of a password or credentials, and a key of text or a key pair, as SFTP's. */
    static final Forms TYPED = new Forms(
            Settings.of(
                    Field.secret("password", "Password", "The password.", Secret.Kind.VALUE, Secret.Kind.CREDENTIALS),
                    Field.secret("key", "Key", "The key.", Secret.Kind.KEY_PAIR, Secret.Kind.VALUE)),
            Settings.NONE,
            Map.of(Direction.IN, Forms.Reach.CONNECTION, Direction.OUT, Forms.Reach.CONNECTION));

    @Test
    void aReferenceInASecretFieldIsTheSourcesHandleAskedForTheFieldsKindsInTheirOrder() {
        List<String> asked = new java.util.ArrayList<>();
        SecretSource source = (reference, kinds) -> {
            asked.add(reference + " " + kinds.stream().map(Secret.Kind::id).toList());
            return Secret.credentials(reference, () -> new Secret.Credentials("u", "p"));
        };
        EndpointConfig cfg = endpoint("drop", config("password", "secret://drop.login", "key", "secret://drop.key"));
        Connection connection = EndpointUse.inbound(TYPED, cfg, "shop/f", 9, source).connection().orElseThrow();
        assertEquals(java.util.Set.of("secret://drop.login [value, credentials]", "secret://drop.key [key-pair, value]"), java.util.Set.copyOf(asked));
        assertEquals("secret://drop.login", connection.secret("password").orElseThrow().reference());
        assertEquals(Secret.Kind.CREDENTIALS, connection.secret("password").orElseThrow().kind());
        assertFalse(connection.values().containsKey("password"), "a reference is never a plain value");
    }

    @Test
    void aSourcesRefusalNamesTheEndpointAndTheField() {
        SecretSource refusing = (reference, kinds) -> { throw new IllegalArgumentException("secret '" + reference + "' is not set"); };
        EndpointConfig cfg = endpoint("drop", config("password", "secret://drop.login"));
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EndpointUse.outbound(TYPED, cfg, refusing));
        assertEquals("endpoint 'drop': 'password': secret 'secret://drop.login' is not set", refused.getMessage());
    }

    @Test
    void withoutASourceAReferenceIsAHandleOfTheFieldsFirstKindThatNoReadTakes() {
        EndpointConfig cfg = endpoint("drop", config("key", "secret://drop.key"));
        Secret key = EndpointUse.outbound(TYPED, cfg).connection().orElseThrow().secret("key").orElseThrow();
        assertEquals(Secret.Kind.KEY_PAIR, key.kind());
        IllegalStateException unread = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, key::keyPair);
        assertTrue(unread.getMessage().startsWith("secret://drop.key is not read here"), unread.getMessage());
    }

    @Test
    void undescribedFormsGiveEveryKeyButTheNodesToTheConnectionOnADirectionThatConnects() {
        EndpointConfig in = endpoint("feed", config("dir", "/in", "flow", "shop/f", "maxBodyBytes", 5));
        assertEquals(new Inbound("shop/f", Optional.of(new Connection("feed", Map.of("dir", "/in"), Map.of())), Map.of(), 99),
                EndpointUse.inbound(UNDESCRIBED, in, "shop/f", 99));
        EndpointConfig out = endpoint("partner", config("answer", "ok", "maxBodyBytes", 7));
        assertEquals(new Connection("partner", Map.of("answer", "ok"), Map.of()), EndpointUse.outbound(UNDESCRIBED, out).connection().orElseThrow(),
                "the reply's limit is not the connection's");
        assertEquals(new Call("shop/f", "s", Map.of(), 7, Optional.empty()), EndpointUse.call(UNDESCRIBED, out, "shop/f", "s", 99));
        assertEquals(List.of(), EndpointUse.check(UNDESCRIBED, Direction.IN, in.options()), "an undescribed form takes anything");
        assertEquals(List.of(), EndpointUse.check(UNDESCRIBED, Direction.OUT, out.options()));

        Forms flowOnly = new Forms(Settings.NONE, Settings.NONE, Map.of(Direction.OUT, Forms.Reach.NO_CONNECTION));
        EndpointConfig internal = endpoint("next", config("address", "b", "extra", 1));
        assertEquals(Optional.empty(), EndpointUse.outbound(flowOnly, internal).connection(), "a direction that reaches no connection has none");
        assertEquals(Map.of("address", "b", "extra", 1), EndpointUse.settings(flowOnly, Direction.OUT, internal), "an undescribed flow form takes everything there");
    }

    @Test
    void theReplysLimitIsTheEndpointsMaxBodyBytesOnAConnectingOutAndTheFlowsOnAnInternalOne() {
        assertEquals(1000, EndpointUse.call(HTTP_LIKE, endpoint("p", config("url", "u", "maxBodyBytes", 1000)), "f", "s", 4096).maxReplyBytes());
        assertEquals(4096, EndpointUse.call(HTTP_LIKE, endpoint("p", config("url", "u")), "f", "s", 4096).maxReplyBytes(), "absent: the flow's");
        EndpointConfig internal = endpoint("next", config("address", "b", "maxBodyBytes", 10));
        assertEquals(4096, EndpointUse.call(INTERNAL, internal, "f", "s", 4096).maxReplyBytes(), "an internal adapter's reply is held to the flow's limit");
        assertEquals(Map.of("address", "b"), EndpointUse.call(INTERNAL, internal, "f", "s", 4096).settings());
        assertEquals(List.of(new Settings.Problem("/maxBodyBytes", Settings.Problem.UNKNOWN, "no such setting")),
                EndpointUse.check(INTERNAL, Direction.OUT, internal.options()), "not a setting of an internal adapter");
    }

    @Test
    void theCheckListsTheConnectionsThenTheFlowsThenTheReplysLimitThenTheUnknownKeys() {
        Map<String, Object> config = config("zeta", 1, "timeoutMs", 0, "path", "orders", "alpha", true, "address", "/a", "maxBodyBytes", 0);
        List<Settings.Problem> problems = EndpointUse.check(HTTP_LIKE, Direction.OUT, config);
        assertEquals(List.of(
                "/url required", "/timeoutMs min",
                "/address direction", "/path pattern",
                "/maxBodyBytes min",
                "/alpha unknown", "/zeta unknown"),
                problems.stream().map(p -> p.path() + " " + p.code()).toList(), problems.toString());
        assertEquals(List.of("/maxBodyBytes min"), codes(EndpointUse.check(HTTP_LIKE, Direction.OUT, config("url", "https://p", "maxBodyBytes", 0))),
                "the reply's limit is at least 1");
        assertEquals(List.of("/url direction"), codes(EndpointUse.check(HTTP_LIKE, Direction.IN, config("url", "https://p", "flow", "f", "maxBodyBytes", 9))),
                "a connection field on a direction without a connection; the node's keys of an in endpoint are no problem");
    }

    @Test
    void theReplysLimitIsOptionalForEveryAdapterAndAtLeastOneWhenGiven() {
        assertEquals(List.of(), EndpointUse.check(HTTP_LIKE, Direction.OUT, config("url", "https://p")),
                "a described connection form: left out, the flow's limit holds");
        assertEquals(4096, EndpointUse.call(HTTP_LIKE, endpoint("p", config("url", "https://p")), "f", "s", 4096).maxReplyBytes());
        assertEquals(List.of("/maxBodyBytes min"), codes(EndpointUse.check(HTTP_LIKE, Direction.OUT, config("url", "https://p", "maxBodyBytes", 0))),
                "given, it is at least 1");
        assertEquals(List.of(), EndpointUse.check(UNDESCRIBED, Direction.OUT, config("answer", "ok")),
                "an undescribed one, as the mock: left out, the flow's limit holds");
        assertEquals(4096, EndpointUse.call(UNDESCRIBED, endpoint("p", config("answer", "ok")), "f", "s", 4096).maxReplyBytes());
    }

    @Test
    void aPatternOfAConnectionFieldIsNotCheckedButOneOfAFlowFieldIs() {
        List<Settings.Problem> problems = EndpointUse.check(HTTP_LIKE, Direction.OUT, config("url", "https://user:pw@p.example", "path", "no-slash", "maxBodyBytes", 10));
        assertEquals(List.of("/path pattern"), codes(problems), "user info in an endpoint's url keeps working; a path must start with /");
        assertTrue(problems.stream().noneMatch(p -> p.message().contains("pw@")), problems.toString());
    }

    @Test
    void aDirectionTheAdapterDoesNotServeIsOneProblem() {
        assertEquals(List.of(new Settings.Problem("", Settings.Problem.DIRECTION, "the adapter does not serve the in direction")),
                EndpointUse.check(INTERNAL, Direction.IN, config("address", "b", "zeta", 1)));
    }

    static List<String> codes(List<Settings.Problem> problems) {
        return problems.stream().map(p -> p.path() + " " + p.code()).toList();
    }
}
