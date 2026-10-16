// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static cctrun.engine.RunHelper.error;
import static cctrun.engine.RunHelper.monitor;
import static cctrun.engine.RunHelper.run;
import static cctrun.engine.RunHelper.screen;
import static cctrun.engine.RunHelper.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fake peripherals: behaviour and error messages as in CC: Tweaked 1.120.2, and their trace records. */
class PeripheralTest {
    @TempDir
    Path out;

    private static final String TWO_CHESTS = """
        {"peripherals": {
          "left":  {"type": "inventory", "size": 3, "items": {"1": {"name": "minecraft:iron_ingot", "count": 50}}},
          "right": {"type": "inventory", "size": 2, "items": {"2": {"name": "minecraft:iron_ingot", "count": 60}}}
        }}""";

    @Test
    void directlyAttachedInventoriesMoveItemsBySideName() throws Exception {
        var result = run(out, """
            local left = peripheral.wrap("left")
            print(peripheral.getType("left"))
            print(left.size(), left.getItemLimit(1))
            -- Forge order: slot 1 of "right" is empty and comes first, so all 30 go there.
            print(left.pushItems("right", 1, 30))
            local list = peripheral.call("right", "list")
            print(list[1].count, list[2].count)
            local d = left.getItemDetail(1)
            print(d.name, d.count, d.displayName, d.maxCount)
            print(left.getItemDetail(2))
            """, TWO_CHESTS, 10);
        assertEquals("ok", result.status(), () -> String.valueOf(result.summary()));
        assertEquals(List.of(
            "minecraft:chest\tinventory",
            "3\t64",
            "30",
            "30\t60",
            "minecraft:iron_ingot\t20\tIron Ingot\t64",
            "nil" // empty slot: getItemDetail returns nil
        ), screen(result));
    }

    @Test
    void inventoryErrorsUseTheGamesMessages() throws Exception {
        var result = run(out, """
            local left = peripheral.wrap("left")
            print(pcall(left.getItemDetail, 9))
            print(pcall(left.pushItems, "top", 1))
            print(pcall(left.pushItems, "right", 9))
            print(pcall(left.pullItems, "nowhere", 1))
            print(left.pushItems("right", 3))
            """, TWO_CHESTS, 10);
        assertEquals(List.of(
            "false\tSlot out of range (between 1 and 3)",
            "false\tTarget 'top' does not exist",
            "false\tFrom slot out of range (between 1 and 3)",
            "false\tSource 'nowhere' does not exist",
            "0"
        ), screen(result));
        assertEquals(4, result.summary().getAsJsonArray("notes").size());
    }

    @Test
    void mainThreadMethodsTakeATickAndAreTracedWithResults() throws Exception {
        var result = run(out, "peripheral.call('left', 'size')", TWO_CHESTS, 10);
        var call = trace(result, "peripheral_call").get(0);
        var ret = trace(result, "peripheral_return").get(0);
        assertEquals("size", call.get("method").getAsString());
        assertTrue(call.get("main_thread").getAsBoolean());
        assertEquals(call.get("id"), ret.get("id"));
        assertEquals(3, ret.getAsJsonArray("result").get(0).getAsInt());
        assertTrue(ret.get("tick").getAsInt() > call.get("tick").getAsInt(), "main-thread call returns on a later tick");
    }

    @Test
    void wiredNetworkPeripheralsAreFoundAndTransferByNetworkName() throws Exception {
        var result = run(out, """
            local names = {}
            for _, inv in ipairs({ peripheral.find("inventory") }) do names[#names + 1] = peripheral.getName(inv) end
            table.sort(names)
            print(table.concat(names, ","))
            local a = peripheral.wrap("minecraft:chest_0")
            print(a.pushItems("minecraft:chest_1", 1))
            print(pcall(a.pushItems, "back", 1))
            local modem = peripheral.wrap("back")
            print(modem.getNameLocal(), modem.isWireless())
            print(pcall(modem.callRemote, "minecraft:chest_0", "nope"))
            """, """
            {"peripherals": {"back": {"type": "wired_modem", "local_name": "computer_7", "network": {
              "minecraft:chest_0": {"type": "inventory", "items": {"1": {"name": "minecraft:stone", "count": 5}}},
              "minecraft:chest_1": {"type": "inventory"}
            }}}}""", 10);
        assertEquals(List.of(
            "minecraft:chest_0,minecraft:chest_1",
            "5",
            "false\tTarget 'back' does not exist", // the modem's side is not on the network
            "computer_7\tfalse",
            "false\tNo such method nope"
        ), screen(result));
        var pushes = trace(result, "peripheral_call", r -> r.get("method").getAsString().equals("pushItems"));
        assertEquals("minecraft:chest_0", pushes.get(0).get("peripheral").getAsString());
    }

