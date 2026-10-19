# A/T 修复矩阵

日期：2026-10-19。仅列已授权 A/T 63 项；每个提交均真实存在且是 1019fix 的祖先。`fixed_in` 是包含完整修复的提交，不表示该提交只修改一个 issue。原始证据保留在模块 issue 文件；当前验证边界见 [README](README.md)。测试执行数只取 [verification-verified.json](verification-verified.json)，真实服务器记录取两个 `*-live-verified.json`，包冒烟取 [smoke-linux-verified.txt](smoke-linux-verified.txt)。CI 实际运行结果另记。

| ID | 批次 | fixed_in | 修复与验证 |
|---|---|---|---|
| ENG-RT-01 | T1 | `be72dd0` | RootMount 对创建/增长/追加/seek/截断/删除/改名及打开句柄实施容量配额，summary.disk 区分 free 与累计写入；VmFailures 在 Lua 文案转换前观察真实 Java OOM。RootMountTest、MainTest.heapExhaustionIsItsOwnStatus、InputSafetyTest 的伪造错误回归通过；删除句柄差异见 DV17。 |
| ENG-RT-02 | T1 | `be72dd0` | harness 提前捕获收尾函数并保护调用，完成报告需 token 且只接受一次；OOM 不再按文案分类。InputSafetyTest 的 globals 篡改/伪造 OOM/重复报告测试通过。保持完整 debug，未声称恶意程序隔离。 |
| ENG-RT-03 | A2 | `be72dd0` | 保留 timeout/exit 2，附 termination_reason=interrupted 和 run_interrupted；写完 summary/state 后恢复线程 interrupt。InputSafetyTest.interruptedRunWritesItsCauseAndRestoresTheInterruptFlag 通过。 |
| ENG-RT-04 | A6 | `e5f721e` | Runner Javadoc 明示每 JVM 一次 run、close(50ms) 后线程仍可能收尾，嵌入调用不提供隔离；保留已有 CLI/MCP 进程模型。文档核对通过。 |
| ENG-RT-05 | T1 | `be72dd0` | harness 对 fs.open 返回 nil 提供 setup 错误并等待 runner 收尾，避免索引 nil 后退回 shell 超时。代码核对；Runner 正常/语法/运行时回归通过。 |
| ENG-RT-06 | A6 | `e5f721e` | Runner Javadoc 与 limitations 记录 CoreConfig 进程级副作用及不支持重复/并发嵌入隔离；不改变一 run 一 JVM 的既有决定。 |
| ENG-RT-07 | A2 | `be72dd0` | RunSpec 限定 timeoutSeconds 为 1..3600，防止 ticks 乘法溢出。InputSafetyTest 拒绝 Integer.MAX_VALUE；既有 timeout 回归通过。 |
| ENG-RT-10 | A2 | `be72dd0` | TraceQuery 在过滤/回放前校验字段类型、尺寸和行号，坏对象与坏 JSON 统一计 skipped。InputSafetyTest.missingAndWrongTraceFieldsDoNotAbortQueriesOrReplay 通过。 |
| ENG-RT-11 | A2 | `be72dd0` | RunSpec 按宿主 Path 检查有效末段，拒绝空白、根路径、点与点点；未引入被审查驳回的 Windows 保留名假设。InputSafetyTest 边界通过。 |
| ENG-RT-15 | T1 | `be72dd0` | RecordingMachine 观察保护调用外 OOM 与非正常 MachineResult，Runner 统一归因并保留原始错误通道；类型 OOM、语法/运行时/无 yield/关机重启回归通过。 |
| ENG-RT-16 | A2 | `be72dd0` | UUID 随机空间与原子 createDirectory/冲突重试结合；InputSafetyTest 确定性制造第一次同名并验证不覆盖。 |
| ENG-RT-17 | T6 | `364dd5c` | 原子 state.json 和父子进程精确 reservation，记录 pid/start identity；取消确认子进程终止后补 failed/cancelled。RunStateTest 与 McpServerTest 通过。外部强杀可能残留最后已知 running，不保证 summary。 |
| ENG-FX-01 | A2 | `be72dd0` | fixture 校验与构建共用 inventory/energy 默认常量，精确数值解析异常带 JSON 路径；FixtureTest 的 timeline 槽位/容量与 InputSafetyTest 边界通过。 |
| ENG-FX-02 | A1 | `be72dd0` | JsonLimits 迭代限制输入深度/节点/字节，在 deepCopy、宽字符检查和 toLua 递归前拒绝。InputSafetyTest 覆盖 20000 层拒绝和合法边界；不复用展示深度 4。 |
| ENG-FX-03 | A2 | `be72dd0` | BigDecimal.longValueExact 校验整数和 long 范围，两阶段复用同一解析规则。InputSafetyTest 拒绝 1e999、Long.MAX+1、小数/负值并接受 Long.MAX。 |
| ENG-FX-04 | A2 | `be72dd0` | timeline 稳定排序、tick 循环用迭代器推进，同 tick 原顺序保留；超出 timeout 的动作给有界 note，整体输入节点上限约束规模。顺序与既有能量/流体动作回归通过。 |
| ENG-FX-06 | A6 | `e5f721e` | JsonValues Javadoc、guide、handoff TRACE_FORMAT 明确 double、大整数精度、非有限数字符串、字符串/容器预览边界；mod JsonValuesTest 同步验证字节与非有限值语义。 |
| ENG-FX-08 | A6 | `e5f721e` | 保留上游 IArguments.getInt 小数截断；新增 PeripheralTest.fractionalInventorySlotUsesTheUpstreamIntegerConversion，并在 guide/handoff 区分方法参数与 exact-integer fixture。 |
| ENG-FX-09 | A6 | `e5f721e` | Inventory 与 ItemStack Javadoc 明确主线程访问约束，未因已驳回的竞争假设增加无关锁。PeripheralTest 回归通过。 |
| CLI-02 | A1 | `364dd5c` | ProtocolReader 按字节有界读行并排空超限行；深度/节点在递归使用前验证，致命 framing OOM 走独立 stderr/exit 4 边界。ProtocolReaderTest 和 McpServerTest 通过。 |
| CLI-03 | A1 | `364dd5c` | JSON-RPC id 限 string/number/null，拒绝深层对象/数组/布尔且不回显；McpServerTest 验证 20000 层 id 后同一服务仍能 ping。 |
| CLI-04 | A3 | `364dd5c` | Call 在提交前绑定 Future，取消时 interrupt live 调用并杀 child，处理取消/启动交错；LiveToolsTest 的在途 wait 取消及 McpServerTest 子进程/状态测试通过。默认 EOF drain 保留。 |
| CLI-06 | A6 | `e5f721e` | 无 id 的 tools/call 保持忽略但输出 stderr 诊断；其他既有协议约定保持。握手/工具/通知回归及 zip mcp-stdio 冒烟通过。 |
| CLI-10 | A3 | `364dd5c` | ToolError.Kind 区分 TIMEOUT/CANCELLED/OTHER，wait 不再用错误文案子串判断。端点超时/500/取消回归通过。 |
| CLI-12 | T5 | `364dd5c` | 新增真实 Java 8 launcher 子进程切换/69、深层 id、500/坏 discovery、在途取消和并发 run 测试。endpoint 文件解析覆盖补齐；CLI-01 的外部 URL 策略仍按 D 类延期。 |
| CLI-13 | A1 | `364dd5c` | Main 的 mcp OOM 路径仅 stderr 和 exit 4；McpServerTest 在受限堆子 JVM 验证协议 stdout 无 run summary。 |
| CLI-14 | A3 | `364dd5c` | RejectedExecutionException 清理 active、取消任务并回 -32603；active 使用 putIfAbsent/remove(key,call) 避免请求交错误删。McpServerTest.rejectedSubmissionDoesNotLeaveActiveCalls 通过。 |
| CLI-15 | A3 | `364dd5c` | 墙钟强杀后保留并返回 stderr 最后最多 8 KiB，过滤 inline 临时路径，再清理重定向文件。代码核对通过；未人为卡死 JVM 等满 120 秒宽限，验证边界在本轮 README 明示。 |
| CLI-17 | A3 | `364dd5c` | 动作基线采集失败用不可用 sentinel 替代旧值，wait 给 baseline_warning，不把旧文字当新匹配；unchanged=true 可显式接受。LiveToolsTest 对应回归通过。 |
| MOD-01 | T2 | `4faa031` | kinds 只保留双段窗口，first_seq 后 long 运算再安全索引；counts/firstAfter 不含轮转丢弃段。RecorderSafetyTest 窗口压力与真实双版本轮转通过。 |
| MOD-04 | T4 | `4faa031` | CcInternals 集中可选反射字段与类型检查，缺失能力诊断后降级，不抛静态初始化错误；精确版本 pin 保留。故意缺失字段测试及两版真实 terminal/wired 能力通过。 |
| MOD-05 | T4 | `fbfd68a` | 补 HTTP auth/Host/慢 body、Trace/Ring/JsonValues/Screens/快照等纯 JUnit，真实双版本验证 413、接管/租约、回放及 endpoint 清理。当前覆盖与平台边界见两份 final JSON，不虚报 Windows/macOS。 |
| MOD-06 | A4 | `4faa031` | HttpPolicy 对缺 Host 与非 loopback Host 拒绝，Bearer 常量时间比较；单测和两版真实 HTTP/1.0 缺 Host/401/403 通过。 |
| MOD-07 | A4 | `4faa031` | POSIX token 创建即 0600，已有文件权限收紧；Windows 不调用 POSIX API。RecorderSafetyTest 与两版 Linux 真实权限检查通过。 |
| MOD-08 | T4 | `fbfd68a` | 每个请求独立 body 5 秒总期限，关闭 exchange 回收慢读，超限请求在同期限内有界排空；不设全局 JVM HTTP 参数。真实 socket 单 worker 回归及两版服务恢复通过。 |
| MOD-09 | T2 | `4faa031` | recordAt 在锁内流式读避免整个 trace 深拷贝；分页只做一条 lookahead 即停止，matched_at_least 明示下界，summary 才精确计数。轮转/读取回归与游戏 trace/回放通过。 |
| MOD-10 | A4 | `4faa031` | 内部失败返回泛化信息，日志留详细诊断；snapshot list 对坏条目单独 skipped 并继续列有效项。两版坏 manifest 与有效快照混合验证通过。 |
| MOD-11 | T4 | `4faa031` | 核实 Lua 字符串每字符一字节，不错误改成 UTF-8 长度；文档记录 double/预览语义；trace 只序列化实际写入行，去掉重复长度估算。JsonValuesTest/Utf8Test 与双版本记录通过。 |
| MOD-12 | A4 | `4faa031` | session 名用毫秒+UUID，首次 attach 排他创建，不破坏 dormant；endpoint 原子发布、仅清理 dead pid 或 owned session。纯文件测试、首轮 dormant 与最终关闭清理通过。 |
| MOD-15 | T2 | `2651495` | recordAt 使用 TraceRecords 容错验证，坏行不再导致 status match 500；RecorderSafetyTest、TraceRecords/replay 回归与真实 trace 检查通过。 |
| MOD-16 | T2 | `4faa031` | screen_hash 使用包括尺寸/文字/fg/bg 的 SHA-256；seq/tick 经 HTTP/MCP 全路径保留 long。digest 单测、Long.MAX_VALUE 参数回归和实机调用通过。 |
| MOD-17 | T2 | `4faa031` | 锁内复制不可变磁盘读快照，读完清理；完整 write 后才提交 seq/index，失败截断、暴露 trace_health、退避重试并补 trace_error。注入部分写入/并发轮转单测和两版压力通过；Windows 未实测。 |
| MOD-19 | T3 | `4faa031` | 先复制并按实际复制内容生成 hash/校验 manifest，再 old->backup/stage->target 可恢复切换；失败保留旧副本，list 恢复中断切换。SnapshotStoreTest 与两版 mount save/diff/restore 通过。 |
| MOD-21 | T2 | `4faa031` | record 选 ring/attachment 与 attach drain/publish 共用 ring monitor，包含已在途写线程。NeoForge recording 真实 handover 连续事件验证通过；Ring 容量/排空回归通过。 |
| MOD-22 | T4 | `4faa031` | registry 消失统一报告 unavailable/unloaded，避免误称电脑已破坏；reload 不自动恢复 AI 控制。两版真实 forceload 卸载/重载与 detach reason 验证通过。 |
| MOD-23 | T2 | `4faa031` | 外设调用 id 来自 session 级原子计数，跨 VM reboot 保持唯一；两版真实两次 reboot 后 trace call ids 去重检查通过。 |
| MOD-24 | A4 | `4faa031` | PeripheralNames 对不同目标重名报歧义，对语义相等的同物理外设 wrapper 保留合法别名。纯单测与两电脑共享 wired modem 的实机 chest 查询通过。 |
| BLD-01 | T5 | `6999b2b` | CI 解压并冒烟同次测试生成的 CLI zip，接 wrapper validation/dependency review/CodeQL/Dependabot，badge 指 main。本地全构建、zip smoke、actionlint 通过；GitHub 实际运行另记。 实际冷缓存 CI 与 SARIF 产物已核对（ci-results-identity.json）。 |
| BLD-02 | A5 | `0968269` | Gradle 从唯一 Versions.TOOL 读取版本，test 比较运行/构建版本，release 在下载资产前验证 tag。匹配 v0.2.3 通过，v0.2.4 拒绝；未推 tag。 |
| BLD-03 | T5 | `0968269` | release 下载同 run 已测 zip/jar，先核对已有同名资产 hash，再上传缺失文件；不重新构建或 clobber。发布 helper 幂等/不匹配单测与 prepare-only 通过，未正式发布。 |
| BLD-04 | T5 | `6999b2b` | 新增 dependency-review、CodeQL 和 Dependabot，wrapper/actions/依赖校验均固定；真实全模块 build/开发服务器 strict 校验及 actionlint 通过。元数据不是安全审计。 实际冷缓存 CI 与 SARIF 产物已核对（ci-results-identity.json）。 |
| BLD-05 | A5 | `4faa031` | 两份 mod 脚本的 gson/junit/CC:T 引用 catalog；仅测试 scope 对齐，不声称改变游戏实际 Gson。两版完整 build/test 与真实服务器通过。 |
| BLD-07 | A5 | `0968269` | text=auto，脚本/Java/Lua/JSON/TOML/mcmeta 明确 LF，bat 保留 CRLF。验证 zip shell LF/权限和两版 pack.mcmeta LF；不声称 jar 已完全可复现。 |
| BLD-08 | A5 | `0968269` | sh/PS1 smoke 默认报告写临时目录并可显式指定，加入忽略规则；最终 zip smoke 使用专门证据路径，普通运行不污染仓库根。 |
| BLD-09 | A5 | `e5f721e` | push 仅 main/v*，PR 面向 main，保留手动触发，消除旧分支 push/PR 重复，badge 回 main。actionlint/配置核对通过。 |
| BLD-10 | T5 | `0968269` | wrapper 使用 Gradle 官方发行 SHA-256，actions 使用经 GitHub API 核实的提交 SHA，CI 做 wrapper validation。实际 strict 构建与 actionlint 通过。 |
| BLD-11 | T5 | `0968269` | 精确三份版本文件名，SHA256SUMS 覆盖产物/verification；已有资产先校验，不同字节拒绝覆盖。prepare-only 清单逐项复核、helper 单测通过；密码签名和正式 tag 发布未执行。 |
| BLD-14 | A5 | `0968269` | 版本单源+tag gate 消除新 tag 携带旧版本资产的路径，未擅定新版本号或重推历史 tag。匹配/不匹配门禁验证通过。 |
| BLD-15 | T5 | `0968269` | 两版显式 JDK 21 构建工具链，Forge release17、NeoForge release21，移除未用 mod cobalt compileOnly；双版完整构建/开发服务器及 class major 检查通过。 |
| DOC-01 | A6 | `e5f721e` | scripts/test_summary.py 从实际四模块 XML 生成机器报告，README/handoff 指向该源，test-results 明确为历史，zip 携带 validation/verification.json。包内报告与源报告比对通过。 |
| DOC-02 | A6 | `e5f721e` | docs/limitations.md 作为用户限制单源，README/building 指向它；handoff 保留历史与差异账本并引用当前用户文档。zip 中包含新文件。 |
| DOC-04 | A6 | `e5f721e` | README 安装命令与 docs/install 的 macOS 命令旁就地标注尚未实测，limitations/测试说明保持相同边界。静态文档/包内容核对通过。 |
| DOC-05 | A6 | `e5f721e` | 以实际 JUnit XML -> verification.json 为唯一当前计数源，记录 commit/dirty/report time/失败与跳过，缺模块明确 unverified；CI 汇总，其他文档不再手工同步数字。 |
