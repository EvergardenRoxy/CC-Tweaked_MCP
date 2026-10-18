# 核对任务 B：engine fixture / peripheral lane（核对报告）

- 被核对仓库：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`
- 基线确认：`git rev-parse HEAD` = `bf68ac478f50065e9d18808201f0e2071a580b37`（= bf68ac4）✔；但 `git describe --tags` = `v0.2.3-9-gbf68ac4`，即 tag v0.2.3 落后 HEAD 9 个提交，"HEAD = v0.2.3" 字面不精确（版本号 0.2.3 来自 Gradle 项目版本：`cli/build/libs/cli-0.2.3.jar`、`engine/build/libs/engine-0.2.3.jar`）。
- 方式：只读核对；未修改仓库文件、未运行 Gradle。工具：read/grep/glob、`javap -p -c -constants`、`jar tf`、jar MANIFEST。gson 与 CC:T 的取证据实际安装产物 `cli/build/install/cct-run/lib/`（gson-2.11.0.jar、cc-tweaked-1.20.1-core-1.120.2.jar、-core-api-1.120.2.jar）与 Gradle 缓存的 `cc-tweaked-1.20.1-forge-1.120.2.jar`。
- 结论计数：**确认 6（F2/F3/F4/F6/F11/F12）、部分确认 5（F1/F5/F8/F9/F10）、不成立 1（F7）**；其中 F1、F10 另含"待 Lead 动态"部分。

## 汇总表

| ID | 审查级别 | 实际位置 | 审查意见（简） | 核对结论 | 证据与说明 | 建议级别 |
|---|---|---|---|---|---|---|
| F1 | 中·疑点（报告已降级） | `engine/src/main/java/cctrun/engine/Fixture.java:74-80`（报告 77-80） | 深嵌套 JSON 是否 SOE 取决于 Gson 版本；Gson 2.11.0 有 255 层上限，走正常 JsonParseException；降级为依赖升级时的回归风险 | **部分确认（结论对、依据错）＋待 Lead 动态** | ① gson 实际版本 = 声明版本：`gradle/libs.versions.toml:4` `gson = "2.11.0"`；`cli/build/install/cct-run/lib/gson-2.11.0.jar` MANIFEST `Bundle-Version: 2.11.0`、`Bundle-SCM ... tag="gson-parent-2.11.0"`。② **该版本没有 255 层嵌套上限**：`javap -p JsonReader` 无 `nestingLimit` 字段/`setNestingLimit` 方法（唯一 `private int limit` 是 1024 字符缓冲区的填充上限，构造函数置 0）；255 上限不属于本仓库实际依赖。③ catch 范围：`Fixture.java:76-80` 只 `catch (JsonParseException e)` → `FixtureException("$", "not valid JSON: " + e.getMessage())`；gson 自己在 `JsonParser.parseReader(JsonReader)` 里捕获 `StackOverflowError`/`OutOfMemoryError` 并包装为 `JsonParseException("Failed parsing JSON source: ... to Json", e)`（`javap -p -c JsonParser.class` 异常表：`19 24 31 Class java/lang/StackOverflowError`、`19 24 65 OutOfMemoryError`）。**因此深嵌套最终仍是 "$: not valid JSON: ..."，不会 SOE 崩溃；但触发点是 JVM 栈深（非固定 255 层）**。④ 入口只有 `Fixture.parse`：CLI `Main.java:151`；MCP 内联 fixture 先写临时文件再走 CLI（`McpTools.java:173-187`）；`Runner.java:124` 的二次 `parseString` 无 catch，但同一输入已先通过 `Fixture.parse`；`JsonValues` 不解析 JSON（只消费已解析的 `JsonElement`）。**待 Lead 动态**：300 层嵌套在默认栈下很可能解析成功（然后由后续校验按位置拒绝，或被 args 放行），测试预期应改为"不会报 not valid JSON / 不会崩溃"，而不是"在 255 层报 JsonParseException"。建议级别：中（维持）但理由改写 | 中（理由改写） |
| F2 | 中 | `Fixture.java:169,188,298,306,364,374`（报告 302-303 应为 306） | 默认值（27/100_000/64）在 validate 与 build 两阶段共 3 处重复；build 靠 `IllegalStateException("validated earlier")` 耦合、丢失 JSON 路径；`FixtureTest.java:51-53` 注释证实历史越界回归 | **确认（行号/数值有偏差）** | ① `27` 共 3 处：`Fixture.java:169`（inventory size 默认）、`306`（timeline item 槽位上限默认）、`364`（build inventory size 默认）；`100_000` 共 3 处：`188`（validate capacity）、`298`（timeline energy 的 capacity 默认）、`374`（build capacity）；**`64` 并非重复 3 处**：`365` 是 `new Inventory(size, 64)` 的槽位上限常量，`421-422` 的 64 是物品 `maxCount`/`count` 默认值（不同语义）。故"27/100_000/64 三处重复"应改为"27 与 100_000 各 3 处；64 只在 365 出现一次（另 421/422 为物品语义）"。② 报告 302-303 实际是注释与 `allowOnly`，默认 27 在 `306`。③ build 阶段靠异常耦合、丢路径确认：`Fixture.java:410` `default -> throw new IllegalStateException("validated earlier: " + type);`、`441` `throw new IllegalStateException("validated earlier", e);`（均无 `$....` JSON 路径）。④ 回归注释原文（**实际在 52-53 行**，51 行是方法签名）：`// Regression: the slot used to be checked against the 4096 hard limit only, so Runner.apply wrote out of` / `// bounds at run time (ArrayIndexOutOfBoundsException, no summary).`；`handoff.md:81`（D51）记录了同一修复。⑤ handoff.md:348 另有一份同样的默认值文档，常量无单一来源。 | 中（维持） |
| F3 | 低 | `Fixture.java:517-523` | longValue 只查下限：1e999→Infinity、≥Long 范围经 `(long)` 静默饱和为 Long.MAX_VALUE；capacity/energy/tank 照单全收 | **确认** | ① `517-523`：`var d = e.getAsDouble(); if (d != Math.rint(d)) ...; if (d < min) ...; return (long) d;` —— 只查下限，无上限；`Math.rint(Infinity)==Infinity` 所以 Infinity 通过检查，`(long) Infinity` = `Long.MAX_VALUE`；`1e999` 经 Gson `LazilyParsedNumber.getAsDouble()` = Infinity。② 调用点：`188-190`（capacity/energy validate，`energy > capacity` 在两者同为 MAX 时通过）、`297-299`（timeline energy）、`374-375`（build `getAsLong()`；`LazilyParsedNumber.longValue()` 先 `Long.parseLong` 失败再 `(long) Double.parseDouble` → 同样饱和）、`203`/`383`（tank amount）。③ 只读后果：`EnergyPeripheral.Storage.setEnergy` 有 clamp（`EnergyPeripheral.java:21-23`），但 fixture 的 capacity 本身可为 `Long.MAX_VALUE`。 | 低（维持） |
| F4 | 低 | `Fixture.java:74-162`；`JsonValues.java:107-124` | 解析无总大小上限（DOM 全量载入）；toLua 无深度/条目限制，与 `of` 的三重上限不对称 | **确认，建议升为中低（新增可达崩溃路径）** | ① 解析无大小上限：`Fixture.parse(String)` 全段无 length 检查；CLI `Main.java:306` `Files.readString(...)` 无上限；MCP 行协议 `McpServer.java:103` `reader.readLine()` 无上限。② `JsonValues.of` 有 `MAX_STRING=200 / MAX_ENTRIES=50 / MAX_DEPTH=4`（`JsonValues.java:18-20`），`toLua`（`107-124`）递归转换无任何上限；消费点：`Runner.java:289`（timeline `event` args）与 `ScriptedPeripheral.java:73`（returns/sets）。③ **NEW**：`event` 的 `args` 只校验"是数组"（`Fixture.java:285`），元素不递归校验 → 深嵌套 args 可通过 `Fixture.parse`，随后 `Runner.apply` 的 `JsonValues.toLua`（`JsonValues.java:107-124`）或 `Runner.noteWideText`（`Runner.java:480-496`，递归 peripherals/timeline）可抛 `StackOverflowError`；`Runner.loop` 只 `catch (RuntimeException)`（`Runner.java:197`），`Main` 只 catch `OutOfMemoryError`/`Exception`（`Main.java:100-107`）→ **无 summary.json、进程带栈退出**。可达但需自伤式 fixture；建议级别 低→中低。 | 低→中低 |
| F5 | 低（部分待证） | `Fixture.java:148-160`；`Runner.java:193-196` | timeline 不排序不查重，跨 tick 执行语义取决于 Runner；同 tick 多条动作允许（测试证实为特性） | **部分确认** | ① 存储：`ArrayList`（`Fixture.java:148-159`），不排序、不查重、无条数上限。② 遍历方式：`Runner.loop` 每 tick 全表线性扫描，`for (var action : fixture.timeline) { if (action.tick() != tick) continue; ... }`（`Runner.java:193-196`）→ **不排序也无需排序**，复杂度严格 O(ticks × actions)（无 tick 索引）；同 tick 多条按 fixture 顺序、在 `computer.tick()` 之前执行（`193-196` 先于 `215`）。③ 测试背书：`PeripheralTest.java:220-223` 两个 `tick:10` 动作（energy/item）都执行，`226` 断言 2 条 `fixture_action` → 同 tick 多条是既定特性。④ 遗留（NEW 提示）：`tick` 只校验 0..Integer.MAX_VALUE（`Fixture.java:154`），超过 timeout 的条目静默不执行、无提示；timeline 无条数上限时每 tick 全表扫描可能拖慢 tick loop（`tick_lag`）。 | 低（维持） |
| F6 | 低 | `peripheral/DispatchPeripheral.java:136-140`（报告 136-139） | RuntimeException 以 `"internal: " + e` 写入 trace，trace 对 MCP 客户端可见；无堆栈，泄漏有限 | **确认** | ① `136-141`：`catch (RuntimeException e) { data.addProperty("ok", false); data.addProperty("error", "internal: " + e); trace.note("peripheral_return", data); throw e; }` —— 用 `Throwable.toString()`（类名+message，无堆栈）。② 可见面：`Trace.note` 既写 `trace.jsonl`，又保留前 `MAX_NOTES=10` 条进 `summary.notes`（`Trace.java:30,80-91`）；MCP `cct_run` 返回 summary 的 `notes`，`cct_trace` 读取 trace 记录 → 客户端可见。③ 成功路径用 `trace.record`、错误路径用 `trace.note`（`129` vs `134/139`），只影响 summary.notes 配额、不影响 trace.jsonl 完整性。 | 低（维持） |
| F7 | 低·疑点 | `peripheral/Inventory.java:38-64`；`peripheral/ItemStack.java:12,15` | `ItemStack.count` 非 volatile，move 在锁内改、getItemDetail 在锁外读；若 timeline 写与 Lua 读不在同一线程则数据竞争 | **不成立（竞争不存在）** | **判定：同一线程，无竞争。** 证据链：① timeline 写在 `Runner.loop` 线程上直接执行 `apply`（`Runner.java:131` → `196`），`Inventory.set`/`EnergyPeripheral.Storage.setEnergy` 均在此线程。② Lua 对 inventory 的读全部是 main-thread 方法：`InventoryPeripheral.java:27-39` 每个方法都是 `method(true, ...)`；`DispatchPeripheral.invoke` 对 main-thread 方法走 `context.executeMainThreadTask(...)`（`DispatchPeripheral.java:112-116`，类注释 27-30 说明"下一 server tick 在主线程执行并 task_complete 恢复"）。③ 这些任务由 `Runner.loop` 里同一线程的 `mainThread.tick()` 执行（`Runner.java:216`）；CC:T 1.120.2 的 `MainThread.tick()` 在调用线程内直接 `invokevirtual MainThreadExecutor.execute()`（`javap -c` tick 偏移 134），而 `MainThreadExecutor.execute()` 在调用线程 `tasks.poll()` 后 `Runnable.run()`（`javap -c` 偏移 18-46）。→ `ItemStack.count` 的所有写（`Inventory.java:57,77`）与读（`InventoryPeripheral.java:32,51` → `ItemStack.java:42-48,51-61`）都在 Runner 主循环线程，非 volatile 不构成数据竞争。④ 遗留（NEW 防御建议）：若将来给 inventory 增加 `main_thread:false` 方法、或把 `mainThread.tick()` 换到别的线程，`count` 非 volatile + 锁外读会变成真竞争；本类注释（`Inventory.java:5-11`）与 `ItemStack.java:11` 未声明线程约束。 | 低·疑点→不成立（可撤销） |
| F8 | 低 | `JsonValues.java:38-43`（报告 40-43） | `\|d\|<1e15` 转 long，更大整数转 double 丢精度；NaN/Infinity 转字符串（类型改变）；有界化取舍 | **部分确认** | ① 代码确认：`40` `if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return new JsonPrimitive((long) d);` `41` `if (Double.isNaN(d) || Double.isInfinite(d)) return new JsonPrimitive(Double.toString(d));` `42` `return new JsonPrimitive(d);` → ≥1e15 的"整数"走 double（丢精度），NaN/Inf 变字符串（JSON 类型改变）。② 文档化程度：类 javadoc 只有 `"Converts values crossing the Lua/Java boundary into bounded JSON for the trace."`（`JsonValues.java:16`），未说明数值强转/类型变化；`handoff.md` 未见 1e15/NaN 相关说明（grep 无命中）。→ 行为与报告一致；"已文档化"不成立。 | 低（维持，建议补 javadoc） |
| F9 | 低 | `peripheral/ItemStack.java:42-48,51-61` | basicDetail/fullDetail 用 HashMap，trace 键序不稳定 | **部分确认（措辞不精确）** | ① 代码确认：`basicDetail()` 用 `new HashMap<String,Object>()`（`42-48`），`fullDetail()` 复用 basicDetail 再追加字段（`51-61`，`tags` 内层是 LinkedHashMap）。② "不稳定"不准确：对固定键集，`HashMap` 迭代序由字符串 hashCode 决定，跨运行**确定**，只是非插入序/非排序；且 JSON 对象键序无语义。③ 测试未断言键序：`PeripheralTest.java:99-112` 断言字段值/屏幕行，无 JSON 键序断言。→ 实际影响仅为 trace 可读性；建议保持低。 | 低（维持；措辞改为"非插入序"） |
| F10 | 低·疑点 | `peripheral/InventoryPeripheral.java:30,35,58-60,74-76`（报告 35,45） | `args.getInt(0)` 对小数参数（1.5）的截断/报错行为无测试；与真实 CC:T `IArguments.getInt` 语义是否一致待证 | **部分确认（静态已定，动态待 Lead）** | ① CC:T 1.120.2 core-api 中 `getInt` 是接口 default 方法：`javap -p -c IArguments` → `getInt(int)` = `getLong(int)` 后 `l2i`；`getLong(int)` = `get(index)`，非 `Number` 则 `LuaValues.badArgumentOf(...,"number")`，否则 `LuaValues.checkFiniteNum(index, number).longValue()`。② `checkFiniteNum` 只做 `Double.isFinite` 检查后原样返回 Number（`javap -c LuaValues.checkFiniteNum`：`checkFinite(index, number.doubleValue())` 后 `areturn` 原 Number）→ **1.5 走 `Number.longValue()` 截断为 1，再 `l2i` = 1，不报错**；NaN/±Infinity 抛 LuaException；非数字抛 bad argument "number expected"。③ 一致性：cct-run 直接调用 CC:T 自己的 `IArguments.getInt` 默认实现（上列行号），没有自写转换 → "与真实 CC:T `IArguments.getInt` 语义"按定义一致。④ 未完全静态验证的部分：真实 generic inventory 用 `@LuaFunction` 声明 `int`/`Optional<Integer>` 参数（Gradle 缓存 `cc-tweaked-1.20.1-forge-1.120.2.jar` 中 `AbstractInventoryMethods.pushItems(T, IComputerAccess, String, int, Optional<Integer>, Optional<Integer>)`），其框架层参数转换实现不在已安装 core jar、未审计；测试也无小数参数（`PeripheralTest.java` 全部整数）→ "无测试"确认。**待 Lead 动态**：`pcall(left.getItemDetail, 1.5)` 预期返回槽位 1 的详情（截断），可与真实环境对照。⑤ 报告行号 45 实为 `getTarget()` 返回语句；`getItemLimit` 在 34-37。 | 低（维持） |
| F11 | 正面 | `Fixture.java:238,311-320`；`ScriptedPeripheral.java:34-50` | scripted 方法名限 Lua 标识符、timeline 只引用已声明方法、sets 只写数据；Lua 参数原样透传，无注入面 | **确认（正面）** | ① 方法名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`：`Fixture.java:238`。② timeline `method` 只能引用该 fixture 已声明的方法：`Fixture.java:311-320`（`requirePeripheral(...,"scripted")` + `if (!target.getAsJsonObject("methods").has(method)) throw ...`）。③ `sets` 只指同表方法名（`Fixture.java:248-251`），运行期只把"第一个参数"存为该方法的返回值（`ScriptedPeripheral.java:34-42`），不执行代码。④ Lua 参数经 `JsonValues.toLua` 原样转换（`JsonValues.java:107-124`、`ScriptedPeripheral.java:73`），全链路无字符串拼接/`load`/反射入口 → 无注入面。⑤ 备注：Lua 标识符限制严于安全需要（peripheral 方法名经 API 调用，不参与代码生成），属防御性约束，不是漏洞。 | 正面（维持） |
| F12 | 正面 | `Fixture.java:365,416-434`；`Inventory.java:33-35,65-79`；`InventoryPeripheral.java:34-37` | 任意格子任意物品、上限 64 与实现一致 | **确认（正面，附建模简化说明）** | ① 任意物品：仅要求 id 含 `":"`（`Fixture.java:420`），`displayName/tags/nbt/damage/maxDamage/maxCount/count` 可选，`count ≤ maxCount ≤ 64`（`416-434`，`421-423`）；任意槽位：`items` 键 1..size（`178-182`），timeline `item` 槽位 1..该 inventory 的 size（`305-307`）。② 上限 64 与实现一致：`365` `new Inventory(size, 64)`；`insertInto` 上限 `Math.min(like.maxCount, slotLimit)`（`Inventory.java:67`）；`getItemLimit` 返回 `inventory.slotLimit()`（`InventoryPeripheral.java:34-37`，恒 64）；`handoff.md:348` 记录 "slot limit 64"、`handoff.md:384` 记录 "Slot limit fixed at 64; no per-slot rules"。③ 建模简化（非缺陷，已文档化）：fixture `items` 直接 `inventory.set`（`Fixture.java:364-370`）不复查 slotLimit —— 因 `maxCount ≤ 64` 与 `slotLimit = 64` 恒一致而无害，但若将来 slotLimit 可配置会漏校验；`getItemLimit` 所有槽位恒 64（真实部分 mod 有 per-slot 规则，已在 handoff L3 说明）。 | 正面（维持） |

## 偏差/修正（含 NEW）

### 行号/描述偏差
1. **F1**：报告 `Fixture.java:77-80` → 实际 `74-80`（catch 在 `78-80`）。更关键的是依据错：**gson 2.11.0 jar 的 `JsonReader` 没有 255 层上限**（`javap` 无 `nestingLimit`/`setNestingLimit`），深嵌套的保护不是 255 上限而是 `JsonParser` 把 `StackOverflowError` 包成 `JsonParseException`（`javap -c` 异常表可证）。因此 300 层动态测试的预期要改写：**大概率解析成功而非报 not valid JSON**；实际保护阈值为 JVM 栈深。
2. **F2**：报告 `302-303` → 实际默认 27 在 `306`（302-303 是注释与 allowOnly）；"27/100_000/64 三处重复" → **27 与 100_000 各 3 处，64 只在 `365` 出现一次**（421/422 的 64 是物品 maxCount 语义）。`FixtureTest.java:51-53` → **注释实际在 52-53 行**（51 行是 `void timelineItemSlotMustFitTheInventory()` 签名）。"丢失 JSON 路径"确认（`410`、`441` 的 `IllegalStateException("validated earlier")`）。
3. **F3**：报告 `517-522` → 实际 `517-523`；结论本身确认，补充 `build` 阶段的 `getAsLong()`（`298`、`374`、`383`）对 `1e999` 同样饱和。
4. **F4**：报告 `73-80,113` → 解析入口实际 `74-162`（无长度检查）+ CLI `Main.java:306`、MCP `McpServer.java:103` 也无上限；`JsonValues.java:107-123` → 实际 `107-124`。
5. **F5**：报告 `148-160` → 实际 `148-159`；补充遍历实现 `Runner.java:193-196` 与复杂度 O(ticks×actions)（每 tick 全表扫描、无索引；不排序不构成问题）。
6. **F6**：报告 `136-139` → 实际 `136-140`（`catch` 到 `throw`）；"trace 对 MCP 客户端可见"确认（`Trace.java:80-91` + `cct_run` notes / `cct_trace`）。
7. **F7**：位置对，但**结论反转**：竞争 **不存在**（详见上表证据链；`MainThread.tick()` 与 `MainThreadExecutor.execute()` 均在调用线程同步执行 `Runnable.run()`）。建议撤销该低·疑点，改为防御性备注（见 NEW-B5）。
8. **F8**：报告 `40-43` → 实际数值分支 `40-42`；"是否已文档化"→ **未文档化**（`JsonValues.java:16` 只有一句 bounded JSON）。
9. **F9**：报告 `42-47` → 实际 `42-48`（basicDetail）/`51-61`（fullDetail）；"键序不稳定" → **"非插入序、但对固定键集确定"**，且测试未断言键序。
10. **F10**：报告 `InventoryPeripheral.java:35,45` → 实际 getInt 调用在 `30`（getItemDetail）、`35`（getItemLimit）、`58-60`/`74-76`（push/pull）；**45 行是 `getTarget()` 的 return**。语义结论：`getInt` 委托 `getLong`→`Number.longValue()`，**1.5 截断为 1、不报错**；cct-run 直接复用 CC:T 的 `IArguments.getInt` 默认实现，无需自证一致。
11. **F12**：报告 `365,423-427` → 实际 `365` 对；物品校验实际 `416-434`（`421-423` 为 maxCount/count 上限逻辑），`Inventory.java:33-35,71-77` 对。

### 级别升降建议
- **F4 低 → 中低**：新增可达崩溃路径（深嵌套 timeline `args` → `JsonValues.toLua`/`Runner.noteWideText` 递归 `StackOverflowError`，且 `loop` 只 catch `RuntimeException`、`Main` 不 catch `StackOverflowError`，导致无 summary、带栈退出）。
- **F1 维持"中·疑点"但理由改写**：不是"依赖升级后出现 255 上限回归"，而是"当前 2.11.0 无硬上限，靠 JVM 栈深 + gson 的 SOE→JsonParseException 包装兜底；若将来 gson 升级引入 255 上限，行为会从'校验拒绝'提前为'parse 拒绝'（对使用者仍是 FixtureException，变化可接受）"。
- **F7 低·疑点 → 不成立**（可撤销或降级为注释级）。
- **F2 建议维持中**：三份常量（27×3、100_000×3）无单一来源，改动默认值容易只改 validate 漏改 build。
- **F6/F8/F9/F10/F12 维持低/正面**。

### NEW（审查报告遗漏的同模块真实问题）
- **NEW-B1（中低，并入 F4）**：timeline `event.args` 只校验"数组"不校验元素（`Fixture.java:283-286`），深嵌套数组/对象可通过校验；`JsonValues.toLua`（`107-124`）与 `Runner.noteWideText`（`480-496`）递归无深度上限，极端深度在 Runner 主循环线程抛 `StackOverflowError`，`Runner.loop`（`197`）与 `Main`（`100-107`）都不捕获 → `summary.json` 不写、进程带栈退出（exit 1）。真实 `of` 有 MAX_DEPTH=4 而 `toLua` 没有，属不对称。
- **NEW-B2（低）**：timeline 的 `tick` 允许 0..Integer.MAX_VALUE（`Fixture.java:154`），超过 `timeoutSeconds*20` 的条目静默不执行且无 trace 提示；timeline 无条数上限，`Runner.loop` 每 tick 全表线性扫描（`Runner.java:193-196`），大表会拉低 tick loop（触发 `tick_lag` 记录）。
- **NEW-B3（信息）**：默认值（27/100_000）在代码 3 处 + `handoff.md:348` 一份文档共 4 个来源，无单一常量；建议抽 `Fixture.DEFAULT_*`。
- **NEW-B4（信息·基线）**：仓库 HEAD `bf68ac4` 正确，但 `git describe` 为 `v0.2.3-9-gbf68ac4`（tag 落后 9 提交）；"HEAD = v0.2.3"应表述为"HEAD = bf68ac4，项目版本 0.2.3（jar 实测 0.2.3），tag v0.2.3 在 9 个提交之前"。
- **NEW-B5（低·防御，F7 附注）**：`ItemStack.count` 非 volatile、`Inventory.get()` 返回引用后 `fullDetail()` 在锁外读（`InventoryPeripheral.java:32-33`）。当前线程模型下无竞争，但 `Inventory.java:5-11`、`ItemStack.java:11` 未写明"所有访问都在主线程"的线程约束；若新增非 main-thread 方法即变成真竞争，建议加注释或让 `count` 读写统一持锁/volatile。
- **NEW-B6（低·文档，F6 附注）**：`DispatchPeripheral` 成功返回写 `peripheral_return` 记录（`record`），失败写 `note`（受 `MAX_NOTES=10` 配额限制，`Trace.java:30,80-91`）——大量失败调用时错误可能只出现在 `trace.jsonl`、`summary.notes` 里看不到；行为与设计一致但文档未说明。

### 待 Lead 动态验证项（静态无法定论）
1. **F1**：300 层（及更深，如 5k/20k 层）嵌套 fixture：预期 2.11.0 下解析成功（无 255 上限），后续按校验位置报错或放行；确认不会 `StackOverflowError` 逃逸（gson 包装）且错误信息为 `$: not valid JSON: ...`（若极深触发栈溢出）。
2. **F10**：`pcall(left.getItemDetail, 1.5)`（预期槽位 1 的详情，整数截断）；`pcall(left.getItemDetail, 0/0)` 之类 NaN/Infinity 预期报 "bad argument ... number expected"；与同版本 CC:T 游戏内行为对照。

## AI-ISSUES

```yaml
- id: F1
  verdict: partial
  severity_report: 中·疑点（报告已降级）
  severity_suggested: medium
  title: 深嵌套 JSON 的 SOE 防护依据不是 255 层上限，而是 gson 的 SOE 包装
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:76-80", "gradle/libs.versions.toml:4"]
  evidence: "实际 gson 2.11.0 的 JsonReader 无 nestingLimit/setNestingLimit；JsonParser.parseReader 把 StackOverflowError/OutOfMemoryError 包成 JsonParseException，Fixture.parse 只 catch JsonParseException，故深嵌套仍报 not valid JSON，阈值是 JVM 栈深。"
  impact: "300 层嵌套在默认栈下很可能解析成功，不会出现报告预期的 255 层 JsonParseException；升级 gson 后行为会提前为 parse 拒绝。"
  fix: "修正报告依据与动态测试预期；可在 javadoc 注明深嵌套依赖 JVM 栈，并考虑给 parse 增加显式大小限制。"
  tests: "FixtureTest 无深嵌套用例；300 层动态待 Lead。"
  source_ids: [F1]
