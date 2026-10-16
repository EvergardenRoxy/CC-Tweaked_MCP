# cct-live：在游戏里观察 CC: Tweaked 电脑（原型）

cct-live 是一个 Forge mod。装上以后，它在 Minecraft 服务端的 Java 层观察每一台 CC: Tweaked 电脑，把看到的内容写成和 cct-run 相同格式的 trace，并通过一个**只监听本机**的 HTTP 接口提供给 AI 工具。

和社区的远程桌面方案相比：

- 不需要在电脑里运行任何 Lua 脚本，不需要 `wget run`，不需要放开 CC 的 http 规则，也不需要转发服务器。
- 记录在 Java 层完成，被测程序无法隐藏或改写这些记录。
- 能看到 Lua 无法如实提供的内容：交给 VM 的每个事件、外设调用及其返回值、VM 错误、红石输出、带颜色的屏幕、开关机、海龟的位置变化。

> 这是**原型**，用来验证可行性。还没有和 `cct-run mcp` 接起来，HTTP 接口的格式也可能改变。

## 版本

只支持 **Minecraft 1.20.1 + Forge 47.x + CC: Tweaked 1.120.2**。这个 mod 用到了 CC: Tweaked 的内部类，所以 `mods.toml` 把 CC: Tweaked 的版本锁定在 1.120.2，版本不对时 Forge 会拒绝加载。

## 构建

需要 JDK 17 或更高版本。第一次构建会下载 Minecraft 和 Forge 的开发环境（几百 MB），如果本机没有 Java 17，还会自动下载一个。

```
./gradlew -p mod build          # 在仓库根目录执行；Windows: gradlew.bat -p mod build
```

生成的文件：`mod/build/libs/cct-live-1.20.1-<版本>.jar`。

`mod/` 是独立的 Gradle 构建，根目录的 `./gradlew build`（cct-run 本体）不会构建它，也不会下载 Minecraft。

## 安装与试用

1. 把 jar 放进游戏的 `mods/` 目录，和 CC: Tweaked 1.120.2 放在一起。单人游戏装在客户端即可；多人服务器装在服务端。
2. 进入世界。日志里应该出现：
   ```
   cct-live: recording CC: Tweaked computers to <游戏目录>/cct-live/sessions/<时间>
   cct-live: endpoint at http://127.0.0.1:47823/ (token in cct-live/token.txt)
   ```
3. 令牌在 `<游戏目录>/cct-live/token.txt`，每个请求都要带上。

PowerShell 示例（在游戏目录里执行；启动器开了版本隔离时，游戏目录是版本文件夹，例如 `.minecraft\versions\<版本名>`）：

```powershell
$t = (Get-Content cct-live\token.txt).Trim()
$h = @{ Authorization = "Bearer $t" }
$u = "http://127.0.0.1:47823"

Invoke-RestMethod -Headers $h "$u/computers"                              # 已加载的电脑
Invoke-RestMethod -Headers $h "$u/computers/0/screen"                     # 0 号电脑的屏幕
Invoke-RestMethod -Headers $h "$u/computers/0/trace?kind=event,peripheral_call,peripheral_return"
Invoke-RestMethod -Headers $h -Method Post -ContentType application/json `
  -Body '{"name":"char","args":["y"]}' "$u/computers/0/event"             # 向电脑发送一个事件
Invoke-RestMethod -Headers $h -Method Post -ContentType application/json `
  -Body '{"action":"reboot"}' "$u/computers/0/power"                      # on / shutdown / reboot
```

curl 示例（bash / macOS / Linux）：

```
curl -H "Authorization: Bearer $(cat cct-live/token.txt)" http://127.0.0.1:47823/computers
```

在 Windows PowerShell 5.1 里，`curl` 是 `Invoke-WebRequest` 的别名，不支持上面的写法，要写成 `curl.exe`：

```powershell
$t = (Get-Content cct-live\token.txt).Trim()
curl.exe -H "Authorization: Bearer $t" http://127.0.0.1:47823/computers
```

`/computers` 只列出已加载并且开过机的电脑。

## HTTP 接口

| 请求 | 作用 |
|---|---|
| `GET /computers` | 当前加载的电脑：id、标签、类型（normal/advanced/command）、是否开机、维度、坐标、对应的 trace 文件 |
| `GET /computers/{id}/screen` | 每一行的文字、前景色和背景色（与 `blit` 相同的 0-9a-f 字符）、光标位置、尺寸 |
| `GET /computers/{id}/trace?kind=a,b&offset=N&limit=N` | 本次会话的 trace 记录，可以按类型筛选（limit 默认 200，最大 5000） |
| `POST /computers/{id}/event` `{"name": "...", "args": [...]}` | 向电脑的事件队列加入一个事件，例如 `char`、`key`、`paste`、`terminate`、`monitor_touch` |
| `POST /computers/{id}/power` `{"action": "on"\|"shutdown"\|"reboot"}` | 开机、关机、重启 |

安全措施：

- 只监听 `127.0.0.1`，局域网无法访问；
- 每个请求都要带令牌；
- 只接受 Host 头为 `127.0.0.1` 或 `localhost` 的请求，防止网页通过 DNS 重绑定访问。

## trace 记录了什么

每台电脑在每次会话里有一个文件：`cct-live/sessions/<时间>/computer-<id>.jsonl`。格式与 cct-run 的 `trace.jsonl` 相同（共有字段 `seq`、`tick`、`ms`、`kind`），schema 为 `cct-live/trace@0`。`tick` 是会话开始以来的服务端 tick 数。

| kind | 内容 | 来源 |
|---|---|---|
| `event` | 交给 Lua VM 的每个事件（包括 timer、task_complete、按键等） | 替换 VM 工厂 `ServerContext.luaMachine` |
| `peripheral_call` / `peripheral_return` | 所有外设调用（`peripheral.wrap`/`find` 最终也走 `peripheral.call`）；主线程方法在之后的 tick 返回；出错时带错误信息 | 包装 VM 的方法查找 |
| `vm_start` / `vm_close` / `vm_error` | VM 的创建和关闭（开机、重启）；VM 级错误，例如 Too long without yielding | 同上 |
| `screen` | 每个 tick 中变化的行，含 `fg` / `bg` 颜色、光标和尺寸 | 每个服务端 tick 读取电脑终端 |
| `redstone_output` | 各个面的红石输出变化（含 bundled） | 每 tick 读取 |
| `power`、`label`、`moved` | 开关机、标签变化、位置或维度变化（海龟移动） | 每 tick 读取 |
| `computer_seen` / `computer_removed` | 电脑被加载或卸载 | 每 tick 读取 |
| `truncated` | 文件达到大小上限（默认 64 MB）后停止写入 | — |

配置文件是 `config/cctlive-common.toml`，可以设置端口、关闭 HTTP 接口、关闭屏幕记录和修改大小上限。

## 原型阶段的已知限制

- **还没有结构化的“程序结果”**。程序出错时，cct-live 能看到屏幕上的错误信息，但拿不到 cct-run 那样的 `status`、`error` 和 `traceback`。下一步计划把 cct-run 的 harness 放进 ROM，通过一个 Lua API 把结果交给 Java。
- 没有 `output` 记录：滚出屏幕的行看不到，因为终端的滚动发生在 CC 内部，没有对外的接入点。
- 显示器、海龟的背包和燃料、文件读写都还没有接口。
- 只读取电脑自己的终端。海龟（包括 `moved` 记录）、口袋电脑和命令电脑都还没有测试过。
- 只在 Linux 的 Forge 47.4.10 开发服务器和正式服务器上测试过，还没有在单人游戏客户端里实测。
