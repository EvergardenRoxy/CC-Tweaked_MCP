// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRateLimitTest {
    @Test void firstRequestOverTheLimitIsAnsweredOnceThenDroppedUntilTheNextWindow() {
        var now = new long[]{0};
        var limit = new NetworkRateLimit(() -> now[0]);
        for (int i = 0; i < NetworkRateLimit.PER_SECOND; i++) assertEquals(NetworkRateLimit.Decision.ADMIT, limit.next());
        assertEquals(NetworkRateLimit.Decision.LIMITED, limit.next());
        assertEquals(NetworkRateLimit.Decision.DROP, limit.next());
        assertEquals(NetworkRateLimit.Decision.DROP, limit.next());
        now[0] = 1_000_000_000L;
        assertEquals(NetworkRateLimit.Decision.ADMIT, limit.next());
    }
}
