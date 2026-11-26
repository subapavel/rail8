package dev.rail8.schemas;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo's systems (examples/demo/systems) and the quickstart's environment (deploy/quickstart/config): every connection a
 * demo flow of schemaVersion 2 names is defined by a system of the demo with the adapter of its use, and the quickstart's
 * environment holds a file for each connection and each system of the demo, at its path, valid against its schema, with the
 * same adapter, every secret a reference.
 */
class DemoSystemsTest {

    static final Path DEMO = DemoPackageTest.DEMO;
    static final Path CONFIG = Path.of("..", "..", "deploy", "quickstart", "config");

    @Test
    void theDemoDefinesItsThreeSystemsAndTheirConnections() throws IOException {
        assertEquals(Map.of("acme/erp/rest", "http", "globex/erp/rest", "http", "billing/sftp", "sftp"), definedConnections(Package.load(DEMO)));
    }

    @Test
    void everyConnectionADemoFlowNamesIsDefinedWithTheAdapterOfItsUse() throws IOException {
        Package pkg = Package.load(DEMO);
        Map<String, String> defined = definedConnections(pkg);
        Set<String> named = new TreeSet<>();
        for (Map.Entry<String, JsonObject> flow : pkg.flows().entrySet()) {
            JsonObject doc = flow.getValue();
            if (Package.schemaVersion(doc) != 2) continue;
            JsonObject from = doc.getJsonObject("from");
            if (from.getValue("connection") instanceof String key) {
                assertEquals(from.getString("adapter"), defined.get(key), flow.getKey() + " from: " + key);
                named.add(key);
            }
            for (Package.WrittenStep step : Package.writtenSteps(doc)) {
                if (step.entry().getValue("connection") instanceof String key) {
                    assertEquals(step.entry().getString("adapter"), defined.get(key), flow.getKey() + " " + step.label() + ": " + key);
                    named.add(key);
                }
            }
        }
        assertEquals(Set.of("acme/erp/rest", "billing/sftp"), named, "the connections sftp-invoice names");
    }

    @Test
    void theQuickstartsEnvironmentSetsEveryConnectionAndNamesEverySystemOfTheDemo() throws IOException {
        Package pkg = Package.load(DEMO);
        Map<String, String> connections = new TreeMap<>();
        Map<String, String> instanceNames = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CONFIG)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String path = CONFIG.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                JsonObject doc = Documents.read(file);
                Schemas.Report report = Schemas.forKind(doc.getString("kind")).validate(doc);
                assertTrue(report.valid(), path + ": " + report);
                String kind = doc.getString("kind").substring("environment.".length());
                assertEquals(kind + "/" + doc.getString("key") + ".yaml", path, "the import reads a file at the path of its kind and key");
                if (kind.equals("connection")) {
                    connections.put(doc.getString("key"), doc.getString("adapter"));
                    for (Map.Entry<String, Object> value : doc.getJsonObject("values")) {
                        if (value.getKey().equals("password") || value.getKey().equals("hostKey")) {
                            assertTrue(String.valueOf(value.getValue()).startsWith("secret://"), path + " " + value.getKey() + " is a reference");
                        }
                    }
                } else {
                    instanceNames.put(doc.getString("key"), doc.getString("instanceName"));
                }
            }
        }
        assertEquals(definedConnections(pkg), connections, "a file per connection of the demo's systems, with its adapter");
        assertEquals(Map.of("acme/erp", "ACME-ERP-DEV", "globex/erp", "GLOBEX-ERP-DEV", "billing", "BILLING-DEV"), instanceNames);
        assertEquals(pkg.systems().keySet(), instanceNames.keySet(), "an instance name for each system of the demo");
    }

    /** Each connection key the systems of {@code pkg} define, with its adapter. */
    private static Map<String, String> definedConnections(Package pkg) {
        Map<String, String> defined = new TreeMap<>();
        pkg.systems().forEach((system, doc) -> {
            JsonObject connections = doc.getJsonObject("connections", new JsonObject());
            for (String name : connections.fieldNames()) defined.put(system + "/" + name, connections.getJsonObject(name).getString("adapter"));
        });
        return defined;
    }
}
