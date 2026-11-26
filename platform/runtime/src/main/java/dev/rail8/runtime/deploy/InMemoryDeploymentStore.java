package dev.rail8.runtime.deploy;

import dev.rail8.core.audit.Actor;
import dev.rail8.core.node.InMemoryMembership;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link DeploymentStore} of a node without Postgres records (dev, tests): lost with the process. The {@code Deployments} that
 * share one such store are one pool: they share its records, its marks, its generation and its lock. It sees no work rows, so
 * {@link #endMarks} ends every mark. What each node runs is checked against an {@link InMemoryMembership} when the store has one,
 * as the Postgres store checks it against {@code nodes}; without one, every write of a listing is taken, and every node that
 * wrote one is listed with its last.
 */
public final class InMemoryDeploymentStore implements DeploymentStore {

    /**
     * One audit row of a deploy or an undeploy, as the store was given it.
     *
     * @param actor who acted, by name
     * @param action {@code deploy} or {@code undeploy}
     * @param subject {@code repository/<repository>} for a deploy, {@code package/<name>} for an undeploy
     * @param detail the commit, its author and the outcome of a deploy; the version, the repository and the commit of an undeploy
     */
    public record Audited(String actor, String action, String subject, JsonObject detail) {}

    /** By name: sorted as {@link String#compareTo} sorts. Guarded by {@code this}, with the generation and the lock's holder. */
    private final Map<String, DeploymentRecord> rows = new TreeMap<>();
    private final Set<String> marked = new TreeSet<>();
    private long generation;
    private Held holder;
    private final List<Audited> audited = new CopyOnWriteArrayList<>();
    /** What each node wrote it runs, by node id; guarded by {@code this}. */
    private final Map<String, Listing> listings = new TreeMap<>();
    /** The systems the records define, by key; guarded by {@code this}. */
    private final Map<String, DeployedSystem> systems = new TreeMap<>();
    /** The connections the deployed and retiring flows reach; guarded by {@code this}. */
    private final List<ConnectionUse> uses = new ArrayList<>();
    /** The generation of the configuration each package's last deploy checked, by name; guarded by {@code this}. */
    private final Map<String, Long> checked = new TreeMap<>();
    /** The leases the listings are checked against; null: none. */
    private final InMemoryMembership membership;

    /** One node's rows: the epoch it wrote them under, and its packages. */
    private record Listing(long epoch, List<NodePackage> packages) {}

    /** A store whose listings are not checked against any membership. */
    public InMemoryDeploymentStore() {
        this.membership = null;
    }

    /** A store whose listings are checked against {@code membership}'s leases, as the Postgres store checks them against {@code nodes}. */
    public InMemoryDeploymentStore(InMemoryMembership membership) {
        this.membership = Objects.requireNonNull(membership, "membership");
    }

    @Override
    public synchronized Future<Snapshot> read() {
        return Future.succeededFuture(snapshot());
    }

    private Snapshot snapshot() {
        return new Snapshot(generation, new ArrayList<>(rows.values()), new ArrayList<>(marked));
    }

    @Override
    public synchronized Future<Boolean> writeListing(String node, long epoch, List<NodePackage> packages) {
        if (membership != null && !(membership.live(node) && membership.epoch(node) == epoch)) return Future.succeededFuture(false);
        listings.put(node, new Listing(epoch, List.copyOf(packages)));
        if (membership != null) listings.entrySet().removeIf(e -> !membership.live(e.getKey()) || membership.epoch(e.getKey()) != e.getValue().epoch());
        return Future.succeededFuture(true);
    }

    @Override
    public synchronized Future<Listings> listings() {
        SortedMap<String, List<NodePackage>> nodes = new TreeMap<>();
        if (membership == null) {
            listings.forEach((node, l) -> nodes.put(node, sorted(l.packages())));
        } else {
            membership.leased().forEach((node, epoch) -> {
                Listing l = listings.get(node);
                nodes.put(node, l == null || l.epoch() != epoch ? List.of() : sorted(l.packages()));
            });
        }
        long read = rows.size() + nodes.values().stream().mapToLong(p -> Math.max(1, p.size())).sum();
        if (read > LISTINGS_MAX) {
            return Future.failedFuture(new IllegalStateException("the pool holds more than " + LISTINGS_MAX
                    + " records and rows of what each node runs together, the most one read takes"));
        }
        return Future.succeededFuture(new Listings(new ArrayList<>(rows.values()), nodes));
    }

    /** {@code packages} sorted by name as {@link String#compareTo} sorts. */
    private static List<NodePackage> sorted(List<NodePackage> packages) {
        return packages.stream().sorted(Comparator.comparing(NodePackage::name)).toList();
    }

    @Override
    public synchronized Future<Optional<PoolLock>> lock() {
        if (holder != null) return Future.succeededFuture(Optional.empty());
        holder = new Held(snapshot());
        return Future.succeededFuture(Optional.of(holder));
    }

    @Override
    public synchronized Future<Void> endMarks() {
        uses.removeIf(u -> marked.contains(u.deployment()));
        marked.clear();
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<List<DeployedSystem>> systems() {
        return Future.succeededFuture(List.copyOf(systems.values()));
    }

    @Override
    public synchronized Future<List<ConnectionUse>> connectionUses() {
        return Future.succeededFuture(List.copyOf(uses));
    }

    /** The generation of the configuration the last deploy of {@code name} checked; empty when none was written. */
    public synchronized Optional<Long> configGeneration(String name) {
        return Optional.ofNullable(checked.get(name));
    }

    @Override
    public Future<Void> audit(Actor by, String repository, JsonObject detail) {
        audited.add(new Audited(by.name(), "deploy", "repository/" + repository, detail.copy()));
        return Future.succeededFuture();
    }

    /** The audit rows written so far, oldest first. */
    public List<Audited> audited() {
        return List.copyOf(audited);
    }

    /** The lock as one holder holds it: its writes fail once it is closed. */
    private final class Held implements PoolLock {
        private final Snapshot pool;
        private long read;

        Held(Snapshot pool) {
            this.pool = pool;
            this.read = pool.generation();
        }

        @Override
        public Snapshot snapshot() {
            return pool;
        }

        @Override
        public Future<List<DeployedSystem>> systems() {
            return InMemoryDeploymentStore.this.systems();
        }

        @Override
        public Future<List<ConnectionUse>> connectionUses() {
            return InMemoryDeploymentStore.this.connectionUses();
        }

        @Override
        public Future<Long> save(DeploymentRecord record, Actor by, JsonObject detail) {
            return write(record.name(), record, null, new Audited(by.name(), "deploy", "repository/" + record.repository(), detail.copy()));
        }

        @Override
        public Future<Long> save(DeploymentRecord record, Map<String, Map<String, Set<dev.rail8.api.Secret.Kind>>> secretUses, Configuration configuration,
                                 Actor by, JsonObject detail) {
            return write(record.name(), record, configuration, new Audited(by.name(), "deploy", "repository/" + record.repository(), detail.copy()));
        }

        @Override
        public Future<Long> delete(String name, Actor by, JsonObject detail) {
            return write(name, null, null, new Audited(by.name(), "undeploy", "package/" + name, detail.copy()));
        }

        /**
         * Writes {@code record} as {@code name}'s row, or deletes the row when it is null, with its audit row {@code row}; with
         * {@code configuration}, the package's systems and uses replaced with its own. A delete removes the package's systems and
         * uses; a save without a configuration keeps them. The uses of a flow marked here stay under its qualified id.
         */
        private Future<Long> write(String name, DeploymentRecord record, Configuration configuration, Audited row) {
            synchronized (InMemoryDeploymentStore.this) {
                if (holder != this) return Future.failedFuture(new IllegalStateException("the pool's lock is no longer held"));
                if (generation != read) return Future.failedFuture(new IllegalStateException("the pool's generation moved from " + read + " to " + generation));
                for (DeployedSystem sys : configuration == null ? List.<DeployedSystem>of() : configuration.systems()) {
                    DeployedSystem other = systems.get(sys.key());
                    if (other != null && !other.deployment().equals(name)) {
                        return Future.failedFuture(new IllegalStateException("system '" + sys.key() + "' is defined by the package '" + other.deployment() + "'"));
                    }
                }
                DeploymentRecord replaced = record == null ? rows.remove(name) : rows.put(name, record);
                List<String> runs = record == null ? List.of() : record.atLeastOnce();
                List<String> retiring = replaced == null ? List.of() : replaced.atLeastOnce().stream().filter(flow -> !runs.contains(flow)).toList();
                if (replaced != null) marked.addAll(retiring);
                runs.forEach(marked::remove);
                if (record == null || configuration != null) {
                    List<ConnectionUse> kept = new ArrayList<>();
                    for (ConnectionUse u : uses) {
                        if (!u.deployment().equals(name)) kept.add(u);
                        else if (retiring.contains(u.flow())) {
                            kept.add(new ConnectionUse(u.flow(), u.flow(), u.step(), u.connection(), u.adapter(), u.direction(), u.address(), u.polled()));
                        }
                    }
                    uses.clear();
                    uses.addAll(kept);
                    systems.values().removeIf(sys -> sys.deployment().equals(name));
                    checked.remove(name);
                }
                if (configuration != null) {
                    uses.removeIf(u -> runs.contains(u.deployment()));   // a flow run again: its uses are its package's from now on
                    configuration.systems().forEach(sys -> systems.put(sys.key(), sys));
                    uses.addAll(configuration.uses());
                    checked.put(name, configuration.generation());
                }
                audited.add(row);
                read = ++generation;
                return Future.succeededFuture(generation);
            }
        }

        @Override
        public Future<Void> close() {
            synchronized (InMemoryDeploymentStore.this) {
                if (holder == this) holder = null;
            }
            return Future.succeededFuture();
        }
    }
}
