# dynamic — 动态探针与运行记录

对 `bf68ac4`（v0.2.3）用已构建的 `cli/build/install/cct-run`（真实 CC:T 1.120.2、Cobalt 0.9.9、gson 2.11.0、JVM 参数 `-Xss4m -Xmx1g`）做的动态复现。所有 run 目录都在 `runs/`，每个含 `summary.json`、`trace.jsonl`、`program/`。

## 脚本（scripts/）

| 文件 | 目的 | 对应结论 |
|---|---|---|
| `debug_probe.lua` | 第一版 debug 探测（在非法 level 上中断，保留作历史） | — |
| `debug_probe2.lua` | 枚举 16 个 debug 函数、registry、thread 参数、栈上局部量、`_G` 函数 upvalue，检查 `__cctrun` | H1：工具箱完整；report.done 链不可达 |
| `fs_write.lua` | 写 20×1MB 并读 `fs.getSize/getFreeSpace/getCapacity` | H2：写入不受容量限制 |
| `fs_move.lua` / `fs_move2.lua` | `fs.move` 目录入自身、前缀兄弟、child→parent、`/a.log→/a.log.1` | E1：行为与游戏一致；两步改名非原子 |
| `energy_probe.lua` | 读 fixture energy 外设的 energy/capacity | F3：1e999 在 build 阶段变成 0 |
| `inventory_probe.lua` | `getItemDetail` 传 1/1.5/1.0/"1"/0/-1/4 | F10：1.5 截断为 1，与 CC:T 默认实现一致 |
| `event_probe.lua` | 等待 `probe` 事件（用于深嵌套 args 探针） | 见下方 BOM 说明 |
| `GsonDepth.java` / `GsonDepth.class` | 直接用 gson-2.11.0.jar 解析 N 层嵌套 | F1：2.11.0 无 255 层上限、解析迭代 |
| `mcp_probe2.ps1` | 驱动 `cct-run mcp`：initialize、1M 层 2MB 行、8MB 行、tools/list 后续 | C2：行长无限制但服务不死 |
| `mcp_probe.ps1` | 第一版 MCP 探针（ReadLineAsync 竞态 bug，保留作历史） | — |

## fixture（fixtures/）

| 文件 | 内容 |
|---|---|
| `nest_{200,255,256,300,1000,5000,20000,50000,200000,1000000}.json` | `{"x":` + N 层 `[` + `0` + N 层 `]` + `}`，用于 F1 深嵌套解析 |
| `deep.json` | 300 层嵌套 fixture |
| `event_deep_{1000,20000,100000,300000}.json` | `timeline[0].do=event, args=[N 层嵌套]`，用于 NEW SOE |
| `energy_inf.json` | `{"peripherals":{"back":{"type":"energy_storage","capacity":1e999}}}` |
| `inventory.json` | 3 格 inventory，槽 1 放 5 个 stone |

## run 记录（runs/）

| 目录 | 程序/场景 | 观察 |
|---|---|---|
| `01-debug_probe-*` | debug_probe.lua | 在非法 level 上报 `bad argument #1 (level out of range)`（探针自身缺陷） |
| `02-debug_probe2-*` | debug_probe2.lua | status=ok；debug 16 函数可用、registry 无 thread、栈无 harness 帧、0 report upvalue |
| `03-fs_write-*` | fs_write.lua | status=ok；20,971,520 B 写入成功、free="unlimited"、capacity=1000000、heap_peak=158MB |
| `04-fs_move-*` | fs_move.lua | status=error：`Can't move a directory inside itself`（与游戏一致） |
| `05-fs_move2-*` | fs_move2.lua | status=ok；inside-self 拒绝、/d→/de 成功、child→parent File exists、/a.log→/a.log.1 成功 |
| `06-energy_probe-*` | energy_probe.lua + energy_inf.json | status=ok；getEnergyCapacity 读到 0（1e999 经 build 阶段 getAsLong 变 0） |
| `07-inventory_probe-*` | inventory_probe.lua + inventory.json | status=ok；getItemDetail(1.5) 返回槽 1；0/-1/4 报槽位越界 |
| `08-event_probe-*` | event_probe.lua + event_deep_1000.json | status=error：probe 脚本当时带 UTF-8 BOM，`unexpected symbol near <\239>`；**此 run 不能用于判断 1000 层行为**（toLua 未 SOE 仍然成立） |
| `09-event_deep_20000-SOE-*` | event_deep_20000.json | 无 summary：StackOverflowError，进程 exit 1 |
| `10-event_deep_100000-SOE-*` | event_deep_100000.json | 同上 |
| `11-event_deep_300000-SOE-*` | event_deep_300000.json | 同上 |

> 说明：`event_probe.lua` 已重新以无 BOM 保存；08 的历史 run 保留以说明该次误差。SOE 发生在 `Runner.apply` 的 `JsonValues.toLua` 递归（fixture 转换阶段），与程序脚本是否有语法错误无关，因此 09-11 的崩溃证据有效。

## 复现命令

```powershell
# 失败/成功都用已构建的 install 目录；run 输出到 --out
$env:Path = "D:\AI\DSH\cc-t\CC-Tweaked_MCP\cli\build\install\cct-run\bin;" + $env:Path
cct-run run evidence\dynamic\scripts\debug_probe2.lua --timeout 30 --out <outdir>
cct-run run evidence\dynamic\scripts\fs_write.lua --timeout 60 --out <outdir>
cct-run run evidence\dynamic\scripts\fs_move2.lua --timeout 30 --out <outdir>
cct-run run evidence\dynamic\scripts\energy_probe.lua --fixture evidence\dynamic\fixtures\energy_inf.json --timeout 15 --out <outdir>
cct-run run evidence\dynamic\scripts\inventory_probe.lua --fixture evidence\dynamic\fixtures\inventory.json --timeout 20 --out <outdir>
cct-run run evidence\dynamic\scripts\event_probe.lua --fixture evidence\dynamic\fixtures\event_deep_20000.json --timeout 15 --out <outdir>
# Gson 深度直测（需 gson jar 在 classpath）
javac -cp <cct-run>/lib/gson-2.11.0.jar evidence\dynamic\scripts\GsonDepth.java
java -cp "<cct-run>/lib/gson-2.11.0.jar;evidence\dynamic\scripts" GsonDepth 1000000
# MCP 探针
powershell.exe -NoProfile -ExecutionPolicy Bypass -File evidence\dynamic\scripts\mcp_probe2.ps1
```


## 2026-10-20 独立复核

上文是原 `bf68ac4` 的历史探针与运行记录，不替换其结论或文件。针对上传的 2026-10-19 报告，新增 [results-2026-10-20-review](results-2026-10-20-review/README.md)，记录 main `25a3921` 的 MCP 与公开 trace writer API 观察。当前核对结论见 [review-2026-10-19-main.md](../../verification/review-2026-10-19-main.md)。原修复/发行/联合测试证据目录也保持独立，不把不同基线的结果拼成一次测试。
