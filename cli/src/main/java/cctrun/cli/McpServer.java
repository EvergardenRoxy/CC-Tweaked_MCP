// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import cctrun.engine.Versions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local MCP server over stdio ({@code cct-run mcp}): newline-delimited JSON-RPC 2.0 on stdin/stdout, logs on stderr.
 * <p>
 * A thin wrapper over the CLI (handoff.md D2, D30): {@code cct_run} starts one child JVM running {@code cct-run run}
 * per call, so every run keeps its own process (D1). The read-only tools ({@code cct_trace}, {@code cct_screen},
 * {@code cct_guide}) call the same CLI code in this process; they only read run directories. Tool calls run
 * concurrently; a {@code notifications/cancelled} kills the run's child process. At end of input the server waits
 * for calls in progress, then exits.
 */
final class McpServer {
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    /** Extra wall time a child run gets on top of its game-time timeout (JVM start, boot, writing the trace). */
    private static final long CHILD_GRACE_SECONDS = 120;
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final InputStream in;
    private final OutputStream out;
    private final Path outDir;
    private final Path workDir;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "cct-run-mcp-call");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Call> active = new ConcurrentHashMap<>();
    private final Path liveDir;
    private final LiveTools live;

    McpServer(InputStream in, OutputStream out, Path outDir, Path workDir) {
        this(in, out, outDir, workDir, null);
    }

    /** @param liveDir game directory for the cct_live_* tools (--live / CCT_LIVE_DIR); may be null. */
    McpServer(InputStream in, OutputStream out, Path outDir, Path workDir, Path liveDir) {
        this.in = in;
        this.out = out;
        this.outDir = outDir.toAbsolutePath().normalize();
        this.workDir = workDir.toAbsolutePath().normalize();
        this.liveDir = liveDir == null ? null : this.workDir.resolve(liveDir).normalize();
        this.live = new LiveTools(this.liveDir, this.workDir);
    }

    /** {@code cct-run mcp [--out DIR] [--live GAME_DIR]}. */
    static int main(Main.Args args, PrintStream stdout) throws Exception {
        if (!args.positional().isEmpty()) throw new Main.UsageException("mcp: takes no arguments");
        // stdout belongs to the protocol from here on; anything else that prints goes to stderr.
        System.setOut(System.err);
        var workDir = Path.of("").toAbsolutePath();
        var outOption = args.option("--out");
        Path outDir;
        if (outOption != null) outDir = workDir.resolve(outOption);
        else if (Files.isWritable(workDir)) outDir = workDir.resolve(".cct-runs");
        else outDir = Path.of(System.getProperty("user.home"), ".cct-run", "runs");
        var liveOption = args.option("--live");
        if (liveOption == null) {
            var env = System.getenv("CCT_LIVE_DIR");
            if (env != null && !env.isBlank()) liveOption = env;
        }
        Path liveDir = liveOption == null ? null : workDir.resolve(liveOption);
        System.err.println("cct-run mcp " + Versions.TOOL + ": ready; runs go to " + outDir.normalize() + "; working directory " + workDir
            + (liveDir == null ? "" : "; live game directory " + liveDir.normalize()));
        return new McpServer(System.in, stdout, outDir, workDir, liveDir).serve();
    }

    int serve() throws IOException, InterruptedException {
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            JsonElement message;
            try {
                message = JsonParser.parseString(line);
            } catch (JsonParseException e) {
                send(error(JsonNull.INSTANCE, -32700, "Parse error"));
                continue;
            }
            if (!message.isJsonObject()) {
                send(error(JsonNull.INSTANCE, -32600, "Invalid Request: expected a JSON object"));
                continue;
            }
            try {
                handle(message.getAsJsonObject());
            } catch (RuntimeException e) {
                // Malformed but parsable message (e.g. a non-string method): answer it, keep serving.
                var id = message.getAsJsonObject().get("id");
                send(error(id != null && id.isJsonPrimitive() ? id : JsonNull.INSTANCE, -32600, "Invalid Request: " + e.getMessage()));
            }
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);
        return 0;
    }

    private void handle(JsonObject message) {
        if (!message.has("method")) return; // a response; this server sends no requests
        var method = message.get("method").getAsString();
        var id = message.get("id");
        var params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
        switch (method) {
            case "initialize" -> reply(id, initialize(params));
            case "ping" -> reply(id, new JsonObject());
            case "tools/list" -> {
                var result = new JsonObject();
                var tools = McpTools.definitions();
                tools.addAll(LiveTools.definitions());
                result.add("tools", tools);
                reply(id, result);
            }
            case "tools/call" -> {
                if (id == null) return;
                var call = new Call();
                active.put(id.toString(), call);
                pool.submit(() -> {
                    JsonObject response;
                    try {
                        response = callTool(params, call);
                    } catch (Throwable e) {
                        e.printStackTrace();
                        response = toolResult("internal error: " + e, true);
                    } finally {
                        active.remove(id.toString());
                    }
                    if (!call.cancelled) {
                        if (response.has("jsonrpcError")) send(error(id, -32602, response.get("jsonrpcError").getAsString()));
                        else reply(id, response);
                    }
                });
            }
            case "notifications/cancelled" -> {
                var call = params.has("requestId") ? active.get(params.get("requestId").toString()) : null;
                if (call != null) call.cancel();
            }
            default -> {
                if (id != null && !method.startsWith("notifications/")) send(error(id, -32601, "Method not found: " + method));
            }
        }
    }

    private JsonObject initialize(JsonObject params) {
        var version = params.get("protocolVersion");
        var requested = version != null && version.isJsonPrimitive() ? version.getAsString() : null;
        var result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        var capabilities = new JsonObject();
        var tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        var info = new JsonObject();
        info.addProperty("name", "cct-run");
        info.addProperty("title", "cct-run: CC: Tweaked " + Versions.CC_TWEAKED + " test environment");
        info.addProperty("version", Versions.TOOL);
        result.add("serverInfo", info);
        result.addProperty("instructions", """
            cct-run runs ComputerCraft (CC: Tweaked %s, Minecraft %s) Lua programs on a headless computer in real time \
            (20 ticks/s) and records everything that happens. Use it to test a program before giving it to the user, and \
            to debug it from the recorded evidence. Call cct_guide once before the first run: it explains the result \
            fields, the trace, fixtures (simulated peripherals, redstone, timed events) and what a passing run does and \
            does not prove. Runs are stored in %s. Relative paths resolve against %s; prefer absolute paths. \
            The cct_live_* tools work on real computers in a running Minecraft game with the cct-live mod (screen, \
            trace of events, peripheral calls, http and file access, printed errors, files, input, clicks, power); read \
            cct_guide with section 7 for them; attach a computer (cct_live_attach) before recording or controlling it, \
            and detach it when done; %s"""
            .formatted(Versions.CC_TWEAKED, Versions.MINECRAFT, outDir, workDir,
                liveDir == null ? "no game directory is configured, so pass game_dir to them." : "the game directory is " + liveDir + "."));
        return result;
    }

    private JsonObject callTool(JsonObject params, Call call) throws Exception {
        var name = params.has("name") ? params.get("name").getAsString() : "";
        var arguments = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        try {
            return switch (name) {
                case McpTools.RUN -> run(arguments, call);
                case McpTools.TRACE -> trace(arguments);
                case McpTools.SCREEN -> inProcess(McpTools.screenArgs(arguments, outDir), -1);
                case McpTools.GUIDE -> guide(arguments);
                default -> {
                    if (LiveTools.NAMES.contains(name)) yield live.call(name, arguments);
                    var unknown = new JsonObject();
                    unknown.addProperty("jsonrpcError", "Unknown tool: " + name);
                    yield unknown;
                }
            };
        } catch (McpTools.ToolError e) {
            return toolResult(e.getMessage(), true);
        }
    }

    // --- cct_run: one child JVM per run -------------------------------------------------------------------------

    private JsonObject run(JsonObject arguments, Call call) throws Exception {
        Path temp = null;
        try {
            var request = McpTools.runArgs(arguments, outDir, workDir);
            if (request.needsTempDir()) {
                temp = Files.createTempDirectory("cct-run-mcp-");
                request = request.materialize(temp);
            }
            var stdoutFile = Files.createTempFile("cct-run-mcp-", ".out");
            var stderrFile = Files.createTempFile("cct-run-mcp-", ".err");
            try {
                var builder = new ProcessBuilder(childCommand(request.argv()))
                    .directory(workDir.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile());
                var process = builder.start();
                process.getOutputStream().close(); // never let a run read the protocol stream
                if (!call.attach(process)) return toolResult("cancelled", true);
                var finished = process.waitFor(request.timeoutSeconds() + CHILD_GRACE_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return toolResult("cct-run did not finish within " + (request.timeoutSeconds() + CHILD_GRACE_SECONDS) + " s of wall time and was stopped.", true);
                }
                if (call.cancelled) return toolResult("cancelled", true);
                var code = process.exitValue();
                // new String(...) replaces undecodable bytes instead of throwing (stderr may come from the JVM itself).
                var stdout = new String(Files.readAllBytes(stdoutFile), StandardCharsets.UTF_8).strip();
                var stderr = request.hideTempPaths(new String(Files.readAllBytes(stderrFile), StandardCharsets.UTF_8).strip());
                if (code >= 0 && code <= Main.EX_OUT_OF_MEMORY && !stdout.isEmpty()) return toolResult(stdout, false);
                return toolResult("cct-run run failed with exit code " + code + " (" + exitMeaning(code) + ").\n" + stderr, true);
            } finally {
                Files.deleteIfExists(stdoutFile);
                Files.deleteIfExists(stderrFile);
            }
        } finally {
            if (temp != null) deleteTree(temp);
        }
    }

    private static String exitMeaning(int code) {
        return switch (code) {
            case Main.EX_USAGE -> "usage error";
            case Main.EX_DATAERR -> "bad input";
            case 3 -> "shutdown or reboot by the program";
            case 69 -> "no Java 17+";
            case Main.EX_SOFTWARE -> "internal error";
            default -> "unexpected";
        };
    }

    /** The command for a child cct-run on this same Java, with this JVM's flags (minus debugger/agent hooks). */
    static List<String> childCommand(List<String> cliArgs) {
        var exe = System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "java.exe" : "java";
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", exe).toString());
        for (var arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-agentlib") || arg.startsWith("-agentpath") || arg.startsWith("-javaagent") || arg.startsWith("-Xrunjdwp")) continue;
            command.add(arg);
        }
        command.add("-D" + Main.STDERR_ENCODING + "=UTF-8"); // read back as UTF-8 in run()
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(cliArgs);
        return command;
    }

    // --- read-only tools: CLI code in this process ---------------------------------------------------------------

    /** cct_guide: the whole guide, or one section (some clients cut long tool results). */
    private JsonObject guide(JsonObject arguments) throws McpTools.ToolError {
        var section = McpTools.guideSection(arguments);
        var whole = inProcess(List.of("guide"), -1);
        if (section == null || whole.get("isError").getAsBoolean()) return whole;
        var text = whole.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        var parts = text.split("\n(?=## )");
        for (var part : parts) {
            if (part.startsWith("## " + section + ".")) return toolResult(part.strip(), false);
        }
        var headings = new StringBuilder("No section " + section + ". Sections:");
        for (var part : parts) if (part.startsWith("## ")) headings.append("\n").append(part.lines().findFirst().orElse(""));
        return toolResult(headings.toString(), true);
    }

    /**
     * cct_trace: ask the CLI for one record more than the caller wants, so the "stopped at limit" note is only
     * added when there really is another record (it used to be added whenever the result was exactly at the limit).
     */
    private JsonObject trace(JsonObject arguments) throws McpTools.ToolError {
        var limit = McpTools.traceLimit(arguments);
        var argv = McpTools.traceArgs(arguments, outDir);
        var canPeek = limit < 100_000;
        if (canPeek) {
            var at = argv.lastIndexOf("--limit");
            if (at >= 0 && at + 1 < argv.size()) argv.set(at + 1, Integer.toString(limit + 1));
        }
        var json = inProcess(argv, -1);
        if (json.get("isError").getAsBoolean()) return json;
        var text = json.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        var lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
        var hasMore = canPeek && lines.length > limit;
        if (hasMore) text = String.join("\n", java.util.Arrays.copyOf(lines, limit));
        if (text.isBlank()) text = "(no matching trace records)";
        if (hasMore) text += "\n(stopped at limit " + limit + "; call again with offset increased by " + limit + " for more)";
        return toolResult(text, false);
    }

    private JsonObject inProcess(List<String> argv, int limit) {
        var buffer = new ByteArrayOutputStream();
        try (var print = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            Main.dispatch(argv.toArray(String[]::new), print);
        } catch (Main.UsageException | Main.InputException e) {
            return toolResult(e.getMessage(), true);
        } catch (InvalidPathException e) {
            return toolResult("path cannot be used with this system's file name encoding: " + e.getInput(), true);
        } catch (Exception e) {
            e.printStackTrace();
            return toolResult("internal error: " + e, true);
        }
        var text = buffer.toString(StandardCharsets.UTF_8).strip();
        if (limit > 0) {
            var count = text.isEmpty() ? 0 : text.split("\n", -1).length;
            if (count == 0) text = "(no matching trace records)";
            else if (count >= limit) text += "\n(stopped at limit " + limit + "; call again with offset increased by " + limit + " for more)";
        }
        return toolResult(text, false);
    }

    // --- JSON-RPC plumbing ---------------------------------------------------------------------------------------

    static JsonObject toolResult(String text, boolean isError) {
        var content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        var array = new JsonArray();
        array.add(content);
        var result = new JsonObject();
        result.add("content", array);
        result.addProperty("isError", isError);
        return result;
    }

    private void reply(JsonElement id, JsonObject result) {
        if (id == null) return;
        var message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.add("id", id);
        message.add("result", result);
        send(message);
    }

    private static JsonObject error(JsonElement id, int code, String text) {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", text);
        var message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.add("id", id == null ? JsonNull.INSTANCE : id);
        message.add("error", error);
        return message;
    }

    /** One message per line, ASCII only (non-ASCII escaped), so no console code page can corrupt it. */
    private synchronized void send(JsonObject message) {
        try {
            out.write(Main.ascii(COMPACT.toJson(message)).getBytes(StandardCharsets.US_ASCII));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            System.err.println("cct-run mcp: cannot write to stdout: " + e);
        }
    }

    private static void deleteTree(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** State of one tools/call, for cancellation. */
    static final class Call {
        volatile boolean cancelled;
        private Process process;

        synchronized boolean attach(Process process) {
            this.process = process;
            if (cancelled) process.destroyForcibly();
            return !cancelled;
        }

        synchronized void cancel() {
            cancelled = true;
            if (process != null) process.destroyForcibly();
        }
    }
}
