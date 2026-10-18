# 核对结果：engine 运行时 lane（A）

基线：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`，`git rev-parse HEAD` = `bf68ac478f50065e9d18808201f0e2071a580b37`（`git describe` = `v0.2.3-9-gbf68ac4`），工作树 clean。核对方式：只读静态（read/grep/jar tf/javap -c/-v），未构建、未改动仓库、未做动态运行（动态探测留给 Lead）。

证据中的 jar 简写：
- **CC** = `C:\Users\11134\.gradle\caches\modules-2\files-2.1\cc.tweaked\cc-tweaked-1.20.1-core\1.120.2\88f2295eff1c30963ebcd647074de03c1c5eb62c\cc-tweaked-1.20.1-core-1.120.2.jar`
- **CB** = `C:\Users\11134\.gradle\caches\modules-2\files-2.1\cc.tweaked\cobalt\0.9.9\b48d2545b6f97de2fe6b1debe9802dec948803be\cobalt-0.9.9.jar`

## 汇总表

| ID | 审查级别 | 实际位置 | 审查意见(简) | 核对结论 | 证据与说明 | 建议级别 |
|---|---|---|---|---|---|---|
| H1 | 高·疑点 | `harness.lua:13-14`；`HarnessApi.java:48-56` | `__cctrun` 仅靠从 `_G` 删除隐藏；Cobalt debug 库对受测程序完整可用；registry→主线程→`debug.getlocal`→report 全链未走通（GlobalRegistry 不持主线程）；建议一次性 token | **部分确认**（(1)(2)(3) 确认，(4) 静态不成立）+ 新增可达伪造面（见偏差 NEW-1） | (1) `javap -p -c -cp CC dan200.computercraft.core.lua.CobaltLuaMachine` 构造器 offset 77-88：`LuaState.globals()` 后 `CoreLibraries.debugGlobals(LuaState)`；`javap -c -cp CB org.squiddev.cobalt.lib.CoreLibraries` 的 `debugGlobals` = `standardGlobals` + `DebugLib.add`；`javap DebugLib` 常量池含 debug/getfenv/gethook/getinfo/getlocal/getmetatable/getregistry/getupvalue/setfenv/sethook/setlocal/setmetatable/setupvalue/traceback/upvalueid/upvaluejoin（16 个）。→ debug 库**无条件**装载、完整可用（确认）。(2) `harness.lua:13-14` `local report = __cctrun; _G.__cctrun = nil`；`javap -c -cp CC ...CobaltLuaMachine` 的 `addAPI` offset 30-75 只把 API 表 `rawset` 进 globals，`HarnessApi` 未实现 `getModuleName`（无 registry 别名）→ 隐藏面只有 `_G` 删除（确认）。(3) `HarnessApi.done`（`HarnessApi.java:48-56`）无 token、无调用者校验，仅 `if (finished) return`，先调到者赢（确认）。(4) 全链静态不通：`javap -p -cp CB org.squiddev.cobalt.GlobalRegistry` 字段仅 `private final LuaTable table`（包装表，无线程字段）；`LuaState` 的 `mainThread` 是独立私有字段、构造器只 `new GlobalRegistry()` 未写入；registry 只被 `LibFunction`（写 `_LOADED`）与 `exception.lua:90-101`（写 `cc_try_barrier`）使用；受测程序在 `exception.try` 新建的协程中运行（`exception.lua:173-182`：`local co = coroutine.create(try_barrier); coroutine.resume(co, {co=co,...}, func, ...)`），程序协程栈上没有 harness 帧 → 静态不支持该链，**完整攻击链结论：待 Lead 动态探测**。额外：CC:T core 1.120.2 **没有 DebugApi**（`jar tf` 仅 `dan200/computercraft/core/lua/errorinfo/DebugHelpers`）；`_G` 可被程序直接写（见 NEW-1）。 | **高（确认，提升）**；若威胁模型仅“用户自写程序”可维持 高·疑点。修复：`done` 加一次性 token（harness 持有、程序不可见）+ harness 顶部局部化 `tostring/printError/debug`（在程序运行前捕获引用）+ OOM 不用文案判；更彻底是受测程序用独立 globals 副本。 |
| H2 | 高 | `Runner.java:55-58,88,134`；`Runner.java:388-392`（summary 无写入统计） | fs 写入不受 DISK_CAPACITY 限制，可写爆 1 GB 堆；OOM 靠文案前缀无法区分 fs 写爆与真实 VM OOM；无写入量统计 | **部分确认**：前三项确认；补充“OOM 可被程序伪造”与“保护调用外的 OOM 不会归为 out_of_memory”（见偏差 2、NEW-4） | (1) `javap -p -c -cp CC dan200.computercraft.core.filesystem.MemoryMount`：`capacity` 字段的**全部**引用只有 `getRemainingSpace()`（offset 0-9：`capacity - computeUsedSpace`）与 `getCapacity()`；`MemoryMount$EntryChannel.write/ensureCapacity` 无容量检查；`Runner.java:88` `new MemoryMount(DISK_CAPACITY)`，`:57` 注释已自述“Reported by fs.getFreeSpace; writes are not limited”，`:58` 常量 `1_000_000`；`FileSystem.getFreeSpace` 仅转发 `MountWrapper.getFreeSpace`（javap `FileSystem.getFreeSpace` offset 0-15）→ DISK_CAPACITY 只用于 `fs.getFreeSpace`（确认）。(2) 无写入量统计：summary 只有 `trace_counts`/`memory`（`Runner.java:388-392`、`:339-348`），无非 trace 字节计数（确认）。(3) 归因链：`javap -v -cp CB org.squiddev.cobalt.LuaError` 常量池 `#155 = String "vm error: \u0001"`（LuaError 构造 `LuaError(Throwable)` 用 `throwable.toString()` 拼接；`LuaThread.loop` 调 `LuaError.wrap(Throwable)`），因此 **fs 写爆（EntryChannel OOM）与 Lua/VM 堆 OOM 产生完全相同的 LuaError 文案**，`Runner.java:134` 只做 `api.message.contains(OOM_MARKER)`，无法区分（确认）。(4) 保护调用外 OOM：`RecordingMachine.java:76-81` 把 `MachineResult` error 只记成 `vm_error` note，`Runner` 全程不读 `MachineResult`（grep：`machines` 只用于 `created`/`executionState`）→ boot/编译/机器层 OOM 会以 timeout/shutdown 呈现（补充）。 | 高（保持；描述按偏差 2 修正）。修复：包一层带容量限制的 `WritableMount`（写前按 `getRemainingSpace`/累计字节拒绝）+ summary 记写入字节；OOM 用 `MachineResult`/`Error` 类型或 JVM 内存阈值判，不用文案。 |
| E1 | 中 | `RootMount.java:29-46`（条件 30、delegate 45、回滚 39-42） | rename 用字符串前缀判断“目录移入自身”：真 inside-self 直接委托 `MemoryMount.rename`（是否拒绝未验证）；`/a→/ab` 误判后靠两步改名碰巧收敛 | **部分确认（代码如述）**；但**风险不成立**：真 inside-self 在 fs.move 层即被拒，MemoryMount 亦拒绝；“碰巧收敛”改为“有意的补偿+回滚” | (1) `javap -c -cp CC dan200.computercraft.core.filesystem.MemoryMount` 的 `rename` 首段 offset 0-18：`dest.startsWith(source)` → `new FileOperationException(source, "Cannot move a directory inside itself")` → **真 inside-self 会被拒绝**（此前“未验证”现已验证）。(2) 更上游 `FileSystem.move`（javap offset 0-89）先调用 segment-aware `contains(source, dest)`：`FileSystem.contains` 把两路径 lowercase，`child.startsWith(parent + "/")` 成立即抛 `"Can't move a directory inside itself"`；`/a → /a/b` 在到达 `RootMount.rename` 之前已被拒 → `RootMount` 的 delegate 分支对 `fs.move` **不可达**（辩护性代码）。(3) `/a → /ab`：`contains` 不阻断（`/ab` 不以 `/a/` 开头），而 `MemoryMount.rename` 的字符串前缀会误拒 → `RootMount` 两步改名是 javadoc `:16-20` 明确说明的补偿，失败时回滚（`39-42`），不是“碰巧”。 | **低/信息**（或标“不成立（行为正确）”）；可选：两步非原子（崩溃残留 `/.cctrun-rename`）加 note。 |
| E2 | 中 | `Runner.java:252-255`（catch 252-254），真超时 `:191`，exit code `:159-165` | 中断被打断时返回 "timeout"，环境中断与程序超时共用状态，exit_code=2 失真 | **确认（代码事实）**，实际影响 **低** | `Java`：`catch (InterruptedException e) { Thread.currentThread().interrupt(); return "timeout"; }`（252-255）；真超时 `if (tick >= maxTicks) return "timeout";`（191）；`switch (outcome) { ... case "timeout" -> 2; ... }`（159-165）；无独立 interrupted 状态（`RunResult.java:12` 注释枚举；全仓 grep 仅 timeout）。实际触发面：MCP 取消/超时是杀子 JVM（`McpServer.java:239-262` `destroyForcibly`），CLI 进程 `Main.java:109-110 System.exit`；只有外部信号或库嵌入才 interrupt。 | **低（降级）**；修复：独立 `interrupted` 状态或 `run_end` 记 `interrupted:true`。 |
| E3 | 中 | `Runner.java:152-157`、javadoc `:41`；CLI `Main.java:167,109-110`；MCP `McpServer.java:226,239,248,262` | `context.close(50ms)` 不等待计算机线程退出，仅记 note；“一 run 一 JVM”下无碍，作库嵌入时泄漏线程 | **部分确认（代码事实）**，严重度 **低**（javadoc 已声明；CLI/MCP 均一 run 一进程） | `Runner.java:41` javadoc “Designed for one run per JVM (the CLI exits afterwards): CoreConfig is global state.”；`:152-157` `try { if (!context.close(50, TimeUnit.MILLISECONDS)) trace.record("context_close_pending", ...) } catch ...`；CLI `Main.java:166-167` `Runner.run(spec)` 后 `:109-110` 注释+`System.exit(code)`；MCP `McpServer.java:226` “cct_run: one child JVM per run”、`:239` `new ProcessBuilder(childCommand(...))`、`:248 waitFor`、`:262 destroyForcibly`。 | **低（降级）**；修复：javadoc 明确“embedding 需 join/不得多次 run”，或 `context.close` 加超时等待+log。 |
| E4 | 中 | `harness.lua:33-35` | `fs.open(path,"r")` 无 nil 检查，程序文件缺失时报 `attempt to index ? (a nil value)`，错误不指向真实原因 | **确认（缺陷存在），当前不可触发** | `harness.lua:33-35`：`local file = fs.open(path, "r"); local contents = file.readAll() or ""; file.close()`；`Runner.java:75`（host 写程序文件）与 `:98` `addFile(mount, storedName, spec.programSource())`、`:99` `addFile(mount, "startup.lua", harness(storedName,...))` → harness 读的 `path`(=storedName) 在 mount 中必存在，`fs.open` 必成功；只有 `addFile` 提前抛异常（run 直接失败）或未来重构才暴露。 | **低（降级，防御性修复）**；建议：`if not file then report.done(false,"setup","program file missing: "..path,nil); ... end`。 |
| L-Cfg | 低 | `Runner.java:79-84`；javap `CoreConfig` 全 `public static` | CoreConfig 进程级静态跨 run 残留，无代码防线 | **确认（低）** | `javap -p -cp CC dan200.computercraft.core.CoreConfig`：`httpEnabled`/`httpWebsocketEnabled`/`defaultComputerSettings`/`maximumFilesOpen` 等均 `public static`；`Runner.java:79-84` 每次 run 覆盖 `httpEnabled=false`、`httpWebsocketEnabled=false`、`defaultComputerSettings=...`，无恢复逻辑；一 run 一 JVM 下无残留，作为库多 run 时 http 永久关闭、设置被上一 run 覆盖。 | 低（维持） |
| L-Ovf | 低 | `Runner.java:183`；`RunSpec.java:29`；`Main.java:133` | `maxTicks = timeoutSeconds * 20` int 乘法可溢出；CLI 已钳制，仅库调用面成立 | **确认（降级说明成立）** | `Runner.java:183` `var maxTicks = spec.timeoutSeconds() * TPS;`；`RunSpec.java:29` 只校验 `if (timeoutSeconds < 1) throw ...`（无上限）；`Main.java:133` `var timeout = args.intOption("--timeout", 15, 1, 3600);` → 溢出阈值 > 107,374,182 s，仅 `Runner.run(RunSpec)` 直接调用可达（典型后果：立即 returned timeout）。 | 低（维持） |
| L-Oom | 低 | `Runner.java:55,134` | OOM 归类硬编码 Cobalt 文案 `"vm error: java.lang.OutOfMemoryError"`，改文案即静默失配 | **确认**，并需补“可被程序伪造”（见 NEW-1） | `Runner.java:55` `static final String OOM_MARKER = "vm error: java.lang.OutOfMemoryError";`；`:134` `if (outcome.equals("error") && api.message != null && api.message.contains(OOM_MARKER)) outcome = "out_of_memory";`；`javap -v -cp CB ...LuaError` 常量池 `#155 "vm error: \u0001"` + `LuaError(Throwable)` 用 `Throwable.toString()` → 该前缀确为 Cobalt 0.9.9 现状（改文案/升级 Cobalt 会静默失配，确认）。 | 低（维持；与 H2/NEW-1 合并修复） |
| L-Close | 低 | `Trace.java:60-61`（close 107-110） | close 后 record 静默丢弃，无计数无日志 | **确认（当前无触发路径）** | `Trace.java:60-61` `public synchronized void record(...) { if (closed) return; ... }`；`droppedNotes`（`:38,88-90,101-104`）只统计 `MAX_NOTES` 截断，不覆盖 close；`Runner.java:170` `run_end` 先记、`:174 trace.close()`、`:175` 写 summary，之后无 record。 | 低（维持） |
| L-Flush | 低 | `Trace.java:72-73` | 每条记录 flush 一次，I/O 放大（有意权衡） | **确认（注释即动机）** | `Trace.java:72` 注释 “A killed run must still leave every earlier record readable: no buffered tail.” + `:73 out.flush();` → 有意权衡，非缺陷。 | 低/信息（维持） |
| L-TQ | 低 | `TraceQuery.java:66,111`（另 69,109） | `record.get("tick").getAsInt()` 假定字段存在，缺失即 NPE 且不计 skipped；半写行容错只覆盖 JSON 解析 | **确认（范围略大于报告：kind 同病）** | `TraceQuery.java:66` `var tick = record.get("tick").getAsInt();`；`:69` `record.get("kind").getAsString()`；`screenAt` `:111` `record.get("tick").getAsInt()`、`:109` `record.get("kind").getAsString()`（均无 `has()`）；`read()` 的 catch 仅包 `JsonParser.parseString`（`:47-52`）→ 合法 JSON 缺字段的 NPE 直接冒泡，`skipped` 不增。 | 低（维持） |
| L-Name | 低 | `Runner.java:441-444`（写路径 `:75`） | storedName 对 ".."/空名/Windows 保留名不设防（仅 API 健壮性） | **部分确认（仅库 API；CLI/MCP 已校验；补空串 NPE）** | `Runner.java:442` `var name = Path.of(programName).getFileName().toString();` → `programName=""` 时 `Path.of("").getFileName()==null` → **NPE**（内部错误而非对齐的输入错误）；".." 未被拦（`:443` 只映射 startup）；`runDir.resolve("program").resolve("..")`（`:75`）解析为 `runDir`（目录）→ 写失败 IOException，不能逃出 run 目录；输入面：MCP `McpTools.java:26` `PROGRAM_NAME=[A-Za-z0-9_.-]{1,64}`、`:117` 且显式拒绝 "."/".."，CLI 用真实文件名（`Main.java:165` `programPath.getFileName().toString()`）→ 仅 `Runner.run(RunSpec)` 库 API 可触发。 | 低（维持；NEW-3） |
| L-Clock | 低 | `Clock.java:19-21` | millis() 实时取 nanoTime，与 loop 线程 tick 组合有亚毫秒错位 | **确认** | `Clock.java:8` `private volatile int tick;`；`:19-21` `return Math.round((System.nanoTime() - startNanos) / 100_000.0) / 10.0;`；`Trace.record` 分两次读 `clock.tick()`（`:64`）与 `clock.millis()`（`:65`）→ 多线程 record（Trace javadoc `:25-26`）下可能 tick n 配 n+1 的 ms。 | 低（维持） |
| L-Big | 低 | `Runner.java:64-178`（run）、`181-257`（loop）、`259-268`（rebootPending）；魔法数 49/52/53/58/59/72/154/267；反射 270-279 | run()/loop() 巨型方法与魔法数（START_DELAY+5 等），反射失败静默吞掉 | **确认（行号漂移，见偏差 6）** | run() `64-178`（115 行）、loop() `181-257`（77 行）；`START_DELAY=50`(49)、`START_GRACE_TICKS=10`(52)、`MIN_TICKS_FOR_RATE=40`(53)、`DISK_CAPACITY`(58)、`RUN_ID` 格式(59)、默认 term `{51,19}`(72)、`context.close(50ms)`(154)、`tick - bootTick <= START_DELAY + 5`(267)；`startRequestedField()` 捕 `ReflectiveOperationException | RuntimeException` 后 `return null`（276-278，静默）→ `rebootPending` 启发式（267）。 | 低（维持） |

