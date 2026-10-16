package cctrun.engine;

import cctrun.engine.peripheral.DispatchPeripheral;
import cctrun.engine.peripheral.EnergyPeripheral;
import cctrun.engine.peripheral.FluidPeripheral;
import cctrun.engine.peripheral.Inventory;
import cctrun.engine.peripheral.InventoryPeripheral;
import cctrun.engine.peripheral.ItemStack;
import cctrun.engine.peripheral.MonitorPeripheral;
import cctrun.engine.peripheral.WiredModemPeripheral;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dan200.computercraft.core.computer.ComputerSide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world around the computer for one run: computer settings, extra files, peripherals, initial
 * redstone inputs and a timeline of things to do at given ticks. Format: handoff.md (FIXTURE_FORMAT).
 * Parsing validates everything up front so a bad fixture fails before the computer starts.
 */
public final class Fixture {
    private static final Set<String> TOP_KEYS = Set.of("schema", "computer", "files", "peripherals", "redstone", "timeline");

    final JsonObject raw;
    final int computerId;
    final String label;
    final Boolean advanced;
    final boolean multishell;
    final int[] term;
    final Map<String, String> files;
    final Map<ComputerSide, JsonObject> peripherals;
    final Map<ComputerSide, Integer> redstone;
    final List<TimelineAction> timeline;

    /** One timeline entry, validated. */
    record TimelineAction(int tick, String action, JsonObject spec, String path) {
    }

    private Fixture(JsonObject raw, int computerId, String label, Boolean advanced, boolean multishell, int[] term, Map<String, String> files,
                    Map<ComputerSide, JsonObject> peripherals, Map<ComputerSide, Integer> redstone, List<TimelineAction> timeline) {
        this.raw = raw;
        this.computerId = computerId;
        this.label = label;
        this.advanced = advanced;
        this.multishell = multishell;
        this.term = term;
        this.files = files;
        this.peripherals = peripherals;
        this.redstone = redstone;
        this.timeline = timeline;
    }

    public static Fixture empty() {
        return new Fixture(new JsonObject(), 0, null, null, false, null, Map.of(), Map.of(), Map.of(), List.of());
    }

    public JsonObject raw() {
        return raw;
    }

