# 联合测试 3 记录：Create 工厂控制中心（第一次）

2026-10-17，维护者在 Windows 单人游戏里进行，提示词见 [`joint-test-factory.md`](joint-test-factory.md)（这次的版本：输入箱是创造板条箱，产物箱是物品保险库，玩家探测范围 32 格，要求交付并验收前不写 `startup.lua`）。资料来自会话记录（dsh `session.v4.jsonl`）、AI 的交接文档和它的工作目录。

## 测试条件

| 项目 | 内容 |
|---|---|
| 客户端 / 模型 | DeepSeek Harness（dsh），算力中心部署的开源 DeepSeek 模型；开着 PUA 插件 |
| 游戏 | Minecraft 1.20.1、Forge、CC: Tweaked 1.120.2、Create 6.0.8、Advanced Peripherals 0.7.48r |
| 被测电脑 | 7 号高级电脑。直接相连：显示器（bottom，50x26）、聊天框（back）、有线调制解调器（right）。有线网络上：转速控制器、应力表、转速表、创造板条箱、物品保险库、玩家探测器、聊天框 |
| 工具 | cct-run 0.1.0（19 个工具，含 `cct_live_snapshot` 和 trace 的 `contains`） |

## 用量

| 项目 | 本次 | 对照（MPlayer 测试） |
|---|---|---|
| 会话时长 | 117 分钟（模型 69 分 15 秒，工具 37 分 16 秒） | — |
| 总 token | **9293 万**（缓存读取 9086 万，命中 99%；未缓存输入 131 万） | 第 2 轮 1530 万，第 3 轮 1945 万 |
| 输出 token | 75.1 万（254 tok/s，首 token 平均 2.1 秒） | 第 2 轮 17.9 万，第 3 轮 25.1 万 |
| 模型步数 / 工具调用 | 559 步 / 576 次 | — |
| 上下文压缩 | **46 次**，其中 35 次在最后 15 分钟（101.9–116.6 分） | — |

主要工具调用次数：`pwsh` 83、`cct_run` 59（19 次出错）、`read` 57、`edit` 56、`write` 47、`cct_live_lua` 45、`cct_live_wait` 43、`cct_live_files` 27、`cct_live_event` 24、`cct_live_trace` 23、`cct_live_screen` 15、`cct_trace` 13、`cct_live_type` 13、`cct_live_status` 10、`cct_live_power` 7、`cct_live_click` 6、`cct_guide` 5、`cct_live_snapshot` 1。

token 主要不是花在单个工具结果上：cct-run 各工具的结果加起来约 25 万字符，AI 读自己的源文件约 34 万字符。大头是 559 步、每步都把整个上下文重发一次，平均每步约 16.6 万 token，几乎全是缓存读取。

## 按“需要观察”逐项

| 项目 | 结果 |
|---|---|
| 写代码前先在实机上确认外设 | **是**。前 18 分钟用 `cct_live_lua` 查了外设名、类型、方法，测了暂停的效果，并逐级调高转速测出应力曲线（-128 → 71.9%，-160 → 89.8%，-176 → 98.8%，-192 时动力停转），据此把最高转速定为 160 |
| 有没有编造 API | 没有发现。`getOperationCooldown` 必须带参数（`"chatMessage"`）、显示器不能显示中文，都是在实机上发现的 |
| 先对齐需求、给出默认假设 | 部分：两条需求在当前环境下做不到（显示器不能显示中文、创造板条箱永远不会空），AI 指出了，并改为纯 ASCII 看板 |
| 并行结构 | 一个事件循环，加上定时调度。第一版的定时器在外设调用期间丢失，导致看板冻结，后来改好 |
| 模拟器测了什么 | 逻辑单元测试 68 项、11 个模块的语法检查、触摸按钮 4 项（timeline 注入 `monitor_touch`）、外设离线（模拟器里没有 Create/AP 外设，等于这些外设都离线）、日志轮换 |
| 实机测了什么 | 看板每秒刷新、三个按钮、`!工厂 状态` 的回复（`sendMessageToPlayer`） |
| 是否请玩家配合 | **没有**。聊天指令全部用 `cct_live_event` 注入自己构造的 `chat` 事件来测（17 次），从没请玩家在聊天栏里输入；过载和缺料也没请玩家配合，只是在最后列为待选项 |
| 是否恢复电脑和工厂、释放控制 | 没有完成：开始时用 `cct_live_snapshot` 保存了快照，但电脑在收尾前被收回，工厂还在 -160 RPM（测试前 -128），诊断文件 `/event_logger.lua`、`/evt.log` 还在 |
| AI 报告的工具问题 | 见下面“核对 AI 的说法” |

