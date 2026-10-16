# handoff

```yaml
format: cct-run/handoff@1
audience: machine reviewer / next agent (not end users)
repo: EvergardenRoxy/CC-Tweaked_MCP
branch: claude/cct-run-cli
base: empty repository (this branch holds the first commits)
date: 2026-10-16
tool_version: 0.1.0 (engine/src/main/java/cctrun/engine/Versions.java)
target: CC: Tweaked 1.120.2 for Minecraft 1.20.1 (upstream tag v1.20.1-1.120.2)
status: CLI implemented, 25/25 engine tests pass, end-to-end verified on Linux (Java 17/21/25); MCP wrapper not started
readme: intentionally absent (user instruction); this file replaces it for now
```

## GOAL

- G1: Let an AI coding agent run a CC: Tweaked Lua program headlessly, outside Minecraft, and debug it from structured, Java-side recorded evidence (result, error + traceback, screens, output, events, peripheral calls, redstone).
- G2: Order of delivery: command-line tool first (`cct-run`), MCP wrapper over the CLI later.
- G3: Companion to the Agent Skill repo EvergardenRoxy/CC-Tweaked_LLM_skill (kept separate so skill installation is unaffected).

## DECISIONS

Source `user` = decided by the repo owner in conversation; `impl` = implementation choice, revisitable.

| id | decision | source |
|---|---|---|
| D1 | One JVM per run; no resident JVM. Reasons: CoreConfig is static global state, crash/OOM isolation, one process = one traceable run. | user |
| D2 | Access option A: CLI invoked through the agent's shell; MCP later as a thin wrapper over the CLI. | user |
| D3 | Remote/hosted MCP rejected. | user |
| D4 | Priority clients: Claude Code, DeepSeek Harness (`dsh`; Bash on Linux/macOS, PowerShell on Windows), Codex. All have shell access. | user |
| D5 | Users are Minecraft players: assume a Java runtime exists (Java 17+). | user |
| D6 | Trace purpose: AI debugging. Time model: real time (20 ticks/s). Recording on the Java side. | user |
| D7 | Base: published artifact `cc.tweaked:cc-tweaked-1.20.1-core:1.120.2` from https://maven.squiddev.cc (same code as tag v1.20.1-1.120.2); no CC:T fork/build, no Minecraft download. | user (tag) + impl (artifact) |
| D8 | CLI interface is provisional; keep room to change it. | user |
| D9 | No README.md yet; maintain this handoff.md. | user |
| D10 | Bytecode target Java 17 (`options.release = 17`), matching the runtime Minecraft 1.20.1 ships. | impl |
| D11 | Multishell off by default (`bios.use_multishell=false`) so scrolled-off lines are captured exactly; fixture `computer.multishell: true` re-enables it. | impl |
| D12 | Item transfer follows Forge semantics (target slots filled in slot order); Fabric fills existing stacks first. | impl |
| D13 | Wired-modem lookup methods (getNamesRemote, isPresentRemote, getTypeRemote, hasTypeRemote, getMethodsRemote) are not traced: CraftOS calls them dozens of times per `peripheral.find`. | impl |

## LAYOUT

```
settings.gradle.kts, build.gradle.kts      two subprojects: engine (library), cli (application)
gradle/libs.versions.toml                  versions; cctweaked = 1.120.2
gradlew, gradlew.bat, gradle/wrapper/      Gradle 9.7.1 wrapper (runs on JDK 17-25)
engine/src/main/java/cctrun/engine/
  Runner.java            one run: computer setup, tick loop, outcome detection, summary.json
  RunSpec.java           run input (record)
  RunResult.java         run output (record)
  Fixture.java           fixture parsing/validation (JSON path in every error) and peripheral construction
  FixtureException.java
  HarnessApi.java        Lua API __cctrun used only by harness.lua (removed from _G before the program runs)
  RecordingMachine.java  wraps CobaltLuaMachine; records every event delivered to the VM
  TracingTerminal.java   Terminal subclass: records scrolled-off lines (output) and per-tick screen diffs (screen)
  Trace.java             JSONL writer, counts, notes
  TraceQuery.java        read side: filtered records, screen reconstruction at a tick
  RunnerEnvironment.java ComputerEnvironment + GlobalEnvironment: in-memory root, ROM/BIOS from core jar
  JsonValues.java        Lua/Java values -> bounded JSON (strings 200 chars, 50 entries, depth 4)
  Clock.java, Versions.java
engine/src/main/java/cctrun/engine/peripheral/
  DispatchPeripheral.java    base: name->impl table, traces call/return, main-thread methods via executeMainThreadTask
  InventoryPeripheral.java, Inventory.java, ItemStack.java
  EnergyPeripheral.java, FluidPeripheral.java
  MonitorPeripheral.java     delegates terminal methods to CC:T's own TermMethods
  WiredModemPeripheral.java, RemoteAccess.java
engine/src/main/resources/cctrun/harness.lua   installed as /startup.lua; mirrors shell.lua executeProgram
engine/src/test/java/cctrun/engine/            RunnerTest, PeripheralTest, FixtureTest, TraceQueryTest, RunHelper
cli/src/main/java/cctrun/cli/Main.java         argument parsing, commands, exit codes
examples/                                      sample programs and fixtures used for end-to-end checks
```

