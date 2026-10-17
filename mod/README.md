# cct-live：让 AI 按需接管游戏里的 CC: Tweaked 电脑（原型）

cct-live 是一个 Forge mod。装上以后，AI（通过 `cct-run mcp`）可以**按需接管**真实游戏里的 CC: Tweaked 电脑：

- 接管后，mod 在 Java 层记录这台电脑做的每一件事，格式和 cct-run 的 trace 相同；
- AI 可以向它输入文字、发送事件、开关机。

整个过程通过一个**只监听本机**的 HTTP 接口完成。

**按需**的意思是：没有被接管的电脑，mod 什么都不记录、不轮询，只在每个事件和每次被拦截的方法调用时多做一次判断。实测开销见下文“性能”。

和社区的远程桌面方案相比：

- 不需要在电脑里运行任何 Lua 脚本，不需要 `wget run`，不需要放开 CC 的 http 规则，也不需要转发服务器；
- 记录在 Java 层完成，被测程序无法隐藏或改写这些记录；
- 能看到 Lua 无法如实提供的内容：交给 VM 的每个事件、外设调用及其返回值、http 请求和下载到的内容、文件的读写、VM 错误、红石输出、带颜色的屏幕、开关机、海龟的位置变化。

> 这是**原型**，HTTP 接口的格式还可能改变。

## 版本

只支持 **Minecraft 1.20.1 + Forge 47.x + CC: Tweaked 1.120.2**。这个 mod 用到了 CC: Tweaked 的内部类，所以 `mods.toml` 把 CC: Tweaked 的版本锁定在 1.120.2，版本不对时 Forge 会拒绝加载。

## 构建

需要 JDK 17 或更高版本。第一次构建会下载 Minecraft 和 Forge 的开发环境（几百 MB），如果本机没有 Java 17，还会自动下载一个。

```
./gradlew -p mod build          # 在仓库根目录执行；Windows: gradlew.bat -p mod build
```

生成的文件：`mod/build/libs/cct-live-1.20.1-<版本>.jar`。

`mod/` 是独立的 Gradle 构建，根目录的 `./gradlew build`（cct-run 本体）不会构建它，也不会下载 Minecraft。

## 安装

1. 把 jar 放进游戏的 `mods/` 目录，和 CC: Tweaked 1.120.2 放在一起。单人游戏装在客户端即可；多人服务器装在服务端。
2. 进入世界。日志里应该出现：
   ```
   cct-live: on demand; recording mode off; attachments are written to <游戏目录>/cct-live/sessions/<时间>
   cct-live: endpoint at http://127.0.0.1:47823/ (token in cct-live/token.txt)
   ```
3. 世界打开期间有一个 `cct-live/endpoint.json`（接口地址），退出世界时删除；令牌在 `cct-live/token.txt`。`cct-run mcp` 靠这两个文件找到接口。

## 接管、解约和租约

| | 怎么发生 | 聊天栏提示 |
|---|---|---|
| **接管** | AI 调用 `cct_live_attach`（HTTP：`POST /computers/{id}/attach`） | `[cct-live] AI 已接管 0 号电脑（租约 1800 秒，输入 /cctlive detach 0 可收回）` |
| **AI 主动释放** | AI 调用 `cct_live_detach` | `[cct-live] AI 已释放 0 号电脑` |
| **玩家收回** | 在游戏里输入 `/cctlive detach <id>` | `[cct-live] 已收回 AI 对 0 号电脑的控制` |
| **租约到期** | AI 在租约时间内（默认 1800 秒）没有再请求这台电脑；每次请求都会续约 | `[cct-live] AI 对 0 号电脑的租约已到期，已自动释放` |
| **电脑被移除** | 方块被拆掉、区块卸载等 | `[cct-live] 0 号电脑已被移除，接管结束` |

- 只有**被接管**的电脑才能用输入和开关机工具（`event`、`type`、`power`），否则返回 409。
- 列出电脑、读当前屏幕不需要接管。
- `/cctlive list` 列出当前被 AI 接管的电脑和剩余租约。`/cctlive` 命令不需要 OP 权限，因为它只能把控制权从 AI 手里收回。
- 提示文字跟随游戏语言（自带 `zh_cn` 和 `en_us`）。在配置里设 `chat = false` 可以关闭聊天提示，但日志里仍会记录。

## 记录模式

