# engine issues（运行时 + fixture/外设）

基线：`bf68ac4`（项目版本 0.2.3，`git describe` = `v0.2.3-9-gbf68ac4`；tag v0.2.3 在 9 个提交之前）。
文件内每条 issue 是一个独立 YAML 文档，字段定义见 `README.md`。只收录核对后**确实存在且尚未修复**的问题。
本文件的条目来源：本次审查（REVIEW-*）与旧 fix plan（LEGACY-*，见 `legacy-open.md` 的映射）。

---

## ENG-RT-01 — fs 写入不受容量限制，可写爆堆；OOM 归类不可区分

```yaml
id: ENG-RT-01
title: "fs 写入不受 DISK_CAPACITY 限制，可写爆 1GB 堆；OOM 只能按 Cobalt 文案归因"
severity: high
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-H2, REVIEW-L-Oom]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "55-58, 88, 134, 388-392"
evidence: |
  Runner:57-58 注释自认 "Reported by fs.getFreeSpace; writes are not limited"；Runner:88 new MemoryMount(DISK_CAPACITY)，
  javap 显示 MemoryMount.capacity 只被 getRemainingSpace/getCapacity 读取，EntryChannel 无容量检查 → 写入无配额。
  动态实测：程序写入 20×1MB=20,971,520 B 成功，fs.getSize=20971520，fs.getFreeSpace("") 返回 "unlimited"，
  fs.getCapacity("")=1000000，JVM heap_peak 158MB（heap_limit 989MB）。
  Runner:134 用 api.message.contains("vm error: java.lang.OutOfMemoryError") 归类 out_of_memory，fs 写爆堆与真实 VM OOM 文案相同。
impact: |
  单个受测程序可在 timeout 窗口内耗尽 1GB 堆；out_of_memory 状态无法区分原因；no write statistics 可供归因。
fix: |
  1) 给根 MemoryMount 加写入配额（或改用带容量/字节统计的 WritableMount 包装），
     在 summary.notes 记录总写入字节数；
  2) OOM 判定改为类型/错误来源判定（MachineResult/Error），不要依赖 Cobalt 文案。
tests: |
  新增 engine 测试：写入超过 capacity 后 getSize 与 fs.getFreeSpace 行为；OOM marker 不再依赖字符串。
verify: |
  动态：cct-run run 写 20MB 程序（见 D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/dynamic/fs_write.lua）。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  RootMount 对创建/增长/追加/seek/截断/删除/改名及打开句柄实施容量配额，summary.disk 区分 free 与累计写入；VmFailures 在 Lua 文案转换前观察真实 Java OOM。RootMountTest、MainTest.heapExhaustionIsItsOwnStatus、InputSafetyTest 的伪造错误回归通过；删除句柄差异见 DV17。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-02 — debug 工具箱完整可用；受测程序可篡改 `_G` 影响 harness 收尾与状态归因

```yaml
id: ENG-RT-02
title: "debug 库对受测程序完整可用；程序可写 _G 篡改 harness 收尾全局并伪造 out_of_memory/timeout"
severity: high
status: fixed
confidence: partial
module: engine-runtime
origin: [REVIEW-H1, REVIEW-NEW-1, REVIEW-L-Oom]
files:
  - path: engine/src/main/resources/cctrun/harness.lua
    lines: "13-14, 33-37, 60-71"
  - path: engine/src/main/java/cctrun/engine/HarnessApi.java
    lines: "48-56"
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "134"
evidence: |
  harness.lua:13 `local report = __cctrun`，:14 `_G.__cctrun = nil` 是唯一防线。
  动态探测（evidence/dynamic/debug_probe2.lua）确认 16 个 debug 函数全部可用（getlocal/setlocal/getupvalue/setupvalue/
  getregistry/sethook/gethook/getinfo/traceback/getmetatable/setmetatable/upvalueid/getfenv/setfenv）；
  registry 只有 {_LOADED, cc_try_barrier}、0 个 thread；debug.getlocal 支持 thread 参数；
  栈上只见 program chunk 与 exception.lua 帧，无 harness 帧；全 _G 函数 upvalue 扫描 0 个 report；
  _G.__cctrun == nil。=> 报告 H1 的 report.done 伪造链在当前实现下不可达（静态+动态均不支持）。
  可达的真实面：harness env.__index=_G（:37），程序可改 _G.tostring/printError/debug；
  Runner:134 只按含 OOM 文案的 message 归因；harness:66-70 在 report.done 前使用 printError/debug，
  被篡改后 harness 可先崩 → 计算机停在交互 shell → run 以 timeout 收尾。