## BUILD

```
./gradlew build                      # compile + 25 tests (~20 s of test time; some tests wait real game time)
./gradlew :cli:installDist           # -> cli/build/install/cct-run/{bin/cct-run, bin/cct-run.bat, lib/*.jar}
cli/build/install/cct-run/bin/cct-run run examples/hello.lua
```

- JVM flags baked into the start scripts (`cli/build.gradle.kts`): `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss4m`.
- Start scripts find Java via `JAVA_HOME`, else `java` on PATH (Gradle-generated). No other discovery yet (see L18).

## INTERFACE_CLI

Provisional (D8).

```
cct-run run <program.lua> [--fixture FILE] [--timeout SECONDS=15 (1..3600)] [--out DIR=.cct-runs]
                          [--term WxH] [--basic | --advanced] [--] [program args...]
cct-run trace  <run-dir|latest> [--kind K1,K2] [--ticks N|A-B|A-|-B] [--peripheral NAME] [--source NAME]
                                [--offset N=0] [--limit N=200] [--out DIR]
cct-run screen <run-dir|latest> [--tick N] [--source term|MONITOR_NAME] [--out DIR]
cct-run version
cct-run help
```

- Options: `--name VALUE` or `--name=VALUE`, anywhere on the line; `--` ends option parsing (needed to pass `--x` to the program).
- stdout: JSON only (`run`: pretty summary + `run_dir`; `trace`: one compact JSON object per line; `screen`/`version`: pretty JSON). stderr: diagnostics; `trace` prints a continuation hint to stderr when `--limit` is hit.
- `latest` = lexicographically greatest directory under `--out` that contains `trace.jsonl` (run ids start with a UTC timestamp).
- Precedence for computer settings: CLI flag > fixture `computer.*` > default (advanced, 51x19).

## EXIT_CODES

| code | meaning |
|---|---|
| 0 | run: program finished without error |
| 1 | run: Lua error (syntax or runtime, includes "Too long without yielding" and "Terminated") |
| 2 | run: timeout (program still running after `--timeout` seconds of real time) |
| 3 | run: program shut down or rebooted the computer |
| 64 | usage error (unknown command/option, bad option value) |
| 65 | bad input (program/fixture not found or unreadable, invalid fixture, not a run directory, no such screen source) |
| 70 | internal error (stack trace on stderr) |

## RUN_DIRECTORY

```
<out>/<run_id>/            run_id = yyyyMMdd-HHmmss-SSS (UTC) + "-" + 4 hex
  summary.json             see SUMMARY_FORMAT
  trace.jsonl              see TRACE_FORMAT
  program/<stored name>    the program as run
  fixture.json             the fixture text as given (only if --fixture)
```

## SUMMARY_FORMAT

`schema: "cct-run/summary@1"`. Keys:

| key | type | notes |
|---|---|---|
| schema, run_id, created_at | string | created_at ISO-8601 UTC |
| status | string | ok, error, timeout, shutdown, reboot |
| exit_code | int | 0/1/2/3 |
| error | object or null | {phase: syntax or runtime, message, traceback (string or null)}; only when status=error |
| timeout_state | string | only on timeout and only if the VM was executing Lua at that moment (CobaltLuaMachine.printExecutionState); absent while waiting for events |
| duration | object | {ticks, seconds (ticks/20), wall_ms} |
| screen | object | {size: [w,h], lines: [...]} final screen; trailing spaces and trailing blank lines removed. For status ok/error: captured when the harness reports; for shutdown/reboot: last non-blank screen before CC:T reset the terminal |
| monitors | object | {name: [lines]} final monitor screens; absent if no monitors |
| trace_counts | object | {kind: count} |
| notes | array | first 10 noteworthy records (peripheral errors, failed program_end, vm_error) with seq/tick/kind |
| input | object | {program: {name, path (in computer), sha256, bytes}, args, fixture_sha256} |
| environment | object | {tool, cc_tweaked, minecraft, java, computer_id, advanced, multishell, http: false, timeout_s, time_model} |
| files | object | relative paths of trace/program/fixture |
| run_dir | string | CLI stdout only (absolute path); not stored in summary.json |

