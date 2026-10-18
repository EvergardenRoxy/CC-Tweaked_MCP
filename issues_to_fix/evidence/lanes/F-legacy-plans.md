# F：上一轮（v0.2.2）遗留条目在 v0.2.3 的现状核对

- 仓库：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`，`git rev-parse HEAD` = **bf68ac4**（`bf68ac478f50065e9d18808201f0e2071a580b37`，v0.2.3 + 双版本 mod 构建），工作树干净。
- 核对对象：`D:\AI\DSH\cc-t\review-report.md`（0.2.2 审查报告）、`fix-plan.md`（B0/B1 + P1-P7）、`easy-fixes-plan.md`（A 批 A1-A12 / B 批 / C 批）。
- 方法：逐条在 HEAD 源码中定位（read/grep），必要时用 `git show --stat` 看做了什么提交；**未修改仓库、未跑 gradlew 构建**。mod 目录已重构为 `mod/src/common/`（共享）+ `mod/forge|neoforge/`（平台层），旧行号全部重查。
- 计数（按 review-report 中 47 条唯一问题）：**fixed 18 / open 26 / obsolete 3 / unclear 0**。批次清单：A 批 12 条 → 11 fixed、1 open（A9）；B 批 6 条 → 1 fixed、5 open；C 批 6 条 → 6 open；fix-plan P1-P7 → 7 fixed（P4 为实现 note 级缓解）。

## 结论摘要

0.2.3 的 A 批（`325f25b` cli A1-A3/A10/A11、`ddea1a6` mod A4/A7/A12、`0fdf64f` engine A5/A6/A8）**除 A9 外全部落地**，B0/B1/P1-P7 也已落地。真正仍开放的是 C 批 6 条（B3/B4/M2/M4/B7/L-reflect）+ B 批 5 条（L-runid/M3/L-hash/L-host/L-pending）+ 旧报告低级别里若干与它们不重叠的小项（L-active-leak、L-kill-files、L-remember、L-endpoint-token、L-token-perm、L-callid、L-datalength、L-readonly-regex、L-available-collision、L-2threads、ticks/seq int 截断、drain 竞态、卸载误报）。其中 **B0/B1/B2/B5/B6/B7 中的 B2（读侧）、M1、A7/B5/UTF-8 等确已修**，与 `docs/release-notes-0.2.3.md:77`、`handoff.md:677`（N14）自述的遗留清单基本一致，但 handoff 的遗留清单已滞后（它仍把 B5、M1 列入遗留，代码显示已修）。

实证：`cli/src/test/java/cctrun/cli/MainTest.java:76-117`（P1/P2）、`LiveToolsTest.java:323-371`（A1/A2/A3/A10）、`McpServerTest.java:207-228`（A11）、`engine/.../TraceTest.java:20-45`（A5/A6）、`RunnerTest.java:198-236`（B1 兜底/P4）、`FixtureTest.java:42-66`（B1/P3）、`mod/src/common/test/.../ComputerPathsTest.java`、`SnapshotManifestTest.java`、`Utf8Test.java`（A4/A7/B0）。0.2.3 发布说明也逐条对应（`docs/release-notes-0.2.3.md:17-45`）。

---

## 表 1：review-report.md 条目现状

### 高 / 中（B0-B7、M1-M4）

| 旧编号 | 旧级别 | 旧描述（简） | 现状 | 当前证据（文件:行号） | 备注 |
|---|---|---|---|---|---|
| B0 | 高·安全 | `cct_live_files` Windows 盘符路径逃逸，可读写宿主机任意文件 | **fixed** | `mod/src/common/java/cctrun/live/ComputerPaths.java:21-30`（拒绝含 `:` 段）；`ComputerFiles.java:30-32` 委托，读/写/list/stat/delete 全部调 `sanitise`（`ComputerFiles.java:51,83,99,139,167`）；单测 `ComputerPathsTest.java:40-42`；发布说明 `docs/release-notes-0.2.3.md:19`（V28 实机回归） | 只在 mod；engine 不受影响。长期建议（真实路径包含性校验）仍未做，但已知逃逸面关闭 |
| B1 | 高·崩溃 | fixture timeline `item` 槽号越界 → AIOOBE，无 summary | **fixed** | 解析期按容器 size 校验：`engine/src/main/java/cctrun/engine/Fixture.java:301-310`；运行期兜底：`Runner.java:193-213`（记 `fixture_action_error` note、status=error、仍写 summary）；测试 `FixtureTest.java:51-66`、`RunnerTest.java:198-213` | 首层 + 第二层都落地 |
| B2 | 中 | trace 末尾半行 → `cct-run trace`/`cct_trace` internal error | **fixed** | 每记录 flush：`engine/.../Trace.java:69-77`；读侧跳过坏行并计数：`TraceQuery.java:31-58`；CLI 报告 skipped：`Main.java:200-207`；测试 `TraceQueryTest.java:53-66`、`MainTest.java:98-109`、`TraceTest.java:20-34` | 完整：flush + 容错 + 提示。MCP 的 `cct_trace` 走 in-process CLI，skipped 只写 stderr，不并入工具结果（小瑕疵） |
| B3 | 中 | cct-live trace 内存索引 `kinds` 无上限，磁盘轮换限制不了内存 | **open** | `mod/src/common/java/cctrun/live/ComputerTrace.java:37-38`（kinds 注释/字段）、`:125`（每条 add）、`:142-155`（rotate 只切文件不裁 kinds）、`:189-199`（按 index=seq-1 全表扫描） | 0.2.3 未动；= 本次审查 D-M1 |
| B4 | 中 | trace 轮换与读取竞争；写失败一次后永久停写 | **open** | `ComputerTrace.java:72-85`（`lines()` 先 flush/segments，流懒打开）、`:142-145`（`Files.move`）、`:111-114`（IOException → `closed=true` 永久停写）；`flush()` 失败同样只 warn（`:206-213`） | 0.2.3 未动；与本次 D-M1/D-L4 相关但不同 |
| B5 | 中 | `max_megabytes_per_computer` 按 char 而非 UTF-8 字节计 | **fixed** | `ComputerTrace.java:130-136`（`bytes += Utf8.length(text)+1`，注释 “max_bytes is a byte budget”）；`Utf8.java:12-15`；测试 `Utf8Test.java:10-11` | A4 落地（`ddea1a6`），发布说明 `:33`。N14 把 B5 列为遗留已过时 |
| B6 | 中 | cct_live_wait 三问题（text/idle else-if、screen_hash NPE、单请求 15s 固定超时） | **fixed** | `cli/src/main/java/cctrun/cli/LiveTools.java:518-533`（hash 为 null 不崩、idle 独立判断）、`:490-512`（每轮请求预算 = min(5s, 剩余)）、`:546-549`（screenHash 空值防御）；测试 `LiveToolsTest.java:323-363` | A1/A2/A3 落地 |
| B7 | 中 | 被杀/超时运行无 summary、无 killed 标记 | **open** | `Runner.java:167-175`（summary 只在 loop 正常结束后写；无 state 文件）；`McpServer.java:261-263`（wall 超时直接 `destroyForcibly`，只回一句 “did not finish…”，不写标记） | = easy-fixes C 批 B7；N14 明确遗留（`handoff.md:677`） |
| M1 | 中 | `onServer` 的 `submit(...).get(5s)` 超时后 Future 未取消，任务仍在服务器队列执行 | **fixed** | `mod/src/common/java/cctrun/live/LiveHttp.java:695-713`（TimeoutException/InterruptedException 均 `future.cancel(false)`，不打断服务器线程）；发布说明 `:42` | A12（`ddea1a6`）。N14 把它列遗留已过时 |
| M2 | 中 | body 文件与 `cct-live/sessions` 从不清理，磁盘无界 | **open** | `ComputerTrace.java:49-60`（构造只删 `.old.jsonl`，不删旧 body）、`:157-182`（body 每 trace 上限 64MB 但从不删）、`:215-223`（close 只关 writer）；`LiveSession.java:44-52`（每次会话新目录）、`:282-294`（close 不清理 sessions） | C 批，未动；N14 遗留 |
| M3 | 中 | 快照 `save()` 先 `deleteTree` 再拷贝，非原子 | **open** | `mod/src/common/java/cctrun/live/ComputerSnapshots.java:92-126`（`:97` `deleteTree(dir)`，之后才建 `files/` 与写 manifest）；无 `.tmp`/替换步骤 | C 批；B 批“M3 快照原子写”同样未做 |
| M4 | 中 | 重复电脑 ID：`traceById`/快照目录只按数字 ID，第二台覆盖第一台 | **open** | `LiveSession.java:75`（`Map<Integer,ComputerTrace> traceById`）、`:166-168`（trace 文件加 `-2` 后缀，只解决文件）、`:202`（`traceById.put(id, …)`）；`ComputerSnapshots.java:44-46`（`snapshots/computer-<id>/`） | C 批，未动；快照/body 仍按数字 id |

### 低级别（engine / cli / LiveTools）

| 旧编号 | 旧级别 | 旧描述（简） | 现状 | 当前证据（文件:行号） | 备注 |
|---|---|---|---|---|---|
| L-runid | 低 | run_id 随机段只有 4 位十六进制，同毫秒可能撞目录 | **open** | `Runner.java:66`：`RUN_ID.format(created) + "-" + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt())` 仍是 4 位；`Files.createDirectories(runDir)`（`:68`）不防冲突 | B 批未做；N14 遗留 |
| L-item-overwrite | 低 | fixture 文件与程序同名时被静默覆盖 | **fixed** | `Runner.java:89-98`：先写 fixture，再写程序；同名时 `trace.note("fixture_file_overwritten", …)`（`:92-97`）；测试 `RunnerTest.java:215-222` | 采用 P4 的“note 而非拒绝”方案，覆盖仍会发生但可见 |
| L-energy-clamp | 低 | timeline `energy` 超 capacity 被静默 clamp | **fixed** | `Fixture.java:293-299`（`energy > capacity` 报错）、`:186-192`（peripheral 初始 energy 也校验）；测试 `FixtureTest.java:42-48` | P3 |
| L-notes-cap | 低 | notes 超 10 条静默丢弃，summary 无提示 | **fixed** | `Trace.java:30,37-38,82-90`（droppedNotes 计数）、`:101-104`；`Runner.java:391-392`（summary 增加 `notes_dropped`）；测试 `TraceTest.java:36-45`、`RunnerTest.java:111-123` | A6 |
| L-pacer-interrupt | 低 | tick 循环被中断时返回 “timeout”，环境中断与程序超时混淆 | **open** | `Runner.java:244-255`（`catch (InterruptedException e) { … return "timeout"; }`） | = 本次审查 A-E2；0.2.3 未改 |
| L-memory | 低 | heap_peak_mb 是各 heap pool 峰值之和，可能大于 -Xmx | **obsolete** | `Runner.java:335-348`（注释明确是 upper bound） | 原报告已注明“代码注释已说明，不列为 bug”，条目保留仅为说明 |
| L-limit-hint | 低 | `trace --limit` 恰好取完仍提示“还有更多” | **fixed** | `Main.java:200-207`（多取一条 `limit+1` 再判 `hasMore`）；MCP 同修：`McpServer.java:331-352`；测试 `MainTest.java:76-95` | P1 |
| L-term | 低 | `parseTerm` 不 trim，`--term "26 x 20"` 报错；与 `Fixture.parseSize` 不一致 | **fixed** | `Main.java:333-345`（`parts[0].trim()/parts[1].trim()`，注释指向 parseSize）；`Fixture.java:464-475` 亦 trim；测试 `MainTest.java:113-118` | P2 |
| L-badrun | 低 | trace.jsonl 存在但损坏时走 B2 的 internal error，错误不指向坏文件 | **obsolete** | `TraceQuery.java:46-52` 跳过坏行；`Main.java:206` 报 skipped；`Main.java:282-302` 只在缺 trace 时报 “not a run directory” | 问题随 B2 容错消失（坏行不再报错），无需再报 “line N invalid” |
| L-exit3 | 低 | MCP exitMeaning 缺 3（shutdown/reboot） | **fixed** | `McpServer.java:286-295`（`case 3 -> "shutdown or reboot by the program"`） | P5 |

### 低级别（McpServer / LiveTools）

| 旧编号 | 旧级别 | 旧描述（简） | 现状 | 当前证据（文件:行号） | 备注 |
|---|---|---|---|---|---|
| L-interrupt-kill | 低 | `waitFor` 被中断时不 destroy 子进程 | **fixed** | `McpServer.java:247-260`（catch InterruptedException：`destroyForcibly()`、`waitFor(5s)`、恢复中断、返回 cancelled）；测试 `McpServerTest.java:207-228` | A11 |
| L-active-leak | 低 | `pool.submit` 在 shutdown 后抛 RejectedExecutionException，`active` 条目泄漏且请求无回复 | **open** | `McpServer.java:145-164`：`active.put` 后直接 `pool.submit(...)`，无 try/catch；异常沿 `handle` 被 `serve()` 的 RuntimeException 捕获（`:117-123`），按 -32600 回一条错误，条目不会 remove | 0.2.3 未动；低 |
| L-kill-files | 低 | 强杀后临时 stdout/stderr 被 finally 删掉，超时结果只剩 wall time | **open** | `McpServer.java:261-263`（超时结果只有 “did not finish within … s”）、`:272-279`（finally 删临时文件） | 未动；现场仅剩 run 目录 |
| L-pending | 低 | `pendingLua` 无 TTL，只在 collect/detach 清理 | **open** | `LiveTools.java:43-44`（Set，无时间戳）、`:663`（`pendingLua.add`）、`:359`（detach 清理）、`:673`（collect 成功清理） | B 批未做 |
| L-kinds | 低 | `kinds()` 对数组元素不校验 primitive，`{"kind":[{}]}` 变 internal error | **fixed** | `LiveTools.java:905-921`（非 string 元素抛 ToolError “must be an array of strings”）；测试 `LiveToolsTest.java:365-371` | A10 |
| L-remember | 低 | `rememberScreens` 失败时清空该电脑全部基线，随后的 wait 可能把旧文字当新变化 | **open** | `LiveTools.java:577-592`（catch 里 `beforeAction.keySet().removeIf(…)`，仍清空；无 warning 字段） | 未动；低 |
| L-endpoint-token | 低 | 完全信任 endpoint.json 的 URL，可把 Bearer token 发往任意地址 | **open** | `LiveTools.java:784-809`（读 endpoint.json/token.txt，`URI.create(url)` 后直接作为 base，无 scheme/host 校验）；全文件无 loopback 限制（grep `loopback|localhost|127.` 无命中） | = easy-fixes **A9**；`handoff.md:677`（N14）明确 “A9 deferred until the mod's client/server interconnect is settled” |

### 低级别（cct-live mod）

| 旧编号 | 旧级别 | 旧描述（简） | 现状 | 当前证据（文件:行号） | 备注 |
|---|---|---|---|---|---|
| L-mirror-iteration | 低 | mod 的 `WiredModemPeripheral.network` 是 synchronizedMap，attach/detach 无锁迭代 | **obsolete** | 0.2.3 的 mod 已无自己的 `WiredModemPeripheral`（`mod/src/common/java/cctrun/live/` 下无 peripheral 包）；同类代码在 engine：`engine/.../peripheral/WiredModemPeripheral.java:29-37` 已改 `Collections.unmodifiableMap(new LinkedHashMap<>(remote))` | A8 落地（`0fdf64f`）。条目随代码搬迁失效 |
| L-reflect | 低 | 三处对 CC:T 内部实现的反射，版本漂移即类初始化失败，无统一适配层/版本自检 | **open** | `mod/.../PeripheralQuery.java:39-51`（`peripheralWrappers` + `$RemotePeripheralWrapper.peripheral`，静态块 `IllegalStateException`）；`Terminals.java:16-26`（`ServerComputer.terminal`）；`Runner.java:270-279`（engine `Computer.startRequested`，失败返回 null） | C 批，未动；本次审查 D-M4 |
| L-host | 低 | `LiveHttp.hostAllowed` 在 Host 缺失时放行 | **open** | `mod/.../LiveHttp.java:733-736`：`return host == null || host.equals("127.0.0.1:" + port) || …` | B 批未做；本次 D-L1 |
| L-body | 低 | 请求体只读 64KB 静默截断，随后报 “body is not valid JSON” | **fixed** | `LiveHttp.java:744-765`：先看 `Content-Length > ComputerFiles.MAX_READ` 直接 413，再 `readNBytes(MAX_READ+1)` 超限 413；`ComputerFiles.java:24` `MAX_READ = 1 MiB` | P6 落地；上限从 64KB 提到 1MiB（与读文件上限一致） |
| L-hash | 低 | `screen_hash` 用 int 哈希，可能碰撞导致 wait 漏变化 | **open** | `LiveHttp.java:342`：`Integer.toHexString(Arrays.hashCode(s.text()) * 31 + Arrays.hashCode(s.bg()))` | B 批未做 |
| L-token-perm | 低 | token.txt 用默认权限落盘 | **open** | `LiveSession.java:267-280`：`Files.writeString(file, token + "\n")`，无 POSIX 权限设置 | 未动；本次 D-L2 |
| L-snapshot-manifest | 低 | restore 直接用 manifest 里的路径 `resolve`，篡改可越出快照目录 | **fixed** | `SnapshotManifest.java:11-44`（所有 path 过 `ComputerPaths.checkRelative`）；`ComputerPaths.java:32-43`；`ComputerSnapshots.java:128-133`、`:204`（读文件也用已校验的 keys）；测试 `SnapshotManifestTest.java:25-42` | A7（`ddea1a6`），V30 实机验证 |
| L-callid | 低 | call id 每 VM 一个，reboot 后从 1 重来，跨 reboot 不唯一 | **open** | `mod/.../LiveMachine.java:91-94`（`Recorder.callIds` per VM）、`:110-113`（`nextCallId` 注释 “per VM”）；`ComputerTrace.java:95-97` 的 session 级 callIds 未被 Recorder 使用 | 未动；低 |
| L-datalength | 低 | `dataLength(String)` 返回 UTF-16 字符数而非字节数 | **open** | `mod/.../TracingMethods.java:177-183`：`if (value instanceof String s) return s.length();` | 与本次审查 D-L6（字符/字节口径）相关 |
| L-readonly-regex | 低 | READ_ONLY 白名单是名字启发式，不能保证无副作用 | **open** | `PeripheralQuery.java:37`（同一正则）、`:107-109`（只按名字拒绝） | 本次 D-D1 |
| L-available-collision | 低 | side 名与有线网络名放同一 map，重名互相覆盖 | **open** | `PeripheralQuery.java:57-66`（先 `out.put(side, …)` 再 `out.putAll(remote(modem))`，后者静默覆盖） | 未动；低 |
| L-2threads | 低 | 固定 2 线程处理所有本地请求，body 读取无超时，慢 POST 可占满 | **open** | `LiveHttp.java:80-85`（`newFixedThreadPool(2)`）；`body()` 无读取超时（`:744-765`） | 本次 D-L3 |
| L（mod）trace/screen 解析无容错 | 低 | 逐行 `JsonParser` 无容错，一条半写记录让整个请求 500 | **fixed** | `LiveHttp.java:596-609`（解析异常 `skipped++` 继续）、`:636`（结果带 `skipped`）；`Screens.java:25-34`（replay 跳过坏行） | 发布说明 `:44` |
| L（mod）endpoint.json 残留 | 低 | `http.enabled=false`/绑定失败时旧 endpoint.json 留给下一个实例 | **fixed** | `LiveSession.java:282-290`：`close()` 无条件 `Files.deleteIfExists(endpointFile())`，注释明确覆盖 “HTTP off / bind failed” | P6 落地 |
| L（mod）`ticks=-` / seq int 截断 | 低 | `ticks=-` 被当 0..MAX；summary 把 seq/tick 强转 int 会回绕 | **open（部分修复）** | `ticks=-` **已修**：`LiveHttp.java:569-581`（空或 `-` → 400）；int 截断**仍在**：`:644-645` `pair((int) v[1], (int) v[2])` / `pair((int) v[3], (int) v[4])` | 建议拆两条；`ticks` 已 fixed，`(int)` 转换 open |
| L（mod）记录模式 drain 竞态 | 低 | attach 时 `ring.drain()` 在 trace 锁/发布 attachment 之前，窗口内的记录丢失或下次重复 | **open** | `LiveSession.java:177-201`：`history = ring.drain(...)`（`:184`）先于 `synchronized (trace)` + `attached.put`（`:190-192`），窗口仍在 | 未动；低 |
| L（mod）区块卸载误报 removed | 低 | 区块卸载导致 registry.get != computer，被当成“电脑已被移除”并播报 | **open** | `LiveSession.java:232-246`：`if (registry.get(computer.getInstanceUUID()) != computer) detach(computer, "removed");`（`:234-235`） | README 列为限制；文案仍有误导 |
| 改进（mod 构建/测试） | 低 | 无 `java.toolchain`；`compileOnly(cobalt)` 未使用；mod 无 test source set | **open（部分）** | toolchain 仍无（`mod/forge/build.gradle.kts:29` 只有 `options.release = 17`；neoforge `:29` release 21）；cobalt 仍在 `mod/forge/build.gradle.kts:65`；**test source set 已加**：`mod/src/common/test/java/cctrun/live/`（ComputerPathsTest/SnapshotManifestTest/Utf8Test） | 测试缺口部分解决（本次审查 D-M5 继续跟踪） |

---

## 表 2：fix-plan / easy-fixes-plan 批次核对

### fix-plan.md（B0/B1 + P1-P7）

| 编号 | 描述（简） | 现状 | 证据 |
|---|---|---|---|
| B0 | 盘符路径逃逸 | fixed | 同表 1 B0（`ComputerPaths.java:21-30` + `ComputerPathsTest.java:40-42`） |
| B1 | timeline item 越界（校验 + 运行期兜底） | fixed | 同表 1 B1（`Fixture.java:301-310`、`Runner.java:193-213`） |
| P1 | trace `--limit` 提示误报 | fixed | `Main.java:200-207`、`McpServer.java:331-352`、`MainTest.java:76-95` |
| P2 | `--term` 与 fixture 解析统一（trim） | fixed | `Main.java:333-345`、`MainTest.java:113-118` |
| P3 | timeline `energy` 超容量早失败 | fixed | `Fixture.java:293-299`、`FixtureTest.java:42-48` |
| P4 | fixture 文件被程序覆盖需可见 | fixed（note 级缓解） | `Runner.java:89-98`、`RunnerTest.java:215-222`（未改成拒绝，符合计划里的“热修先做 note”） |
| P5 | MCP exit 3 文案 | fixed | `McpServer.java:286-295` |
| P6 | mod 三小修（`ticks=-` 400、body 413、endpoint.json 清理） | fixed | `LiveHttp.java:569-581`、`:744-765`；`LiveSession.java:282-290` |
| P7 | trace 读取容忍坏行 | fixed | `TraceQuery.java:46-52`、`Main.java:206`；mod 侧 `LiveHttp.java:596-609`、`Screens.java:25-34` |

### easy-fixes-plan A 批

| 编号 | 描述（简） | 现状 | 证据 / 备注 |
|---|---|---|---|
| A1 | `screen_hash` 缺失 NPE | fixed | `LiveTools.java:518-520,546-549`；测试 `LiveToolsTest.java:323-334` |
| A2 | text 与 idle 的 else-if | fixed | `LiveTools.java:527-533`；测试 `LiveToolsTest.java:336-348` |
| A3 | 单请求 15s 固定超时 | fixed | `LiveTools.java:55-59,490-512`；测试 `LiveToolsTest.java:350-363`（1s timeout 在 2.5s 内返回） |
| A4 | trace 配额按 UTF-8 字节 | fixed | `ComputerTrace.java:130-136`、`Utf8.java:12-15`、`Utf8Test.java:10-11` |
| A5 | trace 缓冲不刷盘 | fixed | `Trace.java:69-77`、`TraceTest.java:20-34` |
| A6 | notes 静默丢弃 | fixed | `Trace.java:30,82-90,101-104`、`Runner.java:391-392`、`RunnerTest.java:111-123` |
| A7 | snapshot manifest 路径校验 | fixed | `SnapshotManifest.java:11-44`、`ComputerPaths.java:32-43`、`SnapshotManifestTest.java:25-42`、`ComputerSnapshots.java:128-133` |
| A8 | wired modem 网络表无锁迭代 | fixed | `engine/.../peripheral/WiredModemPeripheral.java:29-37`（不可变副本） |
| A9 | endpoint.json 的 URL 无限制（可外发 token） | **open** | `LiveTools.java:784-809` 无 scheme/host 校验；`handoff.md:677` 明确 A9 deferred。**A 批唯一未落地项** |
| A10 | `kind` 数组元素类型未校验 | fixed | `LiveTools.java:905-921`、`LiveToolsTest.java:365-371` |
| A11 | MCP 中断不杀子进程 | fixed | `McpServer.java:247-260`、`McpServerTest.java:207-228` |
| A12 | `onServer` 超时 Future 未取消 | fixed | `LiveHttp.java:695-713` |

### easy-fixes-plan B 批 / C 批

| 编号 | 描述（简） | 现状 | 证据 |
|---|---|---|---|
| B-1（=旧 L-runid） | run_id 唯一性 | **open** | `Runner.java:66` 仍 4 位十六进制 |
| B-2（=旧 M3） | 快照原子写 | **open** | `ComputerSnapshots.java:92-126` 仍是先删后拷 |
| B-3（=旧 L-body） | body 64KB 静默截断 | fixed | `LiveHttp.java:744-765`（413，1 MiB） |
| B-4（=旧 L-hash） | 32 位屏幕哈希 | **open** | `LiveHttp.java:342` |
| B-5（=旧 L-host） | 缺 Host 放行 | **open** | `LiveHttp.java:733-736` |
| B-6（=旧 L-pending） | pendingLua TTL | **open** | `LiveTools.java:43-44,663,673` |
| C-1（=旧 B3） | kinds 无界 | **open** | `ComputerTrace.java:37-38,125,142-155,189-199` |
| C-2（=旧 B4） | rotate/读竞争 | **open** | `ComputerTrace.java:72-85,111-114,142-145` |
| C-3（=旧 M2） | body/sessions 不清理 | **open** | `ComputerTrace.java:49-60,157-182`、`LiveSession.java:44-52,282-294` |
| C-4（=旧 M4） | 同 ID 电脑 | **open** | `LiveSession.java:75,166-168,202`、`ComputerSnapshots.java:44-46` |
| C-5（=旧 B7） | 被杀运行标记 | **open** | `Runner.java:167-175`、`McpServer.java:261-263` |
| C-6（=旧 L-reflect） | CcInternals 反射门面 + 版本自检 | **open** | `PeripheralQuery.java:39-51`、`Terminals.java:16-26`、`Runner.java:270-279`；无适配类 |

---

## 仍开放条目汇总（建议优先级）

**P0 / 高（安全、可诊断性）**
1. **A9 / 旧 L-endpoint-token**：`cct-run mcp` 信任 endpoint.json 的 URL，可把 Bearer token 发到任意地址（`LiveTools.java:784-809`）。一行校验即可（scheme=http + host ∈ {127.0.0.1, localhost, ::1}），但 handoff 注为“等 mod 的 client/server 互联方案确定后再做”。
2. **B7 / C-5**：被杀/超时运行无 summary、无 killed 标记（`Runner.java:167-175`、`McpServer.java:261-263`）。B2 读侧已修，写侧仍缺。

**P1 / 中（长会话资源与并发）**
3. **B3 / C-1**：cct-live `kinds` 内存无上限（`ComputerTrace.java:37-38,125,142-155`）。
4. **B4 / C-2**：rotate 与读取竞争 + 写失败永久停写（`ComputerTrace.java:72-85,111-114`）。
5. **M2 / C-3**：body 文件与 sessions 无清理（`ComputerTrace.java:157-182`、`LiveSession.java:282-294`）。
6. **M3 / B-2**：快照 save 非原子（`ComputerSnapshots.java:92-126`）。
7. **M4 / C-4**：重复电脑 ID 在 traceById/快照目录上仍冲突（`LiveSession.java:75,202`、`ComputerSnapshots.java:44-46`）。

**P2 / 低（边界与打磨）**
8. **L-runid**（`Runner.java:66`）—— 8 位十六进制 + 目录冲突重试。
9. **L-active-leak**（`McpServer.java:145-164`）、**L-kill-files**（`McpServer.java:261-279`）—— 服务器关闭/超时路径。
10. **L-pending**（`LiveTools.java:43-44`）、**L-remember**（`LiveTools.java:577-592`）。
11. **L-hash**（`LiveHttp.java:342`）、**L-host**（`LiveHttp.java:733-736`）、**L-token-perm**（`LiveSession.java:267-280`）、**L-2threads**（`LiveHttp.java:80`）。
12. **L-callid**（`LiveMachine.java:91-94,110-113`）、**L-datalength**（`TracingMethods.java:177-183`）、**L-readonly-regex**（`PeripheralQuery.java:37,107-109`）、**L-available-collision**（`PeripheralQuery.java:57-66`）。
13. **L-reflect / C-6**：反射适配门面（`PeripheralQuery.java:39-51`、`Terminals.java:16-26`、`Runner.java:270-279`）。
14. **ticks/seq int 截断**：`ticks=-` 已修，`(int)` 转换仍在（`LiveHttp.java:644-645`）。
15. **drain 竞态**（`LiveSession.java:177-201`）、**卸载误报 removed**（`LiveSession.java:234-235`）。
16. **构建打磨**：`java.toolchain` 缺失、`compileOnly(cobalt)` 未用（`mod/forge/build.gradle.kts:29,65`）。

**已不再成立（无需跟进）**：L-memory（非 bug）、L-badrun（随 B2 容错消失）、L-mirror-iteration（mod 类已移除，engine 侧 A8 已修）。

---

## 与本次审查编号的重复映射

（本次审查编号取自 `evidence/lanes/task-cards/*.md`：A=engine 运行时 H/E/L-*，B=engine fixture F*，C=cli C*/L5-L15，D=mod M1-M5/L1-L7/D1-D2，E=build/docs H3/H4/B1-B5/L8-L13/DOC*）