**关于聊天指令的验证**：AI 交接时写“聊天指令在实机上确认可用”，依据是它注入的事件 `chat ["Evergarden_Roxy","!factory start","u12",false,"!factory start"]`。这条事件的参数排列是 AI 自己写的，所以只能证明程序能读懂它自己构造的事件，**不能证明能读懂 Advanced Peripherals 真正发出的 `chat` 事件**（参数个数、`isHidden` 的位置、中文消息的编码）。真实聊天还没有测过，需要玩家在聊天栏里输入一次。

## 时间花在哪里

按会话时间（分钟）。“原因”一列区分工具问题、客户端环境和模型自身的判断。

| 时间 | 卡住的事 | 原因 | 处理 |
|---|---|---|---|
| 11.3–12.3 | Windows 上 shell 权限配置失败 | 客户端环境 | AI 用 dsh 的 skill 修复 |
| 23.5–23.9 | PowerShell 5.1 写 fixture JSON 的编码出错 3 次 | 客户端环境（PS 5.1 的 `-Encoding`） | 改用 Python 生成 fixture |
| **27.4–35.9** | 16 次 `cct_run` 返回 `internal error: java.nio.charset.MalformedInputException`。AI 先怀疑 CRLF，又以为模拟器“放不了自定义类型的外设”，做了 14 个隔离用的 fixture（`iso_a`–`iso_h`、`as_*`），最后才在指南里找到规则：顶层的键必须是方向名 | **cct-run 的 bug**：MCP 服务器按严格 UTF-8 读子进程的 stderr，但中文 Windows 上 Java 用 GBK 写 stderr；fixture 出错时报错信息里有中文路径（`D:\AI\test\测试\...`），本该显示的校验错误就变成了这个异常 | **已修复** |
| 40.5–47.6 | 模拟器里看板只渲染一次，定时器不再触发。中途得出错误结论“模拟器的 timer ID 是假的”（其实是它自己的探针只接住了 `os.pullEvent()` 的第一个返回值），随后自行纠正 | 程序 bug + 模型误判 | 改成单一定时器，看板能渲染了，但没有找到根本原因 |
| 49.8–55.8 | 往游戏目录直接写文件需要用户批准 | 客户端沙箱 | 用户批准了一次 |
| **57.2–75.4** | 实机上同一个症状：看板渲染一次后冻结。约 18 分钟、十几个探针，中途还得出了“`os.clock()` 不是真实时间”的结论 | CC 的正常行为：外设的主线程方法在等待 `task_complete` 期间到达的事件会被丢掉。**模拟器能复现**（见下文第 3 条），但 trace 仍把这些事件记为已送达，所以 AI 在模拟器里没认出来 | 改成“做完阻塞操作再设定时器、用 `os.epoch` 判断是否到期”后解决 |
| **83.4–110.5** | 聊天指令不响应，约 27 分钟。88.0 分钟时 AI 把正确的 `ev[5]` 改成了 `ev[4]`：它把 trace 里事件的 `args` 当成了 `{os.pullEvent()}`，而 `args` 不含事件名，下标差 1。之后：重启后用 `cct_live_wait` 等 `>`，匹配到的是旧屏幕上的提示符，新程序其实没启动；99.3 分钟 trace 达到 64 MB 上限后停止记录；最后写了一个独立的事件记录程序，在 109.7 分钟改回 `ev[5]` | 模型误判为主。工具有三处让它更难查：`args` 和 Lua 下标的对应关系没写进指南；`wait` 会匹配操作之前就在屏幕上的文字；trace 达到上限后不再记录 | **已改进** |
| 101.9–116.6 | 35 次上下文压缩，其间几次“先做一个决定性检查再汇报” | 客户端的上下文预算 | — |

