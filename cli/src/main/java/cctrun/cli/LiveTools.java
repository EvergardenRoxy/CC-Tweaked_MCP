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
    static final String COMPUTERS = "cct_live_computers", SCREEN = "cct_live_screen", ATTACH = "cct_live_attach",
        DETACH = "cct_live_detach", TRACE = "cct_live_trace", EVENT = "cct_live_event", TYPE = "cct_live_type",
        CLICK = "cct_live_click", FILES = "cct_live_files", POWER = "cct_live_power";
    static final Set<String> NAMES = Set.of(COMPUTERS, SCREEN, ATTACH, DETACH, TRACE, EVENT, TYPE, CLICK, FILES, POWER);
    /** GLFW key codes, as CC: Tweaked 1.120.2 on Minecraft 1.20.1 reports them in key events. */
    static final int KEY_ENTER = 257;

    private static final String GAME_DIR = """
        "game_dir": { "type": "string", "description": "Minecraft game directory (the folder with mods/ and cct-live/; with launcher version isolation the version folder). Default: the server's --live option or CCT_LIVE_DIR, else the last game_dir that worked in this session." }""";
    private static final String COMPUTER = """
        "computer": { "type": "integer", "minimum": 0, "description": "Computer ID, as listed by cct_live_computers." }""";

    private static final String DEFINITIONS = """
        [
          {
            "name": "cct_live_computers",
            "title": "List computers in the running game",
            "description": "Lists the CC: Tweaked computers loaded in a running Minecraft game that has the cct-live mod: id, label, family (normal, advanced, command), on/off, dimension, position, whether an AI is attached (and lease seconds left), plus the server tick, recording_mode and lease_seconds. Unlike cct_run this is the real game: real world, real peripherals, real players. Nothing is recorded for a computer until you attach it (cct_live_attach); listing and cct_live_screen work without attaching.",
            "inputSchema": { "type": "object", "properties": { %1$s }, "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_screen",
            "title": "Screen of a computer in the game",
            "description": "Returns what a real computer's terminal shows now: lines (text), fg and bg (one colour character 0-9a-f per cell, as in term.blit), cursor, size, on/off and the server tick. regions: true adds the screen's colour areas (connected cells of one background colour other than the main one, largest first: bg, from [x,y], to [x,y], center [x,y], cells, rectangle, text inside). Use it to find GUI buttons and panels, also when their labels are drawn as pixel art and unreadable in lines; click a button at its center with cct_live_click. tick: N rebuilds the screen as it was at server tick N from the trace (only ticks while attached). Works without attaching (except tick).",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "regions": { "type": "boolean", "description": "Also return colour regions (buttons, panels). Default false." },
                "tick": { "type": "integer", "minimum": 0, "description": "Rebuild the screen as it was at this server tick, from the trace." }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_attach",
            "title": "Attach to a computer in the game",
            "description": "Starts observing and controlling a real computer: from now on everything it does is recorded (see cct_live_trace) and the input and power tools work on it. The player sees a chat message that an AI took control of the computer, and can take it back with /cctlive detach <id>. The attachment lasts while you keep using it: every request for this computer renews a lease (lease_seconds, 1800 by default); with no request for that long it ends by itself. Calling it again only renews. If the game runs with recording mode on, the trace starts with the computer's recent records from before the attachment (marked history: true); otherwise nothing before the attachment is known, so attach first and then reproduce the problem (re-run the program with cct_live_type, or reboot). Attach only when the user asked you to work on their computer.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_detach",
            "title": "Release a computer in the game",
            "description": "Ends your attachment to a real computer: recording stops, input and power tools stop working on it, and the player sees that the AI released it. Release computers when you are done. Its trace stays readable with cct_live_trace, and attaching again continues the same trace.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_trace",
            "title": "Trace of a computer in the game",
            "description": "Returns the trace of a real computer for this game session (all your attachments to it, one file, seq counts on), oldest first, as JSON {tick, attached, matched, records, next_offset?}. Records exist only while attached (plus recording-mode history, marked history: true). Every record has seq, tick (server ticks since the world opened), ms and kind. Kinds: attach / detach (reason: detach, revoked by a player, expired, removed, closed), printed_error (text the computer printed with printError, which is how the shell reports a program's crash, e.g. '/startup.lua:911: ...'; program = the running program), event (every event delivered to the Lua VM: keys, timers, http_success, task_complete, redstone ...), peripheral_call / peripheral_return, api (http.request/checkURL/websocket, fs.open/delete/move/copy/makeDir with args and result), handle (each read/write/close on an open file, HTTP response or websocket, with the data, first 200 characters by default; read whole files with cct_live_files), screen (changed rows with fg/bg colours), redstone_output, power, label, moved, vm_start, vm_close, vm_error, computer_seen, computer_removed, truncated. Files under /rom are not recorded. To see what an action caused, note 'tick' or the last seq first, act, then query with after=<seq> or ticks=<tick>-.",
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
            "description": "Queues an event on a real computer you have attached (cct_live_attach), exactly as if it had happened in the game, e.g. {name: 'char', args: ['y']}, {name: 'key', args: [257, false]} then {name: 'key_up', args: [257]} (key codes are GLFW: enter 257, backspace 259, tab 258, right 262, left 263, down 264, up 265, left ctrl 341), {name: 'terminate'} (Ctrl+T), {name: 'monitor_touch', args: ['monitor_0', 3, 2]}, or a custom event a program waits for. This acts on the user's real world; send only what the user asked for or agreed to.",
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
            "description": "Types text into a real computer you have attached (cct_live_attach) as a paste event, then presses Enter (unless enter is false). In the shell this runs a command, e.g. 'edit startup.lua' or 'myprogram arg'; in a program it answers read(). Text must be one line. This acts on the user's real world; run only what the user asked for or agreed to.",
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
            "name": "cct_live_click",
            "title": "Click on a computer's screen in the game",
            "description": "Clicks at column x, row y (1-based) of a real computer you have attached: sends mouse_click then mouse_up at the same cell (advanced computers only). Find buttons with cct_live_screen regions: true and click their center. Then check the screen or trace before the next click.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "x": { "type": "integer", "minimum": 1 },
                "y": { "type": "integer", "minimum": 1 },
                "button": { "type": "integer", "enum": [1, 2, 3], "description": "1 left (default), 2 right, 3 middle." }
              },
              "required": ["computer", "x", "y"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_files",
            "title": "Read a computer's files in the game",
            "description": "Reads a real computer's own files (what Lua sees under /, not /rom or disks) without running anything on it; the computer must be attached. op 'list' (path = directory): entries with name, dir, size; 'stat': exists, dir, size; 'read': text of the file from offset (bytes, default 0), up to length bytes (default 65536, max 1048576), with size, eof and encoding (utf-8 if valid, else iso-8859-1). Every result has free_space and capacity of the computer's disk.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "op": { "type": "string", "enum": ["list", "stat", "read"], "description": "Default list." },
                "path": { "type": "string", "description": "Path on the computer, e.g. / or icons/User.lua. Default /." },
                "offset": { "type": "integer", "minimum": 0 },
                "length": { "type": "integer", "minimum": 0, "maximum": 1048576 }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_power",
            "title": "Turn a computer in the game on, off or reboot it",
            "description": "Turns a real computer you have attached (cct_live_attach) on, shuts it down or reboots it (startup.lua runs again on boot). This acts on the user's real world; do it only when the user asked for or agreed to it.",
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
    /** The last game_dir that worked, used when a later call gives none and no default is configured. */
    private volatile Path rememberedGameDir;
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
                allowOnly(a, "game_dir", "computer", "regions", "tick");
                var query = new ArrayList<String>();
                if (a.has("regions") && !a.get("regions").isJsonNull() && a.get("regions").getAsBoolean()) query.add("regions=1");
                var tick = integer(a, "tick");
                if (tick != null) query.add("tick=" + tick);
                yield result(get(endpoint(a), "computers/" + computer(a) + "/screen" + (query.isEmpty() ? "" : "?" + String.join("&", query))));
            }
            case CLICK -> {
                allowOnly(a, "game_dir", "computer", "x", "y", "button");
                var x = integer(a, "x");
                var y = integer(a, "y");
                if (x == null || y == null || x < 1 || y < 1) throw new McpTools.ToolError("'x' and 'y' (1-based column and row) are required");
                var button = integer(a, "button");
                if (button == null) button = 1;
                if (button < 1 || button > 3) throw new McpTools.ToolError("'button' must be 1, 2 or 3");
                var endpoint = endpoint(a);
                var id = computer(a);
                post(endpoint, "computers/" + id + "/event", event("mouse_click", button, x, y));
                var last = post(endpoint, "computers/" + id + "/event", event("mouse_up", button, x, y));
                var out = new JsonObject();
                out.addProperty("clicked", "[" + x + "," + y + "]");
                out.addProperty("button", button);
                out.add("queued", JsonParser.parseString(last));
                yield McpServer.toolResult(Main.ascii(out.toString()), false);
            }
            case FILES -> {
                allowOnly(a, "game_dir", "computer", "op", "path", "offset", "length");
                var query = new ArrayList<String>();
                var op = string(a, "op");
                query.add("op=" + encode(op == null ? "list" : op));
                var path = string(a, "path");
                query.add("path=" + encode(path == null ? "/" : path));
                for (var key : List.of("offset", "length")) {
                    var value = integer(a, key);
                    if (value != null) query.add(key + "=" + value);
                }
                yield result(get(endpoint(a), "computers/" + computer(a) + "/files?" + String.join("&", query)));
            }
            case ATTACH, DETACH -> {
                allowOnly(a, "game_dir", "computer");
                yield result(post(endpoint(a), "computers/" + computer(a) + "/" + (name.equals(ATTACH) ? "attach" : "detach"), new JsonObject()));
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
            gameDir = given != null ? workDir.resolve(given) : defaultGameDir != null ? defaultGameDir : rememberedGameDir;
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
            rememberedGameDir = gameDir;
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