| 旧编号 | 本次审查编号 | 关系 |
|---|---|---|
| B3 | **D-M1**（示例） | 同一问题：kinds 无上限 + status/wait 全表扫描 |
| B4 | D-M1 / D-L4 | 相关但不等同：rotate 竞态与新审查的“全文件逐行解析”都在 trace 读路径 |
| B2 | A-L-TQ、D-L4 | 新审查关注剩余容错缺口（`record.get("tick").getAsInt()` NPE、全文件解析），坏行跳过本身已修 |
| B5 | D-L6 | 新审查看“截断按字符 / 预算按字节”的口径冲突；B5 本身已修 |
| B6 | C-L11 | 新审查只剩 wait 预算与 `isUnanswered` 文案匹配的脆弱性；三个正确性问题已修 |
| B7 | C3（部分） | 新审查从“取消只杀子 JVM、live 工具无进程可杀”角度重提；killed 标记仍无 |
| M1 | 无 | 已修（A12），本次未再列 |
| M2 | D-L7（部分） | 新审查关注会话目录秒级碰撞/endpoint 残留；body/sessions 保留策略仍未列 |
| M3 | D-L5（部分） | 新审查关注错误分类与坏 manifest 拖垮 list；原子性未再列 |
| M4 | 无 | 同 ID 电脑本次未列，但仍 open |
| L-runid | 无 | 本次未列 |
| L-pacer-interrupt | **A-E2** | 同一问题：中断被报成 timeout |
| L-badrun | A-L-TQ（已修） | 坏行容错后不再触发 |
| L-endpoint-token | **C1** | 同一问题：endpoint URL 无校验，token 可外发 |
| L-reflect | **D-M4** | 同一问题：三处反射无适配层/版本自检 |
| L-host | **D-L1** | 同一问题：Host == null 放行 |
| L-token-perm | **D-L2** | 同一问题：token.txt 权限 |
| L-body | D-L3（相关） | body 上限已修；新审查看“无读取超时 + 2 线程” |
| L-2threads | **D-L3** | 同一问题：2 线程池 + 慢 body |
| L-datalength | D-L6（部分） | 字符 vs 字节口径 |
| L-readonly-regex | **D-D1** | 同一问题：READ_ONLY 名字启发式 |
| L-mirror-iteration | 无（obs） | mod 类已移除；engine 版已修，本次审查正面项 |
| L（mod）trace 容错 | D-L4（已修） | 新审查仍关注全文件扫描性能 |
| L（mod）endpoint 残留 | D-L7（部分） | 已修（无条件删除） |
| 构建/测试改进 | E-B3/B4/B5、D-M2/M5、E-DOC* | 新审查从 CI/版本/文档角度另立条目，覆盖旧“改进项”的一部分 |
| — | **C2/C4、A-H1/H2/E1/E3、B-F1-F12、E-DOC1-5** 等 | 本次审查的新发现，旧报告没有对应条目 |

