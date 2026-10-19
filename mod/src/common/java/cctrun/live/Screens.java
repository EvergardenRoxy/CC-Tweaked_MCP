// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;

/** Screen helpers for the endpoint: rebuilding a past screen from the trace, and finding colour regions. */
final class Screens {
    static final int MAX_REGIONS = 60;

    private Screens() {
    }

    /** The terminal as it was at {@code tick}: applies the trace's screen records (rows, fg, bg, size) in order. */
    static Terminals.Snapshot replay(ComputerTrace trace, long tick, String source) throws IOException {
        int width = 0, height = 0, cursorX = 1, cursorY = 1;
        String[] text = new String[0], fg = new String[0], bg = new String[0];
        var seen = false;
        try (var lines = trace.lines()) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                var line = it.next();
                if (line.isBlank() || !line.contains("\"kind\":\"screen\"")) continue;
                JsonObject record;
                try {
                    record = TraceRecords.parse(line);
                } catch (RuntimeException e) {
                    continue; // partial line from a concurrent write: not part of the screen history
                }
                if (!"screen".equals(record.get("kind").getAsString())) continue;
                if (record.get("tick").getAsLong() > tick) break;
                if (!source.equals(record.has("source") ? record.get("source").getAsString() : "term")) continue;
                // After a trace rotation the oldest records left can be row changes without the size they apply to.
                if (!record.has("size") && !seen) continue;
                if (record.has("size")) {
                    var size = record.getAsJsonArray("size");
                    width = size.get(0).getAsInt();
                    height = size.get(1).getAsInt();
                    text = filled(height, width, ' ');
                    fg = filled(height, width, '0');
                    bg = filled(height, width, 'f');
                }
                seen = true;
                apply(text, record.getAsJsonObject("lines"));
                apply(fg, record.getAsJsonObject("fg"));
                apply(bg, record.getAsJsonObject("bg"));
                if (record.has("cursor")) {
                    cursorX = record.getAsJsonArray("cursor").get(0).getAsInt();
                    cursorY = record.getAsJsonArray("cursor").get(1).getAsInt();
                }
            }
        }
        if (!seen) return null;
        return new Terminals.Snapshot(width, height, text, fg, bg, cursorX, cursorY, false);
    }

    private static String[] filled(int rows, int width, char c) {
        var out = new String[rows];
        for (var i = 0; i < rows; i++) out[i] = String.valueOf(c).repeat(width);
        return out;
    }

    private static void apply(String[] target, JsonObject rows) {
        if (rows == null) return;
        for (var entry : rows.entrySet()) {
            var row = Integer.parseInt(entry.getKey()) - 1;
            if (row >= 0 && row < target.length) {
                var value = entry.getValue().getAsString();
                int width = target[row].length();
                target[row] = value.length() >= width ? value.substring(0, width) : value + " ".repeat(width - value.length());
            }
        }
    }

    /**
     * Connected areas (4-neighbour) of one background colour that differs from the screen's most common background:
     * buttons, panels and bars of a GUI, also when their labels are drawn as pixel art. Largest first (by cells).
     */
    static JsonArray regions(Terminals.Snapshot s) {
        var counts = new HashMap<Character, Integer>();
        for (var row : s.bg()) for (var c : row.toCharArray()) counts.merge(c, 1, Integer::sum);
        char background = 'f';
        var best = -1;
        for (var e : counts.entrySet()) if (e.getValue() > best) {
            best = e.getValue();
            background = e.getKey();
        }

        var w = s.width();
        var h = s.height();
        var seen = new boolean[h][w];
        var found = new java.util.ArrayList<JsonObject>();
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var colour = s.bg()[y].charAt(x);
                if (seen[y][x] || colour == background) continue;
                int minX = x, maxX = x, minY = y, maxY = y, cells = 0;
                var queue = new ArrayDeque<int[]>();
                queue.add(new int[]{x, y});
                seen[y][x] = true;
                while (!queue.isEmpty()) {
                    var p = queue.poll();
                    cells++;
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                    for (var d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || seen[ny][nx] || s.bg()[ny].charAt(nx) != colour) continue;
                        seen[ny][nx] = true;
                        queue.add(new int[]{nx, ny});
                    }
                }
                var region = new JsonObject();
                region.addProperty("bg", String.valueOf(colour));
                region.add("from", pair(minX + 1, minY + 1));
                region.add("to", pair(maxX + 1, maxY + 1));
                region.add("center", pair((minX + maxX) / 2 + 1, (minY + maxY) / 2 + 1));
                var label = new StringBuilder();
                for (var yy = minY; yy <= maxY; yy++) {
                    var part = s.text()[yy].substring(minX, maxX + 1).strip();
                    if (!part.isEmpty()) label.append(label.length() > 0 ? " | " : "").append(part);
                }
                region.addProperty("text", label.toString());
                var labels = labels(s, minX, minY, maxX, maxY);
                if (labels.size() > 0) region.add("labels", labels);
                found.add(region);
            }
        }
        found.sort((a, b) -> Integer.compare(area(b), area(a)));
        var out = new JsonArray();
        for (var i = 0; i < Math.min(found.size(), MAX_REGIONS); i++) out.add(found.get(i));
        return out;
    }

    /**
     * Blocks of drawn content inside a region (any non-space character, including pixel-art glyphs), joined across
     * rows when they overlap: the label of a button sits inside its card even when both share one background.
     */
    private static JsonArray labels(Terminals.Snapshot s, int minX, int minY, int maxX, int maxY) {
        var boxes = new java.util.ArrayList<int[]>(); // x1, y1, x2, y2
        for (var y = minY; y <= maxY; y++) {
            var row = s.text()[y];
            var x = minX;
            while (x <= maxX) {
                if (row.charAt(x) == ' ') {
                    x++;
                    continue;
                }
                var start = x;
                // A run allows single spaces inside (words, glyph gaps).
                while (x <= maxX && (row.charAt(x) != ' ' || (x + 1 <= maxX && row.charAt(x + 1) != ' '))) x++;
                var end = x - 1;
                int[] merged = null;
                for (var box : boxes) {
                    if (box[3] == y - 1 && start <= box[2] + 1 && end >= box[0] - 1) {
                        merged = box;
                        break;
                    }
                }
                if (merged != null) {
                    merged[0] = Math.min(merged[0], start);
                    merged[2] = Math.max(merged[2], end);
                    merged[3] = y;
                } else {
                    boxes.add(new int[]{start, y, end, y});
                }
            }
        }
        var out = new JsonArray();
        for (var box : boxes) {
            if (out.size() >= 12) break;
            var label = new JsonObject();
            label.add("from", pair(box[0] + 1, box[1] + 1));
            label.add("to", pair(box[2] + 1, box[3] + 1));
            label.add("center", pair((box[0] + box[2]) / 2 + 1, (box[1] + box[3]) / 2 + 1));
            out.add(label);
        }
        return out;
    }

    private static int area(JsonObject region) {
        var from = region.getAsJsonArray("from");
        var to = region.getAsJsonArray("to");
        return (to.get(0).getAsInt() - from.get(0).getAsInt() + 1) * (to.get(1).getAsInt() - from.get(1).getAsInt() + 1);
    }

    /** Text rows without trailing spaces, and without trailing empty rows (row numbers stay as indexes + 1). */
    static JsonArray compactLines(String[] rows) {
        var last = rows.length - 1;
        while (last >= 0 && rows[last].isBlank()) last--;
        var out = new JsonArray();
        for (var i = 0; i <= last; i++) out.add(rows[i].stripTrailing());
        return out;
    }

    private static JsonArray pair(int a, int b) {
        var out = new JsonArray();
        out.add(a);
        out.add(b);
        return out;
    }
}