## 偏差/修正

1. **H1（高·疑点 → 高·确认，新增可达伪造面）**
   - 审查的三条事实成立：`__cctrun` 只从 `_G` 删除；Cobalt 0.9.9 的 debug 库被 `CobaltLuaMachine` 构造器**无条件**装载（`CoreLibraries.debugGlobals` → `DebugLib.add`），`debug.getlocal/setupvalue/getregistry/sethook/getfenv/setfenv` 对受测程序全部可用；`HarnessApi.done` 无 token。
   - 审查“全链未走通”与静态证据一致（报告把它作为未完成的疑点，结论应为“静态不成立、待动态确认”）：`GlobalRegistry` 只是 `LuaTable` 包装，`LuaState.mainThread` 未写入 registry；受测程序在 `exception.try` 新建的协程里运行，栈上看不到 harness 帧。
   - **报告遗漏了真正可达的路径（NEW-1，建议单列/并入 H1）**：受测程序可**直接写全局表**。Cobalt `BaseLib` 设置 `_G` = globals（`javap -c -cp CB BaseLib` offset 14-17 `rawset("_G", globals)`），harness 的程序 env 是 `__index = _G`（`harness.lua:37`），程序里 `_G` 即全局表。因此：
     - 程序可 `_G.tostring = function() return "vm error: java.lang.OutOfMemoryError: fake" end`，或直接 `error("vm error: java.lang.OutOfMemoryError: fake")`（错误串含该子串即可），`harness.lua:70` 的 `tostring(perr)` → `Runner.java:134` 的 `contains` → **状态被伪造为 `out_of_memory`（exit 4）**；同理可伪造 error.message / traceback。
     - 程序可把 `_G.printError`/`_G.debug` 改坏，使 harness 在 `report.done` 之前崩溃（`harness.lua:66-70`）。`/startup.lua`（harness）由 `rom/startup.lua:213-215` 的 `shell.run(v)` 执行、错误被 shell 吞掉；随后 `bios.lua:730-740` 进入交互 shell 并只有 shell 退出才 `shutdown.lua` → 计算机保持 on，`Runner.loop` 直到 `maxTicks` 才返回 **`timeout`**（把 error 伪装成超时）。
   - 因此级别应从“高·疑点”提升为 **高·确认（结果可信度）**；只有把威胁模型限定为“用户自写程序”时，维持疑点也说得通。修复：`done(token)` 一次性 token；harness 在运行程序**前**局部化 `local tostring, printError, debug = tostring, printError, debug` 并用局部引用收尾；OOM 不用文案判断（见 H2）。
   - 动态探测建议（Lead）：程序内 `print(debug and debug.getlocal, debug and debug.getregistry)`；尝试 `_G.tostring = ...` + `error(...)`；尝试 `_G.printError=nil; error("x")` 观察 status 是否变 timeout；尝试 registry/`coroutine.running()` 找主线程。