impact: |
  受测程序可把普通 error 伪装成 out_of_memory（错误文案可由程序控制），或让 harness 收尾失败而误报 timeout；
  无法伪造 ok=true/报告自身成功（report 对象不可达）。
fix: |
  1) HarnessApi.done 加一次性 token/签名，Java 侧拒绝二次或非 harness 调用；
  2) harness 顶部把 printError/tostring/debug.traceback 局部化（local printError = printError ...），
     或对 _G 关键函数做只读保护；
  3) OOM 改用类型判定，不匹配文案。
tests: |
  动态探针：debug 函数表 + registry 遍历 + _G 篡改（D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/dynamic/debug_probe2.lua）。
  建议加一条 Java 测试：done 二次调用被拒绝。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  harness 提前捕获收尾函数并保护调用，完成报告需 token 且只接受一次；OOM 不再按文案分类。InputSafetyTest 的 globals 篡改/伪造 OOM/重复报告测试通过。保持完整 debug，未声称恶意程序隔离。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-03 — 环境中断被报成 timeout

```yaml
id: ENG-RT-03
title: "tick 循环 InterruptedException 返回 timeout，与程序超时共用 exit_code=2"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-E2, LEGACY-L-pacer-interrupt]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "191, 252-255"
  - path: cli/src/main/java/cctrun/cli/Main.java
    lines: "109-110"
evidence: |
  catch (InterruptedException e) { Thread.currentThread().interrupt(); return "timeout"; }（Runner:252-255）；
  真正超时是 if (tick >= maxTicks) return "timeout";（:191）；两者都映射 exit_code=2，summary 无 interrupted 标记。
impact: |
  上层关闭/中断引擎线程时被误报为程序超时；CLI 有 System.exit、MCP 取消杀子 JVM，实际面窄。
fix: |
  新增 interrupted/cancelled 状态（独立 exit code 或 summary.interrupted=true），或在 run_end 记录原因。
tests: |
  对 Runner 线程发 interrupt，断言状态与 exit_code。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  保留 timeout/exit 2，附 termination_reason=interrupted 和 run_interrupted；写完 summary/state 后恢复线程 interrupt。InputSafetyTest.interruptedRunWritesItsCauseAndRestoresTheInterruptFlag 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-04 — context.close 不等线程退出（库嵌入泄漏）

```yaml
id: ENG-RT-04
title: "context.close(50ms) 后放弃等待计算机线程，仅记 note"
severity: low
status: fixed
confidence: partial
module: engine-runtime
origin: [REVIEW-E3]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "41, 152-157"
evidence: |
  Runner:41 javadoc 声明 one run per JVM；:152-157 close(50, MILLISECONDS) 失败只 trace.record("context_close_pending")。
  CLI 每 run 一次进程并 System.exit；MCP 每 run 子 JVM + waitFor/destroyForcibly，故当前无泄漏后果。
impact: |
  作为库在同一 JVM 连续跑 run 时线程会累积。
fix: |
  javadoc 明确 embedding 限制，或 close 等待并在失败时 fail loud；可提供 closeAll()。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  Runner Javadoc 明示每 JVM 一次 run、close(50ms) 后线程仍可能收尾，嵌入调用不提供隔离；保留已有 CLI/MCP 进程模型。文档核对通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-05 — harness `fs.open` 无 nil 检查

```yaml
id: ENG-RT-05
title: "harness.lua fs.open(path) 无 nil 检查，程序文件缺失时报 nil index"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-E4]
files:
  - path: engine/src/main/resources/cctrun/harness.lua
    lines: "33-35"
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "75, 88-99"
evidence: |
  harness.lua:33 `local file = fs.open(path, "r")`，:34 直接 file.readAll()；Runner 在装 harness 前写入程序文件，
  故当前流程必成功；只有 addFile 异常/重构时才会暴露。
impact: |
  错误信息是 `attempt to index ? (a nil value)`，不指向真实原因（程序文件缺失）。
fix: |
  `if not file then report.done(false, "setup", "program file missing: "..path, nil) end`。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  harness 对 fs.open 返回 nil 提供 setup 错误并等待 runner 收尾，避免索引 nil 后退回 shell 超时。代码核对；Runner 正常/语法/运行时回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-06 — CoreConfig 进程级静态跨 run 残留

```yaml
id: ENG-RT-06
title: "CoreConfig.httpEnabled/defaultComputerSettings 为进程级静态，跨 run 无恢复"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Cfg]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "79-84"
evidence: |
  javap 显示 CoreConfig 字段全 public static；Runner:79-84 每次 run 覆写，无恢复/无状态检查；one-run-per-JVM 下无影响。
