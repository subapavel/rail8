package dev.rail8.components.http;

import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a use of http may set, as its two forms judge it. A sender reaches no connection and sets its address on the node's
 * HTTP port in the flow. A send reaches a connection, which holds the url without user info, the timeout and the pool
 * size, and may set a path in the flow. A key of the other direction, or one the form does not name, is refused.
 */
class HttpSettingsTest {

    static final Forms FORMS = HttpComponent.FORMS;
    static final Settings CONNECTION = FORMS.connection();
    static final Settings FLOW = FORMS.flow();

    @Test
    void aSenderReachesNoConnectionAndASendAlwaysDoes() {
        assertEquals(Map.of(Direction.IN, Forms.Reach.NO_CONNECTION, Direction.OUT, Forms.Reach.CONNECTION), FORMS.reach());
        assertEquals(List.of(), CONNECTION.check(Map.of(), Direction.IN), "no connection field serves the in direction");
    }

    @Test
    void aSenderMustSetItsAddressOnTheHttpPort() {
        assertEquals(List.of(new Settings.Problem("/address", Settings.Problem.REQUIRED, "is required")), FLOW.check(Map.of(), Direction.IN));
        assertEquals(List.of(), FLOW.check(Map.of("address", "/invoice/async"), Direction.IN));
        assertEquals(List.of(), FLOW.check(Map.of("address", "/in", "flow", "invoice", "maxBodyBytes", 1_048_576), Direction.IN),
                "flow and maxBodyBytes are the node's on a sender");
        for (String bad : List.of("in", "/", "/in/", "/a//b", "/../in", "/in?x=1", "/Ü"))
            assertEquals(List.of(new Settings.Problem("/address", Settings.Problem.PATTERN, "must match " + HttpIngress.ADDRESS_PATTERN)),
                    FLOW.check(Map.of("address", bad), Direction.IN), bad);
        for (String gone : List.of("host", "port", "shutdownTimeoutMs"))
            assertEquals(List.of(new Settings.Problem("/" + gone, Settings.Problem.UNKNOWN, "no such setting")),
                    FLOW.check(Map.of("address", "/in", gone, 1), Direction.IN), gone + ": the node's HTTP port has replaced it");
    }

    @Test
    void aConnectionMustSetAUrlAndMaySetATimeoutAndAPool() {
        assertEquals(List.of(), CONNECTION.check(Map.of("url", "http://partner/in", "timeoutMs", 5_000, "poolSize", 2), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/url", Settings.Problem.REQUIRED, "is required")), CONNECTION.check(Map.of(), Direction.OUT));
        assertEquals(Map.of("timeoutMs", 30_000L, "poolSize", 8L), CONNECTION.defaults(Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/maxBodyBytes", Settings.Problem.UNKNOWN, "no such setting")),
                CONNECTION.check(Map.of("url", "http://partner/in", "maxBodyBytes", 1), Direction.OUT), "the reply limit is the platform's, in each call");
    }

    @Test
    void aUrlWithUserInfoIsRefusedByTheConnectionFormWithoutQuotingIt() {
        for (String good : List.of("http://partner/in", "https://partner.example:8443/api/v1?key=a@b", "http://[::1]:9000/x#a@b", "HTTPS://P"))
            assertEquals(List.of(), CONNECTION.check(Map.of("url", good), Direction.OUT), good);
        for (String bad : List.of("https://user:s3cret@partner.example/in", "http://user@partner/in", "http://:s3cret@partner")) {
            List<Settings.Problem> problems = CONNECTION.check(Map.of("url", bad), Direction.OUT);
            assertEquals(1, problems.size(), bad);
            assertEquals("/url", problems.getFirst().path());
            assertEquals(Settings.Problem.PATTERN, problems.getFirst().code(), bad);
            assertFalse(problems.getFirst().message().contains("s3cret") || problems.getFirst().message().contains("user@"),
                    "the problem never quotes the value: " + problems.getFirst().message());
        }
    }

    @Test
    void aSendMaySetAPathFromTheRootAsAnExpression() {
        assertEquals(List.of(), FLOW.check(Map.of(), Direction.OUT), "without a path a send goes to the url as it is");
        assertEquals(List.of(), FLOW.check(Map.of("path", "/orders/42"), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/path", Settings.Problem.PATTERN, "must match /.*")),
                FLOW.check(Map.of("path", "orders"), Direction.OUT));
        assertTrue(((List<?>) FLOW.toJson().get("fields")).stream().anyMatch(f -> f instanceof Map<?, ?> m && "path".equals(m.get("name"))
                && m.get("expression") != null), "the path is an expression: " + FLOW.toJson());
    }

    @Test
    void aKeyOfTheOtherDirectionOrNoneIsRefused() {
        assertEquals(List.of(new Settings.Problem("/path", Settings.Problem.DIRECTION, "a setting of out endpoints only")),
                FLOW.check(Map.of("address", "/in", "path", "/x"), Direction.IN));
        assertEquals(List.of(new Settings.Problem("/address", Settings.Problem.DIRECTION, "a setting of in endpoints only")),
                FLOW.check(Map.of("address", "/x"), Direction.OUT));
        assertEquals(List.of(new Settings.Problem("/proxy", Settings.Problem.UNKNOWN, "no such setting")), FLOW.check(Map.of("address", "/in", "proxy", "p"), Direction.IN));
    }
}
