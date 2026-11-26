package dev.rail8.runtime.config;

import dev.rail8.api.Secret;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlConnection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * Where the configuration's rows live, in Postgres or in memory: the tables as they are, with no rule of the store. The
 * store ({@link ConfigObjects}) decides; these rows only read and write, in the transaction they are given.
 */
interface ConfigRows {

    /** {@code work} in a transaction of its own that takes no lock; its result. */
    <T> T read(Function<Tx, T> work);

    /**
     * {@code work} in a transaction of its own, committed when it returns and rolled back when it throws; its result. A lock
     * not had within the store's wait is 503 {@code busy}.
     */
    <T> T write(Function<Tx, T> work);

    /**
     * {@code work} in the caller's transaction {@code tx}, which the caller commits: the secret store's tick binding again. In
     * Postgres it runs on a virtual thread of its own, since the tick runs on an event loop; in memory on the caller's thread.
     */
    <T> io.vertx.core.Future<T> within(SqlConnection tx, Function<Tx, T> work);

    /** The reports of what each node holds, by node: kept in memory only until the nodes' columns exist. */
    void holds(String node, long epoch, long generation, long stamp, ConfigStore.Refused refused);

    /** One transaction's reads and writes. */
    interface Tx {

        /** The transaction's connection, for the secret store's calls; null in memory. */
        SqlConnection connection();

        /** The one row of {@code config_store}; {@code for update} when {@code lock}. */
        Head head(boolean lock);

        /** Writes the generation, the stamp and the switch of mocks. */
        void head(Head head);

        /** Tells every node that the generation is now {@code generation}, at the commit. */
        void notifyGeneration(long generation);

        /** The object's row; {@code for update} when {@code lock}. */
        Optional<ObjectRow> row(Ref ref, boolean lock);

        /** Inserts or replaces the object's row. */
        void put(ObjectRow row);

        /** Every row of {@code kind} whose key starts with {@code prefix} and sorts after {@code after}, by key, at most {@code limit}. */
        List<ObjectRow> rows(Ref.Kind kind, String prefix, String after, int limit);

        /** Every row with a body in force. */
        List<ObjectRow> inForce();

        /** The rows of {@code kind}, drafts and removed objects included. */
        long count(Ref.Kind kind);

        /** The bytes of every body in force but the one of {@code except}, each as its JSON text. */
        long bodyBytes(Ref except);

        /** Whether {@code id} is the incarnation, the activation or the draft id of any row, or an id of the history. */
        boolean used(long id);

        /** Adds a row to the history; answers its id. */
        long add(History row);

        /** The object's activation rows of the history, newest first. */
        List<History> activations(Ref ref);

        /** The activation row of the history that made version {@code version} of the object. */
        Optional<History> version(Ref ref, int version);

        /** The object's history rows before the id {@code before}, newest first, at most {@code limit}. */
        List<History> history(Ref ref, OptionalLong before, int limit);

        /** Deletes the object's oldest history rows beyond {@code max}, never one of its {@code keep} newest activations. */
        void trim(Ref ref, int max, int keep);

        /** The adapter the deployed definition of the connection's system names for it, when one does. */
        Optional<String> deployedAdapter(String connectionKey);

        /** Whether a deployed system definition names the system {@code systemKey}. */
        boolean deployed(String systemKey);

        /** The deployed flows' uses of the connection {@code connectionKey}. */
        List<Use> uses(String connectionKey);

        /** The deployed flows' uses of every connection of the system {@code systemKey}. */
        List<Use> usesOfSystem(String systemKey);

        /**
         * Replaces the connection's uses of secrets with {@code uses} through the secret store's one method of uses, taking
         * the names' locks; answers its problems, empty when it wrote them.
         */
        List<String> replaceUses(Ref ref, Map<String, Set<Secret.Kind>> uses);

        /** The kind of each name of {@code names} set in the secret store; a name not set is absent. */
        Map<String, Secret.Kind> kinds(Set<String> names);

        /**
         * Writes an audit row in this transaction.
         *
         * @throws ConfigRefusal 503 {@code audit-unavailable} when it cannot be written
         */
        void audit(dev.rail8.core.audit.Actor who, Instant at, String action, String subject, JsonObject detail);

