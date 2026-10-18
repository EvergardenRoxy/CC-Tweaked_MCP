// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/** Copy of cctrun.engine.JsonValues (to be shared once engine and mod have a common module). Converts values crossing the Lua/Java boundary into bounded JSON for the trace. */
public final class JsonValues {
    /** Characters kept per string (config trace.max_string, set when a world opens). */
    static volatile int MAX_STRING = 200;
    static final int MAX_ENTRIES = 50;
    static final int MAX_DEPTH = 4;

    private JsonValues() {
    }

    public static JsonArray array(Object[] values) {
        return array(values, null);
    }

    /** As {@link #array(Object[])}; handle objects (files, HTTP responses) become their {@link Handles} entry. */
    public static JsonArray array(Object[] values, Handles handles) {
        var out = new JsonArray();
        if (values != null) for (var value : values) out.add(of(value, 0, handles));
        return out;
    }

    public static JsonElement of(Object value) {
        return of(value, 0, null);
    }

    private static JsonElement of(Object value, int depth, Handles handles) {
        if (value == null) return JsonNull.INSTANCE;
        if (handles != null) {
            var handle = handles.describe(value);
            if (handle != null) return handle;
        }
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) {
            var d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) d);
            if (Double.isNaN(d) || Double.isInfinite(d)) return new JsonPrimitive(Double.toString(d));
            return new JsonPrimitive(d);
        }
        if (value instanceof String s) return new JsonPrimitive(truncate(asLua(s)));
        if (value instanceof ByteBuffer b) {
            // Only the bytes that will be shown are copied: file and HTTP bodies can be large.
            var copy = b.duplicate();
            var length = copy.remaining();
            var bytes = new byte[Math.min(length, MAX_STRING)];
            copy.get(bytes);
            return new JsonPrimitive(preview(new String(bytes, StandardCharsets.ISO_8859_1), length));
        }
        if (value instanceof byte[] b) {
            return new JsonPrimitive(preview(new String(b, 0, Math.min(b.length, MAX_STRING), StandardCharsets.ISO_8859_1), b.length));
        }
        if (depth >= MAX_DEPTH) return new JsonPrimitive("<nested>");
        if (value instanceof Map<?, ?> map) {
            var out = new JsonObject();
            var n = 0;
            for (var entry : map.entrySet()) {
                if (n++ >= MAX_ENTRIES) {
                    out.addProperty("...", (map.size() - MAX_ENTRIES) + " more");
                    break;
                }
                out.add(keyOf(entry.getKey()), of(entry.getValue(), depth + 1, handles));
            }
            return out;
        }
        if (value instanceof Collection<?> list) return arrayOf(list.toArray(), depth, handles);
        if (value instanceof Object[] list) return arrayOf(list, depth, handles);
        return new JsonPrimitive(truncate(String.valueOf(value)));
    }

    private static JsonArray arrayOf(Object[] values, int depth, Handles handles) {
        var out = new JsonArray();
        for (var i = 0; i < values.length; i++) {
            if (i >= MAX_ENTRIES) {
                out.add("... " + (values.length - MAX_ENTRIES) + " more");
                break;
            }
            out.add(of(values[i], depth + 1, handles));
        }
        return out;
    }

    private static String keyOf(Object key) {
        if (key instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return Long.toString(n.longValue());
        return key instanceof String s ? asLua(s) : String.valueOf(key);
    }

    /**
     * A Java string as Lua receives it: Lua strings are bytes, and CC: Tweaked (Cobalt) turns every character above
     * U+00FF into '?'. So Chinese text from a peripheral or event (e.g. the chat box's message) is recorded as the
     * program sees it, not as the Java side had it.
     */
    static String asLua(String s) {
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                var out = s.toCharArray();
                for (var j = i; j < out.length; j++) if (out[j] > 0xFF) out[j] = '?';
                return new String(out);
            }
        }
        return s;
    }

    static String truncate(String s) {
        return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "...(" + s.length() + " chars)";
    }

    private static String preview(String head, int length) {
        return length <= MAX_STRING ? head : head + "...(" + length + " bytes)";
    }
}