impact: |
  库多 run 时 http 永久关闭、上一 run 的设置渗入下一 run。
fix: |
  run 前后保存/恢复 CoreConfig，或文档声明进程级副作用并加运行期断言。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  Runner Javadoc 与 limitations 记录 CoreConfig 进程级副作用及不支持重复/并发嵌入隔离；不改变一 run 一 JVM 的既有决定。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-07 — maxTicks 的 int 溢出（库调用面）

```yaml
id: ENG-RT-07
title: "maxTicks = timeoutSeconds * 20 为 int 乘法，超大 timeout 溢出"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Ovf]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "183"
  - path: engine/src/main/java/cctrun/engine/RunSpec.java
    lines: "29"
  - path: cli/src/main/java/cctrun/cli/Main.java
    lines: "133"
evidence: |
  Runner:183 int 乘法；RunSpec:29 只校验 timeoutSeconds < 1 无上界；CLI Main:133 intOption(--timeout, 15, 1, 3600) 钳制，
  故仅库调用面成立（timeoutSeconds ≥ 2^31/20 ≈ 1.07 亿时 tick 0 立即 timeout）。
impact: |
  库调用方可得到 "timeout" 且无诊断。
fix: |
  RunSpec 增加合理上限或 Math.multiplyExact/long。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  RunSpec 限定 timeoutSeconds 为 1..3600，防止 ticks 乘法溢出。InputSafetyTest 拒绝 Integer.MAX_VALUE；既有 timeout 回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-08 — Trace close 后静默丢记录

```yaml
id: ENG-RT-08
title: "Trace.record 在 close 后静默丢弃，无计数无日志"
severity: low
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Close]
files:
  - path: engine/src/main/java/cctrun/engine/Trace.java
    lines: "60-61, 107-109"
evidence: |
  record 首行 `if (closed) return;`；droppedNotes 只覆盖 MAX_NOTES 截断；当前时序 run_end 先于 close，无实际丢失路径。
impact: |
  未来时序改动中的丢数据不可见。
fix: |
  close 后丢弃计数进 summary，或在 close 前断言。
tests: |
  none
```

## ENG-RT-09 — 每条记录 flush（有意权衡）

```yaml
id: ENG-RT-09
title: "Trace 每条记录 flush 一次，I/O 放大（作为崩溃可读性的有意权衡）"
severity: info
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Flush]
files:
  - path: engine/src/main/java/cctrun/engine/Trace.java
    lines: "71-73"
evidence: |
  Trace:72 注释 "A killed run must still leave every earlier record readable: no buffered tail."；:73 每记录 flush。
impact: |
  高频 trace 每条一次 flush syscall；属有意选择。
fix: |
  可选按 tick/每 N 条 flush，保留大部分崩溃可读性；若改需基准测试。
tests: |
  none
```

## ENG-RT-10 — TraceQuery 缺字段 NPE 且不计 skipped

```yaml
id: ENG-RT-10
title: "TraceQuery 假定 tick/kind 字段必存在，缺失即 NPE 且不计 skipped"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-TQ]
files:
  - path: engine/src/main/java/cctrun/engine/TraceQuery.java
    lines: "66, 69, 111"
evidence: |
  matches(): record.get("tick").getAsInt()（:66）、kind 同（:69）；screenAt(): record.get("tick").getAsInt()（:111）；
  read() 的容错只包 JsonParser.parseString（:47-52），字段缺失时 NPE 冒泡，skipped 不增加。
impact: |
  合法 JSON 缺字段时整个查询 internal error。
fix: |
  统一 has()/isJsonPrimitive 校验并计入 skipped。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  TraceQuery 在过滤/回放前校验字段类型、尺寸和行号，坏对象与坏 JSON 统一计 skipped。InputSafetyTest.missingAndWrongTraceFieldsDoNotAbortQueriesOrReplay 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-11 — storedName 对畸形 programName 不设防

