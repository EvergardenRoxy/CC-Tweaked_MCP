# cct-run 测试记录

截至 2026-10-17。机器审查用的完整记录在 `handoff.md` 的 VERIFICATION 一节（V1–V13），这里是给人看的汇总。

## 当前结论

| 项目 | 结果 |
|---|---|
| Windows 冒烟测试 | **22/22 通过**（Windows 11、PowerShell 5.1、代码页 936、Zulu 21） |
| Linux 冒烟测试 | bash 21/21；PowerShell 7 20 项通过、1 项跳过（Linux 上没有可用的 Java 8） |
| 单元测试 | 53/53 |
| MCP 服务器 | Windows 冒烟测试和一次 AI 客户端实测通过；Linux 上官方 MCP SDK 客户端验证通过 |
| AI 实测 | 命令行方式和 MCP 方式的标准任务，AI 回答全部正确 |
| macOS | 未测试 |

## Windows 各轮测试（维护者实测）

| 轮次 | 测试包 | 结果 | 发现的问题 → 处理 |
|---|---|---|---|
| 1 | 7f2c026 | 16/16 | ① 非 UTF-8 控制台（代码页 936）下中文/特殊字符乱码 → 输出改为纯 ASCII（W1）<br>② 游戏刻只有约 16 刻/秒（Windows 计时精度）→ 改用绝对时间节拍（W2）<br>③ AI 把 cct-run 自己产生的设置事件当成程序事件，并把参数读反 → 这类事件加 `harness: true` 标记（W3） |
| 2 | a913616 | 17/17 | W1–W3 全部确认修好：编码检查通过，长运行 19.3–19.8 刻/秒。短运行速率偏低是启动预热 → 不足 2 秒的运行不再报告速率 |
| 3 | e98abe5 | 19/19 | 用 Java 8 启动时自动换用本机 Java 17+（实测 Zulu 8 → Zulu 25）。发现 Java 25 下依赖库 Guava 在 stderr 打印 `sun.misc.Unsafe` 警告（**未处理**，不影响结果） |
| 4 | 732e355 | 22/22 | 1 GB 内存上限：耗尽内存的程序 0.6 秒后以 `out_of_memory`（退出码 4）结束；MCP 服务器直接启动和通过 `cmd /c` 启动都正常 |

各轮都确认了：`.bat` 正确传递带空格的参数和 `--`；路径含空格和中文可用；cmd.exe 可直接调用；单次运行约 0.4–0.8 秒。

## AI 客户端实测

| 方式 | 客户端 | 结果 |
|---|---|---|
| 命令行 | Autoclaw、DeepSeek Harness（第 1、2 轮） | 能执行命令并正确读出状态、错误信息和行号 |
| 命令行 | 第 4 轮（客户端未注明） | 全部正确，并正确识别出 2 条事件都是 cct-run 自身产生的 |
| MCP | 第 4 轮（客户端未注明） | 先读说明，再运行文件、查询 trace、直接运行代码，全部正确；提示中的路径不存在时，AI 自行改用了实际安装目录 |
| MCP | Claude Code、Codex、DeepSeek Harness、Autoclaw 分别的结果 | **待反馈**（`docs/testing.md` 第 4b 步） |

## cct-live 实机 AI 测试（MPlayer 安装脚本）

都在维护者的 Windows 单人游戏（PCL2 启动器）里进行，提示词见 [`live-test-mplayer.md`](live-test-mplayer.md)。

| 轮次 | 电脑 | token | 结论 | 根据报告做的改进 |
|---|---|---|---|---|
| 1 | 2 | 未记录 | 找到根因：`git.liulikeji.cn` 的 WAF 对 URL 里带 `user` 的请求返回 4130 字节的滑动验证网页（状态码 200），安装器把它写成了 `icons/User.lua` | `printed_error`、读文件、颜色区域、分节读说明 |
| 2 | — | 1530 万（DeepSeek Harness） | 结论同上 | `status`、`wait`、更紧凑的输出、显示器、写文件（默认关闭） |
| 3 | 4 | 1945 万（deepseek-v4.1-flash） | 结论同上，并做了对照实验、恢复了电脑 | `cct_live_lua`、`cct_live_body`、区域里的 `labels` |
| 4 | 5 | 未记录 | 结论同上，并用 `loadfile()` 确认 47 个文件里只有这 1 个坏；另外找到两个问题：引导 Basalt 时 `load(..., _G)` 的环境里没有 `require`；字体只缓存在内存里，站点不可用时程序开不了机 | `cct_live_wait` 不再返回旧记录；`cct_live_lua` 超时可取回结果、自动清理临时文件；`cct_live_snapshot`（保存 / 比较 / 恢复）；trace 的 `contains`；`computer_dir` |

