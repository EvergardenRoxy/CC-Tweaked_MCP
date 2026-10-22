// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkAccessTest {
    record Claims(boolean ready, boolean claimed, boolean sameDimension, boolean interact, boolean edit) implements NetworkAccess.Claims {}
    @Test void absentOrUnclaimedAllowsWithoutConsultingWildernessOrDimension() {
        assertDoesNotThrow(() -> NetworkAccess.check(null));
        NetworkAccess.check(new NetworkAccess.Claims() {
            public boolean ready() { return true; }
            public boolean claimed() { return false; }
            public boolean sameDimension() { throw new AssertionError("unclaimed land has no dimension restriction"); }
            public boolean interact() { throw new AssertionError("must not apply FTB no_wilderness"); }
            public boolean edit() { throw new AssertionError("must not apply FTB no_wilderness"); }
        });
    }
    @Test void claimedRequiresBothEffectivePermissions() {
        for (boolean interact : new boolean[]{false, true}) for (boolean edit : new boolean[]{false, true}) {
            var claims = new Claims(true, true, true, interact, edit);
            if (interact && edit) assertDoesNotThrow(() -> NetworkAccess.check(claims));
            else assertEquals(403, assertThrows(NetworkControl.Failure.class, () -> NetworkAccess.check(claims)).status);
        }
    }
    @Test void initializationFailureCannotTurnIntoAbsentFallback() {
        assertEquals(503, assertThrows(NetworkControl.Failure.class,
            () -> NetworkAccess.check(new Claims(false, false, true, true, true))).status);
    }
    @Test void claimedCrossDimensionCannotQueryTheWrongWorld() {
        assertEquals(403, assertThrows(NetworkControl.Failure.class,
            () -> NetworkAccess.check(new Claims(true, true, false, true, true))).status);
    }
}