**编号撞名提醒**：旧 M1-M4 与本次 D-M1-M5 含义不同（旧 M1=onServer Future、旧 M2=磁盘清理、旧 M3=快照原子、旧 M4=同 ID；新 D-M1=kinds、D-M2=平台重复、D-M3=固定端口、D-M4=反射、D-M5=测试）。写合并报告时必须带“旧/新”前缀。

---

## 偏差与备注

1. **handoff N14 已滞后**：它把 B5、M1 列为遗留（`handoff.md:677`），但代码显示 B5（A4）与 M1（A12）已在 `ddea1a6` 修复；`docs/release-notes-0.2.3.md:77` 的遗留清单同样应更新（B5 已修、B 批的 body 上限/`ticks` 已修、M1 已修）。
2. **P4 是“可见化”而非“拒绝”**：同名 fixture 文件仍会被程序覆盖，只是 summary.notes 有 `fixture_file_overwritten`；如果按“静默覆盖=bug”的原始定义，可标 fixed（缓解）而非彻底修复。
3. **L-body 的上限已从 64KiB 变为 1MiB**（`ComputerFiles.MAX_READ`），旧报告描述需按新值更新。
4. **mod 目录重构导致旧行号全部失效**：旧报告引用的 `mod/src/main/java/cctrun/live/*` 现在是 `mod/src/common/java/cctrun/live/*`（平台层在 `mod/forge|neoforge`），旧 `mod/.../peripheral/WiredModemPeripheral.java` 已不存在。
5. 本核对为静态核对，未运行 `gradlew`；mod 侧“实机行为”结论沿用 `handoff.md` 的 V28-V31 记录（B0/A7/A2/A6/A10 有实机证据，A8/A12 只有代码+单测）。

