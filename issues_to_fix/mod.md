# mod issues（cct-live：Minecraft 游戏内 HTTP 服务）

基线：`bf68ac4`（v0.2.3；共享代码在 `mod/src/common/java/cctrun/live/`，平台层在 `mod/forge|neoforge/`）。
字段定义见 `README.md`；保留原始核对条目，当前修复状态以 status/fixed_in 字段为准。

---

## MOD-01 — ComputerTrace.kinds 内存索引无界增长

```yaml
id: MOD-01
title: "kinds 索引随记录总数无上限增长；rotate 只切文件不裁剪；status/wait 扫描 O(N-after)"
severity: medium
status: fixed
confidence: partial
module: mod
origin: [REVIEW-M1, LEGACY-B3]
files:
  - path: mod/src/common/java/cctrun/live/ComputerTrace.java
    lines: "37-38, 125, 142-155, 189-199"
evidence: |
  每条记录 kinds.add(kind.intern())（:125）；rotate() 只关闭/移动文件并重置 bytes，从不裁剪 kinds（:142-155）；
  countsAfter/firstAfter 以 index=seq-1 线性扫描。
  量级修正：约 8 B/条，按 README「30 台全部接管约 80 MB/min」估算约 9-15 MB/h/台，不是「数小时数百 MB」。
impact: |
  长会话/多电脑接管时堆内存单调增长，最终 OOM 或频繁 GC；status/wait 延迟随记录数增长。
fix: |
  kinds 改为以 firstSeq 为基线的窗口（rotate 丢弃已落盘旧段并记录 baseSeq），或按 kind 维护 seq 列表/流式扫文件。
tests: |
  可加纯 JUnit：rotate 后 kinds 窗口与 countsAfter/firstAfter 一致性。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  kinds 只保留双段窗口，first_seq 后 long 运算再安全索引；counts/firstAfter 不含轮转丢弃段。RecorderSafetyTest 窗口压力与真实双版本轮转通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-02 — 平台层 CctLive/LiveConfig 与两份构建脚本高度重复

```yaml
id: MOD-02
title: "forge/neoforge 平台层 CctLive/LiveConfig 与两份 build 脚本高度重复，行为修复需双改"
severity: low
status: open
confidence: confirmed
module: mod
origin: [REVIEW-M2]
files:
  - path: mod/forge/src/main/java/cctrun/live/CctLive.java
    lines: "40-53"
  - path: mod/neoforge/src/main/java/cctrun/live/CctLive.java
    lines: "40-52"
  - path: mod/forge/src/main/java/cctrun/live/LiveConfig.java
    lines: "21-67"
  - path: mod/neoforge/src/main/java/cctrun/live/LiveConfig.java
    lines: "21-67"
  - path: mod/forge/build.gradle.kts
    lines: "5-82"
  - path: mod/neoforge/build.gradle.kts
    lines: "5-79"
evidence: |
  git diff --numstat 精确统计：CctLive 相同 93 行/84%，LiveConfig 58 行/82%，build 脚本 66 行/80%（同构 69.5%）；
  差异全为平台 API 名与 loader 配置。审查称「95%」仅在归一化 Forge/NeoForge 类型名后成立。
impact: |
  平台层行为修复需双改，易漂移；当前两份内容一致。
fix: |
  把事件/配置注册抽到 common 的小接口或模板生成平台壳；构建脚本抽 gradle/mod-variant.gradle.kts 或 convention plugin。
tests: |
  none
