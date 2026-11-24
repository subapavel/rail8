package dev.rail8.runtime.deploy;

import dev.rail8.core.node.InMemoryMembership;
import dev.rail8.testkit.FakeClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The in-memory record store (dev, tests, and {@code Deployments}' old constructor) passes the store contract, its
 * listings checked against an in-memory membership whose leases end on a fake clock.
 */
class InMemoryDeploymentStoreTest extends DeploymentStoreContract {

    static final Duration LEASE = Duration.ofSeconds(30);

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-24T08:00:00Z"));
    final InMemoryMembership membership = new InMemoryMembership(clock);
    /** Each node joined, with its epoch: the ones {@link #expire} renews. */
    final Map<String, Long> joined = new TreeMap<>();

    @Override
    protected DeploymentStore emptyStore() {
        return new InMemoryDeploymentStore(membership);
    }

    @Override
    protected List<InMemoryDeploymentStore.Audited> audited() {
        return ((InMemoryDeploymentStore) store).audited();
    }

    @Override
    protected long join(String node) {
        long epoch = membership.join(node, LEASE).await();
        joined.put(node, epoch);
        return epoch;
    }

    @Override
    protected void expire(String node) {
        clock.advance(LEASE.plusSeconds(1));
        joined.forEach((other, epoch) -> {
            if (!other.equals(node)) membership.renew(other, epoch, LEASE).await();
        });
    }

    @Override
    protected void release(String node) {
        membership.release(List.of(node)).await();
    }
}
