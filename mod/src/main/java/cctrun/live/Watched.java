// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.util.Arrays;
import java.util.Objects;

/**
 * One computer the session has seen: its trace and the state polled every server tick. Polling runs on the
 * server thread only.
 * <p>
 * Records: {@code computer_seen} (once), {@code power} (on/off changes), {@code moved} (turtles; position or
 * dimension changes), {@code label}, {@code redstone_output} (as in cct-run), {@code screen} (as in cct-run, plus
 * {@code fg}/{@code bg} colour rows), {@code computer_removed}.
 */
final class Watched {
    private static final ComputerSide[] SIDES = ComputerSide.values();

    private final ServerComputer computer;
    private final ComputerTrace trace;

    private boolean on;
    private String position;
    private String label;
    private final int[] redstone = new int[SIDES.length];
    private final int[] bundled = new int[SIDES.length];
    private Terminals.Snapshot screen;

    Watched(ServerComputer computer, ComputerTrace trace) {
        this.computer = computer;
        this.trace = trace;
        Arrays.fill(redstone, 0);
        Arrays.fill(bundled, 0);

        var data = describe(computer);
        trace.record("computer_seen", data);
        on = computer.isOn();
        position = positionOf(computer);
        label = computer.getLabel();
    }

    ServerComputer computer() {
        return computer;
    }

    ComputerTrace trace() {
        return trace;
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

        if (screens) pollScreen();
    }

    private void pollScreen() {
        var now = Terminals.snapshot(computer);
        var before = screen;
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
        screen = now;
        if (lines.size() == 0 && fg.size() == 0 && bg.size() == 0 && !cursorMoved && !resized) return;

        var data = new JsonObject();
        data.addProperty("source", "term");
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

    void removed() {
        trace.record("computer_removed", new JsonObject());
        trace.close();
    }
}
