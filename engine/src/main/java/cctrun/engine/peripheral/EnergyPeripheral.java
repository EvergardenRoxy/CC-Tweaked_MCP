package cctrun.engine.peripheral;

import cctrun.engine.Trace;

import java.util.Set;

/** A block exposing CC: Tweaked's generic {@code energy_storage} peripheral. Main-thread, as in the game. */
public final class EnergyPeripheral extends DispatchPeripheral {
    /** Stored energy and capacity; the fixture timeline can change {@code energy} during a run. */
    public static final class Storage {
        volatile long energy;
        final long capacity;

        public Storage(long energy, long capacity) {
            this.energy = energy;
            this.capacity = capacity;
        }

        public void setEnergy(long energy) {
            this.energy = Math.max(0, Math.min(energy, capacity));
        }
    }

    private final Storage storage;

    public EnergyPeripheral(String block, Storage storage, Trace trace) {
        super(block, Set.of("energy_storage"), trace);
        this.storage = storage;
        method(true, (c, ctx, a) -> values(storage.energy), "getEnergy");
        method(true, (c, ctx, a) -> values(storage.capacity), "getEnergyCapacity");
    }

    @Override
    public Object getTarget() {
        return storage;
    }
}
