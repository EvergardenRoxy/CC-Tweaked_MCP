// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.util.Objects;

/**
 * One AI attachment to one computer: the trace it writes and the state polled every server tick while it lasts.
 * Created and polled on the server thread; the lease is renewed by every request for this computer.
 * <p>
 * Records written here: {@code attach}, {@code computer_seen}, {@code power} (on/off changes), {@code moved}
 * (turtles; position or dimension changes), {@code label}, {@code redstone_output} (as in cct-run), {@code screen}
 * (as in cct-run, plus {@code fg}/{@code bg} colour rows; source "term" or the side of a directly attached monitor),
 * {@code monitor} (a monitor appeared or went away), {@code detach}.
 */
final class Attachment {
    private static final ComputerSide[] SIDES = ComputerSide.values();

    private final ServerComputer computer;
    private final ComputerTrace trace;
    private volatile long leaseMillis;
    private volatile long expiresAt;

    private boolean on;
    private String position;
    private String label;
    private final int[] redstone = new int[SIDES.length];
    private final int[] bundled = new int[SIDES.length];
    /** Last screen per source: "term" and the sides with a monitor. */
    private final java.util.Map<String, Terminals.Snapshot> screens = new java.util.HashMap<>();

    Attachment(ServerComputer computer, ComputerTrace trace, long leaseMillis) {
        this.computer = computer;
        this.trace = trace;
        this.leaseMillis = leaseMillis;
        renew();
        on = computer.isOn();
        position = positionOf(computer);
        label = computer.getLabel();
        // Outputs already set before the attachment are reported once, as changes from 0.
    }

    ServerComputer computer() {
        return computer;
    }

    ComputerTrace trace() {
        return trace;
    }

    long leaseSeconds() {
        return leaseMillis / 1000;
    }

    /** A different lease length for this attachment (cct_live_attach lease_seconds), then renew. */
    void lease(long seconds) {
        leaseMillis = seconds * 1000L;
        renew();
    }

    void renew() {
        expiresAt = System.currentTimeMillis() + leaseMillis;
    }

    boolean expired(long now) {
        return now >= expiresAt;
    }

    long secondsLeft() {
        return Math.max(0, (expiresAt - System.currentTimeMillis() + 999) / 1000);
    }

    static JsonObject describe(ServerComputer computer) {
        var data = new JsonObject();
        data.addProperty("id", computer.getID());
        data.addProperty("instance", computer.getInstanceUUID().toString());
        data.addProperty("label", computer.getLabel());
        data.addProperty("family", computer.getFamily().name().toLowerCase());
        data.addProperty("on", computer.isOn());
        data.addProperty("dimension", computer.getLevel().dimension().location().toString());
        var pos = computer.getPosition();
        var xyz = new JsonArray();
        xyz.add(pos.getX());
        xyz.add(pos.getY());
        xyz.add(pos.getZ());
        data.add("pos", xyz);
        return data;
    }

    private static String positionOf(ServerComputer computer) {
        var pos = computer.getPosition();
        return computer.getLevel().dimension().location() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    void poll(boolean screens) {
        var nowOn = computer.isOn();
        if (nowOn != on) {
            on = nowOn;
            var data = new JsonObject();
            data.addProperty("on", nowOn);
            trace.record("power", data);
        }

        var nowPosition = positionOf(computer);
        if (!nowPosition.equals(position)) {
            position = nowPosition;
            var data = describe(computer);
            data.remove("on");
            data.remove("instance");
            trace.record("moved", data);
        }

        var nowLabel = computer.getLabel();
        if (!Objects.equals(nowLabel, label)) {
            label = nowLabel;
            var data = new JsonObject();
            data.addProperty("label", nowLabel);
            trace.record("label", data);
        }

        for (var i = 0; i < SIDES.length; i++) {
            var level = computer.getRedstoneOutput(SIDES[i]);
            var bundle = computer.getBundledRedstoneOutput(SIDES[i]);
            if (level == redstone[i] && bundle == bundled[i]) continue;
            redstone[i] = level;
            bundled[i] = bundle;
            var data = new JsonObject();
            data.addProperty("side", SIDES[i].getName());
            data.addProperty("level", level);
            data.addProperty("bundled", bundle);
            trace.record("redstone_output", data);
        }

        if (screens) {
            pollScreen("term", Terminals.snapshot(computer));
            var monitors = Terminals.monitors(computer);
            for (var entry : monitors.entrySet()) pollScreen(entry.getKey(), Terminals.snapshot(entry.getValue()));
            for (var it = this.screens.keySet().iterator(); it.hasNext(); ) {
                var source = it.next();
                if (source.equals("term") || monitors.containsKey(source)) continue;
                it.remove();
                var data = new JsonObject();
                data.addProperty("source", source);
                data.addProperty("present", false);
                trace.record("monitor", data);
            }
        }
    }

    private void pollScreen(String source, Terminals.Snapshot now) {
        var before = screens.get(source);
        if (before == null && !source.equals("term")) {
            var data = new JsonObject();
            data.addProperty("source", source);
            data.addProperty("present", true);
            trace.record("monitor", data);
        }
        var resized = before == null || before.width() != now.width() || before.height() != now.height();
        var lines = new JsonObject();
        var fg = new JsonObject();
        var bg = new JsonObject();
        for (var y = 0; y < now.height(); y++) {
            var row = Integer.toString(y + 1);
            if (resized || !now.text()[y].equals(before.text()[y])) lines.addProperty(row, now.text()[y]);
            if (resized || !now.fg()[y].equals(before.fg()[y])) fg.addProperty(row, now.fg()[y]);
            if (resized || !now.bg()[y].equals(before.bg()[y])) bg.addProperty(row, now.bg()[y]);
        }
        var cursorMoved = before != null && (before.cursorX() != now.cursorX() || before.cursorY() != now.cursorY());
        screens.put(source, now);
        if (lines.size() == 0 && fg.size() == 0 && bg.size() == 0 && !cursorMoved && !resized) return;

        var data = new JsonObject();
        data.addProperty("source", source);
        data.add("lines", lines);
        if (fg.size() > 0) data.add("fg", fg);
        if (bg.size() > 0) data.add("bg", bg);
        var cursor = new JsonArray();
        cursor.add(now.cursorX());
        cursor.add(now.cursorY());
        data.add("cursor", cursor);
        if (resized) {
            var size = new JsonArray();
            size.add(now.width());
            size.add(now.height());
            data.add("size", size);
        }
        trace.record("screen", data);
    }

    void end(String reason) {
        var data = new JsonObject();
        data.addProperty("reason", reason);
        trace.record("detach", data);
        trace.flush();
    }
}
