// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;

import com.google.gson.JsonElement;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;

/** Input limits, independent of the much smaller trace display limits. Traversal uses O(depth) memory. */
public final class JsonLimits {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DEPTH = 128;
    public static final int MAX_NODES = 100_000;
    private JsonLimits() {}

    private record Node(JsonElement value, String path) {}
    private record Frame(Iterator<Node> children, int depth) {}

    /** Reject before calling recursive Gson or Lua conversion operations, including for programmatic trees. */
    public static void check(JsonElement root) {
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(java.util.List.of(new Node(root, "$" )).iterator(), 0));
        int nodes = 0;
        while (!stack.isEmpty()) {
            var frame = stack.peek();
            if (!frame.children().hasNext()) { stack.pop(); continue; }
            var node = frame.children().next();
            if (++nodes > MAX_NODES) throw new IllegalArgumentException(node.path() + ": input exceeds " + MAX_NODES + " nodes");
            var value = node.value();
            if (value == null || value.isJsonNull() || value.isJsonPrimitive()) continue;
            if (frame.depth() >= MAX_DEPTH) throw new IllegalArgumentException(node.path() + ": input exceeds depth " + MAX_DEPTH);
            Iterator<Node> children;
            if (value.isJsonArray()) {
                var iterator = value.getAsJsonArray().iterator();
                children = new Iterator<>() {
                    int index;
                    public boolean hasNext() { return iterator.hasNext(); }
                    public Node next() { return new Node(iterator.next(), node.path() + "[" + index++ + "]"); }
                };
            } else {
                Iterator<Map.Entry<String, JsonElement>> iterator = value.getAsJsonObject().entrySet().iterator();
                children = new Iterator<>() {
                    public boolean hasNext() { return iterator.hasNext(); }
                    public Node next() {
                        var entry = iterator.next();
                        var key = entry.getKey();
                        return new Node(entry.getValue(), node.path() + "." + (key.length() <= 80 ? key : key.substring(0, 80) + "..."));
                    }
                };
            }
            stack.push(new Frame(children, frame.depth() + 1));
        }
    }
}
