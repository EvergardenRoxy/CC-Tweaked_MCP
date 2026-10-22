// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real loopback HTTP, authentication and RPC lifecycle with an in-memory game transport. */
class NetworkBridgeTest {
    @TempDir Path home;

    private static final String CAPABILITIES = "{\"protocol\":4,\"control\":true,\"read_only\":false,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\",\"menu_grant\",\"files\",\"files_write\",\"event\",\"power\",\"peripheral\",\"snapshot\",\"trace\",\"body\"]}";

    /** Answers hello from the given replies in order (the last one repeats), and every other request with 200. */
    private static NetworkClient scripted(AtomicReference<NetworkClient> holder, List<String> operations, NetworkProtocol.Reply... hellos) {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        return new NetworkClient(json -> {
            var request = JsonParser.parseString(json).getAsJsonObject();
            var operation = request.get("operation").getAsString();
            operations.add(operation);
            var reply = operation.equals("hello") ? hellos[Math.min(count.getAndIncrement(), hellos.length - 1)]
                : new NetworkProtocol.Reply(200, JsonParser.parseString("{\"computers\":[]}").getAsJsonObject());
            for (var frame : NetworkProtocol.frames(UUID.fromString(request.get("request_id").getAsString()),
                UUID.fromString(request.get("epoch").getAsString()), reply)) holder.get().receive(frame);
        });
    }