## TRACE_FORMAT

`trace.jsonl`, one JSON object per line. Common fields on every record: `seq` (1..n), `tick` (game tick of the runner loop), `ms` (wall ms since run start, 0.1 ms), `kind`.

| kind | extra fields | recorded by |
|---|---|---|
| trace_start | schema = "cct-run/trace@1" | Trace |
| run_start | program, args, timeout_s | Runner |
| event | name, args | RecordingMachine (every event delivered to the Lua VM, incl. timer, task_complete, setting_changed, redstone, terminate) |
| screen | source ("term" or monitor name), lines {"<row 1-based>": text} (changed rows only), cursor [x,y] 1-based, size [w,h] (first record and on resize) | TracingTerminal, once per tick if changed, plus once before a terminal reset |
| output | source, text | TracingTerminal.scroll (each line that scrolls off the top) |
| peripheral_call | id, peripheral (side or network name), type, method, args, main_thread | DispatchPeripheral |
| peripheral_return | id, peripheral, method, ok, result (array) or error | DispatchPeripheral; main-thread methods return on a later tick |
| redstone_output | side, level, bundled | Runner (Computer.pollRedstoneChanges each tick) |
| modem_transmit | modem, channel, reply_channel, payload | WiredModemPeripheral |
| fixture_action | do, plus action fields | Runner (timeline) |
| program_end | ok, phase, message, traceback | HarnessApi |
| vm_error | message, timeout | RecordingMachine |
| context_close_pending | - | Runner (computer threads still stopping; harmless in CLI) |
| run_end | status, exit_code | Runner |

Screen reconstruction: apply `screen` records of one source in order (size resets row count; `lines` overwrites rows). Implemented in TraceQuery.screenAt.

## FIXTURE_FORMAT

`schema` optional; if present must be `"cct-run/fixture@1"`. Unknown keys are errors. Every validation error names its JSON path (`$.peripherals.left.items.4: slot must be between 1 and 3`).

```json
{
  "schema": "cct-run/fixture@1",
  "computer": { "id": 0, "label": "door", "advanced": true, "multishell": false, "term": "51x19" },
  "files": { "/data/config.txt": "text" },
  "peripherals": {
    "left":   { "type": "inventory", "block": "minecraft:chest", "size": 27,
                "items": { "1": { "name": "minecraft:iron_ingot", "count": 64,
                                  "displayName": "Iron Ingot", "maxCount": 64, "tags": ["c:ingots"],
                                  "nbt": "hash", "damage": 0, "maxDamage": 250 } } },
    "right":  { "type": "energy_storage", "block": "cctrun:energy_cell", "energy": 100, "capacity": 1000 },
    "bottom": { "type": "fluid_storage", "block": "cctrun:tank", "tanks": [ { "name": "minecraft:water", "amount": 1000 } ] },
    "top":    { "type": "monitor", "blocks": [3, 2], "advanced": true },
    "back":   { "type": "wired_modem", "local_name": "computer_0",
                "network": { "minecraft:chest_0": { "type": "inventory" }, "monitor_0": { "type": "monitor" } } }
  },
  "redstone": { "front": 15 },
  "timeline": [
    { "tick": 20, "do": "redstone", "side": "back", "level": 15 },
    { "tick": 30, "do": "event", "name": "key", "args": [28, false] },
    { "tick": 40, "do": "energy", "peripheral": "right", "energy": 500 },
    { "tick": 40, "do": "item", "peripheral": "minecraft:chest_0", "slot": 1, "item": { "name": "minecraft:coal", "count": 3 } },
    { "tick": 60, "do": "item", "peripheral": "left", "slot": 1, "item": null },
    { "tick": 80, "do": "terminate" }
  ]
}
```

Defaults and rules: inventory size 27, item count 1, maxCount 64 (1..64), slot limit 64; energy capacity 100000, energy 0; monitor blocks [1,1] (1..8 x 1..6), monitor advanced = computer advanced; wired modem local_name computer_0; network names must be unique across modems and not side names; no wired modem inside a network; `files` may not contain startup.lua or startup/ (reserved); timeline peripheral names resolve sides and network names; `item: null` empties a slot.

