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
        POWER = "cct_live_power", LUA = "cct_live_lua", BODY = "cct_live_body", SNAPSHOT = "cct_live_snapshot",
        PERIPHERAL = "cct_live_peripheral";
    static final Set<String> NAMES = Set.of(COMPUTERS, STATUS, WAIT, SCREEN, ATTACH, DETACH, TRACE, EVENT, TYPE, CLICK, FILES, POWER, LUA, BODY, SNAPSHOT, PERIPHERAL);
    static final int DEFAULT_LUA_TIMEOUT = 15;
    private static final java.util.concurrent.atomic.AtomicInteger LUA_RUNS = new java.util.concurrent.atomic.AtomicInteger();
    /** cct_live_lua runs that timed out, by "<computer>/<name>": their output may still arrive and be collected. */
    private final Set<String> pendingLua = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Screen hash of each source ("<computer>/<source>") just before the last input action (event, type, click,
     * power): cct_live_wait does not accept 'text' on that unchanged screen, which still shows what was there before.
     */
    private final java.util.Map<String, String> beforeAction = new java.util.concurrent.ConcurrentHashMap<>();
    /** GLFW key codes, as CC: Tweaked 1.120.2 on Minecraft 1.20.1 reports them in key events. */
    static final int KEY_ENTER = 257;
    static final int DEFAULT_TRACE_LIMIT = 50;
    static final int DEFAULT_WAIT = 30, MAX_WAIT = 120;
    static final long POLL_MILLIS = 250;
    /** Each status poll may wait at most this long, so a frozen game cannot eat the caller's whole timeout. */
    static final int POLL_REQUEST_TIMEOUT_MS = 5000;
    /** ... but never less than this, so the last poll before the deadline is not aborted by its own tiny budget. */
    static final int MIN_POLL_REQUEST_TIMEOUT_MS = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
            "description": "Waits on the server side (no polling by you) until the first condition holds, then returns a short result {met: text|kind|idle|timeout, waited_seconds, status, match?}. Conditions (give at least one): text = the screen (of source) contains this text, on a screen that changed since your last cct_live_type/click/event/power (so a prompt or banner left from before does not count); kind = a record of one of these kinds appears after seq 'after' (e.g. [\\"printed_error\\",\\"http_failure\\",\\"vm_close\\"]), match is that record; idle_seconds = the screen did not change for this long (a GUI finished drawing, a download finished). timeout default 30, max 120 seconds. Use it after every action instead of sleeping and re-reading the screen.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s, %3$s,
                "text": { "type": "string" },
                "kind": { "type": "array", "items": { "type": "string" } },
                "after": { "type": "integer", "minimum": 0, "description": "For kind: only records after this seq. Default: the last seq when the wait starts." },
                "idle_seconds": { "type": "number", "minimum": 0.5, "maximum": 60 },
                "unchanged": { "type": "boolean", "description": "Also accept text on a screen that has not changed since your last cct_live_type/click/event/power (default false)." },
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
            "description": "Records of a computer for this game session (all attachments, seq counts on), oldest first: {tick, matched, records, next_offset?}. Default limit 50; screen records are left out unless kind names 'screen'. summary: true returns only counts and seq/tick ranges per kind (use it first). Kinds: printed_error (how a program crash is reported; check it first), event, api (http.request/checkURL/websocket, fs.open/delete/move/copy/makeDir), handle (reads/writes on files, HTTP responses, websockets; data cut to 200 characters, bytes = full length), peripheral_call/peripheral_return, screen, monitor, redstone_output, power, label, moved, file_write, vm_start/vm_close/vm_error, attach/detach, snapshot, trace_rotated (older records were dropped to keep the trace within its size; first_seq in the result is the oldest one left). Files under /rom are not recorded. Calls to peripherals on a wired network are recorded under the network name (via = the modem side). peripheral / method: only calls and returns of that peripheral name / method (exact). contains: only records whose url, path, program, text, event name, peripheral, method or call arguments contain this text (case-insensitive), e.g. a file name to follow one download.",
            "inputSchema": {
              "type": "object",
              "properties": {
                %1$s, %2$s,
                "kind": { "type": "array", "items": { "type": "string" } },
                "exclude": { "type": "array", "items": { "type": "string" } },
                "after": { "type": "integer", "minimum": 0, "description": "Only records with seq greater than this." },
                "ticks": { "type": "string", "description": "N, A-B, A- or -B." },
                "contains": { "type": "string" },
                "peripheral": { "type": "string", "description": "Peripheral name or side, e.g. Create_Stressometer_0." },
                "method": { "type": "string", "description": "Method name, e.g. setTargetSpeed." },
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
            "description": "An attached computer's own files (not disks) and, read-only, /rom (the ROM the computer runs, e.g. rom/apis/peripheral.lua), without running anything on it. op list (default): entries {name, dir, size}; stat: exists, dir, size; read: text from offset, up to length bytes (default 65536, max 1048576), with size, eof, encoding; write: write text to path (append: true to add), only if the player allowed it (files_write in cct_live_attach; config files.allow_write), recorded as file_write. All results include free_space. Write only what the user asked for.",
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
            "name": "cct_live_lua",
            "title": "Run Lua on a computer and get the result",
            "description": "Runs Lua code on an attached computer in one call and returns {ok, output (lines it printed), returns (serialised return values), error, phase, seconds}: writes a temporary script under /.cctlive, runs it from the shell, waits for the result and deletes its files. Use it for diagnostics (fs.getSize, load() a file to check its syntax, peripheral.getMethods ...) instead of writing, running, reading and deleting scripts yourself. Needs the shell prompt on the screen (not while a program runs; force: true skips that check) and the player's files_write permission. If it times out, the result has pending: <name>; call again later with result: <name> (no code) to collect it. Leftover files of earlier runs are removed on each call and on cct_live_detach. The code runs with the computer's full rights: only read-only diagnostics unless the user agreed to changes.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "code": { "type": "string" }, "timeout": { "type": "integer", "minimum": 1, "maximum": 120, "description": "Seconds to wait for the result. Default 15." }, "force": { "type": "boolean" }, "result": { "type": "string", "description": "Collect a timed-out run: the 'pending' name it returned." } }, "required": ["computer"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_body",
            "title": "Full body of an HTTP response",
            "description": "Reads the full body of an HTTP response the computer read with readAll while attached (handle records with a 'body' number; trace data is cut to 200 characters): {size, offset, length, eof, encoding, text}. offset/length in bytes (default 0 / 65536, max 1048576). Bodies are kept up to 4 MB each.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "body": { "type": "integer", "minimum": 1 }, "offset": { "type": "integer", "minimum": 0 }, "length": { "type": "integer", "minimum": 0, "maximum": 1048576 } }, "required": ["computer", "body"], "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
          },
          {
            "name": "cct_live_event",
            "title": "Send an event to a computer in the game",
            "description": "Queues any event on an attached computer, e.g. {name:'char', args:['y']}, {name:'key', args:[257,false]} (GLFW codes: enter 257, backspace 259, tab 258, right 262, left 263, down 264, up 265, left ctrl 341), {name:'terminate'}. Prefer cct_live_type / cct_live_click. Acts on the user's real world.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "name": { "type": "string" }, "args": { "type": "array" } }, "required": ["computer", "name"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_snapshot",
            "title": "Save, compare or restore a computer's files",
            "description": "Snapshots of an attached computer's own files (all of /, except /.cctlive), kept on the game side under cct-live/snapshots/. op save: copy every file now (name default 'before'; saving again replaces it); diff: {identical, added, removed, changed, dirs_added, dirs_removed} compared with the snapshot; restore: write back changed and removed files and delete files and directories the snapshot does not have, then check (identical); needs the player's files_write permission; list: saved snapshots. Save before you change anything, diff at the end to see what changed, restore to put the computer back. Restore deletes files: only on a computer the user let you change.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "op": { "type": "string", "enum": ["save", "diff", "restore", "list"] }, "name": { "type": "string", "description": "1-40 letters, digits, '_' or '-'. Default 'before'." } }, "required": ["computer", "op"], "additionalProperties": false },
            "annotations": { "readOnlyHint": false, "destructiveHint": true, "idempotentHint": false, "openWorldHint": true }
          },
          {
            "name": "cct_live_peripheral",
            "title": "Read a peripheral of a computer, even while a program runs",
            "description": "Calls a getter of one of an attached computer's peripherals (a side, or a name on a wired network) from the game side, without the computer's Lua: works while a program runs (cct_live_lua needs the shell prompt), adds no event and does not touch the program. Without method: the peripheral's type and methods. Only methods that read state (get..., is..., has..., can..., list, size, tanks, count...). Returns {ok, result | error}; recorded as peripheral_query.",
            "inputSchema": { "type": "object", "properties": { %1$s, %2$s, "name": { "type": "string", "description": "Side (left, back, ...) or wired network name, e.g. Create_Stressometer_0." }, "method": { "type": "string" }, "args": { "type": "array" } }, "required": ["computer", "name"], "additionalProperties": false },
            "annotations": { "readOnlyHint": true, "openWorldHint": true }
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
            case LUA -> lua(a);
            case BODY -> {
                allowOnly(a, "game_dir", "computer", "body", "offset", "length");
                var n = integer(a, "body");
                if (n == null || n < 1) throw new McpTools.ToolError("'body' (the body number of a handle record) is required");
                var query = new ArrayList<String>();
                query.add("n=" + n);
                addInteger(query, a, "offset");
                addInteger(query, a, "length");
                yield result(get(endpoint(a), "computers/" + computer(a) + "/body" + join(query)));
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
                rememberScreens(endpoint, id);
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
                var endpoint = endpoint(a);
                var id = computer(a);
                // cct_live_lua's leftovers go before control does (deleting needs the attachment).
                var cleaned = cleanTemp(endpoint, id, Set.of());
                pendingLua.removeIf(k -> k.startsWith(id + "/"));
                var out = JsonParser.parseString(post(endpoint, "computers/" + id + "/detach", new JsonObject())).getAsJsonObject();
                if (cleaned > 0) out.addProperty("temp_files_removed", cleaned);
                yield McpServer.toolResult(Main.ascii(out.toString()), false);
            }
            case SNAPSHOT -> {
                allowOnly(a, "game_dir", "computer", "op", "name");
                var op = string(a, "op");
                if (op == null || !Set.of("save", "diff", "restore", "list").contains(op)) {
                    throw new McpTools.ToolError("'op' must be save, diff, restore or list");
                }
                var body = new JsonObject();
                body.addProperty("op", op);
                var snapshotName = string(a, "name");
                if (snapshotName != null) body.addProperty("name", snapshotName);
                yield result(post(endpoint(a), "computers/" + computer(a) + "/snapshot", body));
            }
            case TRACE -> {
                allowOnly(a, "game_dir", "computer", "kind", "exclude", "after", "ticks", "contains", "peripheral", "method", "summary", "offset", "limit");
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
                addString(query, a, "contains");
                addString(query, a, "peripheral");
                addString(query, a, "method");
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
                rememberScreens(endpoint(a), computer(a));
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
                rememberScreens(endpoint, id);
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
            case PERIPHERAL -> {
                allowOnly(a, "game_dir", "computer", "name", "method", "args");
                var peripheralName = string(a, "name");
                if (peripheralName == null || peripheralName.isBlank()) throw new McpTools.ToolError("'name' (a side or a wired network name) is required");
                var body = new JsonObject();
                body.addProperty("name", peripheralName);
                var method = string(a, "method");
                if (method != null) body.addProperty("method", method);
                if (a.has("args") && !a.get("args").isJsonNull()) {
                    if (!a.get("args").isJsonArray()) throw new McpTools.ToolError("'args' must be an array");
                    body.add("args", a.get("args"));
                }
                yield result(post(endpoint(a), "computers/" + computer(a) + "/peripheral", body));
            }
            case POWER -> {
                allowOnly(a, "game_dir", "computer", "action");
                var action = string(a, "action");
                if (action == null || !Set.of("on", "shutdown", "reboot").contains(action)) {
                    throw new McpTools.ToolError("'action' must be on, shutdown or reboot");
                }
                var body = new JsonObject();
                body.addProperty("action", action);
                rememberScreens(endpoint(a), computer(a));
                yield result(post(endpoint(a), "computers/" + computer(a) + "/power", body));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** cct_live_wait: polls the endpoint here (not the agent) until a condition holds or the timeout passes. */
    private JsonObject wait(JsonObject a) throws McpTools.ToolError {
        allowOnly(a, "game_dir", "computer", "source", "text", "kind", "after", "idle_seconds", "timeout", "unchanged");
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
        // Text left on the screen from before the last action (a prompt, the previous boot's banner) must not count.
        var baseline = bool(a, "unchanged", false) ? null : beforeAction.get(id + "/" + (source == null ? "term" : source));
        var changed = baseline == null;
        var textOnOldScreen = false;

        Long after = integer(a, "after") == null ? null : (long) integer(a, "after");
        if (after == null && !kinds.isEmpty()) {
            // Records count from where the wait starts: without this the first status would match an old record.
            var first = JsonParser.parseString(get(endpoint, "computers/" + id + "/status")).getAsJsonObject();
            after = first.has("last_seq") ? first.get("last_seq").getAsLong() : 0L;
        }
        var start = System.nanoTime();
        var deadline = start + timeout * 1_000_000_000L;
        String lastHash = null;
        var hashSince = start;
        JsonObject lastStatus = null;
        while (true) {
            var now = System.nanoTime();
            var remainingMs = (deadline - now) / 1_000_000L;
            var query = new ArrayList<String>();
            if (source != null) query.add("source=" + encode(source));
            if (after != null) query.add("after=" + after);
            if (!kinds.isEmpty()) query.add("kinds=" + encode(String.join(",", kinds)));
            String body;
            try {
                // Bound each poll by what is left of the wait: a frozen game must end in "timeout", not a hung tool.
                body = get(endpoint, "computers/" + id + "/status" + join(query),
                    Duration.ofMillis(Math.min(POLL_REQUEST_TIMEOUT_MS, Math.max(remainingMs, MIN_POLL_REQUEST_TIMEOUT_MS))));
            } catch (McpTools.ToolError e) {
                if (!isUnanswered(e)) throw e;
                if (System.nanoTime() >= deadline) {
                    // The endpoint answered before, so this timeout is most likely the shrinking poll budget: still a
                    // normal timeout, not "did not answer". Only an endpoint that never answered gets the extra note.
                    return lastStatus != null
                        ? waitResult("timeout", lastStatus, (System.nanoTime() - start) / 1e9, textOnOldScreen, changed)
                        : waitTimedOut((System.nanoTime() - start) / 1e9);
                }
                continue;
            }
            var status = JsonParser.parseString(body).getAsJsonObject();
            lastStatus = status;
            if (after == null) after = status.has("last_seq") ? status.get("last_seq").getAsLong() : 0L;

            now = System.nanoTime();
            var hash = screenHash(status);
            // A status without screen_hash (an older cct-live, or a monitor that is gone) must not crash the wait.
            if (!changed && hash != null && !hash.equals(baseline)) changed = true;
            String met = null;
            if (status.has("match")) met = "kind";
            if (met == null && text != null && screenContains(status, text)) {
                if (changed) met = "text";
                else textOnOldScreen = true;
            }
            // Not "else if": a leftover text match must not suppress the idle condition (review A2).
            if (met == null && idle != null && hash != null) {
                if (!hash.equals(lastHash)) {
                    lastHash = hash;
                    hashSince = now;
                } else if ((now - hashSince) / 1e9 >= idle) met = "idle";
            }
            var waited = (now - start) / 1e9;
            if (met == null && now >= deadline) met = "timeout";
            if (met != null) return waitResult(met, status, waited, textOnOldScreen, changed);
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpTools.ToolError("cancelled");
            }
        }
    }

    private static String screenHash(JsonObject status) {
        return status.has("screen_hash") && !status.get("screen_hash").isJsonNull()
            ? status.get("screen_hash").getAsString() : null;
    }

    /** Builds the cct_live_wait answer for a condition that was met (or for a normal timeout). */
    private static JsonObject waitResult(String met, JsonObject status, double waited, boolean textOnOldScreen, boolean changed) {
        var out = new JsonObject();
        out.addProperty("met", met);
        out.addProperty("waited_seconds", Math.round(waited * 10) / 10.0);
        if (status.has("match")) out.add("match", status.remove("match"));
        if ("timeout".equals(met) && textOnOldScreen && !changed) {
            out.addProperty("note", "the text was on the screen, but the screen has not changed since your last action (cct_live_type/click/event/power), so it is left over from before; pass unchanged: true to accept it");
        }
        out.add("status", status);
        return McpServer.toolResult(Main.ascii(out.toString()), false);
    }

    private static boolean isUnanswered(McpTools.ToolError e) {
        return e.getMessage() != null && e.getMessage().contains("did not answer in time");
    }

    /** What cct_live_wait returns when the endpoint stops answering before any condition could be checked. */
    private static JsonObject waitTimedOut(double waited) {
        var out = new JsonObject();
        out.addProperty("met", "timeout");
        out.addProperty("waited_seconds", Math.round(waited * 10) / 10.0);
        out.addProperty("note", "cct-live did not answer the status request in time (is the game paused or frozen?)");
        return McpServer.toolResult(Main.ascii(out.toString()), false);
    }

    /** Remembers each screen's hash before an input action, for cct_live_wait (best effort: errors are ignored). */
    private void rememberScreens(Endpoint endpoint, int id) {
        try {
            var status = JsonParser.parseString(get(endpoint, "computers/" + id + "/status")).getAsJsonObject();
            beforeAction.put(id + "/term", status.get("screen_hash").getAsString());
            if (status.has("monitors")) {
                for (var monitor : status.getAsJsonArray("monitors")) {
                    var side = monitor.getAsString();
                    var m = JsonParser.parseString(get(endpoint, "computers/" + id + "/status?source=" + encode(side))).getAsJsonObject();
                    beforeAction.put(id + "/" + side, m.get("screen_hash").getAsString());
                }
            }
        } catch (McpTools.ToolError | RuntimeException e) {
            beforeAction.keySet().removeIf(k -> k.startsWith(id + "/"));
        }
    }

    /** cct_live_lua: temporary script under /.cctlive, run from the shell, result read back, files deleted. */
    private JsonObject lua(JsonObject a) throws McpTools.ToolError {
        allowOnly(a, "game_dir", "computer", "code", "timeout", "force", "result");
        var code = string(a, "code");
        var pending = string(a, "result");
        if (pending == null && (code == null || code.isBlank())) throw new McpTools.ToolError("'code' is required (or 'result' to collect a timed-out run)");
        if (pending != null && code != null) throw new McpTools.ToolError("give either 'code' or 'result', not both");
        var timeout = integer(a, "timeout");
        if (timeout == null) timeout = pending != null ? 1 : DEFAULT_LUA_TIMEOUT;
        if (timeout < 1 || timeout > MAX_WAIT) throw new McpTools.ToolError("'timeout' must be 1.." + MAX_WAIT + " seconds");
        var endpoint = endpoint(a);
        var id = computer(a);

        if (pending != null) {
            if (!pending.matches("lua_[0-9_]+")) throw new McpTools.ToolError("'result' must be a pending name from an earlier cct_live_lua, e.g. lua_1792213873910_3");
            return collect(endpoint, id, pending, timeout, System.nanoTime());
        }

        if (!bool(a, "force", false)) {
            var status = JsonParser.parseString(get(endpoint, "computers/" + id + "/status")).getAsJsonObject();
            var lines = status.getAsJsonArray("lines");
            var last = lines.isEmpty() ? "" : lines.get(lines.size() - 1).getAsString().strip();
            if (!last.endsWith(">")) {
                throw new McpTools.ToolError("the shell prompt is not on the screen (last line: '" + last + "'). cct_live_lua runs from the shell, "
                    + "so it needs the program to have ended: if it stopped at a 'press any key' screen, send a char event; inside a running program "
                    + "use cct_live_type (e.g. in the lua REPL) or cct_live_files for reads. Pass force: true only if the prompt is just scrolled away.");
            }
        }
        cleanTemp(endpoint, id, pendingOutputs(id));

        var name = "lua_" + System.currentTimeMillis() + "_" + LUA_RUNS.incrementAndGet();
        var script = ".cctlive/" + name + ".lua";
        var output = ".cctlive/" + name + ".out";
        var write = new JsonObject();
        write.addProperty("path", script);
        write.addProperty("text", wrapper(code, "/" + output, "/" + script));
        post(endpoint, "computers/" + id + "/files", write);

        var start = System.nanoTime();
        post(endpoint, "computers/" + id + "/event", event("paste", "/" + script.substring(0, script.length() - 4)));
        post(endpoint, "computers/" + id + "/event", event("key", KEY_ENTER, false));
        post(endpoint, "computers/" + id + "/event", event("key_up", KEY_ENTER));
        return collect(endpoint, id, name, timeout, start);
    }

    /** Waits for a run's output file, reads it and deletes the run's files; on timeout the run stays pending. */
    private JsonObject collect(Endpoint endpoint, int id, String name, int timeout, long start) throws McpTools.ToolError {
        var script = ".cctlive/" + name + ".lua";
        var output = ".cctlive/" + name + ".out";
        JsonObject result = null;
        while ((System.nanoTime() - start) / 1e9 < timeout) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpTools.ToolError("cancelled");
            }
            var stat = JsonParser.parseString(get(endpoint, "computers/" + id + "/files?op=stat&path=" + encode(output))).getAsJsonObject();
            if (!stat.get("exists").getAsBoolean() || stat.get("size").getAsLong() == 0) continue;
            var read = JsonParser.parseString(get(endpoint, "computers/" + id + "/files?op=read&path=" + encode(output))).getAsJsonObject();
            try {
                result = JsonParser.parseString(read.get("text").getAsString()).getAsJsonObject();
            } catch (RuntimeException e) {
                continue; // still being written
            }
            break;
        }
        var seconds = Math.round((System.nanoTime() - start) / 1e8) / 10.0;
        if (result == null) {
            pendingLua.add(id + "/" + name);
            var out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("timeout", true);
            out.addProperty("pending", name);
            out.addProperty("seconds", seconds);
            out.addProperty("note", "no result after " + timeout + " s; the code may still be running (check cct_live_status). "
                + "Collect it later with cct_live_lua {result: \"" + name + "\"}; its files are removed then, or on cct_live_detach.");
            return McpServer.toolResult(Main.ascii(out.toString()), false);
        }
        pendingLua.remove(id + "/" + name);
        for (var path : List.of(output, script)) {
            var delete = new JsonObject();
            delete.addProperty("op", "delete");
            delete.addProperty("path", path);
            try {
                post(endpoint, "computers/" + id + "/files", delete);
            } catch (McpTools.ToolError ignored) {
                // The script deletes itself; a missing file is fine.
            }
        }
        // CC serialises empty tables as {}: show them as lists.
        for (var key : List.of("output", "returns")) {
            if (result.has(key) && result.get(key).isJsonObject() && result.getAsJsonObject(key).isEmpty()) result.add(key, new JsonArray());
        }
        result.addProperty("seconds", seconds);
        return McpServer.toolResult(Main.ascii(result.toString()), false);
    }

    private Set<String> pendingOutputs(int id) {
        var keep = new java.util.HashSet<String>();
        for (var k : pendingLua) if (k.startsWith(id + "/")) keep.add(k.substring(k.indexOf('/') + 1) + ".out");
        return keep;
    }

    /** Deletes files left in /.cctlive by earlier runs (except outputs still to be collected); best effort. */
    private int cleanTemp(Endpoint endpoint, int id, Set<String> keep) {
        var removed = 0;
        try {
            var stat = JsonParser.parseString(get(endpoint, "computers/" + id + "/files?op=stat&path=.cctlive")).getAsJsonObject();
            if (!stat.get("exists").getAsBoolean()) return 0;
            var list = JsonParser.parseString(get(endpoint, "computers/" + id + "/files?op=list&path=.cctlive")).getAsJsonObject();
            for (var e : list.getAsJsonArray("entries")) {
                var entry = e.getAsJsonObject().get("name").getAsString();
                if (keep.contains(entry)) continue;
                var delete = new JsonObject();
                delete.addProperty("op", "delete");
                delete.addProperty("path", ".cctlive/" + entry);
                post(endpoint, "computers/" + id + "/files", delete);
                removed++;
            }
            if (keep.isEmpty()) {
                var delete = new JsonObject();
                delete.addProperty("op", "delete");
                delete.addProperty("path", ".cctlive");
                post(endpoint, "computers/" + id + "/files", delete);
            }
        } catch (McpTools.ToolError | RuntimeException ignored) {
            // Not attached, writing off, or nothing there: leave it.
        }
        return removed;
    }

    /** The script cct_live_lua runs: the user's code in its own environment with print/write captured. */
    static String wrapper(String code, String outputPath, String scriptPath) {
        var level = 0;
        while (code.contains("]" + "=".repeat(level) + "]")) level++;
        var eq = "=".repeat(level);
        return """
            -- Written by cct-run (cct_live_lua); deletes itself when done.
            local out, outPath, scriptPath = {}, %s, %s
            local env = setmetatable({}, { __index = _ENV })
            local function capture(...)
              local t = table.pack(...)
              for i = 1, t.n do t[i] = tostring(t[i]) end
              out[#out + 1] = table.concat(t, "\t", 1, t.n)
              return t.n
            end
            env.print = capture
            env.printError = capture
            env.write = function(s) out[#out + 1] = tostring(s) end
            local fn, err = load([%s[%s]%s], "=cct_live_lua", "t", env)
            local res
            if not fn then
              res = { ok = false, phase = "compile", error = tostring(err) }
            else
              local r = table.pack(xpcall(fn, debug.traceback))
              if r[1] then
                local rets = {}
                for i = 2, r.n do
                  local ok, s = pcall(textutils.serialise, r[i], { compact = true })
                  rets[#rets + 1] = ok and s or tostring(r[i])
                end
                res = { ok = true, returns = rets }
              else
                res = { ok = false, phase = "runtime", error = tostring(r[2]) }
              end
            end
            res.output = out
            local h = fs.open(outPath, "w")
            h.write(textutils.serialiseJSON(res))
            h.close()
            fs.delete(scriptPath)
            """.formatted(luaString(outputPath), luaString(scriptPath), eq, "\n" + code + "\n", eq);
    }

    private static String luaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
        return get(endpoint, path, REQUEST_TIMEOUT);
    }

    private String get(Endpoint endpoint, String path, Duration timeout) throws McpTools.ToolError {
        return send(endpoint, HttpRequest.newBuilder(endpoint.base().resolve(path)).GET(), timeout);
    }

    private String post(Endpoint endpoint, String path, JsonObject body) throws McpTools.ToolError {
        return post(endpoint, path, body, REQUEST_TIMEOUT);
    }

    private String post(Endpoint endpoint, String path, JsonObject body, Duration timeout) throws McpTools.ToolError {
        return send(endpoint, HttpRequest.newBuilder(endpoint.base().resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)), timeout);
    }

    private String send(Endpoint endpoint, HttpRequest.Builder request, Duration timeout) throws McpTools.ToolError {
        HttpResponse<String> response;
        try {
            response = client.send(request.header("Authorization", "Bearer " + endpoint.token()).timeout(timeout).build(),
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
        for (JsonElement item : kind.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new McpTools.ToolError("'" + key + "' must be an array of strings");
            }
            out.add(item.getAsString());
        }
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
