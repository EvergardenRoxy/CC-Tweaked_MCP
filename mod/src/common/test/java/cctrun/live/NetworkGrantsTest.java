// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class NetworkGrantsTest {
    @Test void grantsArePrivateToOneConnectionAndRevocationIsImmediate() {
        var first = new NetworkGrants<Object>();
        var second = new NetworkGrants<Object>();
        var computer = new Object();
        first.allow(UUID.randomUUID(), 4, computer);
        assertSame(computer, first.require(4, value -> true).target());
        assertThrows(IllegalArgumentException.class, () -> second.require(4, value -> true));
        first.revoke(4);
        assertThrows(IllegalArgumentException.class, () -> first.require(4, value -> true));
    }

    @Test void readsDoNotRenewAndExactExpiryRejectsAccess() {
        var time = new AtomicLong();
        var grants = new NetworkGrants<Object>(time::get);
        grants.allow(UUID.randomUUID(), 4, new Object());
        time.set(NetworkGrants.LEASE_SECONDS * 1_000_000_000L - 1);
        assertNotNull(grants.require(4, value -> true));
        time.incrementAndGet();
        assertThrows(IllegalArgumentException.class, () -> grants.require(4, value -> true));
    }

    @Test void unloadedComputerAndReusedIdCannotInheritTheGrant() {
        var grants = new NetworkGrants<Object>();
        var oldComputer = new Object();
        var replacement = new Object();
        grants.allow(UUID.randomUUID(), 4, oldComputer);
        assertThrows(IllegalArgumentException.class, () -> grants.require(4, value -> value == replacement));
        // Removal is permanent even if the previous object becomes available again.
        assertThrows(IllegalArgumentException.class, () -> grants.require(4, value -> true));
        assertTrue(grants.live(value -> true).isEmpty());
    }

    @Test void duplicateIdsAreRejectedInsteadOfChoosingAnArbitraryComputer() {
        var grants = new NetworkGrants<Object>();
        grants.allow(UUID.randomUUID(), 4, new Object());
        grants.allow(UUID.randomUUID(), 4, new Object());
        assertEquals(2, grants.live(value -> true).size());
        assertThrows(IllegalArgumentException.class, () -> grants.require(4, value -> true));
        grants.revoke(4);
        assertTrue(grants.live(value -> true).isEmpty());
    }

    @Test void grantCountIsBoundedAndConnectionResetDropsEverything() {
        var grants = new NetworkGrants<Object>();
        for (int i = 0; i < NetworkGrants.MAX_GRANTS; i++) grants.allow(UUID.randomUUID(), i, new Object());
        assertThrows(IllegalArgumentException.class, () -> grants.allow(UUID.randomUUID(), 99, new Object()));
        grants.clear();
        assertTrue(grants.live(value -> true).isEmpty());
    }
}
