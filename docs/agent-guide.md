# cct-run agent guide

For AI agents. `cct-run guide` and the MCP tool `cct_guide` return this text.

cct-run runs a CC: Tweaked Lua program on a headless computer: CC: Tweaked 1.120.2, Minecraft 1.20.1, real time at 20 ticks/s. It returns a JSON summary and keeps a full trace of the run, all recorded outside Lua. Use it to check a program before handing it to the user, and to debug it from evidence instead of guessing.

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
cct-run trace  <run-dir|latest> [--kind K1,K2] [--ticks A-B] [--peripheral NAME] [--source NAME] [--offset N] [--limit N]
cct-run screen <run-dir|latest> [--tick N] [--source term|MONITOR]
cct-run guide | version | help
```

- `--timeout` is in seconds of game time. Default 15, maximum 3600.
- Program arguments go after `--`.
- `latest` means the newest run under `--out` (default `.cct-runs` in the current directory).
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
| `status`, `exit_code` | `ok` 0, `error` 1 (syntax or runtime error, including "Too long without yielding" and "Terminated"), `timeout` 2, `shutdown`/`reboot` 3. Other exit codes: 64 usage, 65 bad input (missing file, invalid fixture with JSON path), 69 no Java 17+, 70 internal. |
| `error.phase` | `syntax` (did not compile) or `runtime` |
| `error.message` | `/<file>.lua:<line>: <message>`; the line number is in the user's file |
| `error.traceback` | stack frames, innermost first. Frames in `exception.lua` / `bios.lua` belong to CraftOS; the first `/<file>.lua` frame is where to look |
| `screen.lines` | final screen of the computer, trailing blanks removed. Lines that scrolled away are in the trace (`output`) |
| `monitors` | final screen of each monitor, by peripheral name |
| `notes` | the first noteworthy records: peripheral errors, the failed program end, VM errors, tick lag |
| `trace_counts` | number of trace records per kind |
| `duration` | `ticks`, `seconds` (ticks/20), `wall_ms`; `tick_rate` (about 20; only for runs of at least 2 s) |
| `timeout_state` | where Lua was running at the timeout. Absent when the program was waiting for an event, which is the usual case |

Screen text is CC: Tweaked's own character set. Characters `\u0080`-`\u00ff` are CC graphics glyphs, not Latin-1 letters. For example, the error display draws its gutter with `\u0095` and a marker with `\u0083`. Strings printed from Lua are bytes, so UTF-8 text shows as two or three such characters per letter. CC cannot display Chinese.

## 4. Investigating with the trace

Every record has `seq`, `tick`, `ms` and `kind`. Useful queries:

| question | command |
|---|---|
| What did the program call, and what came back? | `trace latest --kind peripheral_call,peripheral_return` |
| Which calls failed? | the summary's `notes`, or `trace latest --kind peripheral_return`, then look for `"ok":false` |
| Which events reached the program, and when? | `trace latest --kind event` |
| Everything printed, including lines scrolled away | `trace latest --kind output`, then the summary's `screen.lines` |
| What was on screen at tick N? | `screen latest --tick N`, or `--source monitor_0` for a monitor |
| Redstone the program set | `trace latest --kind redstone_output` |
| What happened between ticks 40 and 60? | `trace latest --ticks 40-60` |

Kinds and their pitfalls:

- `event {name, args}`: every event delivered to the Lua VM, including `timer`, `task_complete` and `redstone`.
  - An event delivered while the program was inside `sleep()` or a filtered `os.pullEvent(x)` was received and thrown away. This is a classic CC bug: compare event ticks with what the program was doing.
  - `"harness": true` marks events caused by cct-run itself (its default settings). Ignore those.
  - `setting_changed` args are `(name, new value, old value)`.
- `peripheral_call` / `peripheral_return` share an `id`.
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

Timeline actions:
- `event` queues any event, e.g. `key`, `char`, `monitor_touch`, `rednet_message`-style custom events.
- `redstone` sets an input level.
- `item` sets or (with `null`) clears a slot.
- `energy` sets stored energy.
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
- **Energy and fluid amounts:** change only through the timeline.
- **Time:** in-game time is frozen at 0 (`os.time()`, `os.day()`); real time (`os.clock()`, `os.epoch`) runs normally.
- **Disk:** the 1 MB limit is reported but not enforced.
- **Multishell:** off by default (`multishell` is nil); set `computer.multishell: true` if the program needs it.
- **Startup:** cct-run runs the program from `/startup.lua`, so `shell.getRunningProgram()` returns "startup.lua". A program named `startup.lua` is stored as `/main.lua`.

Suggested wording: "Tested in cct-run (CC: Tweaked 1.120.2 simulation): <what passed>. Not covered by the simulation: <relevant items above>."
