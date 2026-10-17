# handoff

```yaml
format: cct-run/handoff@1
audience: machine reviewer / next agent (not end users)
repo: EvergardenRoxy/CC-Tweaked_MCP
branch: claude/zealous-ramanujan-rrt0qh
base: main (default branch; PR #1 and PR #2 merged, 233883b); this branch = main + mod/ prototype (cct-live, D34-D45, V14-V20) + cct_live_* MCP tools
date: 2026-10-16
tool_version: 0.1.0 (engine/src/main/java/cctrun/engine/Versions.java)
target: CC: Tweaked 1.120.2 for Minecraft 1.20.1 (upstream tag v1.20.1-1.120.2)
status: CLI implemented; MCP server (`cct-run mcp`, stdio) implemented, test version (V11); Windows smoke 22/22 and one MCP client session confirmed (V13); 41/41 tests (engine 28, cli 13); 1 GB heap limit with out_of_memory status (D33); Linux end to end (Java 17/21/25); release zip + licenses + maintainer test kit; Windows smoke test 17/17 with W1-W3 confirmed (V8); N1 (agent guide) and N3 (Java 8-safe launcher) done and verified on Linux (V9), N3 confirmed on Windows (V10); macOS untested; mod/ (cct-live, Forge 1.20.1 mod observing real in-game computers) prototype verified on Linux dev and production Forge servers and in Windows single player (V14); http/fs tracing and cct_live_* MCP tools (D38, D39, V15); on demand with recording mode and attach/lease/chat control handover, overhead measured (D40-D42, V16)
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
| D17 | Resource defaults for N4: JVM heap cap 1 GB; default timeout 15 s (unchanged); trace over its size cap is truncated (not sampled). Heap cap done (D33); trace cap not yet. | user |
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
| D30 | MCP server built now on owner request ("build it as MCP and deliver for testing"), ahead of N4 (overrides the D16 order for N5). Form: `cct-run mcp [--out DIR]` inside the same distribution, so users need only Java; local stdio only (D3). Hand-written JSON-RPC 2.0 (newline-delimited, Gson); no MCP SDK dependency. | user (build MCP) + impl (form) |
| D31 | MCP tool calls: `cct_run` starts one child JVM per call (`java <this JVM's flags> -cp <classpath> cctrun.cli.Main run ...`, same Java as the server), keeping D1. Read-only tools (`cct_trace`, `cct_screen`, `cct_guide`) call Main.dispatch in the server process (they only read run directories; avoids ~0.5 s JVM start per query). Calls run concurrently; `notifications/cancelled` kills the child. | impl |
| D32 | MCP result conventions: text content = the CLI's stdout JSON (ASCII, unchanged). Lua error, timeout, shutdown, reboot, out_of_memory -> `isError: false` (a test result). Bad arguments, missing files, invalid fixtures, exit 64/65/69/70 -> `isError: true` with the CLI's message (temp paths of inline code/fixtures replaced by the given name / "(inline fixture)"). Unknown tool -> JSON-RPC error -32602. | impl |
| D33 | Heap limit (N4 part 1, owner request): `-Xmx1g` in applicationDefaultJvmArgs, so the start scripts, the Java 8 re-exec (input args) and MCP child runs (input args) all get it; JAVA_OPTS / CCT_RUN_OPTS come later and can override. Cobalt converts a Java OutOfMemoryError inside Lua into a Lua error "vm error: java.lang.OutOfMemoryError: ..."; when that error ends the program, status = out_of_memory, exit 4 (string match on the message, Runner.OOM_MARKER). An OOM outside Lua is caught in Main: minimal JSON summary (status out_of_memory, exit 4, note that summary.json is missing). Summary gains `memory {heap_limit_mb, heap_peak_mb}`; `version` gains heap_limit_mb. MCP treats exit 4 as a result (isError false). | user (do 1 GB) + impl |
| D34 | Live (in-game) observation as a Forge mod, not Lua forwarding (owner request after reviewing the community remote-desktop approach, which runs rawterm/server.lua inside the computer and needs a WebSocket relay). Java side sees what Lua cannot report honestly and the program cannot hide it; no CC http rules, no relay. Target Forge 1.20.1 + CC:T 1.120.2 (same as engine). Lives in `mod/` of this repo. | user |
| D35 | `mod/` is a standalone Gradle build (own settings.gradle.kts), run with the root wrapper `./gradlew -p mod build`, so the root build never downloads Minecraft. Toolchain: ModDevGradle legacyforge 2.0.148 (works on Gradle 9.7.1; ForgeGradle 6 does not), foojay resolver for the Java 17 toolchain MCP tooling requires. Dependency: `cc.tweaked:cc-tweaked-1.20.1-forge:1.120.2` (fat jar incl. core; Cobalt jar-in-jar; cobalt compileOnly). mods.toml pins computercraft `[1.120.2]` because internals are used. | impl |
| D36 | Hook points (from 1.120.2 sources): (a) `ServerContext.luaMachine` (public static, @VisibleForTesting, read in the ServerContext constructor on each server start) wrapped by LiveMachine: event (recorded before the VM resumes), vm_start, vm_close, vm_error. (b) VM -> computer: `MachineEnvironment.metrics()` is the computer's `ServerComputer.getMetrics()` (ServerComputer is the ComputerEnvironment); records made before the session maps it are held (max 1000). (c) Peripheral calls: the MachineEnvironment is rebuilt with a wrapped `MethodSupplier<LuaMethod>` that instruments `PeripheralAPI.call` (the ROM's wrap/find go through peripheral.call -> native.call); yielding results are followed through their callback chain via `MethodResult.yield`. (d) Polling on the server thread at the end of each tick over `ServerContext.get(server).registry().getComputers()`: terminal (private field `ServerComputer.terminal` via reflection, read under the Terminal's monitor), redstone, power, label, position. No mixins. | impl |
| D37 | Prototype access: JDK `com.sun.net.httpserver` bound to 127.0.0.1 (port 47823), bearer token in `<game dir>/cct-live/token.txt`, Host header must be loopback (DNS rebinding), anything touching computers runs via `server.submit` (5 s timeout). ASCII-only JSON like the CLI. Not yet wired to `cct-run mcp`. | impl |
| D38 | cct-live traces http and file access (owner request, after a real case: a download that returned an HTML page and broke `require`). TracingMethods also instruments HTTPAPI request/checkURL/websocket and FSAPI open/delete/move/copy/makeDir (one `api` record per call: api, method, args, ok, result/error) and every method of handle objects (AbstractHandle, HttpResponseHandle incl. its ObjectSource body reader, WebsocketHandle; one `handle` record per call with the data). Handles are numbered per VM (Handles, weak identity keys) and appear in JSON as {handle, type, path/mode or url}; http_success/http_failure/websocket_success name the response after the URL. /rom paths skipped (shell and require read them on every program start). Byte data previews copy at most 200 bytes. | user (do 1) + impl |
| D39 | MCP tools for live computers (owner request): `cct_live_computers`, `cct_live_screen`, `cct_live_trace` (kind, after, ticks, offset, limit), `cct_live_event`, `cct_live_type` (paste + key 257/key_up), `cct_live_power`, in `cct-run mcp` next to the simulation tools. Discovery: the mod writes `<game dir>/cct-live/endpoint.json` while a world is open; the game dir comes from the tool's `game_dir`, else `mcp --live DIR`, else CCT_LIVE_DIR. java.net.http client, no proxy, 5 s connect / 15 s request. Endpoint errors become isError results with the mod's message and HTTP status; refused connection -> "Is the world still open?". Input/power tools carry destructiveHint and tell the agent to act only on the user's request. | user (do 3) + impl |
| D40 | On demand (owner request: no observation cost during normal play). The VM factory and method wrappers must still be installed when each VM is created (CC:T offers no later hook point; attaching later would need a reboot), but they are dormant: every event and instrumented call first checks `Recorder.active()` (volatile reads: current session, recording mode, `anyAttached`, then a ConcurrentHashMap lookup by the computer's MetricsObserver) and calls straight through when false. No per-tick polling, no registry iteration, no files unless something is attached. `/computers` and `/screen` read the registry on request (server thread). | user + impl |
| D41 | Recording mode (owner request): config `recorder.enabled` (default false), `recorder.size` (default 256). When on, every computer's hook records (event, peripheral_*, api, handle, vm_*; no screens, no polling) go to an in-memory Ring per MetricsObserver (weak keys); attaching drains it into the new trace first, marked `history: true` with the original tick/ms (`attach` record: history, history_dropped). Peripheral call ids are per VM so pairs survive the ring. | user + impl |
| D42 | Control handover (owner request): attach/detach per computer (HTTP POST /attach, /detach; MCP cct_live_attach/detach). event/type/power need an attachment (409 otherwise); /screen and /computers do not. Lease: config `control.lease_seconds` (default 600, 30..86400), renewed by every request naming the computer, checked each tick against wall-clock time; reasons detach, revoked (player `/cctlive detach <id>`, permission level 0 since it only removes AI control), expired, removed (registry no longer has the instance), closed (world closing, silent). Chat: `broadcastSystemMessage` of `Component.translatableWithFallback` (lang zh_cn/en_us; fallback Chinese), config `control.chat`; always logged. One trace file per attachment: computer-<id>-<n>.jsonl; the last one stays readable via /trace. | user + impl |
| D43 | Changes from the first AI field test (owner: "start improving"; report: MPlayer installer, docs/live-test-mplayer.md). (1) printed_error: the mod jar's data pack adds `data/computercraft/lua/rom/autorun/cctlive.lua`; it takes the per-computer API `__cctlive` (BridgeApi, registerAPIFactory) out of _G and wraps printError, so the shell's report of a program crash becomes a record {text, program}; the shell's exception module is private to each shell, so no traceback yet. (2) `cct_live_files` / GET /files op list|stat|read (createRootMount, read-only, '..' rejected, needs an attachment, text utf-8 or iso-8859-1, max 1 MB per call). (3) `cct_live_click` = mouse_click + mouse_up. (4) /screen?regions=1: connected areas of one background colour other than the most common one (from/to/center/cells/rectangle/text), for GUIs with pixel-art labels. (5) /screen?tick=N rebuilt from the trace. (6) One trace file per computer per session; attachments append (seq continues). (7) lease default 1800 s. (8) trace.max_string (default 200). (9) MCP remembers the last working game_dir. (10) cct_guide {section: N}: the client of the field test cut the 15 KB guide before section 7. | user + impl |
| D44 | Second AI field test (DSH client, computer 3, owner-reported cost 15.3 M tokens, 98% cache reads, 179 k output): many turns with large results (screen polling, trace dumps). Changes (owner: "A B C all", file writing with a config switch): (A) `cct_live_status` (GET /status: tick, last_seq, counts by kind after seq from an in-memory kind index, last printed_error, screen text and hash, monitors); `cct_live_wait` (polls /status every 250 ms in the MCP server until text / kind / idle_seconds or timeout 30 s, max 120 s); /screen returns only text lines without trailing blanks unless colors=1, regions without cells/rectangle; trace default limit 50 and screen records excluded unless asked; trace summary=1; shorter tool descriptions; guide section 7 rewritten around status/wait. (B) Monitors attached directly to the computer (ServerComputer.getPeripheral(side) instanceof MonitorPeripheral, getTerminal()): screen records with source = side, `monitor` records, /screen?source=, replay by source, cct_live_click source -> monitor_touch. Wired-network monitors not covered. (C) cct_live_type multi-line (paste + Enter per line); attach lease_seconds 30..86400; handle records carry `bytes` (full length); cct_live_files op write (POST /files, config files.allow_write default false with an English and Chinese comment, `file_write` record, parent directories created, /rom refused, 1 MB per write). | user + impl |
| D45 | Third AI field test (DSH, deepseek-v4.1-flash, computer 4; 19.5 M tokens, 251 k output; more work than round 2: six diagnostic scripts, a controlled experiment, restoring the computer, WAF probing). Its costliest pattern was write script / run / read output / delete, 4 calls per fact. Changes: `cct_live_lua` (MCP: checks the shell prompt on the status lines unless force, writes /.cctlive/lua_<time>_<n>.lua with the code in a long bracket of a free level, captures print/printError/write in its own environment, xpcall with traceback, writes a JSON result, deletes itself; MCP polls stat, reads, deletes via the new POST /files op delete, which is limited to /.cctlive and recorded as file_delete; needs files.allow_write). HTTP response bodies read with readAll while attached are saved next to the trace (<trace>-body-<n>.bin, 4 MB each, 64 MB per trace); handle records carry `body`; GET /body and `cct_live_body`. Regions gain `labels` (blocks of non-space content inside the region, joined across rows) for buttons that share their card's background. Guide: terminate = Ctrl+T and its effects; seq vs tick. Reproduced the report's WAF claim: `user` in the query string is blocked too (?q=user, ?x=User -> 200 text/html 4130). | user + impl |
| D46 | Fourth AI field test (computer 5; tokens not reported; same WAF conclusion plus `load(..., _G)` without `require` in the Basalt bootstrap and fonts cached only in memory). Fixes: cct_live_wait with kind and no after first reads last_seq, so an old record no longer matches immediately (the first status call had no after, the endpoint used 0). cct_live_lua: timeout returns pending: <name>, `result: <name>` collects it later; each call removes leftovers in /.cctlive except pending outputs, cct_live_detach removes /.cctlive before detaching (temp_files_removed); clearer message when the prompt is missing. Mod: POST /computers/{id}/snapshot {op save|diff|restore|list, name} (ComputerSnapshots: files under cct-live/snapshots/computer-<id>/<name>/files + manifest.json with sha256 per file and dirs; /.cctlive skipped; restore writes back changed/removed files, deletes added files and empty added dirs, re-scans and reports identical; restore needs files.allow_write; save/restore recorded as `snapshot`); trace `contains` (case-insensitive substring of url, path, program, text or any string in args); attach returns computer_dir (ServerContext.storageDir()/computer/<id>, absolute). Guide: multishell can make printed_error.program the tab launcher. Not done (reasons in docs/test-results.md): lua while a program runs, label text, file origin in stat, normalised URLs. Tool count 19. | user + impl |
| D47 | Joint test 3, first run (docs/joint-test-factory-report.md; dsh, open-source DeepSeek served by a compute centre, computer 7, Create + Advanced Peripherals factory controller; 117 min, 92.9 M tokens, 751 k output, 576 tool calls, 46 context compactions; chat commands were only tested with chat events the agent injected itself). Owner approved changes (all but the mod-peripheral fixture, which is open as N12): (1) `cct-run mcp` read a child run's stderr as strict UTF-8 while the child JVM wrote the platform encoding (GBK on Chinese Windows), so any fixture error under a non-ASCII path came back as "internal error: MalformedInputException" (16 calls, ~8 min, wrong conclusions by the agent); the child gets `-Dcctrun.stderr.encoding=UTF-8` (Main swaps System.err) and McpServer decodes with new String(bytes, UTF_8). (2) `cct_live_peripheral` / POST /computers/{id}/peripheral {name, method?, args?} (PeripheralQuery): calls a peripheral's method on the server thread without the Lua VM, for sides and wired-network names (WiredModemPeripheral.peripheralWrappers -> RemotePeripheralWrapper.peripheral by reflection), method map from ServerContext.peripheralMethods().getSelfMethods; an ILuaContext whose issueMainThreadTask keeps the task, which is executed at once and the callback resumed with task_complete (max 16 steps); IComputerAccess stub (queueEvent dropped, no mounts, no peripherals); only names matching (get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)?; without method: type, additional types, methods; recorded as `peripheral_query` {by: ai}. (3) TracingMethods: `<side>.callRemote(name, method, ...)` is recorded as peripheral = name, method = method, via = side (as cct-run records network calls). (4) Trace filters `peripheral`, `method` (exact); `contains` also matches name, peripheral, method. (5) ComputerTrace keeps the newest records: two segments of max/2 (`computer-<id>.jsonl`, `computer-<id>.old.jsonl`); when the current one would exceed max/2 it becomes the old one and the new file starts with `trace_rotated` {rotations, first_seq, max_bytes}; readers use ComputerTrace.lines() over both; status and trace report `first_seq` after a rotation; Attachment writes the next screen record of each source in full after a rotation; Screens.replay skips records of a source until one with size; replaces `truncated`. (6) cct_live_wait `text`: the MCP server remembers each screen's hash (term and monitors, via /status) before cct_live_type/click/event/power; text on a screen still equal to that hash does not count until the screen changed (a timeout then carries a note); `unchanged: true` opts out. (7) Guide: event args start after the event name; events arriving during a main_thread peripheral call, sleep or a filtered pullEvent are discarded although the trace lists them; os.clock() = computer ticks * 0.05. Tool count 20. | user + impl |
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
cli/src/main/java/cctrun/cli/McpServer.java    `cct-run mcp`: stdio JSON-RPC loop, initialize/tools/ping/cancel, child-JVM runs, in-process queries (D30-D32)
cli/src/main/java/cctrun/cli/McpTools.java     MCP tool definitions (JSON schemas) and tool-arguments -> CLI argv mapping
cli/src/main/java/cctrun/cli/LiveTools.java    cct_live_* MCP tools: endpoint discovery (endpoint.json, token.txt) and HTTP calls to cct-live (D39)
cli/src/launcher/java/cctrun/cli/Launcher.java start-script entry point, Java 8 bytecode: version check, re-exec on Java >= 17, exit 69 (D27)
cli/src/launcher/java/cctrun/cli/JavaFinder.java  Java home discovery: JAVA_HOME, PATH, per-OS default roots; reads `release` files
cli/src/test/java/cctrun/cli/                  MainTest, JavaFinderTest, McpServerTest, LiveToolsTest (fake cct-live endpoint)
docs/agent-guide.md                            agent guide (D28); copied into the cli jar as /cctrun/agent-guide.md
docs/joint-test-factory-report.md               joint test 3 (Create factory, computer 7), first run: usage, time sinks, checks of the agent's claims, community suggestions (Chinese)
docs/test-results.md                           human-readable test history (Chinese), summary of VERIFICATION; shipped in the zip
examples/                                      sample programs and fixtures used for end-to-end checks
scripts/smoke-test.ps1                         maintainer smoke test, Windows PowerShell 5.1 / PowerShell 7 (ASCII only)
scripts/smoke-test.sh                          same cases for macOS/Linux (bash 3.2, grep)
docs/testing.md                                test guide for the maintainer (Chinese)
LICENSE                                        MPL-2.0 (own code)
LICENSES/                                      MPL-2.0, LicenseRef-CCPL, Apache-2.0, third-party/{cobalt-MIT, checker-qual-MIT, slf4j-MIT, jzlib-BSD-3-Clause, netty-NOTICE}
THIRD-PARTY-NOTICES.md                         bundled artifacts, licenses, source links, CCPL conditions
mod/                                           cct-live Forge mod prototype, standalone build (D34-D37); README.md (Chinese, user-facing)
  settings.gradle.kts, build.gradle.kts        ModDevGradle legacyforge, Forge 1.20.1-47.4.10, CC:T 1.120.2; runs: client, server (run/ ignored)
  src/main/java/cctrun/live/
    CctLive.java          @Mod entry: installs LiveMachine factory, server start/stop/tick listeners, /cctlive list|detach, chat announcements
  src/main/resources/assets/cctlive/lang/   zh_cn.json, en_us.json (chat messages)
  bench/startup.lua                         overhead benchmark workload (V16)
    LiveConfig.java       config/cctlive-common.toml (http.*, trace.screens, trace.max_megabytes_per_computer, recorder.enabled/size, control.lease_seconds/chat)
    LiveSession.java      one server session: attachments by MetricsObserver, recording rings, lease checks and polling of attached computers only, endpoint.json, token
    LiveMachine.java      ILuaMachine wrapper + Recorder (dormant unless attached or recording mode; D40)
    ComputerSnapshots.java  save / diff / restore of a computer's files under cct-live/snapshots (D46)
    PeripheralQuery.java  peripheral getters called from Java while a program runs (cct_live_peripheral, D47)
    Ring.java             recording-mode buffer of recent records per computer (D41)
    Attachment.java       one attachment: trace, lease, polled state (power, label, moved, redstone_output, screen with fg/bg)
    TracingMethods.java   MethodSupplier wrapper: peripheral_call / peripheral_return
    ComputerTrace.java    JSONL writer, schema cct-live/trace@0, size cap with `truncated`
    Terminals.java        terminal snapshot (reflection on ServerComputer.terminal)
    LiveHttp.java         local HTTP endpoint (D37); writes nothing itself, LiveSession writes cct-live/endpoint.json
    Handles.java          numbering of file/HTTP/websocket handles for api/handle records (D38)
    JsonValues.java       copy of engine's JsonValues (to share once a common module exists)
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
cct-run mcp [--out DIR]           # MCP server on stdio (INTERFACE_MCP)
cct-run version
cct-run help
```

- Options: `--name VALUE` or `--name=VALUE`, anywhere on the line; `--` ends option parsing (needed to pass `--x` to the program).
- stdout is ASCII-only: every non-ASCII character is written as a JSON `\uXXXX` escape (D26/W1). This makes it independent of the console code page. Files on disk (summary.json, trace.jsonl) are UTF-8 and not escaped.
- stdout: JSON only (`run`: pretty summary + `run_dir`; `trace`: one compact JSON object per line; `screen`/`version`: pretty JSON). stderr: diagnostics; `trace` prints a continuation hint to stderr when `--limit` is hit.
- `latest` = lexicographically greatest directory under `--out` that contains `trace.jsonl` (run ids start with a UTC timestamp).
- Precedence for computer settings: CLI flag > fixture `computer.*` > default (advanced, 51x19).

## INTERFACE_MCP

Provisional (D8). `cct-run mcp [--out DIR]`, started by the MCP client.

- Transport: stdio, one JSON-RPC 2.0 message per line; stdout carries only protocol messages (ASCII; non-ASCII escaped); logs on stderr (one "ready" line with the run directory and working directory). End of stdin: wait for calls in progress, exit 0.
- Methods: `initialize` (echoes protocolVersion if in [2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05], else 2025-11-25; capabilities `{tools: {listChanged: false}}`; `instructions` tells the agent to read cct_guide first and names the run/working directories), `ping`, `tools/list`, `tools/call`, `notifications/cancelled`. Other requests -> -32601; unparsable line -> -32700; non-object -> -32600. No batches, resources, prompts, progress notifications, structuredContent or outputSchema.
- Run directory: `--out` (relative to the working directory), else `<working dir>/.cct-runs` if writable, else `~/.cct-run/runs`. Relative tool paths resolve against the server's working directory (the client's choice; Claude Code uses the project directory).

| tool | CLI | arguments (JSON) |
|---|---|---|
| cct_run | run | file \| code (+ name, default program.lua, [A-Za-z0-9_.-]{1,64}); args: string[]; fixture: object (string accepted) \| fixture_file; timeout 1..3600 (default 15); term "WxH"; computer advanced\|basic. Unknown keys rejected. Child wall-time limit = timeout + 120 s. |
| cct_trace | trace | run (default latest); kind: string[] (string accepted); ticks: string; peripheral; source; offset; limit (default 200). Empty result -> "(no matching trace records)"; result at limit -> hint line to raise offset. |
| cct_screen | screen | run (default latest); tick; source |
| cct_guide | guide | none |

- Annotations: cct_run readOnlyHint false, others readOnlyHint true; all openWorldHint false.
- Client configuration examples (Claude Code, Codex, generic mcpServers JSON) are in README.md; Windows uses `cmd /c <dir>\bin\cct-run.bat mcp`.

Live tools (D39), all with optional `game_dir`; results are the cct-live endpoint's JSON (mod/README.md):

| tool | endpoint | arguments |
|---|---|---|
| cct_live_computers | GET /computers | - |
| cct_live_screen | GET /computers/{id}/screen | computer |
| cct_live_attach | POST /computers/{id}/attach | computer (D42) |
| cct_live_detach | POST /computers/{id}/detach | computer |
| cct_live_trace | GET /computers/{id}/trace | computer; kind: string[]; after (seq); ticks; offset; limit (1..5000, default 200) |
| cct_live_event | POST /computers/{id}/event | computer; name; args: array |
| cct_live_type | 3x POST /event: paste text, key [257,false], key_up [257] | computer; text (one line); enter (default true) |
| cct_live_power | POST /computers/{id}/power | computer; action on\|shutdown\|reboot |

## EXIT_CODES

| code | meaning |
|---|---|
| 0 | run: program finished without error |
| 1 | run: Lua error (syntax or runtime, includes "Too long without yielding" and "Terminated") |
| 2 | run: timeout (program still running after `--timeout` seconds of real time) |
| 3 | run: program shut down or rebooted the computer |
| 4 | run: out of memory (heap limit reached, D33) |
| 64 | usage error (unknown command/option, bad option value) |
| 65 | bad input (program/fixture not found or unreadable, invalid fixture, not a run directory, no such screen source, non-ASCII path under a non-UTF-8 locale) |
| 69 | no Java 17+ found (launcher, D27); guidance on stderr |
| (mcp) | `cct-run mcp` exits 0 at end of input; tool-level failures are tool results (D32), not exit codes |
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
| status | string | ok, error, timeout, shutdown, reboot, out_of_memory |
| exit_code | int | 0/1/2/3/4 |
| error | object or null | {phase: syntax or runtime, message, traceback (string or null)}; only when status=error |
| timeout_state | string | only on timeout and only if the VM was executing Lua at that moment (CobaltLuaMachine.printExecutionState); absent while waiting for events |
| memory | object | {heap_limit_mb: Runtime.maxMemory (989 for -Xmx1g with SerialGC), heap_peak_mb: sum of heap pools' peak usage, an upper bound, cct-run included (~40 for a small program)} |
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
- L25: MCP (test version): no progress notifications during long runs (clients with short tool timeouts, e.g. Codex's default 60 s, need a higher limit for long --timeout values; README shows `tool_timeout_sec`); no resources/prompts; text content only (no structuredContent). If a client kills the server without closing stdin, a running child JVM can outlive it until its own timeout (Windows: `cmd /c` wrapper in between). `latest` is shared by concurrent runs; agents are told to pass run_dir. Codex passes only selected environment variables to MCP servers, so JAVA_HOME may need to be set in its config.
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
| DV15 | memory | heap limit about 1 GB for cct-run as a whole; exceeding it ends the program with out_of_memory | no per-computer limit; computers share the server JVM's heap, so a hungry program lags or crashes the server | D33; a program using a few hundred MB passes here but is still a problem on a small server |
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
- R6: Mostly resolved by D33 (1 GB heap cap, out_of_memory status). Remaining: detection is a message match (a program that pcall-catches the OOM and continues is not flagged, though heap_peak_mb shows it); several MCP runs in parallel can each use up to 1 GB.
- R7: CLOSED. The owner created `main` (now the default branch) and merged PR #1 (work up to d7b9c4e). Later work continues on `claude/cct-run-cli`, which has `main` merged in (3bf8c50) so a follow-up PR carries only the new commits.

## TEST_KIT

For the maintainer (D22). Guide: `docs/testing.md`.

- `scripts/smoke-test.ps1` (Windows PowerShell 5.1 / PowerShell 7):
  - Runs 22 cases on Windows (21 elsewhere; mcp-via-cmd is Windows-only), plus two extra checks:
    - stdout decoded with the console's original encoding (`default_encoding_check`);
    - running the `.bat` from `cmd.exe`.
  - Also lists `java.exe` paths under LOCALAPPDATA, APPDATA and Program Files for N3 (paths only; `-NoJavaSearch` disables this).
  - Writes `cct-run-smoke-report.json` (schema `cct-run/smoke-report@1`), with `passed`, `failed`, `skipped`, `environment.java8_for_switch_test` and a `skipped` flag per result.
  - The file is kept ASCII-only for PowerShell 5.1.
- `scripts/smoke-test.sh` (macOS/Linux, bash 3.2, grep only): the same cases minus mcp-via-cmd (21); writes `cct-run-smoke-report.txt`. Java 8 for old-java-switch is searched under /usr/lib/jvm, /Library/Java/JavaVirtualMachines, ~/Library/Java/JavaVirtualMachines and `$CCT_SMOKE_JAVA8_ROOT`.
- Cases: version, args-with-space-and-separator, runtime-error, syntax-error, fixture-wired-network-monitor, trace-latest, screen-latest-monitor, redstone-timeline-terminate, scrolled-output, timeout, too-long-without-yielding, path-with-space-and-cjk, utf8-stdout, usage-error, missing-program, startup-time, tick-rate (18.5-21.5 ticks/s over 60 ticks), guide (`cct-run guide` prints the ASCII guide), memory-limit (examples/memory_hog.lua: exit 4, status out_of_memory, heap_limit_mb 900-1024; needs ~1 GB free RAM; ~4 s), mcp-stdio (one MCP session on stdin: initialize, tools/list = 4 tools, cct_run of print("mcp ok"), cct_guide), mcp-via-cmd (ps1 on Windows: the same through `cmd.exe /c "<bat>" mcp`, the form used in README client configs), old-java-switch (JAVA_HOME = a Java 8 home; expects exit 0 and the "is too old; using Java" stderr line; SKIP when no Java 8 is installed; ps1 searches `release` files up to depth 4 under Program Files, Program Files (x86), /usr/lib/jvm, /Library/Java/JavaVirtualMachines).
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
- V11: MCP server on Linux (build JDK 21):
  - McpServerTest 3 cases: handshake (protocol echo and fallback, 4 tools with object schemas, ping, -32601/-32700/-32602), child-JVM runs (code with Lua error -> isError false and correct /bad.lua:3 message; file with args incl. "b c"; inline fixture; invalid inline fixture -> isError true, JSON path shown, temp path hidden; argument errors; missing file), read-only tools (trace with kind/limit hint, empty result, screen, bad run dir), cancel of a running child (no response, server exits promptly).
  - Official MCP TypeScript SDK client (@modelcontextprotocol/sdk 1.32.1, StdioClientTransport) against the unzipped release zip: connect + initialize, listTools (4), cct_run with inline fixture and timed monitor_touch (status ok, monitor "hello", screen "touched 2 3"), cct_trace, cct_screen, syntax error result, bad timeout -> isError, cct_guide, two cct_run calls in parallel (1.76 s total). Same with JAVA_HOME = JRE 8 (launcher re-exec passes stdio through).
  - smoke-test.sh 20/20 and smoke-test.ps1 (PowerShell 7.6.7) 19 + 1 SKIP, both with the new mcp-stdio case.
- V12: Heap limit on Linux (Java 21, 16 GB RAM):
  - MainTest.heapExhaustionIsItsOwnStatus: child JVM with -Xmx128m runs an allocating program -> exit 4, out_of_memory, message contains OutOfMemoryError, heap_limit_mb <= 128, stopped well before the timeout. 39/39 tests.
  - Release zip: `version` heap_limit_mb 989; with JAVA_OPTS=-Xmx256m 247 (override works); started from JRE 8 (re-exec) 989.
  - examples/memory_hog.lua: exit 4 after 4.3 s, "/memory_hog.lua:7: vm error: java.lang.OutOfMemoryError: Java heap space", heap_peak_mb 983, tick_rate 19.7.
  - Through MCP (SDK client): memory_hog -> isError false, out_of_memory, heap_limit_mb 989 (child inherits -Xmx1g); a small program -> heap_peak_mb 40.
  - smoke-test.sh 21/21; smoke-test.ps1 (PowerShell 7) 20 + 1 SKIP.
- Before D33 (same machine, JAVA_OPTS=-Xmx256m): the OOM already surfaced as an ordinary Lua error (status error, exit 1), showing Cobalt's conversion.
- V13: Windows, by the maintainer, with the release zip of commit 732e355 (report e5f7fb1a-cct-run-smoke-report.json, unpacked at D:\AI\test\mcp):
  - Environment as V8 (Windows 11 26100, PowerShell 5.1, code page 936, Zulu 21 via JAVA_HOME).
  - smoke-test.ps1: 22/22, 0 skipped. default_encoding_check true; cmd.exe exit 0.
  - memory-limit: exit 4, out_of_memory, "/memory_hog.lua:7: vm error: java.lang.OutOfMemoryError: Java heap space", heap_limit_mb 989, heap_peak_mb 979, after 10 ticks / 0.6 s (Linux took 79 ticks / 4 s).
  - mcp-stdio and mcp-via-cmd pass: the `cmd /c "<bat>" mcp` form used in README client configs works on Windows; protocol 2025-06-18 negotiated.
  - old-java-switch: Zulu 8 -> Zulu 25, exit 0; heap_limit_mb 989 after the re-exec. Java 25 still prints the Guava sun.misc.Unsafe warnings on stderr (V10, open).
  - heap_peak_mb of ordinary runs 54-67; no_yield 146.
  - Agent task, CLI form (client not named): correct status/exit_code/message/line 4; counted 2 events and identified both as harness:true setting_changed caused by cct-run (W3 flags read correctly; contrast V6).
  - Agent task, MCP form (client not named): called cct_guide, cct_run on a file (the prompt's C:\cct-run path did not exist; the agent used the server's actual install path), cct_trace (2 harness events), cct_run with inline code (screen "hello from mcp"). All answers correct.
  - Not yet reported: which clients were used, and per-client results for Claude Code, Codex, dsh, Autoclaw (docs/testing.md 4b table).
- V5: The release zip contains bin/{cct-run, cct-run.bat}, lib/ (27 jars), LICENSE, LICENSES/ (3 + 5 third-party files), THIRD-PARTY-NOTICES.md, examples/, scripts/ (smoke-test.sh is executable), docs/testing.md, docs/agent-guide.md. harness.lua inside lib/engine-*.jar carries its CCPL header.

- V14: mod/ prototype (cct-live), Linux, JDK 21 build / Temurin 17 toolchain:
  - `./gradlew -p mod build` -> BUILD SUCCESSFUL (first run ~3.5 min incl. Minecraft decompile/recompile). Container note: repo.maven.apache.org answered 429, so a container-only init script (~/.gradle/init.d) put Google's Central mirror first; nothing of that is in the repo.
  - Dev server (`runServer`, Forge 47.4.10, flat world, driven by RCON): advanced computer placed with `{ComputerId:0,On:1b}` next to a chest; startup.lua sets colours, prints, sets redstone top, calls chest.list() (main thread), calls a missing method, waits for `char`, then errors.
  - Trace: trace_start, computer_seen, vm_start, power on, screen (fg "4..." yellow / bg "b..." blue), peripheral_call list -> event task_complete -> peripheral_return ok with both stacks; peripheral_call nosuchmethod -> peripheral_return ok false "No such method nosuchmethod"; redstone_output top 15; event char "x" injected via POST /event; screen shows "/startup.lua:19: deliberate failure". Reboot via POST /power -> vm_close, power off, vm_start, power on.
  - HTTP checks: no/wrong token 401; foreign Host 403; unknown computer 404; bad action / non-object body 400 (first build sent empty replies on errors: try-with-resources closed the exchange before the catch; fixed).
  - Production: Forge installer 1.20.1-47.4.10 `--installServer`, mods = CC:T 1.120.2 forge jar + reobf cct-live jar, Temurin 17: loads, same trace kinds, char injection works.
  - Owner, Windows single player (PCL2 launcher): mod loads, session dir and token created, but the endpoint listened on [::1]:47823 only (Get-NetTCPConnection), so http://127.0.0.1:47823 was refused; `InetAddress.getLoopbackAddress()` returned ::1 there (not reproducible on Linux, even with -Djava.net.preferIPv6Addresses=true). Fixed: bind 127.0.0.1 explicitly, log the bound address, accept Host [::1]:port. Re-checked on the production server (127.0.0.1 and localhost both 200).
  - Not tested: turtles/pocket/command computers, monitors, long sessions (trace size cap), performance with many computers.

- V15: http/fs tracing and cct_live_* tools, Linux:
  - Dev server, startup.lua downloads http://127.0.0.1:8765/User.lua (a local server answering an HTML 404 page; CC's http rules allowed 127.0.0.1 in the test world's config), writes it to icons/User.lua, then require("icons.User"). Trace: api http.request -> event http_success {handle, type http_response, url} -> handle readAll "<!DOCTYPE html>..." -> api fs.makeDir -> api fs.open icons/User.lua w -> handle write (same text) -> close -> api fs.open r + readAll (require) -> screen "/icons/User.lua:1: unexpected symbol near '<'" (same error as the owner's game). The shell then reads startup.lua line by line to print the failing line (visible as handle readLine records).
  - `cct-run mcp --live mod/run` (installDist) driven over stdio: 10 tools listed, instructions name the game directory; cct_live_type "startup" re-ran the program; cct_live_trace with ticks "<tick>-" returned the paste/key events and the whole chain above; cct_live_screen showed the error.
  - Unit tests: LiveToolsTest (fake endpoint): request paths/bodies, typing order, errors for missing configuration, missing endpoint.json, 404/401 from the endpoint, bad arguments, refused connection. 41/41 tests.
  - Not yet on Windows: the api/handle records and the MCP tools (the owner's earlier run predates them).

- V16: on demand, recording mode, attach/lease/chat (D40-D42), Linux:
  - Dev server (recording mode off, lease 30 s): before any attach no session directory exists; /computers lists attached false; event without attach -> 409 "not attached"; trace without attach -> 409. Attach -> chat/log "AI has taken control of computer 0 (lease 30 s ...)" (dedicated server console shows en_us; Chinese fallback in the log), trace: trace_start, attach, computer_seen, then events/api/handle of the re-run program. RCON `/cctlive list` -> entry with lease left; `/cctlive detach 0` -> "Took control of computer 0 back", a second detach -> "not controlled"; event afterwards -> 409. Re-attach, 36 s idle -> "lease ... expired; released", /computers attached false. Two trace files (computer-0-1, computer-0-2).
  - Recording mode on: attach at tick 171 -> `attach` history 26, followed by the program's records from tick 8 (vm_start, fs.open startup.lua, http.request, http_success, readAll of the HTML page, fs.makeDir, fs.open w, write ...) all marked history: true.
  - Overhead (production Forge 47.4.10, Temurin 17, 4-core container; 30 advanced computers running mod/bench/startup.lua: by id % 3 a queueEvent/pullEvent loop, a write+read file loop, a term.write loop; each writes its loop count once a second; 20 s warm-up, 30 s measured; MSPT from `/forge tps`; order nomod, dormant, recorder, attached, attached, recorder, dormant, nomod; averages of 2 runs):

    | condition | MSPT | events/s | file loops/s | screen loops/s |
    |---|---|---|---|---|
    | no mod | 1.01 | 8650 | 1250 | 33600 |
    | dormant | 0.94 | -1% | +8% | 0% |
    | recording mode | 1.01 | -7% | -8% | -5% |
    | all 30 attached | 1.22 | -18% | -18% | -10% |

    Run-to-run spread of the no-mod baseline ~5%, so dormant is indistinguishable from no mod. An earlier full series with only 20 of the 30 computers running (10 had been saved as off by a forced stop) gave the same picture (dormant +-2%, recording +-3%, attached -6% events / -17% file loops). Trace growth with all 30 attached: ~80 MB per minute. Synthetic tight loops are the worst case; programs that mostly wait cost less.

- V17: field test by an AI client on the owner's Windows single-player game (report: MPlayer installer, computer 2): attach, type, mouse clicks on Basalt buttons found by background colour, file repair through the lua REPL, reboot, detach all worked; root cause found from api/handle records (git.liulikeji.cn WAF answers any URL path containing "user" with a 4130-byte HTML slide-captcha page, status 200; the installer writes it as icons/User.lua). Reproduced independently here: icons/User.lua, icons/UserX.lua, icons/ZZuserZZ.lua -> 200 text/html 4130 bytes containing slideBox; icons/Like.lua -> 200 text/plain 110; icons/Nope.lua -> 404. Tool feedback -> D43.
- V18: D43 on the dev server through `cct-run mcp --live mod/run`: 14 tools; cct_guide section 7 alone (5213 chars); printed_error "/startup.lua:9: module 'icons.User' not found ..." with program startup.lua; files list / (gui.lua 530, icons dir, startup.lua 234, free 997970), read icons/User.lua (56 bytes, utf-8, eof, the HTML), path with '..' -> 400; regions on a two-box test screen -> two bg '6' rectangles centred (12,16) and (36,16); cct_live_click (12,16) -> program printed "clicked 12,16 button 1" and "released 12,16"; screen at tick (attach+2) rebuilt from the trace; detach + attach -> one computer-0.jsonl with attach seq 2, detach seq 49, attach seq 50. 41 tests passing (cli LiveToolsTest/McpServerTest extended). Production-server check of this jar not done (interrupted).

- V19: D44 on the dev server through `cct-run mcp --live mod/run` with an advanced monitor behind computer 0 and files.allow_write = true: 16 tools (tools/list 18.7 k characters); attach lease_seconds 3600 -> monitors ["back"], files_write true; wait text on the monitor timed out after 10.2 s as it should (the 7-column monitor showed "hello m"); screen source back [7,5]; click source back -> monitor_touch, program printed "monitor done" (wait met text); files write diag/check.lua (110 bytes) then type "diag/check" -> wait text "first" -> "first < len 56"; multi-line type into the lua REPL (lua / print(1+1) / print(fs.getFreeSpace('/')) / exit()) -> wait idle -> "2", "997470", prompt back; status new_since_after counts; trace summary kinds; handle readAll icons/User.lua bytes 56; file_write and monitor records; monitor screen replay at a tick. Largest results: trace 1864 chars, wait 659, status 580, screen 144. cli tests 16 (13 + wait, more call cases). Production server not re-checked for this build.

- V20: D45 on the dev server through `cct-run mcp --live mod/run`: 18 tools; startup (HTML download + require) -> handle readAll with body 1 -> cct_live_body returned the 56-byte page; cct_live_lua (read icons/User.lua, load() it, return values) -> ok, output "size 56" / "compiles false ... unexpected symbol near '<'", returns ["56","{free=995871,}","\"x\""], 0.4 s; error('boom') -> phase runtime with traceback; "return (" -> phase compile; /.cctlive empty afterwards; card.lua (card and button share bg pink) -> one region [2,6]-[47,10] with labels [38,7]-[44,9] (button), [4,8]-[8,8], [11,8]-[16,8]; click at the button label center (41,8) -> program printed "clicked 41,8". cli tests pass (tool count 18, lua prompt check, wrapper bracket levels, body request).
- V21: D46 on the dev server through `cct-run mcp --live mod/run`: attach -> computer_dir absolute; power on -> wait kind printed_error met at seq 16 (startup error), a second wait with the same kind -> timeout (old record not returned); snapshot save (7 files, 2 dirs) -> write tmp/new.lua and change startup.lua -> diff added [tmp/new.lua], changed [startup.lua], dirs_added [tmp] -> restore written [startup.lua], deleted [tmp/new.lua, tmp/], identical true -> diff identical; trace contains new.lua -> the one file_write record; lua sleep(3) with timeout 1 -> pending name; 4 s later result: name -> ok, output [hi], returns ["late"]; next lua -> /.cctlive empty; detach -> temp_files_removed 1, /.cctlive gone. cli tests 46/46 (wait start seq, snapshot/contains requests, pending collect, detach cleanup request).

- V22: D47 on Linux. (1) Reproduced with the agent's fixtures: CLI run iso_b.json -> exit 65 "$.peripherals.Create_Stressometer_0: not a side"; through `cct-run mcp` with JAVA_OPTS=-Dstderr.encoding=GBK and the fixture in a directory named \u6d4b\u8bd5: before -> "internal error: java.nio.charset.MalformedInputException: Input length = 1", after -> "exit code 65 (bad input) ... not a side". Simulator reproduces the in-game deadlock: timer 0.05 s, six chest.list() calls, then waiting for that timer -> "timer LOST" while the trace lists the timer event. (2) Dev server (Forge 47.4.10 via RCON: advanced computer {On:1b}, chest on one side, wired_modem_full[peripheral=true] on the other with a second chest behind it, advanced monitor behind; trace.max_megabytes_per_computer = 1) through a sequential MCP client: a program polling `peripheral.call("minecraft:chest_0","list")` every 0.05 s and printing "EVENT <name>" for any other event; cct_live_peripheral minecraft:chest_0 (no method) -> type minecraft:chest, additional [inventory], 6 methods; list -> coal 12 / diamond 2; getItemDetail [4] -> Diamond with tags; back getSize -> [7,5]; pushItems -> 400 read-only refusal; unknown name -> 400 with the available names; the program printed no EVENT line and kept ticking; trace peripheral=minecraft:chest_0 method=list -> records with via "left"; peripheral_query records with by ai. Rotation: two segments of ~512 KB, trace_rotated at seq 2481 (first_seq 1) and 4865 (first_seq 2481), status first_seq, summary across both files, screen at a tick after the rotation rebuilt (term and monitor), a dropped tick -> 404 naming first_seq. Wait: terminate -> wait text "stopped at" met on the changed screen; reboot -> wait "stopped at" (still the old text when the wait starts) -> timeout instead of an immediate match. cli tests 20/20 (LiveToolsTest waitIgnoresTextLeftFromBeforeTheAction drives one LiveTools in order because a session runs calls concurrently; McpServerTest fixtureErrorUnderNonAsciiPath needs a UTF-8 locale and skips otherwise, L20).

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
4. N4: Heap limit DONE (D33, V12). Remaining: a trace size cap with truncation (plus a `truncated` flag in the summary), and `cct-run clean`.
5. N5: DONE as test version (D30-D32, V11). Windows smoke and one MCP client session confirmed (V13). Open: per-client results for Claude Code, Codex, dsh, Autoclaw (docs/testing.md 4b); then consider progress notifications for long runs and structuredContent.
6. N6: Peripherals as the skill's templates need them (D20): e.g. furnace slot rules (DV3), speaker, printer, disk drive, wireless modem + multi-computer rednet. Each one adds DIVERGENCES rows.
7. N7: Colour in screen records (L14), if agents need it.
8. N8: Startup time: optional AppCDS archive created on first run (one file per install); measure the benefit on Java 17.
9. N10: Public release after R4 is decided.
10. N11: cct-live (mod/, D34-D42). Prototype done (V14), http/fs tracing and cct_live_* MCP tools done (V15), on demand + recording mode + attach/lease (V16). Next: owner re-test on Windows with an MCP client; structured program result (harness in ROM via datapack + Lua API -> status/error/traceback like summary.json); monitors, turtle state, file read/write; share Trace/JsonValues with engine in a common module; sim-vs-live trace diff for R1.
11. N12 (open, owner asked whether it is realistic): fixture peripherals for mod blocks without mod-specific code: a scripted peripheral (type name + method -> value, sequence, or timeline change; maybe small Lua bodies later), its values taken from the game with cct_live_peripheral or peripheral_return records (later a tool that turns a live trace into a fixture); mod events (AP `chat`) injected through the timeline with an argument layout recorded in the game. Reasoning in docs/joint-test-factory-report.md.
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
