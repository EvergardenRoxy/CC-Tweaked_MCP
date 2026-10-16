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

    volatile boolean finished;
    volatile boolean ok;
    volatile String phase;
    volatile String message;
    volatile String traceback;
    volatile List<String> screen = List.of();

    HarnessApi(Trace trace, TracingTerminal terminal) {
        this.trace = trace;
        this.terminal = terminal;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "__cctrun" };
    }

    @LuaFunction
    public final void done(IArguments args) throws LuaException {
        if (finished) return;
        ok = args.getBoolean(0);
        phase = args.optString(1, null);
        message = args.optString(2, null);
        traceback = args.optString(3, null);
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
