# cli issues（cct-run 命令与本地 stdio MCP 服务器）

基线：`bf68ac4`（项目版本 0.2.3）。字段定义见 `README.md`；保留原始核对条目，当前修复状态以 status/fixed_in 字段为准。
来源：本次审查（REVIEW-*）+ 旧 fix plan（LEGACY-*）。

---

## CLI-01 — endpoint.json 的 URL 未校验即发送 Bearer 令牌

```yaml
id: CLI-01
title: "endpoint.json 的 url 未校验 scheme/host，Bearer 令牌会发往任意主机"
severity: medium
status: open
confidence: confirmed
module: cli
origin: [REVIEW-C1, LEGACY-L-endpoint-token]
files:
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "784-809, 832"
evidence: |
  803-805 读 <game dir>/cct-live/endpoint.json 的 url 后直接 URI.create（只补尾部 /），无 scheme/host/port 校验；
  832 无条件加 Authorization: Bearer <token.txt>。mod 侧已强制 127.0.0.1 绑定 + Host 校验
  （LiveHttp.java:79/735，LiveSession.java:257 写死 127.0.0.1），故当前安全性完全依赖 mod 侧，CLI 无第二道门。
impact: |
  能改游戏目录下 endpoint.json 的本机程序可把令牌发往任意地址（同权限攻击面）；游戏目录被同步/备份工具带到他处时风险放大。
fix: |
  endpoint() 校验 scheme=http 且 host ∈ {127.0.0.1, localhost, [::1]}，否则 ToolError；保留现有错误信息风格。
tests: |
  LiveToolsTest 补外网 URL 用例（伪造 endpoint.json → 明确报错且不发请求）。
```

## CLI-02 — stdin 无行长上限、serve() 不捕获 Error

```yaml
id: CLI-02
title: "stdin 单行无长度上限，读循环只捕获 JsonParseException/RuntimeException；255 层上限论不成立"
severity: low
status: fixed
confidence: partial
module: cli
origin: [REVIEW-C2]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "100-128"
evidence: |
  103 reader.readLine() 无上限；109/119 只捕 JsonParseException 与 RuntimeException，Error 逃逸。
  实测：Gson 2.11.0 无 255 层上限、解析是迭代的（GsonDepth 直测 1,000,000 层 OK；MCP 2,000,065 B 深嵌套行返回正常 tools/list）；
  解析期 OOM 会被 Gson 包成 JsonParseException 回 -32700，服务不死。故报告「SOE 打死读循环」的路径不成立；
  真实 SOE 见 CLI-03（响应序列化）。
impact: |
  超大单行在 readLine 阶段仍有 OOM 风险；服务端未兜底 Error。
fix: |
  serve() 外层 catch Throwable 并回 -32603 / 继续服务；可选限制行长度（如 16-64 MiB）。
tests: |
  McpServerTest 已覆盖 not json/-32700、method 非字符串/-32600；补超长行用例。
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  ProtocolReader 按字节有界读行并排空超限行；深度/节点在递归使用前验证，致命 framing OOM 走独立 stderr/exit 4 边界。ProtocolReaderTest 和 McpServerTest 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-1：ProtocolReader 按字节限制行，JsonLimits 有深度/节点限额；不重开旧无上限问题。本轮 boolean id 被拒绝后下一条 ping 仍成功，原始响应见 observations.json。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## CLI-03 — 深嵌套 JSON-RPC id 在 send() 序列化时 SOE 打死 MCP 服务

```yaml
id: CLI-03
title: "请求 id 为深嵌套数组时 send()/Gson 递归写 JsonElement 触发 StackOverflowError，进程 exit 1"
severity: medium
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-NEW-1]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "159-162, 389-396"
  - path: cli/src/main/java/cctrun/cli/Main.java
    lines: "100-107"
evidence: |
  Gson 2.11.0 的 JsonElement 写出是递归的（TypeAdapters$28.write）；请求 id 为 20000 层嵌套数组时
  send()/COMPACT.toJson 抛 StackOverflowError，实测 cct-run mcp 进程 exit 1，stdout 只有 initialize 应答。
  读循环/回复路径均未捕获 Error。
impact: |
  异常客户端可崩溃本地 MCP 服务（客户端本可终止进程，实际风险有限，但违反服务器健壮性预期）。