## HARNESS

- Root drive: `MemoryMount(1_000_000)` with fixture files, the program at `/<file name>` and `harness.lua` at `/startup.lua`. A program called `startup.lua` or `startup` is stored as `/main.lua` (summary.input.program.path).
- CoreConfig per run: `httpEnabled=false`, `httpWebsocketEnabled=false`, `defaultComputerSettings="motd.enable=false[,bios.use_multishell=false]"`.
- harness.lua mirrors `executeProgram` in `rom/programs/shell.lua`: `createShellEnv(dir)` with `__index=_G`, `env.arg = {[0]=path, ...}`, `load(contents, "@/" .. path, nil, env)`, `cc.internal.syntax` friendly syntax errors, `bios.strict_globals`, `cc.internal.exception.try` + `exception.report` (same on-screen output as in game), `debug.traceback(co)` for the structured traceback.
- After reporting, the harness waits in `os.pullEventRaw()` forever; the runner stops the loop. (Calling os.shutdown would clear the screen and redstone outputs, which a program ending in game does not do.)
- Outcome detection (Runner.loop): harness reported -> ok/error; second VM created -> reboot; computer off with no pending start for > 10 ticks -> shutdown (pending start read from private `Computer.startRequested` by reflection, fallback: within START_DELAY+5 ticks of last boot); tick >= timeout*20 -> timeout.
- Each run ends with `context.close(50 ms)` without waiting further; the CLI then calls `System.exit`.

## FIDELITY

Matches CC: Tweaked 1.120.2 because the real code runs, or because behaviour was copied from the tag's source:

| aspect | basis |
|---|---|
| Lua runtime, ROM, BIOS, shell, APIs | real CC:T core jar (Cobalt 0.9.9) |
| "Too long without yielding" at ~7 s | real; measured 7.25 s |
| syntax/runtime error text and on-screen rendering | real shell modules via harness.lua |
| `collectgarbage` absent, `//` rejected | real (test asserts collectgarbage nil) |
| monitor terminal methods | real TermMethods |
| monitor size from blocks and text scale | ServerMonitor/MonitorBlockEntity formula (1x1 -> 7x5, 2x1 -> 18x5, 3x2 @0.5 -> 57x24) |
| monitor_resize on setTextScale size change, range error text | MonitorPeripheral source |
| inventory method set, error messages, slot-range wording | forge/fabric InventoryMethods + ArgumentHelpers.assertBetween |
| inventory/energy/fluid methods take >= 1 tick, resume with task_complete | executeMainThreadTask, like generic peripherals |
| pushItems target resolution: side names for direct peripherals, network names via wired modem | PeripheralAPI.getAvailablePeripheral / RemoteAccess |
| callRemote errors "No peripheral: X", "No such method X" | WiredModemPeripheral source |
| reboot delay up to 2.5 s after boot | real Computer.START_DELAY |

## LIMITATIONS

- L1: No turtle, pocket or command computer APIs. Turtles need a world; candidate: CC:T gametest (headless Minecraft server), not started.
- L2: Peripherals: only inventory, energy_storage, fluid_storage (tanks() only; no pushFluid/pullFluid), monitor, wired_modem. Missing: speaker, printer, disk drive, wireless/ender modem, rednet reception, modem_message delivery (transmit is only recorded), peripheral attach/detach at runtime, automatic monitor_touch (inject via timeline `event`).
- L3: getItemDetail returns a subset (name, count, nbt, displayName, maxCount, tags, damage, maxDamage); no enchantments/lore/durability. Slot limit fixed at 64; no per-slot rules (e.g. furnace slots). Transfer order is Forge's (D12).
- L4: HTTP and websockets always disabled (`http` is nil).
- L5: Disk capacity is reported (1,000,000 bytes) but writes are not limited; no "Out of space" error.
- L6: With `computer.multishell: true`, scrolled-off lines are NOT captured (window API redraws via blit); screen diffs still work. With the default (false), the `multishell` API is nil.
- L7: `shell.getRunningProgram()` returns "startup.lua"; `/startup.lua` (the harness) is visible to fs.list/fs.open.
- L8: Events consumed and discarded by Lua code (e.g. inside sleep, or filtered by os.pullEvent) appear as delivered; the trace cannot show that Lua ignored them.
- L9: Wired-modem lookup methods are not traced (D13); a program calling them directly leaves no peripheral_call record for them.
- L10: `timeout_state` is usually absent: CobaltLuaMachine prints a state only while Lua is executing, not while waiting for an event.
- L11: Real-time model (D6): tick/ms values and the interleaving of timers vs. peripheral returns can differ between runs of the same program. Compare traces by order of records, not by tick.
- L12: In-game time is frozen: `os.time()` (in-game) = 0, `os.day()` = 0 (RunnerEnvironment.getDay/getTimeOfDay). `os.epoch("utc")`/`os.clock()` are real.
- L13: A program that prints and shuts down/reboots in the same tick: screen kept via the reset hook; anything printed after the reset is not shown.
- L14: Screen records carry text only; colours and palette are not recorded.
- L15: Run directories accumulate under `.cct-runs`; no cleanup command.
- L16: Not tested on Windows (cct-run.bat generated by Gradle, not executed) or macOS.
- L17: Java discovery = JAVA_HOME or PATH only; the Java runtime bundled with the Minecraft launcher (usually not on PATH) is not found automatically.
- L18: No AppCDS archive; measured fixed cost ~645 ms per run (see JVM_COST).
- L19: Engine tests run many runs per JVM; CoreConfig is global and computer threads may linger briefly. Fine for tests; do not run concurrent runs in one JVM.