2. **H2（高，描述需修正）**：DISK_CAPACITY 不限制写不是 cc-t-run 的 bug，而是 CC:T `MemoryMount` 的设计（`capacity` 只被 `getRemainingSpace`/`getCapacity` 引用，`EntryChannel` 不检查；`Runner.java:57` 注释已自述）。OOM 无法区分 fs 写爆与 VM OOM 这点**确认**（两者都经 `LuaError.wrap(Throwable)` 变成同一句 `"vm error: " + Throwable.toString()`），应由“改文案即失配”升级为“结构性无法归因”：`Runner.java:134` 只看 `api.message`，而 `RecordingMachine.java:76-81` 的 `MachineResult` 不进状态判定（NEW-4：boot/编译/机器层 OOM 会以 timeout/shutdown 呈现）。另 NEW-1 表明受测程序可主动伪造 `out_of_memory`。修复优先级应高于“低”。

3. **E1（中 → 低/信息，风险不成立）**：审查担心的“真 inside-self 直接委托且是否拒绝未验证”已静态验证——`MemoryMount.rename` 首行 `dest.startsWith(source)` 即抛 `Cannot move a directory inside itself`；且 `FileSystem.move` 更上游用 segment-aware `contains()`（`child.startsWith(parent + "/")`，lowercase）先抛 `Can't move a directory inside itself`，`RootMount` 的 delegate 分支经 `fs.move` 不可达。`/a→/ab` 的两步改名是 javadoc `RootMount.java:16-20` 说明的**有意补偿**（MemoryMount 字符串前缀误判），失败会回滚（`:39-42`），不是“碰巧收敛”。建议 中 → 低/信息 或直接标“不成立（行为正确）”。实际行号 29-46（报告 29-44）。