fix: |
  serve() 外层 catch Throwable；id 只允许 string/number/null（JSON-RPC 2.0 规范已限定）。
tests: |
  补深嵌套 id 用例。
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  JSON-RPC id 限 string/number/null，拒绝深层对象/数组/布尔且不回显；McpServerTest 验证 20000 层 id 后同一服务仍能 ping。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## CLI-04 — cancelled 只杀子 JVM，live 工具无人中断

```yaml
id: CLI-04
title: "notifications/cancelled 只杀 cct_run 子进程；live 工具无进程可杀且没有线程 interrupt"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-C3]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "145-168, 433-447"
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "537-542"
evidence: |
  cancel() 仅置 cancelled 标志并 destroyForcibly（443-446）；live 调用不传 Call（:215），Call 也不保存 Thread/Future。
  LiveTools 三处 catch(InterruptedException) 本身可响应中断，但无人 interrupt；结果返回后 :159 再按 cancelled 丢弃响应。
impact: |
  客户端取消后 live 工具仍把轮询预算跑满（最长 120s），白耗 HTTP 请求；无资源泄漏。
fix: |
  Call 记录 Future/Thread，cancel 时 interrupt()；wait/collect 已有 InterruptedException 分支可复用。
tests: |
  McpServerTest.cancelStopsARunningChild 只覆盖 cct_run。
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  Call 在提交前绑定 Future，取消时 interrupt live 调用并杀 child，处理取消/启动交错；LiveToolsTest 的在途 wait 取消及 McpServerTest 子进程/状态测试通过。默认 EOF drain 保留。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-6：McpServer.Call 516-535 绑定 Future 并 cancel(true)，LiveToolsTest 114-134 有停止后续轮询且服务继续响应的回归。本轮确认实现与测试存在，不把旧测试报告冒充新执行。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## CLI-05 — JavaFinder 信任 release 文件并取最高版本

```yaml
id: CLI-05
title: "JavaFinder 信任 release 的 JAVA_VERSION 且按最高版本选择，可被伪造 home 劫持 re-exec"
severity: low
status: open
confidence: confirmed
module: cli
origin: [REVIEW-C4]
files:
  - path: cli/src/launcher/java/cctrun/cli/JavaFinder.java
    lines: "73-90, 125-131"
  - path: cli/src/launcher/java/cctrun/cli/Launcher.java
    lines: "66-76"
evidence: |
  80-83 只解析 release 的 JAVA_VERSION，从不执行 java -version；128 按 major 取最高；
  Launcher 用 best.java() 重新执行。利用前提是能写 JAVA_HOME/PATH/常见安装目录（同权限本地场景），
  不是远程漏洞；不执行候选二进制做探测这一点做得对。
impact: |
  同权限攻击者可劫持 Launcher 重新执行的 Java。
fix: |
  校验可执行文件实际版本/厂商，或只信任显式配置/注册表；把「最高版本」改为 PATH 顺序优先。
tests: |
  JavaFinderTest 4 例，不覆盖伪造 home。
