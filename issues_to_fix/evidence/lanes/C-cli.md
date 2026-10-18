# cli lane 核对报告（C1–C4，L5–L15）

- 基线：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`，`git rev-parse HEAD` = `bf68ac478f50065e9d18808201f0e2071a580b37`，`git describe --tags` = `v0.2.3-9-gbf68ac4`；构建产物 `cli/build/install/cct-run/lib/cli-0.2.3.jar`、`engine-0.2.3.jar`、`gson-2.11.0.jar`（jar manifest `Bundle-Version: 2.11.0`）。仓库 `git status --porcelain` 为空，全程未改仓库、未跑 gradlew。
- 方法：以 HEAD 源码逐行核对（行号以 HEAD 为准）；`javap -p -c` 查 Gson 字节码；用已构建 jar + `javac 21` 在临时目录（`%TEMP%\gsonnest`、`%TEMP%\mcptest`）做 Gson 深嵌套探针，并直接驱动 `java -cp cli/build/install/cct-run/lib/* cctrun.cli.Main mcp`（stdin 文件在临时目录）做动态验证。
- 结论取值：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附方法）/ 降级。

## 汇总表

| ID | 审查级别 | 实际位置（HEAD） | 审查意见（简） | 核对结论 | 证据与说明 | 建议级别 |
|---|---|---|---|---|---|---|
| C1 | 中 | `LiveTools.java:784-809`（endpoint）、`829-852`（send）、`832`（header） | endpoint.json 的 URL 未校验 scheme/host 就把 Bearer token 发过去；回环约束仅在 mod 侧 | **确认**（建议降级） | `LiveTools.java:803-805` 直接 `URI.create(url)`；`832` 无条件加 `Authorization: Bearer`。mod 侧确有回环+Host 约束（`LiveHttp.java:79,735`），故实际风险是同权限本地篡改/伪造 game 目录；token 只在 `832`，错误消息只含 URL（`835/837`） | 低（原中，降级；仍建议加 scheme=http + host 回环校验） |
| C2 | 中 | `McpServer.java:100-128` | stdin 单行无上限；深嵌套 SOE 被 Gson 2.11.0 的 255 层上限阻断；残余风险是超大单行 OOM 打死读循环 | **部分确认** | 无行长上限、catch 只捕 `JsonParseException`/`RuntimeException` 属实；但 "255 层上限阻断 SOE" **不成立**：Gson 2.11.0 解析是迭代的（实测 2,000,000 层 OK）；真正 SOE 在**响应序列化**：深嵌套 `id` 在 `send()` 里递归写出，实测 depth 20000 时 exit 1。解析期 OOM 反而被 Gson 包成 `JsonParseException`（回 -32700，服务不死） | 低（原中，降级；但须修 NEW-1） |
| C3 | 中 | `McpServer.java:145-168,433-447`；`LiveTools.java:537-542,645-650,840-843` | notifications/cancelled 只杀子 JVM；live 工具无进程可杀、线程不中断，跑满预算后丢弃响应 | **确认** | `McpServer.java:443-446` 的 `cancel()` 只置 flag + `destroyForcibly`，没有 Thread/interrupt；live 工具调用不传 `Call`（`McpServer.java:215`），三处 `catch (InterruptedException)` 会响应中断，但无人 interrupt；`159` 结束后丢弃响应。`LiveTools.java:53` MAX_WAIT=120s | 低（原中，降级；语义问题，无资源泄漏） |
| C4 | 中 | `JavaFinder.java:73-90,124-131`；`Launcher.java:31-78` | release 的 JAVA_VERSION 被信任且取最高版本；PATH 可写目录可伪造 home 劫持 re-exec | **确认**（建议降级） | `JavaFinder.java:80-83` 只读 `release` 的版本行、从不执行 `java -version`；`128` 按 `major` 取最高（平局取搜索顺序靠前者）；`Launcher.java:66-76` 用 `best.java()` re-exec。利用前提是攻击者已能写 JAVA_HOME/PATH/Program Files/%LOCALAPPDATA%/`~/.jdks`（同权限），故风险低 | 低（原中，降级；可选加固：验证可执行文件版本或只信任显式配置） |
| L5 | 低 | `McpServer.java:107-123,135-172,389-407` | JSON-RPC 2.0 符合度总体良好；tools/call 无 id 静默丢弃 | **确认**（3 处小偏差） | 错误码 -32700/`110`、-32600/`113-115,122`、-32601/`170`；id 原样回显 `389-396`；通知不回复 `169-171`；`146` 无 id 丢弃。偏差：带 id 的 `notifications/*` 方法被静默丢弃；未知工具用 -32602（`160`+`216-218`） | 低 |
| L6 | 低 | `McpServer.java:46,113-115` | 声明支持 2025-03-26（允许批量）但数组消息统一 -32600 | **确认** | `46` 版本列表含 2025-03-26；`113-115` 任何 JSON 数组都回单个 -32600，无批量处理分支 | 低 |
| L7 | 低 | `McpServer.java:125-126` | EOF 后 `awaitTermination(2h)` 不取消在途调用；断连后子 JVM 跑满 wall limit | **确认** | `125-126` 仅 `pool.shutdown()` + `awaitTermination(2,HOURS)`；子 JVM 上限 `timeout+120s`（`48,248`），<2h，不会遗留；但退出路径不 cancel 在途调用 | 低 |
| L8 | 低 | `JavaFinder.java:96-122,152-178` | 搜索路径事实：JAVA_HOME→PATH→常见目录、无注册表；PATH 项 canonical 后上溯两级 | **确认** | `99` JAVA_HOME；`100-112` PATH→canonical→`getParentFile().getParentFile()`；`114,152-178` 默认根（Windows ProgramFiles/ProgramW6432/ProgramFiles(x86)/%LOCALAPPDATA%/Programs/~/.jdks；mac/Linux 见代码），无注册表 | 低 |
| L9 | 低 | `McpServer.java:228-284,286-295`；`Launcher.java:24-25` | 子 JVM 清理、临时文件删除、退出码映射符合预期 | **确认** | `233-237` 临时目录/文件；`249-260` 中断→`destroyForcibly`+re-interrupt；`261-271` 超时/退出码；`272-283` 删除文件与树；`286-295` 映射 64/65/3/69/70；`Main.java:39` 退出码常量；`Launcher.java:25` 69 | 低 |
| L10 | 低 | `LiveTools.java:835,837` | 连接失败/超时消息带 endpoint URL（含端口），令牌不泄漏 | **确认** | `835`/`837` 拼 `endpoint.base()`；token 全文件仅见于 `781`（record 字段）、`802`（读 token.txt）、`832`（Authorization 头），错误消息不含 token | 低 |
| L11 | 低 | `LiveTools.java:462-544,564-566` | wait 轮询预算收缩正确；isUnanswered 靠错误文案子串匹配，脆弱 | **确认** | `500-501` 每次轮询 `min(5000, max(remainingMs, 1000))`；`564-566` `contains("did not answer in time")`；`MIN` 下限 1000ms 使最后一轮可能略超剩余预算（测试按 <2.5s 断言） | 低 |
| L12 | 低 | `LiveTools.java:595-637,640-690,699-724,727-767` | lua 临时文件有清理；长括号 level 计算防注入 | **确认** | `727-730` `while (code.contains("]"+"=".repeat(level)+"]")) level++`；`744,766` 生成 `load([==[...]==], ...)`；`674-683` 成功后删 .lua/.out；`699-724` cleanTemp；`765` 脚本自删；名字内部生成（`624-626`）无路径穿越 | 低 |
| L13 | 低 | `Main.java:82,319-331`；`McpServer.java:409-418` | 纯 ASCII 输出证据链完整（cp936 安全）；README 无 "ASCII" 字样 | **确认** | `Main.ascii()` 把每个 ≥0x80 字符转 `\uXXXX`（`319-331`），各处输出前调用（`171,205,241,248,266,278`）；`McpServer.send()` 以 US_ASCII 写整行（`410-414`）；`Main.java:82` stdout 为 UTF-8 PrintStream；测试 `MainTest.java:23,143,149`、`McpServerTest.java:36`；README grep 无 ASCII | 低 |
| L14 | 低 | `McpTools.java:33,267-272`；`LiveTools.java:886-891` | 工具描述硬编码 "1.120.2/1.20.1"；参数校验双实现且 string 严格度不一致 | **部分确认** | 双实现且不一致属实：`McpTools.java:267-272` 要求 JSON string，`LiveTools.java:886-891` 接受任意 primitive（数字/布尔被 `getAsString`）；但审查行号 `McpTools.java:866-871` **不存在**（该文件仅 322 行，实际在 `LiveTools.java:886-891`）；硬编码版本只在 `McpTools.java:33`（cct_run 描述），LiveTools 无版本化描述（`LiveTools.java:50` 是注释）；`engine/.../Versions.java` 已有 TOOL/CC_TWEAKED/MINECRAFT 常量 | 低 |
| L15 | 低 | `cli/src/test/java/cctrun/cli/*` | 30 个 @Test；无畸形协议输入、并发交错、Launcher、endpoint 伪造、非 200 映射测试 | **部分确认** | 计数准确：JavaFinderTest 4 + LiveToolsTest 12 + MainTest 8 + McpServerTest 6 = 30。但"无畸形协议输入"不成立：`McpServerTest.java:63,69,94` 已测 `not json`→-32700、method 非字符串→-32600、仍继续服务；"非 200 映射"已测 401/404（`LiveToolsTest.java:296,315`）。真实盲区：Launcher 无测试、endpoint 伪造无测试、深嵌套 id/超长行无测试、500/畸形响应体无测试、并发交错仅靠注释（`LiveToolsTest.java:139`） | 低 |

## C1 细核：endpoint 读取与 token 发送全链

```java
// LiveTools.java:803-805
var url = endpoint.get("url").getAsString();
rememberedGameDir = gameDir;
return new Endpoint(URI.create(url.endsWith("/") ? url : url + "/"), token);
```
```java
// LiveTools.java:832
response = client.send(request.header("Authorization", "Bearer " + endpoint.token()).timeout(timeout).build(), ...);
```

- `endpoint()`（`784-809`）：`game_dir` 由 `workDir.resolve(given)` 得到（`788`），读取 `<gameDir>/cct-live/endpoint.json` 的 `url` 与 `token.txt`；**没有** scheme/host/端口校验，`URI.create` 对 `http://example.com/`、`https://…`、任意主机一律接受。`HttpClient` 显式 `NO_PROXY`（`245`）。
- 发送侧：所有 `get()`/`post()` 最终都走 `send()`（`811-852`），因此令牌会随请求发往 endpoint.json 指定的任意 URL。
- mod 侧确有回环约束：`mod/src/common/java/cctrun/live/LiveHttp.java:30-31`（注释：bound to 127.0.0.1 only, Host must be loopback）、`:79` 绑定 `127.0.0.1`、`:105` Host 不符回 403、`:109` 令牌错误 401、`:735` Host 白名单；`LiveSession.java:257` 写死 `http://127.0.0.1:<port>/`，`:284` 关服删 endpoint.json。
- 真实风险：`endpoint.json` 与 `token.txt` 同目录；能改 endpoint.json 者通常也能读 token.txt，属同权限本地篡改，且 mod 正常写出的 URL 固定为回环。**结论为"确认"但建议级别降为低**；低成本加固仍值得：`scheme == http && host ∈ {127.0.0.1, localhost, [::1]}`（并与 mod 的 Host 校验对称）。
- 附带确认 L10：错误消息 `835/837` 只含 URL 与端口，不含 token；token 仅出现在 `781/802/832`。

## C2 细核：readLine / parseString / catch 范围与 Gson 事实

```java
// McpServer.java:103,107-112
while ((line = reader.readLine()) != null) {
    ...
    try { message = JsonParser.parseString(line); }
    catch (JsonParseException e) { send(error(JsonNull.INSTANCE, -32700, "Parse error")); continue; }
```
```java
// McpServer.java:117-123
try { handle(message.getAsJsonObject()); }
catch (RuntimeException e) { ... send(error(..., -32600, "Invalid Request: " + e.getMessage())); }
```

- `readLine()` 无任何长度上限（`101-105`）；`JsonParseException` 与 `RuntimeException` 之外的 `Error`（SOE/OOM）确实逃逸出 `serve()`（整段无 `catch (Throwable)`/`catch (Error)`）。
- **Gson 2.11.0 实测（该依赖的字节码/行为，不是猜测）**：
  - `JsonParser.parseString` 嵌套数组 254/255/256/300/10 000/100 000/500 000/2 000 000 层全部成功（逐层实测深度完全一致），嵌套对象同量级亦成功 → **没有 255 层上限，也不会解析期 SOE**。原因：`com.google.gson.internal.bind.TypeAdapters$28.read` 用显式 `java/util/ArrayDeque` 迭代解析（`javap` 可见 `ArrayDeque.<init>`、`Deque.addLast/removeLast`，TypeAdapters.java:820/883）。
  - `JsonElement` 的**序列化**仍是递归（`TypeAdapters$28.write`，TypeAdapters.java:933/935）：`JsonParser.parseString("[".repeat(10000)+…).toString()` 即 `StackOverflowError`。
- **动态验证（用 HEAD 构建产物 `cct-run mcp`，临时 stdin 文件）**：
  1. 普通深嵌套（depth 5 000 的 `id`）：三条请求全部正常应答（深 id 原样回显），exit 0 → 解析确实不设限。
  2. depth 20 000 / 100 000 的 `id`：stdout 只有 initialize 应答，进程 **exit 1**，stderr 为 `Exception in thread "main" java.lang.StackOverflowError … com.google.gson.stream.JsonWriter.beforeValue … TypeAdapters$28.write` → SOE 发生在 `send()` 序列化响应时，读循环被 `Error` 打死。
  3. `-Xmx64m` + depth 2 000 000 的 `id`（单行 4 000 039 字符）：服务**没有死**，对应该请求回 `-32700 Parse error`，随后 ping 仍正常；同输入独立测试的异常是 `JsonParseException`，`Caused by: java.lang.OutOfMemoryError` → 解析期 OOM 被 Gson `JsonParser.parseReader` 包成 `JsonParseException`（`javap` 确认异常表：`StackOverflowError→31`、`OutOfMemoryError→65`，消息 `Failed parsing JSON source: ... to Json`）。
- 因此报告的技术论断需修正：255 层上限不存在；解析期 SOE/OOM 都不会直接打死读循环；真正会打死服务的是**响应序列化深嵌套 id**（NEW-1）。报告"超大单行 OOM 打死读循环"仅在 OOM 发生在 `readLine()` 阶段时成立（NEW-2）。

## C3 细核：Call.cancel 与 sleep/中断

```java
// McpServer.java:443-446
synchronized void cancel() {
    cancelled = true;
    if (process != null) process.destroyForcibly();
}
```
```java
// LiveTools.java:537-542
try { Thread.sleep(POLL_MILLIS); }
catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new McpTools.ToolError("cancelled"); }
```

- 取消路径：`notifications/cancelled` → `active.get(requestId.toString()).cancel()`（`165-168`），`cancel()` 只置 flag + 杀子进程；`Call` 没有线程句柄，也不 `interrupt()`。
- live 工具：`callTool` 只把 `Call` 传给 `run()`（`McpServer.java:210`）；`LiveTools.NAMES` 走 `live.call(name, arguments)`（`215`），没有 `Call` 参数 → 无进程可杀、无中断来源；`wait()`/`collect()`/`send()` 三处 `catch (InterruptedException)` 说明**代码本身响应中断**，但没有任何代码 interrupt 调用线程。
- 结果：live 工具取消后线程继续跑到自身预算（`cct_live_wait`/`lua` 最长 120 s，`LiveTools.java:53`），结束后 `McpServer.java:159` 因 `cancelled` 丢弃响应。**确认**，但应表述为"无人中断"而非"sleep 不响应中断"。

## C4 细核：release 解析、best() 与 Launcher re-exec

```java
// JavaFinder.java:80-83
Matcher m = JAVA_VERSION.matcher(line);
if (m.matches()) { int major = major(m.group(1)); return major < 0 ? null : new Home(canonical(dir), m.group(1), major); }
```
```java
// JavaFinder.java:128；Launcher.java:68,76
if (home.major >= minMajor && (best == null || home.major > best.major)) best = home;
command.add(best.java().getPath()); ... Process process = builder.start();
```

- 只信任 `release` 文件（`73-90`），版本号仅由 `JAVA_VERSION` 行得到；从不验证 `bin/java` 的真实版本。
- `best()`（`125-131`）只比较 `major`（补丁版本忽略），取最高；平局保留搜索顺序靠前者。
- 搜索顺序：JAVA_HOME → PATH → 默认根（`96-122,152-178`）；PATH 项 canonical 后取上两级（`104-110`），可被可写目录利用；`addUnder` 还会把 vendor 目录的版本子目录加入（`133-150`）。
- `Launcher` 仅在当前 Java <17 时 re-exec（`31-78`），用 `best.java()` + 原 JVM 参数 + classpath；`REEXEC_MARKER` 防循环。利用前提=攻击者已能写被搜索目录（同用户权限），**级别建议降为低**；加固可取：校验可执行文件 `-version`、忽略 PATH 中的相对项/可写项，或要求显式 `JAVA_HOME`。

## 偏差 / 修正（含 NEW）

1. **NEW-1（C2，重要）** 报告"Gson 2.11.0 的 255 层上限阻断 SOE"**不成立**：Gson 2.11.0 解析是迭代的、无 255 上限（实测 2,000,000 层可解析）；SOE 反而出现在**序列化**：请求里把 `id` 做成深嵌套数组，`reply()` → `send()` → `COMPACT.toJson` 递归写 JsonElement，depth≈20000 即 `StackOverflowError` 打死 MCP 服务（实测 exit 1）。修法：`serve()` 外层 `catch (Throwable)`，并校验 `id` 只允许 string/number/null。
2. **NEW-2（C2）** "超大单行 OOM 打死读循环"**部分不成立**：解析期 OOM 被 Gson 包成 `JsonParseException`（`parseReader` 捕获并转成 "Failed parsing JSON source ... to Json"），实际会被 `109` 捕获回 -32700、服务继续（-Xmx64m + 2,000,000 层实测）；只有 `readLine()` 阶段 OOM（未被 Gson 包裹）才会逃逸。
3. **NEW-3（C2，静态推断）** 若 OOM 从 `serve()` 逃逸，`Main.main` 的 `catch (OutOfMemoryError)`（`Main.java:100-102`）会调 `outOfMemory()` 把 cct_run 摘要 JSON 写到 **stdout（即 MCP 协议流）** 后 exit 4（`253-267`），会污染协议行；建议 mcp 模式单独处理。
4. **NEW-4（C3）** 表述修正：不是"sleep 不响应中断"，而是"没有任何代码 interrupt"；`wait()/collect()/send()` 三处已具备中断响应能力。修法：`Call` 保存提交的 `Future`/`Thread`，`cancel()` 时 `interrupt()`。
5. **NEW-5（L7/L9）** 池线程是 daemon（`McpServer.java:55-59`），EOF 后 2 h 超时直接退出；子 JVM wall 上限 `timeout+120s`（≤3720 s）小于 2 h，故不会遗留，但退出路径确实不 cancel 在途调用。
6. **NEW-6（L11）** `MIN_POLL_REQUEST_TIMEOUT_MS=1000`（`LiveTools.java:58`）使最后一次轮询可能超出剩余预算约 1 s；现有测试按 <2.5 s 断言，属可接受余量，但"预算收缩"并非严格不超时。
7. **NEW-7（L14/L15，行号/范围修正）** `McpTools.java:866-871` 不存在（文件 322 行）；双 `string()` 实际为 `McpTools.java:267-272`（严格）与 `LiveTools.java:886-891`（宽松）；硬编码 `1.120.2/1.20.1` 只在 `McpTools.java:33`（cct_run 描述），LiveTools 无版本化描述（`LiveTools.java:50` 是注释）；`McpServer.initialize` 的 title 用的是 `Versions` 常量。
8. **L15 修正** 30 个 @Test 计数准确；"无畸形协议输入测试"不成立（`McpServerTest.java:63,69,94`）；"非 200 映射"已有 401/404 覆盖（`LiveToolsTest.java:296,315`）。真实盲区：Launcher、endpoint 伪造（如 `url: http://example.com/`）、深嵌套 id/超长行、500/畸形响应体、并发交错压力（`LiveToolsTest.java:139` 仅注释说明并发）。
9. **L5 小偏差**：带 `id` 的 `notifications/*` 请求会被静默丢弃（`McpServer.java:170` 的条件）；`tools/call` 无 id 直接丢弃且不执行（`146`，符合通知语义但易误用）；未知工具/内部错误用 -32602（`160`+`216-218`），与 -32601/-32603 的严格分法略有出入。
10. **C1/C3/C4 级别**：报告级别"中"偏保守：C1/C4 的利用前提都是本地同权限写入，C3 只是取消语义无效、无资源泄漏；建议三条都降为低。C2 建议保持关注（NEW-1 是真实可复现的服务崩溃），但同样限本地 MCP 客户端（客户端本身可 kill 服务），所以定为低-中之间、以修复为主。
11. **行号总体评价**：C1（803-805/832）、C2（101-124）、C3（165-168/443-445）准确；C4（75-90/121-128 实际 73-90/124-131）、L5/L8/L9/L11/L12/L13 为近似（偏差 ≤15 行）；L14 有一处不存在的行号。

## 结论计数

- 确认 12：C1、C3、C4、L5、L6、L7、L8、L9、L10、L11、L12、L13（其中 C1、C4 建议降级）。
- 部分确认 3：C2、L14、L15。
- 不成立 0；已修复 0；无法静态验证 0。
- 建议级别：C1→低、C2→低（必改 NEW-1）、C3→低、C4→低，其余保持低。

## AI-ISSUES

- id: C1
  verdict: confirmed
  severity_report: 中
  severity_suggested: low
  title: "endpoint.json 的 URL 未校验 scheme/host 即发送 Bearer 令牌"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:784-809", "cli/src/main/java/cctrun/cli/LiveTools.java:832"]
  evidence: "803-805 直接 URI.create(url)，832 无条件加 Authorization 头；mod 侧已强制 127.0.0.1 绑定与 Host 校验（LiveHttp.java:79/735），仅同权限本地篡改面，故降级"
  impact: "可把令牌发往任意主机（前提是本地改 endpoint.json 或使用伪造 game 目录）"
  fix: "校验 scheme=http 且 host 为 127.0.0.1/localhost/[::1]"
  tests: none
  source_ids: [C1]
- id: C2
  verdict: partial
  severity_report: 中
  severity_suggested: low
  title: "stdin 单行无长度上限且读循环不捕获 Error；255 层上限说法不成立"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:100-128"]
  evidence: "103 无行长上限，109/119 只捕 JsonParseException/RuntimeException；实测 Gson 解析 200 万层成功（无 255 上限），解析期 OOM 被包成 JsonParseException；真正 SOE 见 NEW-1"
  impact: "异常输入可触发未捕获 Error（OOM/SOE），服务可能崩溃"
  fix: "serve() 外层 catch Throwable，并限制 id 只允许 string/number/null"
  tests: "McpServerTest 仅覆盖 not json 与 method 非字符串"
  source_ids: [C2]
- id: C3
  verdict: confirmed
  severity_report: 中
  severity_suggested: low
  title: "cancelled 只杀子 JVM，live 工具无进程可杀且无人中断"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:145-168", "cli/src/main/java/cctrun/cli/McpServer.java:433-447", "cli/src/main/java/cctrun/cli/LiveTools.java:537-542"]
  evidence: "cancel() 仅置 flag 与 destroyForcibly（443-446），live 调用不传 Call（215）；三处 catch(InterruptedException) 本身响应中断但无人 interrupt，结束后丢弃响应（159）"
  impact: "取消后 live 工具仍跑满预算（最长 120s），无资源泄漏"
  fix: "Call 记录 Future/Thread，cancel 时 interrupt"
  tests: "McpServerTest.cancelStopsARunningChild 仅覆盖 cct_run"
  source_ids: [C3]
- id: C4
  verdict: confirmed
  severity_report: 中
  severity_suggested: low
  title: "release 文件版本被信任且取最高版本，可伪造 home 劫持 re-exec"
  module: cli
  files: ["cli/src/launcher/java/cctrun/cli/JavaFinder.java:73-90", "cli/src/launcher/java/cctrun/cli/JavaFinder.java:125-131", "cli/src/launcher/java/cctrun/cli/Launcher.java:66-76"]
  evidence: "80-83 只解析 release 的 JAVA_VERSION，从不执行 java -version；128 按 major 取最高；Launcher 用 best.java() 重执行；利用需可写 JAVA_HOME/PATH 等目录（同权限），故降级"
  impact: "同权限攻击者可劫持 Launcher 重新执行的 Java"
  fix: "校验可执行文件实际版本或只信任显式配置"
  tests: "JavaFinderTest 4 例，不覆盖伪造 home"
  source_ids: [C4]
- id: L5
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: "JSON-RPC 2.0 符合度良好，仅 3 处小偏差"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:107-123", "cli/src/main/java/cctrun/cli/McpServer.java:135-172", "cli/src/main/java/cctrun/cli/McpServer.java:389-407"]
  evidence: "错误码 -32700/-32600/-32601 正确，id 原样回显，通知不回复；偏差为带 id 的 notifications/* 静默丢弃、未知工具用 -32602（160/216-218）"
  impact: "无实际影响"
  fix: "可选微调错误码与通知处理"
  tests: "McpServerTest.handshakeAndToolList"
  source_ids: [L5]
- id: L6
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: "声明支持 2025-03-26 但批量数组统一回 -32600"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:46", "cli/src/main/java/cctrun/cli/McpServer.java:113-115"]
  evidence: "46 版本列表含 2025-03-26；113-115 任何 JSON 数组只回单个 -32600，无批量处理分支"
  impact: "协商 2025-03-26 的客户端批量请求不可用"
  fix: "声明不支持批量或实现批量处理"
  tests: none
  source_ids: [L6]
- id: L7
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: "EOF 后 awaitTermination(2h) 不取消在途调用"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:125-126"]
  evidence: "125-126 仅 pool.shutdown() 与 awaitTermination(2,HOURS)；子 JVM 上限 timeout+120s（48/248）小于 2h，不会遗留"
  impact: "客户端断连后子 JVM 跑满自身 wall limit 才结束"
  fix: "EOF 时 cancel 在途 Call"
  tests: none
  source_ids: [L7]
- id: L8
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "搜索路径事实描述准确：JAVA_HOME→PATH→常见目录，无注册表"
  module: cli
  files: ["cli/src/launcher/java/cctrun/cli/JavaFinder.java:96-122", "cli/src/launcher/java/cctrun/cli/JavaFinder.java:152-178"]
  evidence: "99 先 JAVA_HOME，100-112 PATH 项 canonical 后取上两级，114/152-178 默认根；全程无注册表访问"
  impact: "无"
  fix: "none"
  tests: "JavaFinderTest.javaHomeAndPathAreSearchedFirst"
  source_ids: [L8]
- id: L9
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "子 JVM 清理、临时文件删除与退出码映射符合预期"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:228-295", "cli/src/launcher/java/cctrun/cli/Launcher.java:24-25"]
  evidence: "233-237 建临时目录/文件，249-260 中断即 destroyForcibly，272-283 删除文件与目录树，286-295 映射 64/65/3/69/70"
  impact: "无"
  fix: "none"
  tests: "McpServerTest.exitMeaningNamesShutdownAndReboot"
  source_ids: [L9]
- id: L10
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "连接失败/超时消息含 endpoint URL 但不泄漏令牌"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:835", "cli/src/main/java/cctrun/cli/LiveTools.java:837"]
  evidence: "835/837 只拼 endpoint.base() 与端口；token 全文件仅见 781/802/832（Authorization 头），错误消息不含 token"
  impact: "无"
  fix: "none"
  tests: "LiveToolsTest.explainsWhatIsMissing（401/连接失败路径）"
  source_ids: [L10]
- id: L11
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "wait 轮询预算收缩正确，isUnanswered 依赖错误文案子串"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:500-501", "cli/src/main/java/cctrun/cli/LiveTools.java:564-566"]
  evidence: "500-501 每轮 min(5000,max(remaining,1000))ms；564-566 contains('did not answer in time')；MIN 1000ms 下限使最后一轮可能略超剩余预算"
  impact: "改文案会破坏超时判定，属脆弱实现"
  fix: "用结构化错误码替代子串匹配"
  tests: "LiveToolsTest.waitEndsWhenTheEndpointStopsAnswering"
  source_ids: [L11]
- id: L12
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "lua 临时文件清理与长括号 level 计算均正确"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/LiveTools.java:727-730", "cli/src/main/java/cctrun/cli/LiveTools.java:744-766", "cli/src/main/java/cctrun/cli/LiveTools.java:674-724"]
  evidence: "727-730 递增 level 后生成 load([==[...]==])；674-683 删 .lua/.out，765 脚本自删，699-724 cleanTemp；文件名由内部生成，无路径穿越"
  impact: "无"
  fix: "none"
  tests: "LiveToolsTest.luaNeedsTheShellPromptAndQuotesTheCode"
  source_ids: [L12]
- id: L13
  verdict: confirmed
  severity_report: 低
  severity_suggested: info
  title: "纯 ASCII 输出实现证据链完整（cp936 安全）"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/Main.java:319-331", "cli/src/main/java/cctrun/cli/McpServer.java:410-414"]
  evidence: "ascii() 转义全部非 ASCII，send() 以 US_ASCII 写整行；Main.java:82 stdout 为 UTF-8；README 无 ASCII 字样，测试覆盖 JSON 往返"
  impact: "无"
  fix: "none"
  tests: "MainTest.guideIsBundledAndAscii/runPrintsAsciiOnlyJsonThatDecodesToTheSameText，McpServerTest session 断言"
  source_ids: [L13]
- id: L14
  verdict: partial
  severity_report: 低
  severity_suggested: low
  title: "参数校验双实现且 string 严格度不一致；报告行号有误"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpTools.java:267-272", "cli/src/main/java/cctrun/cli/LiveTools.java:886-891"]
  evidence: "McpTools.string 要求 JSON string，LiveTools.string 接受任意 primitive；审查行号 McpTools.java:866-871 不存在（文件 322 行）；硬编码版本仅 McpTools.java:33"
  impact: "同类参数在不同工具上接受度不同，行为不一致"
  fix: "合并参数校验工具类，统一严格度"
  tests: none
  source_ids: [L14]
- id: L15
  verdict: partial
  severity_report: 低
  severity_suggested: low
  title: "30 个 @Test 计数准确，但部分宣称的盲区已被覆盖"
  module: cli
  files: ["cli/src/test/java/cctrun/cli/McpServerTest.java:63", "cli/src/test/java/cctrun/cli/LiveToolsTest.java:296"]
  evidence: "30 @Test=4+12+8+6；已有 not json/-32700、method 非字符串/-32600、401/404 测试；真实盲区为 Launcher、endpoint 伪造、深嵌套 id、500 响应、并发交错"
  impact: "上述路径缺回归保护"
  fix: "补 Launcher、伪造 endpoint、深嵌套 id/超长行、500 响应测试"
  tests: "4 个测试类共 30 用例"
  source_ids: [L15]
- id: NEW-1
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: "深嵌套 id 在 send() 序列化时 StackOverflowError 打死 MCP 服务"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/McpServer.java:159-162", "cli/src/main/java/cctrun/cli/McpServer.java:389-396"]
  evidence: "Gson 2.11.0 JsonElement 写出递归：id 为 20000 层嵌套数组时 send()/COMPACT.toJson 触发 StackOverflowError，实测进程 exit 1，stdout 只有 initialize 应答"
  impact: "异常客户端可崩溃本地 MCP 服务（客户端本身可终止进程，实际风险有限）"
  fix: "serve() 外层 catch Throwable；id 仅允许 string/number/null"
  tests: "none（建议补深嵌套 id 用例）"
  source_ids: [C2]
- id: NEW-2
  verdict: confirmed
  severity_report: 中
  severity_suggested: low
  title: "OOM 逃逸时 Main 会把 cct_run 摘要 JSON 写进 MCP 协议流"
  module: cli
  files: ["cli/src/main/java/cctrun/cli/Main.java:100-102", "cli/src/main/java/cctrun/cli/Main.java:253-267"]
  evidence: "静态推断：serve() 抛 OOM 时 Main.main 的 catch(OutOfMemoryError) 调 outOfMemory(stdout) 把摘要行写 stdout 后 exit 4；readLine 阶段 OOM 可逃逸（解析期 OOM 被 Gson 包装不会）"
  impact: "MCP 协议流被非 JSON-RPC 行污染，客户端解析失败"
  fix: "mcp 模式单独处理 Error，不向 stdout 写摘要"
  tests: none
  source_ids: [C2]
