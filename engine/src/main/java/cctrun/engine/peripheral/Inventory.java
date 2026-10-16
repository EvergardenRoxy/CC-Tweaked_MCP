package cctrun.engine.peripheral;

/**
 * Slots of a fake inventory. Slot numbers in this class are 0-based; the Lua API is 1-based.
 * <p>
 * Moving items follows Forge's behaviour (CC: Tweaked's forge InventoryMethods with
 * ItemHandlerHelper.insertItem): without a target slot, the target is filled in slot order, merging
 * with matching stacks and filling empty slots as they come. Fabric fills existing stacks first.
 */
public final class Inventory {
    private final ItemStack[] slots;
    private final int slotLimit;

    public Inventory(int size, int slotLimit) {
        this.slots = new ItemStack[size];
        this.slotLimit = slotLimit;
    }

    public synchronized int size() {
        return slots.length;
    }

    int slotLimit() {
        return slotLimit;
    }

    public synchronized ItemStack get(int slot) {
        return slots[slot];
    }

    public synchronized void set(int slot, ItemStack stack) {
        slots[slot] = stack == null || stack.count <= 0 ? null : stack;
    }

    /** Move up to {@code limit} items from {@code fromSlot} into {@code to} ({@code toSlot} &lt; 0: anywhere). */
    static int move(Inventory from, int fromSlot, Inventory to, int toSlot, int limit) {
        // Lock both in a fixed order so two transfers in opposite directions cannot deadlock.
        var first = System.identityHashCode(from) <= System.identityHashCode(to) ? from : to;
        var second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                var source = from.slots[fromSlot];
                if (source == null || limit <= 0) return 0;
                var toMove = Math.min(source.count, limit);
                var moved = 0;
                if (toSlot >= 0) {
                    moved = to.insertInto(toSlot, source, toMove);
                } else {
                    for (var i = 0; i < to.slots.length && moved < toMove; i++) {
                        if (to == from && i == fromSlot) continue;
                        moved += to.insertInto(i, source, toMove - moved);
                    }
                }
                if (moved > 0) {
                    source.count -= moved;
                    if (source.count <= 0) from.slots[fromSlot] = null;
                }
                return moved;
            }
        }
    }

    /** Put up to {@code amount} of {@code like} into one slot; returns how many fit. Caller holds the lock. */
    private int insertInto(int slot, ItemStack like, int amount) {
        var limit = Math.min(like.maxCount, slotLimit);
        var existing = slots[slot];
        if (existing == null) {
            var n = Math.min(amount, limit);
            if (n > 0) slots[slot] = like.withCount(n);
            return n;
        }
        if (existing == like || !existing.canStackWith(like)) return 0;
        var n = Math.min(amount, limit - existing.count);
        if (n <= 0) return 0;
        existing.count += n;
        return n;
    }
}
