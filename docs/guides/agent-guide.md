# cct-run agent guide

For AI agents. `cct-run guide` and the MCP tool `cct_guide` return this text.

cct-run runs a CC: Tweaked Lua program on a headless computer: CC: Tweaked 1.120.2, Minecraft 1.20.1, real time at 20 ticks/s (source of this version: github.com/cc-tweaked/CC-Tweaked tag `v1.20.1-1.120.2`; raw files at `https://raw.githubusercontent.com/cc-tweaked/CC-Tweaked/v1.20.1-1.120.2/<path>`, e.g. `projects/core/src/main/resources/data/computercraft/lua/rom/apis/peripheral.lua`). It returns a JSON summary and keeps a full trace of the run, all recorded outside Lua. Use it to check a program before handing it to the user, and to debug it from evidence instead of guessing.

Sections: 1-6 cover the simulation (`run`, `trace`, `screen`); 7 covers real computers in a running game (MCP `cct_live_*` tools). Through MCP, `cct_guide` with `section: N` returns one section.

## 1. Workflow

1. Save the program to a file, e.g. `door.lua`.
2. Run it:
   - Windows: `bin\cct-run.bat run door.lua`
   - macOS/Linux: `bin/cct-run run door.lua`
3. Read `status` and `exit_code` (section 3). If it failed, read `error`, `screen` and `notes`.
4. To find out why, query the trace (section 4). Do not guess from the code alone.
5. Fix, re-run, repeat.
6. Programs that interact with the world need a fixture (section 5): peripherals, redstone, and events at given ticks.
7. Programs that loop forever end with `status: "timeout"`. That is normal, so judge them by the screen, the outputs and the trace.
8. When you report to the user, say what was verified and what was not (section 6).

## 2. Commands

```
cct-run run <program.lua> [--fixture FILE] [--timeout SECONDS] [--out DIR] [--term WxH] [--basic|--advanced] [-- program args]
cct-run trace  <run-dir|latest> [--kind K1,K2] [--ticks A-B] [--peripheral NAME] [--source NAME] [--contains TEXT] [--offset N] [--limit N]
cct-run screen <run-dir|latest> [--tick N] [--source term|MONITOR]
cct-run guide | version | help
```

- `--timeout` is in seconds of game time. Default 15, maximum 3600.
- Program arguments go after `--`.
- `latest` means the newest run under `--out` (default `.cct-runs` in the current directory). A bare `run_id` also works for a run under `--out`.
- `--term 26x20` gives a pocket computer size; `--basic` gives a non-colour computer.

Through MCP (`cct-run mcp`), the same commands are tools. Results are the same JSON as the CLI's stdout.

| tool | command | arguments |
|---|---|---|
| `cct_run` | `run` | `file` (path) or `code` (Lua source; `name` sets its file name, default `program.lua`), `args` (array), `fixture` (JSON object) or `fixture_file`, `timeout`, `term`, `computer` (`advanced`/`basic`) |
| `cct_trace` | `trace` | `run` (run_dir or `latest`, the default), `kind` (array), `ticks`, `peripheral`, `source`, `offset`, `limit` |
| `cct_screen` | `screen` | `run`, `tick`, `source` |
| `cct_guide` | `guide` | none |

- A Lua error, a timeout or a shutdown is a normal result (`isError: false`); read `status`. `isError: true` means the call itself was wrong: bad arguments, missing file, invalid fixture (with its JSON path).
- Use absolute paths for `file`, `fixture_file` and `run`. Every result of `cct_run` contains `run_dir`; pass it as `run` when several runs are in progress, since `latest` is simply the newest one.
- In the command examples below, read `trace latest --kind event` as `cct_trace {"kind": ["event"]}`, and so on.

Platform notes:
- stdout is pure ASCII JSON: non-ASCII characters are escaped as `\uXXXX`. Parse it as JSON; never compare raw bytes.
- Windows PowerShell 5.1 wraps native stderr in `NativeCommandError` records when redirected. That is PowerShell noise, not a cct-run error; the message is inside it.
- cct-run needs Java 17+. If started with older Java, it switches to an installed Java 17+ by itself, and stderr says so. If there is none, it exits 69 and explains how to set `JAVA_HOME`.

