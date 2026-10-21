// SPDX-License-Identifier: MPL-2.0
package cctrun.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LiveNetworkSessionsTest {
    @TempDir Path dir;
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void twoMcpConnectionsUseIndependentSessionsAndReleaseOnClose() throws Exception {
        var sessions = new HashSet<String>();
        var holder = new AtomicReference<String>();
        var lease = UUID.randomUUID().toString();
        var fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String agent = exchange.getRequestHeaders().getFirst("X-CCT-Session");
            int code = 200; String body = "{\"ok\":true}";
            if (!"Bearer local-test".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { code = 401; }
            else if (path.endsWith("/sessions/open")) {
                assertNotNull(exchange.getRequestHeaders().getFirst("X-CCT-Open-Key"));
                agent = UUID.randomUUID().toString(); sessions.add(agent);
                body = "{\"agent_session_id\":\"" + agent + "\"}";
            } else if (!sessions.contains(agent)) code = 403;
            else if (path.endsWith("/control/acquire")) {
                if (holder.get() != null && !holder.get().equals(agent)) { code = 409; body = "{\"error\":\"held by another MCP session\"}"; }
                else { holder.set(agent); body = "{\"owned\":true,\"held\":true,\"lease_id\":\"" + lease + "\",\"lease_epoch\":1}"; }
            } else if (path.endsWith("/screen") || path.endsWith("/control/release")) {
                if (!Objects.equals(holder.get(), agent) || !lease.equals(exchange.getRequestHeaders().getFirst("X-CCT-Lease"))
                    || !"1".equals(exchange.getRequestHeaders().getFirst("X-CCT-Lease-Epoch"))) code = 409;
                else if (path.endsWith("/release")) holder.set(null);
            } else if (path.endsWith("/sessions/close")) { sessions.remove(agent); if (Objects.equals(holder.get(), agent)) holder.set(null); }
            var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        fake.start();
        try {
            var home = Files.createDirectories(dir.resolve("cct-live/client"));
            Files.writeString(home.resolve("token.txt"), "local-test");
            Files.writeString(home.resolve("endpoint.json"), "{\"transport\":\"minecraft-network\",\"session\":\"game-1\",\"url\":\"http://127.0.0.1:" + fake.getAddress().getPort() + "/\"}");
            try (var a = new LiveTools(dir, dir); var b = new LiveTools(dir, dir)) {
                var acquire = args("{\"computer\":1,\"op\":\"acquire\"}");
                a.call(LiveTools.CONTROL, acquire);
                var first = holder.get();
                assertThrows(McpTools.ToolError.class, () -> b.call(LiveTools.CONTROL, acquire));
                assertEquals(2, sessions.size());
                assertThrows(McpTools.ToolError.class, () -> b.call(LiveTools.SCREEN, args("{\"computer\":1}")));
                a.call(LiveTools.SCREEN, args("{\"computer\":1}"));
                a.call(LiveTools.CONTROL, args("{\"computer\":1,\"op\":\"release\"}"));
                b.call(LiveTools.CONTROL, acquire);
                assertNotEquals(first, holder.get());
            }
            assertNull(holder.get());
            assertTrue(sessions.isEmpty());
        } finally { fake.stop(0); }
    }

    @Test void heartbeatBelongsToMcpSessionAndFailureDropsOldLeaseCredentials() throws Exception {
        var endpoint = new LiveTools.Endpoint(URI.create("http://127.0.0.1:12345/"), "token", true, "bridge");
        var opens = new ArrayList<String>(); var heartbeats = new ArrayList<String>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        try (var sessions = new LiveNetworkSessions((ep, builder, timeout) -> {
            var request = builder.build(); var path = request.uri().getPath();
            if (path.endsWith("/open")) { var id = UUID.randomUUID().toString(); opens.add(id); return "{\"agent_session_id\":\"" + id + "\"}"; }
            if (path.endsWith("/heartbeat")) {
                heartbeats.add(request.headers().firstValue("X-CCT-Session").orElseThrow());
                if (fail.get()) throw new McpTools.ToolError("connection lost");
            }
            if (path.endsWith("/acquire")) return "{\"owned\":true,\"lease_id\":\"" + UUID.randomUUID() + "\",\"lease_epoch\":1}";
            return "{}";
        })) {
            sessions.control(endpoint, 1, "acquire");
            sessions.heartbeat();
            assertEquals(List.of(opens.get(0)), heartbeats);
            fail.set(true); sessions.heartbeat();
            var request = java.net.http.HttpRequest.newBuilder(endpoint.base().resolve("computers/1/screen")).GET();
            sessions.decorate(endpoint, request);
            assertEquals(2, opens.size());
            assertNotEquals(opens.get(0), request.build().headers().firstValue("X-CCT-Session").orElseThrow());
            assertTrue(request.build().headers().firstValue("X-CCT-Lease").isEmpty());
        }
    }

    @Test void discoveryRejectsRemoteEndpointBeforeSendingAnyToken() throws Exception {
        var home = Files.createDirectories(dir.resolve("cct-live/client"));
        Files.writeString(home.resolve("token.txt"), "must-stay-local");
        Files.writeString(home.resolve("endpoint.json"), "{\"transport\":\"minecraft-network\",\"url\":\"https://example.com/\"}");
        try (var live = new LiveTools(dir, dir)) {
            var failure = assertThrows(McpTools.ToolError.class, () -> live.call(LiveTools.COMPUTERS, new JsonObject()));
            assertTrue(failure.getMessage().contains("local HTTP"));
        }
    }
}