**关于模型能力**：维护者的判断成立，模型确实在简单问题上反复碰壁（timer ID、`os.clock`、`isHidden` 下标来回改、把编码异常归因于 CRLF）。46 次压缩会把已经确认的事实压掉，同一个问题被重新研究。但 27–36 分钟的卡顿完全是工具 bug，57–75 分钟的问题本来可以在模拟器里发现，这两段换成更强的模型也会遇到。

## 核对 AI 的说法

用它留下的 fixture 和 CC: Tweaked 1.120.2 的源码在 Linux 上逐条核对：

1. **“fixture 里有 Create/AP 外设就会让 cct-run 内部出错（MalformedInputException）”：不对。** 命令行直接运行这些 fixture，得到的是正常的校验错误（退出码 65），例如 `$.peripherals.Create_Stressometer_0: not a side` 和 `...network["Create_Stressometer_0"].type: unknown peripheral type "Create_Stressometer"`。用 `-Dstderr.encoding=GBK` 模拟中文 Windows、把 fixture 放在中文路径下，经 MCP 调用就能复现那个异常：是 MCP 服务器把错误信息弄丢了。
2. **“CRLF 或多余的字节也会触发同样的错误”：不对。** 只要 fixture 校验失败、路径里有中文，就会出这个错。
3. **“外设调用阻塞期间到期的定时器会被丢掉”：对**，这是 CC: Tweaked 本身的行为，模拟器里一样。测试程序：设一个 0.05 秒的定时器，接着调用 6 次 `chest.list()`，再等这个定时器。模拟器里的结果是 `timer LOST`，trace 里却有这条 timer 事件。
4. **“`os.clock()` 不是真实时间”：只对一半。** CC: Tweaked 的 `os.clock()` 是 `clock * 0.05`，`clock` 每个 tick 加一。所以它以 0.05 秒为单位前进，在两次 yield 之间的一段 Lua 里不变（忙等时读数为 0），服务器卡顿时会变慢。这在模拟器和游戏里一样，不是 7 号电脑特有的。量时间间隔应该用 `os.epoch("utc")`。
5. **“`cct_live_trace` 的 `contains` 不搜索 `method` 字段”：对**，并且有一个更大的问题：有线网络上的外设，程序实际调用的是调制解调器的 `callRemote`，trace 里记成 `peripheral: "right", method: "callRemote"`，真正的外设名和方法名在参数里。所以按 `setTargetSpeed`（网络上的转速控制器）能搜到，按 `sendMessageToPlayer`（直接贴在 back 的聊天框）搜不到。

## 根据这次测试做的改动（D47）

维护者认可了下面这些改进，都已实现，并在 Linux 开发服务器上验证（电脑 + 直连箱子 + 全尺寸有线调制解调器 + 网络上的箱子 + 显示器，详见 `handoff.md` V22）：

| 改动 | 内容 |
|---|---|
| MCP 读子进程输出 | 子进程固定用 UTF-8 写 stderr；读回时遇到无法解码的字节用替换字符代替，不再抛异常。修复后同样的条件返回 `cct-run run failed with exit code 65 (bad input). ... not a side` |
| `cct_live_peripheral`（新工具） | 在游戏这一侧直接调用外设的读取方法（`get...`、`is...`、`has...`、`list`、`size` 等），不经过电脑的 Lua：程序运行时也能用，不产生事件，不影响程序。不给方法名时列出外设的类型和方法。修改世界的方法（如 `pushItems`、`setTargetSpeed`）会被拒绝。记一条 `peripheral_query` |
| 网络外设的记录 | `callRemote` 记成真正的外设名和方法（和模拟器一致），`via` 是调制解调器所在的面 |
| `cct_live_trace` 过滤 | 新增 `peripheral`、`method`（精确匹配）；`contains` 也搜索事件名、外设名和方法名 |
| trace 大小上限 | 不再停止记录，而是保留最新的记录：分成两个各占一半的文件，新的写满时丢掉旧的，留下 `trace_rotated` 记录；`status` 和 `trace` 的结果带 `first_seq`（最早还保留的一条）；轮换后每个屏幕完整记录一次，按 tick 还原画面仍然可用 |
| `cct_live_wait` 的 `text` | 只认上一次 `cct_live_type`/`click`/`event`/`power` 之后变化过的屏幕：操作之前就在屏幕上的提示符或旧文字不算；超时时附带说明；`unchanged: true` 可以关掉这一点 |
| 指南 | trace 事件的 `args` 从事件名之后算起（`args[1]` 是 `{os.pullEvent()}` 的第 2 项）；外设主线程方法、`sleep`、带过滤的 `pullEvent` 期间到达的事件会被丢掉，trace 照样记录；`os.clock()` 的含义 |

