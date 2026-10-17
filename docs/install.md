# 安装

[← 返回 README](../README.md) · [用法速查](usage.md) · [从源码构建](building.md)

## 1. 准备 Java 17 或更高版本

玩 Minecraft 1.20.1 的电脑一般都已经装了。检查方法：

```
java -version
```

- 如果系统默认的是旧版 Java（例如 Java 8），也没关系：cct-run 会自动找本机已安装的 Java 17+ 并换用它，查找顺序是 `JAVA_HOME`、`PATH`，最后是常见的 JDK 安装目录。
- 如果一个 Java 17+ 都找不到，cct-run 会以退出码 69 结束，并提示如何设置 `JAVA_HOME`。可以安装任意一个发行版，例如 [Adoptium Temurin](https://adoptium.net/) 或 [Zulu](https://www.azul.com/downloads/)。

## 2. 下载并解压

从 [Releases](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/releases) 下载发布包 `cct-run-<版本>.zip` 并解压，例如解压到 `C:\cct-run`。也可以按[从源码构建](building.md)自己构建。目录结构：

```
cct-run\
  bin\cct-run.bat   bin\cct-run      启动脚本（Windows / macOS、Linux）
  lib\*.jar                          程序本体，含 CC: Tweaked
  examples\                          示例程序和 fixture
  README.md                          项目说明
  docs\install.md  usage.md  building.md   安装（本文件）、用法速查、从源码构建
  docs\images\                       README 里的图
  docs\agent-guide.md                给 AI 的使用说明
  docs\testing.md  scripts\          维护者测试脚本
  LICENSE  LICENSES\  THIRD-PARTY-NOTICES.md
```

不需要管理员权限，不写注册表，不需要安装 Minecraft 或其他模组。

## 3. 确认可以运行

```
# Windows（PowerShell 或 cmd）
bin\cct-run.bat version

# macOS / Linux
bin/cct-run version
```

能输出带有 `"cc_tweaked": "1.120.2"` 的 JSON 就说明装好了。

## 4. 接入 AI 客户端（MCP，推荐）

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

**（可选）接管游戏里的电脑**：先按 [`mod/README.md`](../mod/README.md) 安装 cct-live mod，再在 MCP 配置里用环境变量 `CCT_LIVE_DIR` 告诉 cct-run 游戏目录（有 `mods/` 的那个文件夹；启动器开了版本隔离时是版本文件夹）。也可以在参数里加 `--live <游戏目录>`。例如：

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

## 4（另一种）. 用命令行方式接入

在 AI 客户端的项目说明文件里（Claude Code 用 `CLAUDE.md`，Codex 用 `AGENTS.md`，其他客户端用各自的说明或提示词）加上一段，例如：

```markdown
## 测试 CC: Tweaked 程序
本机装有 cct-run：C:\cct-run\bin\cct-run.bat
写完或修改 ComputerCraft 程序后，先用它运行检查，再交给我。
第一次使用前运行 `C:\cct-run\bin\cct-run.bat guide` 阅读使用说明。
```

或者也可以直接在对话里让 AI 执行 `cct-run guide`。

装好后的常用命令和参数见[用法速查](usage.md)。