## 3. Reading the summary (stdout of `run`, also `summary.json` in the run directory)

| field | meaning |
|---|---|
| `status`, `exit_code` | `ok` 0, `error` 1 (syntax or runtime error, including "Too long without yielding" and "Terminated"), `timeout` 2, `shutdown`/`reboot` 3, `out_of_memory` 4 (the program used up the heap limit, about 1 GB; `error.message` names the line). Other exit codes: 64 usage, 65 bad input (missing file, invalid fixture with JSON path), 69 no Java 17+, 70 internal. |
| `error.phase` | `syntax` (did not compile) or `runtime` |
| `error.message` | `/<file>.lua:<line>: <message>`; the line number is in the user's file |
| `error.traceback` | stack frames, innermost first. Frames in `exception.lua` / `bios.lua` belong to CraftOS; the first `/<file>.lua` frame is where to look |
| `screen.lines` | final screen of the computer, trailing blanks removed. Lines that scrolled away are in the trace (`output`) |
| `monitors` | final screen of each monitor, by peripheral name |
| `notes` | the first 10 noteworthy records: peripheral errors, the failed program end, VM errors, tick lag |
| `notes_dropped` | number of noteworthy records omitted from `notes`; present only when nonzero. They are still recorded in trace.jsonl; query the trace for the remaining diagnostics |
| `trace_counts` | number of trace records per kind |
| `duration` | `ticks`, `seconds` (ticks/20), `wall_ms`; `tick_rate` (about 20; only for runs of at least 2 s) |
| `memory` | `heap_limit_mb` (about 1000) and `heap_peak_mb`: the most heap used, cct-run itself included (about 40 MB for a small program). A peak near the limit means the program keeps too much data, even if it did not fail |
| `timeout_state` | where Lua was running at the timeout. Absent when the program was waiting for an event, which is the usual case |

Screen text is CC: Tweaked's own character set. Characters `\u0080`-`\u00ff` are CC graphics glyphs, not Latin-1 letters. For example, the error display draws its gutter with `\u0095` and a marker with `\u0083`. Strings printed from Lua are bytes, so UTF-8 text shows as two or three such characters per letter. CC cannot display Chinese.

`disk` reports `capacity_bytes`, `free_bytes` and cumulative `written_bytes`. An interrupted embedding thread retains status `timeout` / exit 2 with `termination_reason: "interrupted"`; its interrupt flag is restored after summary/state writes.

Each run directory has atomic `state.json` updates (`starting`, `running`, `finished`, `failed`). A leftover running state after external SIGKILL or power loss is the last known state, not proof of liveness: check `pid` and `pid_started_at`. A summary is not guaranteed after external termination. MCP cancellation and wall-clock kills update the exact reserved run directory.

Input limits: 16 MiB per MCP line or fixture file, 128 nested JSON containers, 100,000 nodes, timeout 1-3600 seconds. Oversized MCP lines are drained with bounded memory. JSON-RPC ids must be strings, numbers or null. A tools/call notification without id is ignored with a stderr diagnostic; ordinary EOF drains submitted work. Explicit cancellation interrupts live tools and kills run children. Protocol stdout contains only JSON-RPC responses; fatal framing OOM exits with stderr and code 4.

MCP input must be strict JSON: quote object keys and strings with double quotes; comments and trailing content are rejected with parse error -32700. The next valid line can still be processed.

OOM classification uses observed Java errors, not message matching, including an OOM caught by Lua. The harness captures its reporting helpers before the program runs and accepts completion once. This is hardening, not a sandbox against a program with full debug access.

## 4. Investigating with the trace

Every record has `seq`, `tick`, `ms` and `kind`. Malformed records are skipped and counted. Parameter/result previews have depth 4, up to 50 entries per container and 200 characters per string by default. Numbers follow Lua doubles (large integers may lose precision); NaN and infinities are strings. These preview bounds are independent of input limits. Useful queries:

| question | command |
|---|---|
| What did the program call, and what came back? | `trace latest --kind peripheral_call,peripheral_return` |
| Which calls failed? | the summary's `notes`, or `trace latest --kind peripheral_return`, then look for `"ok":false` |
| Which events reached the program, and when? | `trace latest --kind event` |
| Everything printed, including lines scrolled away | `trace latest --kind output`, then the summary's `screen.lines` |
| What was on screen at tick N? | `screen latest --tick N`, or `--source monitor_0` for a monitor |
| Redstone the program set | `trace latest --kind redstone_output` |
| What happened between ticks 40 and 60? | `trace latest --ticks 40-60` |
| Every record that mentions a method, command or text | `trace latest --contains setTargetSpeed` (text, event name, peripheral, method, arguments, results, screen rows; case-insensitive; MCP: `contains`) |

Kinds and their pitfalls:

- `event {name, args}`: every event delivered to the Lua VM, including `timer`, `task_complete` and `redstone`.
  - `args` are the values after the event name: in `local ev = {os.pullEvent()}`, `ev[1]` is the name and `args[1]` is `ev[2]`. Count from the Lua side before changing an index.
  - An event delivered while the program was inside `sleep()`, a filtered `os.pullEvent(x)`, or a `main_thread` peripheral method (it waits for `task_complete`) was received and thrown away, although the trace lists it. This is a classic CC bug: a timer that fires while the program is in a slow peripheral call is lost, and a loop that only re-arms its timer when the timer arrives stops for good. Compare event ticks with the program's peripheral calls. The fix is `parallel`: put the peripheral polling and the event handling (chat, touches, timers) in separate functions under `parallel.waitForAny`/`waitForAll`. Each coroutine keeps its own filter, so the listener gets every event while the poller waits (measured here: 20 of 20 chat events with `parallel`, 1 of 20 in a single loop). Compare deadlines with `os.epoch("utc")`.
  - Strings in `args` (and in peripheral results) are recorded as Lua receives them: Lua strings are bytes, and a character above U+00FF in a Java string arrives as `?`. Chinese text from a peripheral therefore reaches the program as `??` (the Advanced Peripherals chat box sends a separate `messageUtf8` with the real bytes). To inject UTF-8 text in a fixture event, write each byte as an escape up to U+00FF (`"\u00e5\u00b7\u00a5"` is the UTF-8 of U+5DE5). Fixture strings that would lose characters this way are listed in the summary's `notes` (`fixture_text`).
  - The other way round, a Lua string passed to a peripheral reaches Java byte by byte, so UTF-8 text appears in `peripheral_call` args as sequences like `\u00e5\u00b7\u00b2`, and the game shows it as mojibake. To send Chinese through the Advanced Peripherals chat box, use `sendFormattedMessage`/`sendFormattedMessageToPlayer` with a JSON text component whose text is written as `\uXXXX` escapes: `textutils.serialiseJSON({ text = msg }, { unicode_strings = true })` produces it.
  - A peripheral that the computer reaches twice (directly on a side and again over a wired modem) is attached twice; one that sends events (seen with the Advanced Peripherals chat box) then delivers each event twice. Deduplicate, or use only one of the two names.
  - `"harness": true` marks events caused by cct-run itself (its default settings). Ignore those.
  - `setting_changed` args are `(name, new value, old value)`.
- `peripheral_call` / `peripheral_return` share an `id`.
  - Integer method arguments follow CC:T `IArguments.getInt`: a fractional slot such as 1.5 truncates to 1. Fixture declarations require exact integers; this method-call conversion is separate.
  - Inventory, energy and fluid methods have `main_thread: true`. As in the game, they take at least one tick and return on a later tick.
  - The wired modem's internal lookups (`getNamesRemote`, `isPresentRemote`...) are not recorded.
- `program_start` marks when the program's code began running. It is absent after a syntax error.
- `screen {source, lines, cursor, size}`: only the rows that changed, 1-based.
- `output {source, text}`: a line that scrolled off the top.
- `redstone_output {side, level, bundled}`, `fixture_action`, `modem_transmit`, `program_end`, `tick_lag`, `vm_error`.

## 5. Fixtures (`--fixture world.json`)

Mistakes are reported with their JSON path and exit 65. Fix them and re-run.