---

## AI-ISSUES

```yaml
- id: OLD-B3
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: cct-live 的 trace 内存索引 kinds 随记录数无上限增长，轮换不裁剪
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerTrace.java:37-38", "mod/src/common/java/cctrun/live/ComputerTrace.java:125", "mod/src/common/java/cctrun/live/ComputerTrace.java:142-155", "mod/src/common/java/cctrun/live/ComputerTrace.java:189-199"]
  evidence: "recordAt 每条 kinds.add(kind.intern())；rotate() 只关闭/移动文件并重置 bytes，从不裁剪 kinds；countsAfter/firstAfter 以 index=seq-1 线性扫整个列表。"
  impact: 长时间接管时堆内存单调上涨，且 status/wait 随记录数线性变慢。
  fix: kinds 改为以 firstSeq 为基线的窗口（rotate 丢弃已落盘旧段并记录 baseSeq），或按 kind 维护 seq 列表/流式扫文件。
  tests: mod 无 trace 轮换/裁剪单测；需纯 JUnit + 长稳场景。
  source_ids: ["D-M1"]
- id: OLD-B4
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: trace 轮换与读取竞争，且写失败一次后永久停写
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerTrace.java:72-85", "mod/src/common/java/cctrun/live/ComputerTrace.java:111-114", "mod/src/common/java/cctrun/live/ComputerTrace.java:142-145"]
  evidence: "lines() 先 flush/取 segments()，返回的 Stream 到调用方迭代时才打开文件，期间 rotate 用 Files.move 替换；recordAt 捕获 IOException 后置 closed=true，此后永不记录。"
  impact: Windows 单人游戏轮换并发查询时 trace 可能读半截/混合或静默断流。
  fix: 读方在锁内取段并用独立句柄/临时副本读、容忍旧段缺失；rotate 失败重试；写失败记录并下次重开 writer 且补 trace_error。
  tests: none（需实机并发压力）。
  source_ids: ["D-M1", "D-L4"]
- id: OLD-B7
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 被杀/超时运行无 summary、无 killed 标记
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:167-175", "cli/src/main/java/cctrun/cli/McpServer.java:261-263"]
  evidence: "Runner 只在 loop 正常结束后写 summary.json/run_end，运行前不写 state；MCP wall 超时直接 destroyForcibly，仅回一句 did not finish，不在 run 目录留任何标记。"
  impact: 被杀 run 目录只有半截 trace，agent 无法判断是取消、崩溃还是未结束。
  fix: 启动时写 state:running 小文件、结束时更新；MCP 杀进程后补 killed:true + 原因（或 run_end）。
  tests: none。
  source_ids: ["C3"]
- id: OLD-M2
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: body 文件与 cct-live/sessions 从不清理，磁盘无界
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerTrace.java:49-60", "mod/src/common/java/cctrun/live/ComputerTrace.java:157-182", "mod/src/common/java/cctrun/live/LiveSession.java:44-52", "mod/src/common/java/cctrun/live/LiveSession.java:282-294"]
  evidence: "ComputerTrace 构造只删旧 .old.jsonl，saveBody 写出的 <trace>-body-<n>.bin 从不删除（每 trace 上限 64MB）；每个世界会话新建 sessions/<时间> 目录，close 只关 writer 不清理。"
  impact: 长期使用的世界磁盘持续增长。
  fix: trace 创建/关闭时清理 *-body-*.bin；按数量或总量保留最近 N 个 session。
  tests: none。
  source_ids: ["D-L7"]
- id: OLD-M3
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 快照 save() 先删后拷，非原子
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerSnapshots.java:92-126"]
  evidence: "save 在 :97 先 deleteTree(dir) 再建 files/ 并逐文件写，最后才写 manifest；中途磁盘满/文件被占用会留下无 manifest 的目录。"
  impact: 保存失败时旧快照已丢，随后 diff/restore 报 no snapshot。
  fix: 写到 <dir>.tmp，成功后整体替换（old→.old、tmp→dir、删 .old），失败清 tmp。
  tests: 需实机/挂载级验证（可先抽路径纯逻辑测试）。
  source_ids: ["D-L5"]
- id: OLD-M4
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 重复电脑 ID 时 traceById 与快照目录冲突
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:75", "mod/src/common/java/cctrun/live/LiveSession.java:166-168", "mod/src/common/java/cctrun/live/LiveSession.java:202", "mod/src/common/java/cctrun/live/ComputerSnapshots.java:44-46"]
  evidence: "traceById.put(id, trace) 只按数字 ID，第二台同 ID 电脑覆盖映射；trace 文件加了 -2 后缀但快照目录仍是 snapshots/computer-<id>/。"
  impact: status/trace/body 访问最后 attach 的那台；第二台的 save/restore 会覆盖第一台快照。
  fix: traceById 与快照目录改用 instance UUID（Attachment.describe 已输出 instance），数字 ID 只作展示。
  tests: none。
  source_ids: []
- id: OLD-L-runid
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: run_id 随机段只有 4 位十六进制，可能撞目录
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:66", "engine/src/main/java/cctrun/engine/Runner.java:68"]
  evidence: "runId = UTC 毫秒时间戳 + HexFormat.of().toHexDigits((short) nextInt())（4 位）；Files.createDirectories(runDir) 不检查已存在。"
  impact: 并发 cct_run 同毫秒同随机数时两次运行互相覆盖 trace/summary。
  fix: 随机段扩到 8 位以上；用 createDirectory 并对冲突重试（最多 N 次）。
  tests: 抽 helper 后可单测冲突重试。
  source_ids: []
- id: OLD-L-pacer-interrupt
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: tick 循环被中断时返回 timeout，环境中断与程序超时混淆
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:244-255"]
  evidence: "pacer.awaitTick 的 catch (InterruptedException) 恢复中断标志后直接 return \"timeout\"，exit_code 仍为 2。"
  impact: 取消/关闭场景被报成程序超时，诊断语义失真（CLI 下影响有限）。
  fix: 增加 cancelled/interrupted 独立状态并映射退出码，或至少在 summary 标注 interrupted。
  tests: none。
  source_ids: ["A-E2"]
- id: OLD-L-active-leak
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: pool.submit 失败时 active 条目泄漏
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:145-164"]
  evidence: "tools/call 分支先 active.put(id, call) 再 pool.submit，submit 无 try/catch；RejectedExecutionException 时条目不会 remove，请求也拿不到正确回复。"
  impact: 关闭竞态下 active 表残留、错误码变成 -32600（实际应 -32603），内存影响很小。
  fix: try/catch RejectedExecutionException，移除 active 条目并回 -32603。
  tests: none。
  source_ids: []
- id: OLD-L-kill-files
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 强杀后临时 stderr 被删除，超时结果只剩 wall time
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:261-263", "cli/src/main/java/cctrun/cli/McpServer.java:272-279"]
  evidence: "超时分支只返回 did not finish within … s；finally 无条件删除临时 stdout/stderr 文件，失败现场只剩 run 目录。"
  impact: 超时/崩溃时丢失子进程最后的错误输出，排障信息减少。
  fix: 杀进程后读取 stderr 末尾若干行并入工具结果，再删临时文件。
  tests: none。
  source_ids: []
- id: OLD-L-pending
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: pendingLua 无 TTL，超时 lua 状态长期累积
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:43-44", "cli/src/main/java/cctrun/cli/LiveTools.java:663", "cli/src/main/java/cctrun/cli/LiveTools.java:673"]
  evidence: "pendingLua 是 ConcurrentHashMap 键集合，无时间戳；只在 collect 成功、detach 时清理。"
  impact: 长时间会话中多次 lua 超时会积累小状态，并影响临时文件 keep 集合。
  fix: 进入 lua 入口时清理超过 10 分钟的 pending 项（带时间戳）。
  tests: 可用可控时钟或跳过。
  source_ids: []
- id: OLD-L-remember
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: rememberScreens 失败时清空全部基线且无提示
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:577-592"]
  evidence: "catch 中 beforeAction.keySet().removeIf(k -> k.startsWith(id + \"/\")) 清空该电脑全部基线；返回结果无 warning 字段。"
  impact: wait 可能把旧屏幕文字当作新变化接受。
  fix: 失败时保留旧基线，或在 wait 结果加 warning: baseline unavailable。
  tests: none。
  source_ids: []
- id: OLD-L-endpoint-token
  verdict: confirmed
  severity_report: 低
  severity_suggested: medium
  title: endpoint.json 的 URL 无校验，可把 Bearer token 发往任意地址
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:784-809"]
  evidence: "从 game_dir/cct-live/endpoint.json 读 url 后直接 URI.create 作为 base，无 scheme/host 校验；token.txt 内容原样放入 Authorization 头。"
  impact: 能写游戏目录的本机程序可篡改 URL 外发 token，等价于拿到 cct-live 完全控制权。
  fix: 校验 scheme=http 且 host ∈ {127.0.0.1, localhost, [::1]}，否则 ToolError。
  tests: LiveToolsTest 缺外网 URL 用例（A9 未落地，handoff N14 标注 deferred）。
  source_ids: ["C1"]
- id: OLD-L-reflect
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 三处对 CC:T 私有字段反射，无适配层与版本自检
  module: mod
  files: ["mod/src/common/java/cctrun/live/PeripheralQuery.java:39-51", "mod/src/common/java/cctrun/live/Terminals.java:16-26", "engine/src/main/java/cctrun/engine/Runner.java:270-279"]
  evidence: "PeripheralQuery 静态块取 peripheralWrappers 与 $RemotePeripheralWrapper.peripheral，Terminals 取 ServerComputer.terminal，失败抛 IllegalStateException；Runner 取 Computer.startRequested。"
  impact: CC:T 升级改名时 mod 在类加载阶段失败，错误信息对用户不可读。
  fix: 集中到 CcInternals 适配类，启动自检版本，失败时禁用对应功能而非整体崩溃。
  tests: none。
  source_ids: ["D-M4"]
- id: OLD-L-host
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: Host 头缺失时 hostAllowed 放行
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:733-736"]
  evidence: "return host == null || host.equals(\"127.0.0.1:\" + port) || …：host 为 null 直接通过。"
  impact: 纵深防御缺口（浏览器不会缺 Host，DNS rebinding 已被白名单挡住）。
  fix: host 为 null 也拒绝；HTTP/1.1 合法请求必定带 Host。
  tests: 需 HTTP 客户端级测试。
  source_ids: ["D-L1"]
- id: OLD-L-hash
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: screen_hash 用 32 位 int 哈希，可能碰撞导致 wait 漏变化
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:342"]
  evidence: "Integer.toHexString(Arrays.hashCode(s.text()) * 31 + Arrays.hashCode(s.bg()))，32 位语义。"
  impact: 不同屏幕可能同哈希，cct_live_wait 的 changed/idle 判定偶发漏检。
  fix: 改为 64 位哈希（如 SHA-256 前 8 字节）。
  tests: none。
  source_ids: []
- id: OLD-L-token-perm
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: token.txt 以默认权限落盘
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:267-280"]
  evidence: "Files.writeString(file, token + \"\\n\", UTF_8)，无 setPosixFilePermissions；Windows 走 ACL 继承。"
  impact: POSIX 多用户机上同机其他用户可读 token，等价于完全操控 HTTP 面。
  fix: POSIX 平台创建时设 600，或在文档说明 token 等价于本机控制权。
  tests: 平台相关，可跳过。
  source_ids: ["D-L2"]
- id: OLD-L-callid
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: peripheral call id 每 VM 一个，reboot 后从 1 重来
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveMachine.java:91-94", "mod/src/common/java/cctrun/live/LiveMachine.java:110-113"]
  evidence: "Recorder 持有自己的 AtomicLong callIds 并在 nextCallId 递增，注释明确 per VM；ComputerTrace 的 session 级 callIds 未被使用。"
  impact: 同一 trace 里跨 reboot 的 call/return id 不再全局唯一，按 id 配对的消费者会混淆。
  fix: call id 改用 session 级计数或加 epoch 前缀。
  tests: none。
  source_ids: []
- id: OLD-L-datalength
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: dataLength(String) 返回 UTF-16 字符数而非字节数
  module: mod
  files: ["mod/src/common/java/cctrun/live/TracingMethods.java:177-183"]
  evidence: "if (value instanceof String s) return s.length();（ByteBuffer/byte[] 分支正确返回字节数）。"
  impact: handle 记录的 bytes 字段对非 ASCII 数据偏小，与“bytes = 完整长度”的文档口径不一致。
  fix: 改用 Utf8.length(s)（已有 Utf8 工具类）。
  tests: Utf8Test 已存在，可直接复用。
  source_ids: ["D-L6"]
- id: OLD-L-readonly-regex
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: READ_ONLY 白名单是名字启发式，不能保证查询无副作用
  module: mod
  files: ["mod/src/common/java/cctrun/live/PeripheralQuery.java:37", "mod/src/common/java/cctrun/live/PeripheralQuery.java:107-109"]
  evidence: "Pattern = (get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)?；只按方法名匹配，getAndConsumeXxx 类名称会放行。"
  impact: 对第三方 mod 外设，“查询不改世界”的承诺不成立。
  fix: 增加黑名单/长度限制，或把“只读”做成显式由调用者承担风险的参数。
  tests: none（需真实第三方外设）。
  source_ids: ["D-D1"]
- id: OLD-L-available-collision
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: side 名与有线网络名放同一 map，重名静默覆盖
  module: mod
  files: ["mod/src/common/java/cctrun/live/PeripheralQuery.java:57-66"]
  evidence: "available() 先 out.put(side.getName(), peripheral)，再 out.putAll(remote(modem))，后者覆盖同名键，无冲突检测。"
  impact: 网络名与 side 名或不同网络间重名时，peripheral 调用可能打到错误对象。
  fix: 冲突时报错，或给网络名加前缀区分。
  tests: none。
  source_ids: []
- id: OLD-L-2threads
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 固定 2 线程处理本地请求，body 读取无超时
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:80-85", "mod/src/common/java/cctrun/live/LiveHttp.java:744-765"]
  evidence: "Executors.newFixedThreadPool(2)；body() 用 readNBytes(MAX_READ+1) 无 socket/read 超时；鉴权在 body 读取之前。"
  impact: 持 token 的慢速 POST 可占满线程池，使并发工具调用排队/超时。
  fix: 增至 4 线程或按端点区分，并给 body 读取加超时。
  tests: none。
  source_ids: ["D-L3"]
- id: OLD-L-ticks-int
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: trace summary 把 seq/tick 强转 int，超过 2^31 回绕
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:644-645"]
  evidence: "pair((int) v[1], (int) v[2]) / pair((int) v[3], (int) v[4])；同项的 ticks=- 已在 :569-581 修为 400。"
  impact: 长会话 seq/tick 超过 int 范围后 summary 输出负数。
  fix: pair 改用 long 参数（去掉 (int) 转换）。
  tests: none。
  source_ids: []
- id: OLD-L-drain
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 记录模式 attach 时 ring.drain 在 trace 锁/发布 attachment 之前
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:177-201"]
  evidence: "history = ring.drain(...) 在 :184，synchronized(trace) 与 attached.put 在 :190-192；窗口内电脑线程新记录进入已 drain 的 ring。"
  impact: history 缺失或下次 attach 时以历史身份重复/乱序出现。
  fix: 把 drain 放进 trace 锁内，或发布 attachment 后再 drain 一次。
  tests: none。
  source_ids: []
- id: OLD-L-unload
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 区块卸载被当成电脑移除并播报 removed
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:232-246"]
  evidence: "if (registry.get(computer.getInstanceUUID()) != computer) detach(computer, \"removed\")，仅以 registry 查不到为判据。"
  impact: 玩家走远导致区块卸载时误报“电脑已被移除”，接管被结束、聊天文案误导。
  fix: 区分 unload 与真正移除（同一 UUID 重新加载时保留/恢复）。
  tests: none。
  source_ids: []
- id: OLD-L-build
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: mod 构建缺 java.toolchain，且 compileOnly cobalt 未使用
  module: build-ci
  files: ["mod/forge/build.gradle.kts:29", "mod/forge/build.gradle.kts:65", "mod/neoforge/build.gradle.kts:29"]
  evidence: "两份构建只有 options.release（17/21），无 java.toolchain 块；forge 仍 compileOnly(\"cc.tweaked:cobalt:0.9.9\") 而源码未直接引用；test source set 已补（mod/src/common/test）。"
  impact: 工具链靠运行环境 JDK，构建可复现性弱；多一个未用依赖。
  fix: 显式声明 java.toolchain；删除 cobalt compileOnly。
  tests: CI 构建（build.yml）可覆盖。
  source_ids: ["E-B3", "E-B4", "D-M2"]
```
