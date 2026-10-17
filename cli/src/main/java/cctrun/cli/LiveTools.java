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
    static final String COMPUTERS = "cct_live_computers", STATUS = "cct_live_status", WAIT = "cct_live_wait",
        SCREEN = "cct_live_screen", ATTACH = "cct_live_attach", DETACH = "cct_live_detach", TRACE = "cct_live_trace",
        EVENT = "cct_live_event", TYPE = "cct_live_type", CLICK = "cct_live_click", FILES = "cct_live_files",
        POWER = "cct_live_power";
    static final Set<String> NAMES = Set.of(COMPUTERS, STATUS, WAIT, SCREEN, ATTACH, DETACH, TRACE, EVENT, TYPE, CLICK, FILES, POWER);
    /** GLFW key codes, as CC: Tweaked 1.120.2 on Minecraft 1.20.1 reports them in key events. */
    static final int KEY_ENTER = 257;
    static final int DEFAULT_TRACE_LIMIT = 50;
    static final int DEFAULT_WAIT = 30, MAX_WAIT = 120;
    static final long POLL_MILLIS = 250;

    private static final String GAME_DIR = """
        "game_dir": { "type": "string", "description": "Game directory (folder with mods/ and cct-live/). Default: --live / CCT_LIVE_DIR, else the last one that worked." }""";
    private static final String COMPUTER = """
        "computer": { "type": "integer", "minimum": 0, "description": "Computer ID (cct_live_computers)." }""";
    private static final String SOURCE = """
        "source": { "type": "string", "description": "'term' (default) or the side of a monitor attached to the computer, e.g. 'right' (see monitors in cct_live_status)." }""";

    private static final String DEFINITIONS = """
        [
          {
            "name": "cct_live_computers",
            "title": "List computers in the running game",
            "description": "Real CC: Tweaked computers in the user's running Minecraft game (cct-live mod): id, label, family, on, position, attached. Nothing is recorded until you cct_live_attach a computer. Read cct_guide section 7 first.",
            "inputSchema": { "type": "object", "properties": { %1$s }, "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_status",
            "title": "Short status of a computer",
            "description": "The cheapest way to check a computer: tick, on, last_seq, lease left, monitors, new_since_after (record counts by kind after seq 'after'), last_printed_error, and the screen's text lines (trailing blanks dropped) with a screen_hash. Use this instead of cct_live_screen/cct_live_trace to see whether something happened.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, %3$s, "after": { "type": "integer", "minimum": 0, "description": "Count records after this seq (usually last_seq of your previous call)." } }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_wait",
            "title": "Wait for something on a computer",
            "description": "Waits on the server side (no polling by you) until the first condition holds, then returns a short result {met: text|kind|idle|timeout, waited_seconds, status, match?}. Conditions (give at least one): text = the screen (of source) contains this text; kind = a record of one of these kinds appears after seq 'after' (e.g. [\\"printed_error\\",\\"http_failure\\",\\"vm_close\\"]), match is that record; idle_seconds = the screen did not change for this long (a GUI finished drawing, a download finished). timeout default 30, max 120 seconds. Use it after every action instead of sleeping and re-reading the screen.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s, %3$s,
                "text": { "type": "string" },
                "kind": { "type": "array", "items": { "type": "string" } },
                "after": { "type": "integer", "minimum": 0, "description": "For kind: only records after this seq. Default: the last seq when the wait starts." },
                "idle_seconds": { "type": "number", "minimum": 0.5, "maximum": 60 },
                "timeout": { "type": "integer", "minimum": 1, "maximum": 120 }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_screen",
            "title": "Screen of a computer or monitor",
            "description": "Text lines of the terminal (or a monitor: source) now, trailing blanks dropped. colors: true adds fg/bg rows (blit characters 0-9a-f). regions: true adds colour areas (bg, from, to, center, text), largest first: GUI buttons and panels, also when their labels are pixel art; click a region's center. tick: N rebuilds the screen at server tick N from the trace (while attached). Prefer cct_live_status for routine checks.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s, %3$s,
                "colors": { "type": "boolean" },
                "regions": { "type": "boolean" },
                "tick": { "type": "integer", "minimum": 0 }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_attach",
            "title": "Attach to a computer in the game",
            "description": "Start recording and controlling a real computer (only when the user asked you to work on it). The player sees a chat message and can take control back with /cctlive detach <id>. Every request for the computer renews the lease (lease_seconds, default from the player's config, 30-86400); calling attach again renews or changes it. Returns last_seq, monitors and files_write (whether file writing is allowed). Without recording mode nothing before the attachment is known: attach first, then reproduce the problem.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "lease_seconds": { "type": "integer", "minimum": 30, "maximum": 86400 } }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_detach",
            "title": "Release a computer in the game",
            "description": "End your attachment (do it when done). The trace stays readable; attaching again continues it.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_trace",
            "title": "Trace of a computer in the game",
            "description": "Records of a computer for this game session (all attachments, seq counts on), oldest first: {tick, matched, records, next_offset?}. Default limit 50; screen records are left out unless kind names 'screen'. summary: true returns only counts and seq/tick ranges per kind (use it first). Kinds: printed_error (how a program crash is reported; check it first), event, api (http.request/checkURL/websocket, fs.open/delete/move/copy/makeDir), handle (reads/writes on files, HTTP responses, websockets; data cut to 200 characters, bytes = full length), peripheral_call/peripheral_return, screen, monitor, redstone_output, power, label, moved, file_write, vm_start/vm_close/vm_error, attach/detach, truncated. Files under /rom are not recorded.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "kind": { "type": "array", "items": { "type": "string" } },
                "exclude": { "type": "array", "items": { "type": "string" } },
                "after": { "type": "integer", "minimum": 0, "description": "Only records with seq greater than this." },
                "ticks": { "type": "string", "description": "N, A-B, A- or -B." },
                "summary": { "type": "boolean" },
                "offset": { "type": "integer", "minimum": 0 },
                "limit": { "type": "integer", "minimum": 1, "maximum": 5000 }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_type",
            "title": "Type into a computer in the game",
            "description": "Types text into an attached computer as paste events; each line is followed by Enter (unless enter is false for the last line). Several lines run one after another, e.g. commands in the shell or lines in the lua REPL. Acts on the user's real world.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "text": { "type": "string" }, "enter": { "type": "boolean", "description": "Enter after the last line. Default true." } }, "required": ["computer", "text"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_click",
            "title": "Click on a computer's screen or a monitor",
            "description": "Clicks at column x, row y (1-based) of an attached computer: mouse_click + mouse_up on the terminal (advanced computers), or monitor_touch on a monitor (source = its side). Then cct_live_wait for the effect.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, %3$s, "x": { "type": "integer", "minimum": 1 }, "y": { "type": "integer", "minimum": 1 }, "button": { "type": "integer", "enum": [1, 2, 3], "description": "Terminal only: 1 left (default), 2 right, 3 middle." } }, "required": ["computer", "x", "y"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_files",
            "title": "Files of a computer in the game",
            "description": "An attached computer's own files (not /rom or disks), without running anything on it. op list (default): entries {name, dir, size}; stat: exists, dir, size; read: text from offset, up to length bytes (default 65536, max 1048576), with size, eof, encoding; write: write text to path (append: true to add), only if the player allowed it (files_write in cct_live_attach; config files.allow_write), recorded as file_write. All results include free_space. Write only what the user asked for.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "op": { "type": "string", "enum": ["list", "stat", "read", "write"] },
                "path": { "type": "string" },
                "offset": { "type": "integer", "minimum": 0 },
                "length": { "type": "integer", "minimum": 0, "maximum": 1048576 },
                "text": { "type": "string", "description": "write: the file's content." },
                "append": { "type": "boolean" },
                "encoding": { "type": "string", "enum": ["utf-8", "iso-8859-1"], "description": "write: how text becomes bytes. Default utf-8; iso-8859-1 for CC's one-byte characters." }
              },
              "required": ["computer"],
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_event",
            "title": "Send an event to a computer in the game",
            "description": "Queues any event on an attached computer, e.g. {name:'char', args:['y']}, {name:'key', args:[257,false]} (GLFW codes: enter 257, backspace 259, tab 258, right 262, left 263, down 264, up 265, left ctrl 341), {name:'terminate'}. Prefer cct_live_type / cct_live_click. Acts on the user's real world.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "name": { "type": "string" }, "args": { "type": "array" } }, "required": ["computer", "name"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_power",
            "title": "Turn a computer on, off or reboot it",
            "description": "on, shutdown or reboot an attached computer (startup.lua runs again on boot). Only when the user agreed.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "action": { "type": "string", "enum": ["on", "shutdown", "reboot"] } }, "required": ["computer", "action"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": true }
          }
        ]
        """.formatted(GAME_DIR, COMPUTER, SOURCE);

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
            case STATUS -> {
                allowOnly(a, "game_dir", "computer", "source", "after");
                var query = new ArrayList<String>();
                addString(query, a, "source");
                addInteger(query, a, "after");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/status" + join(query)));
            }
            case WAIT -> wait(a);
            case SCREEN -> {
                allowOnly(a, "game_dir", "computer", "source", "colors", "regions", "tick");
                var query = new ArrayList<String>();
                addString(query, a, "source");
                if (bool(a, "colors", false)) query.add("colors=1");
                if (bool(a, "regions", false)) query.add("regions=1");
                addInteger(query, a, "tick");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/screen" + join(query)));
            }
            case CLICK -> {
                allowOnly(a, "game_dir", "computer", "source", "x", "y", "button");
                var x = integer(a, "x");
                var y = integer(a, "y");
                if (x == null || y == null || x < 1 || y < 1) throw new McpTools.ToolError("'x' and 'y' (1-based column and row) are required");
                var source = string(a, "source");
                var endpoint = endpoint(a);
                var id = computer(a);
                var out = new JsonObject();
                out.addProperty("clicked", "[" + x + "," + y + "]");
                String last;
                if (source == null || source.equals("term")) {
                    var button = integer(a, "button");
                    if (button == null) button = 1;
                    if (button < 1 || button > 3) throw new McpTools.ToolError("'button' must be 1, 2 or 3");
                    post(endpoint, "computers/" + id + "/event", event("mouse_click", button, x, y));
                    last = post(endpoint, "computers/" + id + "/event", event("mouse_up", button, x, y));
                    out.addProperty("button", button);
                } else {
                    // Monitors only know touches: monitor_touch(side, x, y).
                    last = post(endpoint, "computers/" + id + "/event", event("monitor_touch", source, x, y));
                    out.addProperty("monitor", source);
                }
                out.add("queued", JsonParser.parseString(last));
                yield McpServer.toolResult(Main.ascii(out.toString()), false);
            }
            case FILES -> {
                allowOnly(a, "game_dir", "computer", "op", "path", "offset", "length", "text", "append", "encoding");
                var op = string(a, "op");
                if ("write".equals(op)) {
                    var path = string(a, "path");
                    var text = string(a, "text");
                    if (path == null || text == null) throw new McpTools.ToolError("write needs 'path' and 'text'");
                    var body = new JsonObject();
                    body.addProperty("path", path);
                    body.addProperty("text", text);
                    body.addProperty("append", bool(a, "append", false));
                    var encoding = string(a, "encoding");
                    if (encoding != null) body.addProperty("encoding", encoding);
                    yield result(post(endpoint(a), "computers/" + computer(a) + "/files", body));
                }
                var query = new ArrayList<String>();
                query.add("op=" + encode(op == null ? "list" : op));
                var path = string(a, "path");
                query.add("path=" + encode(path == null ? "/" : path));
                addInteger(query, a, "offset");
                addInteger(query, a, "length");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/files" + join(query)));
            }
            case ATTACH -> {
                allowOnly(a, "game_dir", "computer", "lease_seconds");
                var body = new JsonObject();
                var lease = integer(a, "lease_seconds");
                if (lease != null) {
                    if (lease < 30 || lease > 86400) throw new McpTools.ToolError("'lease_seconds' must be 30..86400");
                    body.addProperty("lease_seconds", lease);
                }
                yield result(post(endpoint(a), "computers/" + computer(a) + "/attach", body));
            }
            case DETACH -> {
                allowOnly(a, "game_dir", "computer");
                yield result(post(endpoint(a), "computers/" + computer(a) + "/detach", new JsonObject()));
            }
            case TRACE -> {
                allowOnly(a, "game_dir", "computer", "kind", "exclude", "after", "ticks", "summary", "offset", "limit");
                var query = new ArrayList<String>();
                var kinds = kinds(a, "kind");
                if (!kinds.isEmpty()) query.add("kind=" + encode(String.join(",", kinds)));
                var exclude = kinds(a, "exclude");
                // Screen records are the bulk of a GUI program's trace: leave them out unless asked for.
                if (exclude.isEmpty() && kinds.isEmpty()) exclude = List.of("screen");
                if (!exclude.isEmpty()) query.add("exclude=" + encode(String.join(",", exclude)));
                addInteger(query, a, "after");
                addInteger(query, a, "offset");
                var limit = integer(a, "limit");
                query.add("limit=" + (limit == null ? DEFAULT_TRACE_LIMIT : limit));
                addString(query, a, "ticks");
                if (bool(a, "summary", false)) query.add("summary=1");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/trace" + join(query)));
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
                var lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                var enter = bool(a, "enter", true);
                var endpoint = endpoint(a);
                var id = computer(a);
                String last = null;
                for (var i = 0; i < lines.length; i++) {
                    if (!lines[i].isEmpty()) last = post(endpoint, "computers/" + id + "/event", event("paste", lines[i]));
                    if (i < lines.length - 1 || enter) {
                        post(endpoint, "computers/" + id + "/event", event("key", KEY_ENTER, false));
                        last = post(endpoint, "computers/" + id + "/event", event("key_up", KEY_ENTER));
                    }
                }
                var out = new JsonObject();
                out.addProperty("lines", lines.length);
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

    /** cct_live_wait: polls the endpoint here (not the agent) until a condition holds or the timeout passes. */
    private JsonObject wait(JsonObject a) throws McpTools.ToolError {
        allowOnly(a, "game_dir", "computer", "source", "text", "kind", "after", "idle_seconds", "timeout");
        var text = string(a, "text");
        var kinds = kinds(a, "kind");
        var idle = number(a, "idle_seconds");
        if (text == null && kinds.isEmpty() && idle == null) throw new McpTools.ToolError("give at least one of 'text', 'kind' or 'idle_seconds'");
        var timeout = integer(a, "timeout");
        if (timeout == null) timeout = DEFAULT_WAIT;
        if (timeout < 1 || timeout > MAX_WAIT) throw new McpTools.ToolError("'timeout' must be 1.." + MAX_WAIT + " seconds");
        var endpoint = endpoint(a);
        var id = computer(a);
        var source = string(a, "source");

        var start = System.nanoTime();
        Long after = integer(a, "after") == null ? null : (long) integer(a, "after");
        String lastHash = null;
        var hashSince = start;
        while (true) {
            var query = new ArrayList<String>();
            if (source != null) query.add("source=" + encode(source));
            if (after != null) query.add("after=" + after);
            if (!kinds.isEmpty()) query.add("kinds=" + encode(String.join(",", kinds)));
            var status = JsonParser.parseString(get(endpoint, "computers/" + id + "/status" + join(query))).getAsJsonObject();
            // Without 'after', records count from where the wait started.
            if (after == null) after = status.has("last_seq") ? status.get("last_seq").getAsLong() : 0L;

            var now = System.nanoTime();
            String met = null;
            if (status.has("match")) met = "kind";
            else if (text != null && screenContains(status, text)) met = "text";
            else if (idle != null) {
                var hash = status.get("screen_hash").getAsString();
                if (!hash.equals(lastHash)) {
                    lastHash = hash;
                    hashSince = now;
                } else if ((now - hashSince) / 1e9 >= idle) met = "idle";
            }
            var waited = (now - start) / 1e9;
            if (met == null && waited >= timeout) met = "timeout";
            if (met != null) {
                var out = new JsonObject();
                out.addProperty("met", met);
                out.addProperty("waited_seconds", Math.round(waited * 10) / 10.0);
                if (status.has("match")) out.add("match", status.remove("match"));
                out.add("status", status);
                return McpServer.toolResult(Main.ascii(out.toString()), false);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpTools.ToolError("cancelled");
            }
        }
    }

    private static boolean screenContains(JsonObject status, String text) {
        if (!status.has("lines")) return false;
        for (var line : status.getAsJsonArray("lines")) if (line.getAsString().contains(text)) return true;
        return false;
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

    private static List<String> kinds(JsonObject a, String key) throws McpTools.ToolError {
        var out = new ArrayList<String>();
        if (!a.has(key) || a.get(key).isJsonNull()) return out;
        var kind = a.get(key);
        if (kind.isJsonPrimitive()) {
            for (var k : kind.getAsString().split(",")) if (!k.isBlank()) out.add(k.trim());
            return out;
        }
        if (!kind.isJsonArray()) throw new McpTools.ToolError("'" + key + "' must be an array of strings");
        for (JsonElement item : kind.getAsJsonArray()) out.add(item.getAsString());
        return out;
    }

    private static boolean bool(JsonObject a, String key, boolean fallback) throws McpTools.ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return fallback;
        var value = a.get(key);
        if (!value.isJsonPrimitive()) throw new McpTools.ToolError("'" + key + "' must be true or false");
        var p = value.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : Boolean.parseBoolean(p.getAsString());
    }

    private static Double number(JsonObject a, String key) throws McpTools.ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        try {
            var p = a.get(key).getAsJsonPrimitive();
            return p.isString() ? Double.parseDouble(p.getAsString()) : p.getAsDouble();
        } catch (RuntimeException e) {
            throw new McpTools.ToolError("'" + key + "' must be a number");
        }
    }

    private static void addString(List<String> query, JsonObject a, String key) throws McpTools.ToolError {
        var value = string(a, key);
        if (value != null) query.add(key + "=" + encode(value));
    }

    private static void addInteger(List<String> query, JsonObject a, String key) throws McpTools.ToolError {
        var value = integer(a, key);
        if (value != null) query.add(key + "=" + value);
    }

    private static String join(List<String> query) {
        return query.isEmpty() ? "" : "?" + String.join("&", query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
