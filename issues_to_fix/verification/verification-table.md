# CC-Tweaked_MCP 审查意见核对表（表格式）

- 核对对象：https://github.com/EvergardenRoxy/CC-Tweaked_MCP `main`，本地 HEAD = `bf68ac4`（= origin/main，即审查报告对象）
- 项目版本 0.2.3；`git describe` = `v0.2.3-9-gbf68ac4`（tag `v0.2.3` = `f31082f`，落后 HEAD 9 个提交）
- 审查报告日期：2026-10-18；核对日期：2026-10-18
- 核对方式：6 条并行静态核对 lane（engine 运行时 / engine fixture / cli / mod / 构建 CI / 旧 fix plan）+ Lead 动态复现（已构建的 `cli/build/install/cct-run` + 真实 CC:T 1.120.2 jar）
- 结论枚举：**确认**（问题成立）、**部分确认**（核心成立但报告细节/级别需修正）、**不成立**（报告判断错误）、**无法静态验证**（需动态/第三方环境）、**已修复**（0.2.3 已修）
- 完整证据：`evidence/lanes/A-engine-runtime.md`、`B-engine-fixture.md`、`C-cli.md`、`D-mod.md`、`E-build-docs.md`、`F-legacy-plans.md`
- 待修清单：`CC-Tweaked_MCP/issues_to_fix/`（只收确实存在且未修的问题）

## 总览

| 类别 | 报告数量 | 确认 | 部分确认 | 不成立 | 无法验证 | 级别调整 |
|---|---|---|---|---|---|---|
| 高级别（H1-H4） | 4 | 1 | 2 | 0 | 1（H1 链） | H3/H4 高→中；H1 重新界定 |
| 中级别（E/F/C/M/B） | 19 | 12 | 6 | 1（E1 风险面） | 0 | 8 项降级（E2/E3/E4/C1/C3/C4/M2/M4） |
| 低级别 | 39 | 33 | 4 | 2（F7、D2 影响） | 0 | F4 升中（新 SOE 路径） |
| 疑点 | 3 | 1 | 1 | 1 | 0 | — |
| 正面确认 | 6 | 5 成立 | 1（路径“四层闭环”表述过强） | 0 | 0 | — |
| 文档 | 5 | 3 | 2 | 0 | 0 | — |
| 项目管理 | 12 | 10 | 2（数字/时间变化） | 0 | 0 | — |
| **新增发现（NEW）** | — | **24** | — | — | — | 见文末 NEW 表 |

---

## 一、高级别（4 项）

| 编号 | 报告级别 | 实际位置 | 审查意见摘要 | 核对结论 | 关键证据 | 建议级别 |
|---|---|---|---|---|---|---|
| H1 | 高·疑点 | harness.lua:13-14；HarnessApi.java:48-56；Runner.java:134 | `__cctrun` 仅靠从 `_G` 删除隐藏；Cobalt debug 库对受测程序完整可用（getlocal 等），理论上可伪造 `report.done(true)`；建议一次性 token | **部分确认 / 报告主张不成立**：debug 工具箱完整属实；但 `report.done` 伪造链静态+动态都不可达；真实可达的是 `_G` 篡改面（NEW-1） | 动态：16 个 debug 函数全可用；registry 只有 `{_LOADED, cc_try_barrier}`、0 个 thread；`debug.getlocal(co,…)` 支持 thread；栈上只有 program 与 exception.lua 帧、无 harness 帧；全 `_G` 函数 upvalue 扫描 0 个 report；`_G.__cctrun=nil`。`javap CobaltLuaMachine` 构造器无条件 `CoreLibraries.debugGlobals`→`DebugLib.add`；GlobalRegistry 只包 LuaTable，mainThread 未入 registry；程序跑在 `exception.try` 新协程 | 重新界定为 **高**（测量可信度：_G 可篡改 → 可伪造 out_of_memory/timeout，见 ENG-RT-02） |
| H2 | 高 | Runner.java:55-58, 88, 134 | fs 写入不受 DISK_CAPACITY 限制可写爆 1GB 堆；OOM 仅按 Cobalt 文案归因，与真实 VM OOM 不可区分 | **确认（细节修正）**：写入确实无配额；报告「DISK_CAPACITY 仅用于 fs.getFreeSpace」不准确——它被传给 `new MemoryMount(DISK_CAPACITY)`，getCapacity 报 1000000，getFreeSpace 实报 `unlimited`；OOM 文案可被程序伪造 | 动态：20×1MB=20,971,520 B 写入成功，`fs.getSize=20971520`、`getFreeSpace="unlimited"`、`getCapacity=1000000`，status=ok、heap_peak=158MB。`javap MemoryMount`：capacity 只被 getRemainingSpace/getCapacity 引用，EntryChannel 无容量检查；`LuaError(Throwable)` 模板 `vm error: `+Throwable.toString()，两类 OOM 文案相同 | 高（维持） |
| H3 | 高 | build.yml:42,68,96；scripts/smoke-test.{sh,ps1}；README.md:3,127 | CI 三个 job 只跑 build/distZip；smoke（21/22 用例）零接入；无 CodeQL/dependency-review/wrapper-validation/dependabot；0.2.3 冒烟未重跑；badge 钉 multi-version-build | **确认（级别下调）**：事实全部成立；但项目已在 handoff.md:411（L30）、docs/building.md:42 自述为已知限制 | build.yml 110 行仅 3 job；全仓库 yml/kts/toml/properties grep smoke 零命中；.github 递归仅 workflows/build.yml；README:127 自述 0.2.3 未重跑；README:3 badge 指向 multi-version-build | 中（高→中） |
| H4 | 高 | build.gradle.kts:5；Versions.java:8；build.yml:96-110；RunnerTest.java:35；handoff.md:10 | 打包版本与运行时自报双源；release 不校验 tag；唯一版本测试只锁 Versions.java；gson 分叉是「该风险的现实版本」 | **确认（归因修正、级别下调）**：双源与无 tag 校验成立；但 gson 2.10.1 是依赖不一致，不是版本号漂移；当前三处均 0.2.3、尚未漂移。新增现实风险：tag v0.2.3 后 9 提交而版本号未变 | build.gradle.kts:5 `version="0.2.3"`；Versions.java:8 `TOOL="0.2.3"`；RunnerTest:35 断言 `"ComputerCraft 1.120.2 (cct-run 0.2.3)"`（只锁运行时串）；release job 仅用 `GITHUB_REF_NAME` 命名，无比较；`git describe` = v0.2.3-9-gbf68ac4 | 中（高→中） |