- id: F2
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 默认值 27 与 100_000 各在 validate/build 重复 3 处且 build 靠异常字符串耦合
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:169", "engine/src/main/java/cctrun/engine/Fixture.java:306", "engine/src/main/java/cctrun/engine/Fixture.java:364", "engine/src/main/java/cctrun/engine/Fixture.java:188", "engine/src/main/java/cctrun/engine/Fixture.java:298", "engine/src/main/java/cctrun/engine/Fixture.java:374", "engine/src/main/java/cctrun/engine/Fixture.java:410", "engine/src/main/java/cctrun/engine/Fixture.java:441"]
  evidence: "27 在 169/306/364，100_000 在 188/298/374；64 仅在 365 的 slotLimit（421/422 的 64 是物品 maxCount 语义）；build 阶段抛 IllegalStateException(validated earlier)（410/441）丢失 JSON 路径；FixtureTest.java:52-53 注释记录 slot 越界回归。"
  impact: "改默认值或校验规则时容易只改一处，validate 与 build 不一致会导致运行期异常或语义漂移。"
  fix: "抽 DEFAULT_INVENTORY_SIZE / DEFAULT_ENERGY_CAPACITY 常量供两阶段共用，并给 build 阶段异常补 JSON 路径。"
  tests: "FixtureTest.timelineItemSlotMustFitTheInventory（52-66 行）覆盖该回归。"
  source_ids: [F2]
