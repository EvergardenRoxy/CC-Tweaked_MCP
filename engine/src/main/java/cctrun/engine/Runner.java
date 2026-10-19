// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import cctrun.engine.peripheral.EnergyPeripheral;
import cctrun.engine.peripheral.Inventory;
import cctrun.engine.peripheral.ScriptedPeripheral;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dan200.computercraft.core.ComputerContext;
import dan200.computercraft.core.CoreConfig;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.computer.mainthread.MainThread;
import dan200.computercraft.core.computer.mainthread.MainThreadConfig;
import dan200.computercraft.core.filesystem.MemoryMount;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Runs one program on one headless CC: Tweaked computer in real time (20 ticks per second) and writes
 * a run directory: {@code summary.json}, {@code trace.jsonl}, {@code program/<name>} and
 * {@code fixture.json}.
 * <p>
 * Designed for one run per JVM (the CLI exits afterwards): {@link CoreConfig} is global state.
 */
public final class Runner {
    public static final int TPS = 20;
    /**
     * Computer.START_DELAY in CC: Tweaked 1.120.2: a computer starts at most once per 50 ticks, so os.reboot()
     * soon after boot leaves it off for up to 2.5 s before the new boot.
     */
    private static final int START_DELAY = 50;
    /** Private Computer.startRequested: set while a reboot waits for START_DELAY. Null if CC: Tweaked renamed it. */
    private static final java.lang.reflect.Field START_REQUESTED = startRequestedField();
    private static final int START_GRACE_TICKS = 10;
    private static final int MIN_TICKS_FOR_RATE = 40;
    private static final long MB = 1024 * 1024;
    /** CC: Tweaked's default computer_space_limit. Reported by fs.getFreeSpace; writes are not limited (see handoff.md). */
    private static final long DISK_CAPACITY = 1_000_000;
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private Runner() {
    }

    public static RunResult run(RunSpec spec) throws IOException {
        var created = Instant.now();
        var runDir = RunState.directory(spec.outDir());
        RunState.started(runDir);
        var runId = runDir.getFileName().toString();
        Files.createDirectory(runDir.resolve("program"));

        var fixture = spec.fixture();
        var advanced = spec.advanced() != null ? spec.advanced() : fixture.advanced != null ? fixture.advanced : Boolean.TRUE;
        var size = spec.termSize() != null ? spec.termSize() : fixture.term != null ? fixture.term : new int[]{ 51, 19 };
        var storedName = storedName(spec.programName());

        Files.writeString(runDir.resolve("program").resolve(storedName), spec.programSource(), StandardCharsets.UTF_8);
        if (spec.fixtureSource() != null) Files.writeString(runDir.resolve("fixture.json"), spec.fixtureSource(), StandardCharsets.UTF_8);

        // Untrusted code: no network access.
        CoreConfig.httpEnabled = false;
        CoreConfig.httpWebsocketEnabled = false;
        // Settings cct-run applies to every computer (see injectedSettings).
        var injected = injectedSettings(fixture);
        CoreConfig.defaultComputerSettings = injected.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(","));