```

## MOD-03 — HTTP 端口固定无回退，bind 失败文案与会话状态矛盾

```yaml
id: MOD-03
title: "HTTP 端口固定 47823（无 port=0 回退）；bind 失败后会话已建但报 'could not start'"
severity: medium
status: open
confidence: partial
module: mod
origin: [REVIEW-M3]
files:
  - path: mod/src/common/java/cctrun/live/LiveConfig.java
    lines: "26-27"
  - path: mod/forge/src/main/java/cctrun/live/CctLive.java
    lines: "55-63"
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "49-50, 251-261"
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "76-90"
evidence: |
  端口 47823（1024..65535，0 被排除、无重试）；LiveHttp:79 同步抛 BindException；LiveSession:49-50 已建会话并打日志，
  CctLive:61 却报 "cct-live could not start; computers cannot be attached"；endpoint.json 仅成功后写、close 删。
  2026-10-20 六项约定范围交付（c94928bceb451e9a9ce444f5faf0b51a64a0d389）：
  两加载器分别记录 session 创建失败、HTTP BindException（含 127.0.0.1 与端口/处理办法）和其他 HTTP 启动失败；保持现有端口，无自动换端口/port=0。该范围完成；自动回退及工具可见状态仍待策略决定，整项保持 open。
  本轮证据：issues_to_fix/evidence/dynamic/results-2026-10-20-six-scoped/README.md。
impact: |
  双开/端口占用时第二实例 AI 接管整体失效；日志有 ERROR+堆栈但游戏内/工具侧无提示，易误判。
fix: |
  bind 失败单独捕获并给出端口占用提示与 chat/工具可见状态；可选支持 port=0 回退（http.port() 已能回读实际端口）。
tests: |
  none
```

## MOD-04 — 反射绑定 CC:T 私有字段，无适配层

```yaml
id: MOD-04
title: "反射绑定 peripheralWrappers / RemotePeripheralWrapper.peripheral / ServerComputer.terminal，版本漂移即 ExceptionInInitializerError"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [REVIEW-M4, LEGACY-L-reflect]
files:
  - path: mod/src/common/java/cctrun/live/PeripheralQuery.java
    lines: "41-51"
  - path: mod/src/common/java/cctrun/live/Terminals.java
    lines: "18-26"
  - path: mod/forge/src/main/resources/META-INF/mods.toml
    lines: "24-28"
evidence: |
  javap 证实 1.120.2 的 1.20.1/1.21.1 jar 均存在 terminal、peripheralWrappers、RemotePeripheralWrapper.peripheral；
  静态块失败抛 IllegalStateException → ExceptionInInitializerError；mods.toml versionRange=[1.120.2] 精确 pin，
  风险被 pin 缓解（每次升级 CC:T 必须同步改 mod）。
impact: |
  CC:T 内部改名时首次使用即失败：Terminals 可打断服务器 tick，PeripheralQuery 直接断连接。
fix: |
  集中到 CcInternals 适配类 + 启动自检，失败时禁用对应功能并给版本提示。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  CcInternals 集中可选反射字段与类型检查，缺失能力诊断后降级，不抛静态初始化错误；精确版本 pin 保留。故意缺失字段测试及两版真实 terminal/wired 能力通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-05 — HTTP/trace 层无自动化测试（平台层测试已接入）

```yaml
id: MOD-05
title: "mod 仅 3 类 9 个 @Test；HTTP 鉴权/Host/413、trace 轮转、JsonValues、Ring、Screens.replay 无测试"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-M5, LEGACY-L27, LEGACY-L28]
files:
  - path: mod/src/common/test/java/cctrun/live/ComputerPathsTest.java
    lines: "13-46"
  - path: mod/forge/build.gradle.kts
    lines: "23-25"
  - path: mod/neoforge/build.gradle.kts
    lines: "23-25"
evidence: |
  common 仅 ComputerPathsTest/SnapshotManifestTest/Utf8Test 三个类、9 个 @Test；两个平台构建显式接入 common test 源集
  （故「forge/neoforge 无 test 源集」的表述不成立）；handoff:408-409 自认缺口。
impact: |
  纯 Java/可剥离逻辑（trace 轮转、鉴权、回放）无回归保护。
fix: |
  为 ComputerTrace rotate、LiveHttp 鉴权/Host/413、JsonValues、Ring、Screens.replay 补 JUnit 或抽出可测层。
tests: |
  现有 9 个 @Test 不覆盖上述路径。
fixed_in: fbfd68a67d56a7836d7c0b8a5ac79498e5a81ac3
fixed_at: 2026-10-19
resolution: |
  补 HTTP auth/Host/慢 body、Trace/Ring/JsonValues/Screens/快照等纯 JUnit，真实双版本验证 413、接管/租约、回放及 endpoint 清理。当前覆盖与平台边界见两份 final JSON，不虚报 Windows/macOS。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-06 — Host 校验放行 host==null

```yaml
id: MOD-06
title: "hostAllowed 在 Host 缺失时返回 true（纵深防御缺口）"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [REVIEW-L1, LEGACY-L-host]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "733-736"
evidence: |
  hostAllowed: `return host == null || host.equals("127.0.0.1:" + port) || …`；Host 校验先于鉴权（:104-111）；仅绑 127.0.0.1 且需 token，浏览器场景不可利用。