    @Test void transientHandshakeFailureIsRetriedOnTheSameConnection() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var operations = Collections.synchronizedList(new ArrayList<String>());
        try (var client = scripted(holder, operations, NetworkProtocol.Reply.error(504, "Minecraft request timed out"),
            new NetworkProtocol.Reply(200, JsonParser.parseString(CAPABILITIES).getAsJsonObject()))) {
            holder.set(client);
            client.start(home);
            assertFalse(client.ready());
            assertEquals(503, client.call("computers", new JsonObject()).get(1, TimeUnit.SECONDS).status());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!client.ready() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(client.ready());
            assertEquals(200, client.call("computers", new JsonObject()).get(1, TimeUnit.SECONDS).status());
        }
    }

    @Test void incompatibleProtocolIsNotRetried() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var operations = Collections.synchronizedList(new ArrayList<String>());
        try (var client = scripted(holder, operations, NetworkProtocol.Reply.error(426, "incompatible cct-live protocol"))) {
            holder.set(client);
            client.start(home);
            Thread.sleep(1500);
            var reply = client.call("computers", new JsonObject()).get(1, TimeUnit.SECONDS);
            assertEquals(503, reply.status());
            assertTrue(reply.data().get("error").getAsString().contains("incompatible"));
            assertEquals(List.of("hello"), operations);
        }
    }

    @Test void handshakeRetryBackoffIsBounded() {
        assertEquals(1, NetworkClient.retryDelaySeconds(0));
        assertEquals(2, NetworkClient.retryDelaySeconds(1));
        assertEquals(30, NetworkClient.retryDelaySeconds(5));
        assertEquals(30, NetworkClient.retryDelaySeconds(Integer.MAX_VALUE));
    }

    @Test void bridgeRequiresLocalTokenAndOnlyForwardsAllowedReads() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var operations = Collections.synchronizedList(new ArrayList<String>());
        try (var client = new NetworkClient(json -> {
            var request = JsonParser.parseString(json).getAsJsonObject();
            var operation = request.get("operation").getAsString();
            operations.add(operation);
            var data = operation.equals("hello")
                ? JsonParser.parseString("{\"protocol\":4,\"control\":true,\"read_only\":false,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\",\"menu_grant\",\"files\",\"files_write\",\"event\",\"power\",\"peripheral\",\"snapshot\",\"trace\",\"body\"]}").getAsJsonObject()
                : JsonParser.parseString("{\"computers\":[]}").getAsJsonObject();
            for (var frame : NetworkProtocol.frames(UUID.fromString(request.get("request_id").getAsString()),
                UUID.fromString(request.get("epoch").getAsString()), new NetworkProtocol.Reply(200, data))) holder.get().receive(frame);
        })) {
            holder.set(client);
            client.start(home);
            var endpoint = JsonParser.parseString(Files.readString(home.resolve("endpoint.json"))).getAsJsonObject();
            var base = URI.create(endpoint.get("url").getAsString());
            assertEquals("127.0.0.1", base.getHost());
            var token = Files.readString(home.resolve("token.txt")).trim();
            var http = HttpClient.newHttpClient();
            var denied = http.send(HttpRequest.newBuilder(base.resolve("computers")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            var allowed = http.send(HttpRequest.newBuilder(base.resolve("computers")).header("Authorization", "Bearer " + token).header("X-CCT-Session", UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            // Control reads exist now but need lease credentials; a write-only route has no GET at all.
            for (var path : List.of("computers/1/files", "computers/1/trace", "computers/1/body")) {
                var response = http.send(HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token).header("X-CCT-Session", UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode());
            }
            for (var path : List.of("computers/1/snapshot", "computers/1/lua", "computers/1/detach")) {
                var response = http.send(HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token).header("X-CCT-Session", UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, response.statusCode());
            }
            var write = http.send(HttpRequest.newBuilder(base.resolve("computers/1/attach")).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, write.statusCode());
            var history = http.send(HttpRequest.newBuilder(base.resolve("computers/1/screen?tick=5")).header("Authorization", "Bearer " + token).header("X-CCT-Session", UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, history.statusCode());
            assertEquals(List.of("hello", "computers"), operations);
        }
        assertFalse(Files.exists(home.resolve("endpoint.json")));
        assertTrue(Files.exists(home.resolve("token.txt")));
    }

    @Test void sessionControlRoutesForwardCredentialsButNeverGrantAccessOverHttp() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var sent = Collections.synchronizedList(new ArrayList<JsonObject>());
        try (var client = new NetworkClient(json -> {
            var request = JsonParser.parseString(json).getAsJsonObject(); sent.add(request);
            var data = JsonParser.parseString("{\"protocol\":4,\"control\":true,\"read_only\":false,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\",\"menu_grant\",\"files\",\"files_write\",\"event\",\"power\",\"peripheral\",\"snapshot\",\"trace\",\"body\"]}").getAsJsonObject();
            for (var frame : NetworkProtocol.frames(UUID.fromString(request.get("request_id").getAsString()),
                UUID.fromString(request.get("epoch").getAsString()), new NetworkProtocol.Reply(200, data))) holder.get().receive(frame);
        })) {
            holder.set(client); client.start(home);
            var base = URI.create(JsonParser.parseString(Files.readString(home.resolve("endpoint.json"))).getAsJsonObject().get("url").getAsString());
            var token = Files.readString(home.resolve("token.txt")).trim();
            var agent = UUID.randomUUID().toString(); var lease = UUID.randomUUID().toString();
            var http = HttpClient.newHttpClient();
            for (var route : Map.of("network/sessions/open", "session_open", "network/sessions/heartbeat", "session_heartbeat",
                "network/sessions/close", "session_close", "computers/1/control/acquire", "control_acquire", "computers/1/control/release", "control_release").entrySet()) {
                var request = HttpRequest.newBuilder(base.resolve(route.getKey())).header("Authorization", "Bearer " + token)
                    .header("X-CCT-Open-Key", UUID.randomUUID().toString()).header("X-CCT-Session", agent)
                    .header("X-CCT-Lease", lease).header("X-CCT-Lease-Epoch", "42").POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                var forwarded = sent.get(sent.size() - 1);
                assertEquals(route.getValue(), forwarded.get("operation").getAsString());
                if (!route.getValue().equals("session_open")) assertEquals(agent, forwarded.getAsJsonObject("arguments").get("agent_session_id").getAsString());
                if (route.getValue().equals("control_release")) {
                    assertEquals(lease, forwarded.getAsJsonObject("arguments").get("lease_id").getAsString());
                    assertEquals(42, forwarded.getAsJsonObject("arguments").get("lease_epoch").getAsLong());
                }
            }
            for (var path : List.of("computers", "computers/1/screen", "computers/1/control")) {
                var request = HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token).GET().build();
                assertEquals(400, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var body = HttpRequest.newBuilder(base.resolve("network/sessions/open")).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"grant\":true}")).build();
            assertEquals(413, http.send(body, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void controlRoutesForwardQueriesBodiesAndSplitLargeWrites() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var sent = Collections.synchronizedList(new ArrayList<JsonObject>());
        try (var client = new NetworkClient(json -> {
            var request = JsonParser.parseString(json).getAsJsonObject(); sent.add(request);
            assertTrue(json.length() <= NetworkProtocol.MAX_REQUEST_CHARS, "request of " + json.length() + " chars");
            var operation = request.get("operation").getAsString();
            JsonObject data;
            if (operation.equals("hello")) data = JsonParser.parseString(CAPABILITIES).getAsJsonObject();
            else if (operation.equals("files_write")) {
                data = new JsonObject();
                data.addProperty("written", request.getAsJsonObject("arguments").getAsJsonObject("body").get("text").getAsString().length());
            } else data = JsonParser.parseString("{\"ok\":true}").getAsJsonObject();
            for (var frame : NetworkProtocol.frames(UUID.fromString(request.get("request_id").getAsString()),
                UUID.fromString(request.get("epoch").getAsString()), new NetworkProtocol.Reply(200, data))) holder.get().receive(frame);
        })) {
            holder.set(client); client.start(home);
            var base = URI.create(JsonParser.parseString(Files.readString(home.resolve("endpoint.json"))).getAsJsonObject().get("url").getAsString());
            var token = Files.readString(home.resolve("token.txt")).trim();
            var http = HttpClient.newHttpClient();
            java.util.function.Function<HttpRequest.Builder, HttpRequest.Builder> credentials = b -> b.header("Authorization", "Bearer " + token)
                .header("X-CCT-Session", UUID.randomUUID().toString()).header("X-CCT-Lease", UUID.randomUUID().toString()).header("X-CCT-Lease-Epoch", "7");

            var read = http.send(credentials.apply(HttpRequest.newBuilder(base.resolve("computers/3/files?op=read&path=%2Fstartup.lua&length=100"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, read.statusCode());
            var forwarded = sent.get(sent.size() - 1);
            assertEquals("files", forwarded.get("operation").getAsString());
            var query = forwarded.getAsJsonObject("arguments").getAsJsonObject("query");
            assertEquals("read", query.get("op").getAsString());
            assertEquals("/startup.lua", query.get("path").getAsString());
            assertEquals(3, forwarded.getAsJsonObject("arguments").get("computer").getAsInt());

            var bogus = http.send(credentials.apply(HttpRequest.newBuilder(base.resolve("computers/3/files?evil=1"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bogus.statusCode());

            var event = http.send(credentials.apply(HttpRequest.newBuilder(base.resolve("computers/3/event")))
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"key\",\"args\":[28,false]}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, event.statusCode());
            forwarded = sent.get(sent.size() - 1);
            assertEquals("event", forwarded.get("operation").getAsString());
            assertEquals("key", forwarded.getAsJsonObject("arguments").getAsJsonObject("body").get("name").getAsString());

            var text = ("print(\"line \\\"quoted\\\" \u4e2d\u6587\")\n").repeat(1500);
            var body = new JsonObject();
            body.addProperty("path", "/big.lua");
            body.addProperty("text", text);
            var before = sent.size();
            var write = http.send(credentials.apply(HttpRequest.newBuilder(base.resolve("computers/3/files")))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, write.statusCode());
            var parts = new ArrayList<>(sent.subList(before, sent.size()));
            assertTrue(parts.size() > 3);
            var joined = new StringBuilder();
            for (var i = 0; i < parts.size(); i++) {
                var part = parts.get(i).getAsJsonObject("arguments").getAsJsonObject("body");
                assertEquals("files_write", parts.get(i).get("operation").getAsString());
                assertEquals(i > 0, part.get("append").getAsBoolean());
                joined.append(part.get("text").getAsString());
            }
            assertEquals(text, joined.toString());
            var total = JsonParser.parseString(write.body()).getAsJsonObject();
            assertEquals(text.length(), total.get("written").getAsLong());
            assertEquals(parts.size(), total.get("parts").getAsInt());

            var getWithBody = HttpRequest.newBuilder(base.resolve("computers/3/trace")).header("Authorization", "Bearer " + token)
                .header("X-CCT-Session", UUID.randomUUID().toString()).header("X-CCT-Lease", UUID.randomUUID().toString()).header("X-CCT-Lease-Epoch", "7")
                .method("GET", HttpRequest.BodyPublishers.ofString("x")).build();
            assertEquals(413, http.send(getWithBody, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void disconnectCompletesPendingRequestsAndOldResponsesCannotCrossConnections() throws Exception {
        var sent = new ArrayList<JsonObject>();
        var client = new NetworkClient(json -> sent.add(JsonParser.parseString(json).getAsJsonObject()));
        client.start(home);
        assertEquals(503, client.call("computers", new JsonObject()).get().status());
        var hello = sent.get(0);
        var data = JsonParser.parseString("{\"protocol\":4,\"control\":true,\"read_only\":false,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\",\"menu_grant\",\"files\",\"files_write\",\"event\",\"power\",\"peripheral\",\"snapshot\",\"trace\",\"body\"]}").getAsJsonObject();
        for (var frame : NetworkProtocol.frames(UUID.fromString(hello.get("request_id").getAsString()),
            UUID.fromString(hello.get("epoch").getAsString()), new NetworkProtocol.Reply(200, data))) client.receive(frame);
        var pending = client.call("computers", new JsonObject());
        assertFalse(pending.isDone());
        var oldRequest = sent.get(1);
        client.close();
        assertEquals(503, pending.get(1, TimeUnit.SECONDS).status());
        try (var next = new NetworkClient(json -> {})) {
            next.start(home);
            for (var frame : NetworkProtocol.frames(UUID.fromString(oldRequest.get("request_id").getAsString()),
                UUID.fromString(oldRequest.get("epoch").getAsString()), new NetworkProtocol.Reply(200, data))) next.receive(frame);
            assertEquals(503, next.call("computers", new JsonObject()).get().status());
        }
    }
}
