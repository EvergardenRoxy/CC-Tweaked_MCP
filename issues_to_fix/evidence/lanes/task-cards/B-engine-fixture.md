# 核对任务 B：engine fixture / peripheral lane

基线：D:\AI\DSH\cc-t\CC-Tweaked_MCP，git HEAD = bf68ac4（v0.2.3）。只读核对，**不要修改仓库文件**。
产物：写到 `D:\AI\DSH\cc-t\CC-Tweaked_MCP\issues_to_fix\evidence\lanes\B-engine-fixture.md`（表格 + 偏差说明）。
结论用：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附方法）/ 降级。证据给 文件:行号 + 关键代码（≤2 行）。

## 待核对条目

| ID | 级别 | 审查位置 | 审查意见摘要 | 核对要点 |
|---|---|---|---|---|
| F1 | 中·疑点（报告已降级） | Fixture.java:77-80 | 深嵌套 JSON 是否 SOE 取决于 Gson 版本；Gson 2.11.0 有 255 层上限，走正常 JsonParseException；降级为依赖降级时的回归风险。 | 核对 gson 版本（gradle/libs.versions.toml、实际 jar）、Fixture.parse 的 catch 范围、是否还有其它入口（JsonValues/CLI）。Lead 会做 300 层嵌套 fixture 动态测试。 |
| F2 | 中 | Fixture.java:169,188,298,302-303,365,374 | 默认值（27/100_000/64）在 validate 与 build 两阶段共 3 处重复；build 阶段靠 IllegalStateException("validated earlier") 耦合、丢失 JSON 路径；FixtureTest.java:51-53 注释证实历史越界回归。 | 核对每处行号与重复值；评估是否"确认/部分确认"（是否真有 3 处重复、是否有测试背书）。 |
| F3 | 低 | Fixture.java:517-522 | longValue 只查下限：1e999→Infinity、≥Long 范围经 (long) 静默饱和为 Long.MAX_VALUE；capacity/energy/tank 照单全收。 | 核对代码与调用点；Lead 会做 {"capacity":1e999} 动态测试。 |
| F4 | 低 | Fixture.java:73-80,113; JsonValues.java:107-123 | 解析无总大小上限（DOM 全量载入）；toLua 无深度/条目限制，与 of 的三重上限不对称。 | 核对上限常量与调用路径。 |
| F5 | 低（部分待证） | Fixture.java:148-160 | timeline 不排序不查重，跨 tick 执行语义取决于 Runner；同 tick 多条动作允许（测试证实为特性）。 | 核对 Fixture 存储与 Runner.loop 的 timeline 遍历方式，判断"不排序"是否构成问题、性能影响（每 tick 扫全表）。 |
| F6 | 低 | peripheral/DispatchPeripheral.java:136-139 | RuntimeException 以 "internal: " + e 写入 trace，trace 对 MCP 客户端可见；无堆栈，泄漏有限。 | 核对代码与 trace 消费面。 |
| F7 | 低·疑点 | peripheral/Inventory.java:38-64; peripheral/ItemStack.java:12 | ItemStack.count 非 volatile，move 在锁内改、getItemDetail 在锁外读；若 timeline 写与 Lua 读不在同一线程则数据竞争。 | 核对 Runner.apply 的执行线程、Lua 调用线程模型；给出"竞争存在/不存在"的判定。 |
| F8 | 低 | JsonValues.java:40-43 | |d|<1e15 转 long，更大整数转 double 丢精度；NaN/Infinity 转字符串（类型改变）。有界化取舍。 | 核对代码与类 javadoc 是否已文档化。 |
| F9 | 低 | peripheral/ItemStack.java:42-47 | basicDetail/fullDetail 用 HashMap，trace 键序不稳定。 | 核对代码与测试断言。 |
| F10 | 低·疑点 | peripheral/InventoryPeripheral.java:35,45 | args.getInt(0) 对小数参数（1.5）的截断/报错行为无测试；与真实 CC:T IArguments.getInt 语义是否一致待证。 | 从 CC:T jar（cli/build/install/cct-run/lib 或 Gradle 缓存）查 IArguments/AbstractLuaArgument 实现；Lead 可动态测。 |
| F11 | 正面 | Fixture.java:238,311-320; peripheral/ScriptedPeripheral.java:34-50 | scripted 方法名限 Lua 标识符、timeline 只引用已声明方法、sets 只写数据；Lua 参数原样透传，无注入面。 | 核对并确认正面结论。 |
| F12 | 正面 | Fixture.java:365,423-427; peripheral/Inventory.java:33-35,71-77 | 任意格子任意物品、上限 64 与实现一致。 | 核对并确认。 |

## 额外要求
1. 欢迎补充审查报告遗漏的同模块真实问题（标 NEW）。
2. 行号不符时给实际行号；最后给"偏差/修正"小节（级别升降、描述不准确）。