4. **E2/E3（中 → 低）**：代码事实均确认，但两者都只在“库嵌入 / 外部信号”场景成立：MCP 每次 cct_run 起独立子 JVM（`McpServer.java:226/239/248/262`），取消/超时直接 `destroyForcibly`，不会给 Runner 线程发 interrupt；CLI `Main.java:109-110 System.exit`；`Runner.java:41` javadoc 已声明 one-run-per-JVM。E3 的 `context.close(50ms)` 在现设计下无泄漏后果。建议级别降为低，并在 javadoc 明示 embedding 限制。

5. **E4（中 → 低）**：缺陷确认但当前不可触发（`Runner.java:98` 在 `:99` 安装 harness 之前已把同 `storedName` 程序文件写入 mount，`fs.open` 必成功）。保留为防御性修复建议即可。

6. **行号漂移清单**（相对 bf68ac4 实际行号）：
   - E2：报告 250-255 → 实际 252-255。
   - E3：报告 146-158 → 实际 152-157。
   - E1：报告 29-44 → 实际 29-46（rename 全方法）。
   - L-Big：报告 run() 76-176、loop() 183-268 → 实际 run() **64-178**、loop() **181-257**（259-268 是 `rebootPending`）。
   - 其余条目行号一致（H1 `harness.lua:13-14`、`HarnessApi.java:49` 位于 48-56；H2 55-58/88/134；E4 33-35；L-Cfg 79-84；L-Ovf 183 与 `Main.java:133`；L-Oom 55/134；L-Name 441-445；L-Close 60-61；L-Flush 72-73；L-TQ 66/69/109/111；L-Clock 19-21）。

