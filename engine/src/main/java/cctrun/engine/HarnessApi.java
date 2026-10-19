// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;

import java.util.List;

/**
 * Lua API {@code __cctrun}, used only by harness.lua (which removes it from {@code _G} before the
 * program starts). {@code done(ok, phase, message, traceback)} records the outcome and captures the
 * screen; shutting the computer down afterwards clears the terminal.
 */
public final class HarnessApi implements ILuaAPI {
    private final Trace trace;
    private final TracingTerminal terminal;
    private final String token;

    volatile boolean started;
    volatile boolean finished;
    volatile boolean ok;
    volatile String phase;
    volatile String message;
    volatile String traceback;
    volatile List<String> screen = List.of();

    HarnessApi(Trace trace, TracingTerminal terminal, String token) {
        this.trace = trace;
        this.terminal = terminal;
        this.token = token;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "__cctrun" };
    }

    /** Called by harness.lua right before the program's main chunk runs (after it compiled). */
    @LuaFunction
    public final void start() {
        if (started) return;
        started = true;
        trace.record("program_start", new JsonObject());
    }

    @LuaFunction
    public final synchronized void done(IArguments args) throws LuaException {
        if (!token.equals(args.getString(0))) throw new LuaException("invalid harness report token");
        if (finished) throw new LuaException("harness result already reported");
        ok = args.getBoolean(1);
        phase = args.optString(2, null);
        message = args.optString(3, null);
        traceback = args.optString(4, null);
        screen = terminal.lines();
        finished = true;

        var data = new JsonObject();
        data.addProperty("ok", ok);
        if (!ok) {
            data.addProperty("phase", phase);
            data.addProperty("message", message);
            data.addProperty("traceback", traceback);
        }
        if (ok) trace.record("program_end", data);
        else trace.note("program_end", data);
    }
}
