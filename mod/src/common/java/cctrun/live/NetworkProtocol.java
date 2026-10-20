// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Loader-independent, bounded RPC framing. No Minecraft classes, paths or credentials on the wire. */
final class NetworkProtocol {
    static final int VERSION = 1;
    static final int MAX_REQUEST_CHARS = 4096;
    static final int CHUNK_BYTES = 16 * 1024;
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_CHUNKS = MAX_RESPONSE_BYTES / CHUNK_BYTES;
    static final int MAX_PENDING = 8;

    record Frame(UUID requestId, int index, int count, byte[] data) {
        Frame {
            if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count
                || data.length < 1 || data.length > CHUNK_BYTES) throw new IllegalArgumentException("invalid RPC frame");
        }
    }

    record Reply(int status, JsonObject data) {
        static Reply error(int status, String message) {
            var data = new JsonObject();
            data.addProperty("error", message);
            return new Reply(status, data);
        }
    }

    static Reply negotiate(Reply reply) {
        if (reply.status() != 200) return reply;
        try {
            var data = reply.data();
            if (data.get("protocol").getAsInt() == VERSION && data.get("read_only").getAsBoolean()
                && data.getAsJsonArray("capabilities").asList().stream().map(value -> value.getAsString()).toList()
                    .containsAll(List.of("computers", "status", "screen"))) return reply;
        } catch (RuntimeException ignored) { }
        return Reply.error(426, "incompatible cct-live server capabilities");
    }

    static JsonObject request(UUID id, UUID epoch, String operation, JsonObject arguments) {
        var out = new JsonObject();
        out.addProperty("request_id", id.toString());
        out.addProperty("epoch", epoch.toString());
        out.addProperty("operation", operation);
        out.add("arguments", arguments);
        return out;
    }

    static List<Frame> frames(UUID id, UUID epoch, Reply reply) {
        var out = new JsonObject();
        out.addProperty("epoch", epoch.toString());
        out.addProperty("status", reply.status());
        out.add("data", reply.data());
        var bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESPONSE_BYTES) return frames(id, epoch, Reply.error(413, "response too large; reduce the requested data"));
        var count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        var frames = new ArrayList<Frame>(count);
        for (int i = 0; i < count; i++) frames.add(new Frame(id, i, count,
            java.util.Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min(bytes.length, (i + 1) * CHUNK_BYTES))));
        return frames;
    }

    /** Ordered chunks only (the game connection is ordered). Reject duplicates and unbounded allocation. */
    static final class Assembly {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int count;
        private int next;

        Reply accept(Frame frame, UUID epoch) {
            if (next == 0) count = frame.count();
            if (frame.count() != count || frame.index() != next || bytes.size() + frame.data().length > MAX_RESPONSE_BYTES)
                throw new IllegalArgumentException("invalid RPC chunk sequence");
            bytes.writeBytes(frame.data());
            if (++next != count) return null;
            var object = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            if (!epoch.toString().equals(object.get("epoch").getAsString())) throw new IllegalArgumentException("stale RPC response");
            return new Reply(object.get("status").getAsInt(), object.getAsJsonObject("data"));
        }
    }

    private NetworkProtocol() {}
}
