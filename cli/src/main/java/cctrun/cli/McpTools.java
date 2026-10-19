// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP tool definitions and the mapping from tool arguments to cct-run command lines. Each tool mirrors one CLI
 * command; argument names follow the CLI options (handoff.md INTERFACE_MCP).
 */
final class McpTools {
    static final String RUN = "cct_run", TRACE = "cct_trace", SCREEN = "cct_screen", GUIDE = "cct_guide";
    static final int DEFAULT_TIMEOUT = 15, DEFAULT_TRACE_LIMIT = 200;
    private static final Pattern PROGRAM_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static final String DEFINITIONS = """
        [
          {
            "name": "cct_run",
            "title": "Run a CC: Tweaked program",
            "description": "Runs a ComputerCraft Lua program on a headless CC: Tweaked 1.120.2 computer (Minecraft 1.20.1) in real time, 20 ticks/s, and returns the run summary as JSON: status (ok, error, timeout, shutdown, reboot, out_of_memory), exit_code (0, 1, 2, 3, 4), error {phase, message with file:line, traceback}, final screen lines, monitors, notes (failed peripheral calls etc.), trace_counts, duration, memory (heap limit about 1 GB and peak use) and run_dir. A Lua error is a normal result here, not a tool failure. Programs that loop forever end with status timeout; judge them by screen and trace. Give the program as 'file' (path) or 'code' (source text). Use a fixture to simulate peripherals, redstone and timed events. Read cct_guide once before the first run.",
            "inputSchema": {
              "type": "object",
              "properties": {
                "file": { "type": "string", "description": "Path of the .lua program. Absolute paths are safest; relative paths resolve against the server's working directory." },
                "code": { "type": "string", "description": "Lua source of the program, instead of 'file'." },
                "name": { "type": "string", "description": "File name for 'code' (default program.lua); appears in error messages as /<name>:<line>. Letters, digits, '.', '_' and '-' only." },
                "args": { "type": "array", "items": { "type": "string" }, "description": "Program arguments (the values of ... in the program)." },
                "fixture": { "type": "object", "description": "Fixture (schema cct-run/fixture@1) given inline: computer settings, files, peripherals, redstone inputs, timeline of actions. See cct_guide section 5." },
                "fixture_file": { "type": "string", "description": "Path of a fixture JSON file, instead of 'fixture'." },
                "timeout": { "type": "integer", "minimum": 1, "maximum": 3600, "description": "Seconds of game time before the run is stopped with status timeout. Default 15." },
                "term": { "type": "string", "description": "Terminal size WIDTHxHEIGHT, e.g. 26x20 for a pocket computer size. Default 51x19." },
                "computer": { "type": "string", "enum": ["advanced", "basic"], "description": "Advanced (colour, default) or basic computer." }
              },
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": false }
          },
          {
            "name": "cct_trace",
            "title": "Query a run's trace",
            "description": "Returns trace records of a run as JSON Lines, oldest first, filtered. Every record has seq, tick, ms and kind. Kinds: event, screen, output (lines scrolled off the screen), peripheral_call, peripheral_return, redstone_output, modem_transmit, fixture_action, program_start, program_end, tick_lag, vm_error, run_start, run_end. Use it to find out why a run behaved as it did instead of guessing from the code.",
            "inputSchema": {
              "type": "object",
              "properties": {
                "run": { "type": "string", "description": "Run directory (run_dir from cct_run), its run_id, or 'latest' (default)." },
                "kind": { "type": "array", "items": { "type": "string" }, "description": "Only these kinds, e.g. [\\"peripheral_call\\", \\"peripheral_return\\"]." },
                "ticks": { "type": "string", "description": "Tick range: N, A-B, A- or -B." },
                "peripheral": { "type": "string", "description": "Only records of this peripheral (side or network name)." },
                "contains": { "type": "string", "description": "Only records whose text, event name, peripheral, method, arguments, results or screen rows contain this text (case-insensitive), e.g. setTargetSpeed or a chat command." },
                "source": { "type": "string", "description": "Only screen/output records of this source: term or a monitor name." },
                "offset": { "type": "integer", "minimum": 0, "description": "Skip this many matching records. Default 0." },
                "limit": { "type": "integer", "minimum": 1, "maximum": 100000, "description": "At most this many records. Default 200." }
              },
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": false }
          },
          {
            "name": "cct_screen",
            "title": "Show a screen at a tick",
            "description": "Rebuilds the screen of the computer ('term') or of a monitor as it was at a tick (default: the end of the run). Returns {source, tick, size, cursor, lines}.",
            "inputSchema": {
              "type": "object",
              "properties": {
                "run": { "type": "string", "description": "Run directory (run_dir from cct_run), its run_id, or 'latest' (default)." },
                "tick": { "type": "integer", "minimum": 0, "description": "Show the screen as it was at this tick." },
                "source": { "type": "string", "description": "'term' (default) or a monitor's peripheral name." }
              },
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": false }
          },
          {
            "name": "cct_guide",
            "title": "cct-run guide for agents",
            "description": "Returns the cct-run guide: workflow, summary fields, trace queries and pitfalls, fixture format with examples, and what a passing run does and does not prove (simulation limits to mention to the user). Read it once before the first cct_run. Its command-line examples map 1:1 to these tools. The guide is long (7 sections); if your client shortens long results, read it one section at a time with 'section': 1-6 cover the simulation (cct_run), 7 the cct_live_* tools for real computers in the game.",
            "inputSchema": {
              "type": "object",
              "properties": { "section": { "type": "integer", "minimum": 1, "description": "Return only this section (1-7)." } },
              "additionalProperties": false
            },
            "annotations": { "readOnlyHint": true, "openWorldHint": false }
          }
        ]
        """;

