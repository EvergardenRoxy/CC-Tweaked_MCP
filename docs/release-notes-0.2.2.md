# cct-run 0.2.2

这一版来自 0.2.0 发布后的联合测试 4（Create 订单产线：聊天下单、订单队列、过载保护、断电续跑、显示器界面）。这也是配套 Skill 第一次参与的大型任务：AI 第一版代码就用上了 Skill 里的并行结构，没有再花时间在“外设调用期间丢事件”上（上一轮约 34 分钟）。测试暴露的问题集中在**中文**和几处不顺手的地方，这一版修正了它们。测试记录见 [`joint-test-orders-report.md`](joint-test-orders-report.md)。

配套的 [CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill) 1.2.2 同时更新：结构重新整理，Create 和 Advanced Peripherals 的实测记录独立成文件，新增中文和显示器界面两篇说明。两者建议一起升级。

## 下载

| 文件 | 说明 |
|---|---|
| `cct-run-0.2.2.zip` | 命令行工具和 MCP 服务器 |
| `cct-live-1.20.1-0.2.2.jar` | Forge 1.20.1 mod（可选）。**和 0.2.0 的 mod 功能相同**，只是版本号跟着更新；已经装了 0.2.0 的可以不换 |

系统要求和 0.2.0 相同：cct-run 需要 Java 17+；cct-live 需要 Minecraft 1.20.1、Forge 47.x、CC: Tweaked 1.120.2。

## 改进

- **fixture 里写了中文时会提醒**：事件参数、外设返回值里 U+00FF 以上的字符（中文等）进 Lua 时会变成 `?`，和游戏里一样。以前没有任何提示，联合测试 4 的 AI 因此放弃了在模拟器里测中文指令，改给程序加了一套英文指令。现在运行摘要的 `notes` 里会出现 `fixture_text`，指出是哪个字段，并说明注入 UTF-8 的写法。
- **`cct_trace` / `cct_screen` 可以直接用 `run_id`**：运行摘要里的 `run_id` 现在可以当作 `run` 传入（在输出目录下查找）。以前只认完整的运行目录，AI 传 `run_id` 会得到 “not a run directory”。
- **fixture 的容器最多 4096 格**（以前是 1024）：满尺寸的 Create 物品保险库有 1620 格，实测的保险库有 1080 格，以前只能改小了写。
- **说明（`cct_guide`）补充**：
  - 程序把中文传给外设时，trace 里的参数显示成 `å·…`（每个字节一个字符），游戏里就是乱码；
  - 用 Advanced Peripherals 聊天框发中文的写法：`sendFormattedMessageToPlayer` 加 JSON 文本，中文写成 `\uXXXX` 转义（这种写法已在游戏里验证）；`textutils.serialiseJSON({ text = msg }, { unicode_strings = true })` 能生成同样的转义；
  - `run_id` 的用法。

## 文档

- README 重写：安装、用法速查、从源码构建分别移到 [`install.md`](install.md)、[`usage.md`](usage.md)、[`building.md`](building.md)；新增 cct-live 的性能图和 AI 实测对比图（浅色 / 深色两个版本，由 `scripts/readme_charts.py` 生成）；新增 AI 制作声明。
- 新增联合测试 4 的设计（[`joint-test-orders.md`](joint-test-orders.md)）和分析报告（[`joint-test-orders-report.md`](joint-test-orders-report.md)）。
- 发布包里带上了这些文档和图片。

## 兼容性

- 没有不兼容的改动：0.2.0 的 fixture、MCP 配置、命令行参数都原样可用。
- 运行摘要的 `notes` 里可能多出 `fixture_text` 记录；按 `kind` 筛选 notes 的脚本不受影响。
- cct-live mod 没有改动，0.2.0 和 0.2.2 的 mod 都能配合 cct-run 0.2.2 使用。

## 安装和升级

1. 用 `cct-run-0.2.2.zip` 替换旧的 cct-run 文件夹；MCP 配置不用改。
2. （可选）把 `mods/` 里的 cct-live jar 换成 `cct-live-1.20.1-0.2.2.jar`。

## 测试情况

- **单元测试**：54 个，全部通过（新增 `run_id` 查找；扩展了 fixture 容量上限和 `fixture_text` 提示的测试）。
- **AI 实测**：联合测试 4 用的是 0.2.0。AI 在模拟器里用 `scripted` 描述了全部 Create 和 Advanced Peripherals 外设，在实机上验证了下单、计数、过载保护、重启续跑、触摸、拔插外设和中文收发；后续调整中验证了显示链接器写中文和显示器界面。**这一版新增的功能还没有经过 AI 实测。**

## 已知限制

和 0.2.0 相同：`scripted` 外设只返回 fixture 里写的值，不检查参数类型，也不模拟机器之间的联动；`cct_live_lua` 要在 shell 提示符下运行；macOS 未测试；模拟器不支持海龟、HTTP、真实世界等。

## 许可证

和 0.2.0 相同：本项目代码 MPL-2.0；`harness.lua` 为 CCPL；附带的 CC: Tweaked 及其依赖使用各自的许可证。
