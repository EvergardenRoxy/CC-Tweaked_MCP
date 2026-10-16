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
    static final int MAX_STRING = 200;
    static final int MAX_ENTRIES = 50;
    static final int MAX_DEPTH = 4;

    private JsonValues() {
    }

    public static JsonArray array(Object[] values) {
        var out = new JsonArray();
        if (values != null) for (var value : values) out.add(of(value, 0));
        return out;
    }

    public static JsonElement of(Object value) {
        return of(value, 0);
    }

    private static JsonElement of(Object value, int depth) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) {
            var d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) d);
            if (Double.isNaN(d) || Double.isInfinite(d)) return new JsonPrimitive(Double.toString(d));
            return new JsonPrimitive(d);
        }
        if (value instanceof String s) return new JsonPrimitive(truncate(s));
        if (value instanceof ByteBuffer b) {
            var copy = b.duplicate();
            var bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return new JsonPrimitive(truncate(new String(bytes, StandardCharsets.ISO_8859_1)));
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
                out.add(keyOf(entry.getKey()), of(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> list) return arrayOf(list.toArray(), depth);
        if (value instanceof Object[] list) return arrayOf(list, depth);
        return new JsonPrimitive(truncate(String.valueOf(value)));
    }

    private static JsonArray arrayOf(Object[] values, int depth) {
        var out = new JsonArray();
        for (var i = 0; i < values.length; i++) {
            if (i >= MAX_ENTRIES) {
                out.add("... " + (values.length - MAX_ENTRIES) + " more");
                break;
            }
            out.add(of(values[i], depth + 1));
        }
        return out;
    }

    private static String keyOf(Object key) {
        if (key instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return Long.toString(n.longValue());
        return String.valueOf(key);
    }

    static String truncate(String s) {
        return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "...(" + s.length() + " chars)";
    }
}