    private McpTools() {
    }

    static JsonArray definitions() {
        return JsonParser.parseString(DEFINITIONS).getAsJsonArray();
    }

    // --- cct_run -------------------------------------------------------------------------------------------------

    static RunRequest runArgs(JsonObject a, Path outDir, Path workDir) throws ToolError {
        allowOnly(a, "file", "code", "name", "args", "fixture", "fixture_file", "timeout", "term", "computer");
        var file = string(a, "file");
        var code = string(a, "code");
        if ((file == null) == (code == null)) throw new ToolError("give exactly one of 'file' (a path) or 'code' (Lua source)");
        var name = string(a, "name");
        if (name != null && code == null) throw new ToolError("'name' is only used with 'code'");
        if (name == null) name = "program.lua";
        if (!PROGRAM_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new ToolError("'name' must be 1-64 letters, digits, '.', '_' or '-', e.g. door.lua");
        }

        var fixtureFile = string(a, "fixture_file");
        String fixtureText = null;
        if (a.has("fixture") && !a.get("fixture").isJsonNull()) {
            if (fixtureFile != null) throw new ToolError("give at most one of 'fixture' and 'fixture_file'");
            var fixture = a.get("fixture");
            // Some clients send objects as JSON text; accept both.
            if (fixture.isJsonPrimitive() && fixture.getAsJsonPrimitive().isString()) fixtureText = fixture.getAsString();
            else if (fixture.isJsonObject()) fixtureText = fixture.toString();
            else throw new ToolError("'fixture' must be a JSON object");
        }

        var options = new ArrayList<String>();
        var timeout = integer(a, "timeout");
        if (timeout != null) {
            if (timeout < 1 || timeout > 3600) throw new ToolError("'timeout' must be between 1 and 3600 seconds");
            options.add("--timeout");
            options.add(Integer.toString(timeout));
        }
        var term = string(a, "term");
        if (term != null) {
            options.add("--term");
            options.add(term);
        }
        var computer = string(a, "computer");
        if (computer != null) {
            if (!computer.equals("advanced") && !computer.equals("basic")) throw new ToolError("'computer' must be 'advanced' or 'basic'");
            options.add("--" + computer);
        }
        options.add("--out");
        options.add(outDir.toString());

        return new RunRequest(file == null ? null : path(workDir, file, "file"), code, name,
            fixtureFile == null ? null : path(workDir, fixtureFile, "fixture_file"), fixtureText,
            options, stringList(a, "args"), timeout == null ? DEFAULT_TIMEOUT : timeout, null);
    }

    /**
     * A cct_run call as files plus CLI options. Inline code and fixtures are written to a temporary directory
     * first ({@link #materialize}); the run directory keeps its own copy of both.
     */
    record RunRequest(Path program, String code, String name, Path fixtureFile, String fixtureText,
                      List<String> options, List<String> programArgs, int timeoutSeconds, Path tempDir) {
        boolean needsTempDir() {
            return tempDir == null && (code != null || fixtureText != null);
        }

        RunRequest materialize(Path temp) throws IOException {
            var programPath = program;
            if (code != null) {
                programPath = temp.resolve(name);
                Files.writeString(programPath, code, StandardCharsets.UTF_8);
            }
            var fixturePath = fixtureFile;
            if (fixtureText != null) {
                fixturePath = temp.resolve("fixture.json");
                Files.writeString(fixturePath, fixtureText, StandardCharsets.UTF_8);
            }
            return new RunRequest(programPath, code, name, fixturePath, fixtureText, options, programArgs, timeoutSeconds, temp);
        }

        List<String> argv() {
            var argv = new ArrayList<String>();
            argv.add("run");
            argv.add(program.toString());
            if (fixtureFile != null) {
                argv.add("--fixture");
                argv.add(fixtureFile.toString());
            }
            argv.addAll(options);
            argv.add("--");
            argv.addAll(programArgs);
            return argv;
        }

        /** Error messages name the temporary files; show what the caller gave instead. */
        String hideTempPaths(String text) {
            if (tempDir == null) return text;
            if (fixtureText != null) text = text.replace(fixtureFile.toString(), "(inline fixture)");
            if (code != null) text = text.replace(program.toString(), name);
            return text.replace(tempDir.toString(), "(temp)");
        }
    }