```yaml
id: ENG-RT-11
title: "storedName 对空名/'..'/Windows 保留名不设防（仅库 API）"
severity: low
status: fixed
confidence: partial
module: engine-runtime
origin: [REVIEW-L-Name, REVIEW-NEW-3]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "441-444"
  - path: engine/src/main/java/cctrun/engine/RunSpec.java
    lines: "25"
evidence: |
  Runner:442 Path.of(programName).getFileName().toString()；空串时 getFileName()==null → NPE；
  ".." 未拦；写路径 :75 单段不逃逸；MCP 的 PROGRAM_NAME 正则与 CLI 文件名已挡。
impact: |
  库 API 传空串得内部 NPE，保留名/.. 行为异常。
fix: |
  RunSpec 校验非空且拒绝 ../Windows 保留名。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  RunSpec 按宿主 Path 检查有效末段，拒绝空白、根路径、点与点点；未引入被审查驳回的 Windows 保留名假设。InputSafetyTest 边界通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-12 — Clock tick/ms 亚毫秒错位

```yaml
id: ENG-RT-12
title: "millis() 实时取 nanoTime，与 loop 线程设置的 tick 组合有亚毫秒错位"
severity: low
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Clock]
files:
  - path: engine/src/main/java/cctrun/engine/Clock.java
    lines: "8, 19-21"
  - path: engine/src/main/java/cctrun/engine/Trace.java
    lines: "64-65"
evidence: |
  Clock 有 volatile int tick（:8）与实时 millis（:19-21）；Trace:64-65 分两次读取，多线程 record 下可能跨 tick 边界。
impact: |
  诊断用 tick/ms 轻微不一致，不影响判定。
fix: |
  record 时一次性快照 (tick, ms) 或由 loop 线程写时间戳。
tests: |
  none
```

## ENG-RT-13 — run()/loop() 巨型方法与魔法数

```yaml
id: ENG-RT-13
title: "run()/loop() 巨型方法、魔法数，反射失败静默吞掉"
severity: low
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-L-Big]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "64-178, 181-257, 270-279"
evidence: |
  run() 64-178、loop() 181-257；魔法数 START_DELAY+5(:267)、50/10/40/1000000(:49,52,53,58)；
  startRequestedField() 失败 return null（:276-278）无 note。
  2026-10-20 六项约定范围交付（c94928bceb451e9a9ce444f5faf0b51a64a0d389）：
  每个 run 使用独立 RebootProbe，startRequested 字段缺失/不可访问时保留原 timing fallback，并最多记录一条 reboot_detection_fallback。缺字段故障注入覆盖时序边界与诊断有界；正常 reboot/timeout 回归通过。巨型方法重构未实施，整项保持 open。
  本轮证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
impact: |
  可维护性差；反射失败后 reboot 判定静默退化。
fix: |
  拆分 loop 为 tickOnce/decideOutcome；反射失败记 trace.note；魔法数提常量。
tests: |
  none
```

## ENG-RT-14 — RootMount 两步改名非原子

```yaml
id: ENG-RT-14
title: "RootMount 两步改名非原子，崩溃/强杀会残留 /.cctrun-rename"
severity: low
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-NEW-2]
files:
  - path: engine/src/main/java/cctrun/engine/RootMount.java
    lines: "34-42"
evidence: |
  rename 的 /a→/ab 分支经 temp=.cctrun-rename 两步改名（:36-42），只对 IOException 回滚；JVM 崩溃/强杀不清理。
impact: |
  残留顶层文件；后续 run 是新 mount，不受影响。
fix: |
  启动时清理，或在 trace.note 说明。
tests: |
  none
```

## ENG-RT-15 — 保护链外 OOM/VM error 不进入状态判定

```yaml
id: ENG-RT-15
title: "RecordingMachine 的 MachineResult 错误不参与 Runner 状态判定，保护链外 OOM 呈现为 timeout/shutdown"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [REVIEW-NEW-4]
files:
  - path: engine/src/main/java/cctrun/engine/RecordingMachine.java
    lines: "76-81"
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "136"
evidence: |
  RecordingMachine:76-81 只 trace.note("vm_error")；Runner:136 只读 api 的 finished/message，不读 MachineResult。
