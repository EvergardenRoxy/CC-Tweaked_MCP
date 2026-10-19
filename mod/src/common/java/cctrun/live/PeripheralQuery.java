// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaTask;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.peripheral.modem.wired.WiredModemPeripheral;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Calls a getter of one of a computer's peripherals from Java, on the server thread, without the computer's Lua VM:
 * works while a program runs (cct_live_lua needs the shell prompt) and cannot change what the program sees. No event
 * reaches the computer (queueEvent is dropped); main-thread tasks run at once instead of through task_complete.
 * Only methods whose names read state (get..., is..., has..., list, size, ...) are allowed.
 */
final class PeripheralQuery {
    static final Pattern READ_ONLY = Pattern.compile("(get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)?");
    private static final int MAX_STEPS = 16;
    private static final Field WRAPPERS = CcInternals.optionalField(WiredModemPeripheral.class, "peripheralWrappers", Map.class);
    private static final Field WRAPPED_PERIPHERAL = CcInternals.optionalField(WiredModemPeripheral.class.getName() + "$RemotePeripheralWrapper", "peripheral");
    static boolean available() { return WRAPPERS != null && WRAPPED_PERIPHERAL != null; }
    private static void putUnique(Map<String, IPeripheral> map, String name, IPeripheral value) {
        PeripheralNames.put(map,name,value,(a,b)->a.equals(b));
    }

    private PeripheralQuery() {
    }

    /** Peripherals the computer can reach: its sides, and the names on wired networks of modems on its sides. */
    static Map<String, IPeripheral> available(ServerComputer computer) {
        var out = new HashMap<String, IPeripheral>();
        for (var side : ComputerSide.values()) {
            var peripheral = computer.getPeripheral(side);
            if (peripheral == null) continue;
            putUnique(out, side.getName(), peripheral);
            if (peripheral instanceof WiredModemPeripheral modem && available()) remote(modem).forEach((name, value) -> putUnique(out, name, value));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IPeripheral> remote(WiredModemPeripheral modem) {
        if (!available()) throw new CcInternals.MissingCapability("wired peripheral queries unavailable: expected CC:T 1.120.2 internals");
        var out = new HashMap<String, IPeripheral>();
        try {
            var wrappers = (Map<IComputerAccess, Map<String, Object>>) WRAPPERS.get(modem);
            synchronized (wrappers) {
                for (var perComputer : wrappers.values()) {
                    for (var entry : perComputer.entrySet()) putUnique(out, entry.getKey(), (IPeripheral) WRAPPED_PERIPHERAL.get(entry.getValue()));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Server thread. Without a method: the peripheral's types and methods. */
    static JsonObject call(ServerComputer computer, String name, @Nullable String method, JsonArray args) {
        var peripherals = available(computer);
        var peripheral = peripherals.get(name);
        if (peripheral == null) {
            if (!available()) throw new CcInternals.MissingCapability("no such peripheral on a side; wired lookup unavailable: expected CC:T 1.120.2 internals");
            throw new IllegalArgumentException("no peripheral '" + name + "' on computer " + computer.getID() + "; available: " + new TreeSet<>(peripherals.keySet()));
        }
        var methods = ServerContext.get(computer.getLevel().getServer()).peripheralMethods().getSelfMethods(peripheral);
        var out = new JsonObject();
        out.addProperty("peripheral", name);
        out.addProperty("type", peripheral.getType());
        if (method == null) {
            var types = new JsonArray();
            for (var t : peripheral.getAdditionalTypes()) types.add(t);
            out.add("additional_types", types);
            var names = new JsonArray();
            for (var m : new TreeSet<>(methods.keySet())) names.add(m);
            out.add("methods", names);
            return out;
        }
        out.addProperty("method", method);
        var target = methods.get(method);
        if (target == null) throw new IllegalArgumentException("'" + name + "' has no method " + method + "; methods: " + new TreeSet<>(methods.keySet()));
        if (!READ_ONLY.matcher(method).matches()) {
            throw new IllegalArgumentException("only methods that read state can be called here (get..., is..., has..., can..., list, size, tanks, count...); " + method + " could change the world: run it from a program, or with cct_live_lua at the shell prompt");
        }

        var context = new Context();
        var access = new Access(computer, name);
        try {
            var result = target.apply(peripheral, context, access, new ObjectArguments(lua(args)));
            for (var step = 0; result.getCallback() != null; step++) {
                // A main-thread method waits for task_complete; we are on the main thread, so run its task now.
                if (step >= MAX_STEPS || context.task == null) throw new LuaException(method + " waits for events; it cannot be called here");
                var task = context.task;
                context.task = null;
                Object[] event;
                try {
                    var values = task.execute();
                    event = values == null ? new Object[]{"task_complete", context.id, true} : concat(new Object[]{"task_complete", context.id, true}, values);
                } catch (LuaException e) {
                    event = new Object[]{"task_complete", context.id, false, e.getMessage()};
                }
                result = result.getCallback().resume(event);
            }
            out.addProperty("ok", true);
            out.add("result", JsonValues.array(result.getResult() == null ? new Object[0] : result.getResult()));
        } catch (LuaException e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.getMessage());
        }
        return out;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        var out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** JSON arguments as Lua values: numbers are doubles, objects and arrays are tables (arrays 1-based). */
    private static List<Object> lua(JsonArray args) {
        var out = new ArrayList<>();
        for (var arg : args) out.add(lua(arg));
        return out;
    }

    private static @Nullable Object lua(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        var table = new HashMap<Object, Object>();
        if (value.isJsonArray()) {
            var i = 1;
            for (var v : value.getAsJsonArray()) table.put((double) i++, lua(v));
        } else {
            for (var e : value.getAsJsonObject().entrySet()) table.put(e.getKey(), lua(e.getValue()));
        }
        return table;
    }

    /** Runs nothing by itself: the caller executes the one issued task and resumes the method. */
    private static final class Context implements ILuaContext {
        @Nullable LuaTask task;
        long id;

        @Override
        public long issueMainThreadTask(LuaTask task) throws LuaException {
            if (this.task != null) throw new LuaException("more than one main-thread task");
            this.task = task;
            return ++id;
        }
    }

    /** The calling computer as far as a getter needs it; events and mounts are refused or dropped. */
    private record Access(ServerComputer computer, String name) implements IComputerAccess {
        @Override
        public @Nullable String mount(String desiredLocation, dan200.computercraft.api.filesystem.Mount mount, String driveName) {
            return null;
        }

        @Override
        public @Nullable String mountWritable(String desiredLocation, dan200.computercraft.api.filesystem.WritableMount mount, String driveName) {
            return null;
        }

        @Override
        public void unmount(@Nullable String location) {
        }

        @Override
        public int getID() {
            return computer.getID();
        }

        @Override
        public void queueEvent(String event, @Nullable Object... arguments) {
            // Dropped: the program must not see events caused by the agent's query.
        }

        @Override
        public String getAttachmentName() {
            return name;
        }

        @Override
        public Map<String, IPeripheral> getAvailablePeripherals() {
            return Map.of();
        }

        @Override
        public @Nullable IPeripheral getAvailablePeripheral(String name) {
            return null;
        }

        @Override
        public WorkMonitor getMainThreadMonitor() {
            return MONITOR;
        }
    }

    private static final WorkMonitor MONITOR = new WorkMonitor() {
        @Override
        public boolean canWork() {
            return true;
        }

        @Override
        public boolean shouldWork() {
            return true;
        }

        @Override
        public void trackWork(long time, TimeUnit unit) {
        }
    };
}
