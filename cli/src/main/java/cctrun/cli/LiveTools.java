// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MCP tools for computers in a running Minecraft game with the cct-live mod (mod/README.md). They call the mod's
 * local HTTP endpoint: its address is in {@code <game dir>/cct-live/endpoint.json} (written while a world is open)
 * and its token in {@code <game dir>/cct-live/token.txt}. The game directory comes from the tool's
 * {@code game_dir}, else {@code cct-run mcp --live DIR}, else the CCT_LIVE_DIR environment variable.
 */
final class LiveTools {
    static final String COMPUTERS = "cct_live_computers", SCREEN = "cct_live_screen", TRACE = "cct_live_trace",
        EVENT = "cct_live_event", TYPE = "cct_live_type", POWER = "cct_live_power";
    static final Set<String> NAMES = Set.of(COMPUTERS, SCREEN, TRACE, EVENT, TYPE, POWER);
    /** GLFW key codes, as CC: Tweaked 1.120.2 on Minecraft 1.20.1 reports them in key events. */
    static final int KEY_ENTER = 257;

    private static final String GAME_DIR = """
        "game_dir": { "type": "string", "description": "Minecraft game directory (the folder with mods/ and cct-live/; with launcher version isolation the version folder). Default: the server's --live option or CCT_LIVE_DIR." }""";
    private static final String COMPUTER = """
        "computer": { "type": "integer", "minimum": 0, "description": "Computer ID, as listed by cct_live_computers." }""";

    private static final String DEFINITIONS = """
        [
          {
            "name": "cct_live_computers",
            "title": "List computers in the running game",
            "description": "Lists the CC: Tweaked computers loaded in a running Minecraft game that has the cct-live mod: id, label, family (normal, advanced, command), on/off, dimension, position, and the current server tick. Unlike cct_run this is the real game: real world, real peripherals, real players. Use these tools to observe and debug programs on the user's actual computers.",
            "inputSchema": { "type": "object", "properties": { %1$s }, "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_screen",
            "title": "Screen of a computer in the game",
            "description": "Returns what a real computer's terminal shows now: lines (text), fg and bg (one colour character 0-9a-f per cell, as in term.blit), cursor, size, on/off and the server tick.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_trace",
            "title": "Trace of a computer in the game",
            "description": "Returns trace records the cct-live mod recorded for a real computer during this game session, oldest first, as JSON {tick, matched, records, next_offset?}. Every record has seq, tick (server ticks since the world opened), ms and kind. Kinds: event (every event delivered to the Lua VM: keys, timers, http_success, task_complete, redstone ...), peripheral_call / peripheral_return, api (http.request/checkURL/websocket, fs.open/delete/move/copy/makeDir with args and result), handle (each read/write/close on an open file, HTTP response or websocket, with the data, first 200 characters), screen (changed rows with fg/bg colours), redstone_output, power, label, moved, vm_start, vm_close, vm_error, computer_seen, computer_removed, truncated. Files under /rom are not recorded. To see what an action caused, note 'tick' or the last seq first, act, then query with after=<seq> or ticks=<tick>-.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "kind": { "type": "array", "items": { "type": "string" }, "description": "Only these kinds, e.g. [\\"api\\", \\"handle\\", \\"event\\"]." },
                "after": { "type": "integer", "minimum": 0, "description": "Only records with seq greater than this." },
                "ticks": { "type": "string", "description": "Tick range: N, A-B, A- or -B." },
                "offset": { "type": "integer", "minimum": 0, "description": "Skip this many matching records. Default 0." },
                "limit": { "type": "integer", "minimum": 1, "maximum": 5000, "description": "At most this many records. Default 200." }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_event",
            "title": "Send an event to a computer in the game",
            "description": "Queues an event on a real computer, exactly as if it had happened in the game, e.g. {name: 'char', args: ['y']}, {name: 'key', args: [257, false]} then {name: 'key_up', args: [257]} (key codes are GLFW: enter 257, backspace 259, tab 258, right 262, left 263, down 264, up 265, left ctrl 341), {name: 'terminate'} (Ctrl+T), {name: 'monitor_touch', args: ['monitor_0', 3, 2]}, or a custom event a program waits for. This acts on the user's real world; send only what the user asked for or agreed to.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "name": { "type": "string", "description": "Event name." },
                "args": { "type": "array", "description": "Event arguments (strings, numbers, booleans, objects become tables)." }
              },
              "required": ["computer", "name"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_type",
            "title": "Type a line into a computer in the game",
            "description": "Types text into a real computer as a paste event, then presses Enter (unless enter is false). In the shell this runs a command, e.g. 'edit startup.lua' or 'myprogram arg'; in a program it answers read(). Text must be one line. This acts on the user's real world; run only what the user asked for or agreed to.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "text": { "type": "string", "description": "One line of text (no newline)." },
                "enter": { "type": "boolean", "description": "Press Enter after the text. Default true." }
              },
              "required": ["computer", "text"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_power",
            "title": "Turn a computer in the game on, off or reboot it",
            "description": "Turns a real computer on, shuts it down or reboots it (startup.lua runs again on boot). This acts on the user's real world; do it only when the user asked for or agreed to it.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "action": { "type": "string", "enum": ["on", "shutdown", "reboot"] }
              },
              "required": ["computer", "action"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": true }
          }
        ]
        """.formatted(GAME_DIR, COMPUTER);