## 待决定：fixture 里模拟模组外设

维护者的疑问是模组那么多、怎么兼容。建议的做法是**不为任何一个模组写代码**，而是提供一种通用的“脚本外设”，内容由 AI 从实机上取得：

- fixture 里声明一个外设：类型名（如 `Create_Stressometer`）和方法表，每个方法给出返回值（固定值、按顺序轮流的值，或由 timeline 在某个 tick 修改）。cct-run 只负责按名字返回这些值，并像普通外设一样记录调用。
- 返回值从实机取得：先用 `cct_live_peripheral` 列出方法、读出真实的返回值（这一步程序运行时也能做），或者从 trace 的 `peripheral_return` 里拿，再写进 fixture。以后可以加一个工具，把一段实机 trace 直接转成 fixture。
- 这样模组的数量不影响 cct-run：兼容的工作量是零，模拟的准确度等于 AI 采到的数据。
- 做不到的部分要在测试结论里写明：外设之间的联动（调了 `setTargetSpeed` 后 `getStress` 跟着变）只能靠 timeline 预先排好，或者以后允许在 fixture 里用几行 Lua 描述；模组外设自己发出的事件（如聊天框的 `chat`）仍然用 timeline 的 `event` 注入，参数排列必须来自实机，否则就会重演这次的问题（测的是 AI 自己构造的事件）。

这次的工厂程序有了它，就能在模拟器里测过载保护、缺料暂停（timeline 把物品数改成 0）和通知去重，而这几项在实机上都没测到。

## 社区建议的核对

| 建议 | 核对结果 |
|---|---|
| 用盲文字符拼像素点，做高质量进度条 | **思路对，字符不对。** CC 的终端和显示器是 8 位字符集，不认 Unicode：UTF-8 的盲文字符（3 个字节）会显示成 3 个乱码，和这次测试里中文在显示器上的表现一样。CC 自己的做法是字符 128–159（`\128`–`\159`）：每个字符是一个 2×3 的小方块图案，配合前景色、背景色就能画出比字符细一倍的进度条和图形。这属于 Skill 的内容 |
| CC 有 local 上限，建议在根作用域用首字母大写的全局变量 / 函数 | **上限是真的，办法不推荐。** 在模拟器里验证：一个函数（包括文件的主代码块）里最多 200 个 local，第 201 个时编译报错 `main function has more than 200 local variables`。cct-run 会在第一次运行时就报出来。但改成全局变量有副作用：打开 `bios.strict_globals` 设置后会直接报错（模拟器验证：`Attempt to create global Counter`），而且全局变量更慢、更容易被别的代码改掉。更好的做法是把函数放进模块表（`local M = {}`，`function M.f() end`，模拟器里 500 个函数没问题）并拆成多个文件用 `require`，这次的工厂程序就是这样拆成 11 个模块的。可以写进 Skill |
| 模型把 CC 和 CC:T 的资料混在一起，把旧环境的代码写到新环境 | **这次的会话不支持这个说法。** 这次的错误（定时器丢失、`os.clock`、事件参数下标、`fs.move` 不能移到同前缀的路径、`logger[level](...)` 丢了 `self`、语法错误）在 CC 和 CC:T 里表现完全一样，和版本无关。真正和版本有关的差异出在模组上（Advanced Peripherals 0.7 的 `getOperationCooldown` 必须带参数），AI 是在实机上发现的。另外模拟器运行的就是 CC: Tweaked 1.120.2 本身，旧 API 写错了在第一次运行时就会报错，不会留到实机 |