```

## CLI-06 — JSON-RPC 小偏差（无 id 的 tools/call 静默丢弃等）

```yaml
id: CLI-06
title: "JSON-RPC 2.0 总体符合，但 tools/call 无 id 静默丢弃、带 id 的 notifications 被忽略、未知工具用 -32602"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-L5]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "107-123, 135-172, 389-407"
evidence: |
  错误码 -32700/-32600/-32601 正确，id 原样回显，通知不回复；tools/call 无 id 时 :147 直接 return（不执行不回复）；
  带 id 的 notifications/* 静默丢弃；未知工具经 jsonrpcError 回 -32602（:160/:216-218）。
impact: |
  客户端漏发 id 时排障无迹可寻；协议边角不完全一致。
fix: |
  至少对无 id 的 tools/call 打一行 stderr；可选微调错误码。
tests: |
  McpServerTest.handshakeAndToolList。
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  无 id 的 tools/call 保持忽略但输出 stderr 诊断；其他既有协议约定保持。握手/工具/通知回归及 zip mcp-stdio 冒烟通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## CLI-07 — 声明支持 2025-03-26 但拒绝批量

```yaml
id: CLI-07
title: "PROTOCOL_VERSIONS 含 2025-03-26（允许批量），但数组消息统一回 -32600"
severity: low
status: open
confidence: confirmed
module: cli
origin: [REVIEW-L6]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "46, 113-115"
evidence: |
  46 版本列表含 2025-03-26；113-115 任何 JSON 数组只回单个 -32600，无批量处理分支。2025-06-18+ 已移除批量，故对最新版正确。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-7：主分支 47/115-123/217 仍可协商 2025-03-26 后拒绝数组；本轮探针确认 -32600。该版本规范 basic/index.mdx 的 Batching 段明确 MUST receive batches，故仅补文档声明不支持不能满足所宣称版本；应移除不完整版本或实现其接收行为。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
impact: |
  协商 2025-03-26 的客户端发批量会收到 -32600。
fix: |
  移除无法完整实现的旧协议版本，或按协商版本实现其要求的批量接收；只在文档中声明“不支持批量”不足以满足 2025-03-26。
tests: |
  none
```

## CLI-08 — EOF 后等待 2 小时且不取消在途调用

```yaml
id: CLI-08
title: "stdin EOF 后 awaitTermination(2h)，不取消在途调用"
severity: low
status: open
confidence: confirmed
module: cli
origin: [REVIEW-L7]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "125-126"
evidence: |
  125-126 仅 pool.shutdown() 与 awaitTermination(2, HOURS)；子 JVM 上限 timeout+120s（:48/:248）小于 2h，不会遗留，但客户端已断开时子进程仍跑完。
impact: |
  客户端崩溃/退出后子 JVM 继续消耗资源直到自身 wall limit。
fix: |
  EOF 时 active.values().forEach(Call::cancel)。
tests: |
  none
```

## CLI-09 — 错误消息回传 endpoint URL（非令牌）

```yaml
id: CLI-09
title: "连接失败/超时错误消息把 endpoint URL（含端口）返回给 LLM；令牌不泄漏"
severity: info
status: open
confidence: confirmed
module: cli
origin: [REVIEW-L10]
files:
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "832, 835, 837"
evidence: |
  835/837 只拼 endpoint.base() 与端口；token 全文件仅出现在 Authorization 头（:832），错误消息不含 token；端口不是秘密。
impact: |
  无实质风险，仅最小暴露原则。
fix: |
  可选只说 "cct-live (endpoint.json)" 不带 URL。
tests: |
  LiveToolsTest.explainsWhatIsMissing。
```

## CLI-10 — wait 超时分类依赖错误文案子串

```yaml
id: CLI-10
title: "LiveTools.isUnanswered 用 contains('did not answer in time') 判定端点超时，改文案即静默破坏"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-L11, LEGACY-B6]
files:
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "500-501, 564-566"
evidence: |
  500-501 每轮 min(5000, max(remaining,1000))ms；564-566 用错误文案子串判定「端点未响应」；MIN 1000ms 下限使最后一轮可能略超剩余预算。
impact: |
  文案调整会静默破坏 wait 的 met=timeout 分类。
fix: |
  ToolError 带类型/枚举，替代字符串匹配。
tests: |
  LiveToolsTest.waitEndsWhenTheEndpointStopsAnswering。
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  ToolError.Kind 区分 TIMEOUT/CANCELLED/OTHER，wait 不再用错误文案子串判断。端点超时/500/取消回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## CLI-11 — 参数校验双实现且严格度不一致

```yaml
id: CLI-11
title: "McpTools 与 LiveTools 各有一份参数校验，string() 严格度不同；工具描述硬编码版本"
severity: low
status: open
confidence: partial
module: cli
origin: [REVIEW-L14]
files:
  - path: cli/src/main/java/cctrun/cli/McpTools.java
    lines: "33, 267-272"
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "886-891"
evidence: |
  McpTools.string 要求 JSON string（267-272）；LiveTools.string 接受任意 primitive 并 getAsString()（886-891）；
  审查引用的 McpTools.java:866-871 不存在（文件 322 行）；工具描述硬编码 "1.120.2/1.20.1" 仅 McpTools.java:33。
  2026-10-20 六项约定范围交付（c94928bceb451e9a9ce444f5faf0b51a64a0d389）：
  工具描述由 Versions.CC_TWEAKED/MINECRAFT 格式化；handshake/tools/list 回归验证描述与版本一致。参数校验去重和严格度统一未实施，保留现有转换行为，整项保持 open。
  本轮证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
impact: |
  同一语义参数在不同工具上接受度不同；升级 CC:T 后描述静默过期。
fix: |
  抽公共 Args 工具类；工具描述改用 Versions.* 动态格式化。
tests: |
  none
```

## CLI-12 — 测试盲区

```yaml
id: CLI-12
title: "cli 30 个 @Test 的真实盲区：Launcher、endpoint 伪造、深嵌套 id、500 响应、并发交错"
severity: low
status: fixed
confidence: partial
module: cli
origin: [REVIEW-L15]
files:
  - path: cli/src/test/java/cctrun/cli/McpServerTest.java
    lines: "63, 69, 94"
  - path: cli/src/test/java/cctrun/cli/LiveToolsTest.java
    lines: "296, 315"
evidence: |
  30 @Test=4+12+8+6 计数准确；已测 -32700/-32600/继续服务、401/404；
  真实盲区是 Launcher（整个类）、endpoint 伪造、深嵌套 id、500 响应、并发交错。
impact: |
  上述路径无回归保护（其中 endpoint 伪造/深嵌套 id 直接对应 CLI-01/CLI-03）。
fix: |
  补上述 5 类测试。
tests: |
  4 个测试类共 30 用例。
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  新增真实 Java 8 launcher 子进程切换/69、深层 id、500/坏 discovery、在途取消和并发 run 测试。endpoint 文件解析覆盖补齐；CLI-01 的外部 URL 策略仍按 D 类延期。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## CLI-13 — OOM 逃逸时把 summary JSON 写进 MCP 协议流

```yaml
id: CLI-13
title: "mcp 模式下 OOM 逃逸时 Main 会向 stdout 写 cct_run 摘要 JSON，污染 JSON-RPC 流"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-NEW-2]
files:
  - path: cli/src/main/java/cctrun/cli/Main.java
    lines: "100-102, 253-267"
evidence: |
  serve() 抛 OOM 时 Main.main 的 catch(OutOfMemoryError) 调 outOfMemory(stdout) 写摘要行后 exit 4；
  readLine 阶段 OOM 可逃逸（解析期 OOM 被 Gson 包装回 -32700，不会）。
impact: |
  MCP 协议流被非 JSON-RPC 行污染，客户端解析失败。
fix: |
  mcp 模式单独处理 Error，不向 stdout 写摘要。
tests: |
  none
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  Main 的 mcp OOM 路径仅 stderr 和 exit 4；McpServerTest 在受限堆子 JVM 验证协议 stdout 无 run summary。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## CLI-14 — pool.submit 失败时 active 条目泄漏

```yaml
id: CLI-14
title: "tools/call 先 active.put 再 pool.submit；RejectedExecutionException 时条目残留且错误码不对"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [LEGACY-L-active-leak]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "145-164"
evidence: |
  tools/call 分支先 active.put(id, call) 再 pool.submit，submit 无 try/catch；关闭竞态时条目不会 remove。
impact: |
  active 表残留、请求错误码变成 -32600（实际应 -32603），内存影响很小。
fix: |
  try/catch RejectedExecutionException，移除条目并回 -32603。
tests: |
  none
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  RejectedExecutionException 清理 active、取消任务并回 -32603；active 使用 putIfAbsent/remove(key,call) 避免请求交错误删。McpServerTest.rejectedSubmissionDoesNotLeaveActiveCalls 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-3（相关生命周期核对，不混为原 issue）：McpServer 176-178 用 putIfAbsent 拒绝在途同 id，191/198 用 remove(key, call)；原报告的覆盖旧 Call 路径已处理，不新建重复待修项。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## CLI-15 — 强杀后临时 stderr 被删除，超时结果缺现场

```yaml
id: CLI-15
title: "超时/强杀后 finally 无条件删除临时 stdout/stderr，结果只剩 wall time"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [LEGACY-L-kill-files]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "261-263, 272-279"
evidence: |
  超时分支只返回 did not finish within … s；finally 无条件删除临时 stdout/stderr 文件。
impact: |
  超时/崩溃时丢失子进程最后错误输出，排障信息减少。
fix: |
  杀进程后读取 stderr 末尾若干行并入工具结果，再删临时文件。
tests: |
  none
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  墙钟强杀后保留并返回 stderr 最后最多 8 KiB，过滤 inline 临时路径，再清理重定向文件。代码核对通过；未人为卡死 JVM 等满 120 秒宽限，验证边界在本轮 README 明示。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P1-1：McpServer 294-349 有强杀后的有界等待、best-effort 删除与最终子进程退出检查；删除失败不会覆盖原超时错误。Windows 文件锁场景未重跑，不能保证完全无临时残留。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## CLI-16 — pendingLua 无 TTL

```yaml
id: CLI-16
title: "pendingLua 无时间戳/TTL，超时 lua 状态长期累积"
severity: low
status: open
confidence: confirmed
module: cli
origin: [LEGACY-L-pending]
files:
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "43-44, 663, 673"
evidence: |
  pendingLua 是 ConcurrentHashMap 键集合，无时间戳；只在 collect 成功或 detach 时清理。
impact: |
  长时间会话多次 lua 超时会积累小状态，并影响临时文件 keep 集合。
fix: |
  lua 入口清理超过 10 分钟的 pending 项（带时间戳）。
tests: |
  可用可控时钟或跳过。
```

## CLI-17 — rememberScreens 失败时清空全部基线且无提示

```yaml
id: CLI-17
title: "rememberScreens 失败时清空该电脑全部屏幕基线，wait 可能接受旧变化"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [LEGACY-L-remember]
files:
  - path: cli/src/main/java/cctrun/cli/LiveTools.java
    lines: "577-592"
evidence: |
  catch 中 beforeAction.keySet().removeIf(k -> k.startsWith(id + "/")) 清空该电脑全部基线；返回结果无 warning 字段。
impact: |
  wait 可能把旧屏幕文字当作新变化接受。
fix: |
  失败时保留旧基线，或在结果加 warning: baseline unavailable。
tests: |
  none
fixed_in: 364dd5c5878156131b3ddce5dc1f10b2446b1b92
fixed_at: 2026-10-19
resolution: |
  动作基线采集失败用不可用 sentinel 替代旧值，wait 给 baseline_warning，不把旧文字当新匹配；unchanged=true 可显式接受。LiveToolsTest 对应回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-4：基线不可用已有明确 sentinel/warning，LiveToolsTest.unavailableActionBaselineNeverAcceptsStaleText 有回归。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## CLI-18 — MCP 接受非标准 JSON

```yaml
id: CLI-18
title: "MCP 使用宽松 JsonParser，未加引号的键和单引号字符串也会被接受"
severity: low
status: fixed
confidence: confirmed
module: cli
origin: [REVIEW-20261019-P3-2]
files:
  - path: cli/src/main/java/cctrun/cli/McpServer.java
    lines: "113-119"
evidence: |
  2026-10-20 核对 main 25a392102b1ce47c32743564298df0622b04e9ca：serve 使用 JsonParser.parseString，
  未启用严格 JSON 解析。本轮以该 main 构建的发行目录实测
  {jsonrpc:'2.0',id:1,method:'ping'} 得到 id=1/result={}，而不是解析错误。
  输入、响应与分发 jar hash 见 issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
impact: |
  非 JSON 文本被当作有效 JSON-RPC 请求，可能掩盖客户端格式错误；目前确认的是协议健壮性偏差，
  没有据此证明权限绕过或高危漏洞。与 CLI-02 的行长限制、CLI-03 的 id 深度问题不同。
fix: |
  在现有 Gson 版本上显式使用严格 JSON 解析，非法 JSON 返回 -32700；保留输入字节/深度/节点限制。
  不以升级 Gson 或捕获任意 Throwable 替代严格解析，不混入本轮文档核对。
tests: |
  后续覆盖未加引号的键、单引号字符串、注释、尾随内容被拒绝后同一服务仍可接受合法 ping。
fixed_in: c94928bceb451e9a9ce444f5faf0b51a64a0d389
fixed_at: 2026-10-20
resolution: |
  MCP 输入使用 Gson Strictness.STRICT 并验证完整输入；非法键/单引号/注释/尾随逗号/尾随内容回 -32700 后可继续处理合法 ping，literal null 回 -32600。已有深层 id、输入界限与 live/child 回归通过；没有升级 Gson。
  范围与本轮实测证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
```
