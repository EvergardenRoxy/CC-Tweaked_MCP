// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;
import dan200.computercraft.core.lua.CobaltLuaMachine;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.MachineResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps CC: Tweaked's real Lua VM and records every event delivered to it ({@code event} records),
 * including timers and {@code task_complete}. Lives on the Java side, so the program under test cannot
 * hide or alter what is recorded.
 * <p>
 * Limitation: an event that Lua code receives and then discards (e.g. inside {@code sleep}) is recorded
 * as delivered; whether Lua used it is not visible here.
 * <p>
 * The first {@code setting_changed} event for each setting cct-run injects (see Runner.injectedSettings) is
 * marked {@code "harness": true}: it comes from cct-run's configuration, not from the program.
 */
final class RecordingMachine implements ILuaMachine {
    /** Shared by the runner: which machine is live and how many have been created (a reboot creates another). */
    static final class Holder {
        final AtomicReference<ILuaMachine> current = new AtomicReference<>();
        final AtomicInteger created = new AtomicInteger();

        String executionState() {
            var machine = current.get();
            if (machine == null) return null;
            var out = new StringBuilder();
            machine.printExecutionState(out);
            return out.toString();
        }
    }

    static ILuaMachine.Factory factory(Trace trace, Holder holder, Map<String, Object> injectedSettings) {
        return (environment, bios) -> {
            // Each boot applies the settings again, so each VM gets its own copy to match against.
            var machine = new RecordingMachine(new CobaltLuaMachine(environment, bios), trace, new HashMap<>(injectedSettings));
            holder.created.incrementAndGet();
            holder.current.set(machine);
            return machine;
        };
    }

    private final ILuaMachine inner;
    private final Trace trace;
    private final Map<String, Object> pendingSettings;

    private RecordingMachine(ILuaMachine inner, Trace trace, Map<String, Object> pendingSettings) {
        this.inner = inner;
        this.trace = trace;
        this.pendingSettings = pendingSettings;
    }

    @Override
    public MachineResult handleEvent(String eventName, Object[] arguments) {
        var result = inner.handleEvent(eventName, arguments);
        // The first resume (eventName == null) boots the BIOS; it is not an event.
        if (eventName != null) {
            var data = new JsonObject();
            data.addProperty("name", eventName);
            data.add("args", JsonValues.array(arguments));
            if (fromInjectedSetting(eventName, arguments)) {
                data.addProperty("harness", true);
                data.addProperty("note", "caused by cct-run's default settings, not by the program");
            }
            trace.record("event", data);
        }
        if (result.isError()) {
            var data = new JsonObject();
            data.addProperty("message", result.getMessage());
            data.addProperty("timeout", result == MachineResult.TIMEOUT);
            trace.note("vm_error", data);
        }
        return result;
    }

    /** setting_changed arguments are (name, new value, old value). */
    private boolean fromInjectedSetting(String eventName, Object[] arguments) {
        if (!"setting_changed".equals(eventName) || arguments == null || arguments.length < 2) return false;
        if (!(arguments[0] instanceof String name) || !pendingSettings.containsKey(name)) return false;
        if (!Objects.equals(pendingSettings.get(name), arguments[1])) return false;
        pendingSettings.remove(name);
        return true;
    }

    @Override
    public void printExecutionState(StringBuilder out) {
        inner.printExecutionState(out);
    }

    @Override
    public void close() {
        inner.close();
    }
}