impact: |
  out_of_memory 只覆盖被 harness 捕获的 OOM；保护调用外 OOM 与真实 timeout/shutdown 混淆。
fix: |
  Runner 读取 MachineResult error 并映射状态。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  RecordingMachine 观察保护调用外 OOM 与非正常 MachineResult，Runner 统一归因并保留原始错误通道；类型 OOM、语法/运行时/无 yield/关机重启回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-1：Runner 146-154 优先读取 VmFailures 的 typed OOM；RecordingMachine 69-94 观察原始 VM 故障。不是继续按错误文案推断；本轮未做耗尽错误渲染路径的故障注入。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## ENG-RT-16 — run_id 随机段仅 4 位十六进制

```yaml
id: ENG-RT-16
title: "run_id 随机段仅 4 位十六进制且 createDirectories 不检查冲突"
severity: low
status: fixed
confidence: confirmed
module: engine-runtime
origin: [LEGACY-L-runid]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "66, 68"
evidence: |
  runId = UTC 毫秒时间戳 + HexFormat.of().toHexDigits((short) nextInt())（4 位）；Files.createDirectories(runDir) 不检查已存在。
impact: |
  同毫秒同随机数时两次运行互相覆盖 trace/summary。
fix: |
  随机段扩到 8 位以上，createDirectory + 冲突重试。
tests: |
  抽 helper 单测冲突重试。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  UUID 随机空间与原子 createDirectory/冲突重试结合；InputSafetyTest 确定性制造第一次同名并验证不覆盖。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-17 — 被杀/超时运行无 summary、无 killed 标记

```yaml
id: ENG-RT-17
title: "被杀/超时运行没有 summary，也没有 killed/state 标记"
severity: medium
status: fixed
confidence: confirmed
module: engine-runtime
origin: [LEGACY-B7]
files:
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "167-175"
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "261-263"
evidence: |
  Runner 只在 loop 正常结束后写 summary.json/run_end，运行前不写 state；MCP wall 超时直接 destroyForcibly，只回一句 did not finish，不在 run 目录留标记。
impact: |
  被杀 run 目录只有半截 trace，agent 无法判断是取消、崩溃还是未结束。
fix: |
  启动时写 state:running 小文件、结束时更新；MCP 杀进程后补 killed:true + 原因。
tests: |
  none
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  原子 state.json 和父子进程精确 reservation，记录 pid/start identity；取消确认子进程终止后补 failed/cancelled。RunStateTest 与 McpServerTest 通过。外部强杀可能残留最后已知 running，不保证 summary。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-RT-18 — 模拟器 trace 无容量上限与清理入口

```yaml
id: ENG-RT-18
title: "模拟器 trace.jsonl 无总量上限，跨 run 记录缺少清理入口"
severity: medium
status: open
confidence: confirmed
module: engine-runtime
origin: [REVIEW-20261019-P2-2]
files:
  - path: engine/src/main/java/cctrun/engine/Trace.java
    lines: "31-80"
  - path: README.md
    lines: "178"
evidence: |
  2026-10-20 核对 main 25a392102b1ce47c32743564298df0622b04e9ca：Trace.record 逐条写入并 flush，
  没有字节预算、截断策略或清理入口。README 路线图仍将模拟器 trace 上限与 cct-run clean 列为待做。
  公开 Trace API 探针写入 512 条 4096 字符记录，文件超过 1 MiB；具体值和分发 jar hash 见
  issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
  该探针仅证明所测写入未被容量限制，不把有限测试当作数学上的无限增长证明；无配额结论同时依据源码。
impact: |
  高频或多次运行可积累宿主磁盘记录；不能把模拟器 1 MB 逻辑磁盘配额或 JVM 堆上限当成 trace 配额。
  原报告的 86 MB/h 是特定负载估算，不是写入速率上限。与 MOD-18 的实机 body/session 保留问题不同。
fix: |
  按 handoff D17 已定原则实现超限截断而非采样，保留 D55 已完成行可读性；明确截断标记与查询行为。
  清理入口需要限定运行目录、保留策略和用户数据边界；本轮仅登记，不实现清理或删除记录。
tests: |
  后续验证临界字节数、截断后合法 JSONL/summary、并发 run 隔离，以及清理时不误删其他运行或用户文件。
```

## ENG-FX-01 — 默认值在 validate/build 两阶段重复、靠异常字符串耦合