    // --- cct_trace / cct_screen ----------------------------------------------------------------------------------

    static List<String> traceArgs(JsonObject a, Path outDir) throws ToolError {
        allowOnly(a, "run", "kind", "ticks", "peripheral", "source", "contains", "offset", "limit");
        var argv = runSelector("trace", a, outDir);
        if (a.has("kind") && !a.get("kind").isJsonNull()) {
            var kind = a.get("kind");
            String kinds;
            if (kind.isJsonPrimitive()) kinds = kind.getAsString();
            else kinds = String.join(",", stringList(a, "kind"));
            if (!kinds.isBlank()) add(argv, "--kind", kinds);
        }
        var ticks = a.has("ticks") && !a.get("ticks").isJsonNull() ? a.get("ticks") : null;
        if (ticks != null) {
            if (!ticks.isJsonPrimitive()) throw new ToolError("'ticks' must be a string like 40-60");
            add(argv, "--ticks", ticks.getAsString());
        }
        addString(argv, a, "peripheral", "--peripheral");
        addString(argv, a, "contains", "--contains");
        addString(argv, a, "source", "--source");
        var offset = integer(a, "offset");
        if (offset != null) add(argv, "--offset", offset.toString());
        add(argv, "--limit", Integer.toString(traceLimit(a)));
        return argv;
    }

    static int traceLimit(JsonObject a) throws ToolError {
        var limit = integer(a, "limit");
        return limit == null ? DEFAULT_TRACE_LIMIT : limit;
    }

    static List<String> screenArgs(JsonObject a, Path outDir) throws ToolError {
        allowOnly(a, "run", "tick", "source");
        var argv = runSelector("screen", a, outDir);
        var tick = integer(a, "tick");
        if (tick != null) add(argv, "--tick", tick.toString());
        addString(argv, a, "source", "--source");
        return argv;
    }

    private static List<String> runSelector(String command, JsonObject a, Path outDir) throws ToolError {
        var run = string(a, "run");
        var argv = new ArrayList<String>();
        argv.add(command);
        argv.add(run == null || run.isBlank() ? "latest" : run);
        add(argv, "--out", outDir.toString());
        return argv;
    }

    static Integer guideSection(JsonObject a) throws ToolError {
        allowOnly(a, "section");
        return integer(a, "section");
    }

    // --- argument helpers ----------------------------------------------------------------------------------------

    private static void allowOnly(JsonObject a, String... names) throws ToolError {
        var allowed = List.of(names);
        for (var key : a.keySet()) {
            if (!allowed.contains(key)) throw new ToolError("unknown argument '" + key + "'; expected one of " + allowed);
        }
    }

    private static String string(JsonObject a, String key) throws ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        var value = a.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new ToolError("'" + key + "' must be a string");
        return value.getAsString();
    }

    private static Integer integer(JsonObject a, String key) throws ToolError {
        if (!a.has(key) || a.get(key).isJsonNull()) return null;
        var value = a.get(key);
        try {
            // Accept 5 and "5": some clients send numbers as strings.
            var number = value.getAsJsonPrimitive().isString() ? Double.parseDouble(value.getAsString()) : value.getAsDouble();
            if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) number;
        } catch (RuntimeException e) {
            throw new ToolError("'" + key + "' must be a whole number");
        }
    }

    private static List<String> stringList(JsonObject a, String key) throws ToolError {
        var list = new ArrayList<String>();
        if (!a.has(key) || a.get(key).isJsonNull()) return list;
        if (!a.get(key).isJsonArray()) throw new ToolError("'" + key + "' must be an array of strings");
        for (JsonElement item : a.getAsJsonArray(key)) {
            if (!item.isJsonPrimitive()) throw new ToolError("'" + key + "' must be an array of strings");
            list.add(item.getAsString());
        }
        return list;
    }

    private static Path path(Path workDir, String value, String key) throws ToolError {
        try {
            return workDir.resolve(value);
        } catch (InvalidPathException e) {
            throw new ToolError("'" + key + "' is not a valid path here: " + value);
        }
    }

    private static void addString(List<String> argv, JsonObject a, String key, String option) throws ToolError {
        var value = string(a, key);
        if (value != null) add(argv, option, value);
    }

    private static void add(List<String> argv, String option, String value) {
        argv.add(option);
        argv.add(value);
    }

    /** A bad tool argument: reported to the agent as a tool result with isError. */
    static final class ToolError extends Exception {
        enum Kind { OTHER, TIMEOUT, CANCELLED }
        private final Kind kind;
        ToolError(String message) {
            this(Kind.OTHER, message);
        }
        ToolError(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
        Kind kind() { return kind; }
    }
}
