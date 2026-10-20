// SPDX-License-Identifier: MPL-2.0
package cctrun.engine.peripheral;

import cctrun.engine.JsonValues;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemStackTest {
    @Test
    void detailsSerializeFieldsInDeclaredOrderWithoutLosingOptionalValues() {
        var item = new ItemStack("minecraft:pickaxe", 1, "Pickaxe", 1,
            List.of("minecraft:tools"), "nbt-hash", 2, 100);
        var basic = JsonValues.of(item.basicDetail()).getAsJsonObject();
        assertEquals(List.of("name", "count", "nbt"), new ArrayList<>(basic.keySet()));
        var full = JsonValues.of(item.fullDetail()).getAsJsonObject();
        assertEquals(List.of("name", "count", "nbt", "displayName", "maxCount", "tags", "damage", "maxDamage"),
            new ArrayList<>(full.keySet()));
        assertEquals("nbt-hash", full.get("nbt").getAsString());
        assertEquals(2, full.get("damage").getAsInt());
        assertEquals(100, full.get("maxDamage").getAsInt());
        assertEquals(true, full.getAsJsonObject("tags").get("minecraft:tools").getAsBoolean());
    }
}