```yaml
id: ENG-FX-01
title: "默认值 27/100_000 在 validate 与 build 各重复 3 处，build 阶段靠 IllegalStateException 耦合"
severity: medium
status: fixed
confidence: confirmed
module: engine-fixture
origin: [REVIEW-F2, REVIEW-NEW-3]
files:
  - path: engine/src/main/java/cctrun/engine/Fixture.java
    lines: "169, 188, 298, 306, 364, 374, 410, 441"
evidence: |
  27 在 169/306/364；100_000 在 188/298/374；64 只在 365 的 slotLimit（421/422 的 64 是物品 maxCount 语义）。
  build 阶段抛 IllegalStateException("validated earlier")（410/441）会丢 JSON 路径。
  FixtureTest.java:52-53 注释记录过 slot 越界回归（曾导致 Runner.apply 运行期 AIOOBE、无 summary）。
impact: |
  改默认值/校验规则时易只改一处，两阶段不一致导致运行期异常或语义漂移。
fix: |
  抽 DEFAULT_INVENTORY_SIZE / DEFAULT_ENERGY_CAPACITY 常量两阶段共用；build 阶段异常补 JSON 路径。
tests: |
  FixtureTest.timelineItemSlotMustFitTheInventory 已覆盖一条回归。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  fixture 校验与构建共用 inventory/energy 默认常量，精确数值解析异常带 JSON 路径；FixtureTest 的 timeline 槽位/容量与 InputSafetyTest 边界通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-02 — timeline args 深嵌套可致 StackOverflowError（无 summary）

```yaml
id: ENG-FX-02
title: "event.args 只校验是数组；深嵌套经 JsonValues.toLua 递归触发 StackOverflowError，进程崩溃且无 summary"
severity: medium
status: fixed
confidence: confirmed
module: engine-fixture
origin: [REVIEW-F4, REVIEW-NEW-1]
files:
  - path: engine/src/main/java/cctrun/engine/Fixture.java
    lines: "283-286"
  - path: engine/src/main/java/cctrun/engine/JsonValues.java
    lines: "107-124"
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "193-213, 480-496"
  - path: cli/src/main/java/cctrun/cli/Main.java
    lines: "100-107"
evidence: |
  Fixture:283-286 event 分支只 array(args) 校验，元素不递归校验；
  JsonValues.toLua 无深度/条目上限（of 有 MAX_STRING=200/MAX_ENTRIES=50/MAX_DEPTH=4）；
  Runner.loop 只 catch RuntimeException，Main 不 catch StackOverflowError。
  动态实测（evidence/dynamic/event_deep_*.json）：args 嵌套 1000 层 status=error；20000/100000/300000 层
  全部 `Exception in thread "main" java.lang.StackOverflowError`，exit 1，无 summary.json。
impact: |
  恶意/粗心 fixture 使 cct-run 子进程崩溃且丢失 summary，MCP 只看到子进程失败；fixture 内存无上限。
fix: |
  在 Fixture.parse 对 args 做深度/节点数上限校验；给 toLua/noteWideText 加同一 MAX_DEPTH；Runner/Main 捕获 Error 并写 summary。
tests: |
  none
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  JsonLimits 迭代限制输入深度/节点/字节，在 deepCopy、宽字符检查和 toLua 递归前拒绝。InputSafetyTest 覆盖 20000 层拒绝和合法边界；不复用展示深度 4。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-03 — longValue 只查下限，Infinity/超范围静默饱和

