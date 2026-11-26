package dev.rail8.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.rail8.schemas.PackageTest.PACKAGE;
import static dev.rail8.schemas.PackageTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A package's systems and its flows and tests of {@code schemaVersion: 2}: a system file found by its kind under its key,
 * and what a package says of a flow or a test that names connections, before any node or environment is asked.
 */
class PackageSystemsTest {

    static final String ERP = """
            schemaVersion: 1
            kind: system
            partner: acme
            name: erp
            connections:
              rest: { adapter: http }
            """;

    static final String BILLING = "schemaVersion: 1\nkind: system\nname: billing\nconnections: { sftp: { adapter: sftp } }\n";

    static String flow(String from, String steps) {
        return """
                schemaVersion: 2
                kind: flow
                id: invoices
                version: 1.0.0
                from: %s
                delivery: { mode: sync }
                limits: { maxBodyBytes: 1024 }
                steps: %s
                """.formatted(from, steps);
    }

    static final String SFTP_FLOW = flow("{ adapter: sftp, connection: billing/sftp, settings: { dir: /inbox } }",
            "[ { type: to, name: partner, adapter: http, connection: acme/erp/rest, settings: { path: /invoices } } ]");

    @Test
    void aSystemFileIsFoundByItsKindUnderItsKey(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "systems/acme-erp.yaml", ERP);
        write(dir, "anywhere/billing.yaml", BILLING);
        Package pkg = Package.load(dir);
        assertEquals(List.of(), pkg.problems());
        assertEquals(Set.of("acme/erp", "billing"), pkg.systems().keySet());
        assertEquals("http", pkg.systems().get("acme/erp").getJsonObject("connections").getJsonObject("rest").getString("adapter"));
        assertTrue(pkg.artifacts().contains(new Package.Artifact("systems/acme-erp.yaml", "system", Optional.of("acme/erp"))), pkg.artifacts().toString());
    }

    @Test
    void aSystemKeyTwoFilesDefineIsADuplicate(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "systems/acme-erp.yaml", ERP);
        write(dir, "systems/erp-again.yaml", ERP);
        assertEquals(List.of("system 'acme/erp' is defined twice, in systems/acme-erp.yaml and in systems/erp-again.yaml"), Package.load(dir).problems());
    }

    @Test
    void aFlowOfSchemaVersionTwoNamesConnectionsAndNoEndpointFile(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/invoices.yaml", SFTP_FLOW);
        Package pkg = Package.load(dir);
        assertEquals(List.of(), pkg.problems(), "a key no file of the package defines is the deploy's to check, against what is deployed");
        assertEquals(List.of("billing/sftp", "acme/erp/rest"), Package.connections(pkg.flows().get("invoices")));
    }

    @Test
    void aComputedReceiverIsNotSupportedYet(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/invoices.yaml", flow("{ adapter: http }",
                "[ { type: to, name: partner, adapter: http, by: '${prop.PARTNER}', connections: { domestic: acme/erp/rest } } ]"));
        assertEquals(List.of("flows/invoices.yaml: flow 'invoices' step 'partner' by: a computed receiver (by, connections) is not supported yet; "
                + "name the connection"), Package.load(dir).problems());
    }

    @Test
    void everySettingIsLiteral(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/invoices.yaml", flow("{ adapter: http, settings: { address: '/in/${x}' } }",
                "[ { type: to, name: partner, adapter: http, connection: acme/erp/rest, settings: { path: '/invoices/${key.invoiceId}' } } ]"));
        assertEquals(List.of(
                "flows/invoices.yaml: flow 'invoices' from settings.address: '${' is not supported yet: every setting is literal",
                "flows/invoices.yaml: flow 'invoices' step 'partner' settings.path: '${' is not supported yet: every setting is literal"),
                Package.load(dir).problems());
    }

    @Test
    void aTestOfSchemaVersionTwoMocksTheConnectionsItsFlowNames(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/invoices.yaml", SFTP_FLOW);
        write(dir, "fixtures/inbox/a.xml", "<a/>");
        write(dir, "fixtures/ack.xml", "<ack/>");
        write(dir, "flows/invoices.test.yaml", """
                schemaVersion: 2
                kind: test
                flow: invoices
                mocks:
                  billing/sftp: { files: fixtures/inbox/*.xml }
                  acme/erp/rest: { reply: fixtures/ack.xml }
                cases:
                  - name: fed
                    expect: { sent: { acme/erp/rest: { count: 1, settings: { path: /invoices } } } }
                  - name: not fed
                    mocks: { billing/sftp: { reply: fixtures/ack.xml } }
                    expect: { sent: { globex/erp/rest: { count: 0 } } }
                """);
        assertEquals(List.of(
                "flows/invoices.test.yaml: case 'not fed' sends nothing: no send and no files/messages feed on 'billing/sftp'",
                "flows/invoices.test.yaml: case 'not fed' expects sends to 'globex/erp/rest', which flow 'invoices' does not name"),
                Package.load(dir).problems());
    }

    @Test
    void aTestKeysItsMocksAsItsFlowNamesWhatItTalksTo(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/invoices.yaml", SFTP_FLOW);
        write(dir, "flows/invoices.test.yaml", """
                schemaVersion: 1
                kind: test
                flow: invoices
                mocks: { acme/erp/rest: { fail: 503 }, direct:/orders: { fail: 503 } }
                cases: [ { name: c, send: flows/invoices.yaml, expect: { dlq: 0 } } ]
                """);
        assertEquals(List.of(
                "flows/invoices.test.yaml: a test of schemaVersion 1 tests a flow of schemaVersion 1; flow 'invoices' is of schemaVersion 2",
                "flows/invoices.test.yaml: mock for 'acme/erp/rest', which is no endpoint id: a test of schemaVersion 1 keys its mocks by endpoint id",
                "flows/invoices.test.yaml: mock for 'direct:/orders', which is no endpoint id: a test of schemaVersion 1 keys its mocks by endpoint id"),
                Package.load(dir).problems());
    }

    @Test
    void aCallOfAnotherFlowIsMockedByItsAddress(@TempDir Path dir) throws IOException {
        write(dir, "package.yaml", PACKAGE);
        write(dir, "flows/orders.yaml", flow("{ adapter: http, settings: { address: /orders } }",
                "[ { type: to, name: partner, adapter: direct, settings: { address: /orders/partner } } ]").replace("id: invoices", "id: orders"));
        write(dir, "fixtures/ack.xml", "<ack/>");
        write(dir, "flows/orders.test.yaml", """
                schemaVersion: 2
                kind: test
                flow: orders
                mocks: { direct:/orders/partner: { reply: fixtures/ack.xml }, direct:/elsewhere: { reply: fixtures/ack.xml } }
                cases: [ { name: c, send: fixtures/ack.xml, expect: { sent: { direct:/orders/partner: { count: 1 } } } } ]
                """);
        assertEquals(List.of("flows/orders.test.yaml: mock for 'direct:/elsewhere', which flow 'orders' does not name"), Package.load(dir).problems());
    }
}