7. **新增（NEW）条目**
   - **NEW-1（中/高，建议并入 H1）**：受测程序可通过写 `_G` 篡改 harness 收尾所依赖的全局（`tostring`/`printError`/`debug`），从而伪造 `out_of_memory`、篡改 error message/traceback，或令 harness 崩溃使 error 被误判为 `timeout`。证据：`BaseLib` 设 `_G`=globals（javap CB）；`harness.lua:14,37,66-70`；`Runner.java:134`；`rom/startup.lua:213-215` + `bios.lua:730-740`。
   - **NEW-2（低）**：`RootMount.rename` 的两步改名非原子；JVM 崩溃/强杀会残留 `/.cctrun-rename`（回滚只覆盖 IOException）。可在 `note` 或文档中说明。
   - **NEW-3（低，L-Name 补充）**：`Runner.run(RunSpec)` 传 `programName=""` 时 `Path.of("").getFileName()` 为 null → `storedName` 抛 NPE（内部错误），应在 `RunSpec` 校验 reject（CLI/MCP 已挡，属库 API）。
   - **NEW-4（低/中，H2 补充）**：`RecordingMachine` 的 `MachineResult` 错误未进入状态判定；发生受保护调用之外的 OOM/VM error（boot、编译、harness 自身）时 run 会以 `timeout`/`shutdown` 结束，与 `out_of_memory` 语义混淆。

## 核对结论计数

确认 6（H1 的 1-3 子项、E2、L-Cfg、L-Ovf、L-Oom、L-Close、L-Flush、L-TQ、L-Clock 等以主结论计）；部分确认 5（H1、H2、E1、E3、L-Name）；不成立/风险不成立 1（E1 风险面，已并入部分确认）；已修复 0；无法静态验证 1（H1 的完整攻击链，附动态探测方法）；降级建议 6（E1、E2、E3、E4、H1 级别反向提升、H2 描述修正）。NEW 4 条。

