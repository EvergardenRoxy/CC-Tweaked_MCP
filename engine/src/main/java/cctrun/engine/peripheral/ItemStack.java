// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A stack of items in a fake inventory. Two stacks merge when name, nbt and damage are equal. */
public final class ItemStack {
    final String name;
    int count;
    final String displayName;
    final int maxCount;
    final List<String> tags;
    final String nbt;
    final Integer damage;
    final Integer maxDamage;

    public ItemStack(String name, int count, String displayName, int maxCount, List<String> tags, String nbt, Integer damage, Integer maxDamage) {
        this.name = name;
        this.count = count;
        this.displayName = displayName != null ? displayName : defaultDisplayName(name);
        this.maxCount = maxCount;
        this.tags = List.copyOf(tags);
        this.nbt = nbt;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }

    ItemStack withCount(int newCount) {
        return new ItemStack(name, newCount, displayName, maxCount, tags, nbt, damage, maxDamage);
    }

    boolean canStackWith(ItemStack other) {
        return name.equals(other.name) && Objects.equals(nbt, other.nbt) && Objects.equals(damage, other.damage);
    }

    /** Shape of an entry in {@code inventory.list()}. */
    Map<String, Object> basicDetail() {
        var out = new HashMap<String, Object>();
        out.put("name", name);
        out.put("count", count);
        if (nbt != null) out.put("nbt", nbt);
        return out;
    }

    /** Shape of {@code inventory.getItemDetail(slot)} (a subset of the real one; see handoff.md). */
    Map<String, Object> fullDetail() {
        var out = basicDetail();
        out.put("displayName", displayName);
        out.put("maxCount", maxCount);
        var tagMap = new LinkedHashMap<String, Object>();
        for (var tag : tags) tagMap.put(tag, true);
        out.put("tags", tagMap);
        if (damage != null) out.put("damage", damage);
        if (maxDamage != null) out.put("maxDamage", maxDamage);
        return out;
    }

    /** "minecraft:iron_ingot" -> "Iron Ingot". */
    static String defaultDisplayName(String name) {
        var path = name.substring(name.indexOf(':') + 1);
        var out = new StringBuilder();
        for (var word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