- id: F3
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: longValue 只查下限，Infinity 与超 Long 范围的值被静默饱和为 Long.MAX_VALUE
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:517-523", "engine/src/main/java/cctrun/engine/Fixture.java:188-190", "engine/src/main/java/cctrun/engine/Fixture.java:297-299", "engine/src/main/java/cctrun/engine/Fixture.java:374-375"]
  evidence: "longValue 校验 d == Math.rint(d) 与 d < min 后直接 (long) d；Infinity 通过两检查并饱和为 Long.MAX_VALUE；build 阶段 getAsLong 经 LazilyParsedNumber 同样饱和；capacity/energy/tank amount 均无上限。"
  impact: "fixture 可声明 capacity=Long.MAX_VALUE 或 1e999 而不报错，能量模型边界失真但不会崩溃（setEnergy 有 clamp）。"
  fix: "longValue 增加 max 参数或 Long.MAX_VALUE 有限性检查，对 1e999 报 must be a whole number in range。"
  tests: "无（Lead 将动态测 {capacity:1e999}）。"
  source_ids: [F3]
- id: F4
  verdict: confirmed
  severity_report: 低
  severity_suggested: medium
  title: 解析无总大小上限且 toLua 无深度限制，深嵌套 timeline args 可致 StackOverflowError 且无 summary
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:283-286", "engine/src/main/java/cctrun/engine/JsonValues.java:107-124", "engine/src/main/java/cctrun/engine/JsonValues.java:18-20", "engine/src/main/java/cctrun/engine/Runner.java:197", "engine/src/main/java/cctrun/engine/Runner.java:480-496"]
  evidence: "event.args 只校验是数组、元素不校验；JsonValues.toLua 无 depth/entries 上限（of 有 MAX_STRING=200/MAX_ENTRIES=50/MAX_DEPTH=4）；Runner.loop 只 catch RuntimeException，Main 不 catch StackOverflowError，深嵌套可致无 summary 带栈退出。"
  impact: "自伤式 fixture 可使 cct-run 进程异常退出、不写 summary.json，MCP 客户端只看到子进程失败；极端 fixture 内存占用无上限。"
  fix: "给 toLua/noteWideText 加深度与条目上限（复用 JsonValues MAX_DEPTH），或在 Fixture.parse 对 args 做递归深度校验并限制 fixture 字节数。"
  tests: "none"
  source_ids: [F4, NEW-1]