## RISKS

- R1: False confidence from fakes. Any divergence from the game in peripheral behaviour makes passing runs misleading. Mitigation so far: messages/semantics copied from tag source (FIDELITY). Needed: document per-peripheral fidelity for agents; extend from source, not memory.
- R2: `Computer.startRequested` is read by reflection (reboot detection). A CC:T upgrade may rename it; code falls back to a timing rule (not exercised by tests).
- R3: Single source for the core artifact (maven.squiddev.cc). If unavailable, builds fail. Consider vendoring/mirroring before releases.
- R4: Licensing. CC:T core is MPL-2.0; parts of the ROM/fonts inside the jar are CCPL (requires free source availability, CCPL for redistributed CC:T parts, and the user owning Minecraft). The repo does not contain CC:T code, but `installDist` output and any release archive include the CC:T jars. Decide release packaging and add a LICENSE/NOTICE before distributing binaries. This repo has no LICENSE file yet (owner decision).
- R5: Unbounded trace size: a long run (timeout up to 3600 s) that redraws every tick writes one screen record per tick. Needs caps or sampling.
- R6: Untrusted code and memory: no Lua memory limit; a program allocating rapidly can exhaust the JVM heap (default max heap = 1/4 of RAM) before "Too long without yielding". Consider `-Xmx` in the start scripts and handling OutOfMemoryError as a distinct status.
- R7: The first push of this branch to the empty repository may make `claude/cct-run-cli` the default branch on GitHub; the owner may want to create `main` and change the default.

## VERIFICATION

Environment: Linux x86_64, build JDK 21.0.12 (Gradle 9.7.1 wrapper), runtime checks on Temurin JRE 17.0.20 and Temurin JDK 25.0.4.

- V1: `./gradlew build` -> BUILD SUCCESSFUL; engine tests 25 run, 0 failed (RunnerTest 11, PeripheralTest 9, FixtureTest 3, TraceQueryTest 2).
- V2: CLI end to end (installDist start script), all exit codes as specified:
  - examples/hello.lua `-- a "b c"` -> ok, args preserved, `http` nil.
  - examples/runtime_error.lua -> error/runtime, message `/runtime_error.lua:4: attempt to index local 'mon' (a nil value)`, traceback, screen shows the game's error with source line.
  - examples/syntax_error.lua -> error/syntax, message `/syntax_error.lua:4: 'then' expected near 'print'`, screen shows "Expected then after if condition.".
  - examples/storage_monitor.lua + fixture -> ok; monitor_0 shows totals; pushItems moved 32; error `Target 'minecraft:nowhere' does not exist` in notes; 12 traced peripheral calls (61 before D13).
  - examples/redstone_door.lua + fixture -> input at tick 10 -> output 15 at tick 11 -> output 0 at tick 31; falling-edge event at tick 12 delivered during sleep (visible example of L8 / the "sleep drops events" pitfall); terminate at tick 50 -> error "Terminated".
  - examples/long_output.lua -> 12 `output` records (lines 1-12), screen lines 13-30.
  - examples/no_yield.lua -> error "Too long without yielding" after 7.25 s.
  - examples/stuck.lua `--timeout 2` -> timeout, exit 2.
  - usage/bad input: no command, missing program, `--timeout 0`, unknown option -> 64; missing file, invalid fixture (`$.peripherals.left.size: must be at least 1`), bad run dir, unknown screen source -> 65.
  - `trace --kind/--ticks/--peripheral/--offset/--limit`, `screen --source monitor_0`, `version` produce the documented JSON.
