// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import java.util.function.LongSupplier;

/**
 * Per-connection one-second window. The first request over the limit in a window is answered with 429 so the
 * client learns at once instead of waiting for its timeout; later ones are dropped, bounding the extra replies to
 * one small frame per second.
 */
final class NetworkRateLimit {
    enum Decision { ADMIT, LIMITED, DROP }
    static final int PER_SECOND = 20;
    private final LongSupplier clock;
    private long window;
    private int requests;

    NetworkRateLimit() { this(System::nanoTime); }
    NetworkRateLimit(LongSupplier clock) { this.clock = clock; window = clock.getAsLong(); }

    Decision next() {
        var now = clock.getAsLong();
        if (now - window >= 1_000_000_000L) { window = now; requests = 0; }
        if (requests == Integer.MAX_VALUE) return Decision.DROP;
        var count = ++requests;
        return count <= PER_SECOND ? Decision.ADMIT : count == PER_SECOND + 1 ? Decision.LIMITED : Decision.DROP;
    }
}
