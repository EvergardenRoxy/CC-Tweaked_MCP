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
                ? JsonParser.parseString("{\"protocol\":1,\"read_only\":true,\"capabilities\":[\"computers\",\"status\",\"screen\"]}").getAsJsonObject()
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
            var allowed = http.send(HttpRequest.newBuilder(base.resolve("computers")).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            for (var path : List.of("computers/1/files", "computers/1/trace", "computers/1/body", "computers/1/snapshot")) {
                var response = http.send(HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, response.statusCode());
            }
            var write = http.send(HttpRequest.newBuilder(base.resolve("computers/1/attach")).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, write.statusCode());
            var history = http.send(HttpRequest.newBuilder(base.resolve("computers/1/screen?tick=5")).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, history.statusCode());
            assertEquals(List.of("hello", "computers"), operations);
        }
        assertFalse(Files.exists(home.resolve("endpoint.json")));
        assertTrue(Files.exists(home.resolve("token.txt")));
    }

    @Test void disconnectCompletesPendingRequestsAndOldResponsesCannotCrossConnections() throws Exception {
        var sent = new ArrayList<JsonObject>();
        var client = new NetworkClient(json -> sent.add(JsonParser.parseString(json).getAsJsonObject()));
        client.start(home);
        assertEquals(503, client.call("computers", new JsonObject()).get().status());
        var hello = sent.get(0);
        var data = JsonParser.parseString("{\"protocol\":1,\"read_only\":true,\"capabilities\":[\"computers\",\"status\",\"screen\"]}").getAsJsonObject();
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
