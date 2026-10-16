// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import cctrun.engine.Trace;
import cctrun.engine.TracingTerminal;
import dan200.computercraft.api.lua.Coerced;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.core.apis.TermMethods;
import dan200.computercraft.core.terminal.Terminal;

import java.util.Set;

/**
 * A monitor of {@code blocksWide x blocksHigh} blocks. Terminal methods delegate to CC: Tweaked's own
 * {@link TermMethods}, so their behaviour (argument checks, colour handling, blit) is the game's.
 * Text scale and the resulting size use the formula from CC: Tweaked's ServerMonitor.
 */
public final class MonitorPeripheral extends DispatchPeripheral {
    // From dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity (v1.20.1-1.120.2).
    private static final double RENDER_BORDER = 2.0 / 16.0;
    private static final double RENDER_MARGIN = 0.5 / 16.0;
    private static final double RENDER_PIXEL_SCALE = 1.0 / 64.0;

    private final int blocksWide, blocksHigh;
    private final TracingTerminal terminal;
    private volatile int textScale = 2; // half-units, as in the game: 2 means scale 1.0

    public MonitorPeripheral(String name, int blocksWide, int blocksHigh, boolean advanced, Trace trace) {
        super("monitor", Set.of(), trace);
        this.blocksWide = blocksWide;
        this.blocksHigh = blocksHigh;
        this.terminal = TracingTerminal.create(width(blocksWide, 2), height(blocksHigh, 2), advanced, name, trace);

        var term = new TermMethods() {
            @Override
            public Terminal getTerminal() {
                return terminal;
            }
        };

        method(false, (c, ctx, a) -> {
            term.write(new Coerced<>(a.getStringCoerced(0)));
            return null;
        }, "write");
        method(false, (c, ctx, a) -> {
            term.scroll(a.getInt(0));
            return null;
        }, "scroll");
        method(false, (c, ctx, a) -> term.getCursorPos(), "getCursorPos");
        method(false, (c, ctx, a) -> {
            term.setCursorPos(a.getInt(0), a.getInt(1));
            return null;
        }, "setCursorPos");
        method(false, (c, ctx, a) -> values(term.getCursorBlink()), "getCursorBlink");
        method(false, (c, ctx, a) -> {
            term.setCursorBlink(a.getBoolean(0));
            return null;
        }, "setCursorBlink");
        method(false, (c, ctx, a) -> term.getSize(), "getSize");
        method(false, (c, ctx, a) -> {
            term.clear();
            return null;
        }, "clear");
        method(false, (c, ctx, a) -> {
            term.clearLine();
            return null;
        }, "clearLine");
        method(false, (c, ctx, a) -> values(term.getTextColour()), "getTextColour", "getTextColor");
        method(false, (c, ctx, a) -> {
            term.setTextColour(a.getInt(0));
            return null;
        }, "setTextColour", "setTextColor");
        method(false, (c, ctx, a) -> values(term.getBackgroundColour()), "getBackgroundColour", "getBackgroundColor");
        method(false, (c, ctx, a) -> {
            term.setBackgroundColour(a.getInt(0));
            return null;
        }, "setBackgroundColour", "setBackgroundColor");
        method(false, (c, ctx, a) -> values(term.getIsColour()), "isColour", "isColor");
        method(false, (c, ctx, a) -> {
            term.blit(a.getBytes(0), a.getBytes(1), a.getBytes(2));
            return null;
        }, "blit");
        method(false, (c, ctx, a) -> {
            term.setPaletteColour(a);
            return null;
        }, "setPaletteColour", "setPaletteColor");
        method(false, (c, ctx, a) -> term.getPaletteColour(a.getInt(0)), "getPaletteColour", "getPaletteColor");

        method(false, (c, ctx, a) -> {
            var scale = (int) (a.getFiniteDouble(0) * 2.0);
            if (scale < 1 || scale > 10) throw new LuaException("Expected number in range 0.5-5");
            if (setTextScale(scale)) c.queueEvent("monitor_resize", c.getAttachmentName());
            return null;
        }, "setTextScale");
        method(false, (c, ctx, a) -> values(textScale / 2.0), "getTextScale");
    }

    public TracingTerminal terminal() {
        return terminal;
    }

    /** Returns whether the terminal size changed. */
    private synchronized boolean setTextScale(int scale) {
        textScale = scale;
        var w = width(blocksWide, scale);
        var h = height(blocksHigh, scale);
        if (w == terminal.getWidth() && h == terminal.getHeight()) return false;
        terminal.resize(w, h);
        return true;
    }

    static int width(int blocks, int halfScale) {
        return (int) Math.max(Math.round((blocks - 2.0 * (RENDER_BORDER + RENDER_MARGIN)) / (halfScale * 0.5 * 6.0 * RENDER_PIXEL_SCALE)), 1.0);
    }

    static int height(int blocks, int halfScale) {
        return (int) Math.max(Math.round((blocks - 2.0 * (RENDER_BORDER + RENDER_MARGIN)) / (halfScale * 0.5 * 9.0 * RENDER_PIXEL_SCALE)), 1.0);
    }

    @Override
    public Object getTarget() {
        return terminal;
    }
}