默认关闭。关闭时，接管前发生的事都看不到，AI 要先接管、再让问题重现（比如用 `cct_live_type` 重新运行程序，或者重启电脑）。

打开后（`[recorder] enabled = true`），每台电脑在**内存里**保留最近 `size` 条记录（默认 256 条）。范围包括事件、外设调用、http 和文件读写，不包括屏幕。AI 接管时，这些记录会先写进 trace，并标上 `"history": true`，所以连“接管之前刚刚发生的事”也能看到。代价是每个事件和每次调用都要转换一次数据，具体见下面的性能数据。

## 配置：`config/cctlive-common.toml`

| 项 | 默认值 | 作用 |
|---|---|---|
| `http.enabled` / `http.port` | `true` / `47823` | 本机 HTTP 接口 |
| `trace.screens` | `true` | 接管期间是否每 tick 记录屏幕变化 |
| `trace.max_megabytes_per_computer` | `64` | 每台电脑的 trace 文件大小上限 |
| `trace.max_string` | `200` | 记录里每个字符串（文件和 HTTP 数据、事件参数等）保留的字符数（50 到 65536） |
| `recorder.enabled` / `recorder.size` | `false` / `256` | 记录模式，以及每台电脑保留的条数 |
| `control.lease_seconds` | `1800` | 租约时长（30 到 86400 秒） |
| `control.chat` | `true` | 是否在聊天栏提示接管和释放 |
| `files.allow_write` | `false` | **是否允许 AI 写入电脑上的文件**（新建、覆盖、追加）。默认关闭；打开后，已接管电脑的 AI 可以修改这台电脑上的任何文件（包括 `startup.lua`），每次写入都会在 trace 里留下 `file_write` 记录。读文件不受这个开关影响 |

配置在进入世界时读取，修改后要重新进入世界才会生效。已有的配置文件会保留原来的值：旧版生成的 `lease_seconds = 600` 不会自动改成 1800，需要手动修改，或者删掉配置文件让它重新生成。

## 通过 MCP 让 AI 使用

`cct-run mcp` 提供 12 个 `cct_live_*` 工具。每次工具返回的内容都会留在 AI 的上下文里，所以工具默认只返回很少的数据：日常检查用 `status`，等待用 `wait`，需要细节时再查 trace 或屏幕。

| 工具 | 作用 | 需要先接管 |
|---|---|---|
| `cct_live_computers` | 列出已加载的电脑，以及是否被接管 | 否 |
| `cct_live_attach` / `cct_live_detach` | 接管（可以用 `lease_seconds` 指定租约）/ 释放 | — |
| `cct_live_status` | 简短状态：tick、最新 seq、某个 seq 之后各类记录新增了多少条、最近一条 `printed_error`、屏幕上的文字 | 否 |
| `cct_live_wait` | **在服务端等待**，直到屏幕出现某段文字、出现某类记录（比如 `printed_error`），或者屏幕连续若干秒没有变化，最长 120 秒。AI 不用再反复轮询 | 否 |
| `cct_live_screen` | 电脑或显示器（`source`）当前的文字；`colors` 附带颜色，`regions` 列出颜色区域（按钮），`tick` 还原某一刻 | 否（`tick` 要有过接管记录） |
| `cct_live_trace` | 查询 trace：默认最多 50 条，不含 `screen` 记录；`summary: true` 只返回各类记录的数量 | 读取之前的记录时不需要 |
| `cct_live_type` | 输入文字，每行后面按一次回车，可以一次输入多行 | 是 |
| `cct_live_click` | 点击电脑屏幕（mouse_click + mouse_up），或触摸显示器（`source` 是显示器所在的面，发送 monitor_touch） | 是 |
| `cct_live_files` | 读电脑上的文件（list / stat / read）；如果 config 里打开了 `files.allow_write`，还可以写入（write） | 是 |
| `cct_live_event` | 发送任意事件 | 是 |
| `cct_live_power` | 开机、关机、重启 | 是 |

需要告诉 `cct-run mcp` 游戏目录在哪里（就是有 `mods/` 和 `cct-live/` 的那个目录；启动器开了版本隔离时，是版本文件夹）。有三种方式：

- 启动参数 `cct-run mcp --live <游戏目录>`；
- 环境变量 `CCT_LIVE_DIR`（目录名里有空格或特殊字符时推荐用这种）；
- AI 在调用时传 `game_dir`（同一次会话里，后面的调用会沿用上一次成功使用的值）。