impact: |
  无 Host 的 HTTP/1.0 请求被放行；纵深缺口。
fix: |
  host 为 null 也拒绝（HTTP/1.1 合法请求必带 Host）。
tests: |
  需 HTTP 客户端级测试。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  HttpPolicy 对缺 Host 与非 loopback Host 拒绝，Bearer 常量时间比较；单测和两版真实 HTTP/1.0 缺 Host/401/403 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-5：HttpPolicy.hostAllowed 13-14 要求非 null 的回环 Host；缺失 Host 不再放行。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-07 — token.txt 未设 POSIX 0600

```yaml
id: MOD-07
title: "token.txt 以默认权限落盘，POSIX 多用户机同机用户可读"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [REVIEW-L2, LEGACY-L-token-perm]
files:
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "267-280"
evidence: |
  Files.writeString(file, token + "\n", UTF_8)（:278），无 setPosixFilePermissions；Windows 走 ACL 继承不适用。
impact: |
  Linux/macOS 多用户机上 token 等价本机完全控制权。
fix: |
  POSIX 平台创建后 setPosixFilePermissions(0600)，或在文档标明风险。
tests: |
  平台相关，可跳过。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  POSIX token 创建即 0600，已有文件权限收紧；Windows 不调用 POSIX API。RecorderSafetyTest 与两版 Linux 真实权限检查通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-7：LocalFiles.token 13-30 创建/修正 POSIX rw-------，已有纯文件回归；该结论不覆盖 Windows ACL。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-08 — 2 线程执行器 + body 无读取超时

```yaml
id: MOD-08
title: "固定 2 线程处理请求，body 读取无超时；持 token 的慢速 POST 可占满线程池"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-L3, LEGACY-L-2threads]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "79-85, 744-765"
evidence: |
  newFixedThreadPool(2)，backlog 16；body 用 readNBytes(MAX_READ+1) 无读超时；鉴权（:108）先于读 body，慢 POST 须已持 token。
impact: |
  持 token 的慢速 POST 可长期占满 2 线程，其他工具调用排队/超时。
fix: |
  增加读超时，或加大线程池并按端点隔离。
tests: |
  none
fixed_in: fbfd68a67d56a7836d7c0b8a5ac79498e5a81ac3
fixed_at: 2026-10-19
resolution: |
  所有响应前执行 body 5 秒总期限，包含拒绝鉴权/Host、GET、未知路径与忽略 body 的端点；超限有界排空，慢 body 在发响应头前关闭连接，不设置全局 JVM HTTP 参数。已实测复现两条拒绝请求卡死两个 worker，单 worker 回归及两版新脚本验证回收。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-6：LiveHttp 81 仍为 2 线程，但 HttpPolicy.read 已有独立截止时间；2 线程本身是容量选择，不能由此单独推出功能缺陷。线程池容量没有新增实机压力结论，原无超时缺口不重开。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-09 — trace/回放端点全文件读取

