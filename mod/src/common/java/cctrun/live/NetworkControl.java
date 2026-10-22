// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Server-thread-only sessions and exclusive leases, scoped to one running world. No native CC hooks. */
final class NetworkControl {
    static final int MAX_SESSIONS = 8;
    record Owner(UUID player, UUID connection) {}
    record Agent(UUID id, Owner owner, UUID openKey, long expiresAt) {}
    record Lease(int computer, UUID instance, UUID agent, UUID id, long epoch, long expiresAt, long activityAt) {}
    static final class Failure extends RuntimeException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    private final Map<UUID, Agent> agents = new HashMap<>();
    private final Map<Integer, Lease> leases = new HashMap<>();
    private final LongSupplier clock;
    private final long ttl, idle;
    private long nextEpoch;

    NetworkControl(long seconds, long idleSeconds) { this(System::nanoTime, seconds, idleSeconds); }
    NetworkControl(LongSupplier clock, long seconds, long idleSeconds) {
        if (seconds < 1 || idleSeconds < seconds) throw new IllegalArgumentException("invalid lease timing");
        this.clock = clock;
        ttl = seconds * 1_000_000_000L;
        idle = idleSeconds * 1_000_000_000L;
    }

    Agent open(Owner owner, UUID openKey) {
        prune();
        for (var agent : agents.values()) if (agent.owner().equals(owner) && agent.openKey().equals(openKey)) return agent;
        if (agents.values().stream().filter(a -> a.owner().equals(owner)).count() >= MAX_SESSIONS)
            throw new Failure(429, "too many MCP sessions on this game connection");
        var agent = new Agent(UUID.randomUUID(), owner, openKey, clock.getAsLong() + ttl);
        agents.put(agent.id(), agent);
        return agent;
    }

    Agent require(Owner owner, UUID id) {
        prune();
        var agent = agents.get(id);
        if (agent == null || !agent.owner().equals(owner)) throw new Failure(403, "MCP session expired or belongs to another connection; reopen session");
        return agent;
    }

    void heartbeat(Owner owner, UUID id) {
        var agent = require(owner, id);
        long now = clock.getAsLong();
        agents.put(id, new Agent(id, owner, agent.openKey(), now + ttl));
        leases.replaceAll((key, value) -> value.agent().equals(id)
            ? new Lease(key, value.instance(), id, value.id(), value.epoch(), now + ttl, value.activityAt()) : value);
    }

    void close(Owner owner, UUID id) { require(owner, id); agents.remove(id); leases.values().removeIf(l -> l.agent().equals(id)); }
    void remove(Owner owner) {
        var ids = new HashSet<UUID>();
        agents.values().removeIf(a -> { if (!a.owner().equals(owner)) return false; ids.add(a.id()); return true; });
        leases.values().removeIf(l -> ids.contains(l.agent()));
    }

    Lease acquire(Owner owner, UUID id, int computer, UUID instance) {
        require(owner, id);
        var existing = leases.get(computer);
        if (existing != null) {
            if (existing.agent().equals(id) && existing.instance().equals(instance)) return existing;
            throw new Failure(409, "computer is held by another MCP session");
        }
        long now = clock.getAsLong();
        var lease = new Lease(computer, instance, id, UUID.randomUUID(), ++nextEpoch, now + ttl, now);
        leases.put(computer, lease);
        return lease;
    }

    /** True while some session holds a live lease on this computer ID. */
    boolean held(int computer) { prune(); return leases.containsKey(computer); }

    Lease state(Owner owner, UUID id, int computer) { require(owner, id); return leases.get(computer); }

    Lease validate(Owner owner, UUID id, int computer, UUID instance, UUID leaseId, long epoch, boolean activity) {
        require(owner, id);
        var lease = leases.get(computer);
        if (lease == null || !lease.agent().equals(id) || !lease.instance().equals(instance)
            || !lease.id().equals(leaseId) || lease.epoch() != epoch)
            throw new Failure(409, "valid exclusive lease required; acquire control before accessing this computer");
        if (activity) {
            lease = new Lease(computer, instance, id, lease.id(), epoch, lease.expiresAt(), clock.getAsLong());
            leases.put(computer, lease);
        }
        return lease;
    }

    void release(Owner owner, UUID id, int computer, UUID instance, UUID leaseId, long epoch) {
        validate(owner, id, computer, instance, leaseId, epoch, false);
        leases.remove(computer);
    }

    void invalidate(Predicate<Lease> keep) { prune(); leases.values().removeIf(l -> !keep.test(l)); }
    Agent agent(UUID id) { return agents.get(id); }
    long secondsLeft(Lease lease) { return Math.max(0, (lease.expiresAt() - clock.getAsLong()) / 1_000_000_000L); }
    void clear() { agents.clear(); leases.clear(); }
    void prune() {
        long now = clock.getAsLong();
        agents.values().removeIf(a -> now >= a.expiresAt());
        leases.values().removeIf(l -> !agents.containsKey(l.agent()) || now >= l.expiresAt() || now - l.activityAt() >= idle);
    }
}