        /** The secret store's audit rows {@code secret.overwrite} and {@code secret.delete} of the names, newest first. */
        List<SecretAct> secretActs(Set<String> names, Optional<Instant> from, Optional<Instant> until);

        /** Up to {@code max} rows bound, or with a consent bound, under another key than {@code keyId}, {@code for update skip locked}. */
        List<ObjectRow> boundElsewhere(byte[] keyId, int max);

        /** Up to {@code max} activation rows of the history bound under another key than {@code keyId}, {@code for update skip locked}. */
        List<History> historyBoundElsewhere(byte[] keyId, int max);

        /** Writes the binding of the history's row {@code id}. */
        void bindHistory(long id, byte[] keyId, byte[] tag);

        /** The rows, consents and history rows whose tag is bound under {@code keyId}. */
        long countUnder(byte[] keyId);
    }

    /** The one row of {@code config_store}. */
    record Head(long generation, long stamp, boolean mocksAllowed) { }

    /** A deployed flow's use of a connection, as {@code deployment_connection_uses} holds it. */
    record Use(String flow, String step, String connection, String adapter, String direction, boolean address) { }

    /** One act of the secret store on a name: {@code secret.overwrite} or {@code secret.delete}, by whom and when. */
    record SecretAct(Instant at, String actor, String action, String name) { }

    /** One row of {@code config_objects}, as a mutable copy a write changes and puts back. */
    final class ObjectRow {
        Ref ref;
        long incarnation;
        long revision;
        int version;
        Long activation;
        Integer format;
        JsonObject body;
        byte[] keyId;
        byte[] tag;
        Instant activatedAt;
        String activatedBy;
        Long draftId;
        Integer draftFormat;
        JsonObject draft;
        Instant draftAt;
        String draftBy;
        byte[] consent;
        byte[] consentKeyId;
        byte[] consentTag;
        Instant consentAt;
        String consentBy;
        int run;
        Instant stoppedAt;
        String stoppedBy;

        /** A copy, with copies of its bodies and bytes. */
        ObjectRow copy() {
            ObjectRow r = new ObjectRow();
            r.ref = ref;
            r.incarnation = incarnation;
            r.revision = revision;
            r.version = version;
            r.activation = activation;
            r.format = format;
            r.body = body == null ? null : body.copy();
            r.keyId = keyId == null ? null : keyId.clone();
            r.tag = tag == null ? null : tag.clone();
            r.activatedAt = activatedAt;
            r.activatedBy = activatedBy;
            r.draftId = draftId;
            r.draftFormat = draftFormat;
            r.draft = draft == null ? null : draft.copy();
            r.draftAt = draftAt;
            r.draftBy = draftBy;
            r.consent = consent == null ? null : consent.clone();
            r.consentKeyId = consentKeyId == null ? null : consentKeyId.clone();
            r.consentTag = consentTag == null ? null : consentTag.clone();
            r.consentAt = consentAt;
            r.consentBy = consentBy;
            r.run = run;
            r.stoppedAt = stoppedAt;
            r.stoppedBy = stoppedBy;
            return r;
        }

        /** Drops the draft and the consent to it. */
        void dropDraft() {
            draftId = null;
            draftFormat = null;
            draft = null;
            draftAt = null;
            draftBy = null;
            dropConsent();
        }

        /** Drops the consent. */
        void dropConsent() {
            consent = null;
            consentKeyId = null;
            consentTag = null;
            consentAt = null;
            consentBy = null;
        }

        @Override
        public String toString() {
            return "ObjectRow[" + ref + ", revision " + revision + ", version " + version + "]";
        }
    }

    /** One row of {@code config_history}. */
    final class History {
        long id;
        Ref ref;
        Instant at;
        String actor;
        Long userId;
        String via;
        String credential;
        String change;
        long revision;
        Long generation;
        Integer version;
        Integer format;
        JsonObject body;
        Long incarnation;
        Long activation;
        byte[] keyId;
        byte[] tag;
        JsonArray diff = new JsonArray();
        String note;

        @Override
        public String toString() {
            return "History[" + id + ", " + ref + ", " + change + "]";
        }
    }
}
