# 核对任务 C：cli lane

基线：D:\AI\DSH\cc-t\CC-Tweaked_MCP，git HEAD = bf68ac4（v0.2.3）。只读核对，**不要修改仓库文件**。
产物：写到 `D:\AI\DSH\cc-t\CC-Tweaked_MCP\issues_to_fix\evidence\lanes\C-cli.md`（表格 + 偏差说明）。
结论用：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附方法）/ 降级。证据给 文件:行号 + 关键代码（≤2 行）。
注意：该模块在 0.2.3 之前修过一批问题，行号可能已漂移；以 HEAD 为准核对。

## 待核对条目（审查报告编号；F5-F15 为低级别）

| ID | 级别 | 审查位置 | 审查意见摘要 | 核对要点 |
|---|---|---|---|---|
| C1 | 中 | cli/src/main/java/cctrun/cli/LiveTools.java:803-805,832 | endpoint.json 的 URL 未做 scheme/host 校验即把 Bearer 令牌发往该 URL；回环约束仅靠 mod 侧；建议校验 scheme=http、host 为 127.0.0.1/localhost/[::1]。 | 核对 endpoint()/send()/get() 全链；确认 token 只在 Authorization 头、错误消息是否含 URL；评估真实风险级别。 |
| C2 | 中 | cli/src/main/java/cctrun/cli/McpServer.java:101-124 | stdin 单行无长度上限；深嵌套 SOE 当前被 Gson 2.11.0 的 255 层上限阻断；残余风险是超大单行 OOM 打死读循环。 | 核对 readLine/parseString/catch 范围；确认 catch 只捕 RuntimeException/JsonParseException，Error 逃逸；Lead 会做嵌套与超大行动态测试。 |
| C3 | 中 | McpServer.java:165-168,443-445 | notifications/cancelled 只杀子 JVM：cct_run 取消有效，但 live 工具无进程可杀、线程不中断，仍跑满预算后丢弃响应。 | 核对 Call.cancel 与 LiveTools.wait/collect 的 sleep/中断处理；确认 Thread.sleep 是否响应 interrupt（不响应还是响应但未 interrupt）。 |
| C4 | 中 | JavaFinder.java:75-90,121-128; Launcher.java:47-77 | release 文件 JAVA_VERSION 被信任且取最高版本；PATH 可写目录可伪造 home 劫持 re-exec（本地场景）。 | 核对 inspect/best/findAll 与 Launcher 启动链；评估风险前提与级别。 |
| L5 | 低 | McpServer.java:103,110-123,160-170 | JSON-RPC 2.0 符合度总体良好（行分帧、错误码、通知、id 回显）；tools/call 无 id 时静默丢弃。 | 逐项核对错误码、通知不回复、id 回显、无 id 丢弃。 |
| L6 | 低 | McpServer.java:46,113-114 | 声明支持 2025-03-26（允许批量）但数组消息统一 -32600。 | 核对 PROTOCOL_VERSIONS 与批量处理分支。 |
| L7 | 低 | McpServer.java:125-126 | EOF 后 awaitTermination(2h) 不取消在途调用；客户端断连后子 JVM 跑满 wall limit。 | 核对 EOF 分支与 call 列表处理。 |
| L8 | 低 | JavaFinder.java:94-119,152-176 | 搜索路径事实：JAVA_HOME→PATH→常见目录、无注册表；PATH 项 canonical 后上溯两级。 | 核对并确认事实描述是否准确。 |
| L9 | 低 | McpServer.java:239-310; Launcher.java:25-77 | 子 JVM 清理、临时文件删除、退出码映射（69/64/65/70、0-4）符合预期。 | 核对并确认。 |
| L10 | 低 | LiveTools.java:835,837 | 连接失败/超时错误消息把 endpoint URL（含端口）返回给 LLM；令牌不泄漏。 | 核对消息构造与 token 出现点（全文件 grep token）。 |
| L11 | 低 | LiveTools.java:462-543,564-566 | wait 轮询预算收缩正确；isUnanswered 靠错误文案子串匹配，脆弱。 | 核对预算计算与字符串匹配点。 |
| L12 | 低 | LiveTools.java:595-691,699-722,727-768 | lua 临时文件生命周期有清理；长括号 level 计算防 Lua 注入。 | 核对长括号构造算法与清理路径。 |
| L13 | 低 | Main.java:82,319-335; McpServer.java:410-418 | 纯 ASCII 输出实现证据链完整（cp936 安全）；README 本身无 "ASCII" 字样。 | 核对 ascii() 与写出编码、测试覆盖。 |
| L14 | 低 | McpTools.java:33,866-871; LiveTools.java:886-891 | 工具描述硬编码 "1.120.2/1.20.1"；参数校验双实现且 string 严格度不一致。 | 核对两处 string() 实现差异与实际行为。 |
| L15 | 低 | cli/src/test/java/cctrun/cli/* | 30 个 @Test；无畸形协议输入、并发交错、Launcher、endpoint 伪造、非 200 映射测试。 | 统计 @Test 数与测试类；列出盲区是否准确（现在可能已增加测试，注意 0.2.3 是否补过）。 |

## 额外要求
1. 报告还提到旧 F15 的测试盲区；如 HEAD 已有新增测试覆盖，明确标"已修复"。
2. 行号不符给实际行号；最后给"偏差/修正"小节。
