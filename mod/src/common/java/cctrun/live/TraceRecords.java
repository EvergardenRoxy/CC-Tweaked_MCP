// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import com.google.gson.*;

/** Validate fields before query/replay code touches them; malformed JSON objects count as skipped lines too. */
final class TraceRecords {
    private TraceRecords() {}
    static JsonObject parse(String text) {
        var record = JsonParser.parseString(text).getAsJsonObject();
        checkDepth(record);
        for (String key : new String[]{"seq", "tick"}) {
            var value = record.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || value.getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException("invalid " + key);
        }
        var kind = record.get("kind");
        if (kind == null || !kind.isJsonPrimitive() || !kind.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid kind");
        if ("screen".equals(kind.getAsString())) {
            if (record.has("size")) {
                var size=record.getAsJsonArray("size");
                if(size.size()!=2)throw new IllegalArgumentException("invalid screen size");
                for(var n:size)if(n.getAsBigDecimal().intValueExact()<1 || n.getAsInt()>1024)throw new IllegalArgumentException("invalid screen size");
            }
            if(record.has("cursor")) {
                var cursor=record.getAsJsonArray("cursor");
                if(cursor.size()!=2)throw new IllegalArgumentException("invalid cursor");
                for(var n:cursor)n.getAsBigDecimal().intValueExact();
            }
            for(String key:new String[]{"lines","fg","bg"})if(record.has(key)) {
                for(var e:record.getAsJsonObject(key).entrySet()) {
                    int row=Integer.parseInt(e.getKey());
                    if(row<1 || row>1024 || !e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("invalid screen row");
                }
            }
        }
        return record;
    }
    static void checkDepth(JsonElement value) {
        var stack=new java.util.ArrayDeque<java.util.Iterator<JsonElement>>();
        stack.push(java.util.List.of(value).iterator());
        int count=0;
        while(!stack.isEmpty()) {
            var iterator=stack.peek();
            if(!iterator.hasNext()){stack.pop();continue;}
            var node=iterator.next();
            if(++count>100_000)throw new IllegalArgumentException("JSON exceeds 100000 nodes");
            if(node.isJsonPrimitive() || node.isJsonNull())continue;
            if(stack.size()>128)throw new IllegalArgumentException("JSON exceeds depth 128");
            stack.push(node.isJsonArray()?node.getAsJsonArray().iterator():node.getAsJsonObject().asMap().values().iterator());
        }
    }
}
