# cct-run 0.1.0（首个测试版）

cct-run 让 AI 能**实际运行和调试** CC: Tweaked 程序，有两种方式：

- **模拟器**：不用打开 Minecraft，直接在真实的 CC: Tweaked 1.120.2 代码上运行程序，返回结构化结果和完整记录（trace）。
- **cct-live mod（原型）**：AI 在玩家同意后，接管正在运行的游戏里的真实电脑，查看它的运行情况，也可以操作它。不需要在电脑里运行 Lua 脚本，也不需要转发服务器。

这两种方式都通过同一个本地 MCP 服务器（`cct-run mcp`，共 20 个工具）提供给 AI。配套的 AI Skill 是 [CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill)，它告诉 AI 什么时候该测试、怎么测试。

## 下载

| 文件 | 说明 |
|---|---|
| `cct-run-0.1.0.zip` | 命令行工具和 MCP 服务器，解压即可用（Windows / Linux） |
| `cct-live-1.20.1-0.1.0.jar` | Forge 1.20.1 mod，放进游戏的 `mods/` 文件夹。可选，只有需要实机功能时才装 |

## 系统要求

- **cct-run**：Java 17 或更高版本。如果系统默认的是旧版 Java（例如 Java 8），cct-run 会自动找本机已安装的 Java 17+ 并换用它；一个都找不到时，以退出码 69 结束并提示怎么设置 `JAVA_HOME`。
- **cct-live**：Minecraft 1.20.1，Forge 47.x（测试版本是 47.4.10），**CC: Tweaked 1.120.2**（版本已锁定，其他版本的 CC: Tweaked 不能加载这个 mod）。单人游戏和专用服务器都可以用。

## 主要功能

### 模拟器（命令行和 MCP）

- `cct-run run`：运行程序，返回 `status`（`ok` / `error` / `timeout` / `shutdown` / `reboot` / `out_of_memory`）、带 `文件:行号` 的错误和调用栈、电脑屏幕和显示器的最终画面，以及失败的外设调用。
- 用 **fixture** 模拟程序需要的外设和环境：箱子、显示器、能量和流体存储、有线网络、红石输入、预置文件，以及按 tick 注入的事件（按键、显示器触摸、红石变化、`terminate`）。
- `cct-run trace` / `cct-run screen`：按类型或 tick 范围查询记录，还原任意一刻的画面。
- `cct-run guide`：给 AI 的使用说明，可以按章节读取（`section`），避免长文本被客户端截断。
- 输出都是 JSON；退出码：0 正常结束，1 Lua 错误，2 超时，3 关机或重启，4 内存耗尽，64 / 65 参数或输入有误，69 找不到 Java 17+，70 内部错误。
- 内存上限 1 GB，内存耗尽单独作为一种结果返回。

### MCP 服务器（`cct-run mcp`）

- 本地 stdio 方式，每次运行启动一个独立的进程。
- 模拟器工具 4 个：`cct_run`、`cct_trace`、`cct_screen`、`cct_guide`。
- 实机工具 16 个：`cct_live_computers`、`cct_live_attach`、`cct_live_detach`、`cct_live_status`、`cct_live_wait`、`cct_live_screen`、`cct_live_trace`、`cct_live_type`、`cct_live_click`、`cct_live_files`、`cct_live_lua`、`cct_live_body`、`cct_live_snapshot`、`cct_live_peripheral`、`cct_live_event`、`cct_live_power`。
- 用 `--live <游戏目录>` 或环境变量 `CCT_LIVE_DIR` 指定游戏目录。启动器开了版本隔离时，填版本文件夹。

### cct-live mod（原型）

- **按需记录**：平时不记录任何东西。AI 接管某台电脑后，才从 Java 层记录它的事件、外设调用、HTTP 和文件读写（`api` / `handle`，带数据的完整长度）、`printError` 打印的错误（`printed_error`）、带颜色的屏幕、直接贴在电脑上的显示器、红石输出、开关机等。
- **接管、解约和租约**：
  - 接管和释放时，聊天栏会提示（中文和英文）。
  - 玩家随时可以用 `/cctlive detach <id>` 收回控制权，用 `/cctlive list` 查看接管情况。
  - 租约默认 1800 秒，AI 的每次请求都会续约，超时自动释放。
- **记录模式**（可选）：在内存里保留每台电脑最近的一小段记录，AI 接管时能看到接管之前发生了什么。
- **省 token 的工具**：
  - `cct_live_status`：返回很小的状态摘要。
  - `cct_live_wait`：在服务端等待某段文字、某类记录出现，或者画面停止变化，最长 120 秒；等文字时不会被操作之前就在屏幕上的旧文字骗到。
  - `cct_live_trace`：默认只返回 50 条，`summary` 只返回各类记录的数量，`contains` 按 URL、路径或文字过滤（比如只看某一个文件的下载），`peripheral` / `method` 只看某个外设或方法的调用。trace 超过大小上限后保留最新的记录。
  - `cct_live_peripheral`：程序运行时也能直接读取外设（应力表、箱子等），不经过电脑的 Lua，不影响程序。
  - `cct_live_files` 可以只读地查看 `/rom`（这台电脑实际运行的 ROM）。