    private final Path defaultGameDir;
    private final Path workDir;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .proxy(HttpClient.Builder.NO_PROXY)
        .build();

    /** @param defaultGameDir from --live or CCT_LIVE_DIR; may be null. */
    LiveTools(Path defaultGameDir, Path workDir) {
        this.defaultGameDir = defaultGameDir;
        this.workDir = workDir;
    }

    static JsonArray definitions() {
        return JsonParser.parseString(DEFINITIONS).getAsJsonArray();
    }

    /** Runs one live tool; the result text is the endpoint's JSON. */
    JsonObject call(String name, JsonObject a) throws McpTools.ToolError {
        return switch (name) {
            case COMPUTERS -> {
                allowOnly(a, "game_dir");
                yield result(get(endpoint(a), "computers"));
            }
            case SCREEN -> {
                allowOnly(a, "game_dir", "computer");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/screen"));
            }
            case TRACE -> {
                allowOnly(a, "game_dir", "computer", "kind", "after", "ticks", "offset", "limit");
                var query = new ArrayList<String>();
                var kinds = kinds(a);
                if (!kinds.isEmpty()) query.add("kind=" + encode(String.join(",", kinds)));
                for (var key : List.of("after", "offset", "limit")) {
                    var value = integer(a, key);
                    if (value != null) query.add(key + "=" + value);
                }
                var ticks = string(a, "ticks");
                if (ticks != null) query.add("ticks=" + encode(ticks));
                var path = "computers/" + computer(a) + "/trace" + (query.isEmpty() ? "" : "?" + String.join("&", query));
                yield result(get(endpoint(a), path));
            }
            case EVENT -> {
                allowOnly(a, "game_dir", "computer", "name", "args");
                var eventName = string(a, "name");
                if (eventName == null || eventName.isBlank()) throw new McpTools.ToolError("'name' (the event name) is required");
                var body = new JsonObject();
                body.addProperty("name", eventName);
                if (a.has("args") && !a.get("args").isJsonNull()) {
                    if (!a.get("args").isJsonArray()) throw new McpTools.ToolError("'args' must be an array");
                    body.add("args", a.get("args"));
                }
                yield result(post(endpoint(a), "computers/" + computer(a) + "/event", body));
            }
            case TYPE -> {
                allowOnly(a, "game_dir", "computer", "text", "enter");
                var text = string(a, "text");
                if (text == null) throw new McpTools.ToolError("'text' is required");
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw new McpTools.ToolError("'text' must be a single line; send one call per line");
                var enter = !a.has("enter") || a.get("enter").isJsonNull() || a.get("enter").getAsBoolean();
                var endpoint = endpoint(a);
                var id = computer(a);
                if (!text.isEmpty()) post(endpoint, "computers/" + id + "/event", event("paste", text));
                String last = null;
                if (enter) {
                    post(endpoint, "computers/" + id + "/event", event("key", KEY_ENTER, false));
                    last = post(endpoint, "computers/" + id + "/event", event("key_up", KEY_ENTER));
                }
                var out = new JsonObject();
                out.addProperty("typed", text);
                out.addProperty("enter", enter);
                if (last != null) out.add("queued", JsonParser.parseString(last));
                yield McpServer.toolResult(Main.ascii(out.toString()), false);
            }
            case POWER -> {
                allowOnly(a, "game_dir", "computer", "action");
                var action = string(a, "action");
                if (action == null || !Set.of("on", "shutdown", "reboot").contains(action)) {
                    throw new McpTools.ToolError("'action' must be on, shutdown or reboot");
                }
                var body = new JsonObject();
                body.addProperty("action", action);
                yield result(post(endpoint(a), "computers/" + computer(a) + "/power", body));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    // --- endpoint discovery and HTTP -----------------------------------------------------------------------------

    record Endpoint(URI base, String token) {
    }

    private Endpoint endpoint(JsonObject a) throws McpTools.ToolError {
        var given = string(a, "game_dir");
        Path gameDir;
        try {
            gameDir = given != null ? workDir.resolve(given) : defaultGameDir;
        } catch (InvalidPathException e) {
            throw new McpTools.ToolError("'game_dir' is not a valid path here: " + given);
        }
        if (gameDir == null) {
            throw new McpTools.ToolError("No Minecraft game directory is configured. Pass game_dir (the folder that contains mods/ and cct-live/), or start the server as `cct-run mcp --live <game dir>`, or set CCT_LIVE_DIR.");
        }
        var home = gameDir.resolve("cct-live");
        var endpointFile = home.resolve("endpoint.json");
        if (!Files.isRegularFile(endpointFile)) {
            throw new McpTools.ToolError("cct-live is not running in " + gameDir + " (no cct-live/endpoint.json). Start Minecraft with the cct-live mod and open a world; the file exists only while a world is open.");
        }
        try {
            var endpoint = JsonParser.parseString(Files.readString(endpointFile, StandardCharsets.UTF_8)).getAsJsonObject();
            var token = Files.readString(home.resolve("token.txt"), StandardCharsets.UTF_8).trim();
            var url = endpoint.get("url").getAsString();
            return new Endpoint(URI.create(url.endsWith("/") ? url : url + "/"), token);
        } catch (IOException | RuntimeException e) {
            throw new McpTools.ToolError("cannot read " + home + " (endpoint.json / token.txt): " + e.getMessage());
        }
    }

    private String get(Endpoint endpoint, String path) throws McpTools.ToolError {
        return send(endpoint, HttpRequest.newBuilder(endpoint.base().resolve(path)).GET());
    }

    private String post(Endpoint endpoint, String path, JsonObject body) throws McpTools.ToolError {
        return send(endpoint, HttpRequest.newBuilder(endpoint.base().resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
    }

    private String send(Endpoint endpoint, HttpRequest.Builder request) throws McpTools.ToolError {
        HttpResponse<String> response;
        try {
            response = client.send(request.header("Authorization", "Bearer " + endpoint.token()).timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new McpTools.ToolError("cannot connect to cct-live at " + endpoint.base() + ". Is the world still open? (endpoint.json may be left over from a game that did not close cleanly.)");
        } catch (HttpTimeoutException e) {
            throw new McpTools.ToolError("cct-live at " + endpoint.base() + " did not answer in time (is the game paused or frozen?)");
        } catch (IOException e) {
            throw new McpTools.ToolError("request to cct-live failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTools.ToolError("cancelled");
        }
        if (response.statusCode() == 200) return response.body();
        var message = response.body();
        try {
            var parsed = JsonParser.parseString(message);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("error")) message = parsed.getAsJsonObject().get("error").getAsString();
        } catch (JsonParseException ignored) {
        }
        throw new McpTools.ToolError("cct-live: " + message + " (HTTP " + response.statusCode() + ")");
    }

    private static JsonObject result(String body) {
        return McpServer.toolResult(Main.ascii(body.strip()), false);
    }

    private static JsonObject event(String name, Object... args) {
        var body = new JsonObject();
        body.addProperty("name", name);
        var array = new JsonArray();
        for (var arg : args) {
            if (arg instanceof Number n) array.add(n);
            else if (arg instanceof Boolean b) array.add(b);
            else array.add(String.valueOf(arg));
        }
        body.add("args", array);
        return body;
    }

    // --- argument helpers ----------------------------------------------------------------------------------------

    private static void allowOnly(JsonObject a, String... names) throws McpTools.ToolError {
        var allowed = List.of(names);
        for (var key : a.keySet()) {
            if (!allowed.contains(key)) throw new McpTools.ToolError("unknown argument '" + key + "'; expected one of " + allowed);
        }
    }

    private static int computer(JsonObject a) throws McpTools.ToolError {
        var id = integer(a, "computer");
        if (id == null || id < 0) throw new McpTools.ToolError("'computer' (a computer ID from cct_live_computers) is required");
        return id;
    }

    private static String string(JsonObject a, String key) throws McpTools.ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        var value = a.get(key);
        if (!value.isJsonPrimitive()) throw new McpTools.ToolError("'" + key + "' must be a string");
        return value.getAsString();
    }

    private static Integer integer(JsonObject a, String key) throws McpTools.ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        try {
            var value = a.get(key).getAsJsonPrimitive();
            var number = value.isString() ? Double.parseDouble(value.getAsString()) : value.getAsDouble();
            if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) number;
        } catch (RuntimeException e) {
            throw new McpTools.ToolError("'" + key + "' must be a whole number");
        }
    }

    private static List<String> kinds(JsonObject a) throws McpTools.ToolError {
        var out = new ArrayList<String>();
        if (!a.has("kind") || a.get("kind").isJsonNull()) return out;
        var kind = a.get("kind");
        if (kind.isJsonPrimitive()) {
            for (var k : kind.getAsString().split(",")) if (!k.isBlank()) out.add(k.trim());
            return out;
        }
        if (!kind.isJsonArray()) throw new McpTools.ToolError("'kind' must be an array of strings");
        for (JsonElement item : kind.getAsJsonArray()) out.add(item.getAsString());
        return out;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