    public static Fixture parse(String json) throws FixtureException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new FixtureException("$", "not valid JSON: " + e.getMessage());
        }
        var obj = object(root, "$");
        for (var key : obj.keySet()) if (!TOP_KEYS.contains(key)) throw new FixtureException("$." + key, "unknown key (allowed: " + TOP_KEYS + ")");
        if (obj.has("schema") && !Versions.FIXTURE_SCHEMA.equals(string(obj.get("schema"), "$.schema"))) {
            throw new FixtureException("$.schema", "expected \"" + Versions.FIXTURE_SCHEMA + "\"");
        }

        var computerId = 0;
        String label = null;
        Boolean advanced = null;
        var multishell = false;
        int[] term = null;
        if (obj.has("computer")) {
            var computer = object(obj.get("computer"), "$.computer");
            for (var key : computer.keySet()) {
                var path = "$.computer." + key;
                switch (key) {
                    case "id" -> computerId = integer(computer.get(key), path, 0, Integer.MAX_VALUE);
                    case "label" -> label = string(computer.get(key), path);
                    case "advanced" -> advanced = bool(computer.get(key), path);
                    case "multishell" -> multishell = bool(computer.get(key), path);
                    case "term" -> term = parseSize(string(computer.get(key), path), path);
                    default -> throw new FixtureException(path, "unknown key (allowed: id, label, advanced, multishell, term)");
                }
            }
        }

        var files = new LinkedHashMap<String, String>();
        if (obj.has("files")) {
            var fileObj = object(obj.get("files"), "$.files");
            for (var entry : fileObj.entrySet()) {
                var path = "$.files[\"" + entry.getKey() + "\"]";
                var name = entry.getKey().startsWith("/") ? entry.getKey().substring(1) : entry.getKey();
                if (name.isEmpty() || name.contains("..")) throw new FixtureException(path, "invalid file path");
                if (name.equals("startup.lua") || name.equals("startup") || name.startsWith("startup/")) {
                    throw new FixtureException(path, "reserved: cct-run runs the program from /startup.lua");
                }
                files.put(name, string(entry.getValue(), path));
            }
        }

        var peripherals = new LinkedHashMap<ComputerSide, JsonObject>();
        if (obj.has("peripherals")) {
            var sides = object(obj.get("peripherals"), "$.peripherals");
            var networkNames = new java.util.HashSet<String>();
            for (var entry : sides.entrySet()) {
                var path = "$.peripherals." + entry.getKey();
                var side = side(entry.getKey(), path);
                var spec = object(entry.getValue(), path);
                validatePeripheral(spec, path, true);
                if (spec.has("network")) {
                    for (var name : spec.getAsJsonObject("network").keySet()) {
                        if (!networkNames.add(name)) throw new FixtureException(path + ".network[\"" + name + "\"]", "name already used on another wired network");
                    }
                }
                peripherals.put(side, spec);
            }
        }

        var redstone = new LinkedHashMap<ComputerSide, Integer>();
        if (obj.has("redstone")) {
            var sides = object(obj.get("redstone"), "$.redstone");
            for (var entry : sides.entrySet()) {
                var path = "$.redstone." + entry.getKey();
                redstone.put(side(entry.getKey(), path), integer(entry.getValue(), path, 0, 15));
            }
        }

        var timeline = new ArrayList<TimelineAction>();
        if (obj.has("timeline")) {
            var list = array(obj.get("timeline"), "$.timeline");
            for (var i = 0; i < list.size(); i++) {
                var path = "$.timeline[" + i + "]";
                var spec = object(list.get(i), path);
                var tick = integer(require(spec, "tick", path), path + ".tick", 0, Integer.MAX_VALUE);
                var action = string(require(spec, "do", path), path + ".do");
                validateAction(action, spec, path, peripherals);
                timeline.add(new TimelineAction(tick, action, spec, path));
            }
        }

        return new Fixture(obj, computerId, label, advanced, multishell, term, files, peripherals, redstone, timeline);
    }

    private static void validatePeripheral(JsonObject spec, String path, boolean allowModem) throws FixtureException {
        var type = string(require(spec, "type", path), path + ".type");
        switch (type) {
            case "inventory" -> {
                allowOnly(spec, path, "type", "block", "size", "items");
                var size = spec.has("size") ? integer(spec.get("size"), path + ".size", 1, 1024) : 27;
                if (spec.has("block")) string(spec.get("block"), path + ".block");
                if (spec.has("items")) {
                    var items = object(spec.get("items"), path + ".items");
                    for (var entry : items.entrySet()) {
                        var itemPath = path + ".items." + entry.getKey();
                        int slot;
                        try {
                            slot = Integer.parseInt(entry.getKey());
                        } catch (NumberFormatException e) {
                            throw new FixtureException(itemPath, "slot keys must be integers");
                        }
                        if (slot < 1 || slot > size) throw new FixtureException(itemPath, "slot must be between 1 and " + size);
                        item(entry.getValue(), itemPath);
                    }
                }
            }
            case "energy_storage" -> {
                allowOnly(spec, path, "type", "block", "energy", "capacity");
                var capacity = spec.has("capacity") ? longValue(spec.get("capacity"), path + ".capacity", 0) : 100_000;
                var energy = spec.has("energy") ? longValue(spec.get("energy"), path + ".energy", 0) : 0;
                if (energy > capacity) throw new FixtureException(path + ".energy", "must not exceed capacity");
                if (spec.has("block")) string(spec.get("block"), path + ".block");
            }
            case "fluid_storage" -> {
                allowOnly(spec, path, "type", "block", "tanks");
                if (spec.has("block")) string(spec.get("block"), path + ".block");
                if (spec.has("tanks")) {
                    var tanks = array(spec.get("tanks"), path + ".tanks");
                    for (var i = 0; i < tanks.size(); i++) {
                        var tankPath = path + ".tanks[" + i + "]";
                        var tank = object(tanks.get(i), tankPath);
                        allowOnly(tank, tankPath, "name", "amount");
                        string(require(tank, "name", tankPath), tankPath + ".name");
                        longValue(require(tank, "amount", tankPath), tankPath + ".amount", 0);
                    }
                }
            }
            case "monitor" -> {
                allowOnly(spec, path, "type", "blocks", "advanced");
                if (spec.has("blocks")) parseBlocks(spec.get("blocks"), path + ".blocks");
                if (spec.has("advanced")) bool(spec.get("advanced"), path + ".advanced");
            }
            case "wired_modem" -> {
                if (!allowModem) throw new FixtureException(path, "a wired modem cannot be on a wired network");
                allowOnly(spec, path, "type", "local_name", "network");
                if (spec.has("local_name")) string(spec.get("local_name"), path + ".local_name");
                if (spec.has("network")) {
                    var network = object(spec.get("network"), path + ".network");
                    for (var entry : network.entrySet()) {
                        var remotePath = path + ".network[\"" + entry.getKey() + "\"]";
                        if (ComputerSide.NAMES.contains(entry.getKey())) throw new FixtureException(remotePath, "network names cannot be side names");
                        validatePeripheral(object(entry.getValue(), remotePath), remotePath, false);
                    }
                }
            }
            default -> throw new FixtureException(path + ".type", "unknown peripheral type \"" + type
                + "\" (supported: inventory, energy_storage, fluid_storage, monitor, wired_modem)");
        }
    }

    private static void validateAction(String action, JsonObject spec, String path, Map<ComputerSide, JsonObject> peripherals) throws FixtureException {
        switch (action) {
            case "event" -> {
                allowOnly(spec, path, "tick", "do", "name", "args");
                string(require(spec, "name", path), path + ".name");
                if (spec.has("args")) array(spec.get("args"), path + ".args");
            }
            case "terminate" -> allowOnly(spec, path, "tick", "do");
            case "redstone" -> {
                allowOnly(spec, path, "tick", "do", "side", "level");
                side(string(require(spec, "side", path), path + ".side"), path + ".side");
                integer(require(spec, "level", path), path + ".level", 0, 15);
            }
            case "energy" -> {
                allowOnly(spec, path, "tick", "do", "peripheral", "energy");
                requirePeripheral(spec, path, peripherals, "energy_storage");
                longValue(require(spec, "energy", path), path + ".energy", 0);
            }
            case "item" -> {
                allowOnly(spec, path, "tick", "do", "peripheral", "slot", "item");
                requirePeripheral(spec, path, peripherals, "inventory");
                integer(require(spec, "slot", path), path + ".slot", 1, 1024);
                var item = require(spec, "item", path);
                if (!item.isJsonNull()) item(item, path + ".item");
            }
            default -> throw new FixtureException(path + ".do", "unknown action \"" + action + "\" (supported: event, terminate, redstone, energy, item)");
        }
    }

    private static void requirePeripheral(JsonObject spec, String path, Map<ComputerSide, JsonObject> peripherals, String type) throws FixtureException {
        var name = string(require(spec, "peripheral", path), path + ".peripheral");
        var found = findSpec(name, peripherals);
        if (found == null) throw new FixtureException(path + ".peripheral", "no peripheral named \"" + name + "\" in this fixture");
        if (!type.equals(found.get("type").getAsString())) throw new FixtureException(path + ".peripheral", "\"" + name + "\" is not " + type);
    }

    private static JsonObject findSpec(String name, Map<ComputerSide, JsonObject> peripherals) {
        for (var entry : peripherals.entrySet()) {
            if (entry.getKey().getName().equals(name)) return entry.getValue();
            var spec = entry.getValue();
            if (spec.has("network") && spec.getAsJsonObject("network").has(name)) return spec.getAsJsonObject("network").getAsJsonObject(name);
        }
        return null;
    }

    /** Peripherals created for one run, addressable by side or network name. */
    record Built(Map<ComputerSide, DispatchPeripheral> sides, Map<String, DispatchPeripheral> byName, List<MonitorPeripheral> monitors) {
    }

    Built build(Trace trace, boolean computerAdvanced) {
        var sides = new LinkedHashMap<ComputerSide, DispatchPeripheral>();
        var byName = new LinkedHashMap<String, DispatchPeripheral>();
        var monitors = new ArrayList<MonitorPeripheral>();
        for (var entry : peripherals.entrySet()) {
            var name = entry.getKey().getName();
            var peripheral = create(name, entry.getValue(), trace, byName, monitors, computerAdvanced);
            sides.put(entry.getKey(), peripheral);
            byName.put(name, peripheral);
        }
        return new Built(sides, byName, monitors);
    }

    private static DispatchPeripheral create(String name, JsonObject spec, Trace trace, Map<String, DispatchPeripheral> byName,
                                             List<MonitorPeripheral> monitors, boolean computerAdvanced) {
        var type = spec.get("type").getAsString();
        switch (type) {
            case "inventory" -> {
                var size = spec.has("size") ? spec.get("size").getAsInt() : 27;
                var inventory = new Inventory(size, 64);
                if (spec.has("items")) {
                    for (var entry : spec.getAsJsonObject("items").entrySet()) {
                        inventory.set(Integer.parseInt(entry.getKey()) - 1, itemUnchecked(entry.getValue()));
                    }
                }
                return new InventoryPeripheral(blockOr(spec, "minecraft:chest"), inventory, trace);
            }
            case "energy_storage" -> {
                var capacity = spec.has("capacity") ? spec.get("capacity").getAsLong() : 100_000;
                var energy = spec.has("energy") ? spec.get("energy").getAsLong() : 0;
                return new EnergyPeripheral(blockOr(spec, "cctrun:energy_cell"), new EnergyPeripheral.Storage(energy, capacity), trace);
            }
            case "fluid_storage" -> {
                var tanks = new ArrayList<FluidPeripheral.Tank>();
                if (spec.has("tanks")) {
                    for (var tank : spec.getAsJsonArray("tanks")) {
                        var t = tank.getAsJsonObject();
                        tanks.add(new FluidPeripheral.Tank(t.get("name").getAsString(), t.get("amount").getAsLong()));
                    }
                }
                return new FluidPeripheral(blockOr(spec, "cctrun:tank"), tanks, trace);
            }
            case "monitor" -> {
                var blocks = spec.has("blocks") ? parseBlocksUnchecked(spec.get("blocks")) : new int[]{ 1, 1 };
                var advanced = spec.has("advanced") ? spec.get("advanced").getAsBoolean() : computerAdvanced;
                var monitor = new MonitorPeripheral(name, blocks[0], blocks[1], advanced, trace);
                monitors.add(monitor);
                return monitor;
            }
            case "wired_modem" -> {
                var remote = new LinkedHashMap<String, DispatchPeripheral>();
                if (spec.has("network")) {
                    for (var entry : spec.getAsJsonObject("network").entrySet()) {
                        var peripheral = create(entry.getKey(), entry.getValue().getAsJsonObject(), trace, byName, monitors, computerAdvanced);
                        remote.put(entry.getKey(), peripheral);
                        byName.put(entry.getKey(), peripheral);
                    }
                }
                var localName = spec.has("local_name") ? spec.get("local_name").getAsString() : "computer_0";
                return new WiredModemPeripheral(localName, remote, trace);
            }
            default -> throw new IllegalStateException("validated earlier: " + type);
        }
    }

    // --- item parsing -------------------------------------------------------------------------

    private static ItemStack item(JsonElement element, String path) throws FixtureException {
        var spec = object(element, path);
        allowOnly(spec, path, "name", "count", "displayName", "maxCount", "tags", "nbt", "damage", "maxDamage");
        var name = string(require(spec, "name", path), path + ".name");
        if (!name.contains(":")) throw new FixtureException(path + ".name", "item ids look like \"minecraft:iron_ingot\"");
        var maxCount = spec.has("maxCount") ? integer(spec.get("maxCount"), path + ".maxCount", 1, 64) : 64;
        var count = spec.has("count") ? integer(spec.get("count"), path + ".count", 1, 64) : 1;
        if (count > maxCount) throw new FixtureException(path + ".count", "exceeds maxCount " + maxCount);
        var tags = new ArrayList<String>();
        if (spec.has("tags")) {
            var list = array(spec.get("tags"), path + ".tags");
            for (var i = 0; i < list.size(); i++) tags.add(string(list.get(i), path + ".tags[" + i + "]"));
        }
        return new ItemStack(name, count,
            spec.has("displayName") ? string(spec.get("displayName"), path + ".displayName") : null,
            maxCount, tags,
            spec.has("nbt") ? string(spec.get("nbt"), path + ".nbt") : null,
            spec.has("damage") ? integer(spec.get("damage"), path + ".damage", 0, Integer.MAX_VALUE) : null,
            spec.has("maxDamage") ? integer(spec.get("maxDamage"), path + ".maxDamage", 1, Integer.MAX_VALUE) : null);
    }

    static ItemStack itemUnchecked(JsonElement element) {
        try {
            return item(element, "$");
        } catch (FixtureException e) {
            throw new IllegalStateException("validated earlier", e);
        }
    }

    // --- primitive helpers --------------------------------------------------------------------

    static ComputerSide side(String name, String path) throws FixtureException {
        var side = ComputerSide.valueOfInsensitive(name);
        if (side == null) throw new FixtureException(path, "not a side (use one of " + ComputerSide.NAMES + ")");
        return side;
    }

    private static int[] parseBlocks(JsonElement element, String path) throws FixtureException {
        var list = array(element, path);
        if (list.size() != 2) throw new FixtureException(path, "expected [width, height] in blocks");
        return new int[]{ integer(list.get(0), path + "[0]", 1, 8), integer(list.get(1), path + "[1]", 1, 6) };
    }

    private static int[] parseBlocksUnchecked(JsonElement element) {
        var list = element.getAsJsonArray();
        return new int[]{ list.get(0).getAsInt(), list.get(1).getAsInt() };
    }

    static int[] parseSize(String value, String path) throws FixtureException {
        var parts = value.toLowerCase().split("x");
        try {
            if (parts.length == 2) {
                var w = Integer.parseInt(parts[0].trim());
                var h = Integer.parseInt(parts[1].trim());
                if (w >= 1 && w <= 255 && h >= 1 && h <= 255) return new int[]{ w, h };
            }
        } catch (NumberFormatException ignored) {
        }
        throw new FixtureException(path, "expected WIDTHxHEIGHT, e.g. 51x19");
    }

    private static String blockOr(JsonObject spec, String fallback) {
        return spec.has("block") ? spec.get("block").getAsString() : fallback;
    }

    private static void allowOnly(JsonObject obj, String path, String... keys) throws FixtureException {
        var allowed = Set.of(keys);
        for (var key : obj.keySet()) if (!allowed.contains(key)) throw new FixtureException(path + "." + key, "unknown key (allowed: " + allowed + ")");
    }

    private static JsonElement require(JsonObject obj, String key, String path) throws FixtureException {
        if (!obj.has(key)) throw new FixtureException(path + "." + key, "required");
        return obj.get(key);
    }

    private static JsonObject object(JsonElement e, String path) throws FixtureException {
        if (e == null || !e.isJsonObject()) throw new FixtureException(path, "expected an object");
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String path) throws FixtureException {
        if (e == null || !e.isJsonArray()) throw new FixtureException(path, "expected an array");
        return e.getAsJsonArray();
    }

    private static String string(JsonElement e, String path) throws FixtureException {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new FixtureException(path, "expected a string");
        return e.getAsString();
    }

    private static boolean bool(JsonElement e, String path) throws FixtureException {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new FixtureException(path, "expected true or false");
        return e.getAsBoolean();
    }

    private static int integer(JsonElement e, String path, int min, int max) throws FixtureException {
        var value = longValue(e, path, min);
        if (value > max) throw new FixtureException(path, "must be between " + min + " and " + max);
        return (int) value;
    }

    private static long longValue(JsonElement e, String path, long min) throws FixtureException {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new FixtureException(path, "expected a number");
        var d = e.getAsDouble();
        if (d != Math.rint(d)) throw new FixtureException(path, "expected a whole number");
        if (d < min) throw new FixtureException(path, "must be at least " + min);
        return (long) d;
    }
}
