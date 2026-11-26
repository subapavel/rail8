package dev.rail8.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.config.Ref;
import dev.rail8.schemas.Schemas;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The files of export and import: how a file is read, its problems, the chain of upgrades and the text an export writes. */
class ConfigFilesTest {

    private static final ConfigFiles STANDARD = ConfigFiles.STANDARD;

    /** Format 1 of a system names its instance {@code name}; format 2 calls it {@code instanceName}. */
    private static final FormatUpgrade RENAME = new FormatUpgrade() {
        @Override
        public int from() {
            return 1;
        }

        @Override
        public JsonNode apply(JsonNode body) {
            ObjectNode copy = body.deepCopy();
            copy.set("instanceName", copy.remove("name"));
            return copy;
        }
    };

    /** A system of formats 1 and 2, with any document valid, and the connection of the standard files. */
    private static final ConfigFiles TWO_FORMATS = new ConfigFiles(List.of(
            new ConfigFiles.FileKind("environment.connection", Ref.Kind.CONNECTION, Map.of(1, Schemas.ENVIRONMENT_CONNECTION::validate), List.of()),
            new ConfigFiles.FileKind("environment.system", Ref.Kind.SYSTEM, Map.of(1, ConfigFilesTest::valid, 2, ConfigFilesTest::valid),
                    List.of(RENAME))));

    private static Schemas.Report valid(JsonObject document) {
        return new Schemas.Report(true, List.of());
    }

    private static List<String> codes(ConfigFiles.Read read) {
        return read.problems().stream().map(p -> p.getString("code")).toList();
    }

    @Test
    void aConnectionFileIsReadToItsRefAndItsBodyWithoutTheEnvelope() {
        ConfigFiles.Read read = STANDARD.read("connection/acme/api.yaml", """
                schemaVersion: 1
                kind: environment.connection
                key: acme/api
                adapter: web
                values:
                  url: https://partner.example/api
                  verifyTls: no
                secrets:
                  partner.token:
                    kind: value
                """);
        assertEquals(List.of(), read.problems());
        assertEquals(Optional.of(Ref.connection("acme/api")), read.ref());
        JsonObject body = read.body().orElseThrow();
        assertEquals(new JsonObject().put("adapter", "web").put("values", new JsonObject().put("url", "https://partner.example/api")
                .put("verifyTls", "no")), body, "a YAML word like no stays a text: the save's check of the field refuses it");
    }

    @Test
    void anOlderFormatIsUpgradedAndANewerOneNamesTheNewestOfEachKind() {
        ConfigFiles.Read old = TWO_FORMATS.read("system/acme.yaml", "schemaVersion: 1\nkind: environment.system\nkey: acme\nname: ACME\n");
        assertEquals(List.of(), old.problems());
        assertEquals(new JsonObject().put("instanceName", "ACME"), old.body().orElseThrow());
        ConfigFiles.Read current = TWO_FORMATS.read("system/acme.yaml", "schemaVersion: 2\nkind: environment.system\nkey: acme\ninstanceName: ACME\n");
        assertEquals(new JsonObject().put("instanceName", "ACME"), current.body().orElseThrow());
        assertEquals(new JsonObject().put("instanceName", "ACME"), TWO_FORMATS.upgrade(Ref.Kind.SYSTEM, 1, new JsonObject().put("name", "ACME")),
                "a stored body is upgraded by the same chain");
        assertEquals(2, TWO_FORMATS.newest(Ref.Kind.SYSTEM));

        ConfigFiles.Read newer = TWO_FORMATS.read("system/acme.yaml", "schemaVersion: 3\nkind: environment.system\nkey: acme\n");
        assertEquals(List.of("format"), codes(newer));
        assertEquals(new JsonObject().put("environment.connection", 1).put("environment.system", 2), newer.problems().getFirst().getJsonObject("newest"));
        assertTrue(newer.ref().isEmpty() && newer.body().isEmpty());
        assertEquals(List.of("format"), codes(STANDARD.read("x.yaml", "schemaVersion: 1\nkind: environment.partner\nkey: acme\n")));
        assertThrows(IllegalStateException.class, () -> TWO_FORMATS.upgrade(Ref.Kind.SYSTEM, 3, new JsonObject()));
    }

