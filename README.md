# cct-run：给 AI 用的 CC: Tweaked 测试环境

cct-run 让 AI 编程助手（Claude Code、Codex、DeepSeek Harness 等）**不用启动 Minecraft**，就能直接运行 CC: Tweaked 的 Lua 程序，并拿到结构化的运行结果和完整的运行记录，用来检查和调试程序。

- 运行的是 **CC: Tweaked 1.120.2（Minecraft 1.20.1）的真实代码**，包括 Lua 运行时、ROM、BIOS、shell 和各个 API，不是自己仿写的。
- 按真实时间运行，每秒 20 刻。
- 每次运行都会输出 JSON 摘要（结果、错误和行号、最终屏幕），并把全过程记录到 trace 文件里。

模拟器测不到的内容（海龟、HTTP、真实的模组外设和真实世界），可以交给可选的 **cct-live mod**：它是一个 Forge 1.20.1 mod，在玩家同意后，让 AI 接管正在运行的游戏里的真实电脑，查看它的运行记录，也可以操作它。不需要在电脑里运行 Lua 脚本，也不需要转发服务器。见 [`mod/README.md`](mod/README.md)。

有两种接入方式：

- **MCP 服务器**（推荐）：`cct-run mcp` 是一个本地 MCP 服务器（stdio），一共 18 个工具：模拟器 4 个（`cct_run`、`cct_trace`、`cct_screen`、`cct_guide`），实机 14 个（`cct_live_*`，需要 cct-live mod）。支持 MCP 的客户端（Claude Code、Codex、Claude Desktop、DeepSeek Harness 等）配置一次即可使用。
- **命令行**：AI 通过终端直接调用 `cct-run` 命令，适合不支持 MCP 或只有终端工具的客户端。命令行只有模拟器功能。

配套的 AI Skill [CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill) 会告诉 AI 什么时候该测试、怎么测试：有模拟器时交付前先运行，有实机工具时在你同意后到游戏里验证。

> 当前版本是 **0.1.0（首个测试版）**，更新内容见 [`docs/release-notes-0.1.0.md`](docs/release-notes-0.1.0.md)。cct-live 还是原型。

---

## 能做什么

| 功能 | 说明 |
|---|---|
| 运行程序 | 在无界面的 CC 电脑上运行 `.lua` 程序，可以传命令行参数，可以设置超时 |
| 报告结果 | `ok` / `error` / `timeout` / `shutdown` / `reboot`，并给出对应的退出码 |
| 定位错误 | 语法错误和运行时错误都给出文件名、行号、错误信息和调用栈，与游戏里显示的一致；“Too long without yielding”也能复现（约 7 秒） |
| 内存上限 | 每次运行最多使用约 1 GB 内存；超出时以 `out_of_memory` 结束（退出码 4）并指出出错行，不会拖垮电脑；摘要里给出内存峰值 |
| 屏幕内容 | 程序结束时电脑屏幕和每台显示器上的文字；滚出屏幕的行也有记录 |
| 全程记录（trace） | 每个事件、每次外设调用及其返回值、红石输出、屏幕变化都带时间（第几刻）记录下来，可以按类型、时间段、外设筛选查询 |
| 回放屏幕 | 查询任意一刻电脑或显示器上显示的内容 |
| 模拟外设和世界 | 用 fixture 文件布置箱子等容器、能量存储、流体存储、显示器、有线调制解调器网络、红石输入和预置文件 |
| 按时间注入事件 | 在指定的刻改变红石输入、修改箱子内容、改能量、发送任意事件（如按键、触摸显示器），或者按下 Ctrl+T 终止程序 |
| MCP 服务器 | `cct-run mcp`：本地 stdio MCP 服务器，模拟器的四个工具和命令一一对应；可以直接传 Lua 代码和 fixture 对象，不必先写文件；每次运行都在独立的 Java 进程里，可以同时运行多个，也可以取消 |
| 接管游戏里的电脑（cct-live） | 装了 cct-live mod 后，AI 可以按需接管真实游戏里的电脑：查看屏幕和显示器、读取运行记录（事件、外设调用、HTTP 和文件读写、`printError` 打印的错误）、输入文字、点击、读写文件、运行一段 Lua、开关机。接管时聊天栏有提示，玩家随时可以收回 |
| AI 使用说明 | `cct-run guide` 打印一份写给 AI 的英文说明：使用流程、各字段含义、排查方法、常见陷阱、“测试通过”意味着什么，以及实机工具的用法（第 7 节）；可以按章节读取 |