```json
{
  "schema": "cct-run/fixture@1",
  "computer": { "id": 0, "label": "door", "advanced": true, "multishell": false, "term": "51x19" },
  "files": { "/config.txt": "speed=2" },
  "peripherals": {
    "left":  { "type": "inventory", "block": "minecraft:chest", "size": 27,
               "items": { "1": { "name": "minecraft:iron_ingot", "count": 64 } } },
    "top":   { "type": "monitor", "blocks": [3, 2] },
    "right": { "type": "energy_storage", "energy": 100, "capacity": 1000 },
    "bottom":{ "type": "fluid_storage", "tanks": [ { "name": "minecraft:water", "amount": 1000 } ] },
    "back":  { "type": "wired_modem", "network": {
                 "minecraft:chest_0": { "type": "inventory" },
                 "monitor_0": { "type": "monitor" } } }
  },
  "redstone": { "front": 15 },
  "timeline": [
    { "tick": 20, "do": "redstone", "side": "back", "level": 15 },
    { "tick": 30, "do": "event", "name": "monitor_touch", "args": ["top", 3, 2] },
    { "tick": 40, "do": "item", "peripheral": "minecraft:chest_0", "slot": 1, "item": { "name": "minecraft:coal", "count": 8 } },
    { "tick": 50, "do": "energy", "peripheral": "right", "energy": 900 },
    { "tick": 80, "do": "terminate" }
  ]
}
```

Peripheral rules:
- Sides: `top bottom left right front back`.
- On a wired modem, peripherals are reached by network name, e.g. `peripheral.wrap("minecraft:chest_0")`. Directly attached ones are reached by side.
- `pushItems` / `pullItems` targets resolve the same way.
- An inventory's type is its `block` (default `minecraft:chest`) plus `inventory`.
- Monitor size follows the game: 1x1 block = 7x5 characters at scale 1; `setTextScale(0.5)` doubles it.

Peripherals from other mods (Create, Advanced Peripherals, ...) are not modelled, but you can describe them with `"type": "scripted"`: you give the type name and what each method returns, and cct-run answers and traces the calls like any other peripheral. Take the names, methods and real values from the game first (`cct_live_peripheral` without `method` lists the methods; with a getter it returns the value), or from `peripheral_call`/`peripheral_return` records.

```json
"back": { "type": "wired_modem", "network": {
  "Create_Stressometer_0": { "type": "scripted", "peripheral_type": "Create_Stressometer",
    "methods": { "getStress": 11776, "getStressCapacity": { "returns": [16384] } } },
  "Create_RotationSpeedController_0": { "type": "scripted", "peripheral_type": "Create_RotationSpeedController",
    "methods": { "getTargetSpeed": -128, "setTargetSpeed": { "sets": "getTargetSpeed" } } },
  "playerDetector_0": { "type": "scripted", "peripheral_type": "playerDetector",
    "methods": { "isPlayerInRange": { "sequence": [[true], [true], [false]] },
                 "getPlayerPos": { "error": "Player is not online" } } },
  "chatBox_0": { "type": "scripted", "peripheral_type": "chatBox",
    "methods": { "sendMessageToPlayer": { "returns": [true] },
                 "getOperationCooldown": { "returns": [{ "chatMessage": 0 }] } } } } }
```