```yaml
id: MOD-09
title: "trace/回放端点每次全文件读取并逐行解析，长会话延迟随文件增大"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-L4]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "363-372, 553-656"
  - path: mod/src/common/java/cctrun/live/Screens.java
    lines: "21-60"
evidence: |
  trace()/recordAt/replay 均全文件读 + 逐行 JsonParser；trace 落盘受 64MB 上限约束，不是无限增长。
impact: |
  接近上限时单次 trace/回放解析 64MB，延迟秒级；与 MOD-01 叠加更明显。
fix: |
  用 kinds/seq 索引或分段偏移定位；至少到 limit 即停。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  recordAt 在锁内流式读避免整个 trace 深拷贝；分页只做一条 lookahead 即停止，matched_at_least 明示下界，summary 才精确计数。轮转/读取回归与游戏 trace/回放通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P3-6：记录读取已有窗口、分页与磁盘快照；逐文件读取成本并非全部消失。不把保留的重查询成本误判为旧问题未修。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-10 — 错误分类混乱、坏 manifest 拖垮 list

```yaml
id: MOD-10
title: "客户端错误多落 500 且回显 e.getMessage()；一个坏 manifest 中断整个 snapshot list"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-L5]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "165-170, 253, 430-472"
  - path: mod/src/common/java/cctrun/live/ComputerSnapshots.java
    lines: "226-246"
evidence: |
  校验类 400，IO 类 BadRequest(500,...+e.getMessage())（:253/430/452/472）；未捕获异常 500 internal:+e；list 循环内 manifest 无 try/catch。
impact: |
  客户端可触发 500 并得到内部消息；一个损坏快照让 list 整体失败。
fix: |
  按错误类型分状态码；list 内 per-entry try/catch 并计 skipped。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  内部失败返回泛化信息，日志留详细诊断；snapshot list 对坏条目单独 skipped 并继续列有效项。两版坏 manifest 与有效快照混合验证通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-11 — 字符/字节口径与大整数精度、双重序列化

```yaml
id: MOD-11
title: "截断按字符、预算按字节；>2^53 整数丢精度；recordAt 双重序列化"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-L6, LEGACY-L-datalength]
files:
  - path: mod/src/common/java/cctrun/live/JsonValues.java
    lines: "48-52, 117-119"
  - path: mod/src/common/java/cctrun/live/ComputerTrace.java
    lines: "108, 131"
  - path: mod/src/common/java/cctrun/live/TracingMethods.java
    lines: "177-183"
evidence: |
  truncate 按 UTF-16 字符、预算按 UTF-8 字节；|d|>=1e15 走 double；recordAt 先 GSON.toJson 估算再序列化整行；「文档冲突」不成立（LiveConfig:35/README:85 均写字符数）。另 TracingMethods.dataLength(String) 返回 s.length()（UTF-16）而非字节。
impact: |
  大整数格式/精度受限；每记录多一次序列化；handle bytes 字段对非 ASCII 偏小。
fix: |
  明确文档口径；大整数按需字符串化；估算用廉价长度；dataLength 改用 Utf8.length。
tests: |
  Utf8Test 可复用。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  核实 Lua 字符串每字符一字节，不错误改成 UTF-8 长度；文档记录 double/预览语义；trace 只序列化实际写入行，去掉重复长度估算。JsonValuesTest/Utf8Test 与双版本记录通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-12 — 会话目录秒级碰撞 / endpoint.json 残留

```yaml
id: MOD-12
title: "会话目录名 yyyyMMdd-HHmmss 秒级碰撞；崩溃时 endpoint.json 残留"
severity: low
status: fixed
confidence: partial
module: mod
origin: [REVIEW-L7, REVIEW-NEW-2]
files:
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "35, 44-52, 282-294"
  - path: mod/src/common/java/cctrun/live/ComputerTrace.java
    lines: "56"
evidence: |
  start()/stop() 不清理。目录名 yyyyMMdd-HHmmss 同秒重开复用目录，ComputerTrace:56 newBufferedWriter 截断旧 trace；endpoint.json 仅正常 stop 删（:287），崩溃/强杀残留，start 不校验旧文件。
impact: |
  极快重开世界丢上一会话 trace；崩溃后 endpoint.json 可能指向死端口或另一实例，cct-run mcp --live 连错。
fix: |
  目录名加毫秒/随机后缀或已存在加 -2；start 时校验 pid/port 并删除陈旧 endpoint.json。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  session 名用毫秒+UUID，首次 attach 排他创建，不破坏 dormant；endpoint 原子发布、仅清理 dead pid 或 owned session。纯文件测试、首轮 dormant 与最终关闭清理通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-13 — READ_ONLY 白名单是名称启发式