- id: F5
  verdict: partial
  severity_report: 低（部分待证）
  severity_suggested: low
  title: timeline 不排序但按 tick 每次全表线性扫描，同 tick 顺序依赖 fixture 顺序
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:148-159", "engine/src/main/java/cctrun/engine/Runner.java:193-196"]
  evidence: "timeline 存 ArrayList，不排序不查重无条数上限；Runner.loop 每 tick for 全表 + tick 相等才执行，复杂度 O(ticks×actions)，同 tick 多条按 fixture 顺序且在 computer.tick() 之前执行。"
  impact: "不排序不构成正确性问题（语义只依赖 tick），但大 timeline 会拖慢 tick loop；超过 timeout 的 tick 静默不执行且无提示。"
  fix: "可按 tick 排序或建索引以降低复杂度；对 tick 大于 timeout 的条目给 parse 期告警，并在 handoff 文档说明同 tick 顺序语义。"
  tests: "PeripheralTest.energyFluidAndTimelineChanges（220-226 行）证实同 tick 多条均执行。"
  source_ids: [F5, NEW-2]
- id: F6
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: "RuntimeException 以 internal: 前缀写入 trace，无堆栈但对 MCP 客户端可见"
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/peripheral/DispatchPeripheral.java:136-140", "engine/src/main/java/cctrun/engine/Trace.java:80-91"]
  evidence: "catch RuntimeException 写 error=internal: + e 并 trace.note(peripheral_return)；note 同时进 trace.jsonl 与前 10 条 summary.notes，cct_run 返回 notes、cct_trace 可读。"
  impact: "泄漏限于异常类名与 message（无堆栈），可能包含内部类名/边界信息，风险有限。"
  fix: "可把 internal 错误的 message 替换为固定文案、详细异常只写 trace 不进 summary.notes。"
  tests: "PeripheralTest.inventoryErrorsUseTheGamesMessages（116-134 行）覆盖 notes 可见性。"
  source_ids: [F6, NEW-6]