### 一个例子

```
> bin\cct-run.bat run examples\runtime_error.lua
{
  "status": "error",
  "exit_code": 1,
  "error": {
    "phase": "runtime",
    "message": "/runtime_error.lua:4: attempt to index local 'mon' (a nil value)",
    "traceback": "stack traceback:\n\t/runtime_error.lua:4: in local 'draw'\n..."
  },
  "screen": { "size": [51, 19], "lines": [ "/runtime_error.lua:4: attempt to index local 'mon'", ... ] },
  "trace_counts": { "event": 2, "screen": 2, ... },
  "run_dir": "...\\.cct-runs\\20261016-155108-066-fb88"
  ...
}
```

然后查这次运行收到了哪些事件：

```
> bin\cct-run.bat trace latest --kind event
```

---

## 安装

### 1. 准备 Java 17 或更高版本

玩 Minecraft 1.20.1 的电脑一般都已经装了。检查方法：

```
java -version
```

- 如果系统默认的是旧版 Java（例如 Java 8），也没关系：cct-run 会自动找本机已安装的 Java 17+ 并换用它，查找顺序是 `JAVA_HOME`、`PATH`，最后是常见的 JDK 安装目录。
- 如果一个 Java 17+ 都找不到，cct-run 会以退出码 69 结束，并提示如何设置 `JAVA_HOME`。可以安装任意一个发行版，例如 [Adoptium Temurin](https://adoptium.net/) 或 [Zulu](https://www.azul.com/downloads/)。

### 2. 下载并解压

从 [Releases](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/releases) 下载发布包 `cct-run-<版本>.zip` 并解压，例如解压到 `C:\cct-run`。还没有发布包时，按“从源码构建”一节自己构建。目录结构：

```
cct-run\
  bin\cct-run.bat   bin\cct-run      启动脚本（Windows / macOS、Linux）
  lib\*.jar                          程序本体，含 CC: Tweaked
  examples\                          示例程序和 fixture
  README.md                          本文件
  docs\agent-guide.md                给 AI 的使用说明
  docs\testing.md  scripts\          维护者测试脚本
  LICENSE  LICENSES\  THIRD-PARTY-NOTICES.md
```

不需要管理员权限，不写注册表，不需要安装 Minecraft 或其他模组。

### 3. 确认可以运行

```
# Windows（PowerShell 或 cmd）
bin\cct-run.bat version

# macOS / Linux
bin/cct-run version
```

能输出带有 `"cc_tweaked": "1.120.2"` 的 JSON 就说明装好了。

### 4. 接入 AI 客户端（MCP，推荐）

下面以解压到 `C:\cct-run`（Windows）或 `/opt/cct-run`（macOS / Linux）为例，请换成你自己的路径。Windows 上通过 `cmd /c` 启动 `.bat`，这是多数客户端启动批处理文件的可靠写法。

**Claude Code**：在 **cmd** 里运行（PowerShell 可能吞掉 `--`）：

```bat
claude mcp add --scope user cct-run -- cmd /c C:\cct-run\bin\cct-run.bat mcp
```

macOS / Linux：

```bash
claude mcp add --scope user cct-run -- /opt/cct-run/bin/cct-run mcp
```

也可以在项目根目录放一个 `.mcp.json`（只对这个项目生效，第一次使用时 Claude Code 会请你确认）：

```json
{
  "mcpServers": {
    "cct-run": { "command": "cmd", "args": ["/c", "C:\\cct-run\\bin\\cct-run.bat", "mcp"] }
  }
}
```

**Codex**：编辑 `~/.codex/config.toml`（Windows 上是 `%USERPROFILE%\.codex\config.toml`）：

```toml
[mcp_servers.cct-run]
command = "cmd"
args = ["/c", "C:\\cct-run\\bin\\cct-run.bat", "mcp"]
tool_timeout_sec = 300          # 运行可能持续到 --timeout 秒，默认的 60 秒可能不够
# Codex 不会把所有环境变量传给 MCP 服务器；如果 java 不在 PATH 上，请写明：
# env = { JAVA_HOME = "C:\\Program Files\\Zulu\\zulu-21" }
```

**其他支持 MCP 的客户端**（Claude Desktop、Cherry Studio 等使用 `mcpServers` JSON 的客户端；DeepSeek Harness、Autoclaw 如果支持 MCP，填写同样的命令和参数即可，配置位置见各自的文档）：

```json
{
  "mcpServers": {
    "cct-run": {
      "command": "cmd",
      "args": ["/c", "C:\\cct-run\\bin\\cct-run.bat", "mcp", "--out", "C:\\cct-run\\runs"]
    }
  }
}
```

说明：

- 运行记录默认保存在客户端启动服务器时所在目录的 `.cct-runs` 下（Claude Code 是项目目录，建议把 `.cct-runs/` 加进 `.gitignore`）；这个目录不可写时改存到 `~/.cct-run/runs`。像上面那样加 `--out 目录` 可以固定位置。
- 服务器启动后，第一次调用前 AI 会看到提示：先调用 `cct_guide` 阅读说明。
- 配置好后可以直接对 AI 说：“写一个……的 CC 程序，并用 cct_run 测试。”

**（可选）接管游戏里的电脑**：先按 [`mod/README.md`](mod/README.md) 安装 cct-live mod，再在 MCP 配置里用环境变量 `CCT_LIVE_DIR` 告诉 cct-run 游戏目录（有 `mods/` 的那个文件夹；启动器开了版本隔离时是版本文件夹）。也可以在参数里加 `--live <游戏目录>`。例如：

```json
{
  "mcpServers": {
    "cct-run": {
      "command": "cmd",
      "args": ["/c", "C:\\cct-run\\bin\\cct-run.bat", "mcp"],
      "env": { "CCT_LIVE_DIR": "C:\\.minecraft\\versions\\1.20.1-Forge" }
    }
  }
}
```

Claude Code 可以用 `claude mcp add --scope user -e CCT_LIVE_DIR=<游戏目录> cct-run -- cmd /c C:\cct-run\bin\cct-run.bat mcp`；Codex 在 `config.toml` 里写 `env = { CCT_LIVE_DIR = "<游戏目录>" }`。路径里有空格或中文都可以，JSON 里的 `\` 要写成 `\\`。

### 4（另一种）. 用命令行方式接入

在 AI 客户端的项目说明文件里（Claude Code 用 `CLAUDE.md`，Codex 用 `AGENTS.md`，其他客户端用各自的说明或提示词）加上一段，例如：

```markdown
## 测试 CC: Tweaked 程序
本机装有 cct-run：C:\cct-run\bin\cct-run.bat
写完或修改 ComputerCraft 程序后，先用它运行检查，再交给我。
第一次使用前运行 `C:\cct-run\bin\cct-run.bat guide` 阅读使用说明。
```

或者也可以直接在对话里让 AI 执行 `cct-run guide`。

---

## 用法速查

```
cct-run run <程序.lua> [--fixture 文件] [--timeout 秒] [--out 目录] [--term 宽x高] [--basic|--advanced] [-- 程序参数...]
cct-run trace  <运行目录|latest> [--kind 类型1,类型2] [--ticks A-B] [--peripheral 名称] [--source 名称] [--offset N] [--limit N]
cct-run screen <运行目录|latest> [--tick N] [--source term|显示器名]
cct-run guide | version | help
cct-run mcp [--out 目录] [--live 游戏目录]   # MCP 服务器，由客户端启动
```

MCP 工具与命令的对应关系：

| 工具 | 对应命令 | 参数 |
|---|---|---|
| `cct_run` | `run` | `file`（路径）或 `code`（Lua 源码，可配 `name`）、`args`、`fixture`（JSON 对象）或 `fixture_file`、`timeout`、`term`、`computer`（`advanced`/`basic`） |
| `cct_trace` | `trace` | `run`（默认 `latest`）、`kind`（数组）、`ticks`、`peripheral`、`source`、`offset`、`limit` |
| `cct_screen` | `screen` | `run`、`tick`、`source` |
| `cct_guide` | `guide` | `section`（可选，1 到 7） |
| `cct_live_*`（14 个） | — | 按需接管**真实游戏里**的电脑，需要 cct-live mod（原型）：`computers`、`attach` / `detach`、`status`、`wait`、`screen`、`trace`、`type`、`click`、`files`、`lua`、`body`、`event`、`power`。接管时聊天栏有提示，有租约，玩家可以用 `/cctlive detach` 收回。用 `--live <游戏目录>` 或环境变量 `CCT_LIVE_DIR` 指定游戏目录。各工具的说明见 [`mod/README.md`](mod/README.md) |

工具返回的 JSON 和命令行输出相同。程序本身出错（`status: "error"`）是正常的测试结果；只有参数错误、文件不存在等情况，工具调用才会标记为出错。

- `--timeout` 是游戏时间（秒），默认 15，最大 3600。死循环程序以 `timeout` 结束是正常的。
- 每次运行的结果保存在 `.cct-runs\<运行编号>\` 下：`summary.json`（摘要）、`trace.jsonl`（全程记录）、程序和 fixture 的副本。`latest` 指最新的一次运行。
- 标准输出只有 JSON，而且只包含 ASCII 字符（非 ASCII 字符写成 `\uXXXX`），所以在任何控制台编码下都不会乱码。

| 退出码 | 含义 |
|---|---|
| 0 | 程序正常结束 |
| 1 | Lua 错误（语法错误、运行时错误、Too long without yielding、被 Ctrl+T 终止） |
| 2 | 超时，程序仍在运行 |
| 3 | 程序关机或重启了电脑 |
| 4 | 内存耗尽（超过约 1 GB 的上限） |
| 64 | 命令用法错误 |
| 65 | 输入有问题（文件不存在、fixture 格式错误等，错误信息会指出 JSON 中的具体位置） |
| 69 | 找不到 Java 17+ |
| 70 | 内部错误 |

需要更大的内存上限时，设置环境变量 `CCT_RUN_OPTS=-Xmx2g`（或 `JAVA_OPTS`）后再启动 cct-run；MCP 方式下在客户端配置的环境变量里设置。

### fixture 示例

```json
{
  "schema": "cct-run/fixture@1",
  "computer": { "label": "door", "advanced": true },
  "peripherals": {
    "top":  { "type": "monitor", "blocks": [3, 2] },
    "back": { "type": "wired_modem", "network": {
                "minecraft:chest_0": { "type": "inventory",
                  "items": { "1": { "name": "minecraft:iron_ingot", "count": 64 } } } } }
  },
  "redstone": { "front": 15 },
  "timeline": [
    { "tick": 20, "do": "redstone", "side": "left", "level": 15 },
    { "tick": 30, "do": "event", "name": "monitor_touch", "args": ["top", 3, 2] },
    { "tick": 80, "do": "terminate" }
  ]
}
```

完整写法见 `docs/agent-guide.md` 第 5 节，以及 `examples/` 中的两个 fixture。

---

## 还不能模拟的内容

测试通过表示程序**在这个模拟环境里**运行正确，不保证和游戏里完全一致。目前没有：

- 海龟（turtle）、口袋电脑、命令电脑；
- 真实世界（方块、区块加载、服务器卡顿）；
- HTTP / WebSocket、扬声器、打印机、磁盘驱动器、无线 / 末影调制解调器、接收调制解调器消息；
- 容器的格子限制：任何格子都能放任何物品，上限 64（真实的熔炉、模组方块有规则）；物品详情只有部分字段；
- 能量和流体只在 fixture 时间线里改变；游戏内时间固定为 0（`os.time()`、`os.day()`）；
- 磁盘容量只报告、不限制；内存上限约 1 GB（游戏里没有单台电脑的内存上限，电脑和服务器共用内存）；
- 只记录屏幕上的文字，不记录颜色；
- CC 无法显示中文，屏幕上看到的是 CC 自己的字符集。

完整的差异清单见 `handoff.md` 的 LIMITATIONS 和 DIVERGENCES 两节。

---

## 测试情况

| 平台 / 项目 | 结果 |
|---|---|
| Windows 11（PowerShell 5.1、cmd、代码页 936） | 冒烟测试 22/22：命令行、MCP 服务器（直接启动和 `cmd /c`）、内存上限、从 Java 8 自动切换、路径含空格和中文 |
| Linux | 单元测试 43/43；冒烟测试全部通过；官方 MCP SDK 客户端验证通过；Java 17 / 21 / 25 均可运行 |
| macOS | 尚未测试 |
| cct-live | Linux 上的 Forge 47.4.10 开发服务器和正式服务器；Windows 单人游戏（PCL2 启动器）三轮 AI 实测（MPlayer 安装脚本排错）。性能数据见 [`mod/README.md`](mod/README.md#性能) |
| AI 客户端 | 命令行方式：Autoclaw、DeepSeek Harness 实测可用；MCP 方式：一次模拟器实测四步全部正确，DeepSeek Harness 三轮实机实测可用。其他客户端的 MCP 接入结果待反馈 |

每轮测试发现的问题和处理见 [`docs/test-results.md`](docs/test-results.md)；在自己的机器上测试的方法见 `docs/testing.md`。

---

## 从源码构建

需要 JDK 17 或更高版本，构建时会下载依赖。

```
./gradlew build             # 编译并运行测试
./gradlew :cli:distZip      # 生成 cli/build/distributions/cct-run-<版本>.zip
./gradlew :cli:installDist  # 生成 cli/build/install/cct-run/，可以直接运行
```

CC: Tweaked 使用 Maven 上发布的 `cc.tweaked:cc-tweaked-1.20.1-core:1.120.2`（来自 https://maven.squiddev.cc），不需要自己编译 CC: Tweaked，也不需要下载 Minecraft。

项目结构、设计决定、已知问题见 `handoff.md`。

---

## 路线图

1. ~~命令行工具、全程记录、外设模拟~~（已完成）
2. ~~旧 Java 自动切换、给 AI 的使用说明~~（已完成）
3. ~~**MCP 服务器**：本地 stdio 方式，工具和命令一一对应，每次运行启动一个独立进程（不做远程 MCP）~~（0.1.0 测试版）
4. 资源限制：~~1 GB 内存上限、内存耗尽单独作为一种结果~~（已完成）；trace 超过上限截断、`cct-run clean` 清理旧记录（待做）
5. 按需添加更多外设（熔炉格子规则、扬声器、打印机、磁盘驱动器、无线调制解调器和多电脑 rednet 等）
6. ~~**cct-live**：一个 Forge 1.20.1 mod，让 AI 按需接管真实游戏里的 CC 电脑：接管后从 Java 层记录事件、外设调用、HTTP 和文件读写、带颜色的屏幕、显示器、红石，并允许输入、点击、读写文件、运行 Lua 和开关机；平时不记录；不需要在电脑里运行 Lua 脚本，也不需要转发服务器~~（0.1.0 原型，见 [`mod/README.md`](mod/README.md)）
7. cct-live 后续：程序出错时的调用栈、有线网络连接的显示器、海龟的背包和燃料、海龟和口袋电脑的测试；和 Skill 一起做联合测试（[`docs/joint-test.md`](docs/joint-test.md)）

配套的 AI Skill：[EvergardenRoxy/CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill)。

---

## 许可证

- 本项目代码：[MPL-2.0](LICENSE)
- `engine/src/main/resources/cctrun/harness.lua` 改编自 CC: Tweaked 的 `shell.lua`，使用 CCPL（ComputerCraft Public License）
- 发布包中包含 CC: Tweaked 及其依赖，它们各自的许可证见 `LICENSES/` 和 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)

本项目不是 CC: Tweaked 官方项目。CC: Tweaked 由 SquidDev 等人开发：https://github.com/cc-tweaked/CC-Tweaked