## AI-ISSUES

- id: H1
  verdict: partial
  severity_report: "高·疑点"
  severity_suggested: high
  title: "debug 库对受测程序完整可用、__cctrun 仅靠删 _G 隐藏；registry→主线程链静态不成立"
  module: engine-runtime
  files: ["engine/src/main/resources/cctrun/harness.lua:13-14", "engine/src/main/java/cctrun/engine/HarnessApi.java:48-56", "engine/src/main/java/cctrun/engine/Runner.java:134"]
  evidence: 'javap CobaltLuaMachine 构造器调用 CoreLibraries.debugGlobals→DebugLib.add（16 函数）；addAPI 只 rawset 进 globals；GlobalRegistry 仅包 LuaTable、mainThread 未入 registry，程序跑在 exception.try 新协程；完整伪造链本身无法静态验证。'
  impact: "程序若能取到 report 即可伪造结果；静态链不通，但存在 _G 写伪造面（NEW-1）。"
  fix: "done() 加一次性 token；harness 运行程序前局部化 tostring/printError/debug；OOM 不用文案判定。"
  tests: "动态：程序内检查 debug 函数、遍历 _G 找 __cctrun、尝试 _G 篡改。"
  source_ids: ["NEW-1", "L-Oom"]

- id: H2
  verdict: partial
  severity_report: "高"
  severity_suggested: high
  title: "DISK_CAPACITY 只用于 getFreeSpace，fs 写入不限容量可写爆堆；OOM 仅按文案归因且可被伪造"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:55-58", "engine/src/main/java/cctrun/engine/Runner.java:88", "engine/src/main/java/cctrun/engine/Runner.java:134", "engine/src/main/java/cctrun/engine/Runner.java:388-392"]
  evidence: 'javap MemoryMount 的 capacity 字段仅被 getRemainingSpace/getCapacity 引用，EntryChannel 无容量检查；Runner:57 注释自述；LuaError(Throwable) 模板为 vm error 加 Throwable.toString()，fs OOM 与 VM OOM 文案相同。'
  impact: "单个程序可耗尽 1GB 堆导致 run/JVM 失败；OOM 状态无法区分原因且可被程序伪造。"
  fix: "用带容量限制与字节统计的 WritableMount；OOM 用 MachineResult/Error 类型判定。"
  tests: "动态：循环写大字符串观察 status/exit 与 heap_peak_mb；error 文案含 marker 看是否 out_of_memory。"
  source_ids: ["NEW-1", "NEW-4", "L-Oom"]

- id: E1
  verdict: partial
  severity_report: "中"
  severity_suggested: low
  title: "RootMount.rename 的字符串前缀分支是正确补偿，真 inside-self 在 FileSystem.move 层即被拒绝"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/RootMount.java:29-46"]
  evidence: 'javap MemoryMount.rename 首段为 dest.startsWith(source) 后抛 Cannot move a directory inside itself；FileSystem.move 上游用 segment-aware contains 先拒绝；/a→/ab 两步改名有 javadoc 说明与回滚。'
  impact: "报告担心的数据破坏不成立；仅两步改名非原子（崩溃残留 /.cctrun-rename，NEW-2）。"
  fix: "无需修复；可选在 note 说明非原子残留。"
  tests: "动态：fs.move('/a','/a/b') 期望拒绝；fs.move('/a.log','/a.log.1') 期望成功。"
  source_ids: ["NEW-2"]

- id: E2
  verdict: confirmed
  severity_report: "中"
  severity_suggested: low
  title: "tick 循环被 InterruptedException 打断时返回 timeout，与程序超时共用 exit_code=2"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:252-255", "engine/src/main/java/cctrun/engine/Runner.java:191", "engine/src/main/java/cctrun/engine/Runner.java:159-165"]
  evidence: 'catch (InterruptedException e) { Thread.currentThread().interrupt(); return "timeout"; }；真超时 if (tick >= maxTicks) return "timeout"；switch 中 "timeout"->2；无独立 interrupted 状态。'
  impact: "环境中断被误报为程序超时；MCP 取消走杀子 JVM、CLI 有 System.exit，实际面窄。"
  fix: "新增 interrupted 状态或在 run_end 记 interrupted:true。"
  tests: "动态：向 Runner 线程发 interrupt，检查 status/exit_code。"
  source_ids: ["E3"]

- id: E3
  verdict: partial
  severity_report: "中"
  severity_suggested: low
  title: "context.close(50ms) 不等计算机线程退出；一 run 一 JVM 下无碍，库嵌入会泄漏线程"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:152-157", "engine/src/main/java/cctrun/engine/Runner.java:41", "cli/src/main/java/cctrun/cli/Main.java:167", "cli/src/main/java/cctrun/cli/Main.java:109-110"]
  evidence: 'Runner:41 javadoc 声明 one run per JVM；:152-157 close(50ms) 失败仅 trace.record context_close_pending；CLI 每 run 进程 System.exit；MCP 每 run 起子 JVM 并 waitFor/destroyForcibly。'
  impact: "现设计无泄漏后果；库嵌入时线程残留。"
  fix: "javadoc 明确 embedding 限制，或 close 等待并记录残留。"
  tests: none
  source_ids: ["E2"]