- id: F8
  verdict: partial
  severity_report: 低
  severity_suggested: low
  title: trace 数值转换把 ≥1e15 整数转 double、NaN/Infinity 转字符串，且未在 javadoc 说明
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/JsonValues.java:38-43", "engine/src/main/java/cctrun/engine/JsonValues.java:16"]
  evidence: "代码行为与报告一致：整数 |d|<1e15 转 long，其余整数转 double 丢精度，NaN/±Inf 转字符串；类 javadoc 仅 bounded JSON for the trace，未记录数值强转/类型变化。"
  impact: "trace 消费方按 JSON 类型判断时可能把 Infinity 当字符串、大整数丢精度；不影响运行结果。"
  fix: "在 JsonValues 类 javadoc 与 handoff TRACE_FORMAT 记录数值/类型有界化规则。"
  tests: "none"
  source_ids: [F8]
- id: F9
  verdict: partial
  severity_report: 低
  severity_suggested: low
  title: basicDetail/fullDetail 用 HashMap 导致 trace 键序非插入序（对固定键集确定）
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/peripheral/ItemStack.java:42-48", "engine/src/main/java/cctrun/engine/peripheral/ItemStack.java:51-61"]
  evidence: "basicDetail 用 HashMap，fullDetail 复用后追加字段；HashMap 迭代序由字符串 hash 决定，对固定键集确定但非插入序/非排序，JSON 语义不受影响；测试未断言键序。"
  impact: "trace 可读性与跨版本可比性略差，无功能影响。"
  fix: "改用 LinkedHashMap 固定字段顺序，便于人工与工具比对。"
  tests: "PeripheralTest 仅断言字段值，无键序断言。"
  source_ids: [F9]
