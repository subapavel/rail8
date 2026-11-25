package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Where the secret store's rows live: the tables {@code secret_store}, {@code secrets}, {@code secret_uses}, the secret columns of
 * {@code nodes} and the audit; {@code secret_uses} is written only by {@link SecretStore#replaceUses}. {@link PgSecretRows} is Postgres; {@link MemorySecretRows} holds them in memory for tests. Only
 * {@link SecretStore} calls it, and it decides everything; this only reads and writes.
 */
interface SecretRows {

    /** The store's generation, raised by every change, and its stamp, a random number written by every change. */
    record Head(long generation, long stamp) { }

    /** A row of {@code secrets} without its sealed value: what a refresh compares and the list shows. */
    record Meta(String name, Secret.Kind kind, int format, byte[] keyId, byte[] nonce, int sealedBytes, Instant createdAt, Instant changedAt, String changedBy) { }

    /** A whole row of {@code secrets}. */
    record Stored(Meta meta, byte[] sealed) { }

    /** One read of every row: the head, every row's meta, and the sealed value of each row whose nonce was not held. */
    record Snapshot(Head head, List<Meta> rows, Map<String, byte[]> sealed) { }

    /**
     * A node's row of {@code nodes}: its epoch, the epoch of its last join to the store (null: never), the ids of the keys it
     * holds (null: none) and the names of its {@code RAIL8_SECRET_*} variables (empty: none written).
     */
    record NodeKeys(String node, long epoch, Long secretEpoch, byte[] key, byte[] previous, List<String> variables) {

        /** Whether its row was written by its current join: otherwise it counts as holding no key. */
        boolean joined() {
            return secretEpoch != null && secretEpoch == epoch;
        }

        /** Whether it holds {@code keyId}, as its current or previous key, by its current join. */
        boolean holds(byte[] keyId) {
            return joined() && (java.util.Arrays.equals(key, keyId) || java.util.Arrays.equals(previous, keyId));
        }

        /** Whether its current key is {@code keyId}, by its current join. */
        boolean names(byte[] keyId) {
            return joined() && java.util.Arrays.equals(key, keyId);
        }
    }

    /** A use of a secret: who ({@code flow}, {@code connection}, {@code credential} or {@code platform}, and its id) and the kinds it takes. */
    record Use(String name, String userKind, String userId, Set<Secret.Kind> kinds) { }

    /** What the list reads, in one read: every row's meta, every use, every node and the records whose uses are not known. */
    record Listing(List<Meta> rows, List<Use> uses, List<NodeKeys> nodes, List<String> unknown) { }

    /** The work of one write, inside its transaction, under the store's row lock. */
    interface Tx {
        /** The head as the lock read it. */
        Head head();

        /** Every node's row whose lease holds. */
        Future<List<NodeKeys>> liveNodes();

        /** Every row's meta. */
        Future<List<Meta>> rows();

        /** The whole row of {@code name}. */
        Future<Optional<Stored>> row(String name);

        /** Inserts the row, or replaces the row of its name. */
        Future<Void> upsert(Stored row);

        /** Deletes the row of {@code name}. */
        Future<Void> delete(String name);

        /** The uses of {@code name}. */
        Future<List<Use>> uses(String name);

        /** The packages whose records' uses are not known yet. */
        Future<List<String>> unknownRecords();

        /** Writes the secret columns of {@code node}'s row: its keys' ids (null: none), its variables' names and its join's epoch. */
        Future<Void> joined(String node, long epoch, byte[] key, byte[] previous, List<String> variables);

        /** Clears the previous key's id of {@code node}'s row. */
        Future<Void> clearPrevious(String node);

        /** Writes an audit row; its failure fails the write. */
        Future<Void> audit(Actor who, String action, String subject, JsonObject detail);

        /** Raises the generation, writes a new stamp and notifies every node: the head after. */
        Future<Head> changed();
    }

    /** The head now. */
    Future<Head> head();

    /** One read at repeatable read: the head, every row's meta, and the sealed value of each row whose nonce {@code held} does not hold. */
    Future<Snapshot> snapshot(Set<ByteBuffer> held);

    /**
     * One transaction: the transaction lock of each of {@code names} in ascending order of its key, then the store's row lock,
     * then {@code work}; committed when its future succeeds, rolled back when it fails.
     */
    <T> Future<T> write(Collection<String> names, Function<Tx, Future<T>> work);

    /** Every node's row whose lease holds. */
    Future<List<NodeKeys>> liveNodes();

    /** The rows, uses, live nodes and unknown records, in one read. */
    Future<Listing> listing();
}