- id: E4
  verdict: confirmed
  severity_report: "中"
  severity_suggested: low
  title: "harness fs.open 无 nil 检查，程序文件缺失时报 nil index 而非真实原因（当前不可触发）"
  module: engine-runtime
  files: ["engine/src/main/resources/cctrun/harness.lua:33-35", "engine/src/main/java/cctrun/engine/Runner.java:98-99"]
  evidence: 'harness.lua:33 local file = fs.open(path,"r"); file.readAll()；Runner:75/98 先把同 storedName 程序文件写入 mount，:99 才装 harness，故 fs.open 必成功。'
  impact: "仅在 addFile 异常/重构时暴露，错误信息不指向原因。"
  fix: 'if not file then report.done(false,"setup","program file missing: "..path,nil) end。'
  tests: none
  source_ids: []

- id: L-Cfg
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "CoreConfig 进程级静态跨 run 残留，无代码防线"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:79-84"]
  evidence: 'javap CoreConfig 字段全 public static；Runner:79-84 每次 run 覆盖 httpEnabled/httpWebsocketEnabled/defaultComputerSettings，无恢复；一 run 一 JVM 下无残留。'
  impact: "库多 run 时 http 永久关闭、设置被上一 run 覆盖。"
  fix: "保存并按需恢复 CoreConfig，或文档声明进程级副作用。"
  tests: none
  source_ids: []

- id: L-Ovf
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "maxTicks = timeoutSeconds * 20 为 int 乘法可溢出，仅库调用面成立"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:183", "engine/src/main/java/cctrun/engine/RunSpec.java:29", "cli/src/main/java/cctrun/cli/Main.java:133"]
  evidence: 'Runner:183 int 乘法；RunSpec:29 只校验 timeoutSeconds < 1 无上限；Main:133 intOption(--timeout,15,1,3600) 钳制。'
  impact: "仅库调用 timeoutSeconds>107374182 时溢出，典型立即 timeout。"
  fix: "RunSpec 增加合理上限或改用 long/Math.multiplyExact。"
  tests: none
  source_ids: []

- id: L-Oom
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "OOM 归类硬编码 Cobalt 文案，改文案即静默失配（且可被程序伪造）"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:55", "engine/src/main/java/cctrun/engine/Runner.java:134"]
  evidence: 'Runner:55 常量 "vm error: java.lang.OutOfMemoryError"，:134 用 api.message.contains；javap LuaError 常量池模板为 vm error 加 Throwable.toString()，确为 Cobalt 0.9.9 文案。'
  impact: "升级 Cobalt 或改文案会静默失去 OOM 归类；error 文案可伪造该状态（NEW-1）。"
  fix: "用 MachineResult/Error 类型或 JVM 内存阈值判定，不用文案。"
  tests: none
  source_ids: ["NEW-1"]

- id: L-Close
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "Trace.close 后 record 静默丢弃，无计数无日志（当前无触发路径）"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Trace.java:60-61"]
  evidence: 'record 首行 if (closed) return; 无计数；droppedNotes 只覆盖 MAX_NOTES 截断；Runner:170 run_end 后 :174 close，之后无 record。'
  impact: "未来 close 后记录会静默丢。"
  fix: "closed 丢弃计数并入 summary，或在 close 前断言。"
  tests: none
  source_ids: []

- id: L-Flush
  verdict: confirmed
  severity_report: "低"
  severity_suggested: info
  title: "每条记录 flush 一次，I/O 放大（有意的崩溃可读性权衡）"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Trace.java:72-73"]
  evidence: 'Trace.java:72 注释 A killed run must still leave every earlier record readable; :73 out.flush()。'
  impact: "每条记录一次 flush，I/O 放大；属有意权衡。"
  fix: "可选：批量 flush（每 N 条/每 tick），保留崩溃可读性。"
  tests: none
  source_ids: []

- id: L-TQ
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "TraceQuery 假定 tick/kind 字段存在，缺字段 NPE 且不计 skipped"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/TraceQuery.java:66", "engine/src/main/java/cctrun/engine/TraceQuery.java:111", "engine/src/main/java/cctrun/engine/TraceQuery.java:69"]
  evidence: 'TraceQuery:66 record.get("tick").getAsInt()、:111 screenAt 同；:69/:109 kind 同无 has() 检查；read 的 catch 只包 JsonParser.parseString(:47-52)。'
  impact: "合法 JSON 缺字段时 NPE 冒泡且不计 skipped，查询 internal error。"
  fix: "统一 has()/isJsonPrimitive 校验并计入 skipped。"
  tests: none
  source_ids: []