- id: F10
  verdict: partial
  severity_report: 低·疑点
  severity_suggested: low
  title: args.getInt 对小数截断为整数（1.5→1）且无测试，但复用 CC:T 默认实现故与游戏语义一致
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java:30", "engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java:35", "engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java:58-60", "engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java:74-76"]
  evidence: "IArguments.getInt 委托 getLong（Number 检查 + LuaValues.checkFiniteNum + Number.longValue）后 l2i；1.5 截断为 1 不报错，NaN/Inf 抛 LuaException；cct-run 直接调用 CC:T 的默认实现，语义按定义一致。"
  impact: "程序传 1.5 时会静默取槽位 1 而不是报参数错误，与游戏行为一致但用户可能意外。"
  fix: "保持现状（与游戏一致）；补一条小数参数的测试并考虑在 fixture/guide 文档说明截断行为。"
  tests: "none（PeripheralTest 全部整数参数；1.5 待 Lead 动态）。"
  source_ids: [F10]
- id: F11
  verdict: confirmed
  severity_report: 正面
  severity_suggested: info
  title: scripted 外设无注入面（方法名限标识符、只引用已声明方法、参数原样透传）
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:238", "engine/src/main/java/cctrun/engine/Fixture.java:311-320", "engine/src/main/java/cctrun/engine/peripheral/ScriptedPeripheral.java:34-50"]
  evidence: "方法名必须匹配 [A-Za-z_][A-Za-z0-9_]*；timeline method 只能引用该 fixture 已声明的 scripted 方法；sets 只写同表方法名且仅把首参数存为返回值；Lua 参数经 JsonValues.toLua 原样转换，无字符串拼接/load/反射入口。"
  impact: "fixture 无法注入或执行任意 Lua/Java 代码，正面结论成立。"
  fix: "保持；可在 handoff 增加一句安全设计说明。"
  tests: "PeripheralTest.scriptedPeripheralsStandInForModBlocks（31-76 行）与 scriptedPeripheralFixtureErrors（79-89 行）。"
  source_ids: [F11]