第 4 轮报告里提到但**没有改**的：
- `cct_live_lua` 在程序运行时无法使用：它要从 shell 运行；要在程序运行时执行代码，就得改动程序收发事件的方式，会影响被测程序本身。（联合测试 3 之后加了 `cct_live_peripheral`：不运行 Lua，在游戏这一侧直接读取外设，程序运行时也能用。）
- 区域的 `labels` 不带文字：像素图按钮本来就没有文字可读。
- `cct_live_files stat` 不报告文件的来源：可以用 trace 的 `contains` 查到这个文件是哪次下载写入的。
- 去掉 URL 里易变参数的“归一化 URL”：用 `contains` 按文件名过滤就够了。

## 联合测试 3：Create 工厂控制中心（模组外设）

提示词见 [`joint-test-factory.md`](joint-test-factory.md)，第 1 次的分析见 [`joint-test-factory-report.md`](joint-test-factory-report.md)。

| 次数 | 电脑 | token | 结论 | 根据报告做的改进 |
|---|---|---|---|---|
| 1 | 7 | 9293 万（开源 DeepSeek，算力中心部署） | 看板、按钮在实机上可用；聊天指令只用注入的事件测过，过载保护和缺料暂停没有测到；没有完成收尾 | 中文 Windows 上 fixture 出错不再变成 `MalformedInputException`；`cct_live_peripheral`（程序运行时读外设）；网络外设按真实名字记录；trace 的 `peripheral` / `method` 过滤和大小上限后的轮换；`cct_live_wait` 不再匹配旧画面；指南补充事件参数下标、事件丢失和 `os.clock` |
| 2 | 8 | 7064 万（输出 24.5 万；deepseek-v4.1-flash-1017 官方 API；60 分钟，268 次调用） | 请玩家配合了 17 次：真实聊天、真实过载保护和通知去重、拆装应力表、远离禁止加速、开机自启都在实机上验证；缺料和满箱只在模拟器；收尾完整。CC 的事件丢失问题仍花了约 34 分钟，交付的程序只是缩小了丢失窗口 | 模拟器 `fs.move` 到同前缀的路径不再误报；trace 里的字符串按 Lua 实际收到的记录；指南：用 `parallel` 避免事件丢失、注入 UTF-8、同一外设接两次时事件重复、源码 tag。之后实现了 N12 / N13：fixture 的 `scripted` 外设（用它在模拟器里复现了这次交付程序的丢指令问题）、`cct_trace` 的 `contains`、`cct_live_files` 只读 `/rom` |

## 联合测试 4：Create 订单产线（订单队列 + 断电恢复）

提示词见 [`joint-test-orders.md`](joint-test-orders.md)，分析见 [`joint-test-orders-report.md`](joint-test-orders-report.md)。

| 次数 | 电脑 | token | 结论 | 根据报告做的改进 |
|---|---|---|---|---|
| 1 | 0 | 4382 万（输出 19.9 万；deepseek-v4.1-flash-1017 官方 API；任务约 38 分钟） | 第一版就是并行结构，没有遇到事件丢失；模组外设在模拟器里全部用 `scripted`；实机验证了下单、计数、过载保护、重启续跑、触摸、拔插外设；中文回复乱码在玩家反馈后改为 JSON 文本组件。维护者验收待补 | `cct_trace` 可以用 `run_id`；箱子容量上限 1024 → 4096；fixture 中文变 `?` 时在 `notes` 里提示；指南补充向外设发中文的写法 |

## Linux 验证（开发环境）

- 单元测试 54 个：运行、错误定位、外设、fixture、trace 查询、节拍、Java 查找、MCP 协议（握手、工具调用、取消）、内存耗尽、`cct_live_*` 工具（对接模拟的 cct-live 接口）。
- 官方 MCP TypeScript SDK 客户端连接发布包：四个工具、内联 fixture 与定时触摸显示器、两个运行并行、参数错误提示、内存耗尽结果；用 Java 8 启动同样可用。
- Java 8 自动切换、找不到 Java 17+ 时退出码 69 的提示、`JAVA_OPTS=-Xmx256m` 覆盖内存上限，均已验证。

## 尚未解决 / 待测

1. 各 AI 客户端分别接入 MCP 的结果（见上表）。
2. Java 25 下的 Guava 警告（stderr 噪音）。
3. trace 大小上限与截断、`cct-run clean` 清理旧记录（N4 剩余部分）。
4. macOS 未测试；PowerShell 7 在 Windows 上未测试。
5. 程序用 `pcall` 接住内存错误后继续运行时，不会被标记为 `out_of_memory`（`memory.heap_peak_mb` 仍会显示接近上限）。
