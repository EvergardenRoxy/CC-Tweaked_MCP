// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;

import dan200.computercraft.core.lua.CobaltLuaMachine;
import org.squiddev.cobalt.LuaState;

/** Pinned CC:T 1.120.2/Cobalt 0.9.9 adapter: observe Java errors before LuaError loses its cause. */
final class VmFailures {
    volatile boolean outOfMemory;
    volatile String machineError;
    volatile boolean machineTimeout;

    void observe(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof OutOfMemoryError) { outOfMemory = true; return; }
        }
    }

    void install(CobaltLuaMachine machine) {
        try {
            var stateField = CobaltLuaMachine.class.getDeclaredField("state");
            stateField.setAccessible(true);
            var state = (LuaState) stateField.get(machine);
            var reporterField = LuaState.class.getDeclaredField("reportError");
            reporterField.setAccessible(true);
            var original = (LuaState.ErrorReporter) reporterField.get(state);
            LuaState.ErrorReporter reporter = (error, message) -> {
                observe(error); // allocation-free OOM flag, before any logging
                if (original != null && !(error instanceof OutOfMemoryError)) original.report(error, message);
            };
            reporterField.set(state, reporter);
            if (reporterField.get(state) != reporter) throw new IllegalStateException("error reporter was not installed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Cannot observe VM failures; expected CC:T 1.120.2 / Cobalt 0.9.9", e);
        }
    }
}