---

## 二、中级别（19 项）

| 编号 | 报告级别 | 实际位置 | 审查意见摘要 | 核对结论 | 关键证据 | 建议级别 |
|---|---|---|---|---|---|---|
| E1 | 中 | RootMount.java:29-46 | rename 用字符串前缀判断「目录移入自身」；真 inside-self 直接委托 MemoryMount（未验证）；/a→/ab 误判靠两步改名碰巧收敛 | **不成立（风险面）**：行为与游戏一致；两步改名是有意补偿+回滚；仅非原子（残留 /.cctrun-rename）= NEW-2 | `javap MemoryMount.rename` 首行即 `dest.startsWith(source)` 抛 "Cannot move a directory inside itself"；上游 `FileSystem.move` 用 segment-aware contains() 先拒绝。动态：`fs.move("a","a/x")` → `Can't move a directory inside itself`；`/d→/de` 成功；`/f/x→/f` File exists；`/a.log→/a.log.1` 成功 | 低/信息（中→低） |
| E2 | 中 | Runner.java:252-255 | tick 循环被 InterruptedException 打断返回 `"timeout"`，与程序超时共用 exit_code=2 | **确认** | `catch (InterruptedException) { … return "timeout"; }`；真超时 `if (tick >= maxTicks) return "timeout"`；无 interrupted 状态；CLI 有 System.exit、MCP 杀子 JVM，实际面窄 | 低（中→低） |
| E3 | 中 | Runner.java:152-157 | `context.close(50ms)` 不等待计算机线程退出，仅记 note；嵌入为库时泄漏线程 | **部分确认** | :152-157 失败仅 `trace.record("context_close_pending")`；:41 javadoc 声明 one run per JVM；CLI/MCP 均每 run 一进程 | 低（中→低） |
| E4 | 中 | harness.lua:33-35 | `fs.open(path,"r")` 无 nil 检查，程序文件缺失时报 nil index | **确认（当前不可触发）** | :33-34 无 nil 检查；Runner:75/98 先写程序文件再装 harness，故必成功；只有 addFile 异常/重构时暴露 | 低（中→低） |
| F1 | 中·疑点（报告已降为回归风险） | Fixture.java:76-80；libs.versions.toml:4 | 深嵌套 JSON SOE 取决于 Gson 版本；2.11.0 有 255 层上限会走正常报错，故降级 | **不成立（依据错误）**：gson 2.11.0 无 255 层上限、解析是迭代的；但存在另一条真实 SOE（event.args→toLua），见 NEW | `javap -p` JsonReader 无 nestingLimit/setNestingLimit；GsonDepth 直测 1,000,000 层解析 OK；cct-run 深嵌套 fixture 200…1,000,000 全部走到 validate（exit 65 unknown key）。JsonParser 确实 catch SOE 并包成 JsonParseException，但当前阈值远高于 255 | 降级/删除（原条目不成立）；新增 ENG-FX-02 |
| C1 | 中 | LiveTools.java:784-809,832 | endpoint.json 的 URL 未做 scheme/host 校验即把 Bearer 令牌发往该 URL；回环约束仅靠 mod 侧 | **确认（级别下调）**：代码事实成立；mod 侧已强制 127.0.0.1 绑定+Host 校验，只剩同权限本地篡改面 | :803-805 直接 URI.create；:832 无条件 Authorization；mod LiveHttp.java:79/735、LiveSession.java:257 写死回环 | 低（中→低）；旧计划 A9 仍标 P0，故 issues 里保留 medium |
| C2 | 中 | McpServer.java:100-128 | stdin 单行无长度上限；深嵌套 SOE 当前被 Gson 2.11.0 的 255 层上限阻断；残余超大行 OOM 打死读循环 | **部分确认（前提错误）**：行长无上限属实；255 层上限不存在；实测 2,000,065 B 深嵌套行、8 MB 行都正常处理，服务不死；真正 SOE 在响应序列化（NEW-1） | :103 readLine 无上限；:109/:119 只捕 JsonParseException/RuntimeException；MCP 实测 1M 层（2MB）行返回正常 tools/list；8MB 行正常；进程存活 | 低（中→低） |
| C3 | 中 | McpServer.java:145-168,433-447；LiveTools.java:537-542 | notifications/cancelled 只杀子 JVM；live 工具无进程可杀、线程不被中断，跑满预算后丢弃响应 | **确认（级别下调）**：不是 sleep 不响应中断（三处 catch(InterruptedException) 都在），而是没人 interrupt | Call 只存 Process（:443-446）；live 调用不传 Call（:215）；Call 无 Thread/Future；结果返回后 :159 才按 cancelled 丢弃 | 低（中→低） |
| C4 | 中 | JavaFinder.java:73-90,125-131；Launcher.java:66-76 | release 文件的 JAVA_VERSION 被信任且取最高版本；PATH 可写目录可伪造 home 劫持 re-exec | **确认（级别下调）**：利用需可写 JAVA_HOME/PATH/安装目录（同权限本地场景） | :80-83 只解析 release；:128 按 major 取最高；Launcher 用 best.java() 重执行；不执行 `java -version` 探测（这点做得好） | 低（中→低） |
| M1 | 中 | ComputerTrace.java:37-38,125,142-155,189-199 | kinds 索引随记录总数无上限增长（磁盘有轮转配额，内存没有）；status/wait 线性扫描 | **部分确认（量级夸大）**：无界属实；约 8B/条，按 README 80MB/min/30 台 ≈ 9-15MB/h/台，不是「数小时数百MB」；扫描 O(N−after) | `kinds.add(kind.intern())` 每记录一项；rotate 只切文件；countsAfter/firstAfter 从 after 扫到表尾 | 中（维持，量级修正） |
| M2 | 中 | mod/forge vs mod/neoforge 的 CctLive/LiveConfig/build 脚本 | 平台层约 95% 逐行重复（日志文案一字不差）；行为修复需双改 | **确认（比例修正）**：git diff --numstat 完全相同 93 行（84%）/58 行（82%）/66 行（80%）；Jaccard 73%/69%/69.5%；「95%」仅在归一化 Forge/NeoForge 类型名后成立 | 差异全为平台 API 名与 loader 配置；两份 LiveConfig 键名/默认值/注释一致 | 低（中→低） |
| M3 | 中 | LiveConfig.java:26-27；forge/CctLive.java:55-63；LiveSession.java:49-50,251-261；LiveHttp.java:76-90 | 端口固定 47823、无 port=0 回退；bind 失败只留笼统日志，「会话已建、仅 HTTP 未起」不可区分；双开实例第二实例接管静默失效 | **部分确认**：端口/无回退/双开失效成立；但「静默」不准确——latest.log 有 ERROR+堆栈；文案矛盾（会话已建却报 could not start）成立 | :57-62 try { session=start; if(http) startHttp } catch IOException → "cct-live could not start; computers cannot be attached"；endpoint.json 仅成功后写、close 删；start 不清理崩溃残留（NEW-2） | 中（维持） |
| M4 | 中 | PeripheralQuery.java:41-51；Terminals.java:18-26；mods.toml:24-28 | 反射绑定 CC:T 三个私有字段，版本漂移即 ExceptionInInitializerError 级联；靠 mods.toml 精确 pin 缓解 | **确认（级别下调）**：javap 证实 1.120.2 两份 jar 均存在 terminal/peripheralWrappers/RemotePeripheralWrapper.peripheral；pin 精确 | 静态块失败抛 IllegalStateException→ExceptionInInitializerError；versionRange=[1.120.2] | 低（中→低） |
| M5 | 中 | mod/src/common/test；forge/neoforge build.gradle.kts:23-25 | 平台层 0 测试；common 仅 3 测试类；HTTP 鉴权/Host/413、trace 轮转、JsonValues、Ring、Screens.replay 全无测试 | **部分确认**：「forge/neoforge 无 test 源集」不成立——两处显式接入 common test 源集，实测 3 类 9 个 @Test；测试覆盖缺口成立 | common 仅 ComputerPathsTest/SnapshotManifestTest/Utf8Test；handoff:408-409 自认缺口 | 低（中→低） |
| B1 | 中 | build.yml:82,88,96,104-110 | release job 重新检出重建，发布物与 CI 测过的产物不保证一致；上传的 mod jar 未被复用；gh release create 不幂等 | **确认** | needs:[tests,mod] 但全文无 download-artifact；release 重新 checkout 并 `:cli:distZip assembleMods`；无 release 存在性判断 | 中（维持） |
| B2 | 中 | .github/；gradle/wrapper/properties:3 | 无 CodeQL/dependency-review/wrapper-validation/dependabot/verification-metadata | **确认**（absence 已枚举） | .github 仅 build.yml；gradle 仅 catalog+wrapper；无 verification-metadata | 中（维持） |
| B3 | 中 | mod/forge:70；mod/neoforge:67；libs.versions.toml:4；THIRD-PARTY-NOTICES:33 | mod 测试依赖手写 gson 2.10.1，catalog/notices 为 2.11.0；junit 系亦绕 catalog | **确认（行号修正）** | gson 2.11.0 在 libs.versions.toml:**4**（:6 是 slf4j）；两份脚本 :70/:67 精确；junit-bom 5.11.4 手写 | 中（维持） |
| B4 | 中 | mod/forge/build.gradle.kts vs mod/neoforge/build.gradle.kts | 两脚本约 70% 逐行重复（连行号一致）；「1.120.2」硬编码四处 | **部分确认（数字修正）**：完全相同 66/82 行 = 80.5%（Jaccard 69.5%）；「四处」不成立——每份各 1 处 + catalog = 3 处 | difflib 差异 29 行（插件、MC/loader、release 17↔21、loader 配置、cobalt、metadata 文件名） | 中（维持） |
| B5 | 中 | .gitattributes:1-2；cli/build.gradle.kts:53；handoff.md:635 | 无 `* text=auto`；smoke-test.sh 不受 eol=lf 保护且随发布 zip 出厂；pack.mcmeta 行尾导致跨平台哈希不可比（handoff 实测） | **部分确认**：行尾问题成立；`git ls-files --eol` 显示 smoke-test.sh i/lf w/crlf attr/；handoff:635 有实测记录。报告「JDK 单一 21 无矩阵」成立；「1018 是遗留临时分支」不准确（origin/1018 已合并真实分支，已删） | .gitattributes 仅 2 行；distZip include scripts/** | 中（维持） |

---

## 三、低级别（报告 39 项）与疑点

> 行号已按 HEAD 校正；级别为核对后建议。

| 模块 | 编号/条目 | 位置 | 结论 | 证据/说明 |
|---|---|---|---|---|
| engine | L-Cfg CoreConfig 静态残留 | Runner.java:79-84 | 确认（low） | CoreConfig 字段 public static；一 run 一 JVM 下无影响 |
| engine | L-Ovf maxTicks int 溢出 | Runner.java:183；RunSpec:29；Main:133 | 确认（low，降级成立） | CLI 钳制 1..3600；仅库调用面 |
| engine | L-Oom OOM 文案硬编码 | Runner.java:55,134 | 确认（low） | `contains("vm error: java.lang.OutOfMemoryError")`；可被程序伪造（NEW-1） |
| engine | L-Close close 后静默丢记录 | Trace.java:60-61 | 确认（low） | 无计数；当前无触发路径 |
| engine | L-Flush 每记录 flush | Trace.java:71-73 | 确认（info，有意权衡） | 注释明示崩溃可读性动机 |
| engine | L-TQ TraceQuery 缺字段 NPE | TraceQuery.java:66,69,111 | 确认（low） | read() 容错只包 JSON 解析 |
| engine | L-Name storedName 畸形名 | Runner.java:441-444 | 部分确认（low） | 空串 NPE（NEW-3）、".." 未拦；CLI/MCP 已挡 |
| engine | L-Clock tick/ms 错位 | Clock.java:8,19-21；Trace:64-65 | 确认（low） | 亚毫秒级，不影响判定 |
| engine | L-Big 巨型方法/魔法数 | Runner.java:64-178,181-257,270-279 | 确认（low） | 反射失败静默 return null |
| fixture | F3 longValue 无上限 | Fixture.java:517-523 等 | 确认（low） | 动态 `{"capacity":1e999}`：validate 通过、build 取值 0（两阶段不一致） |
| fixture | F4 解析无大小上限/toLua 无深度 | Fixture.java:73-113；JsonValues:107-124 | 确认（升 medium） | 动态：深嵌套 event.args 20k 层 → StackOverflowError、exit 1、无 summary（ENG-FX-02） |
| fixture | F5 timeline 不排序 | Fixture.java:148-159；Runner:189-196 | 部分确认（low） | 不排序不构成正确性问题；每 tick 全表 O(ticks×actions)；超 timeout 动作静默 |
| fixture | F7 ItemStack.count 非 volatile | ItemStack.java:12；Inventory.java:38-64 | **不成立**（无竞争） | 所有 inventory 方法 method(true) 经 mainThread.tick() 在同一 Runner 线程执行；javap MainThread.tick 在调用线程内跑任务 |
| fixture | F6 internal 错误入 trace | DispatchPeripheral.java:136-140 | 确认（low） | 无堆栈；summary.notes 受 10 条配额（NEW-6） |
| fixture | F9 HashMap 键序 | ItemStack.java:42-47 | 部分确认（low） | 对固定键集确定，非插入序；无功能影响 |
| fixture | F10 getInt 小数 | InventoryPeripheral.java:30,35 等 | 部分确认（low） | 动态 1.5→1 截断不报错；复用 CC:T 默认实现，与游戏一致 |
| cli | F7(旧) EOF awaitTermination 2h | McpServer.java:125-126 | 确认（low） | 不取消在途调用 |
| cli | F6(旧) 批量/协议声明 | McpServer.java:46,113-115 | 确认（low） | 声明 2025-03-26（允许批量）但数组统一 -32600 |
| cli | F10 endpoint URL 进错误消息 | LiveTools.java:832,835,837 | 确认（info） | token 不泄漏 |
| cli | F11 isUnanswered 子串匹配 | LiveTools.java:564-566 | 确认（low） | 改文案即静默破坏 wait 分类 |
| cli | F14 工具描述硬编码版本 | McpTools.java:33 | 确认（low） | 仅此一处；McpTools.java:866-871 是报告行号错误；双 string() 实际在 McpTools:267-272 vs LiveTools:886-891 |
| cli | F15 测试盲区 | McpServerTest/LiveToolsTest | 部分确认（low） | 30 @Test 计数准确；-32700/-32600、401/404 已有测试；真实盲区：Launcher、endpoint 伪造、深嵌套 id、500、并发 |
| mod | L1 host==null 放行 | LiveHttp.java:733-736 | 确认（low） | Host 校验先于鉴权；仅回环+token |
| mod | L2 token.txt 未 0600 | LiveSession.java:267-280 | 确认（low） | POSIX 多用户风险 |
| mod | L3 2 线程 + body 无超时 | LiveHttp.java:79-85,744-765 | 部分确认（low） | 鉴权先于 body；需持 token |
| mod | L4 全文件扫描 | LiveHttp.java:363-372,553-656；Screens:21-60 | 部分确认（low） | 受 64MB 上限约束 |
| mod | L5 500 回显/坏 manifest | LiveHttp.java:165-170,253,430-472；ComputerSnapshots:226-246 | 部分确认（low） | 校验类 400；list 无 per-entry try/catch |
| mod | L6 字符/字节、大整数、双重序列化 | JsonValues.java:48-52,117-119；ComputerTrace:108,131；TracingMethods:177-183 | 部分确认（low） | 「文档冲突」不成立；dataLength(String) 用 UTF-16 长度 |
| mod | L7 会话目录碰撞/endpoint 残留 | LiveSession.java:35,44-52,282-294 | 部分确认（low） | 同秒重开截断旧 trace；start 不清理残留 |
| 疑点 | D1 READ_ONLY 名称启发式 | PeripheralQuery.java:37,107-109 | **无法静态验证**（倾向确认） | 正则放行 getAndConsumeXxx 类名；无第三方外设实例 |
| 疑点 | D2 Windows 保留设备名 | ComputerPaths.java:19-28 | **不成立（安全影响）** | 本机 Windows11+JDK21 实测：CON/COM1 被当普通文件创建，NUL 报 FileSystemException；不构成设备访问 |
| 疑点 | 发布资产无签名/校验和 | build.yml:96,105-110；build.gradle.kts:36,39 | **确认**（升 medium） | 无 SHA256/签名；*.jar glob；gh release 不幂等 |

---

## 四、正面确认（6 项）

| # | 报告结论 | 核对结论 | 证据 |
|---|---|---|---|
| 1 | mod HTTP 安全主路径逐行无缺陷（回环绑定、Bearer 恒时比较、Host 校验、无 CORS、1MB/413、ASCII JSON） | **基本成立**（存在 host==null 例外=MOD-06；报告引用的 :812-819 只是 ASCII 转义，不是安全项） | LiveHttp.java:79（唯一 `HttpServer.create`）、:733-742、:744-756、:809-822 |
| 2 | 路径穿越四层闭环且有回归背书 | **基本成立但“闭环”过强**：每次 mount 前 sanitise、manifest checkRelative、/rom 只读、delete 限 .cctlive；handoff:407 自认 sanitise 不证明包含性（junction/symlink 可逃逸，需本机写权限） | ComputerPaths.java:21-43；SnapshotManifest.java:38-41；ComputerPathTest 4 @Test |
| 3 | engine 沙箱边界成立（纯内存 MemoryMount；HTTP 关闭；tick 绝对截止；trace 逐条 flush） | **成立** | Runner.java:88；动态 fs.move/fs 写入均在内存 mount；HTTP 关闭 |
| 4 | MCP 协议总体符合（行分帧、错误码、通知不回复、id 回显、ASCII 输出） | **成立**（小偏差见 CLI-06） | McpServer.java:107-123,135-172,389-407；Main.ascii |
| 5 | scripted 外设注入面控制良好（方法名限标识符、timeline 只引用已声明方法、sets 只写数据、参数透传） | **成立** | Fixture.java:238,311-320；ScriptedPeripheral.java:34-50 |
| 6 | README 数字诚实（20 工具、81 测试、1GB 上限、任意格子任意物品≤64） | **成立** | McpTools 4 + LiveTools 16；静态 @Test 42+30+9=81；cli:45 -Xmx1g；Fixture.java:365,421-422 + Inventory.java:67 |

---

## 五、文档（5 项）

| # | 报告结论 | 核对结论 | 修正后的事实 |
|---|---|---|---|
| DOC1 | test-results.md 54/54 与 README 81/81 漂移 | **确认（更严重）** | 实为三方三个数：docs/test-results.md:11 **54/54**、README:124 **81/81**、handoff.md:15 **41/41**；且 test-results.md 被打进发布 zip（cli/build.gradle.kts:53） |
| DOC2 | handoff.md（127KB）放根目录且被 README 引用为 LIMITATIONS 出处 | **确认** | handoff.md 127,670 B；:5 自述 audience 是 machine reviewer；README:115 指向其 LIMITATIONS/DIVERGENCES |
| DOC3 | 受众混杂；mod README 与根 README 数千字重复；joint-test 四份 | **部分确认（两处夸大）** | 受众混杂成立（5 份 joint-test* + live-test-mplayer + test-results 与 install/usage 同级）；mod README 与根 README 全文相似度仅 0.093，非数千字重复；joint-test 是 **5 份** |
| DOC4 | 测试记录三处手工同步（机制） | **确认** | test-results / README 测试表 / handoff VERIFICATION 三处手抄同一事实 |
| DOC5 | macOS 从未测试，README 应显著标注 | **部分确认** | 测试表已标注（README:128、test-results:14/81）；缺的是安装节就地提示；仓库无 bin/（由 application 插件生成在发布 zip） |

---

## 六、项目管理（GitHub 实况，2026-10-18 核对）

| 项 | 报告 | 核对（GitHub API） | 结论 |
|---|---|---|---|
| description / topics / homepage | 全空 | description=null、homepage=null、topics=[] | 确认 |
| stars / forks / watchers | 0/0/0 | stars=1（审查时为 0）、forks=0、watchers=1、subscribers=0 | 数值已变化（1 star），不改变结论 |
| Issues | 0，从未使用 | real issues=0（13 个 item 全是 PR） | 确认 |
| PRs | 13 个全部 closed，全部自开自合；#6-#9 四个同名 | 13 个全部 merged、user 均 EvergardenRoxy；#6-#9 同名 "Claude/ecstatic ritchie g164bm" | 确认 |
| Releases | 4 个（v0.1.0/v0.2.0/v0.2.2/v0.2.3），各 2 资产，命名中英混杂 | 4 个、各 2 资产、无 prerelease；标题 "首个测试版发布"/"cct-run 0.2.2发布"/"cct-run 0.2.3 修复安全性等问题" | 确认 |
| Tags | 4 个，0.2.1 跳号 | v0.1.0/v0.2.0/v0.2.2/v0.2.3（无 v0.2.1）；v0.2.3 指向 `f31082f`，落后 HEAD 9 提交 | 确认 + 新风险 BLD-14 |
| 分支 | main + multi-version-build 已合并未删除 | 远程 2 个：main、multi-version-build；本地另有 1018、neoforge-1.21.1 | 确认 |
| 社区/治理文件 | 无 CONTRIBUTING/COC/SECURITY/CHANGELOG/模板/dependabot | 根目录与 .github 枚举均无 | 确认 |
| 作者构成 | Claude 40、Evergarden_Roxy 31、占位 1（72 commits） | git shortlog：Claude 40、Roxy 21+10=31、cct-run review 1；共 72 | 确认 |
| 第三方分发 | Modrinth/CurseForge 无结果 | 报告联网结论；本次未复核（GitHub 分发为主） | 未复核 |
| 许可与合规 | MPL-2.0 + CCPL 隔离 + NOTICES 对齐良好 | 对齐成立；NOTICES 手工维护无 CI（BLD-12） | 确认 |

---

## 七、审查报告本身的偏差/需修正处（核对结论）

1. **F1 依据错误**：gson 2.11.0 并无 255 层上限、JsonParser 解析是迭代的（实测 1,000,000 层 OK）；「深嵌套会走正常 JsonParseException，故降级」不成立。但另有真实 SOE 路径：`timeline.event.args` 深嵌套 → `JsonValues.toLua` 递归 → StackOverflowError（20k 层实测进程崩溃、无 summary）。
2. **C2 前提错误**：同一 255 层说法；实测 2 MB 深嵌套行、8 MB 行 MCP 服务正常。真正 SOE 在**响应序列化**（请求 id 深嵌套 → `send()` 递归 → exit 1）。
3. **E1 风险反了**：MemoryMount.rename 首行与 FileSystem.move 上游都拒绝真 inside-self；动态 `fs.move("a","a/x")` 得到与游戏一致的 "Can't move a directory inside itself"；/a→/ab 两步改名是正确补偿（仅非原子→NEW-2）。
4. **F7 不成立**：ItemStack.count 非 volatile 但所有 inventory 方法都在同一 Runner 线程（method(true) + mainThread.tick 在调用线程执行），当前无数据竞争。
5. **D2 不成立（安全影响）**：Windows 11 + JDK 21 实测 CON/COM1 被当普通文件创建、NUL 报错，不构成设备访问；可降为提示。
6. **级别偏高**：H3/H4 高→中（项目已自述为已知限制 / 当前未漂移）；C1/C3/C4/E2/E3/E4/M2/M4/M5 → 低。旧 P0 的 A9（endpoint token）仍建议 medium，故 issues 里保留。
7. **量级/比例修正**：M1「数小时数百MB」→ 约 9-15MB/h/台；M2「95%」→ 84/82/80%（Jaccard 73/69/69.5）；B4「70%、1.120.2 四处」→ 80.5%、3 处；DOC1 实为 54/81/41 三方。
8. **明确不成立的说法**：「forge/neoforge 无 test 源集」（两处已接 common test，3 类 9 @Test）；「mod README 与根 README 数千字重复」（相似度 0.093）；「joint-test 四份」（5 份）；「H1 可伪造 report.done(true)」（动态+静态均不可达）。
9. **行号漂移**：E2 250-255→252-255；E3 146-158→152-157；E1 29-44→29-46；L-Big 76-176/183-268→64-178/181-257；F2 302-303→306、64 只 365；C4 实际 JavaFinder:73-90,125-131；L14 的 McpTools.java:866-871 不存在（实际 267-272）；H4 的 catalog gson 在 toml:4；README badge 在 :3。
10. **基线表述**：审查报告说 v0.2.3 = commit bf68ac4 正确，但 tag v0.2.3 在 9 个提交之前（`v0.2.3-9-gbf68ac4`），复现时应 checkout bf68ac4 而非 tag。
11. **H2 细节**：DISK_CAPACITY 不是「只用于 getFreeSpace」——它是 `new MemoryMount(DISK_CAPACITY)` 的容量，`getCapacity` 报 1,000,000，`getFreeSpace` 实报 `unlimited`；「写入无限制」结论正确。
12. **正面确认的引用偏差**：路径「四层闭环」应改为「多层输入过滤」（sanitise 不是包含性证明）；HTTP 安全条目的 :812-819 引用的是 ASCII 输出转义，不是安全项。

## 八、新增发现（报告未列，NEW）

| ID | 级别 | 位置 | 摘要 | 已并入 |
|---|---|---|---|---|
| NEW-1 | 高/中 | harness.lua:14,37,60-71；Runner.java:134 | 受测程序可写 `_G` 篡改 tostring/printError/debug，伪造 out_of_memory 或让 harness 崩成 timeout | ENG-RT-02 |
| NEW-2 | 低 | RootMount.java:34-42 | 两步改名非原子，崩溃残留 /.cctrun-rename | ENG-RT-14 |
| NEW-3 | 低 | Runner.java:441-444 | programName="" → storedName NPE | ENG-RT-11 |
| NEW-4 | 低 | RecordingMachine.java:76-81；Runner.java:136 | 保护链外 OOM/VM error 不参与状态判定 | ENG-RT-15 |
| NEW-5 | 中 | Fixture.java:283-286；JsonValues.java:107-124 | 深嵌套 event.args → toLua 递归 SOE，无 summary（实测复现） | ENG-FX-02 |
| NEW-6 | 低 | Fixture.java:148-159；Runner.java:189-196 | timeline 无条数上限、超 timeout 动作静默丢弃 | ENG-FX-04 |
| NEW-7 | 低 | DispatchPeripheral.java:136-140；Trace.java:30,80-91 | notes 受 10 条配额，summary.notes 不完整 | ENG-FX-05 |
| NEW-8 | 中 | McpServer.java:159-162,389-396 | 深嵌套 id 在 send() 序列化 SOE，MCP 进程 exit 1（实测） | CLI-03 |
| NEW-9 | 低 | Main.java:100-102,253-267 | OOM 逃逸时把 summary JSON 写进 MCP 协议流 | CLI-13 |
| NEW-10 | 低 | LiveHttp.java:695-713 | onServer 超时后已开始任务仍执行（503 但生效） | MOD-14 |
| NEW-11 | 低 | LiveHttp.java:363-372 | recordAt 半行容错缺失，status?kinds= 可 500 | MOD-15 |
| NEW-12 | 低 | LiveHttp.java:342,644-645 | screen_hash 32 位；summary seq/tick 强转 int | MOD-16 |
| NEW-13 | 中 | build.gradle.kts:5；build.yml:104 | tag v0.2.3 后 9 提交版本号未变，推 v0.2.4 会发布 0.2.3 资产 | BLD-14 |
| NEW-14 | 中 | cli/build.gradle.kts:53；docs/test-results.md:11 | 过期测试记录被打进 0.2.3 发布包 | DOC-01 |
| NEW-15 | 低 | handoff.md:15 | handoff status 仍写 41/41，误导后续审查者 | DOC-01 |
| NEW-16 | 低 | .github/workflows/build.yml:16,18 | 三个已合并分支仍在 push 触发列表 | BLD-09 |
| NEW-17 | 低 | .gitattributes:1；mods.toml:1 | 工作树行尾依赖 core.autocrlf，是哈希不可比根因 | BLD-07 |
| NEW-18 | info | build.yml:34,36,45,60 | actions 全用主版本 tag、无 wrapper 校验（与 L10 同源） | BLD-10 |
| NEW-19 | 低 | LiveSession.java:177-201 | 记录模式 attach 时 drain 早于 trace 锁 | MOD-21 |
| NEW-20 | 低 | LiveSession.java:232-246 | 区块卸载误报 removed | MOD-22 |
| NEW-21 | 低 | LiveMachine.java:91-94,110-113 | peripheral call id 每 VM 一个，reboot 重置 | MOD-23 |
| NEW-22 | 低 | PeripheralQuery.java:57-66 | side/网络名重名静默覆盖 | MOD-24 |
| NEW-23 | 低 | ComputerTrace.java:72-85,111-114 | 轮换/读取竞争、写失败永久停写（旧 B4） | MOD-17 |
| NEW-24 | 中 | ComputerSnapshots.java:92-126 等 | 快照非原子/body-session 清理/同 ID 冲突（旧 M2-M4） | MOD-18/19/20 |

## 九、动态复现记录（Lead 实测）

环境：Windows 11；Zulu JDK 21.0.12.1；已构建 `CC-Tweaked_MCP/cli/build/install/cct-run`（0.2.3，含真实 cc-tweaked-core 1.120.2 + cobalt 0.9.9）。启动脚本参数：`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss4m -Xmx1g`。

| 探针 | 命令/文件 | 观察 | 结论 |
|---|---|---|---|
| H1 debug | `evidence/dynamic/debug_probe2.lua` | 16 个 debug 函数全可用；registry 0 threads；栈无 harness 帧；0 个 report upvalue；`_G.__cctrun=nil` | 工具箱完整；report.done 伪造链不可达 |
| H2 fs 写 | `evidence/dynamic/fs_write.lua` | 20 MB 写入成功；size=20971520；free="unlimited"；capacity=1000000；heap_peak=158MB | 写入不受容量限制 |
| E1 fs.move | `evidence/dynamic/fs_move2.lua` | inside-self 拒绝（与游戏一致）；/d→/de 成功；child→parent File exists；/a.log→/a.log.1 成功 | 行为与游戏一致 |
| F1 深嵌套 | `evidence/dynamic/nest_*.json` + GsonDepth.java | 200…1,000,000 层全部解析成功；GsonDepth 直测 1,000,000 层 OK | 无 255 上限、无 SOE |
| F3 Infinity | `evidence/dynamic/energy_inf.json` | validate 通过；程序读到 capacity=0（build 阶段 getAsLong） | validate/build 两阶段不一致 |
| F10 getInt | `evidence/dynamic/inventory_probe.lua` | getItemDetail(1.5) 返回槽 1；"1" 报 number expected；0/-1/4 Slot out of range | 截断，与 CC:T 默认实现一致 |
| NEW SOE | `evidence/dynamic/event_deep_*.json` | 1000 层 status=error；20000/100000/300000 层 StackOverflowError、exit 1、无 summary | 真实崩溃路径 |
| C2 MCP | `evidence/dynamic/mcp_probe2.ps1` | 1M 层（2MB）行、8 MB 行均正常返回 tools/list；服务存活 | 无行长限制但也无 SOE；残余 OOM 面 |
| C NEW | 同上 + 深嵌套 id | id 为 20000 层数组 → send() SOE、exit 1 | 响应序列化 SOE |

产物：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/dynamic/`（脚本、fixture、run 目录）。核对 lane 原始表：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/lanes/*.md`。

## 十、结论与优先级

1. **必须立即处理（P0）**：ENG-RT-01（fs 写爆堆/OOM 归因）、ENG-RT-02（_G 篡改 + done token）、CLI-01（endpoint URL 校验）、ENG-FX-02（fixture SOE 崩进程）、BLD-02 + BLD-14（版本单源 + tag 校验）。
2. **近期（P1）**：H3/BLD-01 smoke 接入 CI + 供应链扫描；MOD-01 kinds 窗口 + MOD-17/18/19/20；C1/C2 相关；DOC-01 测试数字单一来源；SECURITY.md。
3. **卫生（P2）**：其余 low/info 条目按模块批量清理。

> 说明：本表只反映静态核对 + 有限动态探针结果；mod 的 HTTP/平台行为、Windows 保留名与跨版本构建未做全量实测。
