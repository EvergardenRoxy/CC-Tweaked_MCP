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

    @Test void bridgeRequiresLocalTokenAndOnlyForwardsAllowedReads() throws Exception {
        var holder = new AtomicReference<NetworkClient>();
        var operations = Collections.synchronizedList(new ArrayList<String>());
        try (var client = new NetworkClient(json -> {
            var request = JsonParser.parseString(json).getAsJsonObject();
            var operation = request.get("operation").getAsString();
            operations.add(operation);
            var data = operation.equals("hello")
                ? JsonParser.parseString("{\"protocol\":2,\"read_only\":true,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\"]}").getAsJsonObject()
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
            for (var path : List.of("computers/1/files", "computers/1/trace", "computers/1/body", "computers/1/snapshot")) {
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
            var data = JsonParser.parseString("{\"protocol\":2,\"read_only\":true,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\"]}").getAsJsonObject();
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

    @Test void disconnectCompletesPendingRequestsAndOldResponsesCannotCrossConnections() throws Exception {
        var sent = new ArrayList<JsonObject>();
        var client = new NetworkClient(json -> sent.add(JsonParser.parseString(json).getAsJsonObject()));
        client.start(home);
        assertEquals(503, client.call("computers", new JsonObject()).get().status());
        var hello = sent.get(0);
        var data = JsonParser.parseString("{\"protocol\":2,\"read_only\":true,\"capabilities\":[\"computers\",\"status\",\"screen\",\"session_open\",\"session_heartbeat\",\"session_close\",\"control_acquire\",\"control_status\",\"control_release\"]}").getAsJsonObject();
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
