# cct-live

**Let AI coding assistants work with your real CC: Tweaked computers, with your permission.**

cct-live lets an AI assistant that supports MCP (Claude Code, Codex, Claude Desktop, DeepSeek Harness and others) **take over a CC: Tweaked computer inside a running game**, but only after you allow it. The AI can read the screen, see what the computer actually did, edit files, run Lua and type input, so it can test and debug your programs in the real world instead of guessing.

It pairs with **[cct-run](https://github.com/EvergardenRoxy/CC-Tweaked_MCP)**, a local MCP server. cct-run also includes an offline simulator that runs CC: Tweaked's real code without launching Minecraft. This mod covers what the simulator can't: turtles, HTTP, real modded peripherals and the real world.

> ⚠️ **Prototype.** The interface may still change. Always update cct-live and cct-run together.

---

## Features

- **Nothing to install on the computer.** You don't need a Lua script, `wget run`, changes to CC's HTTP rules or a relay server.
- **Recorded on the Java side.** The program being tested can't hide or rewrite the record. The trace includes:
  - every event the Lua VM receives, and every peripheral call with its return value
  - HTTP requests and responses, and file reads and writes
  - errors printed with `printError`, and VM errors such as *Too long without yielding*
  - redstone output, the screen in color, power changes and turtle movement
- **The AI can act on the computer.** It can type text, click the screen or monitors, send any event, turn the computer on, off or restart it, and run a snippet of Lua and get its output and return value back.
- **Files and snapshots.** The AI can read files on the computer and in `/rom`. Writing files is **off by default** and must be enabled in the config. The AI can also save a snapshot of all the computer's files, diff against it and restore it.
- **Waits on the server.** The AI can wait for some text to appear, for an error, or for the screen to settle, instead of polling repeatedly.
- **Lightweight.** Computers that haven't been taken over aren't recorded. In our benchmark, the overhead was within measurement error.

## Safety and control

- The AI only touches a computer **after it is attached**, and the chat announces it: *"[cct-live] AI has taken over computer 0…"*
- Any player can take control back at any time with `/cctlive detach <id>`. Control is also released automatically when the lease expires (30 minutes by default).
- **Writing files is disabled by default** (`files.allow_write = false`).
- In local mode, the mod listens only on `127.0.0.1`, requires a token for every request and rejects foreign Host headers.

## Multiplayer (network bridge, preview)

On a multiplayer server, players can let **their own local AI** operate computers they have authorized. The connection goes through their existing game connection, so the server doesn't need to open any extra ports.

- To authorize a computer, click the **AI** button next to its terminal or run `/cctlive network authorize <id>`. Click the button again or run `/cctlive network revoke <id>` to revoke.
- An authorization lasts 30 minutes. Only one MCP session can control a computer at a time.
- **FTB Chunks** (optional): in claimed chunks, the player needs both interact and edit permission.
- Server admins can turn the bridge off with `network.enabled = false`.

For the bridge, install cct-live on **both the client and the server**.

## Requirements

| Minecraft | Loader | CC: Tweaked |
|---|---|---|
| 1.20.1 | Forge 47.1+ | **1.120.2** (exact) |
| 1.21.1 | NeoForge 21.1.9+ | **1.120.2** (exact) |

cct-live uses CC: Tweaked internals, so it is pinned to **CC: Tweaked 1.120.2**, and the loader will refuse other versions.

On the AI side you also need **[cct-run](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/releases)**, version 0.3.0 or later.

## Quick start

1. Put cct-live and CC: Tweaked 1.120.2 in `mods/`, then enter a world.
2. Register cct-run as an MCP server and point it at your game directory. For Claude Code, add this to `.mcp.json`:
   ```json
   { "mcpServers": { "cct-run": {
       "command": "/path/to/cct-run/bin/cct-run", "args": ["mcp"],
       "env": { "CCT_LIVE_DIR": "/path/to/.minecraft" } } } }
   ```
3. Ask your AI to list the computers (`cct_live_computers`) and attach to one.

For the full tool list, config options and the HTTP API, see the [mod documentation](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/main/mod/README.md).

Tip: the companion AI skill [CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill) teaches the AI how to write CC programs and when and how to test them.

## AI disclosure

This project was built with generative AI (Claude Code) writing code, tests and documentation under the maintainer's direction. The maintainer reviews all of it, verifies it with unit tests and in-game testing, and is responsible for maintaining it.

---

<details>
<summary><b>中文介绍</b></summary>

**经你同意后，让 AI 编程助手接管游戏里真实的 CC: Tweaked 电脑。**

cct-live 让支持 MCP 的 AI 助手（Claude Code、Codex、Claude Desktop、DeepSeek Harness 等）在你允许之后，**接管正在运行的游戏里的 CC: Tweaked 电脑**：查看屏幕和完整运行记录、读写文件、运行 Lua、输入操作。这样它能在真实世界里测试、调试你的程序，不用靠猜。

需要配合本地 MCP 服务器 **[cct-run](https://github.com/EvergardenRoxy/CC-Tweaked_MCP)** 使用。cct-run 自带离线模拟器，模拟器测不到的内容（海龟、HTTP、真实的模组外设、真实世界）由本 mod 补上。

- 电脑里不用装任何脚本，不用放开 http 规则，也不用转发服务器；
- 记录在 Java 层完成，被测程序无法隐藏或改写这些记录：事件、外设调用和返回值、HTTP、文件读写、`printError`、VM 错误、红石、带颜色的屏幕、海龟移动；
- 接管时聊天栏公开提示，任何玩家都可以用 `/cctlive detach <id>` 收回；写文件默认关闭；
- 多人服务器（预览）：玩家点击电脑界面旁的 **AI** 按钮授权，通过已有的游戏连接让自己本机的 AI 操作，服务器不用开放端口；支持 FTB Chunks 领地权限；
- 要求 CC: Tweaked **1.120.2**（精确版本），Forge 1.20.1 或 NeoForge 1.21.1；
- **原型阶段**，cct-live 和 cct-run 请一起升级。

完整文档：[mod/README.md](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/main/mod/README.md)。本项目在维护者指导下由 AI（Claude Code）参与编写，所有内容经维护者审阅和测试。

</details>