- A method's value is either one plain return value (number, string, boolean, array = list table), or an object with one of `returns` (all return values, so a table is `{"returns": [{...}]}`), `sequence` (the returns of successive calls; the last one repeats) or `error` (the call raises this message). No key at all: returns nothing.
- `sets: "<method>"`: the call stores its first argument as that method's return value (a setter and its getter).
- `main_thread` (peripheral or method, default `true`): like most mod peripherals, the call takes a tick and waits for `task_complete`, so events arriving meanwhile are dropped as in the game. `additional_types` adds types for `peripheral.hasType`/`find`.
- Nothing else is simulated: values change only through `sets` and the timeline, and machines do not react to each other (raising the speed does not raise the stress unless the timeline says so). Mod events (e.g. the chat box's `chat`) are timeline `event`s; copy their argument layout from a real event in `cct_live_trace`.

Timeline actions:
- `event` queues any event, e.g. `key`, `char`, `monitor_touch`, `rednet_message`-style custom events.
- `redstone` sets an input level.
- `item` sets or (with `null`) clears a slot.
- `energy` sets stored energy.
- `method` changes what a scripted peripheral's method returns from then on: `{ "tick": 60, "do": "method", "peripheral": "Create_Stressometer_0", "method": "getStress", "returns": [16000] }` (or `sequence`, `error`).
- `terminate` sends Ctrl+T, a clean way to end an endless loop.

Recipes:
- Redstone-triggered program: set the input with a `redstone` action, check `redstone_output` in the trace.
- Item sorting or storage: build the network in the fixture; after the run, check `peripheral_return` results, and read the counts from a final `list()` the program prints.
- UI on a monitor: inject `monitor_touch` events, then use `screen latest --source <monitor> --tick N`.
- Long-running loop: give it a `--timeout`, or use `terminate`. Then inspect the trace rather than the status.

## 6. What a pass means

A pass means the program works against this model, which is not guaranteed to match the game. Mention to the user any of these that apply:

- **Not available:** turtles, pocket and command computers, the world (blocks, chunk loading, server lag), http/websockets, speakers, printers, disk drives, wireless/ender modems, received modem messages.
- **Inventories:**
  - any slot accepts any item, with a limit of 64; real furnaces and modded blocks have slot rules;
  - `getItemDetail` returns only name, count, displayName, maxCount, tags, nbt and damage;
  - item transfer fills target slots in order (Forge behaviour; Fabric fills existing stacks first).
- **Fluids:** only `tanks()`.
- **Scripted peripherals:** they return what the fixture says, nothing more; their accuracy is that of the values you copied from the game.
- **Energy and fluid amounts:** change only through the timeline.
- **Time:** in-game time is frozen at 0 (`os.time()`, `os.day()`); real time (`os.clock()`, `os.epoch`) runs normally. `os.clock()` counts the computer's ticks (0.05 s steps, here and in the game): it does not move inside one run of Lua between two yields and falls behind when the server lags. Measure intervals with `os.epoch("utc")`.
- **Disk:** the root drive enforces 1,000,000 bytes, with at least 500 bytes per entry, including directories, the program and harness. Deleting or truncating frees space. Deleting a file closes its open handles in this simulator; host filesystem behaviour can differ.
- **Memory:** cct-run stops a program at about 1 GB of heap (`out_of_memory`). The game has no per-computer limit: the computer shares the server's memory, so a program that runs out here would lag or crash a server, and one that stays under it can still be too heavy for a small server.
- **Multishell:** off by default (`multishell` is nil); set `computer.multishell: true` if the program needs it.
- **Startup:** cct-run runs the program from `/startup.lua`, so `shell.getRunningProgram()` returns "startup.lua". A program named `startup.lua` is stored as `/main.lua`.

Suggested wording: "Tested in cct-run (CC: Tweaked 1.120.2 simulation): <what passed>. Not covered by the simulation: <relevant items above>."

## 7. Real computers in the game (cct_live_* tools, MCP only, prototype)

With the cct-live mod (Forge 1.20.1, CC: Tweaked 1.120.2) these tools observe and control the computers in the user's running game: real world, real peripherals, real http. Nothing is recorded for a computer until you attach it, and only an attached computer accepts input.

Save tokens: every tool result stays in your context. Check with `cct_live_status` (small), wait with `cct_live_wait` (one call, waits on the server side), and only then read details with `cct_live_trace` (filtered) or `cct_live_screen`. Never sleep and re-read the screen in a loop.

Tools:

- `cct_live_computers`: computers loaded now (id, label, family, on, position, attached).
- `cct_live_attach {computer, lease_seconds}`: start recording and controlling. The player sees a chat message and can take control back with `/cctlive detach <id>`. Every request renews the lease; for a long test pass a longer `lease_seconds`. Returns `last_seq`, `monitors` (sides with a monitor), `files_write` and `computer_dir` (where the game keeps this computer's files).
- `cct_live_status {computer, after, source}`: tick, on, `last_seq`, lease left, monitors, `new_since_after` (record counts by kind after seq `after`), `last_printed_error`, the screen's text lines and `screen_hash`.
- `cct_live_wait {computer, text | kind | idle_seconds, after, source, timeout, unchanged}`: returns when the screen shows `text`, a record of a `kind` appears (e.g. `["printed_error","http_failure","vm_close"]`; returned as `match`), or the screen stays unchanged for `idle_seconds`; `met` says which, or `timeout` (default 30 s, max 120). Without `after`, `kind` only matches records that appear after the wait starts. `text` only counts on a screen that changed since your last `cct_live_type`/`click`/`event`/`power`: a prompt `>` or a banner left from before the action does not end the wait (a timeout then carries a `note`); pass `unchanged: true` to accept text that was already there.
- `cct_live_type {computer, text, enter}`: types text; each line is followed by Enter. Several lines run in order (shell commands, lines in the `lua` REPL).
- `cct_live_click {computer, x, y, source}`: a click on the terminal (mouse_click + mouse_up) or a touch on a monitor (`source` = its side, monitor_touch).
- `cct_live_screen {computer, source, colors, regions, tick}`: text lines of the terminal or a monitor. `regions: true` adds colour areas (`bg`, `from`, `to`, `center`, `text`, `labels`): GUI buttons are such areas even when their labels are pixel art. When a button shares its background with the card around it, the region is the whole card: then use its `labels` (blocks of drawn content inside, e.g. the button's label) and click the label's `center`. `colors: true` adds fg/bg rows. `tick: N` rebuilds the screen at tick N from the trace.
- `cct_live_lua {computer, code, timeout}`: runs Lua on the computer in one call and returns `ok`, `output` (printed lines), `returns` (serialised), `error` and `phase` (compile or runtime, with traceback). It writes a temporary script under `/.cctlive`, runs it from the shell and deletes it. Use it for every diagnostic (sizes, `load()` a file to check its syntax, `peripheral.getMethods`, reading settings) instead of write/run/read/delete by hand. Needs the shell prompt on the screen (a program that crashed to a "press any key" screen: send a `char` event first) and the player's `files_write`; the code has the computer's full rights, so keep it read-only unless the user agreed to changes. On timeout it returns `pending: <name>`: the code may still be running; collect the result later with `cct_live_lua {computer, result: <name>}` instead of running it again. Leftover files under `/.cctlive` are removed on the next call and on `cct_live_detach`.
- `cct_live_snapshot {computer, op, name}`: `save` copies all of the computer's files (except `/.cctlive`) on the game side; `diff` lists `added`, `removed`, `changed` files and directories since then (`identical` true when nothing changed); `restore` writes back changed and removed files and deletes the added ones, then checks (needs `files_write`); `list` shows saved snapshots. Save before your first change; at the end `diff`, `restore` what you changed, and `diff` again to prove the computer is back. Do not copy the world folder by hand.
- `cct_live_peripheral {computer, name, method, args}`: calls a getter of one of the computer's peripherals (a side or a wired network name, e.g. `Create_Stressometer_0`) on the game side, without the computer's Lua: it works while a program runs, adds no event and does not disturb the program. Without `method` it lists the peripheral's type and methods. Only methods that read state (`get...`, `is...`, `has...`, `can...`, `list`, `size`, `tanks`, `count...`). Recorded as `peripheral_query`. Use it to check readings while the program under test is running instead of stopping it for `cct_live_lua`.
- `cct_live_body {computer, body, offset, length}`: the full body of an HTTP response read with `readAll` while attached (the `handle` record has `body: N`); trace data is cut to 200 characters.
- `cct_live_files {computer, op, path, ...}`: the computer's own files without running anything on it, and `/rom` read-only (the ROM this computer runs, e.g. `rom/apis/peripheral.lua`, to check how a CC API behaves): `list`, `stat`, `read` (up to 1 MB per call), and `write` (`text`, `append`) only when the player allowed it (`files_write`). A write is recorded as `file_write`. For diagnostics prefer `cct_live_lua`.
- `cct_live_trace {computer, kind, exclude, after, ticks, contains, peripheral, method, summary, offset, limit}`: records of this game session (all attachments; seq keeps counting). Default limit 50; `screen` records are left out unless `kind` names them. Start with `summary: true` (counts and seq ranges per kind). `contains` keeps records whose url, path, program, text, event name, peripheral, method or arguments contain the text (case-insensitive), e.g. `contains: "User.lua"` to follow one download through `api`, `handle` and the file write. `peripheral` and `method` keep the calls and returns of one peripheral or method (exact names). The trace keeps the newest records within the player's size limit: when older ones were dropped, results carry `first_seq` (the oldest left) and a `trace_rotated` record marks each drop. Kinds:
  - `printed_error`: text printed with `printError` and the running `program`; this is how the shell reports a program crash (e.g. `/startup.lua:911: module 'icons.User' not found ...`). Look here first. Under multishell (`bg`, `fg`) `program` can be the tab's launcher (e.g. `rom/programs/advanced/bg.lua`): trust the file name in the error text. `vm_error` only covers VM-level failures such as "Too long without yielding";
  - `api`: `http.request/checkURL/websocket` and `fs.open/delete/move/copy/makeDir` with args and result (`http.get` shows up as `http.request`);
  - `handle`: each call on an open file, HTTP response or websocket (`readAll`, `write`, ...) with `path` or `url`, the data cut to 200 characters, `bytes` = the full length, and for HTTP `readAll` a `body` number (read it with `cct_live_body`);
  - `peripheral_call`/`peripheral_return`: a peripheral on a wired network is recorded under its network name and method, with `via` = the modem's side (the program called `callRemote` on the modem);
  - `event` (see section 4: `args` start after the event name, and events that arrive during a peripheral call are dropped by CC), `screen` (`source` term or a monitor side), `monitor`, `redstone_output`, `power`, `label`, `moved`, `file_write`, `file_delete`, `snapshot`, `peripheral_query` (your `cct_live_peripheral` calls), `vm_start`, `vm_close`, `attach`, `detach` (reason detach, revoked = a player took control back, expired, removed, closed), `trace_rotated`. Files under /rom are not recorded.
- `cct_live_event {computer, name, args}`: any event (key codes are GLFW: enter 257). `terminate` is the same as the player holding Ctrl+T: a program that catches it may clean up, one that does not ends with "Terminated", and code after its event loop runs (which can expose unguarded paths). `cct_live_power {computer, action}`: on, shutdown, reboot.
- Every record has both `seq` (order in the trace) and `tick` (server time). `after` filters by seq, `ticks` by tick; `cct_live_status` and `cct_live_wait` return both the current `tick` and `last_seq`, so note both before an action.

Workflow:

1. `cct_live_computers`, then `cct_live_attach` (note `last_seq`); if you may change files, `cct_live_snapshot {op: "save"}` first.
2. Act (`cct_live_type`, `cct_live_click`), then `cct_live_wait` for the expected text, an error kind, or `idle_seconds`.
3. `cct_live_status {after: <last_seq>}` to see what happened; read only the kinds that matter with `cct_live_trace {kind, after}`; check facts on the computer with `cct_live_lua` (shell prompt) or `cct_live_peripheral` (while a program runs).
4. Explain from the records: e.g. an `api` http.request, then a `handle` readAll whose data starts with `<!DOCTYPE html>`, then a `handle` write of it to `/x.lua`, then a `printed_error` `unexpected symbol near '<'` means the download returned a web page, not Lua. Read the files involved with `cct_live_files`.
5. If you changed files: `cct_live_snapshot {op: "diff"}`, `restore` what is yours to put back, `diff` again. Then `cct_live_detach`.

Rules:

- Attach only when the user asked you to work on their computer. Input, writes and power change the user's real world: only what the user asked for or agreed to; never delete or overwrite their files to "test". Do not send `terminate` to a program unless asked: it changes what the program does next.
- If a tool reports `revoked`, the player took control back: stop and ask.
- No call stack yet for program errors (only the printed text).
- Monitors: only those attached directly to the computer, not over a wired network.
- If a tool says cct-live is not running, the user must open a world with the mod; `game_dir` is the game directory (the version folder when the launcher isolates versions).

Live trace notes: `first_seq` marks the retained window; older data has been rotated away. Status `trace_health` reports write failures, recovery and dropped records. Paginated trace results with `matched_at_least: true` give a lower bound; use a summary query for exact retained counts. Sequence and tick fields are 64-bit integers end to end. A screen hash covers dimensions, text and foreground/background colours; use it only for equality comparisons. If action baseline capture failed, wait reports `baseline_warning` and will not count stale text as a fresh match; `unchanged: true` explicitly allows existing text. Lua strings are bytes: Java U+0000..U+00FF map one byte each, wider chars map to '?'. Body byte lengths use that mapping, not UTF-8 encoding. A registry disappearance means unavailable/unloaded; AI control is not automatically reattached on return.