        var clock = new Clock();
        try (var trace = new Trace(runDir.resolve("trace.jsonl"), clock)) {
            var mount = new MemoryMount(DISK_CAPACITY);
            for (var file : fixture.files.entrySet()) addFile(mount, file.getKey(), file.getValue());
            // The program is written after the fixture files, so a same-named fixture file would be replaced
            // silently. Say so in summary.notes instead of leaving the fixture author guessing.
            if (fixture.files.containsKey(storedName)) {
                var data = new JsonObject();
                data.addProperty("path", "/" + storedName);
                data.addProperty("note", "the fixture file was replaced by the program source");
                trace.note("fixture_file_overwritten", data);
            }
            addFile(mount, storedName, spec.programSource());
            var reportToken = java.util.UUID.randomUUID().toString();
            addFile(mount, "startup.lua", harness(storedName, spec.args(), reportToken));

            var rootMount = new RootMount(mount);
            var env = new RunnerEnvironment(rootMount);
            var terminal = TracingTerminal.create(size[0], size[1], advanced, "term", trace);
            var mainThread = new MainThread(MainThreadConfig.DEFAULT);
            var machines = new RecordingMachine.Holder();
            var context = ComputerContext.builder(env)
                .mainThreadScheduler(mainThread)
                .luaFactory(RecordingMachine.factory(trace, machines, injected))
                .build();
            var computer = new Computer(context, env, terminal, fixture.computerId);
            if (fixture.label != null) computer.setLabel(fixture.label);
            var api = new HarnessApi(trace, terminal, reportToken);
            computer.addApi(api);

            var built = fixture.build(trace, advanced);
            for (var entry : built.sides().entrySet()) computer.getEnvironment().setPeripheral(entry.getKey(), entry.getValue());
            for (var entry : fixture.redstone.entrySet()) computer.getRedstone().setInput(entry.getKey(), entry.getValue(), 0);

            var start = new JsonObject();
            start.addProperty("program", storedName);
            start.add("args", JsonValues.array(spec.args().toArray()));
            start.addProperty("timeout_s", spec.timeoutSeconds());
            trace.record("run_start", start);
            if (spec.fixtureSource() != null) {
                var source = com.google.gson.JsonParser.parseString(spec.fixtureSource()).getAsJsonObject();
                for (var key : List.of("peripherals", "timeline")) {
                    if (source.has(key)) noteWideText(source.get(key), "$." + key, trace);
                }
            }

            var pacer = TickPacer.realTime(TPS);
            var outcome = loop(spec, clock, trace, computer, mainThread, terminal, built, machines, api, fixture, pacer);
            if (machines.failures.outOfMemory) outcome = "out_of_memory";

            var timeoutState = outcome.equals("timeout") ? machines.executionState() : null;
            // The VM only has a state to print while Lua code is running, not while it waits for an event.
            if (timeoutState != null && timeoutState.isBlank()) timeoutState = null;
            List<String> finalScreen;
            if (api.finished) finalScreen = api.screen;
            else if ((outcome.equals("shutdown") || outcome.equals("reboot")) && terminal.beforeReset() != null) finalScreen = terminal.beforeReset();
            else finalScreen = terminal.lines();
            var monitorScreens = new JsonObject();
            for (var monitor : built.monitors()) {
                monitor.terminal().snapshot();
                monitorScreens.add(monitor.terminal().source(), stringArray(monitor.terminal().lines()));
            }

            computer.shutdown();
            computer.tick();
            computer.unload();
            // Ask the computer threads to stop without waiting for them: the process exits right after.
            try {
                if (!context.close(50, TimeUnit.MILLISECONDS)) trace.record("context_close_pending", new JsonObject());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var exitCode = switch (outcome) {
                case "ok" -> 0;
                case "error" -> 1;
                case "timeout" -> 2;
                case "out_of_memory" -> 4;
                default -> 3;
            };

            var end = new JsonObject();
            end.addProperty("status", outcome);
            end.addProperty("exit_code", exitCode);
            trace.record("run_end", end);

            var summary = summary(spec, fixture, created, runId, storedName, outcome, exitCode, clock, pacer, api, finalScreen,
                terminal, monitorScreens, timeoutState, advanced, size, trace);
            var disk = new JsonObject();
            disk.addProperty("capacity_bytes", rootMount.getCapacity());
            disk.addProperty("free_bytes", rootMount.getRemainingSpace());
            disk.addProperty("written_bytes", rootMount.writtenBytes());
            summary.add("disk", disk);
            if (Thread.currentThread().isInterrupted()) summary.addProperty("termination_reason", "interrupted");
            trace.close();
            Files.writeString(runDir.resolve("summary.json"), Trace.GSON.newBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
            RunState.update(runDir, "finished", outcome);
            return new RunResult(outcome, exitCode, runDir, summary);
        }
    }

    /** Tick loop. Returns ok | error | timeout | shutdown | reboot. */
    private static String loop(RunSpec spec, Clock clock, Trace trace, Computer computer, MainThread mainThread, TracingTerminal terminal,
                               Fixture.Built built, RecordingMachine.Holder machines, HarnessApi api, Fixture fixture, TickPacer pacer) {
        var maxTicks = spec.timeoutSeconds() * TPS;
        var everOn = false;
        var bootTick = 0;
        var boots = 0;
        var lastStartPending = -1;
        var actions = fixture.timeline.iterator();
        Fixture.TimelineAction nextAction = actions.hasNext() ? actions.next() : null;
        var outside = fixture.timeline.stream().filter(a -> a.tick() >= maxTicks).count();
        if (outside > 0) {
            var note = new JsonObject();
            note.addProperty("count", outside);
            note.addProperty("message", "timeline actions at or after the timeout will not run");
            trace.note("fixture_actions_outside_timeout", note);
        }
        computer.turnOn();
        for (var tick = 0; ; tick++) {
            clock.setTick(tick);
            if (tick >= maxTicks) return "timeout";

            while (nextAction != null && nextAction.tick() <= tick) {
                var action = nextAction;
                nextAction = actions.hasNext() ? actions.next() : null;
                try {
                    apply(action, computer, built, trace);
                } catch (RuntimeException e) {
                    // A fixture action that fails at run time (bad slot, wrong type, ...) must not kill the whole
                    // process: record it, make the run end as an error, and still write summary.json.
                    var data = new JsonObject();
                    data.addProperty("ok", false);
                    data.addProperty("action", action.action());
                    data.addProperty("path", action.path());
                    data.addProperty("error", String.valueOf(e));
                    trace.note("fixture_action_error", data);
                    api.ok = false;
                    api.phase = "fixture";
                    api.message = "timeline action " + action.path() + " failed: " + e;
                    api.screen = terminal.lines();
                    api.finished = true;
                    return "error";
                }
            }

            computer.tick();
            mainThread.tick();

            var changed = computer.pollRedstoneChanges();
            for (var side : ComputerSide.values()) {
                if ((changed & (1 << side.ordinal())) == 0) continue;
                var data = new JsonObject();
                data.addProperty("side", side.getName());
                data.addProperty("level", computer.getRedstone().getExternalOutput(side));
                data.addProperty("bundled", computer.getRedstone().getExternalBundledOutput(side));
                trace.record("redstone_output", data);
            }

            terminal.snapshot();
            for (var monitor : built.monitors()) monitor.terminal().snapshot();

            if (api.finished) return api.ok ? "ok" : "error";
            if (machines.failures.machineError != null) {
                api.phase = "vm";
                api.message = machines.failures.machineError;
                return "error";
            }
            if (machines.created.get() > 1) return "reboot";
            // A warm JVM can boot, run and shut down within one tick, so "has started" is "a VM was created".
            everOn |= computer.isOn() || machines.created.get() > 0;
            if (machines.created.get() != boots) {
                boots = machines.created.get();
                bootTick = tick;
            }
            // os.reboot() also turns the computer off, then starts it again once START_DELAY has passed. After the
            // start is handed to the computer thread there is a short gap before it is on, hence the grace ticks.
            if (rebootPending(computer, tick, bootTick)) lastStartPending = tick;
            if (everOn && !computer.isOn() && tick - lastStartPending > START_GRACE_TICKS) return "shutdown";

            try {
                var behind = pacer.awaitTick(tick + 1);
                if (behind > 0) {
                    var data = new JsonObject();
                    data.addProperty("behind_ms", behind / 1_000_000);
                    data.addProperty("note", "tick loop fell behind; missed ticks were skipped");
                    trace.note("tick_lag", data);
                }
            } catch (InterruptedException e) {
                var reason = new JsonObject();
                reason.addProperty("reason", "interrupted");
                trace.note("run_interrupted", reason);
                Thread.currentThread().interrupt();
                return "timeout";
            }
        }
    }

    private static boolean rebootPending(Computer computer, int tick, int bootTick) {
        if (START_REQUESTED != null) {
            try {
                return START_REQUESTED.getBoolean(computer);
            } catch (IllegalAccessException ignored) {
                // Fall through to the timing rule.
            }
        }
        return tick - bootTick <= START_DELAY + 5;
    }

    private static java.lang.reflect.Field startRequestedField() {
        try {
            var field = Computer.class.getDeclaredField("startRequested");
            if (field.getType() != boolean.class) return null;
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void apply(Fixture.TimelineAction action, Computer computer, Fixture.Built built, Trace trace) {
        var spec = action.spec();
        var data = new JsonObject();
        data.addProperty("do", action.action());
        switch (action.action()) {
            case "event" -> {
                var args = spec.has("args") ? spec.getAsJsonArray("args") : new JsonArray();
                var values = new Object[args.size()];
                for (var i = 0; i < args.size(); i++) values[i] = JsonValues.toLua(args.get(i));
                computer.queueEvent(spec.get("name").getAsString(), values);
                data.addProperty("name", spec.get("name").getAsString());
                data.add("args", args);
            }
            case "terminate" -> computer.queueEvent("terminate", null);
            case "redstone" -> {
                var side = ComputerSide.valueOfInsensitive(spec.get("side").getAsString());
                var level = spec.get("level").getAsInt();
                computer.getRedstone().setInput(side, level, 0);
                data.addProperty("side", side.getName());
                data.addProperty("level", level);
            }
            case "energy" -> {
                var name = spec.get("peripheral").getAsString();
                var storage = (EnergyPeripheral.Storage) built.byName().get(name).getTarget();
                storage.setEnergy(spec.get("energy").getAsLong());
                data.addProperty("peripheral", name);
                data.addProperty("energy", spec.get("energy").getAsLong());
            }
            case "item" -> {
                var name = spec.get("peripheral").getAsString();
                var inventory = (Inventory) built.byName().get(name).getTarget();
                var slot = spec.get("slot").getAsInt();
                var item = spec.get("item");
                inventory.set(slot - 1, item.isJsonNull() ? null : Fixture.itemUnchecked(item));
                data.addProperty("peripheral", name);
                data.addProperty("slot", slot);
                data.add("item", item);
            }
            case "method" -> {
                var name = spec.get("peripheral").getAsString();
                var behaviours = ((ScriptedPeripheral) built.byName().get(name)).getTarget();
                var method = spec.get("method").getAsString();
                var behaviour = new JsonObject();
                for (var key : new String[]{"returns", "sequence", "error"}) if (spec.has(key)) behaviour.add(key, spec.get(key));
                behaviours.get(method).set(behaviour);
                data.addProperty("peripheral", name);
                data.addProperty("method", method);
                for (var entry : behaviour.entrySet()) data.add(entry.getKey(), entry.getValue());
            }
            default -> throw new IllegalStateException("validated earlier: " + action.action());
        }
        trace.record("fixture_action", data);
    }

    /**
     * Heap figures of this JVM (cct-run itself included): the limit (-Xmx, 1 GB from the start scripts) and the
     * highest use seen, as the sum of each heap pool's peak, so an upper bound.
     */
    static JsonObject memory() {
        long peak = 0;
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        }
        var memory = new JsonObject();
        memory.addProperty("heap_limit_mb", Runtime.getRuntime().maxMemory() / MB);
        memory.addProperty("heap_peak_mb", peak / MB);
        return memory;
    }

    private static JsonObject summary(RunSpec spec, Fixture fixture, Instant created, String runId, String storedName, String outcome,
                                      int exitCode, Clock clock, TickPacer pacer, HarnessApi api, List<String> finalScreen, TracingTerminal terminal,
                                      JsonObject monitorScreens, String timeoutState, boolean advanced, int[] size, Trace trace) {
        var out = new JsonObject();
        out.addProperty("schema", Versions.SUMMARY_SCHEMA);
        out.addProperty("run_id", runId);
        out.addProperty("created_at", created.toString());
        out.addProperty("status", outcome);
        out.addProperty("exit_code", exitCode);

        var error = new JsonObject();
        if (outcome.equals("error") || outcome.equals("out_of_memory")) {
            error.addProperty("phase", api.phase);
            error.addProperty("message", api.message);
            error.addProperty("traceback", api.traceback);
            out.add("error", error);
        } else {
            out.add("error", null);
        }
        if (timeoutState != null) out.addProperty("timeout_state", timeoutState.strip());

        var duration = new JsonObject();
        duration.addProperty("ticks", clock.tick());
        duration.addProperty("seconds", clock.tick() / (double) TPS);
        duration.addProperty("wall_ms", clock.millis());
        // Measured over the tick loop; should be close to 20. Lower means game time ran slow (see handoff.md DV14).
        // Omitted for runs under 2 s, where JVM warm-up in the first ticks dominates the figure.
        if (clock.tick() >= MIN_TICKS_FOR_RATE) duration.addProperty("tick_rate", Math.round(pacer.tickRate(clock.tick()) * 10) / 10.0);
        if (pacer.lagResets() > 0) duration.addProperty("lag_resets", pacer.lagResets());
        out.add("duration", duration);
        out.add("memory", memory());

        var screen = new JsonObject();
        screen.add("size", intArray(size[0], size[1]));
        screen.add("lines", stringArray(finalScreen));
        out.add("screen", screen);
        if (monitorScreens.size() > 0) out.add("monitors", monitorScreens);

        var counts = new JsonObject();
        for (var entry : trace.counts().entrySet()) counts.addProperty(entry.getKey(), entry.getValue());
        out.add("trace_counts", counts);
        out.add("notes", trace.notes());
        if (trace.droppedNotes() > 0) out.addProperty("notes_dropped", trace.droppedNotes());

        var input = new JsonObject();
        var program = new JsonObject();
        program.addProperty("name", spec.programName());
        program.addProperty("path", "/" + storedName);
        program.addProperty("sha256", sha256(spec.programSource()));
        program.addProperty("bytes", spec.programSource().getBytes(StandardCharsets.UTF_8).length);
        input.add("program", program);
        input.add("args", JsonValues.array(spec.args().toArray()));
        input.addProperty("fixture_sha256", spec.fixtureSource() == null ? null : sha256(spec.fixtureSource()));
        out.add("input", input);

        var env = new JsonObject();
        env.addProperty("tool", Versions.TOOL);
        env.addProperty("cc_tweaked", Versions.CC_TWEAKED);
        env.addProperty("minecraft", Versions.MINECRAFT);
        env.addProperty("java", System.getProperty("java.version"));
        env.addProperty("computer_id", fixture.computerId);
        env.addProperty("advanced", advanced);
        env.addProperty("multishell", fixture.multishell);
        env.addProperty("http", false);
        env.addProperty("timeout_s", spec.timeoutSeconds());
        env.addProperty("time_model", "real-time, " + TPS + " ticks/s");
        out.add("environment", env);

        var files = new JsonObject();
        files.addProperty("trace", "trace.jsonl");
        files.addProperty("program", "program/" + storedName);
        if (spec.fixtureSource() != null) files.addProperty("fixture", "fixture.json");
        out.add("files", files);
        return out;
    }

    /**
     * Settings cct-run sets on every computer through CC: Tweaked's default settings. Each one that differs from
     * CraftOS's default produces one setting_changed event at boot, which RecordingMachine flags as cct-run's.
     * Without multishell the program writes straight to the native terminal, so lines that scroll off are recorded
     * exactly (with multishell, a Lua window redraws via blit and never calls Terminal.scroll). The startup tip is
     * noise in the first screen record.
     */
    static java.util.Map<String, Object> injectedSettings(Fixture fixture) {
        var settings = new java.util.LinkedHashMap<String, Object>();
        settings.put("motd.enable", false);
        if (!fixture.multishell) settings.put("bios.use_multishell", false);
        return settings;
    }

    /** The harness owns /startup.lua, so a program called startup.lua is stored as main.lua. */
    static String storedName(String programName) {
        var name = Path.of(programName).getFileName().toString();
        if (name.equals("startup.lua") || name.equals("startup")) return "main.lua";
        return name;
    }

    static String harness(String path, List<String> args, String token) {
        String template;
        try (var in = Runner.class.getResourceAsStream("/cctrun/harness.lua")) {
            if (in == null) throw new IllegalStateException("harness.lua missing from the jar");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var luaArgs = new StringBuilder();
        for (var i = 0; i < args.size(); i++) {
            if (i > 0) luaArgs.append(", ");
            luaArgs.append(luaString(args.get(i)));
        }
        return template.replace("__CCTRUN_PATH__", luaString(path)).replace("__CCTRUN_ARGS__", luaArgs).replace("__CCTRUN_TOKEN__", luaString(token));
    }

    static Path allocateRunDirectory(Path parent, String timestamp, java.util.function.Supplier<String> suffix) throws IOException {
        Files.createDirectories(parent);
        for (int attempt = 0; attempt < 100; attempt++) {
            var path = parent.resolve(timestamp + "-" + suffix.get());
            try { return Files.createDirectory(path); }
            catch (java.nio.file.FileAlreadyExistsException ignored) { }
        }
        throw new IOException("could not allocate a unique run directory");
    }

    /** A Lua string literal for any text: printable ASCII as is, everything else as \ddd bytes (UTF-8). */
    static String luaString(String value) {
        var out = new StringBuilder("\"");
        for (var b : value.getBytes(StandardCharsets.UTF_8)) {
            var c = b & 0xff;
            if (c == '"' || c == '\\') out.append('\\').append((char) c);
            else if (c >= 0x20 && c < 0x7f) out.append((char) c);
            else out.append('\\').append(String.format("%03d", c));
        }
        return out.append('"').toString();
    }

    /**
     * Fixture strings reach Lua through Java (event arguments, peripheral results), so characters above U+00FF arrive
     * as '?', as they do in the game. Say so: a test that injects Chinese text otherwise fails without a clue.
     * Files are not affected (they are written as UTF-8).
     */
    private static void noteWideText(JsonElement value, String path, Trace trace) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) noteWideText(entry.getValue(), path + "." + entry.getKey(), trace);
        } else if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            for (var i = 0; i < array.size(); i++) noteWideText(array.get(i), path + "[" + i + "]", trace);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            var text = value.getAsString();
            if (text.chars().noneMatch(c -> c > 0xFF)) return;
            var data = new JsonObject();
            data.addProperty("path", path);
            data.addProperty("lua_sees", JsonValues.truncate(JsonValues.asLua(text)));
            data.addProperty("hint", "characters above U+00FF reach Lua as '?', as Java strings do in the game; for UTF-8 bytes "
                + "(e.g. the chat box's messageUtf8) write each byte as an escape up to U+00FF (guide section 4)");
            trace.note("fixture_text", data);
        }
    }

    private static void addFile(MemoryMount mount, String path, String contents) throws IOException {
        var slash = path.lastIndexOf('/');
        if (slash > 0) mount.makeDirectory(path.substring(0, slash));
        mount.addFile(path, contents);
    }

    private static JsonArray stringArray(List<String> values) {
        var out = new JsonArray();
        for (var value : values) out.add(value);
        return out;
    }

    private static JsonArray intArray(int... values) {
        var out = new JsonArray();
        for (var value : values) out.add(value);
        return out;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