    @Test
    void monitorDrawsResizesAndQueuesMonitorResize() throws Exception {
        var result = run(out, """
            local mon = peripheral.wrap("top")
            print(mon.getSize())
            mon.setTextScale(0.5)
            print(mon.getSize())
            local _, side = os.pullEvent("monitor_resize")
            print(side, mon.getTextScale(), mon.isColour())
            mon.setTextColour(colours.red)
            mon.setCursorPos(2, 1)
            mon.write("hello")
            print(pcall(mon.setTextScale, 7))
            """, "{\"peripherals\": {\"top\": {\"type\": \"monitor\", \"blocks\": [2, 1]}}}", 10);
        assertEquals("ok", result.status(), () -> String.valueOf(result.summary()));
        // 2x1 blocks: 18x5 at scale 1, 36x10 at scale 0.5 (CC: Tweaked's ServerMonitor formula).
        assertEquals(List.of("18\t5", "36\t10", "top\t0.5\ttrue", "false\tExpected number in range 0.5-5"), screen(result));
        assertEquals(List.of(" hello"), monitor(result, "top"));
        assertTrue(trace(result, "screen", r -> r.get("source").getAsString().equals("top")).size() >= 1);
    }

    @Test
    void singleMonitorBlockIsSevenByFive() throws Exception {
        var result = run(out, "print(peripheral.call('top', 'getSize'))", "{\"peripherals\": {\"top\": {\"type\": \"monitor\"}}}", 10);
        assertEquals(List.of("7\t5"), screen(result));
    }

    @Test
    void energyFluidAndTimelineChanges() throws Exception {
        var result = run(out, """
            local cell = peripheral.find("energy_storage")
            print(cell.getEnergy(), cell.getEnergyCapacity())
            sleep(1)
            print(cell.getEnergy())
            local tanks = peripheral.call("bottom", "tanks")
            print(tanks[1].name, tanks[1].amount, tanks[2])
            print(peripheral.call("left", "getItemDetail", 2).count)
            """, """
            {"peripherals": {
              "right": {"type": "energy_storage", "energy": 100, "capacity": 1000},
              "bottom": {"type": "fluid_storage", "tanks": [{"name": "minecraft:water", "amount": 500}, {"name": "minecraft:lava", "amount": 0}]},
              "left": {"type": "inventory", "size": 2}
            },
             "timeline": [
              {"tick": 10, "do": "energy", "peripheral": "right", "energy": 900},
              {"tick": 10, "do": "item", "peripheral": "left", "slot": 2, "item": {"name": "minecraft:coal", "count": 7}}
            ]}""", 10);
        assertEquals("ok", result.status(), () -> String.valueOf(result.summary()));
        assertEquals(List.of("100\t1000", "900", "minecraft:water\t500\tnil", "7"), screen(result));
        assertEquals(2, trace(result, "fixture_action").size());
    }

    @Test
    void redstoneInputsTimelineAndOutputs() throws Exception {
        var result = run(out, """
            print(redstone.getInput("back"))
            os.pullEvent("redstone")
            print(redstone.getAnalogInput("front"))
            redstone.setOutput("left", true)
            sleep(0.2)
            redstone.setAnalogOutput("left", 3)
            sleep(0.2)
            """, """
            {"redstone": {"back": 15},
             "timeline": [{"tick": 5, "do": "redstone", "side": "front", "level": 9}]}""", 10);
        assertEquals(List.of("true", "9"), screen(result));
        var outputs = trace(result, "redstone_output").stream().map(r -> r.get("level").getAsInt()).toList();
        assertEquals(List.of(15, 3), outputs);
    }

    @Test
    void terminateFromTheTimeline() throws Exception {
        var result = run(out, "while true do os.pullEvent() end", "{\"timeline\": [{\"tick\": 5, \"do\": \"terminate\"}]}", 10);
        assertEquals("error", result.status());
        assertEquals("Terminated", error(result).get("message").getAsString());
    }
}
