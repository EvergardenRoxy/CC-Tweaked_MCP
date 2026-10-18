# 核对任务 D：mod lane

基线：D:\AI\DSH\cc-t\CC-Tweaked_MCP，git HEAD = bf68ac4（v0.2.3）。只读核对，**不要修改仓库文件**。
注意新布局：共享代码在 `mod/src/common/java/cctrun/live/`，平台层在 `mod/forge/...` 与 `mod/neoforge/...`。
产物：写到 `D:\AI\DSH\cc-t\CC-Tweaked_MCP\issues_to_fix\evidence\lanes\D-mod.md`（表格 + 偏差说明）。
结论用：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附方法）/ 降级。证据给 文件:行号 + 关键代码（≤2 行）。

## 待核对条目

| ID | 级别 | 审查位置 | 审查意见摘要 | 核对要点 |
|---|---|---|---|---|
| M1 | 中 | ComputerTrace.java:38,125,191,197 | kinds 索引列表随记录总数无上限增长（磁盘 trace 有双半区轮转配额，内存侧没有）；status/wait 线性全表扫描。 | 核对 kinds 增长与 rotate 是否裁剪；核对 countsAfter/firstAfter 扫描；结合 LiveHttp trace/replay 全文件扫描（:365-368,553-602）评估。 |
| M2 | 中 | mod/forge vs mod/neoforge | 平台层 CctLive/LiveConfig 约 95% 逐行重复（日志文案一字不差）；行为修复需双改；构建脚本约 70% 重复。 | 用 diff/相似度核对两份 CctLive/LiveConfig 与两份 build.gradle.kts；给出重复比例与实质差异。 |
| M3 | 中 | CctLive.java:55-63（双平台同）+ LiveConfig.java:24-27 + LiveSession.java:252 | HTTP 端口固定 47823、无 port=0 回退；bind 失败只留笼统日志，"会话已建、仅 HTTP 未起"不可区分；双开实例时第二个实例 AI 接管整体静默失效。 | 核对端口定义/范围、startHttp 失败路径、日志文案、endpoint.json 写入时序；确认是否真的整体失效。 |
| M4 | 中 | PeripheralQuery.java:43-47; Terminals.java:21-24 | 反射绑定 CC:T 三个私有字段（peripheralWrappers/RemotePeripheralWrapper.peripheral/ServerComputer.terminal），版本漂移即 ExceptionInInitializerError 级联；靠 mods.toml 精确 pin 缓解。 | 核对反射字段名、初始化时机（static?）、异常处理；核对 mods.toml 的 CC:T 版本 pin。 |
| M5 | 中 | forge/neoforge 无 test 源集；common 仅 3 测试类 | 平台层 0 测试；HTTP 鉴权/Host/413、trace 轮转、JsonValues、Ring、Screens.replay 无测试；handoff.md:407-409 自认缺口。 | 核对测试源集与测试类清单；统计 @Test 数；核对 handoff 引用。 |
| L1 | 低 | LiveHttp.java:735 | Host 校验放行 host==null（纵深缺口，浏览器不可利用）。 | 核对 hostAllowed 逻辑与绑定地址；确认是否已修。 |
| L2 | 低 | LiveSession.java:268-279 | token.txt 未设 POSIX 0600；Linux 多用户机可读=完全操控 HTTP 面。 | 核对 Files.writeString 调用与权限处理；确认平台差异。 |
| L3 | 低 | LiveHttp.java:80,748-756 | 2 线程执行器 + body 无读取超时，持令牌慢速 POST 可占满线程池。 | 核对 executor 与 body 读取、鉴权顺序、backlog。 |
| L4 | 低 | LiveHttp.java:365-368,553-602; Screens.java:21-36 | trace/回放端点每次全文件读取并逐行 JSON 解析，长会话延迟随文件增大。 | 核对实现与上限（默认 64MB）；评估是否确认。 |
| L5 | 低 | LiveHttp.java:253,452,759; ComputerSnapshots.java:230-239 | 客户端错误多落 500 且回显 e.getMessage()；一个坏 manifest 拖垮整个 snapshot list。 | 核对错误分类与 list 循环异常处理。 |
| L6 | 低 | JsonValues.java:48-52,117-118; ComputerTrace.java:108,133 | 截断按字符、预算按字节；>2^53 整数丢精度；recordAt 双重序列化。 | 核对代码与文档口径是否冲突（LiveConfig 注释 "Characters kept"）。 |
| L7 | 低 | LiveSession.java:35,46,284-290 | 会话目录秒级碰撞；崩溃时 endpoint.json 残留。 | 核对目录命名、close 清理时序。 |
| D1 | 疑点（中低） | PeripheralQuery.java:37,107 | READ_ONLY 外设方法白名单是名称启发式，第三方 mod 的 getAndConsumeXxx 型方法可绕过"查询不改世界"承诺。 | 核对正则与调用路径；判断是否可确认（无实例则维持疑点）。 |
| D2 | 疑点（低） | ComputerPaths.java:19-28 | Windows 保留设备名（CON/NUL）不在路径校验范围。 | 核对 sanitise 与后续 mount 兜底；判断是否构成新增权限。 |
| P1-P6 | 正面 | LiveHttp.java:79,738-741,733-735,812-819,748-756; ComputerPaths/SnapshotManifest; 线程模型; cctlive.lua; 元数据 | 审查报告的 6 条正面确认（HTTP 安全主路径、路径穿越四层闭环、线程模型、会话生命周期、autorun 注入、双平台元数据）。 | 抽查每条的代码证据是否成立；指出夸大/不准确处。 |

## 额外要求
1. 对 M2 给出 `diff` 或相似度证据（可用 PowerShell Compare-Object 或 fc），统计两份文件行数与差异行数。
2. 行号不符给实际行号；最后给"偏差/修正"小节（级别升降、描述不准确）。