- id: F12
  verdict: confirmed
  severity_report: 正面
  severity_suggested: info
  title: 任意格子任意物品且上限 64 与实现一致（附 slotLimit 建模简化说明）
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:365", "engine/src/main/java/cctrun/engine/Fixture.java:416-434", "engine/src/main/java/cctrun/engine/peripheral/Inventory.java:33-35", "engine/src/main/java/cctrun/engine/peripheral/Inventory.java:65-79"]
  evidence: "物品 id 仅要求含冒号，count≤maxCount≤64；槽位 1..size；slotLimit=64 且 insertInto 用 min(maxCount, slotLimit)，getItemLimit 返回 slotLimit；fixture items 直接 set 不复查 slotLimit 但 maxCount≤64 使其无害；handoff 记录 slot limit 固定 64、无 per-slot 规则。"
  impact: "建模与真实箱子一致；若将来 slotLimit 可配置，fixture items 写入会缺校验。"
  fix: "保持；若引入可配置 slotLimit，在 build 阶段对 items 数量做同源校验。"
  tests: "PeripheralTest.directlyAttachedInventoriesMoveItemsBySideName（91-114 行）覆盖 getItemLimit 与详情。"
  source_ids: [F12]
- id: NEW-1
  verdict: confirmed
  severity_report: NEW
  severity_suggested: medium
  title: 深嵌套 timeline event.args 可致 Runner 线程 StackOverflowError 且不写 summary
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:283-286", "engine/src/main/java/cctrun/engine/JsonValues.java:107-124", "engine/src/main/java/cctrun/engine/Runner.java:193-213", "engine/src/main/java/cctrun/engine/Runner.java:480-496", "cli/src/main/java/cctrun/cli/Main.java:100-107"]
  evidence: "args 只校验为数组、元素不递归校验，深嵌套可过 parse；Runner.apply 的 JsonValues.toLua 与 noteWideText 递归无深度上限；loop 只 catch RuntimeException，Main 不 catch StackOverflowError，故无 summary.json、进程带栈退出。"
  impact: "恶意/粗心 fixture 可令 cct-run 崩溃且丢失 summary；MCP 侧只得到子进程失败。"
  fix: "在 Fixture.parse 对 args 做深度/节点数上限校验，并给 toLua/noteWideText 加同一 MAX_DEPTH。"
  tests: "none"
  source_ids: [F4, NEW-1]
