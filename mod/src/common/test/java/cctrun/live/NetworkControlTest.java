// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class NetworkControlTest {
    private final AtomicLong clock = new AtomicLong();
    private final NetworkControl control = new NetworkControl(clock::get, 60, 300);
    private final NetworkControl.Owner player = new NetworkControl.Owner(UUID.randomUUID(), UUID.randomUUID());
    private final UUID instance = UUID.randomUUID();
    private UUID open() { return control.open(player, UUID.randomUUID()).id(); }
    private void seconds(long seconds) { clock.addAndGet(seconds * 1_000_000_000L); }

    @Test void differentMcpSessionsOfSamePlayerCannotShareAComputer() {
        var a = open(); var b = open();
        var lease = control.acquire(player, a, 7, instance);
        assertEquals(409, assertThrows(NetworkControl.Failure.class, () -> control.acquire(player, b, 7, instance)).status);
        assertEquals(lease, control.acquire(player, a, 7, instance));
        assertNotNull(control.acquire(player, b, 8, UUID.randomUUID()));
        assertThrows(NetworkControl.Failure.class, () -> control.validate(player, b, 7, instance, lease.id(), lease.epoch(), true));
    }

    @Test void sameNumericIdWithDifferentInstanceStillConflicts() {
        var a = open(); var b = open();
        control.acquire(player, a, 7, instance);
        assertThrows(NetworkControl.Failure.class, () -> control.acquire(player, b, 7, UUID.randomUUID()));
        assertThrows(NetworkControl.Failure.class, () -> control.acquire(player, a, 7, UUID.randomUUID()));
    }

    @Test void anotherPlayerCannotUseAStolenAgentOrLease() {
        var a = open(); var lease = control.acquire(player, a, 7, instance);
        var other = new NetworkControl.Owner(UUID.randomUUID(), UUID.randomUUID());
        assertThrows(NetworkControl.Failure.class, () -> control.heartbeat(other, a));
        assertThrows(NetworkControl.Failure.class, () -> control.validate(other, a, 7, instance, lease.id(), lease.epoch(), false));
        var b = control.open(other, UUID.randomUUID()).id();
        assertThrows(NetworkControl.Failure.class, () -> control.acquire(other, b, 7, instance));
    }

    @Test void releaseFencesOldRequestsAndCannotReleaseNewLease() {
        var a = open(); var old = control.acquire(player, a, 7, instance);
        control.release(player, a, 7, instance, old.id(), old.epoch());
        var next = control.acquire(player, a, 7, instance);
        assertTrue(next.epoch() > old.epoch());
        assertNotEquals(old.id(), next.id());
        assertThrows(NetworkControl.Failure.class, () -> control.validate(player, a, 7, instance, old.id(), old.epoch(), true));
        assertThrows(NetworkControl.Failure.class, () -> control.release(player, a, 7, instance, old.id(), old.epoch()));
        assertEquals(next, control.state(player, a, 7));
    }

    @Test void heartbeatOnlyRenewsItsOwnSessionAndDoesNotCountAsBusinessActivity() {
        var a = open(); var b = open();
        control.acquire(player, a, 7, instance);
        control.acquire(player, b, 8, UUID.randomUUID());
        for (int i = 0; i < 20; i++) { seconds(15); control.heartbeat(player, a); }
        assertThrows(NetworkControl.Failure.class, () -> control.require(player, b));
        assertNull(control.state(player, a, 7));
        assertNull(control.state(player, a, 8));
    }

    @Test void businessActivityKeepsAnActiveLeaseUntilHeartbeatStops() {
        var a = open(); var lease = control.acquire(player, a, 7, instance);
        for (int i = 0; i < 30; i++) {
            seconds(15); control.heartbeat(player, a);
            control.validate(player, a, 7, instance, lease.id(), lease.epoch(), true);
        }
        seconds(60);
        assertThrows(NetworkControl.Failure.class, () -> control.require(player, a));
        var b = open();
        assertNotNull(control.acquire(player, b, 7, instance));
    }

    @Test void disconnectAndNewConnectionEpochRejectOldSessions() {
        var a = open(); control.acquire(player, a, 7, instance);
        var reconnected = new NetworkControl.Owner(player.player(), UUID.randomUUID());
        assertThrows(NetworkControl.Failure.class, () -> control.require(reconnected, a));
        control.remove(player);
        assertThrows(NetworkControl.Failure.class, () -> control.require(player, a));
        var b = control.open(reconnected, UUID.randomUUID()).id();
        assertNotNull(control.acquire(reconnected, b, 7, instance));
    }

    @Test void closeRevocationAndWorldShutdownReleaseLocks() {
        var a = open(); control.acquire(player, a, 7, instance);
        control.invalidate(lease -> lease.computer() != 7);
        assertNull(control.state(player, a, 7));
        control.acquire(player, a, 7, instance);
        control.close(player, a);
        var b = open(); control.acquire(player, b, 7, instance);
        control.clear();
        assertThrows(NetworkControl.Failure.class, () -> control.require(player, b));
    }

    @Test void openRetryIsIdempotentAndSessionCountIsBounded() {
        var key = UUID.randomUUID(); var a = control.open(player, key);
        assertEquals(a, control.open(player, key));
        for (int i = 1; i < NetworkControl.MAX_SESSIONS; i++) open();
        assertEquals(429, assertThrows(NetworkControl.Failure.class, this::open).status);
        seconds(60);
        assertNotNull(open());
    }
}