```yaml
id: MOD-13
title: "READ_ONLY 外设方法白名单是正则启发式，无法保证第三方 getter 无副作用"
severity: low
status: open
confidence: cannot-static
module: mod
origin: [REVIEW-D1, LEGACY-L-readonly-regex]
files:
  - path: mod/src/common/java/cctrun/live/PeripheralQuery.java
    lines: "37, 107-109"
evidence: |
  '正则 (get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)? 会放行 getAndConsumeXxx 类名；Access 丢弃事件、mount 返回 null 等其余隔离闭环；无真实第三方外设实例可验。'
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-5：PeripheralQuery 100-120 仍按名字过滤并在服务器线程调用 target.apply；第三方 getter 的副作用和跨线程竞争均缺少实际外设验证。名称不是只读证明；未验证的竞争路径只记核对表，不作为另一个已确认缺陷。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
impact: |
  若第三方外设存在「名字像读但有副作用」的方法，AI 查询可绕过「查询不改世界」承诺。
fix: |
  增加黑名单/危险方法拒绝，或把只读显式声明为调用者承担风险的参数。
tests: |
  需真实第三方外设。
```

## MOD-14 — onServer 超时后已开始任务仍执行

```yaml
id: MOD-14
title: "onServer 超时后 future.cancel(false) 只取消未开始任务，已开始的变更请求继续执行"
severity: low
status: open
confidence: confirmed
module: mod
origin: [REVIEW-NEW-1]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "695-713"
evidence: |
  future.cancel(false)（:706）仅取消未开始任务；已开始任务继续在服务器线程跑完，而请求已回 503。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-3：LiveHttp 708-725 已有 future.cancel(false)，排队任务与已开始任务必须区分；已开始的服务器线程操作仍可能在 503 后生效。状态保持 open，不声称已复现游戏中的重复副作用。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
impact: |
  变更类请求（power/event/files/snapshot/attach）可能「报失败但生效」，破坏幂等预期。
fix: |
  变更类端点改排队+回执（pending），或在结果标注任务是否已开始。
tests: |
  none
```

## MOD-15 — recordAt 缺半行容错，status?kinds= 可 500

```yaml
id: MOD-15
title: "recordAt 只解析匹配行且无 catch；并发写入/轮换的半行可致 JsonSyntaxException→500"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [REVIEW-NEW-3]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "363-372"
evidence: |
  recordAt 只解析匹配 {"seq":N, 前缀的行且无 try/catch；trace()/replay 已有跳过坏行逻辑。
impact: |
  status 的 kinds 查询偶发 500。
fix: |
  同 trace() 一样 catch RuntimeException 并计 skipped。
tests: |
  none
fixed_in: 265149543eccc154e422adb06115676cc11cbba1
fixed_at: 2026-10-19
resolution: |
  recordAt 使用 TraceRecords 容错验证，坏行不再导致 status match 500；RecorderSafetyTest、TraceRecords/replay 回归与真实 trace 检查通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-16 — screen_hash 32 位 + seq/tick 强转 int

```yaml
id: MOD-16
title: "screen_hash 为 32 位可碰撞；summary 把 seq/tick 强转 int，>2^31 回绕"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [REVIEW-NEW-4, LEGACY-L-hash, LEGACY-L-ticks-int]
files:
  - path: mod/src/common/java/cctrun/live/LiveHttp.java
    lines: "342, 644-645"
evidence: |
  screen_hash=Arrays.hashCode(text)*31+Arrays.hashCode(bg) 为 32 位；summary 把 seq/tick 强转 (int)；ticks=- 已在 :569-581 修为 400。
impact: |
  cct_live_wait 可能漏判屏幕变化；超长会话 summary 数字错乱。
fix: |
  改 64 位哈希；pair 改用 long 参数。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  screen_hash 使用包括尺寸/文字/fg/bg 的 SHA-256；seq/tick 经 HTTP/MCP 全路径保留 long。digest 单测、Long.MAX_VALUE 参数回归和实机调用通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-17 — trace 轮换与读取竞争，写失败一次后永久停写

