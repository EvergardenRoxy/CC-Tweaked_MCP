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
status: CLI implemented; 35/35 tests (engine 28, cli 7); Linux end to end (Java 17/21/25); release zip + licenses + maintainer test kit; Windows smoke test 17/17 with W1-W3 confirmed (V8); N1 (agent guide) and N3 (Java 8-safe launcher) done and verified on Linux (V9), N3 confirmed on Windows (V10); macOS untested; MCP wrapper not started
readme: README.md (user-facing, Chinese); this file is the machine-oriented record
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
| D9 | README.md (Chinese, for users: what it is, install, features, limits, roadmap) added on owner request; it states that the MCP server is not built yet. handoff.md stays the machine-oriented record. | user |
| D10 | Bytecode target Java 17 (`options.release = 17`), matching the runtime Minecraft 1.20.1 ships. | impl |
| D11 | Multishell off by default (`bios.use_multishell=false`) so scrolled-off lines are captured exactly; fixture `computer.multishell: true` re-enables it. | impl |
| D12 | Item transfer follows Forge semantics (target slots filled in slot order); Fabric fills existing stacks first. | impl |
| D13 | Wired-modem lookup methods (getNamesRemote, isPresentRemote, getTypeRemote, hasTypeRemote, getMethodsRemote) are not traced: CraftOS calls them dozens of times per `peripheral.find`. | impl, confirmed by user |
| D14 | Licensing: comply with the licenses of bundled code. Own code MPL-2.0 (`LICENSE`, SPDX headers). `harness.lua` is CCPL because it adapts code from CC:T `rom/programs/shell.lua` (SPDX LicenseRef-CCPL, (c) 2017 Daniel Ratcliffe). Third-party licenses in `LICENSES/`, inventory in `THIRD-PARTY-NOTICES.md`. Open question on CCPL section 7: see R4. | user (comply) + impl (per-file split) |
| D15 | Distribution: release zip (`./gradlew :cli:distZip`) with launcher, libs, licenses, notices, examples, test scripts, test guide. No public release until R4 is resolved; the current zip is a maintainer test build. | user |
| D16 | Development order (incremental, not one round): N1 agent usage notes -> N3 Java discovery + N2 Windows/macOS -> N4 resource limits -> N5 MCP wrapper. | user |
| D17 | Resource defaults for N4: JVM heap cap 1 GB; default timeout 15 s (unchanged); trace over its size cap is truncated (not sampled). Not implemented yet. | user |
| D18 | D11, D12, D13 confirmed. | user |
| D19 | Turtles (N9) out of scope for now. | user |
| D20 | New peripherals (N6) are added as the skill's templates need them; every simulated behaviour that can differ from the game is recorded in DIVERGENCES. | user |
| D21 | Skill integration (M6) parked; both repos will change later. Work in the skill repo (EvergardenRoxy/CC-Tweaked_LLM_skill, branch claude/charming-bohr-b36rua, 2 unmerged commits) is parked. | user |
| D22 | Windows (required) and macOS (optional) testing is done by the maintainer with the test kit (`docs/testing.md`, `scripts/smoke-test.ps1`, `scripts/smoke-test.sh`); results come back as report files. | user |
| D23 | Java discovery (N3): launchers such as Prism and PCL CE use the system JDK by default and normally do not download Java into their own folders. Discovery = JAVA_HOME, then PATH, then (fallback only) common system JDK install folders; launcher folders are not searched. | user |
| D24 | R4 closed by the owner: the project serves the game, which the owner judges compatible with the CCPL; packaging the zip (D15) proceeds. harness.lua stays CCPL-marked and the notices stay. | user |
| D25 | Agent clients tested so far: Autoclaw and dsh (Windows). Claude Code and Codex wait for user feedback. PowerShell 7 is not installed on the maintainer's machine, so it is not tested. | user |
| D27 | N3: the start scripts launch `cctrun.cli.Launcher`, compiled for Java 8 (source set `launcher`). On Java >= 17 it calls Main in the same JVM. On older Java it searches per D23 (JAVA_HOME, PATH, then common system JDK folders; version read from each home's `release` file), re-executes itself with the newest Java >= 17 (same JVM flags and classpath, env CCT_RUN_REEXEC=1 prevents loops) and says so on stderr. None found: exit 69 with JAVA_HOME instructions for PowerShell, cmd and sh. The Gradle start scripts still choose the first `java` (JAVA_HOME, else PATH); a Java older than 8 cannot load the launcher at all. | user (do N3) + impl |
| D28 | N1: agent guide `docs/agent-guide.md` (English, ASCII, for agents; placement in the skill repo stays parked under D21). Bundled in the jar and printed by `cct-run guide`; also shipped as a file in the zip. Covers workflow, commands, summary fields, trace queries and pitfalls, fixtures, and what a pass means (DIVERGENCES in plain terms). | user (do N1) + impl |
| D29 | `duration.tick_rate` is reported only when a run lasts at least 40 ticks (2 s). Short runs include JVM/boot warm-up and measured 13.7-17.3 ticks/s on Windows (V8), which would look like a pacing bug. | impl |
| D26 | W1: stdout JSON is ASCII-only (non-ASCII as \uXXXX); files stay UTF-8. W2: ticks paced against absolute deadlines (TickPacer); a lag above 1 s skips the missed ticks. W3: setting_changed events caused by cct-run's injected settings carry `harness: true`; a `program_start` record marks the start of the program. | user (do W1-W3) + impl |

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
engine/src/test/java/cctrun/engine/            RunnerTest, PeripheralTest, FixtureTest, TraceQueryTest, TickPacerTest, RunHelper
cli/src/main/java/cctrun/cli/Main.java         argument parsing, commands, exit codes (Java 17)
cli/src/launcher/java/cctrun/cli/Launcher.java start-script entry point, Java 8 bytecode: version check, re-exec on Java >= 17, exit 69 (D27)
cli/src/launcher/java/cctrun/cli/JavaFinder.java  Java home discovery: JAVA_HOME, PATH, per-OS default roots; reads `release` files
cli/src/test/java/cctrun/cli/                  MainTest, JavaFinderTest
docs/agent-guide.md                            agent guide (D28); copied into the cli jar as /cctrun/agent-guide.md
examples/                                      sample programs and fixtures used for end-to-end checks
scripts/smoke-test.ps1                         maintainer smoke test, Windows PowerShell 5.1 / PowerShell 7 (ASCII only)
scripts/smoke-test.sh                          same cases for macOS/Linux (bash 3.2, grep)
docs/testing.md                                test guide for the maintainer (Chinese)
LICENSE                                        MPL-2.0 (own code)
LICENSES/                                      MPL-2.0, LicenseRef-CCPL, Apache-2.0, third-party/{cobalt-MIT, checker-qual-MIT, slf4j-MIT, jzlib-BSD-3-Clause, netty-NOTICE}
THIRD-PARTY-NOTICES.md                         bundled artifacts, licenses, source links, CCPL conditions
```

## BUILD

```
./gradlew build                      # compile + 35 tests (~25 s of test time; some tests wait real game time)
./gradlew :cli:installDist           # -> cli/build/install/cct-run/{bin/cct-run, bin/cct-run.bat, lib/*.jar}
./gradlew :cli:distZip               # -> cli/build/distributions/cct-run-<version>.zip (~28 MB): bin, lib, LICENSE(S), notices, examples, scripts, docs/testing.md, docs/agent-guide.md
cli/build/install/cct-run/bin/cct-run run examples/hello.lua
```

- JVM flags baked into the start scripts (`cli/build.gradle.kts`): `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss4m`.
- Start scripts (Gradle-generated) start the first Java found via `JAVA_HOME`, else `java` on PATH, with main class `cctrun.cli.Launcher`; the launcher handles Java < 17 (D27).
- The launcher source set compiles with `--release 8` (`-Xlint:-options` hides the obsolete-target warning); its classes go into the cli jar. Check: Launcher.class/JavaFinder.class major version 52, Main.class 61.
- Test-only system property `cctrun.java.searchOnly=<roots separated by File.pathSeparator>`: the launcher searches only these roots (ignores JAVA_HOME, PATH and default roots). Used to exercise the exit-69 path.

## INTERFACE_CLI

Provisional (D8).

```
cct-run run <program.lua> [--fixture FILE] [--timeout SECONDS=15 (1..3600)] [--out DIR=.cct-runs]
                          [--term WxH] [--basic | --advanced] [--] [program args...]
cct-run trace  <run-dir|latest> [--kind K1,K2] [--ticks N|A-B|A-|-B] [--peripheral NAME] [--source NAME]
                                [--offset N=0] [--limit N=200] [--out DIR]
cct-run screen <run-dir|latest> [--tick N] [--source term|MONITOR_NAME] [--out DIR]
cct-run guide                     # prints docs/agent-guide.md (ASCII)
cct-run version
cct-run help
```

- Options: `--name VALUE` or `--name=VALUE`, anywhere on the line; `--` ends option parsing (needed to pass `--x` to the program).
- stdout is ASCII-only: every non-ASCII character is written as a JSON `\uXXXX` escape (D26/W1). This makes it independent of the console code page. Files on disk (summary.json, trace.jsonl) are UTF-8 and not escaped.
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
| 65 | bad input (program/fixture not found or unreadable, invalid fixture, not a run directory, no such screen source, non-ASCII path under a non-UTF-8 locale) |
| 69 | no Java 17+ found (launcher, D27); guidance on stderr |
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
| duration | object | {ticks, seconds (ticks/20), wall_ms, tick_rate (measured ticks/s over the loop, about 20; only when ticks >= 40, D29), lag_resets (only if > 0)} |
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
| event | name, args, [harness: true, note] | RecordingMachine (every event delivered to the Lua VM, incl. timer, task_complete, setting_changed, redstone, terminate). `harness: true` marks the setting_changed events caused by cct-run's injected settings (W3); setting_changed args are (name, new value, old value). |
| program_start | - | HarnessApi (the program compiled and its main chunk is about to run; absent on syntax errors) |
| tick_lag | behind_ms, note | Runner (loop fell more than 1 s behind; missed ticks skipped); also in notes |
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
- L16: Verified on Windows 11 / Windows PowerShell 5.1 / cmd.exe (V6, V8). Not run on macOS. PowerShell 7 not tested on Windows (D25).
- L17: Resolved by N3 (D27) for Java 8-16 on JAVA_HOME/PATH. Remaining: (a) if neither JAVA_HOME nor PATH has any Java, the Gradle start script fails before the launcher runs ("JAVA_HOME is not set and no 'java' command"); (b) Java runtimes inside launcher folders are not searched (D23); (c) homes without a `release` file are ignored; (d) the re-exec costs one extra JVM start (~150-300 ms). Windows re-test pending (smoke case old-java-switch).
- L18: No AppCDS archive; measured fixed cost ~645 ms per run (see JVM_COST).
- L19: Engine tests run many runs per JVM; CoreConfig is global and computer threads may linger briefly. Fine for tests; do not run concurrent runs in one JVM.
- L20: Non-ASCII paths fail under a POSIX/C locale (Java's sun.jnu.encoding = ASCII): exit 65 with a hint to use a UTF-8 locale. Works with a UTF-8 locale; Windows uses Unicode file APIs (to be confirmed by the test kit).
- L21: Resolved by W1 (ASCII-only stdout); confirmed on Windows code page 936 (V8). Before the fix, code page 936 mis-decoded "caf" + U+00C3 U+00A9 as "caf" + U+8119 U+6F0F (V6).
- L22: Resolved by W2 (absolute-deadline pacing): before the fix, Windows ran about 15.5-16 ticks/s (V6), so game time ran ~20% slow against wall time. Cause: Thread.sleep(50 - elapsed) rounded up to the 15.6 ms Windows timer granularity, giving 62.5 ms ticks. TickPacerTest reproduces that granularity and shows 200 ticks in 10.0 s; confirmed on Windows: 19.3-19.8 ticks/s on runs of 40-143 ticks (V8). A single tick can still start up to one timer quantum late; short runs are dominated by warm-up (D29).
- L23: Every trace still contains the setting_changed events caused by cct-run's own default settings (motd.enable, and bios.use_multishell unless multishell is on). They are now flagged `harness: true` with a note (W3). Agents must read setting_changed args as (name, new value, old value); one agent read them backwards (V6).
- L24: Screen text uses CC's own character set: chars 0x80-0xFF are CC graphics glyphs (e.g. the error gutter U+0095, marker U+0083), not Latin-1; Lua strings are bytes, so UTF-8 text appears as 2-3 glyphs per letter. Documented in the agent guide; no translation is done.

## DIVERGENCES

Ledger of simulated behaviour that can differ from the real game (D20). Rule: every new or changed fake peripheral or harness behaviour adds a row with the upstream evidence used and what can differ. Agents should treat a passing run as "passes against this model", not "works in game", wherever a row applies.

| id | area | simulation | real game | evidence / note |
|---|---|---|---|---|
| DV1 | inventory transfer order | Forge: fill target slots in order | Fabric: existing stacks first; Forge: slot order | forge/fabric InventoryMethods (D12) |
| DV2 | inventory detail | subset of getItemDetail fields | full ItemDetails (enchantments, lore, durability...) | L3 |
| DV3 | inventory slots | every slot accepts any item, limit 64 | per-block rules (furnace input/fuel/output, shulker boxes, modded inventories) | L3; matters for the furnace-array template |
| DV4 | fluids | tanks() only, static | pushFluid/pullFluid; amounts change with the world | L2 |
| DV5 | energy | static unless set by timeline | changes with generators/consumers | fixture timeline `energy` |
| DV6 | monitors | size from blocks, no touch unless injected | players touch; multi-block monitors can be broken/extended | timeline `event` monitor_touch |
| DV7 | wired network | fixed membership | peripherals join/leave (peripheral / peripheral_detach events) | L2 |
| DV8 | modem messages | transmit recorded, nothing received | other computers reply | L2 |
| DV9 | time | in-game time frozen at 0 | os.time()/os.day() advance | L12 |
| DV10 | disk | capacity reported, not enforced | "Out of space" errors | L5 |
| DV11 | http | disabled | server config decides | L4 |
| DV12 | shell | multishell off by default; startup.lua is the harness | multishell on for advanced computers; user's own startup | D11, L7 |
| DV13 | world | no blocks, no turtles, no chunk unloading, no server lag | all present | L1, D19 |
| DV14 | tick rate | about 20 ticks/s on average (W2); individual ticks jitter by up to one OS timer quantum; ticks skipped after a lag of more than 1 s | 20 ticks/s; slower under server lag | before W2, ~16 ticks/s on Windows (V6); `duration.tick_rate` reports the measured rate |

## RISKS

- R1: False confidence from fakes. Any divergence from the game in peripheral behaviour makes passing runs misleading. Mitigation so far: messages/semantics copied from tag source (FIDELITY). Needed: document per-peripheral fidelity for agents; extend from source, not memory.
- R2: `Computer.startRequested` is read by reflection (reboot detection). A CC:T upgrade may rename it; code falls back to a timing rule (not exercised by tests).
- R3: Single source for the core artifact (maven.squiddev.cc). If unavailable, builds fail. Consider vendoring/mirroring before releases.
- R4: Licensing, CCPL section 7. CLOSED by owner decision D24; kept here for the record.
  - Facts:
    - The published core jar's POM declares "ComputerCraft Public License, Version 1.0". Upstream source files are individually MPL-2.0 or CCPL; for example, `rom/programs/shell.lua` is CCPL.
    - CCPL section 5 allows redistributing the mod, in whole or in part, "included in a distribution", provided sources stay available at no cost and the files remain CCPL. Done: see THIRD-PARTY-NOTICES.md.
    - CCPL section 7: "It is permitted to use the code and assets contained in this mod ... in other Minecraft Mods, provided they are non-commercial. However: the code and assets may not be used in commercial mods, mods for other games, other games, other non-game projects, or any commercial projects."
  - Exposure:
    - (a) `harness.lua` adapts code from shell.lua, which is CCPL code used in a non-game project.
    - (b) Bundling the core jar in a tool that is not a mod: section 5 permits redistribution, but section 7 may be read to restrict use.
  - Context: upstream ARCHITECTURE.md describes `core` as usable "in emulators and other tooling"; CraftOS-PC (an emulator, not a mod) distributes the CraftOS ROM.
  - Options:
    - O1: ask the CC:T maintainers for clarification or permission (recommended in all cases).
    - O2: remove copied CCPL code by rewriting harness.lua from the public behaviour/API only (keeps the same observable behaviour), so that all own code is MPL-2.0.
    - O3: stop bundling CC:T and download the core jar from maven.squiddev.cc on first run; this reverses D15.
  - Status: closed by D24.
- R5: Unbounded trace size: a long run (timeout up to 3600 s) that redraws every tick writes one screen record per tick. Needs caps or sampling.
- R6: Untrusted code and memory: no Lua memory limit; a program allocating rapidly can exhaust the JVM heap (default max heap = 1/4 of RAM) before "Too long without yielding". Consider `-Xmx` in the start scripts and handling OutOfMemoryError as a distinct status.
- R7: Confirmed: the repository's default branch is `claude/cct-run-cli` (first push to an empty repository). Owner action: create `main` and set it as default (A1).

## TEST_KIT

For the maintainer (D22). Guide: `docs/testing.md`.

- `scripts/smoke-test.ps1` (Windows PowerShell 5.1 / PowerShell 7):
  - Runs 19 cases (exit codes and JSON fields), plus two extra checks:
    - stdout decoded with the console's original encoding (`default_encoding_check`);
    - running the `.bat` from `cmd.exe`.
  - Also lists `java.exe` paths under LOCALAPPDATA, APPDATA and Program Files for N3 (paths only; `-NoJavaSearch` disables this).
  - Writes `cct-run-smoke-report.json` (schema `cct-run/smoke-report@1`), with `passed`, `failed`, `skipped`, `environment.java8_for_switch_test` and a `skipped` flag per result.
  - The file is kept ASCII-only for PowerShell 5.1.
- `scripts/smoke-test.sh` (macOS/Linux, bash 3.2, grep only): the same 19 cases; writes `cct-run-smoke-report.txt`. Java 8 for old-java-switch is searched under /usr/lib/jvm, /Library/Java/JavaVirtualMachines, ~/Library/Java/JavaVirtualMachines and `$CCT_SMOKE_JAVA8_ROOT`.
- Cases: version, args-with-space-and-separator, runtime-error, syntax-error, fixture-wired-network-monitor, trace-latest, screen-latest-monitor, redstone-timeline-terminate, scrolled-output, timeout, too-long-without-yielding, path-with-space-and-cjk, utf8-stdout, usage-error, missing-program, startup-time, tick-rate (18.5-21.5 ticks/s over 60 ticks), guide (`cct-run guide` prints the ASCII guide), old-java-switch (JAVA_HOME = a Java 8 home; expects exit 0 and the "is too old; using Java" stderr line; SKIP when no Java 8 is installed; ps1 searches `release` files up to depth 4 under Program Files, Program Files (x86), /usr/lib/jvm, /Library/Java/JavaVirtualMachines).
- The guide also asks for manual cmd.exe output and an agent task, run in dsh, Codex and Claude Code on Windows, with a results table.

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
- V4: Test kit, on Linux only (not Windows):
  - `smoke-test.ps1` under PowerShell 7.6.7: 16/16 with LC_ALL=C.UTF-8.
  - Under the C locale, path-with-space-and-cjk fails with exit 65 and the locale hint (L20); before the fix this was exit 70 (internal error).
  - `smoke-test.sh`: 16/16 with LC_ALL=C.UTF-8.
  - Both were run from the source tree and from the unzipped release layout.
- V6: Windows, by the maintainer, with the release zip of commit 7f2c026 (report ea8e42a8-report-ps51.json):
  - Environment: Windows 11 (NT 10.0.26100), Windows PowerShell 5.1.26100 (Desktop), console encoding gb2312 / code page 936, Zulu JDK 21.0.12 via JAVA_HOME (trailing backslash works), launchers Prism and PCL CE.
  - smoke-test.ps1: 16/16 PASS. cmd.exe exit 0. Confirmed on Windows:
    - argument passing through cct-run.bat, including "b c" and "--";
    - paths with spaces and CJK characters (Unicode file APIs; L20 is Linux/macOS-only);
    - cold run 0.45-0.8 s.
  - default_encoding_check: false (L21, W1).
  - Tick rate about 16/s on long runs (L22, W2): timeout 40 ticks in 2573 ms; no_yield 115 ticks in 7242 ms; redstone_door 51 ticks in 3239 ms.
  - PowerShell 5.1 wraps a native command's stderr as NativeCommandError records when redirected (seen for exit 64/65). This is not a cct-run bug, but agents on 5.1 will see it.
  - java_candidates: no launcher-managed runtime found. Only system JDKs (Zulu 8/11/17/21/25, Oracle, GraalVM) and app-bundled JREs (MCA Selector, SlimeVR). Where Prism/PCL CE keep downloaded Java on this machine is unknown (possibly not under the searched roots, or not used because system JDKs are configured).
  - Manual cmd.exe run of hello.lua `-- a "b c"`: ok, args preserved.
  - Agent task (client not named in the report): correct status, exit_code, error.message and line 4; event count 2 (both setting_changed, see L23).
- V7: W1-W3 on Linux:
  - 30/30 tests: TickPacerTest (simulated 15.625 ms granularity -> 20.0 ticks/s; stall -> one lag reset), RunnerTest (harness-flagged settings, program_start, tick_rate 15-25), MainTest (ASCII-only stdout decodes to the original text; files stay UTF-8).
  - smoke-test.ps1 under PowerShell 7.6.7 and smoke-test.sh: 17/17 each (new case tick-rate).
- V8: Windows, by the maintainer, with the release zip of commit a913616 (report 3a803f2d-report-ps51-v2.json):
  - Environment: Windows 11 (NT 10.0.26100), Windows PowerShell 5.1.26100.9711, console gb2312 / code page 936, Zulu 21.0.12.1 via JAVA_HOME `C:\Program Files\Zulu\zulu-21`.
  - smoke-test.ps1: 17/17 PASS; cmd.exe exit 0.
  - W1 confirmed: default_encoding_check true on code page 936.
  - W2 confirmed: tick_rate 19.3 (40 ticks), 19.5 (51), 19.5 (60), 19.8 (143). Runs of 3-8 ticks showed 13.7-17.3 (warm-up) -> D29.
  - Cold runs 0.44-0.73 s.
- V9: N1 + N3 on Linux (build JDK 21; Temurin JRE 8u504 for the old-Java checks):
  - 35/35 tests: JavaFinderTest 4 (version parsing incl. "1.8.0_x", newest >= 17 wins, JAVA_HOME before PATH), MainTest 3 (+ guide bundled and ASCII), RunnerTest 12.
  - Launcher.class and JavaFinder.class are major 52 (Java 8), Main.class 61 (Java 17).
  - JAVA_HOME=JRE 8: stderr "is too old; using Java 21 ...", run exit 0. With `cctrun.java.searchOnly` limited to the JRE 8 root: exit 69 with guidance.
  - From the unzipped release zip with LC_ALL=C.UTF-8: smoke-test.sh 19/19 (old-java-switch exercised via CCT_SMOKE_JAVA8_ROOT); smoke-test.ps1 under PowerShell 7.6.7 18 pass + old-java-switch SKIP (the JRE 8 used here is outside the ps1 search roots). Under the C locale path-with-space-and-cjk fails with exit 65 as expected (L20).
  - docs/agent-guide.md is pure ASCII (grep for bytes > 0x7F finds none).
- V10: Windows, by the maintainer, with the release zip of commit e98abe5 (report e9b34290-report-ps51-v3.json): 19/19 PASS, 0 skipped; default_encoding_check true; cmd.exe exit 0; tick_rate 19.4-19.8 on 40-143 ticks. old-java-switch: JAVA_HOME = Zulu 8 (1.8.0_482) -> "is too old; using Java 25.0.2 at C:\Program Files\Zulu\zulu-25", exit 0, 1.07 s (vs 0.51 s direct). Side effect: on Java 25, Guava 31.1 prints `sun.misc.Unsafe` deprecation WARNING lines to stderr (harmless; stdout JSON unaffected). Candidate fix: prefer the newest LTS <= 21, or pass `--sun-misc-unsafe-memory-access=allow` when re-executing on Java >= 24.
- V5: The release zip contains bin/{cct-run, cct-run.bat}, lib/ (27 jars), LICENSE, LICENSES/ (3 + 5 third-party files), THIRD-PARTY-NOTICES.md, examples/, scripts/ (smoke-test.sh is executable), docs/testing.md, docs/agent-guide.md. harness.lua inside lib/engine-*.jar carries its CCPL header.

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

Order set by D16; incremental.

1. N1: DONE (D28, V9). Follow-up: move or link the guide from the skill repo when M6 resumes (D21).
2. N3: DONE (D27, V9, V10). Open: Java 25 Unsafe warnings on stderr (V10).
3. N2: W1-W3 confirmed on Windows (V8). Remaining: N3 Windows re-test (above); macOS optional; Claude Code and Codex results come from users later (D25).
4. N4: Resource limits with the D17 values: `-Xmx1g` in the start scripts, OutOfMemoryError as a distinct status, a trace size cap with truncation (plus a `truncated` flag in the summary), and `cct-run clean`.
5. N5: MCP wrapper over the CLI (stdio): tools run/trace/screen map 1:1 to the CLI; each call spawns one `cct-run` (D1).
6. N6: Peripherals as the skill's templates need them (D20): e.g. furnace slot rules (DV3), speaker, printer, disk drive, wireless modem + multi-computer rednet. Each one adds DIVERGENCES rows.
7. N7: Colour in screen records (L14), if agents need it.
8. N8: Startup time: optional AppCDS archive created on first run (one file per install); measure the benefit on Java 17.
9. N10: Public release after R4 is decided.
- Deferred: N9 turtles (D19).

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