- id: NEW-2
  verdict: confirmed
  severity_report: NEW
  severity_suggested: low
  title: timeline 无条数上限且超过 timeout 的 tick 静默不执行
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:148-159", "engine/src/main/java/cctrun/engine/Fixture.java:154", "engine/src/main/java/cctrun/engine/Runner.java:189-196"]
  evidence: "timeline 条数无上限、tick 允许 0..Integer.MAX_VALUE；loop 每 tick 全表扫描且 tick>=maxTicks 直接 return timeout，超出 timeout 的动作静默丢失，无 trace 提示。"
  impact: "大 timeline 使每 tick 扫描变慢（tick_lag），过期动作不执行也无从得知，调试时易误判。"
  fix: "parse 期对 tick 超 timeout 给出警告/notes，并限制 timeline 条数或按 tick 排序索引。"
  tests: "none"
  source_ids: [F5, NEW-2]
- id: NEW-3
  verdict: confirmed
  severity_report: NEW
  severity_suggested: info
  title: 默认值无单一常量来源（代码 3 处 + handoff 文档一份）
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/Fixture.java:169", "engine/src/main/java/cctrun/engine/Fixture.java:306", "engine/src/main/java/cctrun/engine/Fixture.java:364", "handoff.md:348"]
  evidence: "27 在三处代码 + handoff.md:348 文档中重复；100_000 同理，无 Fixture.DEFAULT_* 常量。"
  impact: "默认值调整易漏改，文档与代码可能不一致。"
  fix: "抽 DEFAULT_INVENTORY_SIZE/DEFAULT_ENERGY_CAPACITY 常量并让文档引用。"
  tests: "FixtureTest 断言部分默认值但不覆盖全部路径。"
  source_ids: [F2, NEW-3]
- id: NEW-4
  verdict: confirmed
  severity_report: NEW
  severity_suggested: info
  title: 基线表述 v0.2.3 与 git describe 不一致（HEAD 比 tag v0.2.3 多 9 提交）
  module: engine-fixture
  files: ["gradle/libs.versions.toml:4"]
  evidence: "HEAD=bf68ac4 正确，但 git describe=v0.2.3-9-gbf68ac4；0.2.3 是 Gradle 项目版本（cli/engine jar 均为 0.2.3），tag v0.2.3 在 9 个提交之前。"
  impact: "审查基线若写成 HEAD=v0.2.3 会与实际提交不一致，复现时可能 checkout 错误提交。"
  fix: "报告基线改为 HEAD=bf68ac4（项目版本 0.2.3，tag 落后 9 提交）。"
  tests: "none"
  source_ids: [NEW-4]
- id: NEW-5
  verdict: confirmed
  severity_report: NEW
  severity_suggested: low
  title: ItemStack.count 非 volatile 且线程约束未文档化（当前线程模型下无竞争）
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/peripheral/ItemStack.java:15", "engine/src/main/java/cctrun/engine/peripheral/Inventory.java:29-31", "engine/src/main/java/cctrun/engine/peripheral/InventoryPeripheral.java:32-33", "engine/src/main/java/cctrun/engine/peripheral/DispatchPeripheral.java:112-116"]
  evidence: "count 非 volatile，move 在 Inventory 锁内改、getItemDetail 在锁外读；但 inventory 全部方法 method(true) 经 executeMainThreadTask 由 mainThread.tick() 在同一 Runner 线程执行，apply 也在该线程，故当前无数据竞争。"
  impact: "现状安全；若新增非 main-thread 方法或改线程模型即成真竞争，且无注释提醒。"
  fix: "在 Inventory/ItemStack 注明所有访问必须在主线程，或让 count 读写统一持锁/volatile。"
  tests: "none"
  source_ids: [F7, NEW-5]
- id: NEW-6
  verdict: confirmed
  severity_report: NEW
  severity_suggested: low
  title: 外设错误写 trace.note 受 MAX_NOTES=10 配额限制，summary.notes 可能不完整
  module: engine-fixture
  files: ["engine/src/main/java/cctrun/engine/peripheral/DispatchPeripheral.java:136-140", "engine/src/main/java/cctrun/engine/Trace.java:30", "engine/src/main/java/cctrun/engine/Trace.java:80-91"]
  evidence: "成功用 trace.record、LuaException/RuntimeException 用 trace.note；note 只保留前 10 条进 summary.notes，其余仅 droppedNotes 计数，详细错误只在 trace.jsonl。"
  impact: "大量失败外设调用时 summary.notes 只显示前 10 条，错误排查需改读 trace.jsonl。"
  fix: "文档说明 notes 配额与 trace.jsonl 是完整来源，或对 internal 错误单独计数。"
  tests: "PeripheralTest.inventoryErrorsUseTheGamesMessages 断言 4 条 notes。"
  source_ids: [F6, NEW-6]
```