- **模拟器**：fixture 新增 `scripted` 外设，用来描述 Create、Advanced Peripherals 等模组的外设（类型名和每个方法的返回值，数值从实机上取）；`cct_trace` 新增 `contains` 文字过滤。
  - `cct_live_lua`：一次调用就能在电脑上运行一段 Lua 并拿到结果，临时文件自动删除；超时后可以用 `result` 取回结果。
  - `cct_live_body`：读取 HTTP 响应的完整正文，每个最多 4 MB。
- **快照和恢复**：`cct_live_snapshot` 在改动之前保存电脑的全部文件，结束时列出改动了什么，并能一键恢复原样（恢复需要打开 `files.allow_write`）。快照保存在 `cct-live/snapshots/` 下。
- **图形界面**：`cct_live_screen` 的 `regions` 会列出颜色区域和里面的按钮标签（`labels`），便于点击按钮；查看和点击显示器时加 `source`。
- **本机 HTTP 接口**：只监听 `127.0.0.1:47823`，需要 token（`cct-live/token.txt`），并检查 Host 头；所有操作都在服务器线程上执行。

## 安装和升级说明

1. 解压 `cct-run-0.1.0.zip`，然后运行 `bin\cct-run.bat version`（macOS / Linux 上运行 `bin/cct-run version`）确认能用。
2. 在 AI 客户端里添加 MCP 服务器，命令是 `cct-run mcp`。各客户端的写法见 README 的“接入 AI 客户端”一节。
3. 需要实机功能时：
   - 把 jar 放进 `mods/`，进入世界一次，`config/cctlive-common.toml` 会自动生成；
   - 在 MCP 配置里设置 `CCT_LIVE_DIR`。

**config 里需要注意的两项：**

- `files.allow_write` 默认是 **`false`**，打开后 AI 才能写电脑上的文件，`cct_live_lua` 也需要它。打开后，已接管电脑的 AI 可以修改这台电脑上的任何文件（包括 `startup.lua`）；每次写入都会在 trace 里留下 `file_write` 记录。
- `control.lease_seconds`：用过早期原型的话，旧的 config 文件里可能还是 `600`。现在的默认值是 `1800`，可以手动改掉。

## 安全

- HTTP 接口只监听本机地址，需要 token，并检查 Host 头，网页不能跨站访问它。
- 对电脑的所有控制操作（输入、点击、事件、开关机、写文件）都要求先接管；接管会在聊天栏公开提示，玩家随时可以收回。
- 写文件默认关闭，必须由玩家在 config 里打开。

## 测试情况

- **单元测试**：全部通过。
- **Windows 11**：冒烟测试 22/22 通过（PowerShell、cmd、代码页 936、从 Java 8 自动切换、路径含空格和中文）。
- **cct-live**：在 Linux 上的 Forge 47.4.10 开发服务器和正式服务器上测试过，也在 Windows 单人游戏（PCL2 启动器）里做过四轮 AI 实测（用 MPlayer 安装脚本做排错，每轮都找到了同一个根因）。各轮结果见 `docs/test-results.md`。
- **性能**（30 台电脑一刻不停地跑循环的极端情况）：
  - 没有接管时，和不装 mod 的差别在测量误差之内；
  - 记录模式大约慢 5% 到 8%；
  - 全部接管大约慢 10% 到 18%；
  - 服务器每 tick 耗时始终在 1.2 ms 左右或更低。

  详见 `mod/README.md`。

## 已知限制

- macOS 还没有测试过。
- 模拟器不支持：海龟、口袋电脑、命令电脑；HTTP / WebSocket；扬声器、打印机、磁盘驱动器、无线调制解调器；真实世界（方块、区块加载、卡顿）；游戏内时间（固定为 0）；容器的格子规则。完整清单见 README 的“还不能模拟的内容”。
- cct-live：
  - 程序出错时能拿到错误文字，但拿不到调用栈；
  - 不支持通过有线网络连接的显示器；
  - 海龟、口袋电脑和命令电脑还没有测试过；
  - `cct_live_lua` 要从 shell 运行，程序运行时不能用（程序运行时可以用 `cct_live_peripheral` 读取外设）；
  - `cct_live_peripheral`、trace 的 `peripheral` / `method` 和轮换、`cct_live_wait` 对旧画面的处理、`/rom` 只读、fixture 的 `scripted` 外设和 `cct_trace` 的 `contains` 是本版最后加入的，只在开发服务器和模拟器里验证过，还没有经过 AI 实测。
- 接管之前就打开的文件或 HTTP 句柄，trace 里不会显示它的路径或 URL（打开记录模式可以缓解）。

## 许可证

- 本项目代码：MPL-2.0；
- `harness.lua`：CCPL；
- 附带的 CC: Tweaked 及其依赖：使用各自的许可证，见发布包里的 `LICENSES/` 和 `THIRD-PARTY-NOTICES.md`。
