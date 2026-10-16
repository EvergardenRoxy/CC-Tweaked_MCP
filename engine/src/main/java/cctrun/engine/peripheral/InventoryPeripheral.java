// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import cctrun.engine.Trace;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A block with an item inventory, as CC: Tweaked's generic {@code inventory} peripheral exposes it.
 * Type is the block id (e.g. {@code minecraft:chest}) with additional type {@code inventory}.
 * All methods run on the main thread, as in the game. Error messages match the game's.
 */
public final class InventoryPeripheral extends DispatchPeripheral {
    private final Inventory inventory;

    public InventoryPeripheral(String block, Inventory inventory, Trace trace) {
        super(block, Set.of("inventory"), trace);
        this.inventory = inventory;

        method(true, (c, ctx, a) -> values(inventory.size()), "size");
        method(true, (c, ctx, a) -> values(list()), "list");
        method(true, (c, ctx, a) -> {
            var slot = checkSlot(a.getInt(0), "Slot out of range (%s)");
            var stack = inventory.get(slot);
            return values(stack == null ? null : stack.fullDetail());
        }, "getItemDetail");
        method(true, (c, ctx, a) -> {
            checkSlot(a.getInt(0), "Slot out of range (%s)");
            return values(inventory.slotLimit());
        }, "getItemLimit");
        method(true, this::pushItems, "pushItems");
        method(true, this::pullItems, "pullItems");
    }

    @Override
    public Object getTarget() {
        return inventory;
    }

    private Map<Integer, Object> list() {
        var out = new HashMap<Integer, Object>();
        for (var i = 0; i < inventory.size(); i++) {
            var stack = inventory.get(i);
            if (stack != null) out.put(i + 1, stack.basicDetail());
        }
        return out;
    }

    private Object[] pushItems(IComputerAccess computer, ILuaContext ctx, IArguments args) throws LuaException {
        var toName = args.getString(0);
        var fromSlot = args.getInt(1);
        var limit = args.optInt(2).orElse(Integer.MAX_VALUE);
        var toSlot = args.optInt(3);

        var location = computer.getAvailablePeripheral(toName);
        if (location == null) throw new LuaException("Target '" + toName + "' does not exist");
        if (!(location.getTarget() instanceof Inventory to)) throw new LuaException("Target '" + toName + "' is not an inventory");

        checkRange(fromSlot, inventory.size(), "From slot out of range (%s)");
        if (toSlot.isPresent()) checkRange(toSlot.get(), to.size(), "To slot out of range (%s)");
        if (limit <= 0) return values(0);
        return values(Inventory.move(inventory, fromSlot - 1, to, toSlot.map(s -> s - 1).orElse(-1), limit));
    }

    private Object[] pullItems(IComputerAccess computer, ILuaContext ctx, IArguments args) throws LuaException {
        var fromName = args.getString(0);
        var fromSlot = args.getInt(1);
        var limit = args.optInt(2).orElse(Integer.MAX_VALUE);
        var toSlot = args.optInt(3);

        var location = computer.getAvailablePeripheral(fromName);
        if (location == null) throw new LuaException("Source '" + fromName + "' does not exist");
        if (!(location.getTarget() instanceof Inventory from)) throw new LuaException("Source '" + fromName + "' is not an inventory");

        checkRange(fromSlot, from.size(), "From slot out of range (%s)");
        if (toSlot.isPresent()) checkRange(toSlot.get(), inventory.size(), "To slot out of range (%s)");
        if (limit <= 0) return values(0);
        return values(Inventory.move(from, fromSlot - 1, inventory, toSlot.map(s -> s - 1).orElse(-1), limit));
    }

    private int checkSlot(int slot, String message) throws LuaException {
        checkRange(slot, inventory.size(), message);
        return slot - 1;
    }

    /** Same wording as dan200.computercraft.core.util.ArgumentHelpers#assertBetween. */
    static void checkRange(int value, int max, String message) throws LuaException {
        if (value < 1 || value > max) throw new LuaException(String.format(message, "between 1 and " + max));
    }
}
