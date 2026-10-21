// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NetworkProtocolTest {
    @Test void chunksReassembleUnicodeEvenAcrossUtf8Boundaries() {
        var id = UUID.randomUUID();
        var epoch = UUID.randomUUID();
        var data = new JsonObject();
        data.addProperty("text", "你好🦊".repeat(8000));
        var frames = NetworkProtocol.frames(id, epoch, new NetworkProtocol.Reply(200, data));
        assertTrue(frames.size() > 1);
        var assembly = new NetworkProtocol.Assembly();
        NetworkProtocol.Reply result = null;
        for (var frame : frames) {
            assertTrue(frame.data().length <= NetworkProtocol.CHUNK_BYTES);
            result = assembly.accept(frame, epoch);
        }
        assertNotNull(result);
        assertEquals(data, result.data());
    }

    @Test void oversizedResponseBecomesBoundedError() {
        var epoch = UUID.randomUUID();
        var data = new JsonObject();
        data.addProperty("text", "x".repeat(NetworkProtocol.MAX_RESPONSE_BYTES));
        var frames = NetworkProtocol.frames(UUID.randomUUID(), epoch, new NetworkProtocol.Reply(200, data));
        assertEquals(1, frames.size());
        assertEquals(413, new NetworkProtocol.Assembly().accept(frames.get(0), epoch).status());
    }

    @Test void rejectsOutOfOrderDuplicateStaleAndOversizedFrames() {
        var epoch = UUID.randomUUID();
        var data = new JsonObject();
        data.addProperty("text", "x".repeat(20000));
        var frames = NetworkProtocol.frames(UUID.randomUUID(), epoch, new NetworkProtocol.Reply(200, data));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProtocol.Assembly().accept(frames.get(1), epoch));
        var assembly = new NetworkProtocol.Assembly();
        assertNull(assembly.accept(frames.get(0), epoch));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(frames.get(0), epoch));
        var small = NetworkProtocol.frames(UUID.randomUUID(), epoch, NetworkProtocol.Reply.error(403, "denied")).get(0);
        assertThrows(IllegalArgumentException.class, () -> new NetworkProtocol.Assembly().accept(small, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProtocol.Frame(UUID.randomUUID(), 0, 999, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProtocol.Frame(UUID.randomUUID(), 0, 1, new byte[NetworkProtocol.CHUNK_BYTES + 1]));
    }

    @Test void handshakeRequiresBothVersionAndTheReadOnlyCapabilities() {
        var data = JsonParser.parseString("""
            {"protocol":3,"read_only":true,"capabilities":["computers","status","screen","session_open","session_heartbeat","session_close","control_acquire","control_status","control_release","menu_grant"]}
            """).getAsJsonObject();
        assertEquals(200, NetworkProtocol.negotiate(new NetworkProtocol.Reply(200, data)).status());
        data.addProperty("protocol", 1);
        assertEquals(426, NetworkProtocol.negotiate(new NetworkProtocol.Reply(200, data)).status());
        assertEquals(426, NetworkProtocol.negotiate(new NetworkProtocol.Reply(200, new JsonObject())).status());
        assertEquals(403, NetworkProtocol.negotiate(NetworkProtocol.Reply.error(403, "disabled")).status());
    }
}
