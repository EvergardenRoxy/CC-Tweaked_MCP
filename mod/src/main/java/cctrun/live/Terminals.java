// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.lang.reflect.Field;

/** Reads a computer's terminal (text and colours). */
final class Terminals {
    /** A copy of a terminal; colours are the terminal's own blit characters (0-9a-f). Cursor is 1-based. */
    record Snapshot(int width, int height, String[] text, String[] fg, String[] bg, int cursorX, int cursorY, boolean blink) {
    }

    private static final Field TERMINAL;

    static {
        try {
            // ServerComputer keeps its terminal private; getTerminalState() only builds a compressed network packet.
            TERMINAL = ServerComputer.class.getDeclaredField("terminal");
            TERMINAL.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("cct-live does not match this CC: Tweaked version (ServerComputer.terminal)", e);
        }
    }

    private Terminals() {
    }

    static Terminal of(ServerComputer computer) {
        try {
            return (Terminal) TERMINAL.get(computer);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Snapshot snapshot(ServerComputer computer) {
        return snapshot(of(computer));
    }

    /** Monitors attached directly to the computer, by side name (wired-network monitors are not included). */
    static java.util.Map<String, Terminal> monitors(ServerComputer computer) {
        var out = new java.util.TreeMap<String, Terminal>();
        for (var side : dan200.computercraft.core.computer.ComputerSide.values()) {
            if (!(computer.getPeripheral(side) instanceof dan200.computercraft.shared.peripheral.monitor.MonitorPeripheral monitor)) continue;
            try {
                var terminal = monitor.getTerminal();
                if (terminal != null) out.put(side.getName(), terminal);
            } catch (dan200.computercraft.api.lua.LuaException e) {
                // Monitor not formed yet (just placed or being rebuilt).
            }
        }
        return out;
    }

    /** "term" for the computer's own terminal, else a side with a monitor; null if there is none there. */
    static Terminal source(ServerComputer computer, String source) {
        if (source == null || source.equals("term")) return of(computer);
        return monitors(computer).get(source);
    }

    static Snapshot snapshot(Terminal terminal) {
        // Terminal's writers are synchronised on the terminal, so this sees a consistent screen.
        synchronized (terminal) {
            var w = terminal.getWidth();
            var h = terminal.getHeight();
            var text = new String[h];
            var fg = new String[h];
            var bg = new String[h];
            for (var y = 0; y < h; y++) {
                text[y] = terminal.getLine(y).toString();
                fg[y] = terminal.getTextColourLine(y).toString();
                bg[y] = terminal.getBackgroundColourLine(y).toString();
            }
            return new Snapshot(w, h, text, fg, bg, terminal.getCursorX() + 1, terminal.getCursorY() + 1, terminal.getCursorBlink());
        }
    }
}