Claude Code 的 `.mcp.json` 示例：

```json
{
  "mcpServers": {
    "cct-run": {
      "command": "cmd",
      "args": ["/c", "C:\\cct-run\\bin\\cct-run.bat", "mcp"],
      "env": { "CCT_LIVE_DIR": "C:\\PCL2\\.minecraft\\versions\\All of CC：Tweaked" }
    }
  }
}
```

## HTTP 接口

每个请求都要带 `Authorization: Bearer <cct-live/token.txt 的内容>`。

| 请求 | 作用 |
|---|---|
| `GET /computers` | 已加载的电脑：id、标签、类型、是否开机、维度、坐标、是否被接管、剩余租约；以及 `recording_mode`、`lease_seconds` |
| `GET /computers/{id}/screen?source=&colors=1&regions=1&tick=N` | 屏幕上的文字（去掉行尾空格）、光标、尺寸；`source` 是 `term`（默认）或显示器所在的面；`colors=1` 附带前景色和背景色；`regions=1` 附带颜色区域；`tick=N` 从 trace 还原那一刻的屏幕 |
| `GET /computers/{id}/status?after=&kinds=&source=` | 简短状态，适合轮询：tick、最新 seq、`after` 之后各类记录的数量、`after` 之后第一条属于 `kinds` 的记录（`match`）、屏幕文字和 `screen_hash`、显示器列表 |
| `GET /computers/{id}/files?op=list\|stat\|read&path=&offset=&length=` | 电脑上的文件（需要已接管）：`/` 下的文件，不包括 `/rom` 和磁盘 |
| `POST /computers/{id}/files` `{"path", "text", "append"?, "encoding"?}` | 写文件（需要已接管，并且 config 里 `files.allow_write = true`），会记一条 `file_write` |
| `POST /computers/{id}/attach` `{"lease_seconds"?}` | 接管；如果已经接管，则续约（或改成新的租约时长） |
| `POST /computers/{id}/detach` | 释放 |
| `GET /computers/{id}/trace?kind=&exclude=&after=&ticks=&summary=1&offset=&limit=` | 这台电脑本次游戏的 trace：`kind` / `exclude` 按类型筛选，`after` 只要 seq 大于 N 的记录，`ticks` 按 tick 范围筛选，`summary=1` 只返回各类记录的数量和范围（limit 默认 50，最大 5000） |
| `POST /computers/{id}/event` `{"name": "...", "args": [...]}` | 向电脑的事件队列加入一个事件（需要已接管） |
| `POST /computers/{id}/power` `{"action": "on"\|"shutdown"\|"reboot"}` | 开机、关机、重启（需要已接管） |

只要请求涉及一台已被接管的电脑，就会为它续约。

PowerShell 示例（在游戏目录里执行；PowerShell 5.1 的 `curl` 是 `Invoke-WebRequest` 的别名，要用 curl 时请写 `curl.exe`）：

```powershell
$t = (Get-Content cct-live\token.txt).Trim()
$h = @{ Authorization = "Bearer $t" }
$u = "http://127.0.0.1:47823"
Invoke-RestMethod -Headers $h "$u/computers"
Invoke-RestMethod -Headers $h -Method Post "$u/computers/0/attach"
Invoke-RestMethod -Headers $h "$u/computers/0/trace?kind=event,api,handle"
Invoke-RestMethod -Headers $h -Method Post "$u/computers/0/detach"
```

安全措施：

- 只监听 `127.0.0.1`，局域网无法访问；
- 每个请求都要带令牌；
- 只接受 Host 头为 `127.0.0.1`、`localhost` 或 `[::1]` 的请求，防止网页通过 DNS 重绑定访问；
- 控制必须先接管，接管时会在聊天栏公开提示，玩家可以随时收回。

## trace 记录了什么

每台电脑在每次游戏会话中写一个文件：`cct-live/sessions/<时间>/computer-<id>.jsonl`，多次接管都追加在里面（seq 连续编号，用 `attach` / `detach` 记录分隔）。格式与 cct-run 的 `trace.jsonl` 相同（共有字段 `seq`、`tick`、`ms`、`kind`），schema 为 `cct-live/trace@0`。`tick` 是世界打开以来的服务端 tick 数。