    @Test
    void aChainOfUpgradesStartsAtFormatOneWithOneStepFromEachFormat() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigFiles.FileKind("environment.system", Ref.Kind.SYSTEM,
                Map.of(1, ConfigFilesTest::valid, 2, ConfigFilesTest::valid), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConfigFiles.FileKind("environment.system", Ref.Kind.SYSTEM,
                Map.of(1, ConfigFilesTest::valid, 3, ConfigFilesTest::valid), List.of(RENAME)));
    }

    @Test
    void eachProblemNamesItsFileAndCodeAndNoneHoldsAValueOfTheFile() {
        ConfigFiles.Read yaml = STANDARD.read("connection/acme/api.yaml", "key: [the-token-value\n");
        assertEquals(List.of("yaml"), codes(yaml));
        assertFalse(yaml.problems().getFirst().encode().contains("the-token-value"), yaml.problems().toString());
        assertTrue(yaml.problems().getFirst().getString("text").contains("line"), yaml.problems().toString());
        assertEquals(List.of("yaml"), codes(STANDARD.read("a.yaml", "- a list\n")));

        ConfigFiles.Read schema = STANDARD.read("connection/acme/api.yaml",
                "schemaVersion: 1\nkind: environment.connection\nkey: acme/api\nadapter: web\nvalues: {}\nrevision: 3\n");
        assertEquals(List.of("schema"), codes(schema));
        assertEquals("/revision", schema.problems().getFirst().getString("at"));
        assertEquals(Optional.of(Ref.connection("acme/api")), schema.ref(), "a file the schema refuses still names its object");
        assertTrue(schema.body().isEmpty());

        ConfigFiles.Read key = STANDARD.read("connection/Acme.yaml", "schemaVersion: 1\nkind: environment.connection\nkey: Acme\nadapter: web\nvalues: {}\n");
        assertEquals(List.of("key"), codes(key));
        assertTrue(key.ref().isEmpty());

        ConfigFiles.Read path = STANDARD.read("connection/other.yaml", "schemaVersion: 1\nkind: environment.connection\nkey: acme/api\nadapter: web\nvalues: {}\n");
        assertEquals(List.of("path"), codes(path));
        assertEquals("connection/acme/api.yaml", path.problems().getFirst().getString("expected"));

        String big = "schemaVersion: 1\n# " + "x".repeat((int) ConfigFiles.MAX_FILE_BYTES) + "\n";
        assertEquals(List.of("too-large"), codes(STANDARD.read("a.yaml", big)));
    }

    @Test
    void theTextOfAConnectionKeepsTheFormsOrderLeavesDefaultsOutAndReadsBackToTheSameBody() {
        Settings form = Settings.of(
                Field.text("url", "URL", "Where.").required().server(),
                Field.bool("verifyTls", "Verify TLS", "Whether.").byDefault(true),
                Field.integer("timeoutMs", "Timeout (ms)", "How long.").byDefault(30_000),
                Field.secret("token", "Token", "The login."));
        JsonObject body = new JsonObject().put("adapter", "web").put("mock", new JsonObject().put("reply", "echo"))
                .put("values", new JsonObject().put("zeta", "0012").put("token", "secret://partner.token").put("timeoutMs", 30_000)
                        .put("verifyTls", false).put("url", "https://partner.example/api").put("allowedHosts", new io.vertx.core.json.JsonArray().add("a.example")));
        Ref api = Ref.connection("acme/api");
        String text = STANDARD.text(api, body, Optional.of(form), new JsonObject().put("partner.token", new JsonObject().put("kind", "value")));
        assertEquals("""
                schemaVersion: 1
                kind: environment.connection
                key: acme/api
                adapter: web
                mock:
                  reply: echo
                values:
                  url: https://partner.example/api
                  verifyTls: false
                  token: secret://partner.token
                  allowedHosts:
                  - a.example
                  zeta: "0012"
                secrets:
                  partner.token:
                    kind: value
                """, text);
        ConfigFiles.Read back = STANDARD.read(ConfigFiles.path(api), text);
        assertEquals(List.of(), back.problems());
        assertEquals(ConfigFiles.withoutDefaults(body, form), back.body().orElseThrow());
    }
}