```yaml
id: MOD-17
title: "lines() 懒打开流与 rotate 竞争；recordAt IOException 后 closed=true 永久停写"
severity: medium
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-B4]
files:
  - path: mod/src/common/java/cctrun/live/ComputerTrace.java
    lines: "72-85, 111-114, 142-145"
evidence: |
  lines() 先 flush/取 segments()，返回的 Stream 到调用方迭代时才打开文件，期间 rotate 用 Files.move 替换；
  recordAt 捕获 IOException 后置 closed=true，此后永不记录。
impact: |
  Windows 单人游戏轮换并发查询时 trace 可能读半截/混合或静默断流。
fix: |
  读方在锁内取段并用独立句柄/临时副本读、容忍旧段缺失；rotate 失败重试；写失败记录并下次重开 writer 且补 trace_error。
tests: |
  需实机并发压力。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  锁内复制不可变磁盘读快照，读完清理；完整 write 后才提交 seq/index，失败截断、暴露 trace_health、退避重试并补 trace_error。注入部分写入/并发轮转单测和两版压力通过；Windows 未实测。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P1-2：ComputerTrace.lines 69-86 在写锁内复制读者快照，recordAt 108-132 有写失败退避恢复；RecorderSafetyTest 34-86 覆盖快照轮换与部分写失败。Windows 文件锁原场景未重跑。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-18 — body 文件与 sessions 目录从不清理

```yaml
id: MOD-18
title: "saveBody 写出的 *-body-*.bin 从不删除；每个世界会话新建 sessions/<时间> 目录且 close 不清理"
severity: medium
status: open
confidence: confirmed
module: mod
origin: [LEGACY-M2]
files:
  - path: mod/src/common/java/cctrun/live/ComputerTrace.java
    lines: "49-60, 157-182"
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "44-52, 282-294"
evidence: |
  ComputerTrace 构造只删旧 .old.jsonl；saveBody 的 <trace>-body-<n>.bin 从不删除（每 trace 上限 64MB）；每个世界会话新建 sessions/<时间> 目录，close 只关 writer。
impact: |
  长期使用的世界磁盘持续增长。
fix: |
  trace 创建/关闭时清理 *-body-*.bin；按数量或总量保留最近 N 个 session。
tests: |
  none
```

## MOD-19 — 快照 save() 非原子

```yaml
id: MOD-19
title: "快照 save() 先删后拷，中途失败会留下无 manifest 的目录并丢掉旧快照"
severity: medium
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-M3]
files:
  - path: mod/src/common/java/cctrun/live/ComputerSnapshots.java
    lines: "92-126"
evidence: |
  save 在 :97 先 deleteTree(dir) 再建 files/ 并逐文件写，最后才写 manifest；中途磁盘满/文件被占用会留下无 manifest 的目录。
impact: |
  保存失败时旧快照已丢，随后 diff/restore 报 no snapshot。
fix: |
  写 <dir>.tmp，成功后整体替换（old→.old、tmp→dir、删 .old），失败清 tmp。
tests: |
  需实机/挂载级验证（可先抽路径纯逻辑测试）。
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  先复制并按实际复制内容生成 hash/校验 manifest，再 old->backup/stage->target 可恢复切换；失败保留旧副本，list 恢复中断切换。SnapshotStoreTest 与两版 mount save/diff/restore 通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
  2026-10-20 复核 main 25a392102b1ce47c32743564298df0622b04e9ca：
  P2-4：ComputerSnapshots.save 95-131 调用 SnapshotStore.replace，后者写 stage/backup 并回滚/恢复。确认已有可恢复替换，不称为任何文件系统上的全事务原子性。
  当前核对表：issues_to_fix/verification/review-2026-10-19-main.md；本次动态观察：issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json。
```

## MOD-20 — 重复电脑 ID 时 trace/快照目录冲突

```yaml
id: MOD-20
title: "traceById 只按数字 ID；同 ID 第二台电脑覆盖映射，快照目录仍为 computer-<id>"
severity: medium
status: open
confidence: confirmed
module: mod
origin: [LEGACY-M4]
files:
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "75, 166-168, 202"
  - path: mod/src/common/java/cctrun/live/ComputerSnapshots.java
    lines: "44-46"