```yaml
id: ENG-FX-03
title: "longValue 只查下限；Infinity 与超 Long 范围的值静默饱和，且 validate/build 取值不一致"
severity: low
status: fixed
confidence: confirmed
module: engine-fixture
origin: [REVIEW-F3]
files:
  - path: engine/src/main/java/cctrun/engine/Fixture.java
    lines: "188-190, 297-299, 374-375, 517-523"
evidence: |
  longValue 校验 d==Math.rint(d) 与 d<min 后直接 (long)d；Infinity 通过两检查并饱和为 Long.MAX_VALUE。
  build 阶段改用 JsonPrimitive.getAsLong()（:374），LazilyParsedNumber → BigDecimal.longValue()。
  动态实测 {"capacity":1e999}：validate 通过，程序读到 getEnergyCapacity()=0（build 阶段取值为 0），
  即 validate 与 build 对同一值给出不同结果。
impact: |
  fixture 可声明 1e999/Long.MAX 而不报错，能量/容量模型失真且两阶段不一致。
fix: |
  longValue 增加 max 与 Double.isFinite 检查；build 阶段复用 validate 的解析结果而不是重读 JSON。
tests: |
  无；加 1e999、Long.MAX+1、NaN 用例。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  BigDecimal.longValueExact 校验整数和 long 范围，两阶段复用同一解析规则。InputSafetyTest 拒绝 1e999、Long.MAX+1、小数/负值并接受 Long.MAX。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-04 — timeline 不排序、每 tick 全表扫描、超 timeout 动作静默丢弃

```yaml
id: ENG-FX-04
title: "timeline 不排序不查重、每 tick 全表线性扫描；tick 超过 timeout 的动作静默丢弃"
severity: low
status: fixed
confidence: partial
module: engine-fixture
origin: [REVIEW-F5, REVIEW-NEW-2]
files:
  - path: engine/src/main/java/cctrun/engine/Fixture.java
    lines: "148-159"
  - path: engine/src/main/java/cctrun/engine/Runner.java
    lines: "189-196"
evidence: |
  timeline 存 ArrayList，不排序不查重无条数上限；Runner.loop 每 tick 遍历全表、tick 相等才执行，复杂度 O(ticks×actions)；
  tick>=maxTicks 直接 return timeout，超过 timeout 的动作无提示。
impact: |
  大 timeline 拖慢 tick loop；过期动作不执行也无从得知，调试易误判。同一 tick 多条按 fixture 顺序执行（测试证实均执行）。
fix: |
  parse 期对 tick>timeout 的条目告警/notes；按 tick 排序或建索引；限制 timeline 条数。
tests: |
  PeripheralTest.energyFluidAndTimelineChanges 覆盖同 tick 多条。
fixed_in: be72dd0ad3796f184779cb01825215e20b1261c3
fixed_at: 2026-10-19
resolution: |
  timeline 稳定排序、tick 循环用迭代器推进，同 tick 原顺序保留；超出 timeout 的动作给有界 note，整体输入节点上限约束规模。顺序与既有能量/流体动作回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-05 — 外设 RuntimeException 细节写入对客户端可见的 trace

```yaml
id: ENG-FX-05
title: "DispatchPeripheral 把 'internal: '+e 写入 trace，summary.notes 受 MAX_NOTES=10 配额限制"
severity: low
status: open
confidence: confirmed
module: engine-fixture
origin: [REVIEW-F6, REVIEW-NEW-6]
files:
  - path: engine/src/main/java/cctrun/engine/peripheral/DispatchPeripheral.java
    lines: "136-140"
  - path: engine/src/main/java/cctrun/engine/Trace.java
    lines: "30, 80-91"
evidence: |
  catch RuntimeException → data.addProperty("error", "internal: " + e)，随 peripheral_return 进 trace；
  note 只保留前 10 条进 summary.notes，其余只计 droppedNotes，详细错误只在 trace.jsonl。
  2026-10-20 六项约定范围交付（c94928bceb451e9a9ce444f5faf0b51a64a0d389）：
  agent guide/limitations 说明 notes 仅首 10 条、notes_dropped 非零时给出省略数，剩余诊断查 trace，区分预览截断。未移除本地 RuntimeException 细节或改变错误传播；仅完成约定文档子项，整项保持 open。
  本轮证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
impact: |
  泄漏限于异常类名+message（无堆栈）；大量失败时 summary.notes 不完整。
fix: |
  internal 错误改固定文案，详细异常只写 trace；文档说明 notes 配额与 trace.jsonl 是完整来源。
tests: |
  PeripheralTest.inventoryErrorsUseTheGamesMessages 覆盖 notes。
```

## ENG-FX-06 — trace 数值类型有界化未文档化