| kind | 内容 |
|---|---|
| `attach` / `detach` | 接管开始（租约、是否打开记录模式、带入了多少条历史记录）/ 结束（原因：`detach`、`revoked`、`expired`、`removed`、`closed`） |
| `event` | 交给 Lua VM 的每个事件（包括 timer、task_complete、按键、http_success 等） |
| `peripheral_call` / `peripheral_return` | 所有外设调用（`peripheral.wrap`/`find` 最终也走 `peripheral.call`）；主线程方法在之后的 tick 返回；出错时带错误信息 |
| `api` | `http.request` / `checkURL` / `websocket`（`http.get`、`http.post` 最终都调用 `http.request`），以及 `fs.open` / `delete` / `move` / `copy` / `makeDir`，含参数和结果 |
| `handle` | 对已打开的文件、HTTP 响应、websocket 的每次调用（`readAll`、`readLine`、`write`、`close` 等），带 `path` 或 `url`，读写的数据（默认前 200 个字符），以及 `bytes`（数据的完整长度）。`/rom` 下的文件不记录 |
| `printed_error` | 电脑用 `printError` 打印的文字，以及当时运行的程序。shell 就是这样报告程序崩溃的（例如 `/startup.lua:911: module 'icons.User' not found`），所以程序出错时先看这一类。由 mod 自带的 ROM 脚本 `rom/autorun/cctlive.lua` 提供 |
| `vm_start` / `vm_close` / `vm_error` | VM 的创建和关闭（开机、重启）；VM 级错误，例如 Too long without yielding |
| `screen` | 每个 tick 中变化的行，含 `fg` / `bg` 颜色、光标和尺寸；`source` 是 `term` 或直接贴在电脑上的显示器所在的面 |
| `monitor` | 显示器接上或拆掉 |
| `file_write` | AI 通过 `cct_live_files` 写入的文件（路径和字节数） |
| `redstone_output`、`power`、`label`、`moved` | 红石输出变化、开关机、标签变化、位置或维度变化（海龟移动） |
| `computer_seen` | 接管时这台电脑的信息 |
| `truncated` | 文件达到大小上限后停止写入 |

带 `"history": true` 的记录来自记录模式，是接管之前发生的事。

## 性能

在 Linux 的正式版 Forge 47.4.10 服务器（Java 17，4 核容器）上，放了 30 台高级电脑。每台运行 `mod/bench/startup.lua` 里三种不停循环的负载之一（各 10 台）：

- 频繁收发事件；
- 频繁读写文件；
- 频繁写屏幕。

每种配置跑两轮，每轮预热 20 秒后测 30 秒，下表是平均值。服务器 tick 耗时来自 `/forge tps`。

| 配置 | 服务器每 tick 耗时 | 事件循环/秒 | 文件循环/秒 | 写屏循环/秒 |
|---|---|---|---|---|
| 不装 mod | 1.01 ms | 8650 | 1250 | 33600 |
| 装了 mod，没有接管（平时的状态） | 0.94 ms | −1% | +8% | 0% |
| 打开记录模式 | 1.01 ms | −7% | −8% | −5% |
| 30 台全部接管 | 1.22 ms | −18% | −18% | −10% |

- 两轮“不装 mod”之间就相差约 5%，所以“没有接管”和“不装 mod”的差别**在测量误差之内**，测不出额外开销。
- 记录模式大约慢 5% 到 8%，接管大约慢 10% 到 18%。这是电脑一刻不停地跑循环的极端情况；普通程序大部分时间都在等待事件，实际影响会小得多。
- 服务器每 tick 耗时在所有配置下都只有 1 ms 左右（一个 tick 的预算是 50 ms）。
- 30 台全部接管时，trace 每分钟增长约 80 MB；每次接管的文件有大小上限（默认 64 MB）。

## 原型阶段的已知限制

- 程序出错时能拿到错误文字（`printed_error`），但还拿不到调用栈：shell 内部用来报告错误的模块是它私有的，从外面接不进去。
- 只能从接管那一刻开始记录，除非打开记录模式。文件或 HTTP 句柄如果是在接管之前打开的，trace 里不会显示它的路径或 URL。
- 没有 `output` 记录：滚出屏幕的行看不到。
- 只支持直接贴在电脑上的显示器，不支持通过有线网络连接的显示器；海龟的背包和燃料还没有接口。
- 海龟、口袋电脑和命令电脑都还没有测试过。
- 测试过的环境：Linux 上的 Forge 47.4.10 开发服务器和正式服务器，以及 Windows 单人游戏（PCL2 启动器，只测过接管功能加入之前的版本）。
