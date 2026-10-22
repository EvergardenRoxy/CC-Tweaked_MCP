// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

/** Binary permission, with unavailable checks represented as failures rather than an allow fallback. */
final class NetworkAccess {
    interface Claims {
        boolean ready();
        boolean claimed();
        boolean sameDimension();
        boolean interact();
        boolean edit();
    }
    static void check(Claims claims) {
        if (claims == null) return; // Optional integration is genuinely absent.
        if (!claims.ready()) throw new NetworkControl.Failure(503, "FTB Chunks protection is not ready");
        if (!claims.claimed()) return; // Explicit policy: ignore no_wilderness on unclaimed land.
        if (!claims.sameDimension()) throw new NetworkControl.Failure(403, "claimed computers currently require the player to be in the same dimension");
        if (!claims.interact() || !claims.edit())
            throw new NetworkControl.Failure(403, "FTB Chunks requires effective block interaction AND block edit permission");
    }
    private NetworkAccess() {}
}