```yaml
id: ENG-FX-06
title: "trace 数值转换：≥1e15 整数转 double、NaN/Infinity 转字符串，类 javadoc 未说明"
severity: info
status: fixed
confidence: partial
module: engine-fixture
origin: [REVIEW-F8]
files:
  - path: engine/src/main/java/cctrun/engine/JsonValues.java
    lines: "16, 38-43"
evidence: |
  整数 |d|<1e15 转 long；更大整数转 double 丢精度；NaN/±Inf 转字符串；类 javadoc 仅 "bounded JSON for the trace"，
  未记录强转/类型变化。属有界化取舍。
impact: |
  trace 消费方按 JSON 类型判断时可能误读；不影响运行结果。
fix: |
  在 JsonValues javadoc 与 handoff TRACE_FORMAT 记录规则。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  JsonValues Javadoc、guide、handoff TRACE_FORMAT 明确 double、大整数精度、非有限数字符串、字符串/容器预览边界；mod JsonValuesTest 同步验证字节与非有限值语义。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-07 — ItemStack 详情用 HashMap，trace 键序非插入序

```yaml
id: ENG-FX-07
title: "basicDetail/fullDetail 用 HashMap，trace 键序按 hash 而非插入序"
severity: low
status: fixed
confidence: partial
module: engine-fixture
origin: [REVIEW-F9]
files:
  - path: engine/src/main/java/cctrun/engine/peripheral/ItemStack.java
    lines: "42-48, 51-61"
evidence: |
  basicDetail 用 HashMap，fullDetail 复用后追加；HashMap 迭代序由字符串 hash 决定，对固定键集确定但非插入/排序；测试不断言键序。
impact: |
  trace 可读性与跨版本可比性略差，无功能影响。
fix: |
  改用 LinkedHashMap 固定字段顺序。
tests: |
  PeripheralTest 仅断言字段值。
fixed_in: c94928bceb451e9a9ce444f5faf0b51a64a0d389
fixed_at: 2026-10-20
resolution: |
  ItemStack.basicDetail 使用 LinkedHashMap，fullDetail 沿用其顺序；直接 JsonValues 序列化的字段与可选 nbt/tags/damage 值有回归，现有物品操作回归通过。不承诺 Lua pairs 顺序。
  范围与本轮实测证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
```

## ENG-FX-08 — getInt 对小数静默截断（与游戏一致，缺测试）

```yaml
id: ENG-FX-08
title: "args.getInt 对小数截断为整数（1.5→1）且无测试；复用 CC:T 默认实现故与游戏语义一致"
severity: low
status: fixed
confidence: partial
module: engine-fixture
origin: [REVIEW-F10]
files:
  - path: engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java
    lines: "30, 35, 58-60, 74-76"
evidence: |
  IArguments.getInt 委托 getLong（Number 检查 + checkFiniteNum + Number.longValue）后 l2i；1.5→1 不报错；
  cct-run 直接复用 CC:T 默认实现。动态实测：getItemDetail(1.5) 返回槽 1 的 table，不报错；"1" 报 number expected。
impact: |
  用户可能意外 1.5 静默取槽 1，但与游戏一致。
fix: |
  保持现状；补小数参数测试并在 guide/handoff 说明截断行为。
tests: |
  无小数参数用例。
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  保留上游 IArguments.getInt 小数截断；新增 PeripheralTest.fractionalInventorySlotUsesTheUpstreamIntegerConversion，并在 guide/handoff 区分方法参数与 exact-integer fixture。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## ENG-FX-09 — ItemStack.count 线程约束未文档化（当前无竞争）

```yaml
id: ENG-FX-09
title: "ItemStack.count 非 volatile、线程约束未文档化；当前线程模型下无竞争"
severity: low
status: fixed
confidence: confirmed
module: engine-fixture
origin: [REVIEW-F7, REVIEW-NEW-5]
files:
  - path: engine/src/main/java/cctrun/engine/peripheral/ItemStack.java
    lines: "12-15"
  - path: engine/src/main/java/cctrun/engine/peripheral/Inventory.java
    lines: "29-31"
  - path: engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java
    lines: "32-33"
evidence: |
  count 非 volatile；move 在 Inventory 锁内改、getItemDetail 在锁外读；但 InventoryPeripheral 全部 method(true) 经
  executeMainThreadTask 由 mainThread.tick() 在 Runner.loop 同一线程执行，apply 也在该线程，
  javap 证实 CC:T 1.120.2 MainThread.tick() 在调用线程内执行任务 => 当前无数据竞争。
impact: |
  现状安全；若将来新增非 main-thread 方法或改线程模型即成真竞争，且无注释提醒。
fix: |
  在 Inventory/ItemStack 注明所有访问必须在主线程，或统一持锁/volatile。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  Inventory 与 ItemStack Javadoc 明确主线程访问约束，未因已驳回的竞争假设增加无关锁。PeripheralTest 回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

