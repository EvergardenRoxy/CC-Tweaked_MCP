package cctrun.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.core.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A terminal (the computer's own screen or a monitor) that records:
 * <ul>
 *   <li>{@code output}: every line that scrolls off the top, so nothing printed is lost;</li>
 *   <li>{@code screen}: the lines that changed since the previous snapshot (taken once per tick by the runner).</li>
 * </ul>
 */
public final class TracingTerminal extends Terminal {
    private final String source;
    private final Trace trace;
    private final AtomicBoolean dirty;
    private List<String> lastLines = List.of();
    private int lastWidth = -1, lastHeight = -1;
    private volatile List<String> beforeReset;

    public static TracingTerminal create(int width, int height, boolean colour, String source, Trace trace) {
        return new TracingTerminal(width, height, colour, source, trace, new AtomicBoolean(true));
    }

    private TracingTerminal(int width, int height, boolean colour, String source, Trace trace, AtomicBoolean dirty) {
        super(width, height, colour, () -> dirty.set(true));
        this.source = source;
        this.trace = trace;
        this.dirty = dirty;
    }

    public String source() {
        return source;
    }

    @Override
    public synchronized void scroll(int yDiff) {
        if (yDiff > 0) {
            for (var y = 0; y < Math.min(yDiff, getHeight()); y++) {
                var data = new JsonObject();
                data.addProperty("source", source);
                data.addProperty("text", getLine(y).toString().stripTrailing());
                trace.record("output", data);
            }
        }
        super.scroll(yDiff);
    }

    /**
     * CC: Tweaked resets the terminal when the computer shuts down or reboots. Record the screen first, so a
     * program that prints and then shuts down in the same tick does not lose its output.
     */
    @Override
    public synchronized void reset() {
        var current = lines();
        if (!current.isEmpty()) {
            beforeReset = current;
            snapshot(true);
        }
        super.reset();
    }

    /** The last non-blank screen seen before a reset, or null. */
    public List<String> beforeReset() {
        return beforeReset;
    }

    /** Current screen, trailing spaces and trailing blank lines removed. */
    public synchronized List<String> lines() {
        var lines = new ArrayList<String>(getHeight());
        for (var y = 0; y < getHeight(); y++) lines.add(getLine(y).toString().stripTrailing());
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /** Full screen (all rows, including blank ones), trailing spaces removed. */
    synchronized List<String> allLines() {
        var lines = new ArrayList<String>(getHeight());
        for (var y = 0; y < getHeight(); y++) lines.add(getLine(y).toString().stripTrailing());
        return lines;
    }

    /** Record a {@code screen} diff if anything changed since the last call. */
    void snapshot() {
        snapshot(false);
    }

    private synchronized void snapshot(boolean force) {
        if (!dirty.getAndSet(false) && !force) return;
        var current = allLines();
        int width = getWidth(), height = getHeight(), cursorX = getCursorX(), cursorY = getCursorY();

        var changed = new JsonObject();
        for (var y = 0; y < current.size(); y++) {
            var before = y < lastLines.size() ? lastLines.get(y) : "";
            if (!current.get(y).equals(before)) changed.addProperty(Integer.toString(y + 1), current.get(y));
        }
        var resized = width != lastWidth || height != lastHeight;
        if (changed.size() == 0 && !resized) return;

        var data = new JsonObject();
        data.addProperty("source", source);
        if (resized) {
            var size = new JsonArray();
            size.add(width);
            size.add(height);
            data.add("size", size);
        }
        data.add("lines", changed);
        var cursor = new JsonArray();
        cursor.add(cursorX + 1);
        cursor.add(cursorY + 1);
        data.add("cursor", cursor);
        trace.record("screen", data);

        lastLines = current;
        lastWidth = width;
        lastHeight = height;
    }
}
