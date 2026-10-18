// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaFunction;

import java.util.Optional;

/**
 * The global {@code __cctlive}, one per computer, used only by the ROM script {@code rom/autorun/cctlive.lua}
 * (which takes it out of {@code _G} before any user program runs). It carries what only Lua can see to the Java
 * side, currently the text of {@code printError} calls: the shell prints a program's error message that way, so
 * this is how a crash such as {@code /startup.lua:911: module 'icons.User' not found} becomes a trace record.
 * <p>
 * {@link LiveMachine}'s factory finds this object among the VM's APIs and connects it to the VM's recorder; while
 * the computer is not attached (and recording mode is off) {@link #report} returns at once.
 */
final class BridgeApi implements ILuaAPI {
    private volatile LiveMachine.Recorder recorder;

    @Override
    public String[] getNames() {
        return new String[]{"__cctlive"};
    }

    void connect(LiveMachine.Recorder recorder) {
        this.recorder = recorder;
    }

    /** printError(...) was called: text is its arguments joined by tabs, program the shell's running program. */
    @LuaFunction
    public final void printedError(String text, Optional<String> program) {
        var r = recorder;
        if (r == null || !r.active()) return;
        var data = new JsonObject();
        data.addProperty("text", JsonValues.truncate(text));
        program.ifPresent(p -> data.addProperty("program", p));
        r.record("printed_error", data);
    }
}
