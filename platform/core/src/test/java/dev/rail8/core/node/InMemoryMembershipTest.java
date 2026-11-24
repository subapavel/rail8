package dev.rail8.core.node;

import dev.rail8.testkit.FakeClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a (D-101 (1)): the in-memory membership's rows beside {@code MembershipContract} — a renew of a node that never joined
 * renews nothing and leaves no row; a renew that is not renewed never names its own expired row among the expired peers; a
 * release takes only the peers asked for whose lease ran out, each once; a reconnect has nothing to drop and changes no row. The
 * leased nodes are those whose lease holds now, each with its current epoch.
 */
class InMemoryMembershipTest {

    static final Duration L = Duration.ofSeconds(30);

    final FakeClock clock = FakeClock.at(Instant.parse("2026-11-05T08:00:00Z"));
    final InMemoryMembership members = new InMemoryMembership(clock);

    @Test
    void theLeasedNodesAreTheLiveOnesWithTheirCurrentEpochUpToTheLeasesEnd() {
        members.join("b", L).await();
        members.join("a", L).await();
        long restarted = members.join("a", L).await();
        members.join("c", L).await();
        clock.advance(Duration.ofSeconds(10));
        members.join("d", L).await();
        clock.advance(L.minusSeconds(10));            // the end of b's, a's and c's leases: live up to and including it
        assertEquals(java.util.Map.of("a", restarted, "b", 1L, "c", 1L, "d", 1L), members.leased());
        assertEquals(List.of("a", "b", "c", "d"), List.copyOf(members.leased().keySet()), "sorted by id");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(java.util.Map.of("d", 1L), members.leased(), "a lease that ran out holds no more, released or not");
        members.release(List.of("b")).await();
        members.join("c", L).await();
        assertEquals(java.util.Map.of("c", 2L, "d", 1L), members.leased(), "a rejoin holds under its new epoch");
    }

    @Test
    void aNodeThatNeverJoinedIsNotRenewedAndLeavesNoRow() {
        members.join("b", L).await();
        Membership.Renewal r = members.renew("ghost", 1, L).await();
        assertFalse(r.renewed(), "no row: superseded");
        assertEquals(1, r.live(), "b alone is live");
        assertFalse(members.live("ghost"), "no row made by the renew");
        assertEquals(0, members.epoch("ghost"));
    }

    @Test
    void aRenewThatIsNotRenewedNeverNamesItsOwnRowExpired() {
        members.join("a", L).await();
        members.join("b", L).await();
        long newer = members.join("a", L).await();   // a restarted: its first incarnation is superseded
        clock.advance(L.plusSeconds(1));              // every row ran out, none released

        Membership.Renewal old = members.renew("a", newer - 1, L).await();
        assertFalse(old.renewed(), "fixture: the old incarnation's renew");
        assertEquals(List.of("b"), old.expired(), "its own expired row is not a peer to release");
        assertEquals(0, old.live());
    }

    @Test
    void aReleaseTakesOnlyThePeersAskedForWhoseLeaseRanOutEachOnce() {
        members.join("b", L).await();
        members.join("c", L).await();
        clock.advance(L.plusSeconds(1));
        members.join("d", L).await();                 // joined after the others ran out: live

        assertEquals(List.of("b"), members.release(List.of("b", "d")).await(), "c was not asked for, d is live");
        assertFalse(members.live("b"));
        assertTrue(members.live("c"), "an expired row nobody released is still its node's");
        assertTrue(members.live("d"));
        assertEquals(List.of(), members.release(List.of("b")).await(), "a released row is not released again");
    }

    @Test
    void aReconnectDropsNothingInMemoryTheRowsStayAndTheNextRenewRenews() {
        long epoch = members.join("a", L).await();
        members.reconnect();   // what NodeLease asks after an unanswered renew: no connection here, nothing to drop
        assertTrue(members.live("a"));
        assertTrue(members.renew("a", epoch, L).await().renewed(), "the next renew renews the same incarnation");
        assertEquals(epoch, members.epoch("a"));
    }
}
