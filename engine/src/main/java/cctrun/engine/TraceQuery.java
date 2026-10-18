// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads a run directory back: filtered trace records and the screen at a given tick. */
public final class TraceQuery {
    /**
     * Filter for {@link #records}. Null fields match everything. {@code contains}: case-insensitive text in a record's
     * url, path, program, text, event name, peripheral, method or arguments (as cct-live's trace filter).
     */
    public record Filter(Set<String> kinds, Integer fromTick, Integer toTick, String peripheral, String source, String contains, int offset, int limit) {
        public Filter(Set<String> kinds, Integer fromTick, Integer toTick, String peripheral, String source, int offset, int limit) {
            this(kinds, fromTick, toTick, peripheral, source, null, offset, limit);
        }
    }

    private TraceQuery() {
    }

    /**
     * Result of {@link #read}: the matching records, plus how many lines could not be parsed. A run whose JVM was
     * killed can end with a half-written JSON line; readers skip it instead of failing the whole query.
     */
    public record Result(List<JsonObject> records, int skipped) {
    }

    /** Filtered records plus the number of malformed lines that were skipped. */
    public static Result read(Path runDir, Filter filter) throws IOException {
        var out = new ArrayList<JsonObject>();
        var matched = 0;
        var skipped = 0;
        try (var lines = Files.lines(runDir.resolve("trace.jsonl"), StandardCharsets.UTF_8)) {
            for (var it = lines.iterator(); it.hasNext() && out.size() < filter.limit(); ) {
                var line = it.next();
                JsonObject record;
                try {
                    record = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    skipped++;
                    continue;
                }
                if (!matches(record, filter)) continue;
                if (matched++ < filter.offset()) continue;
                out.add(record);
            }
        }
        return new Result(out, skipped);
    }

    public static List<JsonObject> records(Path runDir, Filter filter) throws IOException {
        return read(runDir, filter).records();
    }

    private static boolean matches(JsonObject record, Filter filter) {
        var tick = record.get("tick").getAsInt();
        if (filter.fromTick() != null && tick < filter.fromTick()) return false;
        if (filter.toTick() != null && tick > filter.toTick()) return false;
        if (filter.kinds() != null && !filter.kinds().contains(record.get("kind").getAsString())) return false;
        if (filter.peripheral() != null && !(record.has("peripheral") && filter.peripheral().equals(record.get("peripheral").getAsString()))) return false;
        if (filter.source() != null && !(record.has("source") && filter.source().equals(record.get("source").getAsString()))) return false;
        if (filter.contains() != null && !mentions(record, filter.contains().toLowerCase(java.util.Locale.ROOT))) return false;
        return true;
    }

    private static boolean mentions(JsonObject record, String needle) {
        for (var key : new String[]{"url", "path", "program", "text", "name", "peripheral", "method", "message"}) {
            var v = record.get(key);
            if (v != null && v.isJsonPrimitive() && v.getAsString().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        }
        return mentions(record.get("args"), needle) || mentions(record.get("result"), needle) || mentions(record.get("lines"), needle);
    }

    /** Strings anywhere in arguments, results or screen rows. */
    private static boolean mentions(com.google.gson.JsonElement value, String needle) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonPrimitive()) return value.getAsString().toLowerCase(java.util.Locale.ROOT).contains(needle);
        if (value.isJsonArray()) {
            for (var v : value.getAsJsonArray()) if (mentions(v, needle)) return true;
            return false;
        }
        for (var e : value.getAsJsonObject().entrySet()) if (mentions(e.getValue(), needle)) return true;
        return false;
    }

    /** The screen of {@code source} ("term" or a monitor name) as it was at the end of {@code tick} (null: last). */
    public static ScreenAt screenAt(Path runDir, String source, Integer tick) throws IOException {
        var lines = new ArrayList<String>();
        int width = 0, height = 0, cursorX = 1, cursorY = 1, atTick = -1;
        var found = false;
        try (var stream = Files.lines(runDir.resolve("trace.jsonl"), StandardCharsets.UTF_8)) {
            for (var it = stream.iterator(); it.hasNext(); ) {
                JsonObject record;
                try {
                    record = JsonParser.parseString(it.next()).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue; // half-written last line of a killed run
                }
                if (!record.get("kind").getAsString().equals("screen")) continue;
                if (!record.get("source").getAsString().equals(source)) continue;
                var recordTick = record.get("tick").getAsInt();
                if (tick != null && recordTick > tick) break;
                found = true;
                atTick = recordTick;
                if (record.has("size")) {
                    var size = record.getAsJsonArray("size");
                    width = size.get(0).getAsInt();
                    height = size.get(1).getAsInt();
                    while (lines.size() < height) lines.add("");
                    while (lines.size() > height) lines.remove(lines.size() - 1);
                }
                for (var entry : record.getAsJsonObject("lines").entrySet()) {
                    var y = Integer.parseInt(entry.getKey()) - 1;
                    while (lines.size() <= y) lines.add("");
                    lines.set(y, entry.getValue().getAsString());
                }
                var cursor = record.getAsJsonArray("cursor");
                cursorX = cursor.get(0).getAsInt();
                cursorY = cursor.get(1).getAsInt();
            }
        }
        if (!found) return null;
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return new ScreenAt(source, atTick, width, height, cursorX, cursorY, lines);
    }

    /** A reconstructed screen. {@code tick} is the tick of the last change at or before the requested tick. */
    public record ScreenAt(String source, int tick, int width, int height, int cursorX, int cursorY, List<String> lines) {
    }
}
