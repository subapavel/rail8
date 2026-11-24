package dev.rail8.runtime.deploy;

import dev.rail8.core.audit.Actor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Where the pool's {@link DeploymentRecord}s, its generation and the audit rows of the deploys live: Postgres on a node
 * ({@link PgDeploymentStore}), memory in dev and tests ({@link InMemoryDeploymentStore}). Records are written only under the
 * pool's lock ({@link #lock}), one holder at a time across the pool, and each write raises the pool's generation by one. Beside
 * the records, each node writes what it runs itself ({@link #writeListing}), so that any node can answer for the whole pool
 * ({@link #listings}); nothing reads those rows to decide anything.
 *
 * @since 0.1
 */
public interface DeploymentStore {

    /**
     * The pool as one read saw it.
     *
     * @param generation how many deploys and undeploys the pool committed
     * @param records every record, sorted by name as {@link String#compareTo} sorts ({@code Deployments} lists its packages so),
     *                whatever the database's collation
     * @param marked the qualified ids of the flows the pool retires, sorted: each was run at-least-once by a record that a later write
     *               replaced with one that does not run it so, or deleted; the work queue still takes messages for it, and its rows are
     *               drained, until {@link #endMarks} ends its mark
     */
    record Snapshot(long generation, List<DeploymentRecord> records, List<String> marked) {
        public Snapshot {
            records = List.copyOf(records);
            marked = List.copyOf(marked);
        }

        /** A pool that retires no flow. */
        public Snapshot(long generation, List<DeploymentRecord> records) {
            this(generation, records, List.of());
        }
    }

    /**
     * One package as the node that runs it lists it: its row of what each node runs.
     *
     * @param name the package's name
     * @param version its manifest's version; a {@link DeployedPackage.State#FAILED} package's is its record's
     * @param repository the repository it was deployed from; a FAILED package's is its record's
     * @param commit the commit it was deployed from, 40 lower-case hex digits; a FAILED package's is its record's
     * @param deployedAt when the deploy that the node runs wrote it, to the microsecond
     * @param state whether the node runs it
     * @param behind why the node's last install of the package's record was refused, one line each, at most
     *               {@link #BEHIND_LINES} lines of at most {@link #BEHIND_LINE_MAX} characters; empty when it was not
     */
    record NodePackage(String name, String version, String repository, String commit, Instant deployedAt, DeployedPackage.State state,
                       List<String> behind) {
        public NodePackage {
            behind = List.copyOf(behind);
        }
    }

    /**
     * What the pool runs, read in one read-only transaction.
     *
     * @param records every record of the pool, sorted by name as {@link String#compareTo} sorts
     * @param nodes each node whose lease holds, by id, sorted as {@link String#compareTo} sorts: its rows of its current epoch,
     *              sorted by name the same way; empty for a node that wrote none
     */
    record Listings(List<DeploymentRecord> records, SortedMap<String, List<NodePackage>> nodes) {
        public Listings {
            records = List.copyOf(records);
            TreeMap<String, List<NodePackage>> copy = new TreeMap<>();
            nodes.forEach((node, packages) -> copy.put(node, List.copyOf(packages)));
            nodes = Collections.unmodifiableSortedMap(copy);
        }
    }

    /** The most lines of why a node's package is behind ({@link NodePackage#behind}). */
    int BEHIND_LINES = 20;

    /** The most characters of one line of why a node's package is behind ({@link NodePackage#behind}). */
    int BEHIND_LINE_MAX = 500;

    /**
     * The most rows {@link #listings} reads, records and nodes' packages together; a node that wrote none counts as one. A pool
     * that holds more is answered with a failure that names the limit, never with a part of it.
     */
    int LISTINGS_MAX = 10_000;

    /** The pool's generation, every record and every mark, read in one snapshot. */
    Future<Snapshot> read();

    /**
     * Replaces the rows of what {@code node} runs with {@code packages}, whole, in one transaction, and only while the node's
     * membership row carries {@code epoch} with a lease that was not released: answers false and writes nothing otherwise. The same
     * transaction deletes the rows of every node whose epoch moved on or whose lease was released. It takes no pool lock and raises
     * no generation.
     *
     * @param node the node that writes, about itself
     * @param epoch its incarnation, as its membership joined it
     * @param packages each package it lists from git
     */
    Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages);

    /**
     * Every record, and the rows of every node whose lease holds, of its current epoch, read in one read-only transaction; it fails,
     * naming {@link #LISTINGS_MAX}, when they are more than that.
     */
    Future<Listings> listings();

    /**
     * Ends the mark of each retired flow whose work rows are gone, each in a transaction of its own and only while no write of a message for
     * it is between its admission and its commit (the flow's admission lock, taken without waiting): a mark it cannot lock stays.
     */
    Future<Void> endMarks();

    /**
     * Takes the pool's lock without waiting: empty when another holder has it. The lock is held until {@link PoolLock#close}, or
     * until the store loses it (on Postgres: its connection closed); then every write through it fails.
     */
    Future<Optional<PoolLock>> lock();

    /**
     * Appends the audit row of one deploy that wrote no record, a refused or a failed one: when (the store's clock), who deployed
     * ({@code by}: the actor, the user, how they proved who they are and the id of their credential), the action {@code deploy},
     * the subject {@code repository/<repository>} and {@code detail}, which names the commit and the outcome. Never a body or a
     * credential's secret.
     */
    Future<Void> audit(Actor by, String repository, JsonObject detail);

    /**
     * The pool's lock, held: the one way to write records. Each write is one transaction that changes one record, raises the
     * generation by one and appends the write's audit row, and only while the generation is the one this lock read or last wrote:
     * otherwise it fails and writes nothing. A row that cannot be appended fails the write, and nothing of it is written.
     */
    interface PoolLock {

        /** The pool as read once the lock was taken. */
        Snapshot snapshot();

        /**
         * Writes {@code record} as its package's one row (a row the package had is replaced whole); answers the new generation. In
         * the same transaction each flow the replaced row runs at-least-once and {@code record} does not is marked, with a copy of
         * that row, the mark of each flow {@code record} runs at-least-once ends, and the deploy's audit row is appended: as
         * {@link #audit} appends one, with the subject {@code repository/<record's repository>}.
         *
         * @param by who deployed
         * @param detail the commit, its author and the outcome
         */
        Future<Long> save(DeploymentRecord record, Actor by, JsonObject detail);

        /**
         * Deletes the row of the package {@code name}, and no other, marking each flow it ran at-least-once with a copy of it, in one
         * transaction with the undeploy's audit row: who undeployed ({@code by}, as {@link #audit} records it), the action
         * {@code undeploy}, the subject {@code package/<name>} and {@code detail}; answers the new generation, raised whether a row
         * was there or not.
         *
         * @param by who undeployed
         * @param detail the version, the repository and the commit the package ran
         */
        Future<Long> delete(String name, Actor by, JsonObject detail);

        /** Frees the lock; never fails. */
        Future<Void> close();
    }
}