- V3: Runtime compatibility: `cct-run version` and the storage_monitor example pass on Java 17.0.20 and 25.0.4.

## JVM_COST

Measured on this machine (4 cores), program `print("done")`:

| setup | wall time per run |
|---|---|
| cct-run start script (C1 only, SerialGC), new JVM per run | 547-810 ms, avg 645 ms (5 runs) |
| earlier prototype, new JVM, waiting for context close | ~1690 ms (context close waited its full 1 s timeout) |
| earlier prototype, new JVM + AppCDS + C1 | ~424 ms (not adopted yet: Java 17 has no -XX:+AutoCreateSharedArchive; would need an archive per install) |
| resident JVM sharing one ComputerContext (rejected by D1) | ~100 ms |

Most of the remaining cost is JVM start/class loading plus ~6 boot ticks at 50 ms.

## ENV_NOTES

- In the sandbox where this was built, repo.maven.apache.org intermittently answered 404/429. Builds used a local Gradle init script (not committed) that rewrites Maven Central to https://maven-central.storage-download.googleapis.com/maven2/ and keeps the Gradle plugin portal. The committed build uses plain `mavenCentral()`.
- Upstream CC:T itself needs JDK 25 to build (gradle/gradle-daemon-jvm.properties); this repo does not build CC:T, only depends on the published jar.

## NEXT

Ordered by expected value; none started.

1. N1: Agent-facing usage notes (in this repo and/or the skill repo): when to run, how to read summary/notes, how to query trace, fixture cookbook.
2. N2: Windows check for dsh/PowerShell (cct-run.bat, paths, UTF-8 stdout) and macOS.
3. N3: Java discovery in launcher scripts (L17): JAVA_HOME, PATH, Minecraft launcher runtime dirs (`.minecraft/runtime/java-runtime-*`, Prism/CurseForge).
4. N4: Resource limits (R5, R6): heap cap, OOM -> distinct status, trace size cap, `cct-run clean`.
5. N5: MCP wrapper over the CLI (stdio): tools run/trace/screen mapping 1:1 to the CLI; spawns one `cct-run` per call (D1).
6. N6: More peripherals by demand from the skill's templates: speaker, printer, disk drive, wireless modem + multi-computer rednet.
7. N7: Colour in screen records (L14) if agents need it.
8. N8: Startup time: optional AppCDS archive created on first run (R: per-install file), measure benefit on Java 17.
9. N9: Turtles (L1) via CC:T gametest or a dedicated world model; scope decision needed.
10. N10: Release packaging after the license decision (R4).

## REFERENCES

Upstream sources consulted at tag v1.20.1-1.120.2 (github.com/cc-tweaked/CC-Tweaked):

- projects/core/src/main/java/dan200/computercraft/core/computer/{Computer,ComputerExecutor,ComputerEnvironment,GlobalEnvironment,Environment}.java
- projects/core/src/main/java/dan200/computercraft/core/{ComputerContext,CoreConfig}.java
- projects/core/src/main/java/dan200/computercraft/core/lua/{ILuaMachine,CobaltLuaMachine,MachineResult}.java
- projects/core/src/main/java/dan200/computercraft/core/terminal/Terminal.java, core/apis/TermMethods.java, core/apis/PeripheralAPI.java
- projects/core/src/main/java/dan200/computercraft/core/redstone/RedstoneState.java, core/util/ArgumentHelpers.java
- projects/core/src/main/resources/data/computercraft/lua/{bios.lua, rom/programs/shell.lua, rom/apis/peripheral.lua, rom/modules/main/cc/require.lua, rom/modules/main/cc/internal/exception.lua}
- projects/common/src/main/java/dan200/computercraft/shared/peripheral/monitor/{MonitorPeripheral,ServerMonitor,MonitorBlockEntity}.java
- projects/common/src/main/java/dan200/computercraft/shared/peripheral/modem/wired/WiredModemPeripheral.java
- projects/{forge,fabric}/src/main/java/dan200/computercraft/shared/peripheral/generic/methods/InventoryMethods.java
- projects/core/src/test/java/dan200/computercraft/core/computer/ComputerBootstrap.java (pattern for headless computers)
