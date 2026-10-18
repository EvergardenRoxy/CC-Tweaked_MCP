# 核对结果 D：mod lane（只读核对）

基线：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`，HEAD = `bf68ac478f50065e9d18808201f0e2071a580b37`（on main，Merge PR #13 multi-version-build）。
**注意**：HEAD 哈希与任务卡一致，但 `git describe --tags --always` = `v0.2.3-9-gbf68ac4`（在 tag v0.2.3 **之后** 9 个提交）；"v0.2.3" 来自 `build.gradle.kts:5 version = "0.2.3"`，不是 HEAD 所在的 tag（见偏差 §3-1）。
核对方式：read/grep/glob；PowerShell `Compare-Object` 做逐行 diff；`javap -p` 对 Gradle 缓存里真实 CC: Tweaked 1.120.2 jar（1.20.1-forge 与 1.21.1-forge）核对反射字段；本机 JDK 21 小探针核对 Windows 保留设备名的 NIO 行为。**未运行 gradlew 构建，未改仓库任何文件。**

## 1. 汇总表

| ID | 审查级别 | 实际位置 | 审查意见（简） | 核对结论 | 证据与说明 | 建议级别 |
|---|---|---|---|---|---|---|
| M1 | 中 | `mod/src/common/java/cctrun/live/ComputerTrace.java:38,125,142-155,189-199`；`LiveHttp.java:363-372,553-656`；`Screens.java:21-60` | kinds 索引随记录总数无上限增长，rotate 不裁剪；status/wait 线性扫描 | **部分确认** | `:125 kinds.add(kind.intern())`；`:142-155 rotate()` 只切文件/重置 bytes，`kinds` 从不裁剪；`:189-193`/`:196-199` 线性扫；量级不成立（约 10-15 MB/h/台，非"数小时数百 MB"） | 中（维持；量级降为低） |
| M2 | 中 | `mod/forge/.../CctLive.java`(111) vs `mod/neoforge/.../CctLive.java`(109)；两份 `LiveConfig.java`(71/71)；两份 `build.gradle.kts`(82/79) | 平台层约 95% 重复，日志一字不差；构建约 70% 重复 | **确认**（比例需改写） | `git diff --no-index --numstat`：CctLive 18 删/16 增 → 相同 93 行（84%）；LiveConfig 13/13 → 相同 58 行（82%）；build 16 删/13 增 → 相同 66 行（80%，Jaccard 69.5%≈70%）；扣除平台 API 名后≈100% 同构 | 低（可维护性，非中） |
| M3 | 中 | `CctLive.java:55-63`(forge)/`:54-62`(neo)；`LiveConfig.java:24-27`；`LiveSession.java:44-52,251-261,282-294`；`LiveHttp.java:76-90` | 端口固定 47823、无 0 回退；bind 失败日志笼统；endpoint.json 时序 | **部分确认** | `LiveConfig.java:27 defineInRange("port",47823,1024,65535)`；`:79 HttpServer.create(...)` 同步抛 BindException；`LiveSession.java:49` 先设 current、`:50` 已打会话日志，`CctLive.java:61` 却报 "could not start"；endpoint.json 仅在 bind 成功后写（`:260`）、close 无条件删（`:287`） | 中（维持） |
| M4 | 中 | `PeripheralQuery.java:39-51,69-82,107-109`；`Terminals.java:16-26`；`mods.toml:24-28` | 反射绑定 3 个 CC:T 私有字段，版本漂移即 ExceptionInInitializerError 级联；靠 mods.toml pin | **确认**（措辞略夸大） | `PeripheralQuery.java:43 getDeclaredField("peripheralWrappers")`、`:46 wrapper.getDeclaredField("peripheral")`；`Terminals.java:21 getDeclaredField("terminal")`；两者都在 `static {}`；javap 证实两版本 jar 字段均存在；`mods.toml:26 versionRange="[${cctVersion}]"`→ 精确 1.120.2 | 低（有精确 pin + 明确报错信息） |
| M5 | 中 | `mod/src/common/test/java/cctrun/live/`（3 类 9 @Test）；两平台 `build.gradle.kts:23-25`；`handoff.md:408-409` | 平台层 0 测试；HTTP 鉴权/Host/413、trace 轮转、JsonValues、Ring、Screens.replay 无测试 | **部分确认** | 3 类 9 @Test（ComputerPathsTest 4、SnapshotManifestTest 4、Utf8Test 1）；"forge/neoforge 无 test 源集"**不成立**（两处显式 `test { java.srcDir(.../common/test/java) }`）；所列无测试项均属实 | 低-中 |
| L1 | 低 | `LiveHttp.java:733-736` | Host 校验放行 host==null | **确认** | `:735 return host == null \|\| host.equals("127.0.0.1:"+port) \|\| ...`；未修 | 低（维持） |
| L2 | 低 | `LiveSession.java:268-280` | token.txt 未设 POSIX 0600 | **确认**（POSIX 平台） | `:278 Files.writeString(file, token + "\n", UTF_8)`，无 `setPosixFilePermissions`；Windows 走 ACL 继承，不适用 | 低（维持） |
| L3 | 低 | `LiveHttp.java:79-84,744-765` | 2 线程 + body 无读取超时，持令牌慢 POST 可占满线程池 | **部分确认** | `:80 Executors.newFixedThreadPool(2, ...)`；`:79` backlog 16；`:755 readNBytes(MAX_READ+1)` 无 socket/read 超时；但鉴权先于读 body（`:108`），攻击者须已持有 token | 低（维持） |
| L4 | 低 | `LiveHttp.java:363-372,553-656`；`Screens.java:21-60` | trace/回放每次全文件读取并逐行解析 | **部分确认** | `:596-629` 全文件逐行 `JsonParser.parseString`；`:365-368` recordAt 全文件找 seq；`Screens.java:25-58` 全文件回放（`:36` 超 tick 才 break）；但文件被 64 MB 上限约束（`LiveConfig.java:34`） | 低（维持，补"上限内"） |
| L5 | 低 | `LiveHttp.java:165-170,253,430,452,472,759-763`；`ComputerSnapshots.java:226-246` | 客户端错误多落 500 且回显 e.getMessage()；坏 manifest 拖垮整个 list | **部分确认** | 校验类错是 400（`:166`），IO 类错 `BadRequest(500, "... : " + e.getMessage())`（`:253/430/452/472`）；`:169 "internal: " + e`；`ComputerSnapshots.java:232-241` 循环内 `manifest(d)` 无 try/catch，一行坏 manifest 中断整个列表 | 低（维持） |
| L6 | 低 | `JsonValues.java:48-52,117-119`；`ComputerTrace.java:108,131` | 截断按字符、预算按字节；>2^53 丢精度；recordAt 双重序列化 | **部分确认** | `:117-119` 按 `s.length()`/substring；`:108` 估算 `GSON.toJson(data)` + `:131` 再序列化整行；`:50` 阈值是 `Math.abs(d)<1e15`（1e15~2^53 值不丢但写成 double 形式）；"文档冲突"**不成立**（`LiveConfig.java:35`、`README.md:85` 均写"字符数"） | 低（维持） |
| L7 | 低 | `LiveSession.java:35,46,282-294`；`ComputerTrace.java:56,166-168` | 会话目录秒级碰撞；崩溃时 endpoint.json 残留 | **部分确认** | `:35 "yyyyMMdd-HHmmss"` + `:46` 目录名；同秒重开时 `:165 Files.createDirectories` 复用目录 → `ComputerTrace.java:56 newBufferedWriter` 截断旧 trace；`endpoint.json` 仅正常 stop 删（`:287`），start（`:44-52`）不清理崩溃残留 | 低（维持） |
| D1 | 疑点（中低） | `PeripheralQuery.java:37,107-109,266(LiveHttp)` | READ_ONLY 白名单是名称启发式，第三方外设 getAndConsumeXxx 可绕过 | **无法静态验证**（倾向部分确认） | 正则 `(get\|is\|has\|can\|list\|size\|tanks\|count)([A-Z_0-9].*)?` 确实放行 `getAndConsumeX` 类名；但 `Access.queueEvent` 丢弃事件、mount 返回 null（`:185-206`），"改世界"的实际效果需真实第三方外设实例才能确认 | 低（维持疑点） |
| D2 | 疑点（低） | `ComputerPaths.java:21-30` | Windows 保留设备名（CON/NUL）不在路径校验范围 | **不成立**（代码事实正确，安全影响不成立） | `sanitise` 只拒 `..` 与 `:`，确无保留名检查；但本机 Windows 11 + JDK 21 NIO 探针：`dir.resolve("CON")` 的 `Files.writeString` 成功并在目录里生成普通文件（size=9，可读回），`NUL` 抛 `FileSystemException` → 不构成访问宿主设备 | 提示（降级） |
| P1 | 正面 | `LiveHttp.java:79,733-736,738-742,744-756,809-822` | HTTP 安全主路径 | **部分确认** | 绑定 loopback（`:79`）+ Host 白名单（`:734-735`）+ 常量时间 token 比较（`:741`）+ 1 MiB body 413（`:748-756`）成立；引用的 `:812-819` 是 ASCII 输出转义，**不是**安全项；`host==null` 例外即 L1 | 保持（补两条说明） |
| P2 | 正面 | `ComputerPaths.java:21-43`；`ComputerFiles.java:51,83,99,139,167,169`；`SnapshotManifest.java:38,41` | 路径穿越四层闭环 | **部分确认** | 四层都在：每次 mount 前 sanitise、manifest 路径 `checkRelative`、`/rom` 只读、delete 限 `.cctlive`；但不是"闭环"：无真实路径包含性校验（`handoff.md:407/L26` 自认），junction/符号链接仍可逃逸（需本机写权限） | 保持（措辞降级） |
| P3 | 正面 | `LiveHttp.java:695-713`；`ComputerTrace.java:99,189-201`；`LiveSession.java:69,72,74-75` | 线程模型 | **确认**（带边界） | HTTP 线程 → `onServer(server.submit).get(5s)`（`:695-713`）；trace 方法 synchronized；attached=ConcurrentHashMap，traces 只服务器线程；边界：`:706 future.cancel(false)` 不能停已开始任务（见 NEW-1） | 保持 |
| P4 | 正面 | `LiveSession.java:44-58,155-223,226-247,282-294` | 会话生命周期 | **部分确认** | start→stop→close 闭环、lease 每请求续、detach 原因分类、endpoint.json 随世界关闭删除；但区块卸载走 `"removed"`（`:234-235`）会误报"电脑已被移除"，崩溃不清理（L7） | 保持（注明限制） |
| P5 | 正面 | `cctlive.lua:4-6,12-23`；`BridgeApi.java:24-26,33-41`；`CctLive.java:47` | autorun 注入 | **确认** | 脚本先 `rawget(_G,"__cctlive")` 再置 nil，包装 `printError` 后 `pcall(bridge.printedError,...)` 并调用原函数；`BridgeApi.getNames()={"__cctlive"}`；数据包路径 `data/computercraft/lua/rom/autorun/cctlive.lua` 经 `processResources` 进 jar，CC 的 `ResourceMount` 合并 server ResourceManager 里的 `computercraft` 命名空间（handoff V18 已在游戏内验证 printed_error 落 trace） | 保持 |
| P6 | 正面 | `mods.toml`(forge 28 行) / `neoforge.mods.toml`(35 行)；两份 `build.gradle.kts` | 双平台元数据 | **确认** | 两 toml 均 modId/version/displayName/description/license/loader+computercraft 依赖齐全，`versionRange="[${cctVersion}]"` 精确 pin；差异仅加载器声明方式（forge 额外 forge 依赖 + `loaderVersion="[47,48)"`；neoforge 额外 minecraft 依赖） | 保持 |

## 2. 关键条目明细

### M1（部分确认）
- 增长：`:125 kinds.add(kind.intern())` 每写一条记录追加一项，`rotate()`（`:142-155`）只做 close/`Files.move`/新建 writer/重置 `bytes`/`firstSeq`/`currentFirstSeq`，**从不裁剪 `kinds`**（也无基准偏移）。索引语义 `kinds.get(n-1) ↔ seq n` 在轮换后仍然成立、不会错位；问题只是"整个会话"的内存无界。
- 扫描：`:189-193 countsAfter` 从 `after` 起线性扫到末尾；`:196-199 firstAfter` 同。`status`（`LiveHttp.java:350-358`）调用两者；`wait` 通过 status 轮询。典型 `after=last_seq` 时是 O(增量)，**全表扫描是 after 落后时的最坏情况**（审查说"线性全表扫描"偏绝对，见偏差 §3-4）。
- 量级修正：每项约 8 B 引用（+ArrayList 容量 1.5 倍），kind 字符串 `intern()` 后是少量常量，不重复占内存。按 `README.md:220`（30 台全部接管约 80 MB/min）→ 每台约 2.7 MB/min；若平均记录 100–150 B，则约 1.8–2.7 万条/min → **约 9–15 MB/h/台**，数十小时才会到数百 MB。审查报告 B3 的"数小时可达数百 MB"偏高；但"磁盘 64 MB 双半区配额管不住内存"这一核心判断成立。
- 与 `LiveHttp` 全文件扫描的关系：`trace()`（`:596-629`）即使 `limit=50` 也解析整个（最多 64 MB）文件以统计 matched/next_offset；`recordAt`（`:363-372`）全文件找 seq；`Screens.replay`（`:25-58`）全文件回放。三者都被 `trace.max_megabytes_per_computer`（默认 64，`LiveConfig.java:34`）**限制**，不是无限恶化 → L4 同样降级为低。
- 建议级别：中可保留（长会话 + 常轮询），但量级描述改小；修复建议仍是"窗口化 kinds（rotate 时以 firstSeq 为基线丢弃旧段）"或"每 kind 计数 + 分段 seq 列表"。

### M2（确认，比例需改写）
实测（`git diff --no-index --numstat` 的增/删行数 + `Compare-Object` 交叉核对）：
| 文件 | 行数 | 差异（删/增） | 完全相同行 | 逐行同一比例 | 差异行占比（=1−同构度） |
|---|---|---|---|---|---|
| forge/neoforge `CctLive.java` | 111 / 109 | 18 / 16 | 93 | 93/111 = **84%**（93/109 = 85%） | 34/127 = 27%（同构 73%） |
| forge/neoforge `LiveConfig.java` | 71 / 71 | 13 / 13 | 58 | **82%** | 26/84 = 31%（同构 69%） |
| forge/neoforge `build.gradle.kts` | 82 / 79 | 16 / 13 | 66 | 66/82 = **80%**（66/79 = 84%） | 29/95 = 31%（同构 **69.5%≈70%**） |

- `CctLive` 的 17 对差异**全部**是平台 API：`ModLoadingContext.get().registerConfig` vs 构造器 `ModContainer.registerConfig`（forge `:40-42` / neo `:40-41`）、`MinecraftForge.EVENT_BUS` vs `NeoForge.EVENT_BUS`、`TickEvent.ServerTickEvent`+phase 判断（forge `:69-70`）vs `ServerTickEvent.Post`（neo `:68`）、以及对应 import。命令注册、chat announce、日志文案逐字相同（含中文 fallback）。
- `LiveConfig` 13 对差异全部是 `ForgeConfigSpec` ↔ `ModConfigSpec`（import、SPEC 与 11 个 field 声明的类型、builder），所有 key/默认值/注释（含中文）相同。
- `build.gradle.kts` 实质差异 6 处：plugin（`legacyforge` vs `moddev`）、`mcVersion`（1.20.1/1.21.1）、loader 版本变量与块（`legacyForge{enable{...}}` vs `neoForge{version=...}`）、`options.release`（17/21）、依赖 scope（`modImplementation` vs `implementation`）、forge 额外 `compileOnly("cc.tweaked:cobalt:0.9.9")`（forge `:65`）；`sourceSets`（含 common/test）、JUnit/Gson 依赖、`processResources expand` 逐行相同。
- 结论："日志文案一字不差""行为修复需双改（平台层）"成立，但"约 95%"只对"归一化平台 API 之后"成立（此时≈100%），逐行原始只 **84%/82%**（build 80%）；"构建约 70% 重复"对应的是"差异行/总变更量"口径（同构 69.5%），按文件行数则 80%。级别建议降为低（纯重复/维护性，无行为缺陷）。

### M3（部分确认）
- 端口：`LiveConfig.java:26-27` 默认 47823，范围 1024..65535（**0 被下限排除**，`startHttp` 无重试/无 0 回退）；`LiveHttp.java:79` `HttpServer.create(new InetSocketAddress(127.0.0.1, port), 16)` 同步 bind，失败抛 `BindException`。
- 失败路径：`LiveSession.startHttp()`（`:251-261`）先 `new LiveHttp(...).start()`，成功后才写 `endpoint.json`（`:260`）；`LiveHttp.start()` 失败时异常直接冒泡，`CctLive.onServerStarted` 的 `catch (IOException)`（forge `:60-62` / neo `:59-61`）打 `LOG.error("cct-live could not start; computers cannot be attached", e)`。
  → "会话已建、仅 HTTP 未起"在代码里**是两种状态**：`LiveSession.java:49` 先 `current = session`、`:50` 已打 "on demand; ... attachments are written to <dir>"，随后才失败。日志顺序可区分；但错误文案把整件事说成"cct-live could not start"（与事实相反），且没有 chat/`/cctlive` 状态/工具可见信号 → 对玩家与 AI **不可区分**。
  → 同理 `endpoint.json` 写盘失败也会被这条文案误报成"mod 没起来"（HTTP 其实在跑），属同一文案问题。
- 双开实例："第二个实例 AI 接管整体失效"**成立**（同一主机抢 47823 → BindException；该 game dir 无 endpoint.json；`cct-live` 只能通过 HTTP attach，而 `/cctlive list/detach` 不提供接管）。"静默"**部分成立**：latest.log 有一条带堆栈的 ERROR，但游戏内/聊天/工具侧无提示，且 AI 侧只会看到 "找不到 endpoint"（`cli` 的 `cct-run mcp --live` 读 `cct-live/endpoint.json`）。
- 补充（NEW-2）：`LiveSession.start()` 在 `:44-52` 不清理上一次崩溃留下的 `endpoint.json`；`close()` 才删（`:282-294`）。若本次 HTTP 关闭或 bind 失败，旧文件会继续指向旧 pid/端口，`cct-run mcp --live` 可能连到"另一个实例的活端口"或死端口。
- 结论：端口固定/无回退=确认；失败日志笼统且文案错误=确认；"不可区分"=部分（日志可区分、玩家/AI 侧不可区分）；endpoint.json 时序（成功后写、关闭删）正确，但缺启动清理。建议级别维持中（双开是常见操作，失效且难发现）。

### M4（确认，措辞略夸大）
- 字段：javap 对 `cc-tweaked-1.20.1-forge-1.120.2.jar` 与 `cc-tweaked-1.21.1-forge-1.120.2.jar` 均确认
  `ServerComputer.terminal`（private final NetworkedTerminal）、`WiredModemPeripheral.peripheralWrappers`（private final Map<IComputerAccess, ConcurrentMap<String, RemotePeripheralWrapper>>）、`WiredModemPeripheral$RemotePeripheralWrapper.peripheral`（private final IPeripheral）。
- 初始化时机：三处都在**类静态块**：`PeripheralQuery.java:41-51`（`:43`、`:46` 两个 field，catch `ReflectiveOperationException` → `IllegalStateException("cct-live needs CC: Tweaked 1.120.2 (WiredModemPeripheral internals)")`）；`Terminals.java:18-26`（`:21` field，catch `NoSuchFieldException` → `IllegalStateException("cct-live does not match this CC: Tweaked version (ServerComputer.terminal)")`）。因此是**首次使用**才触发（`Terminals` 首次在 `Attachment.poll:144-146`/`LiveHttp.screen:303-307`；`PeripheralQuery` 首次在 `LiveHttp.peripheral:266`），不是 mod 构造期。
- 级联真实性：JVM 会把静态初始化抛出的异常包装为 `ExceptionInInitializerError`（后续使用 `NoClassDefFoundError`）。`Terminals` 若坏：`LiveSession.tick` 的 `catch (RuntimeException)`（`:239-243`）抓不到 Error → 服务器 tick 报错；HTTP `attach/screen/status` 会把 Error 包成 `IllegalStateException` → 500。`PeripheralQuery` 若坏：`LiveHttp.handle` 的 `catch (Exception)`（`:167`）抓不到 Error → 连接被 HttpServer 直接断开，**不是** 500 JSON。上述与"级联"一致，但两处异常消息已明确写出所需版本与字段，定位不难。
- pin：`mods.toml:24-28` 与 `neoforge.mods.toml:30-35` 的 `versionRange="[${cctVersion}]"`，`processResources` 展开 `cctVersion=1.120.2`（forge `build.gradle.kts:11-12,78-82`）→ Maven 单版本区间 = **精确 1.120.2**。因此"版本漂移即级联"实际只在有人手工改 pin/换 jar 时发生。
- 结论：确认；建议从"中"降为"低"（有精确 pin + 明确报错 + 仅 3 处、集中），修复方向仍是集中到 `CcInternals` 适配类 + 启动自检。

### M5（部分确认）
- 测试清单：`mod/src/common/test/java/cctrun/live/` 仅 3 类、9 个 `@Test`：`ComputerPathsTest`（4：normal/parent/checkRelative/drive-letter）、`SnapshotManifestTest`（4：reads/escape-file/escape-dir/no-files）、`Utf8Test`（1：bytes-not-chars）。
- "forge/neoforge 无 test 源集"**不成立**：两平台 `build.gradle.kts:23-25` 都有 `test { java.srcDir(rootProject.file("mod/src/common/test/java")) }`，测试随两个变体各自编译运行；准确说法是"没有平台专属测试类"。
- 所列无测试项属实：HTTP 鉴权/Host/413、`ComputerTrace` 轮转、`JsonValues`、`Ring`、`Screens.replay`、`PeripheralQuery`、`LiveSession` 均无测试；`handoff.md:408-409`（L27/L28）自认 "B0 路径 ... 已游戏内验证；endpoint.json cleanup 和 live trace tolerance 仍无自动化测试 / A12、A8 无自动化测试" —— 但 `:407` 是 L26（sanitise 不是包含性校验），不是测试缺口，引用范围偏 1 行。
- 建议：级别低-中（安全主路径已有 9 个单测 + 游戏内 V28/V30 验证，但 HTTP 层整体仍无 harness）。

### L1–L7 要点
- **L1**：`:733-736` 放行 `host == null`；Host 检查在鉴权之前（`:104-111`），但缺 Host 的客户端拿不到 token 也只会 401，浏览器一定带 Host → 纵深缺口，确认。
- **L2**：`:268-280` 读/建 token.txt，`Files.writeString(...UTF_8)` 无权限收紧；POSIX 下默认 umask（通常 0644）→ 同机其他用户可读 = 完全控制 HTTP 面（绑定 loopback 不挡本机用户）。Windows 为 ACL 继承，不适用。
- **L3**：`Executors.newFixedThreadPool(2)`（`:80-84`）+ `com.sun.net.httpserver` 默认无请求体读取超时 + `:755 readNBytes(MAX_READ + 1)`；但 `:108 authorised` 在 `:133 body(exchange)` 之前，慢速 POST 需有效 token；backlog 16。确认（低）。
- **L4**：`trace()`/`recordAt`/`replay` 都全文件读+逐行 `JsonParser`；`:596-629` 即使只回 50 条也扫全文件。**但** trace 落盘被 64 MB 上限分成两个 ≤32 MB 的半区（`ComputerTrace.java:109` 条件 `bytes + estimate > maxBytes/2`），延迟有界。建议把"随文件增大"改为"增长到 64 MB 上限后恒定（单次请求最坏解析 64 MB）"。
- **L5**：错误分类是混合的：参数/路径/状态类 → `BadRequest(400/403/404/409)`；IO 类 → `BadRequest(500, "... : " + e.getMessage())`（`:253/430/452/472`），未捕获异常 → `500 "internal: " + e`（`:169`，回显异常类名与消息，可能含宿主机路径）。`ComputerSnapshots.list`（`:226-246`）循环里 `manifest(d)`（`:235`）无 per-entry try/catch：一个 `JsonSyntaxException` → 整体 500；一个 `checkRelative` 违规 → 整体 400，且没有任何"部分结果/skipped"标记。确认。
- **L6**：`truncate`（`:117-119`）按 UTF-16 字符数截断；文件预算按 UTF-8 字节（`ComputerTrace.java:108/135`）——这是两种不同口径，但 `LiveConfig.java:35` 与 `README.md:85` 都明确写"字符数"，**没有文档冲突**。整数：`:50` 仅当 `d == rint(d) && |d| < 1e15` 才写 long，1e15–2^53 之间写成 double（Gson 输出如 `1.0E15`，值不丢但类型/格式变了），>2^53 才丢精度（而 Lua 侧本身是 double，源头就丢）。`recordAt` 双重序列化（`:108` 估算 + `:131` 正式）确认。
- **L7**：目录名 `yyyyMMdd-HHmmss`（`:35,46`）；同一秒内结束再开会复用同一目录（`Files.createDirectories` 不报错），随后 `ComputerTrace.java:56 newBufferedWriter` 截断同名 `computer-<id>.jsonl`（`-2` 后缀只在同目录内文件已存在时加）；`endpoint.json` 只在 `stop/close` 删（`:287`），崩溃（kill -9/断电）留残留，且下次 `start` 不校验 pid/port。确认（低）。

### D1（无法静态验证）/ D2（不成立）
- **D1**：`READ_ONLY = (get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)?`（`:37`）是纯名称白名单，`getAndConsumeXxx`、`listAndClear` 之类都会通过；`:107-109` 只在方法名命中查询表后校验。缓解：`Access` 丢弃 `queueEvent`（`:203-206`）、`mount/mountWritable` 返回 null、查询结果只读。是否真有第三方外设"getter 改世界"无法从仓库静态验证（无此实例），维持疑点，级别低。
- **D2**：代码事实确认（`sanitise` 只拒 `..` 与 `:`，无 CON/NUL/AUX/COM1 黑名单）；但安全影响不成立：本机探针（Windows 11 + Zulu JDK 21，`Files.writeString(Files.createDirectory(tmp).resolve("CON"), "hello-con")`）→ 成功并在该目录生成**普通文件**（size=9，可读回，`Files.list` 可见）；`NUL` → `java.nio.file.FileSystemException`（"参数不正确"）；`COM1` 同样生成普通文件。Java NIO 在 Windows 上以绝对路径打开时未解释设备名，因此不构成"访问宿主设备/新增权限"；仅剩"奇怪文件名"的可用性瑕疵。建议降级为提示（该行为依赖 JDK/OS，未在游戏内验证）。

### P1–P6（抽查）
- **P1**：主路径成立（loopback、Host 白名单、常量时间 `MessageDigest.isEqual`、1 MiB body 413、JSON 错误不回显内部栈但 `:169` 会回显异常）。不准确处：`:812-819` 是 `send()` 的 ASCII 转义（`\uXXXX`），属输出健壮性，不是安全控制；`hostAllowed` 的 `host==null` 例外就是 L1，正面条目里应带上它。
- **P2**：四层都在，但"闭环"言过其实：`handoff.md:407` 自认 sanitise 只是输入过滤、"不证明宿主路径解析后仍在电脑目录内"，没有 real-path 包含性检查；junction/symlink 仍是理论逃逸面（需本机写权限）。B0（`C:/Windows/win.ini`）已由拒 `:` + 拒 `..` 修复（D50）。
- **P3**：成立。所有触碰 Minecraft/CC 对象的操作都经 `onServer` → 服务器线程（`LiveHttp.java:695-713`），trace 写入/查询 synchronized，attached 用 CHM，`traces` 只服务器线程、`traceById` CHM，`rings` synchronized WeakHashMap。边界：`:706 cancel(false)` 超时只能取消**尚未开始**的排队任务，已开始的任务仍会在服务器线程跑完 → 请求已回 503 但副作用仍可能发生（见 NEW-1）；另外 `recordAt` 半行容错缺失（NEW-3）。
- **P4**：成立但有两条限制：区块卸载被当作 `"removed"`（`LiveSession.java:234-235`，chat 说"电脑已被移除"，实际只是没加载）；崩溃不执行 `close()`（L7）。
- **P5**：成立。`cctlive.lua` 在 autorun 里先取走 `_G.__cctlive` 再置 nil（用户程序看不到），包装 `printError` 后 `pcall(bridge.printedError, text, program)` 并且**仍调用原函数**（不吞输出）；`BridgeApi` 以 `getNames()={"__cctlive"}` 暴露给 Lua，`CctLive.java:47` 用 `ComputerCraftAPI.registerAPIFactory` 注册；`data/computercraft/lua/rom/autorun/cctlive.lua` 随 `src/common/resources` 进 jar，CC 的 `ResourceMount` 由 server `ResourceManager` 合并 `computercraft` 命名空间（handoff V18 游戏内已见 printed_error）。
- **P6**：成立。两 toml 元数据字段齐全、CC:T 依赖精确 pin；差异只是加载器声明方式（forge 额外 `forge` 依赖、`loaderVersion="[47,48)"`；neoforge 额外 `minecraft` 依赖并 `type="required"`），无遗漏或冲突。

## 3. 偏差 / 修正（含 NEW）

1. **偏差（基线）**：HEAD 哈希 = bf68ac4 与任务卡一致，但 `git describe` = `v0.2.3-9-gbf68ac4`，即 HEAD 在 tag v0.2.3 之后 9 个提交；"v0.2.3" 是 `build.gradle.kts:5` 的工程版本，不是 tag。审查报告的旧行号（如 `mod/src/main/java/...`）来自重构前布局，核对时已按 `mod/src/common/...` + `mod/forge|neoforge/...` 重新锚定。
2. **偏差（M1 量级）**："数小时可达数百 MB"不成立：约 8 B/条，按 README 的 2.7 MB/min/台 ≈ **9–15 MB/h/台**；数十小时才到数百 MB。"磁盘配额管不住内存"的结论仍正确。另：`countsAfter/firstAfter` 是 O(N−after)，不是"永远全表"；全表扫描的典型来源是 `LiveHttp.trace()` 的 matched/next_offset 统计（且被 64 MB 上限约束）。
3. **偏差（M2 比例）**：逐行完全相同 93 行（CctLive，84%）、58 行（LiveConfig，82%）、66 行（build，80%）；按"差异行/变更总量"的 Jaccard 口径为 73%/69%/69.5%（"约 70%"只对构建脚本成立）。"约 95%"只在把 `Forge*`/`NeoForge*` 类型名归一化后成立（此时≈100%）。"日志文案一字不差"准确。建议把 M2 降为低。
4. **偏差（M3 措辞）**：日志文案 `"cct-live could not start; computers cannot be attached"` 与会话已创建（`LiveSession.java:49-50` 已设 current 并打会话日志）**矛盾**；且它同时覆盖 endpoint.json 写盘失败等"HTTP 已起来"的失败。双开实例失效成立，"静默"应改为"仅 ERROR 日志 + 堆栈，游戏内/工具侧无提示"。endpoint.json 时序本身正确（成功后写、close 无条件删），但**启动不清理崩溃残留**（NEW-2）。
5. **偏差（M5 引用）**："forge/neoforge 无 test 源集"不成立（两处 `sourceSets.test` 显式接入 common 测试，测试随变体运行），准确说法是"无平台专属测试"；`handoff.md:407-409` 中 408-409（L27/L28）才是测试缺口，407 是 L26（sanitise 包含性）。
6. **偏差（L6 文档冲突）**：不成立。`LiveConfig.java:35` 与 `README.md:85` 都写"字符数"；实现也是按字符截断。真正值得记的是"每字符串按字符、trace 配额按字节"的双口径（D56 有意为之）。
7. **偏差（L4 无上限暗示）**：`trace`/`replay`/`recordAt` 的扫描量被 64 MB 上限约束，不是随会话无限增长；建议在审查意见里补"最坏 64 MB 解析/请求"。
8. **偏差（P1 引用）**：`:812-819` 是 `send()` 的 ASCII `\uXXXX` 转义（输出健壮性），不属于 HTTP 安全主路径；P1 应把 `host==null`（L1）作为已知例外写进去。
9. **NEW-1（低，部分已修）**：`LiveHttp.java:706 future.cancel(false)` 只能取消**尚未开始**的排队任务；`onServer` 超时回 503 后，已在服务器线程上跑的任务会继续执行，变更类请求（power/event/files/snapshot/attach）出现"失败但生效"。原审查 M1（onServer 不取消 Future）已修一半，建议：变更类端点改"排队+回执"，或对已开始任务在结果里标注 `pending: true`。
10. **NEW-2（低）**：`LiveSession.start()`（`:44-52`）不清理上一次崩溃留下的 `cct-live/endpoint.json`；`close()` 才删。若本次 HTTP 关闭/绑定失败，残留文件会把 `cct-run mcp --live` 指向死端口或**另一个实例**的活端口（双开时后者会静默串到别的游戏进程）。建议 start 时校验 pid/port 后删除不属于本进程的文件（README:55 的"退出时删除"没有覆盖崩溃）。
11. **NEW-3（低）**：`LiveHttp.recordAt`（`:363-372`）只对匹配行 `JsonParser.parseString` 且无 catch；并发写入/轮换产生的半行若恰好匹配 `{"seq":N,` 前缀会抛 `JsonSyntaxException` → 500。`trace()`（`:600-606`）与 `Screens.replay`（`:29-34`）已做"跳过坏行"，`status?kinds=` 路径没有。建议同样 catch 并计 `skipped`。
12. **NEW-4（提示，观察项）**：`LiveHttp.java:342 screen_hash` 是 `Arrays.hashCode(text)*31 + Arrays.hashCode(bg)` 的 32 位哈希，屏内容变化可能碰撞（旧报告 L-hash 已记录，本 lane 未列）；`summary` 分支把 seq/tick 强转 `(int)`（`:644-645`），>2^31 回绕——本 lane 的 M1 未覆盖，附带记录。
13. **D2 修正**：实测 Windows 11 + JDK 21 的 NIO 把 `CON`/`COM1` 当普通文件名创建、`NUL` 报错，不构成设备/宿主文件访问 → 建议从"疑点（低）"降为"提示"，并注明该结论依赖 JDK/OS、未在游戏内复现。

## 4. 结论计数

- 确认：7（M2、M4、L1、L2、P3、P5、P6）
- 部分确认：11（M1、M3、M5、L3、L4、L5、L6、L7、P1、P2、P4）
- 不成立：1（D2 的安全影响）
- 无法静态验证：1（D1，倾向部分确认）
- 已修复：0 整条；部分已修 2（L7 的 close 无条件删 endpoint.json；旧报告 onServer 不取消 Future → 现 `cancel(false)`，见 NEW-1）
- 建议级别调整：M2 中→低；M4 中→低；M5 中→低-中；M1 量级降为低（条目保留中）；D2 低→提示；其余维持。

## AI-ISSUES

- id: M1
  verdict: partial
  severity_report: '中'
  severity_suggested: medium
  title: 'kinds 内存索引随记录总数无界增长，rotate 不裁剪'
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerTrace.java:38", "mod/src/common/java/cctrun/live/ComputerTrace.java:125", "mod/src/common/java/cctrun/live/ComputerTrace.java:142-155", "mod/src/common/java/cctrun/live/ComputerTrace.java:189-199"]
  evidence: 'kinds.add(kind.intern()) 每记录一项；rotate 只切文件不裁剪；约 8 B/条，按 README 30 台 80MB/min 推算约 9-15MB/h/台，非“数小时数百 MB”；countsAfter 为 O(N−after)'
  impact: '长会话/多电脑接管时内存单调增长，最终 OOM 或频繁 GC'
  fix: 'kinds 以 firstSeq 为基线窗口化（rotate 丢弃旧段），或每 kind 计数+分段 seq；status 扫描改增量'
  tests: '可加纯 JUnit（rotate 后 kinds 窗口与 countsAfter/firstAfter 一致性）'
  source_ids: [B3, M1]

- id: M2
  verdict: confirmed
  severity_report: '中'
  severity_suggested: low
  title: '平台层 CctLive/LiveConfig 与两份构建脚本高度重复'
  module: mod
  files: ["mod/forge/src/main/java/cctrun/live/CctLive.java:40-53", "mod/neoforge/src/main/java/cctrun/live/CctLive.java:40-52", "mod/forge/src/main/java/cctrun/live/LiveConfig.java:21-67", "mod/neoforge/src/main/java/cctrun/live/LiveConfig.java:21-67", "mod/forge/build.gradle.kts:5-82", "mod/neoforge/build.gradle.kts:5-79"]
  evidence: 'git diff --numstat：CctLive 18 删/16 增（相同 93 行/84%）、LiveConfig 13/13（58 行/82%）、build 16/13（66 行/80%，同构 69.5%）；差异全为平台 API 名与 loader 配置'
  impact: '平台层行为修复需双改，易漂移'
  fix: '把事件/配置注册抽到 common 的小接口或模板生成平台壳'
  tests: none
  source_ids: [D58]

- id: M3
  verdict: partial
  severity_report: '中'
  severity_suggested: medium
  title: 'HTTP 端口固定无回退，bind 失败文案与会话已建矛盾'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveConfig.java:26-27", "mod/forge/src/main/java/cctrun/live/CctLive.java:55-63", "mod/src/common/java/cctrun/live/LiveSession.java:49-50", "mod/src/common/java/cctrun/live/LiveSession.java:251-261", "mod/src/common/java/cctrun/live/LiveHttp.java:76-90"]
  evidence: '端口 47823（1024..65535，0 被排除、无重试）；LiveHttp:79 同步抛 BindException；LiveSession:49-50 已建会话，CctLive:61 却报 could not start；endpoint.json 仅成功后写、close 删'
  impact: '双开/端口占用时 AI 接管整体失效且游戏内无提示；错误文案误导'
  fix: 'bind 失败单独捕获并给出端口占用提示与 chat/工具可见状态；可选 port=0 回退'
  tests: none
  source_ids: [D42]

- id: M4
  verdict: confirmed
  severity_report: '中'
  severity_suggested: low
  title: '反射绑定 3 个 CC:T 私有字段，版本漂移即类初始化失败'
  module: mod
  files: ["mod/src/common/java/cctrun/live/PeripheralQuery.java:41-51", "mod/src/common/java/cctrun/live/Terminals.java:18-26", "mod/forge/src/main/resources/META-INF/mods.toml:24-28"]
  evidence: 'javap 证实 1.120.2 的 1.20.1/1.21.1 jar 均存在 terminal、peripheralWrappers、RemotePeripheralWrapper.peripheral；静态块抛 IllegalStateException→ExceptionInInitializerError；mods.toml versionRange=[1.120.2] 精确 pin'
  impact: 'CC:T 内部改名时首次使用即失败：Terminals 可打断服务器 tick，PeripheralQuery 直接断连接'
  fix: '集中到 CcInternals 适配类+启动自检，失败时禁用对应功能并给版本提示'
  tests: none
  source_ids: [L-reflect]

- id: M5
  verdict: partial
  severity_report: '中'
  severity_suggested: low
  title: 'HTTP/trace 层无自动化测试，平台层无专属测试'
  module: mod
  files: ["mod/forge/build.gradle.kts:23-25", "mod/neoforge/build.gradle.kts:23-25", "mod/src/common/test/java/cctrun/live/ComputerPathsTest.java:13-46"]
  evidence: 'common 仅 3 类 9 个 @Test（路径/manifest/UTF-8）；两平台显式接入 common test 源集，故“无 test 源集”不成立；handoff:408-409 自认缺口'
  impact: 'HTTP 鉴权/Host/413、trace 轮转、Screens.replay 回归无自动化保护'
  fix: '为纯 Java 部分（ComputerTrace rotate、JsonValues、Ring、Screens.replay、鉴权/Host/413）补 JUnit 或抽出可测层'
  tests: '现有 9 个 @Test 不覆盖上述路径'
  source_ids: [L27, L28]

- id: L1
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: 'Host 校验放行 host==null'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:733-736"]
  evidence: 'hostAllowed 在 host==null 时返回 true；Host 校验先于鉴权（:104-111）；绑定仅 127.0.0.1 且需 token，浏览器场景不可利用'
  impact: '纵深防御缺口：无 Host 的 HTTP/1.0 请求被放行'
  fix: '要求 Host 必填（HTTP/1.1 合法请求都带）'
  tests: none
  source_ids: [L-host]

- id: L2
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: 'token.txt 未设 POSIX 0600'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:268-280"]
  evidence: 'token 用 Files.writeString 默认权限落盘（:278），无 setPosixFilePermissions；POSIX 下同机其他用户可读；Windows 走 ACL 继承不适用'
  impact: 'Linux/macOS 多用户机同机用户可完全操控 HTTP 面（token 等价本机控制权）'
  fix: 'POSIX 平台创建后 setPosixFilePermissions(0600)，或在文档标明风险'
  tests: none
  source_ids: [L-token-perm]

- id: L3
  verdict: partial
  severity_report: '低'
  severity_suggested: low
  title: '2 线程执行器 + body 无读取超时'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:79-84", "mod/src/common/java/cctrun/live/LiveHttp.java:744-765"]
  evidence: 'Executors.newFixedThreadPool(2)（:80-84），backlog 16，body 用 readNBytes 无读超时；但鉴权（:108）先于读 body，慢 POST 须已持 token'
  impact: '持 token 的慢 POST 可长期占满 2 线程，其他工具调用排队/超时'
  fix: '增加读超时/请求上限，或加大线程池并按端点隔离'
  tests: none
  source_ids: [L-2threads]

- id: L4
  verdict: partial
  severity_report: '低'
  severity_suggested: low
  title: 'trace/回放端点每次全文件读取并逐行解析'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:363-372", "mod/src/common/java/cctrun/live/LiveHttp.java:553-656", "mod/src/common/java/cctrun/live/Screens.java:21-60"]
  evidence: 'trace()/recordAt/replay 均全文件读+逐行 JsonParser（:596-629、:363-372；Screens:25-58）；但 trace 落盘被 64MB 上限约束，不是无限增长'
  impact: '接近上限时单次 trace/回放解析 64MB，延迟秒级'
  fix: '用 kinds/seq 索引或分段偏移定位，避免全文件扫描；至少到 limit 即停'
  tests: none
  source_ids: [B2]

- id: L5
  verdict: partial
  severity_report: '低'
  severity_suggested: low
  title: '客户端错误多落 500 并回显 message；坏 manifest 拖垮整个 list'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:165-170", "mod/src/common/java/cctrun/live/LiveHttp.java:253", "mod/src/common/java/cctrun/live/LiveHttp.java:430-472", "mod/src/common/java/cctrun/live/ComputerSnapshots.java:226-246"]
  evidence: '校验类 400，IO 类 BadRequest(500,...+e.getMessage())（:253/430/452/472）；未捕获异常 500 internal:+e；ComputerSnapshots.list 循环内 manifest 无 try/catch，一个坏文件中断整个列表'
  impact: '客户端可触发 500 并得到内部消息；一个损坏快照让 list 整体失败'
  fix: '按错误类型分状态码；list 内 per-entry try/catch 并计 skipped'
  tests: none
  source_ids: [B2]

- id: L6
  verdict: partial
  severity_report: '低'
  severity_suggested: low
  title: '截断按字符/预算按字节；大整数走 double；recordAt 双重序列化'
  module: mod
  files: ["mod/src/common/java/cctrun/live/JsonValues.java:48-52", "mod/src/common/java/cctrun/live/JsonValues.java:117-119", "mod/src/common/java/cctrun/live/ComputerTrace.java:108", "mod/src/common/java/cctrun/live/ComputerTrace.java:131"]
  evidence: 'truncate 按 UTF-16 字符、预算按 UTF-8 字节；|d|>=1e15 走 double；recordAt 先 GSON.toJson 估算再序列化整行；“文档冲突”不成立（LiveConfig:35/README:85 均写字符数）'
  impact: '大整数格式/精度受限；每记录多一次序列化开销'
  fix: '明确文档口径；大整数按需字符串化；估算用廉价长度'
  tests: none
  source_ids: [B5]

- id: L7
  verdict: partial
  severity_report: '低'
  severity_suggested: low
  title: '会话目录秒级碰撞；崩溃时 endpoint.json 残留'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:35", "mod/src/common/java/cctrun/live/LiveSession.java:46", "mod/src/common/java/cctrun/live/LiveSession.java:282-294", "mod/src/common/java/cctrun/live/ComputerTrace.java:56"]
  evidence: '目录名 yyyyMMdd-HHmmss 同秒重开复用目录，ComputerTrace:56 newBufferedWriter 截断旧 trace；endpoint.json 仅正常 stop 删（:287），start 不清理崩溃残留'
  impact: '极快重开世界丢上一会话 trace；崩溃后 endpoint.json 指向死端口/别的实例'
  fix: '目录名加毫秒/随机后缀或已存在加 -2；start 时按 pid/port 校验删旧 endpoint.json'
  tests: none
  source_ids: [L-endpoint]

- id: D1
  verdict: cannot-static
  severity_report: '疑点（中低）'
  severity_suggested: low
  title: 'READ_ONLY 外设方法白名单是名称启发式'
  module: mod
  files: ["mod/src/common/java/cctrun/live/PeripheralQuery.java:37", "mod/src/common/java/cctrun/live/PeripheralQuery.java:107-109"]
  evidence: '正则 (get|is|has|can|list|size|tanks|count)([A-Z_0-9].*)? 放行 getAndConsumeXxx 类名；Access 丢弃事件、mount 返回 null 缓解；无真实第三方外设实例可验'
  impact: '若第三方外设存在“getter 改世界”，AI 查询可产生副作用，违背只读承诺'
  fix: '增加黑名单/危险方法拒绝，或提供显式只读参数并在工具侧标注风险'
  tests: none
  source_ids: [L-readonly-regex]

- id: P1
  verdict: partial
  severity_report: '正面'
  severity_suggested: info
  title: 'HTTP 安全主路径正面确认基本成立，但引用含非安全项'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:79", "mod/src/common/java/cctrun/live/LiveHttp.java:733-742", "mod/src/common/java/cctrun/live/LiveHttp.java:744-756", "mod/src/common/java/cctrun/live/LiveHttp.java:809-822"]
  evidence: 'loopback 绑定+Host 白名单+常量时间 token+1MiB body 413 均成立；引用的 :812-819 只是 ASCII 输出转义，非安全项；host==null 例外即 L1'
  impact: '正面结论成立，但引用偏差可能让读者误判安全项范围'
  fix: '更正引用，把 host==null 例外并入正面条目说明'
  tests: none
  source_ids: [L-host, L-token-perm]

- id: P2
  verdict: partial
  severity_report: '正面'
  severity_suggested: info
  title: '路径穿越多层防护成立，但非“四层闭环”'
  module: mod
  files: ["mod/src/common/java/cctrun/live/ComputerPaths.java:21-43", "mod/src/common/java/cctrun/live/ComputerFiles.java:51", "mod/src/common/java/cctrun/live/SnapshotManifest.java:38-41"]
  evidence: '每次 mount 前 sanitise、manifest checkRelative、/rom 只读、delete 限 .cctlive 都在；但 handoff:407 自认 sanitise 不证明包含性，junction/symlink 仍可逃逸（需本机写权限）'
  impact: '路径穿越主路径已挡（B0 已修），但不是形式化包含性证明'
  fix: '加真实路径包含性校验，或把“闭环”改为“多层输入过滤”'
  tests: 'ComputerPathsTest 4 个 @Test 覆盖 sanitise/checkRelative'
  source_ids: [B0, L26]

- id: P4
  verdict: partial
  severity_report: '正面'
  severity_suggested: info
  title: '会话生命周期闭环成立，但卸载误报 removed 且崩溃不清理'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:155-223", "mod/src/common/java/cctrun/live/LiveSession.java:234-235", "mod/src/common/java/cctrun/live/LiveSession.java:282-294"]
  evidence: 'start/stop/close、lease 续约、detach 原因、endpoint 删除闭环成立；但区块卸载走 removed（:234-235）误报“电脑已被移除”，崩溃不执行 close'
  impact: '走远/卸载时聊天文案误导；崩溃后残留 endpoint/trace 未收尾'
  fix: '区分 unload 与 removed 的原因与文案；崩溃场景靠 L7 的启动清理兜底'
  tests: none
  source_ids: [D42, L7]

- id: NEW-1
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: 'onServer 超时后已开始任务仍会执行'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:695-713"]
  evidence: 'future.cancel(false)（:706）仅取消未开始任务；已开始任务继续在服务器线程跑完，而请求已回 503'
  impact: '变更类请求（power/event/files/snapshot/attach）可能“报失败但生效”，破坏幂等预期'
  fix: '变更类端点改排队+回执（pending），或在结果标注任务是否已开始'
  tests: none
  source_ids: [旧报告 M1]

- id: NEW-2
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: '启动不清理崩溃残留的 endpoint.json'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveSession.java:44-52", "mod/src/common/java/cctrun/live/LiveSession.java:282-294"]
  evidence: 'start 不校验旧文件，endpoint.json 仅 close（:287）删除；双开/换端口/崩溃后可能指向死端口或另一实例'
  impact: 'cct-run mcp --live 连错实例或连接失败，难诊断'
  fix: 'start 时读 endpoint.json 校验 pid/port，不属于本进程则删除'
  tests: none
  source_ids: [L-endpoint]

- id: NEW-3
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: 'recordAt 缺半行容错，status?kinds= 可 500'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:363-372"]
  evidence: 'recordAt 只解析匹配行且无 catch；并发写入/轮换的半行若匹配 {"seq":N, 前缀则 JsonSyntaxException→500；trace()/replay 已有跳过坏行逻辑'
  impact: 'status 的 kinds 查询偶发 500'
  fix: '同 trace() 一样 catch RuntimeException 并计 skipped'
  tests: none
  source_ids: [B2]

- id: NEW-4
  verdict: confirmed
  severity_report: '低'
  severity_suggested: low
  title: 'screen_hash 为 32 位；summary seq/tick 强转 int'
  module: mod
  files: ["mod/src/common/java/cctrun/live/LiveHttp.java:342", "mod/src/common/java/cctrun/live/LiveHttp.java:644-645"]
  evidence: 'screen_hash=Arrays.hashCode(text)*31+Arrays.hashCode(bg) 32 位可碰撞；summary 把 seq/tick 强转 (int)，>2^31 回绕'
  impact: 'cct_live_wait 可能漏判屏幕变化；超长会话 summary 数字错乱'
  fix: '改 64 位哈希；summary 保持 long'
  tests: none
  source_ids: [L-hash]