// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fixture validation: every mistake is reported with its JSON path. */
class FixtureTest {
    private static String error(String json) {
        return assertThrows(FixtureException.class, () -> Fixture.parse(json)).getMessage();
    }

    @Test
    void reportsPathsOfMistakes() {
        assertEquals("$: not valid JSON: java.io.EOFException: End of input at line 1 column 2 path $.", error("{"));
        assertEquals("$.peripherals: expected an object", error("{\"peripherals\": []}"));
        assertEquals("$.peripherals.up: not a side (use one of [bottom, top, back, front, right, left])", error("{\"peripherals\": {\"up\": {}}}"));
        assertEquals("$.peripherals.left.type: required", error("{\"peripherals\": {\"left\": {}}}"));
        assertEquals("$.peripherals.left.type: unknown peripheral type \"turtle\" (supported: inventory, energy_storage, fluid_storage, monitor, wired_modem, scripted)",
            error("{\"peripherals\": {\"left\": {\"type\": \"turtle\"}}}"));
        assertEquals("$.peripherals.left.items.4: slot must be between 1 and 3",
            error("{\"peripherals\": {\"left\": {\"type\": \"inventory\", \"size\": 3, \"items\": {\"4\": {\"name\": \"minecraft:dirt\"}}}}}"));
        assertEquals("$.peripherals.left.items.1.count: exceeds maxCount 16",
            error("{\"peripherals\": {\"left\": {\"type\": \"inventory\", \"items\": {\"1\": {\"name\": \"minecraft:egg\", \"count\": 17, \"maxCount\": 16}}}}}"));
        assertEquals("$.timeline[0].peripheral: no peripheral named \"right\" in this fixture",
            error("{\"timeline\": [{\"tick\": 1, \"do\": \"energy\", \"peripheral\": \"right\", \"energy\": 1}]}"));
        assertEquals("$.timeline[0].do: unknown action \"explode\" (supported: event, terminate, redstone, energy, item, method)",
            error("{\"timeline\": [{\"tick\": 1, \"do\": \"explode\"}]}"));
        assertEquals("$.computer.term: expected WIDTHxHEIGHT, e.g. 51x19", error("{\"computer\": {\"term\": \"big\"}}"));
        assertEquals("$.colour: unknown key (allowed: [schema, computer, files, peripherals, redstone, timeline])",
            error("{\"colour\": true}").replaceAll("\\[.*]", "[schema, computer, files, peripherals, redstone, timeline]"));
    }

    @Test
    void reservedFilesAndDuplicateNetworkNamesAreRejected() {
        assertEquals("$.files[\"/startup.lua\"]: reserved: cct-run runs the program from /startup.lua",
            error("{\"files\": {\"/startup.lua\": \"\"}}"));
        assertEquals("$.peripherals.top.network[\"minecraft:chest_0\"]: name already used on another wired network",
            error("{\"peripherals\": {\"back\": {\"type\": \"wired_modem\", \"network\": {\"minecraft:chest_0\": {\"type\": \"inventory\"}}},"
                + "\"top\": {\"type\": \"wired_modem\", \"network\": {\"minecraft:chest_0\": {\"type\": \"inventory\"}}}}}"));
    }

    @Test
    void networkNamesMustNotBeSides() {
        assertEquals("$.peripherals.back.network[\"left\"]: network names cannot be side names",
            error("{\"peripherals\": {\"back\": {\"type\": \"wired_modem\", \"network\": {\"left\": {\"type\": \"inventory\"}}}}}"));
    }
}
