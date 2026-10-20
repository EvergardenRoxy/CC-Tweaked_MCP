// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** A single connection's explicit, expiring grants. Never resolves a grant by a reused numeric ID alone. */
final class NetworkGrants<T> {
    static final int MAX_GRANTS = 32;
    static final long LEASE_SECONDS = 1800;
    record Grant<T>(UUID instance, int id, T target, long expiresAt) {}
    private final Map<UUID, Grant<T>> grants = new LinkedHashMap<>();
    private final LongSupplier clock;

    NetworkGrants() { this(System::nanoTime); }
    NetworkGrants(LongSupplier clock) { this.clock = clock; }

    void allow(UUID instance, int id, T target) {
        prune(value -> true);
        if (!grants.containsKey(instance) && grants.size() >= MAX_GRANTS) throw new IllegalArgumentException("too many computer grants");
        grants.put(instance, new Grant<>(instance, id, target, clock.getAsLong() + LEASE_SECONDS * 1_000_000_000L));
    }

    List<Grant<T>> live(Predicate<T> available) {
        prune(available);
        return List.copyOf(grants.values());
    }

    Grant<T> require(int id, Predicate<T> available) {
        var matches = live(available).stream().filter(grant -> grant.id() == id).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("computer is not authorized for this connection; allow viewing in its game screen");
        if (matches.size() != 1) throw new IllegalArgumentException("ambiguous computer ID; revoke duplicate grants before reading");
        return matches.get(0);
    }

    long secondsLeft(Grant<T> grant) { return Math.max(0, (grant.expiresAt() - clock.getAsLong()) / 1_000_000_000L); }
    void revoke(int id) { grants.values().removeIf(grant -> grant.id() == id); }
    void clear() { grants.clear(); }
    private void prune(Predicate<T> available) {
        var now = clock.getAsLong();
        grants.values().removeIf(grant -> now >= grant.expiresAt() || !available.test(grant.target()));
    }
}
