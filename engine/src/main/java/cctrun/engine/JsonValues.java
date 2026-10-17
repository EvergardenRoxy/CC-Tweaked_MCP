// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/** Converts values crossing the Lua/Java boundary into bounded JSON for the trace. */
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
        if (value instanceof String s) return new JsonPrimitive(truncate(asLua(s)));
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

    /** A fixture's JSON value as a Lua value: numbers are doubles, arrays are 1-based tables, objects tables. */
    public static Object toLua(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        if (value.isJsonArray()) {
            var out = new java.util.HashMap<Object, Object>();
            var array = value.getAsJsonArray();
            for (var i = 0; i < array.size(); i++) out.put((double) (i + 1), toLua(array.get(i)));
            return out;
        }
        var out = new java.util.HashMap<Object, Object>();
        for (var entry : value.getAsJsonObject().entrySet()) out.put(entry.getKey(), toLua(entry.getValue()));
        return out;
    }
}