- id: L-Name
  verdict: partial
  severity_report: "低"
  severity_suggested: low
  title: "storedName 对空名/../保留名不设防（仅库 API，CLI/MCP 已校验）"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:441-444", "engine/src/main/java/cctrun/engine/Runner.java:75"]
  evidence: 'Runner:442 Path.of(programName).getFileName().toString()；空串时 getFileName()==null 抛 NPE；".." 未拦；写路径 :75 单段不逃逸；MCP PROGRAM_NAME 正则与 CLI 文件名已挡。'
  impact: "仅库 API：空名 NPE（内部错误）、保留名/.. 行为异常。"
  fix: "RunSpec 校验非空且拒绝 ../Windows 保留名。"
  tests: none
  source_ids: ["NEW-3"]

- id: L-Clock
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "millis() 实时取 nanoTime，与 loop 线程 tick 组合有亚毫秒错位"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Clock.java:19-21", "engine/src/main/java/cctrun/engine/Trace.java:64-65"]
  evidence: 'Clock:8 volatile int tick、:19-21 millis 实时 nanoTime；Trace:64-65 分两次读 tick 与 millis，多线程 record 下可能错位。'
  impact: "亚毫秒级 tick/ms 不一致，不影响判定。"
  fix: "record 时一次性快照 (tick, ms)，或由 loop 线程写入时间戳。"
  tests: none
  source_ids: []

- id: L-Big
  verdict: confirmed
  severity_report: "低"
  severity_suggested: low
  title: "run()/loop() 巨型方法与魔法数，反射失败静默吞掉"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:64-178", "engine/src/main/java/cctrun/engine/Runner.java:181-257", "engine/src/main/java/cctrun/engine/Runner.java:270-279"]
  evidence: 'run() 64-178、loop() 181-257；魔法数 START_DELAY+5(:267)、50/10/40/1000000(:49,52,53,58)；startRequestedField() 失败 return null(:276-278) 静默。'
  impact: "可维护性/反射静默退化风险。"
  fix: "拆分 loop() 为 tickOnce/decideOutcome，反射失败记 note。"
  tests: none
  source_ids: []

- id: NEW-1
  verdict: confirmed
  severity_report: "未列（NEW，建议并入 H1）"
  severity_suggested: medium
  title: "受测程序可写 _G 篡改 harness 收尾全局，伪造 out_of_memory 或把 error 伪装成 timeout"
  module: engine-runtime
  files: ["engine/src/main/resources/cctrun/harness.lua:14", "engine/src/main/resources/cctrun/harness.lua:37", "engine/src/main/resources/cctrun/harness.lua:66-70", "engine/src/main/java/cctrun/engine/Runner.java:134"]
  evidence: 'javap BaseLib 设 _G=globals，harness env __index=_G(:37)；程序可改 _G.tostring/printError/debug；Runner:134 只 contains 文案；harness 崩后 shell.run 吞错、bios 进交互 shell 导致 timeout。'
  impact: "结果状态/错误信息可被受测程序伪造（out_of_memory、timeout、任意 message/traceback）。"
  fix: "done 一次性 token；harness 顶部 local 化收尾所用全局；OOM 用 MachineResult/类型判。"
  tests: "动态：_G.tostring 伪造 + error；_G.printError=nil + error 观察 status。"
  source_ids: ["H1", "H2", "L-Oom"]

- id: NEW-2
  verdict: confirmed
  severity_report: "未列（NEW）"
  severity_suggested: low
  title: "RootMount 两步改名非原子，崩溃/强杀会残留 /.cctrun-rename"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/RootMount.java:34-42"]
  evidence: 'RootMount.rename 两步改名 :36-42，仅 IOException 回滚；JVM 崩溃/强杀残留 /.cctrun-rename。'
  impact: "极小：残留顶层文件，后续 run 新 mount 不受影响。"
  fix: "文档/note 说明，或启动时清理。"
  tests: none
  source_ids: ["E1"]

- id: NEW-3
  verdict: confirmed
  severity_report: "未列（NEW）"
  severity_suggested: low
  title: "Runner.run(programName=\"\") 抛 NPE 而非对齐的输入错误"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/Runner.java:441-444", "engine/src/main/java/cctrun/engine/RunSpec.java:25"]
  evidence: 'Path.of("").getFileName() 为 null → storedName 抛 NPE；RunSpec:25 只 requireNonNull(programName)。'
  impact: "库 API 传空串得内部 NPE。"
  fix: "RunSpec 校验 programName 非空。"
  tests: none
  source_ids: ["L-Name"]

- id: NEW-4
  verdict: confirmed
  severity_report: "未列（NEW）"
  severity_suggested: low
  title: "RecordingMachine 的 MachineResult 错误不进状态判定，保护链外 OOM 会呈现为 timeout/shutdown"
  module: engine-runtime
  files: ["engine/src/main/java/cctrun/engine/RecordingMachine.java:76-81", "engine/src/main/java/cctrun/engine/Runner.java:136"]
  evidence: 'RecordingMachine:76-81 仅 trace.note("vm_error")，Runner 不读 MachineResult；保护调用外 OOM/VM error 时 api.finished=false，loop 以 timeout/shutdown 结束。'
  impact: "保护链外 OOM 与真实超时混淆，out_of_memory 只覆盖被 harness 捕获的 OOM。"
  fix: "Runner 读取 MachineResult error 并映射状态。"
  tests: none
  source_ids: ["H2", "L-Oom"]
