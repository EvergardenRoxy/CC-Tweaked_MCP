# cct-run 测试记录

截至 2026-10-17。机器审查用的完整记录在 `handoff.md` 的 VERIFICATION 一节（V1–V13），这里是给人看的汇总。

## 当前结论

| 项目 | 结果 |
|---|---|
| Windows 冒烟测试 | **22/22 通过**（Windows 11、PowerShell 5.1、代码页 936、Zulu 21） |
| Linux 冒烟测试 | bash 21/21；PowerShell 7 20 项通过、1 项跳过（Linux 上没有可用的 Java 8） |
| 单元测试 | 39/39 |
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

## Linux 验证（开发环境）

- 单元测试 39 个：运行、错误定位、外设、fixture、trace 查询、节拍、Java 查找、MCP 协议（握手、工具调用、取消）、内存耗尽。
- 官方 MCP TypeScript SDK 客户端连接发布包：四个工具、内联 fixture 与定时触摸显示器、两个运行并行、参数错误提示、内存耗尽结果；用 Java 8 启动同样可用。
- Java 8 自动切换、找不到 Java 17+ 时退出码 69 的提示、`JAVA_OPTS=-Xmx256m` 覆盖内存上限，均已验证。

## 尚未解决 / 待测

1. 各 AI 客户端分别接入 MCP 的结果（见上表）。
2. Java 25 下的 Guava 警告（stderr 噪音）。
3. trace 大小上限与截断、`cct-run clean` 清理旧记录（N4 剩余部分）。
4. macOS 未测试；PowerShell 7 在 Windows 上未测试。
5. 程序用 `pcall` 接住内存错误后继续运行时，不会被标记为 `out_of_memory`（`memory.heap_peak_mb` 仍会显示接近上限）。
