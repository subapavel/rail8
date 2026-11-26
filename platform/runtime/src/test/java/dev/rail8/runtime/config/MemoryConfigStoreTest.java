package dev.rail8.runtime.config;

import dev.rail8.runtime.secret.MemorySecretStores;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.json.JsonObject;

import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;


/** The in-memory store passes the {@link ConfigStoreContract}: the nodes' stores share one {@link MemoryConfigRows.Db}. */
class MemoryConfigStoreTest extends ConfigStoreContract {

    final MemorySecretStores secretStores = new MemorySecretStores();
    final MemoryConfigRows.Db db = new MemoryConfigRows.Db();

    @Override
    SecretStore secretStore(String node, Map<String, String> env, FakeClock clock, FakeTimer timer) {
        synchronized (db) {
            ConfigStore.NodeReport before = db.nodes.get(node);
            db.lease(node, before == null ? 1 : before.epoch() + 1);
        }
        return secretStores.start(node, env, clock, timer);
    }

    @Override
    ConfigObjects configStore(SecretStore secrets, Function<String, Optional<ConfigObjects.Adapter>> adapters, Clock clock, RandomGenerator random,
                              Consumer<String> warn, ConfigFiles files) {
        return new ConfigObjects(new MemoryConfigRows(db, secrets), secrets, adapters, clock, random, warn, files);
    }

    @Override
    void deploySystem(String system, JsonObject definition) {
        synchronized (db) {
            db.systems.put(system, definition.copy());
        }
    }

    @Override
    void use(String flow, String step, String connection, String adapter, String direction, boolean address) {
        synchronized (db) {
            db.uses.add(new ConfigRows.Use(flow, step, connection, adapter, direction, address));
        }
    }

    @Override
    void auditFails(boolean fails) {
        synchronized (db) {
            db.auditFails = fails;
        }
    }

    @Override
    List<JsonObject> audit(String action) {
        synchronized (db) {
            return db.audit.stream().filter(a -> a.getString("action").equals(action))
                    .map(a -> new JsonObject().put("actor", a.getString("actor")).put("subject", a.getString("subject")).put("detail", a.getJsonObject("detail")))
                    .toList();
        }
    }

    private ConfigRows.ObjectRow row(Ref ref) {
        ConfigRows.ObjectRow r = db.objects.get(ref);
        if (r == null) throw new AssertionError("no row of " + ref);
        return r;
    }

    @Override
    void tamperBody(Ref ref, JsonObject body) {
        synchronized (db) {
            row(ref).body = body.copy();
        }
    }

    @Override
    void unbind(Ref ref) {
        synchronized (db) {
            row(ref).keyId = null;
            row(ref).tag = null;
        }
    }

    @Override
    void tamperDraft(Ref ref, JsonObject draft) {
        synchronized (db) {
            row(ref).draft = draft.copy();
        }
    }

    @Override
    void tamperHistoryBody(Ref ref, int version, JsonObject body) {
        synchronized (db) {
            db.history.stream().filter(h -> h.ref.equals(ref) && h.change.equals("activate") && h.version == version).findFirst()
                    .orElseThrow().body = body.copy();
        }
    }

    @Override
    JsonObject consentOf(Ref ref) {
        synchronized (db) {
            ConfigRows.ObjectRow r = row(ref);
            Base64.Encoder b64 = Base64.getEncoder();
            return new JsonObject().put("consent", b64.encodeToString(r.consent)).put("keyId", b64.encodeToString(r.consentKeyId))
                    .put("tag", r.consentTag == null ? null : b64.encodeToString(r.consentTag)).put("at", r.consentAt.toString()).put("by", r.consentBy);
        }
    }

    @Override
    void copyIds(Ref from, Ref to) {
        synchronized (db) {
            ConfigRows.ObjectRow source = row(from);
            ConfigRows.ObjectRow target = row(to);
            target.incarnation = source.incarnation;
            target.draftId = source.draftId;
            target.activation = source.activation;
        }
    }

    @Override
    void putConsent(Ref ref, JsonObject consent) {
        synchronized (db) {
            ConfigRows.ObjectRow r = row(ref);
            Base64.Decoder b64 = Base64.getDecoder();
            r.consent = b64.decode(consent.getString("consent"));
            r.consentKeyId = b64.decode(consent.getString("keyId"));
            r.consentTag = b64.decode(consent.getString("tag"));
            r.consentAt = java.time.Instant.parse(consent.getString("at"));
            r.consentBy = consent.getString("by");
        }
    }

    @Override
    void tamperSecret(String name) {
        secretStores.tamper(name);
    }

    @Override
    void deleteRow(Ref ref) {
        synchronized (db) {
            db.objects.remove(ref);
            db.history.removeIf(h -> h.ref.equals(ref));
        }
    }

    @Override
    Object snapshot() {
        synchronized (db) {
            return db.copy();
        }
    }

    @Override
    void setBack(Object snapshot) {
        synchronized (db) {
            MemoryConfigRows.Db back = ((MemoryConfigRows.Db) snapshot).copy();
            back.audit = db.audit;
            back.nodes = db.nodes;
            back.lapsed = db.lapsed;
            db.take(back);
        }
    }

    @Override
    int historyRows(Ref ref) {
        synchronized (db) {
            return (int) db.history.stream().filter(h -> h.ref.equals(ref)).count();
        }
    }

    @Override
    long epoch(String node) {
        synchronized (db) {
            return db.nodes.get(node).epoch();
        }
    }

    @Override
    void lapse(String node) {
        synchronized (db) {
            db.lapsed.add(node);
        }
    }
}
