package cctrun.engine.peripheral;

import cctrun.engine.Trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A block exposing CC: Tweaked's generic {@code fluid_storage} peripheral. Only {@code tanks()} is
 * implemented; pushFluid / pullFluid are not (see handoff.md).
 */
public final class FluidPeripheral extends DispatchPeripheral {
    public record Tank(String name, long amount) {
    }

    private final List<Tank> tanks;

    public FluidPeripheral(String block, List<Tank> tanks, Trace trace) {
        super(block, Set.of("fluid_storage"), trace);
        this.tanks = List.copyOf(tanks);
        method(true, (c, ctx, a) -> {
            var out = new ArrayList<Object>();
            for (var tank : this.tanks) out.add(tank.amount() <= 0 ? null : Map.of("name", tank.name(), "amount", tank.amount()));
            return values(out);
        }, "tanks");
    }

    @Override
    public Object getTarget() {
        return tanks;
    }
}
