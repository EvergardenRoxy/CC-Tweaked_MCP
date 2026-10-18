# 核对任务 A：engine 运行时 lane

基线：D:\AI\DSH\cc-t\CC-Tweaked_MCP，git HEAD = bf68ac4（v0.2.3，即审查对象）。只读核对，**不要修改仓库文件**。
产物：把结果写到 `D:\AI\DSH\cc-t\CC-Tweaked_MCP\issues_to_fix\evidence\lanes\A-engine-runtime.md`（表格 + 偏差说明）。
每条结论用：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附验证方法）/ 降级。证据必须给 文件:行号 + 关键代码片段（≤2 行）或命令输出。

## 待核对条目（编号即最终表编号）

| ID | 级别 | 审查位置 | 审查意见摘要 | 核对要点 |
|---|---|---|---|---|
| H1 | 高·疑点 | engine/src/main/resources/cctrun/harness.lua:13-14；engine/src/main/java/cctrun/engine/HarnessApi.java:49 | `__cctrun` 仅靠从 `_G` 删除隐藏；Cobalt debug 库对受测程序完整可用（getlocal/setupvalue/getregistry 等）；完整伪造链（程序→registry→主线程→debug.getlocal→report）未走通，GlobalRegistry 不持有主线程；建议加一次性 token。 | 核对 harness.lua 保护方式、HarnessApi.done 的权重、程序能否经 env/协程/registry 触到 harness 局部量；检查 Cobalt/CC:T jar 中 debug API 装载证据（javap/strings）。动态探测由 Lead 执行，标注"待 Lead 探测结果"。给出可实施修复建议。 |
| H2 | 高 | Runner.java:56-58,134 | fs 写入不受 DISK_CAPACITY 限制，可写爆 1 GB 堆；OOM 归类靠错误文案前缀，无法区分 fs 写爆与真实 VM OOM；无写入量统计。 | 核对 MemoryMount/根文件系统实现（engine 内 RootMount、CC:T jar）、DISK_CAPACITY 只用于 getFreeSpace、OOM 判定链。Lead 会做动态写入测试。 |
| E1 | 中 | RootMount.java:29-44 | rename 用字符串前缀判断"目录移入自身"：真 inside-self 直接委托 MemoryMount.rename（是否拒绝未验证）；/a→/ab 误判后靠两步改名碰巧收敛。 | 核对代码分支；尽量从 CC:T 1.120.2 jar/源码确认 `AbstractInMemoryMount.rename` / `FileSystem.move` 对 dest 在 source 内的行为（javap 或 Gradle 缓存中的 sources jar）。Lead 会做动态 fs.move 测试。 |
| E2 | 中 | Runner.java:250-255 | tick 循环被 InterruptedException 打断时返回 "timeout"，环境中断与程序超时共用状态，exit_code=2 失真。 | 核对代码及 summary 字段；确认无独立 interrupted 状态。 |
| E3 | 中 | Runner.java:146-158 | context.close(50ms) 不等待计算机线程退出，仅记 note；"一 run 一 JVM" 下无碍，作为库嵌入时泄漏线程。 | 核对 javadoc 的 one-run-per-JVM 声明与 CLI 实际调用方式（cli/McpServer 是否每 run 一个子 JVM），判断严重度。 |
| E4 | 中 | harness.lua:33-35 | fs.open(path,"r") 无 nil 检查，程序文件缺失时报 `attempt to index ? (a nil value)`，错误不指向真实原因。 | 核对代码；确认 Runner 是否总在启动前写入程序文件（触发面），给出结论"确认但当前不可触发/需条件"。 |
| L-Cfg | 低 | Runner.java:78-86 | CoreConfig 进程级静态跨 run 残留，无代码防线。 | 核对静态字段写入与 one-run-per-JVM 约束。 |
| L-Ovf | 低 | Runner.java:183 | maxTicks = timeoutSeconds * 20 为 int 乘法可溢出；CLI 已钳制 --timeout ∈ [1,3600]（Main.java:133），仅库调用面成立。 | 核对 RunSpec 校验与 CLI 钳制行号；确认 report 的降级是否成立。 |
| L-Oom | 低 | Runner.java:55,134 | OOM 归类硬编码 Cobalt 文案 "vm error: java.lang.OutOfMemoryError"，改文案即静默失配。 | 核对常量与 contains 匹配。 |
| L-Close | 低 | Trace.java:60-62 | close 后 record 静默丢弃，无计数无日志。 | 核对 close/record 与 notes_dropped 的对比。 |
| L-Flush | 低 | Trace.java:73 | 每条记录 flush 一次，I/O 放大（有意权衡）。 | 核对代码与注释动机。 |
| L-TQ | 低 | TraceQuery.java:66,111 | `record.get("tick").getAsInt()` 假定字段存在，缺失即 NPE 且不计 skipped；半写行容错只覆盖 JSON 解析。 | 核对 read()/matches()/screenAt() 容错范围。 |
| L-Name | 低 | Runner.java:441-445 | storedName 对 ".."/空名/Windows 保留名不设防（仅 API 健壮性）。 | 核对代码与输入来源。 |
| L-Clock | 低 | Clock.java:19-22 | millis() 实时取 nanoTime，与 loop 线程的 tick 组合有亚毫秒错位。 | 核对 Clock 实现与多线程 record 调用点。 |
| L-Big | 低 | Runner.java:76-176,183-268 | run()/loop() 巨型方法与魔法数（START_DELAY+5 等），反射失败静默吞掉。 | 核对方法与魔法数、rebootPending 启发式。 |

## 额外要求
1. 用 `git rev-parse HEAD` 记录版本；如发现行号与审查报告不一致，明确指出（表格加"实际行号"列）。
2. 对 H1 额外核对这些事实：CC:T core 1.120.2 有无 DebugApi；Cobalt 0.9.9 的 DebugLib 是否被 CobaltLuaMachine 装载（找 `debugGlobals` 调用）；GlobalRegistry 是否含主线程引用。给出 jar 路径与 javap/strings 证据。
3. 对 E1 若无法从 jar 确认 MemoryMount.rename 行为，标"无法静态验证（需动态）"，不要猜。
4. 最后给"偏差/修正"小节：审查报告哪些描述不准确、级别应升降、行号漂移。
