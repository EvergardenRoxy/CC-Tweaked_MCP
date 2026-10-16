// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cct_live_* tools against a fake cct-live endpoint. */
class LiveToolsTest {
    @TempDir
    Path dir;

    private HttpServer fake;
    /** "METHOD path?query body" of every request the fake endpoint received. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private Path game() throws Exception {
        fake = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        fake.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var uri = exchange.getRequestURI();
            requests.add(exchange.getRequestMethod() + " " + uri.getPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) + (body.isEmpty() ? "" : " " + body));
            int status;
            String reply;
            if (!"Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                reply = "{\"error\":\"missing or wrong token\"}";
            } else if (uri.getPath().equals("/computers/9/screen")) {
                status = 404;
                reply = "{\"error\":\"no computer with id 9 is loaded\"}";
            } else {
                status = 200;
                reply = "{\"ok\":true,\"path\":\"" + uri.getPath() + "\"}";
            }
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        var game = dir.resolve("game");
        Files.createDirectories(game.resolve("cct-live"));
        Files.writeString(game.resolve("cct-live/token.txt"), "secret\n");
        Files.writeString(game.resolve("cct-live/endpoint.json"),
            "{\"schema\":\"cct-live/endpoint@0\",\"url\":\"http://127.0.0.1:" + fake.getAddress().getPort() + "/\"}");
        return game;
    }

    @AfterEach
    void stop() {
        if (fake != null) fake.stop(0);
    }

    private Map<String, JsonObject> session(Path liveDir, String... calls) throws Exception {
        var lines = new ArrayList<String>();
        for (var i = 0; i < calls.length; i++) {
            var split = calls[i].indexOf(' ');
            lines.add("{\"jsonrpc\":\"2.0\",\"id\":" + (i + 1) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + calls[i].substring(0, split) + "\",\"arguments\":" + calls[i].substring(split + 1) + "}}");
        }
        var input = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        new McpServer(input, output, dir.resolve("runs"), dir, liveDir).serve();
        var responses = new HashMap<String, JsonObject>();
        for (var line : output.toString(StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank()) continue;
            var message = JsonParser.parseString(line).getAsJsonObject();
            responses.put(message.get("id").toString(), message.getAsJsonObject("result"));
        }
        return responses;
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void callsTheEndpoint() throws Exception {
        var r = session(game(),
            "cct_live_computers {}",
            "cct_live_screen {\"computer\": 0}",
            "cct_live_trace {\"computer\": 0, \"kind\": [\"api\", \"handle\"], \"after\": 12, \"ticks\": \"40-\", \"limit\": 5}",
            "cct_live_event {\"computer\": 1, \"name\": \"char\", \"args\": [\"y\"]}",
            "cct_live_type {\"computer\": 1, \"text\": \"ls rom\"}",
            "cct_live_power {\"computer\": 1, \"action\": \"reboot\"}");
        for (var i = 1; i <= 6; i++) assertFalse(r.get(Integer.toString(i)).get("isError").getAsBoolean(), text(r.get(Integer.toString(i))));
        assertTrue(text(r.get("1")).contains("/computers"));
        // Calls run concurrently, so compare as a set; the three requests of cct_live_type are sent in order.
        var expected = List.of(
            "GET /computers",
            "GET /computers/0/screen",
            "GET /computers/0/trace?kind=api%2Chandle&after=12&limit=5&ticks=40-",
            "POST /computers/1/event {\"name\":\"char\",\"args\":[\"y\"]}",
            "POST /computers/1/event {\"name\":\"paste\",\"args\":[\"ls rom\"]}",
            "POST /computers/1/event {\"name\":\"key\",\"args\":[257,false]}",
            "POST /computers/1/event {\"name\":\"key_up\",\"args\":[257]}",
            "POST /computers/1/power {\"action\":\"reboot\"}");
        assertEquals(expected.stream().sorted().toList(), requests.stream().sorted().toList());
        var typed = requests.stream().filter(q -> q.contains("paste") || q.contains("\"key")).toList();
        assertEquals(List.of(expected.get(4), expected.get(5), expected.get(6)), typed);
    }

    @Test
    void explainsWhatIsMissing() throws Exception {
        var game = game();
        var r = session(null, "cct_live_computers {}");
        assertTrue(r.get("1").get("isError").getAsBoolean());
        assertTrue(text(r.get("1")).contains("--live"), text(r.get("1")));

        r = session(dir.resolve("nowhere"), "cct_live_computers {}");
        assertTrue(text(r.get("1")).contains("no cct-live/endpoint.json"), text(r.get("1")));

        r = session(game,
            "cct_live_screen {\"computer\": 9}",
            "cct_live_screen {}",
            "cct_live_type {\"computer\": 1, \"text\": \"a\\nb\"}",
            "cct_live_power {\"computer\": 1, \"action\": \"explode\"}",
            "cct_live_screen {\"computer\": 0, \"bogus\": 1}",
            "cct_live_computers {\"game_dir\": \"" + game.toString().replace("\\", "\\\\") + "\"}");
        assertEquals("cct-live: no computer with id 9 is loaded (HTTP 404)", text(r.get("1")));
        assertTrue(text(r.get("2")).contains("'computer'"));
        assertTrue(text(r.get("3")).contains("single line"));
        assertTrue(text(r.get("4")).contains("on, shutdown or reboot"));
        assertTrue(text(r.get("5")).contains("unknown argument 'bogus'"));
        assertFalse(r.get("6").get("isError").getAsBoolean());
        for (var i = 1; i <= 5; i++) assertTrue(r.get(Integer.toString(i)).get("isError").getAsBoolean());

        Files.writeString(game.resolve("cct-live/token.txt"), "wrong\n");
        r = session(game, "cct_live_computers {}");
        assertEquals("cct-live: missing or wrong token (HTTP 401)", text(r.get("1")));

        fake.stop(0);
        Files.writeString(game.resolve("cct-live/token.txt"), "secret\n");
        r = session(game, "cct_live_computers {}");
        assertTrue(text(r.get("1")).contains("Is the world still open?"), text(r.get("1")));
    }
}