evidence: |
  traceById.put(id, trace) 只按数字 ID，第二台同 ID 电脑覆盖映射；trace 文件加了 -2 后缀但快照目录仍是 snapshots/computer-<id>/。
impact: |
  status/trace/body 访问最后 attach 的那台；第二台的 save/restore 会覆盖第一台快照。
fix: |
  traceById 与快照目录改用 instance UUID（Attachment.describe 已输出 instance），数字 ID 只作展示。
tests: |
  none
```

## MOD-21 — 记录模式 attach 时 drain 在 trace 锁/发布 attachment 之前

```yaml
id: MOD-21
title: "history = ring.drain(...) 早于 synchronized(trace) 与 attached.put，窗口内新记录丢失/乱序"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-L-drain]
files:
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "177-201"
evidence: |
  history = ring.drain(...) 在 :184，synchronized(trace) 与 attached.put 在 :190-192；窗口内电脑线程新记录进入已 drain 的 ring。
impact: |
  history 缺失或下次 attach 时以历史身份重复/乱序出现。
fix: |
  把 drain 放进 trace 锁内，或发布 attachment 后再 drain 一次。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  record 选 ring/attachment 与 attach drain/publish 共用 ring monitor，包含已在途写线程。NeoForge recording 真实 handover 连续事件验证通过；Ring 容量/排空回归通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-22 — 区块卸载被当成电脑移除

```yaml
id: MOD-22
title: "registry 查不到即 detach(computer, 'removed')，区块卸载误报电脑被移除"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-L-unload]
files:
  - path: mod/src/common/java/cctrun/live/LiveSession.java
    lines: "232-246"
evidence: |
  if (registry.get(computer.getInstanceUUID()) != computer) detach(computer, "removed")，只以 registry 查不到为判据。
impact: |
  玩家走远导致区块卸载时误报「电脑已被移除」，接管被结束、聊天文案误导。
fix: |
  区分 unload 与真正移除（同一 UUID 重新加载时保留/恢复）。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  registry 消失统一报告 unavailable/unloaded，避免误称电脑已破坏；reload 不自动恢复 AI 控制。两版真实 forceload 卸载/重载与 detach reason 验证通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-23 — peripheral call id 每 VM 一个

```yaml
id: MOD-23
title: "peripheral call id 每 VM 一个，reboot 后从 1 重来，跨 reboot 的 call/return 不再唯一"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-L-callid]
files:
  - path: mod/src/common/java/cctrun/live/LiveMachine.java
    lines: "91-94, 110-113"
evidence: |
  Recorder 持有自己的 AtomicLong callIds 并在 nextCallId 递增，注释明确 per VM；ComputerTrace 的 session 级 callIds 未被使用。
impact: |
  同一 trace 里跨 reboot 的 call/return id 不再全局唯一，按 id 配对的消费者会混淆。
fix: |
  call id 改用 session 级计数或加 epoch 前缀。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  外设调用 id 来自 session 级原子计数，跨 VM reboot 保持唯一；两版真实两次 reboot 后 trace call ids 去重检查通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## MOD-24 — side 名与网络名同 map，重名静默覆盖

```yaml
id: MOD-24
title: "available() 先放 side 再 putAll(remote)，重名键静默覆盖"
severity: low
status: fixed
confidence: confirmed
module: mod
origin: [LEGACY-L-available-collision]
files:
  - path: mod/src/common/java/cctrun/live/PeripheralQuery.java
    lines: "57-66"
evidence: |
  out.put(side.getName(), peripheral) 后 out.putAll(remote(modem))，后者覆盖同名键，无冲突检测。
impact: |
  网络名与 side 名或不同网络间重名时，peripheral 调用可能打到错误对象。
fix: |
  冲突时报错，或给网络名加前缀区分。
tests: |
  none
fixed_in: 4faa031635975700cc59e9321426cf9002c42504
fixed_at: 2026-10-19
resolution: |
  PeripheralNames 对不同目标重名报歧义，对语义相等的同物理外设 wrapper 保留合法别名。纯单测与两电脑共享 wired modem 的实机 chest 查询通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```
