package dev.rail8.schemas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes that name systems' connections: a system file, a flow of {@code schemaVersion: 2} with its sender and its send
 * steps, and a test of {@code schemaVersion: 2} keyed by connection key.
 */
class ConnectionsSchemaTest {

    static final String V2 = """
            schemaVersion: 2
            kind: flow
            id: invoices
            version: 1.0.0
            from: %s
            delivery: { mode: sync }
            limits: { maxBodyBytes: 1024 }
            steps: [ %s ]
            """;

    static Schemas.Report flow(String from, String step) {
        return Schemas.FLOW.validate(Documents.yaml(V2.formatted(from, step)));
    }

    static Schemas.Report step(String step) {
        return flow("{ adapter: http, settings: { address: /in } }", step);
    }

    @Test
    void aSendStepNamesItsConnectionByKey() {
        assertTrue(step("{ type: to, name: partner, adapter: http, connection: acme/erp/rest, settings: { path: /invoices } }").valid());
        assertTrue(step("{ type: to, adapter: http, connection: eshop/rest, maxReplyBytes: 10 }").valid());
    }

    @Test
    void aSendStepOfAnInternalAdapterNamesNoConnection() {
        assertTrue(step("{ type: to, adapter: direct, settings: { address: /orders/partner } }").valid());
    }

    @Test
    void anAgreementIsReservedAndRefused() {
        assertFalse(step("{ type: to, adapter: http, agreement: x }").valid());
        assertFalse(step("{ type: to, adapter: http, connection: acme/erp/rest, agreement: x }").valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ type: to, adapter: http, connection: acme/erp/rest, endpoint: partner }",
            "{ type: to, adapter: http, byProp: PARTNER, endpoints: { a: partner } }",
            "{ type: to, endpoint: partner }",
            "{ type: to, adapter: http, connection: acme/erp/rest, url: http://x }",
            "{ type: to, connection: acme/erp/rest }",
            "{ type: to, adapter: http, connection: acme }",
            "{ type: to, adapter: http, connection: rail8/erp/rest }",
            "{ type: to, adapter: http, connection: by-agreement/rest }",
            "{ type: to, adapter: http, connection: a/b/c/d }",
            "{ type: to, adapter: http, connection: acme/erp/rest, settings: { path: [ a ] } }",
            "{ type: to, adapter: http, connection: acme/erp/rest, maxReplyBytes: 0 }"
    })
    void aSendStepOfSchemaVersionTwoRefusesWhatItDoesNotTake(String step) {
        assertFalse(step(step).valid(), step);
    }

    @Test
    void theOtherStepTypesDoNotChange() {
        assertTrue(step("{ type: xslt, stylesheet: a.xsl }").valid());
        assertFalse(step("{ type: xslt }").valid());
    }

    @Test
    void aSenderNamesItsAdapterAndAPollingSenderItsConnection() {
        assertTrue(flow("{ adapter: http, settings: { address: /in } }", "{ type: xslt, stylesheet: a.xsl }").valid());
        assertTrue(flow("{ adapter: sftp, connection: billing/sftp, settings: { dir: /inbox, afterSuccess: delete } }", "{ type: xslt, stylesheet: a.xsl }").valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ endpoint: in }",
            "{ adapter: http, endpoint: in }",
            "{ adapter: http, senders: [ eshop ] }",
            "{ adapter: http, address: /in }",
            "{ settings: { address: /in } }"
    })
    void aSenderOfSchemaVersionTwoRefusesWhatItDoesNotTake(String from) {
        assertFalse(flow(from, "{ type: xslt, stylesheet: a.xsl }").valid(), from);
    }

    @Test
    void aFlowOfSchemaVersionOneKeepsNamingEndpoints() {
        String v1 = V2.replace("schemaVersion: 2", "schemaVersion: 1");
        assertTrue(Schemas.FLOW.validate(Documents.yaml(v1.formatted("{ endpoint: in }", "{ type: to, endpoint: partner }"))).valid());
        assertFalse(Schemas.FLOW.validate(Documents.yaml(v1.formatted("{ adapter: http }", "{ type: to, endpoint: partner }"))).valid());
        assertFalse(Schemas.FLOW.validate(Documents.yaml(v1.formatted("{ endpoint: in }", "{ type: to, adapter: http, connection: acme/erp/rest }"))).valid());
    }

    @Test
    void aProcessOfAFlowOfSchemaVersionTwoSendsAsItsStepsDo() {
        String flow = V2.formatted("{ adapter: http }", "{ type: process, process: send }") + "processes:\n  send: { steps: [ %s ] }\n";
        assertTrue(Schemas.FLOW.validate(Documents.yaml(flow.formatted("{ type: to, adapter: http, connection: acme/erp/rest }"))).valid());
        assertFalse(Schemas.FLOW.validate(Documents.yaml(flow.formatted("{ type: to, endpoint: partner }"))).valid());
    }

    @Test
    void aSystemNamesItsPartnerItsNameAndItsConnections() {
        assertTrue(system("partner: acme\nname: erp\ndescription: Its ERP.\nconnections:\n  rest: { adapter: http, description: Its API. }\n").valid());
        assertTrue(system("name: eshop\n").valid());
        assertTrue(system("name: eshop\nconnections: {}\n").valid());
        assertTrue(system("partner: acme\nname: rail8\n").valid(), "a reserved word starts no key, it may end one");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name: rail8\n", "name: subscribers\n", "name: by-agreement\n", "partner: rail8\nname: erp\n",
            "name: Eshop\n", "name: e/shop\n", "partner: acme\n", "name: eshop\nconnections: { rest: { } }\n",
            "name: eshop\nconnections: { rest: { adapter: http, url: http://x } }\n", "name: eshop\nconnections: { Rest: { adapter: http } }\n",
            "name: eshop\nhost: x\n"
    })
    void aSystemFileRefusesWhatItDoesNotTake(String body) {
        assertFalse(system(body).valid(), body);
    }

    static Schemas.Report system(String body) {
        return Schemas.SYSTEM.validate(Documents.yaml("schemaVersion: 1\nkind: system\n" + body));
    }

    @Test
    void aTestOfSchemaVersionTwoKeysItsMocksByConnectionAndChecksTheSettings() {
        assertTrue(Schemas.TEST.validate(Documents.yaml("""
                schemaVersion: 2
                kind: test
                flow: sftp-invoice
                mocks:
                  billing/sftp: { files: fixtures/inbox/*.xml }
                  acme/erp/rest: { reply: fixtures/partner-ack.xml, delay: 20ms }
                  direct:/orders/partner: { reply: fixtures/partner-ack.xml }
                cases:
                  - name: invoice file
                    expect:
                      sent:
                        acme/erp/rest: { count: 1, settings: { path: /invoices }, xpath: /PartnerInvoice/@number, equals: "4711" }
                """)).valid());
        assertFalse(Schemas.TEST.validate(Documents.yaml("""
                schemaVersion: 2
                kind: test
                flow: f
                cases: [ { name: c, expect: { sent: { acme/erp/rest: { settings: { path: [ x ] } } } } } ]
                """)).valid());
    }
}
