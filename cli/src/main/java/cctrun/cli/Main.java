// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import cctrun.engine.Fixture;
import cctrun.engine.FixtureException;
import cctrun.engine.RunSpec;
import cctrun.engine.Runner;
import cctrun.engine.TraceQuery;
import cctrun.engine.Versions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Command-line entry point. All machine-readable output goes to stdout as ASCII-only JSON (non-ASCII characters are
 * escaped as \\uXXXX); diagnostics go to stderr.
 * Exit codes: run 0/1/2/3/4 (see RunResult), 64 usage error, 65 bad input, 70 internal error.
 * {@code mcp} serves the same commands as MCP tools (McpServer).
 */
public final class Main {
    static final int EX_OUT_OF_MEMORY = 4, EX_USAGE = 64, EX_DATAERR = 65, EX_SOFTWARE = 70;
    /** System property naming the charset for stderr (see main). */
    static final String STDERR_ENCODING = "cctrun.stderr.encoding";
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Path DEFAULT_OUT = Path.of(".cct-runs");

    private static final String USAGE = """
        Usage:
          cct-run run <program.lua> [--fixture FILE] [--timeout SECONDS] [--out DIR] [--term WxH]
                                    [--basic | --advanced] [--] [program args...]
          cct-run trace <run-dir|latest> [--kind K1,K2] [--ticks A-B] [--peripheral NAME] [--source NAME] [--contains TEXT]
                                         [--offset N] [--limit N] [--out DIR]
          cct-run screen <run-dir|latest> [--tick N] [--source term|MONITOR] [--out DIR]
          cct-run guide
          cct-run mcp [--out DIR] [--live GAME_DIR]
          cct-run version

        run      Runs the program on a headless CC: Tweaked %s computer (Minecraft %s) in real time and
                 prints the summary as JSON. Exit code: 0 ok, 1 Lua error, 2 timeout, 3 shutdown/reboot,
                 4 out of memory (the heap limit, 1 GB by default, was reached).
                 The run directory (summary.json, trace.jsonl, inputs) goes under --out (default .cct-runs).
        trace    Prints trace records (JSON Lines) of a run, filtered. Default --limit 200.
                 Kinds: event screen output peripheral_call peripheral_return redstone_output
                        modem_transmit fixture_action program_end vm_error run_start run_end
        screen   Prints the screen of the computer ("term", default) or of a monitor at a tick.
        guide    Prints the agent guide: workflow, reading results, fixtures, what a pass means. Read it first.
        mcp      Runs a local MCP server on stdin/stdout (tools cct_run, cct_trace, cct_screen, cct_guide).
                 Start it from an MCP client, not by hand. Runs go to --out (default .cct-runs).
                 --live GAME_DIR (or CCT_LIVE_DIR): Minecraft game directory with the cct-live mod, for the
                 cct_live_* tools (computers, screen, attach, detach, trace, event, type, power) on real
                 in-game computers.
        version  Prints tool, CC: Tweaked, Minecraft and Java versions as JSON.
        """.formatted(Versions.CC_TWEAKED, Versions.MINECRAFT);

    private Main() {
    }

    public static void main(String[] argv) {
        // Set by `cct-run mcp` for its child runs, which reads stderr back: without it, Java writes stderr in the
        // platform encoding (GBK on Chinese Windows), and a non-ASCII path in an error message could not be decoded.
        var stderrEncoding = System.getProperty(STDERR_ENCODING);
        if (stderrEncoding != null) System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, Charset.forName(stderrEncoding)));
        var stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = dispatch(argv, stdout);
        } catch (UsageException e) {
            System.err.println("cct-run: " + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            code = EX_USAGE;
        } catch (InputException e) {
            System.err.println("cct-run: " + e.getMessage());
            code = EX_DATAERR;
        } catch (java.nio.file.InvalidPathException e) {
            // Typically a non-ASCII path under a POSIX/C locale, where Java cannot encode file names.
            System.err.println("cct-run: path cannot be used with this system's file name encoding (sun.jnu.encoding="
                + System.getProperty("sun.jnu.encoding") + "): " + e.getInput()
                + "\n  Use an ASCII path, or a UTF-8 locale (e.g. LC_ALL=C.UTF-8 on Linux/macOS).");
            code = EX_DATAERR;
        } catch (OutOfMemoryError e) {
            // Outside Lua (Cobalt reports those as Lua errors): the run could not finish its summary.
            code = outOfMemory(stdout, e);
        } catch (Exception e) {
            System.err.println("cct-run: internal error: " + e);
            e.printStackTrace();
            code = EX_SOFTWARE;
        }
        stdout.flush();
        // Exit explicitly: CC: Tweaked's computer threads may still be winding down.
        System.exit(code);
    }

    static int dispatch(String[] argv, PrintStream out) throws Exception {
        if (argv.length == 0) throw new UsageException("missing command");
        var rest = Arrays.asList(argv).subList(1, argv.length);
        return switch (argv[0]) {
            case "run" -> run(new Args(rest, Set.of("--fixture", "--timeout", "--out", "--term"), Set.of("--basic", "--advanced")), out);
            case "trace" -> trace(new Args(rest, Set.of("--kind", "--ticks", "--peripheral", "--source", "--contains", "--offset", "--limit", "--out"), Set.of()), out);
            case "screen" -> screen(new Args(rest, Set.of("--tick", "--source", "--out"), Set.of()), out);
            case "version", "--version" -> version(out);
            case "guide" -> guide(out);
            case "mcp" -> McpServer.main(new Args(rest, Set.of("--out", "--live"), Set.of()), out);
            case "help", "--help", "-h" -> {
                out.print(USAGE);
                yield 0;
            }
            default -> throw new UsageException("unknown command '" + argv[0] + "'");
        };
    }

    private static int run(Args args, PrintStream out) throws Exception {
        var fixturePath = args.option("--fixture");
        var timeout = args.intOption("--timeout", 15, 1, 3600);
        var outDir = args.pathOption("--out", DEFAULT_OUT);
        var termOption = args.option("--term");
        var basic = args.flag("--basic");
        var advancedFlag = args.flag("--advanced");
        if (basic && advancedFlag) throw new UsageException("--basic and --advanced are exclusive");
        var positional = args.positional();
        if (positional.isEmpty()) throw new UsageException("run: missing <program.lua>");

        var programPath = Path.of(positional.get(0));
        var programSource = readFile(programPath, "program");
        var programArgs = positional.subList(1, positional.size());

        var fixture = Fixture.empty();
        String fixtureSource = null;
        if (fixturePath != null) {
            fixtureSource = readFile(Path.of(fixturePath), "fixture");
            try {
                fixture = Fixture.parse(fixtureSource);
            } catch (FixtureException e) {
                throw new InputException("fixture " + fixturePath + ": " + e.getMessage());
            }
        }
        int[] term = null;
        if (termOption != null) {
            try {
                term = parseTerm(termOption);
            } catch (IllegalArgumentException e) {
                throw new UsageException("--term: " + e.getMessage());
            }
        }

        var spec = new RunSpec(programPath.getFileName().toString(), programSource, programArgs, fixture, fixtureSource,
            timeout, term, basic ? Boolean.FALSE : advancedFlag ? Boolean.TRUE : null, outDir);
        var result = Runner.run(spec);

        var summary = result.summary().deepCopy();
        summary.addProperty("run_dir", result.runDir().toAbsolutePath().normalize().toString());
        out.println(ascii(PRETTY.toJson(summary)));
        return result.exitCode();
    }

    private static int trace(Args args, PrintStream out) throws Exception {
        var kinds = args.option("--kind");
        var ticks = args.option("--ticks");
        var peripheral = args.option("--peripheral");
        var source = args.option("--source");
        var offset = args.intOption("--offset", 0, 0, Integer.MAX_VALUE);
        var limit = args.intOption("--limit", 200, 1, 100_000);
        var outDir = args.pathOption("--out", DEFAULT_OUT);
        var runDir = runDir(args.positional(), outDir, "trace");

        Integer from = null, to = null;
        if (ticks != null) {
            var parts = ticks.split("-", -1);
            try {
                if (parts.length == 1) from = to = Integer.parseInt(parts[0]);
                else if (parts.length == 2) {
                    if (!parts[0].isEmpty()) from = Integer.parseInt(parts[0]);
                    if (!parts[1].isEmpty()) to = Integer.parseInt(parts[1]);
                } else throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new UsageException("--ticks: expected N, A-B, A- or -B");
            }
        }
        Set<String> kindSet = kinds == null ? null : Arrays.stream(kinds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        // Ask for one record more than requested, so "there is more" is known without reading the trace twice.
        var result = TraceQuery.read(runDir, new TraceQuery.Filter(kindSet, from, to, peripheral, source, args.option("--contains"), offset, limit + 1));
        var records = new ArrayList<>(result.records());
        var hasMore = records.size() > limit;
        if (hasMore) records.subList(limit, records.size()).clear();
        for (var record : records) out.println(ascii(COMPACT.toJson(record)));
        if (result.skipped() > 0) System.err.println("cct-run: skipped " + result.skipped() + " malformed trace line(s)");
        if (hasMore) System.err.println("cct-run: stopped at --limit " + limit + "; use --offset " + (offset + limit) + " for more");
        return 0;
    }

    private static int screen(Args args, PrintStream out) throws Exception {
        var tick = args.option("--tick");
        var source = args.option("--source");
        var outDir = args.pathOption("--out", DEFAULT_OUT);
        var runDir = runDir(args.positional(), outDir, "screen");
        Integer atTick = null;
        if (tick != null) {
            try {
                atTick = Integer.parseInt(tick);
            } catch (NumberFormatException e) {
                throw new UsageException("--tick: expected a number");
            }
        }
        var screen = TraceQuery.screenAt(runDir, source == null ? "term" : source, atTick);
        if (screen == null) throw new InputException("no screen recorded for source '" + (source == null ? "term" : source) + "'" + (atTick == null ? "" : " up to tick " + atTick));

        var json = new JsonObject();
        json.addProperty("source", screen.source());
        json.addProperty("tick", screen.tick());
        var size = new JsonArray();
        size.add(screen.width());
        size.add(screen.height());
        json.add("size", size);
        var cursor = new JsonArray();
        cursor.add(screen.cursorX());
        cursor.add(screen.cursorY());
        json.add("cursor", cursor);
        var lines = new JsonArray();
        screen.lines().forEach(lines::add);
        json.add("lines", lines);
        out.println(ascii(PRETTY.toJson(json)));
        return 0;
    }

    private static int guide(PrintStream out) throws IOException {
        try (var in = Main.class.getResourceAsStream("/cctrun/agent-guide.md")) {
            if (in == null) throw new IOException("agent-guide.md missing from the jar");
            out.print(ascii(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        return 0;
    }

    private static int outOfMemory(PrintStream out, OutOfMemoryError e) {
        var limitMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        var message = "cct-run ran out of memory (heap limit " + limitMb + " MB): " + e.getMessage();
        System.err.println("cct-run: " + message);
        var json = new JsonObject();
        json.addProperty("schema", Versions.SUMMARY_SCHEMA);
        json.addProperty("status", "out_of_memory");
        json.addProperty("exit_code", EX_OUT_OF_MEMORY);
        var error = new JsonObject();
        error.addProperty("phase", "runtime");
        error.addProperty("message", message);
        json.add("error", error);
        json.addProperty("note", "cct-run itself ran out of memory, so summary.json was not written and the trace may be incomplete");
        out.println(ascii(PRETTY.toJson(json)));
        return EX_OUT_OF_MEMORY;
    }

    private static int version(PrintStream out) {
        var json = new JsonObject();
        json.addProperty("tool", Versions.TOOL);
        json.addProperty("cc_tweaked", Versions.CC_TWEAKED);
        json.addProperty("minecraft", Versions.MINECRAFT);
        json.addProperty("java", System.getProperty("java.version"));
        json.addProperty("java_home", System.getProperty("java.home"));
        json.addProperty("heap_limit_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        out.println(ascii(PRETTY.toJson(json)));
        return 0;
    }

    private static Path runDir(List<String> positional, Path outDir, String command) throws UsageException, InputException, IOException {
        if (positional.size() != 1) throw new UsageException(command + ": expected exactly one <run-dir|latest>");
        var given = positional.get(0);
        if (!given.equals("latest")) {
            var dir = Path.of(given);
            // A bare run_id names a run in the output directory.
            if (!Files.isRegularFile(dir.resolve("trace.jsonl")) && !dir.isAbsolute()) {
                var inOut = outDir.resolve(given);
                if (Files.isRegularFile(inOut.resolve("trace.jsonl"))) return inOut;
            }
            if (!Files.isRegularFile(dir.resolve("trace.jsonl"))) throw new InputException("not a run directory (no trace.jsonl): " + dir);
            return dir;
        }
        if (!Files.isDirectory(outDir)) throw new InputException("no runs in " + outDir);
        // Run ids start with a UTC timestamp, so the lexicographically last one is the newest.
        try (var dirs = Files.list(outDir)) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve("trace.jsonl")))
                .max(Comparator.comparing(d -> d.getFileName().toString()))
                .orElseThrow(() -> new InputException("no runs in " + outDir));
        }
    }

    private static String readFile(Path path, String what) throws InputException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new InputException(what + " not found: " + path);
        } catch (IOException e) {
            throw new InputException("cannot read " + what + " " + path + ": " + e.getMessage());
        }
    }

    /**
     * Escape every non-ASCII character as \\uXXXX so stdout is plain ASCII. Consoles that are not UTF-8 (e.g.
     * Windows code page 936) then cannot garble it, and JSON parsers turn the escapes back into the same text.
     * Valid because JSON syntax itself is ASCII: any non-ASCII character is inside a string. Files on disk stay UTF-8.
     */
    static String ascii(String json) {
        StringBuilder out = null;
        for (var i = 0; i < json.length(); i++) {
            var c = json.charAt(i);
            if (c < 0x80) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(json.length() + 16).append(json, 0, i);
            out.append(String.format("\\u%04x", (int) c));
        }
        return out == null ? json : out.toString();
    }

    static int[] parseTerm(String value) {
        var parts = value.toLowerCase().split("x");
        if (parts.length == 2) {
            try {
                // Trim like Fixture.parseSize, so "--term \"26 x 20\"" is accepted in both places.
                var w = Integer.parseInt(parts[0].trim());
                var h = Integer.parseInt(parts[1].trim());
                if (w >= 1 && w <= 255 && h >= 1 && h <= 255) return new int[]{ w, h };
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException("expected WIDTHxHEIGHT, e.g. 51x19");
    }

    /**
     * Minimal option parser. Value options accept "--name VALUE" and "--name=VALUE"; flags take no value.
     * Options may appear anywhere; "--" ends option parsing (use it to pass "--x" to the program).
     */
    static final class Args {
        private final List<String> positional = new ArrayList<>();
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        private final Set<String> flags = new java.util.HashSet<>();

        Args(List<String> argv, Set<String> valueOptions, Set<String> flagOptions) throws UsageException {
            var onlyPositional = false;
            for (var i = 0; i < argv.size(); i++) {
                var arg = argv.get(i);
                if (onlyPositional || !arg.startsWith("--")) {
                    positional.add(arg);
                } else if (arg.equals("--")) {
                    onlyPositional = true;
                } else {
                    var eq = arg.indexOf('=');
                    var name = eq < 0 ? arg : arg.substring(0, eq);
                    if (valueOptions.contains(name)) {
                        String value;
                        if (eq >= 0) value = arg.substring(eq + 1);
                        else if (i + 1 < argv.size()) value = argv.get(++i);
                        else throw new UsageException(name + " needs a value");
                        if (values.put(name, value) != null) throw new UsageException(name + " given twice");
                    } else if (flagOptions.contains(name) && eq < 0) {
                        flags.add(name);
                    } else {
                        throw new UsageException("unknown option " + name);
                    }
                }
            }
        }

        String option(String name) {
            return values.get(name);
        }

        boolean flag(String name) {
            return flags.contains(name);
        }

        int intOption(String name, int fallback, int min, int max) throws UsageException {
            var value = option(name);
            if (value == null) return fallback;
            try {
                var n = Integer.parseInt(value);
                if (n < min || n > max) throw new NumberFormatException();
                return n;
            } catch (NumberFormatException e) {
                throw new UsageException(name + ": expected a whole number between " + min + " and " + max);
            }
        }

        Path pathOption(String name, Path fallback) {
            var value = option(name);
            return value == null ? fallback : Path.of(value);
        }

        List<String> positional() {
            return positional;
        }
    }

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    static final class InputException extends Exception {
        InputException(String message) {
            super(message);
        }
    }
}
