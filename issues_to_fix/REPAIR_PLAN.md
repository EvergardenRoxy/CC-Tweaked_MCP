# 自主修复执行计划

日期：2026-10-19。当前代码基线：`851f25d`（分支 `work`，版本 0.2.3）；原问题核对基线：`bf68ac4`。

本计划覆盖 `engine.md`、`cli.md`、`mod.md`、`build-ci.md`、`docs-project.md` 的全部 95 个 ID。它是执行安排，不是新的 issue 清单。最初交付限于计划与开工检查；后续用户已授权 A/T 全部自主修复、验证后推送 `1019fix`。本轮完成范围与证据见文末执行记录，实际状态仍以模块 issue 文件为准。

## 范围与约束

- 遵循 `handoff.md` 最新决策及审查更正 D60–D63。保留每次 run 一个 JVM、本地 stdio MCP、Java 8 启动器、Java 17 engine/cli 与共享 mod 源码、Java 21 NeoForge 目标。
- 不升级或 fork CC:T/Cobalt 来绕过问题，不增加远程 MCP。保留既有 fixture、工具参数及退出码；确需改变公开行为的项目列入 D 类。
- 所有宿主 mount 路径继续经过 `ComputerPaths` 校验；测试按 D53 避免在普通 JUnit 初始化 Minecraft 类。
- 现有 trace 逐条 flush 是 D55 的崩溃可读性保证；不为性能取消。保持未接管且未开启 recording 时的低开销路径。
- 现有历史证据保留在 `issues_to_fix/{verification,evidence}`，不移动、不覆盖、不把本轮执行结果混写成旧轮次证据。
- 新增或改变模拟行为时更新 `handoff.md` 的 `DIVERGENCES`，给出上游依据；模拟测试通过不能写成游戏实测通过。
- 先完成本地修改、验证和可审查差异。此计划不执行发布、推 tag、修改线上设置、清理玩家数据或修改历史提交。

## 分类结论

| 类别 | 数量 | 执行规则 |
|---|---:|---|
| A：可直接自主推进 | 38 | 已有明确局部修法，含缺陷、构建和必要文档工作；按批次实现及验证 |
| T：技术验证后可自主推进 | 25 | 先验证上游接口、故障场景或测试隔离方式；成立后实现，不把验证失败算作修复 |
| D：兼容性或策略待定 | 11 | 保留现状，先整理具体方案与影响；不阻塞 A/T |
| K：保持行为或后续工程整理 | 13 | 不是当前必须修复的功能 bug，不为清零而重构或改变已有决策 |
| O：仓库运营或治理 | 7 | 不混入代码修复；可起草材料，外部操作另按实际任务范围处理 |
| S：已有修复待同步 | 1 | 核对已有提交和实际完成范围，避免重复实现 |

A/T 共 63 条是可自主规划的工作范围，部分条目是配套验证或文档任务，不等于 63 个独立 bug。一个 issue 可能包含多个子问题；完成一部分时保留 `status: open` 并补充完成范围。

当前已确认的状态偏差：

- `PROJ-02`：`dc57617` 已加入 `SECURITY.md`，并由 PR #15 合入 `851f25d`。本地“缺文件”已解决；私密漏洞报告按钮是否在 GitHub 启用尚未确认，不能据此宣称渠道已实测。
- `DOC-01`：`docs/test-results.md` 已更新至 81 个逻辑测试、双 mod 变体共 90 次执行（handoff V39）。仍缺结果单源与当前文档引用机制，不重做旧的 54→81 修订。
- 不重开已完成的路径过滤、trace 半行基础容错、UTF-8 trace 配额、body 413、`ticks=-` 400 等旧问题；本计划只处理相应条目列出的剩余缺口。

## 执行顺序与批次

每个批次是一组可独立审查的改动；复杂批次可拆成多次提交。顺序为：

1. B0 建立可运行基线；A1 先堵输入崩溃；立即进行 T1，优先处理两条 high 的可信度问题。
2. A2 → A3 → T6：完善输入、取消和失败现场。T6 依赖 A2 的唯一运行目录与 A3 的取消生命周期。
3. A4 → T2 → T3 → T4：mod 安全边界、trace 与快照可靠性。A4 的局部修复可先交付。
4. A5 → T5 → A6：构建及发布门禁、测试证据和文档收尾。A5 可在前面批次遇到技术阻塞时提前推进；发布前必须完成版本校验。

### B0：开工基线

- 记录 HEAD、JDK/Gradle 版本、执行命令与实际测试结果，先确认已有测试可运行。
- 配置任务专用的 Gradle 缓存与完整 JDK，不改仓库仓储地址、不提交环境代理配置。
- 基线命令：`:engine:test :cli:test :cli:installDist`；随后按改动范围验证两个 mod 变体。全量基线结果只以实际 XML 报告为准，不沿用历史 81/90 作为本轮通过数。
- 原始深嵌套或 OOM 复现放在有内存、时间上限的子 JVM 内，防止杀死测试控制进程。

### A1：输入崩溃与协议完整性（4 条）

IDs：`CLI-02`、`CLI-03`、`CLI-13`、`ENG-FX-02`。

- JSON-RPC `id` 只接受字符串、数字或 null；数组、对象及布尔值按无效请求处理，错误响应不得回显深嵌套 id。
- 在读行阶段限制输入占用，超限后有界丢弃至换行，再处理下一条请求；不先 `readLine()` 分配完整巨行后才检查。
- 初始工程上限建议为 MCP 单行 16 MiB、输入 JSON 容器深度 128、单个输入文档节点数 100,000；集中定义并写明单位。先检查现有示例与历史 8 MiB 探针的兼容性，必要调整并记录依据。输入深度上限不复用 trace 展示截断的 `MAX_DEPTH=4`。
- fixture 在首次递归转换、`deepCopy`、`noteWideText` 之前用迭代遍历校验；覆盖 event args、scripted 返回值、sequence 和 timeline method 值。`toLua` 对库直接调用也有保护。
- 不照抄“捕获所有 Throwable 后继续运行”：预防深度崩溃优先；仅在安全边界处理可恢复错误。OOM/致命 VM 故障可能无法分配响应，MCP 退出时也不得向协议流输出 run summary。

验收：20,000 层 id 被拒后，同一 MCP 进程仍能 ping/list/call；20,000 层 fixture 得带 JSON 路径的输入错误而非 SOE；合法边界输入能运行；CLI stdout 与 MCP stdout 各守自身格式。解析失败发生在运行前时沿用 exit 65，不强造成功启动的 summary。

### T1：运行结果可信度与文件配额（4 条，优先技术验证）

IDs：`ENG-RT-01`、`ENG-RT-02`、`ENG-RT-05`、`ENG-RT-15`。

- 先读取已固定版本的 `CobaltLuaMachine`、`MachineResult` 与异常转换链；验证真正的 `OutOfMemoryError` 在哪里仍可按类型观察。当前 `RecordingMachine` 只取得 `result.getMessage()`，不能假定换成读取 MachineResult 就实现类型归因。
- 比较可行的本地集成点：Java 侧故障记录、上游可用扩展点、harness 可可靠携带的内部结果。若原始异常已丢失且无受支持入口，在“不 fork 上游”的约束下记录阻塞和剩余风险，不以另一个字符串匹配冒充修复，也不假定堆使用量能证明 OOM。
- harness 提前捕获收尾所需函数及模块引用，包括 `printError`、`tostring`、`debug.traceback` 和异常报告依赖；加程序文件打开失败诊断。Java 侧完成状态一次性提交，验证重复报告；token 仅能作为加固，不能宣称可抵抗完整 debug 权限。
- 根 mount 实施容量检查：增长、append、seek 后扩展、truncate、delete、rename、打开句柄及预置文件均计入；明确磁盘实际占用和累计写入量的区别，沿用上游容量语义并记录 harness/程序的占用。
- `MachineResult` 的非正常失败进入 Java 结果通道，同时保留 shutdown/reboot 及 Too long without yielding 的已有含义。

验收：伪造 OOM 文案仍是普通 Lua error；篡改收尾 globals 不变成 timeout；受控子 JVM 的真实 OOM 仍有正确结果；超配额得到预期 fs 错误且删文件可释放空间。上述结果分类全部成立前，不将两条 high 标成 fixed。

### A2：engine 边界与查询可靠性（8 条）

IDs：`ENG-RT-03`、`ENG-RT-07`、`ENG-RT-10`、`ENG-RT-11`、`ENG-RT-16`、`ENG-FX-01`、`ENG-FX-03`、`ENG-FX-04`。

- 用附加终止原因标明 interrupt，保持已有 status/exit code 集合，不直接引入新退出码。
- `RunSpec` 对齐 CLI 的 timeout 上限；程序名先验证非空、末段及 `.`/`..`，以实际 Path 语义核对，不无依据扩大 Windows 保留名限制。
- trace 统一校验记录必需字段与类型；查询和屏幕回放均容忍合法 JSON 的缺字段/坏字段，明确 skipped 的反馈位置。
- run 目录扩大随机空间并用 `createDirectory` 原子占位、冲突重试；不以概率更低替代冲突检测。
- fixture 抽取同义默认值；整数用精确解析和边界验证，覆盖 Long.MAX_VALUE 及相邻值，不用 double 比较边界冒充精确校验。
- timeline 按 tick 稳定建索引，保留同 tick 的原顺序和全部动作；超出本次 timeout 的动作给出有界提示，不作为重复动作删除。

验收：中断原因可区分；超大库输入清晰拒绝；缺字段 trace 后的有效行仍可查询；确定性制造 run-id 冲突无覆盖；`1e999`/Long 越界被拒；合法整数前后一致；同 tick 顺序不变、不会漏执行。

### A3：MCP 取消和诊断（5 条）

IDs：`CLI-04`、`CLI-10`、`CLI-14`、`CLI-15`、`CLI-17`。

- `Call` 绑定可取消的任务，覆盖取消先于 submit、任务开始、子进程 attach 的竞态；取消 live wait/HTTP 时停止轮询和后续步骤，释放 active 条目。
- submit 被拒后清理 active，用服务器内部错误响应；验证并发请求 id 不被错误移除。
- `ToolError` 增加错误类别以区分未响应、HTTP 错误和取消，文案变更不能改变 wait 分类。
- 强杀后有界等待退出，采集 stderr 尾部、过滤 inline 临时路径后返回，再清理文件；不把 token 或无限错误日志带回结果。
- 屏幕基线采集失败应显式标记不可用并在 wait 中说明；不能把缺基线视为已经发生变化。无条件保留更旧基线同样不能证明本次动作后的变化。

验收：取消 live wait 后请求计数停止，其他请求仍成功；取消子 JVM 无残留；拒绝提交不泄漏 active；超时结果有有界现场；基线失败不会立即接受旧屏幕文字。

### T6：运行目录状态与异常终止（1 条）

ID：`ENG-RT-17`。依赖 A2、A3。

- 建立可靠的父子进程 run-id/目录关联；通过内部启动约定定位实际目录，不扫描 shared latest 猜测被杀的是哪次 run。
- 运行初始原子写入 `state.json`，正常结束与 summary 对应更新；MCP 主动取消或墙钟超时在确认子进程退出后补充原因。
- 外部 SIGKILL/断电无法保证终态落盘：残留 running 只能表示最后已知状态；结合 pid/启动标识报告 stale/unknown，不能承诺所有强杀都有 summary。

验收：两次并发 run 只更新自己的状态；正常、取消、父级超时均可区分；异常标记写失败不会伪造成功；已有 summary 读取兼容。

### A4：mod 局部安全与数据保护（5 条）

IDs：`MOD-06`、`MOD-07`、`MOD-10`、`MOD-12`、`MOD-24`。

- 缺 Host 的请求拒绝，保留 loopback 白名单与 token 校验；抽出可独立测试的校验层。
- POSIX token 创建时即设 owner-only 权限，读取已有 token 时修正过宽权限；Windows 使用受支持的现有权限模型，不盲调 POSIX API。
- snapshot list 对损坏条目单独报告 skipped/原因，其余有效快照继续列出；内部错误与客户端输入错误区分，日志保留排障细节。
- 会话目录使用不碰撞的预定名字，并在首次写入时排他创建；不为了分配目录提前开启 dormant tracing。启动失败不继续提供失效 endpoint，不能凭跨平台不可靠的 pid 存活判断删除其他实例的 endpoint。
- 外设重名时明确报歧义，不能静默选错对象；保持无冲突名称的既有调用形式。

验收：Host/鉴权边界、POSIX 权限、损坏快照隔离、同秒重开目录隔离、外设重名错误均有针对性验证；mod 接入变更在两个变体构建，并列出实机剩余项。

### T2：mod trace 窗口与并发（7 条）

IDs：`MOD-01`、`MOD-09`、`MOD-15`、`MOD-16`、`MOD-17`、`MOD-21`、`MOD-23`。

- 先剥离时钟、日志和文件 I/O 依赖以支持不初始化 Minecraft 的测试，不复制生产实现另测一份。
- kinds 随保留段裁剪，使用 long baseSeq；`after < first_seq` 公开缺口，counts/match 不再返回已丢弃记录。按保留窗口查询，普通 trace limit 命中后及时停止扫描。
- 读段与轮换采用有明确生命周期的读取快照/句柄方案；不能把整个大文件复制进堆，也不能在 Windows 上持句柄后假定 move 一定成功。
- 写失败后显式记录健康状态并按可控策略恢复；序号、索引、bytes 只对实际完成的写入提交。避免每 tick 无限重试刷日志、假计数或永久无声停写。
- recordAt 的半行/坏字段容错与 query/replay 一致；seq/tick 路径端到端保留 long，覆盖 HTTP 和 MCP 参数，而非只去掉 summary 的强转。
- screen_hash 使用更稳健的内容摘要，继续以字符串呈现；测试包括前景色、背景色、尺寸和文字变化。
- attach 历史转实时的发布与 recorder 写入共用明确同步协议。仅将 drain 移进 trace 锁不能覆盖已经选中 ring 路径的写线程；用闩锁测试这一竞态，保持 dormant 快速路径。
- call id 在同一 session 内跨 reboot 唯一，历史与实时记录沿用一致规则。

验收：多次轮换后内存随保留窗口有界；counts/match/first_seq 一致；并发读无重复段/混段；注入 I/O 故障可见且可恢复；seq 超 int 上限仍准确；attach 竞态无丢失/重复。Linux 自动验证后，Windows 文件占用及 Forge/NeoForge 接入仍须实测，不据此虚报全部覆盖完成。

### T3：快照保存可恢复替换（1 条）

ID：`MOD-19`。

- 同目录构建临时快照，按实际复制内容生成并校验 manifest/hash，再替换目标；同名保存串行化。
- 目录替换在目标已存在或文件系统不支持 `ATOMIC_MOVE` 时不能保证单步原子。采用保留旧快照的可恢复切换，明确备份/临时目录恢复规则，失败后不删除唯一完整副本。
- list/diff/restore 不将半成品目录当作完整快照；沿用 manifest 路径校验。验证中途源文件变化，避免 manifest 记扫描时旧 hash、文件却是复制时新内容。

验收：复制中断、manifest 写失败、每个 rename 失败点均不丢旧快照；重试/恢复可用；正常 save/diff/restore 成立。文件事务用纯路径测试，mount 接入另做两个版本的游戏验证。

### T4：mod 适配与覆盖缺口（5 条）

IDs：`MOD-04`、`MOD-05`、`MOD-08`、`MOD-11`、`MOD-22`。

- 反射适配集中并启动自检；某项失败只停用依赖它的能力并明确解释，不能让静态初始化打断 server tick；继续精确 pin CC:T。
- HTTP/trace 测试随 A4/T2/T3 接入，覆盖 auth、Host、413、Ring、JsonValues、回放和 lease；未覆盖项继续列明，不能“新增一个测试类”就关闭 MOD-05。
- 先验证 JDK HttpServer 对慢请求可用的中止机制，用带 token 的慢速客户端实测；线程池加大并不是读取超时修复。不设置影响 Minecraft 内其他 HTTP 服务的全局 JVM 属性。
- 先核对 `TracingMethods` 中 String/ByteBuffer/byte[] 的真实含义：Lua 字符串可能是 ISO-8859-1 承载的原始字节，不能机械改为 UTF-8 长度。明确 `bytes`、字符截断和大整数展示策略；性能优化另测，保留现有 trace 消费兼容性。
- 复现真实区块 unload/reload 与电脑破坏的区别；先修正无法判断时的说明，不自行增加自动重接管行为或延长控制租约。

验收：故意不匹配的反射字段能安全降级；慢 POST 被回收且其他请求恢复；多种编码的 bytes 符合实际数据；两个加载器的 unload/reload 均有可追踪结果。测试无法替代第三方外设的真实副作用验证。

### A5：版本和构建的确定性（6 条）

IDs：`BLD-02`、`BLD-05`、`BLD-07`、`BLD-08`、`BLD-09`、`BLD-14`。

- 采用 handoff D60 允许的最小方案：Gradle 从 `Versions.java` 读取唯一 TOOL 版本，校验唯一匹配；测试比较构建/运行版本，不再硬编码 0.2.3。
- release 打包前校验 tag 与构建/运行版本；不为验证门禁推真实 tag，也不在本计划指定下个公开版本号。
- gson/junit 与 CC:T 坐标引用 catalog；mod 测试库版本与游戏实际依赖需分别说明，不把 testImplementation 对齐说成升级游戏 Gson。
- `.gitattributes` 对脚本、toml、mcmeta 等文本明确 LF，bat 保留 CRLF；按文本处理 mcmeta，不用 `-text` 声称解决所有来源的行尾漂移。只规范必要文件，避免全仓无关重写。
- smoke 报告忽略规则与明确输出位置；CI push 收敛 main/tags、PR 面向 main；README badge 指向 main。

验收：匹配版本允许打包，不匹配明确失败；两个 mod 变体测试依赖一致；发布包脚本 LF/执行权限正确；干净工作树不会因 smoke 报告混入源码。行尾修复只证明相关内容稳定，不宣称 jar 已完全可复现。

### T5：CI、供应链和发布物验证（7 条）

IDs：`BLD-01`、`BLD-03`、`BLD-04`、`BLD-10`、`BLD-11`、`BLD-15`、`CLI-12`。

- CI 复用已测试的 CLI zip 与两个 mod jar；smoke 验证实际待发布 zip 的解压内容，release 下载同一 run 的产物，用确切文件名、数量和 SHA256 清单检查。
- 为 wrapper 设置由可信发行源核实的 SHA256；actions 固定经核实的 commit SHA，接入 wrapper 校验、Dependabot、依赖审计及适合此 Gradle 工程的 CodeQL。依赖校验元数据需核对来源，不把首次下载自动信任所有校验和当成审计。
- 生成 SHA256SUMS；密码签名/身份配置仅准备方案，不凭空添加密钥。BLD-11 的签名建议不是本地修复的强制前提。
- release 重跑保持已发布资产不可悄然替换：同名同 hash 可跳过，不同 hash 明确失败；不直接 `--clobber` 覆盖公开发行物。
- 验证 ModDevGradle 实际工具链要求和 cobalt 依赖解析；只有两版完整编译均通过才删除“未用”依赖，不能仅依据源码没有 import 就删除编译所需传递依赖。
- CLI-12 随 A1/A3 补协议和并发回归；另补 Launcher 子进程、500 端点。endpoint 伪造测试可先验证本地文件解析行为，CLI-01 的策略变更仍待 D 类结论。
- A3 若实现中断，不改变默认 EOF 语义：现有 smoke 将多请求一次性 pipe 后立即 EOF，仍依赖完成在途调用；CI 接入先保留此约定。

验收：本地打包/smoke/配置校验通过；CI PR 验证后才标为实际 CI 通过；tag 发布流程本地检查不得冒充 GitHub Release 已演练。无 GitHub 权限或外部配置时明确剩余项，不阻塞其他本地修复。

### A6：文档与证据收尾（10 条）

IDs：`DOC-01`、`DOC-02`、`DOC-04`、`DOC-05`、`ENG-FX-06`、`ENG-FX-08`、`ENG-FX-09`、`ENG-RT-04`、`ENG-RT-06`、`CLI-06`。

- 从实际 JUnit XML 生成一份结果摘要，带 commit、环境、executions/failures/errors/skipped；区分共享 mod 用例与两个变体的执行次数。README/handoff 引用该出处，历史测试记录不改写成当前结果。
- 增加中文用户向 `docs/limitations.md`，README 指向它；内部 handoff 保留 D/V/N 与历史，不搬走审查证据。安装步骤就地注明 macOS 未实测。
- 文档明确 trace 数值有界化、IArguments 小数截断、Inventory/ItemStack 主线程约束；小数参数只补能验证上游语义的针对性回归。
- 对 ENG-RT-04/06 只明确每 JVM 单 run、close 与 CoreConfig 副作用；不支持同 JVM 并发嵌入，不为了这两条改变 D1。
- CLI-06 先补无 id tools/call 的 stderr 诊断与行为说明；未知工具错误码及已声明协议版本不在此批次顺手改变。

验收：链接有效、agent guide 保持英文 ASCII、PS1 保持 ASCII；当前结果只引用本轮实测，文档不把未覆盖平台写为已通过。公开 limitations 与新增模拟差异一致。

## D 类：需先形成兼容性/策略结论

| ID | 原因与准备工作 |
|---|---|
| CLI-01 | handoff N14 明确延期到客户端/服务端互联方案确定。可准备 URI scheme/authority/userinfo/port/redirect 测试矩阵；不立即收紧到 loopback。后续按 D37/D39 的现行本地模型与新的互联决定明确最终范围 |
| CLI-05 | 改“选最新 Java”或搜索优先级会触碰 D23/D27；先核对候选来源与现有 Windows 行为，不执行不可信候选来探测版本 |
| CLI-07 | 移除旧协议版本或新增 batch 都影响客户端；先核对对应 MCP 规范的要求，不自行减少支持范围 |
| CLI-08 | handoff 明定 EOF 等待完成，smoke 也依赖；不能把 EOF 等同 cancellation。先设计兼容的显式退出/有界 drain 行为 |
| CLI-11 | 公共 Args 去重可做，但把数值 primitive 转 string 的现有行为收紧可能破坏客户端；先记录兼容矩阵 |
| CLI-16 | pending TTL 会影响稍后 collect 和临时脚本保留语义；先定义过期提示及是否删文件，不擅自用 10 分钟销毁结果 |
| MOD-03 | 自动换端口/port=0 是新增运维行为；可先明确 bind 失败诊断，再决定回退策略 |
| MOD-13 | getter 名称不能证明无副作用；需要真实第三方方法验证和能力边界方案，不能靠扩正则/黑名单宣布只读安全 |
| MOD-14 | 已开始 server task 无法靠 cancel(false) 回滚；pending/回执/幂等键涉及 API 语义。不得改 cancel(true) 中断 Minecraft 主线程 |
| MOD-18 | 自动删除 body/session 属玩家记录保留策略；先设计配额、保留周期和显式清理方式，不自动删除历史 |
| MOD-20 | UUID 会影响工具寻址和磁盘布局；先验证同 ID 在 CC:T 中本就共享的存储语义，再决定消歧与迁移。不能直接迁移/拆分既有快照目录 |

这些项先保留原 status；不以本计划分类为由改成 wontfix。

## K/O/S 类处理

- K 类：ENG-RT-09 的逐条 flush、ENG-FX-08 的小数截断是现有语义；前者保持，后者在 A6 补说明。ENG-RT-14 的内存 mount 随 JVM 消失，“强杀后清理下次 run”不是成立的修复路径。ENG-FX-05 的本地诊断、CLI-09 的端口展示、BLD-13 的平台差异不因审查建议而删改。其余索引到附录，不混入缺陷批次。
- O 类：PROJ-01/03/04/05/06/07/08 属元数据、治理文件、协作习惯、发行命名、渠道与身份。可另外交付 CONTRIBUTING/模板草稿，不能擅自承诺维护者义务、发布渠道、改邮箱或清理远程历史；不把 bus factor 或分支数量作为代码 bug 修复。
- S 类：PROJ-02 核对现有 SECURITY.md 与 `dc57617`；后续同步 issue/INDEX 的完成范围，GitHub 私密报告开关待真实检查。不重复创建文件。

## 验证与完成定义

常用命令（在仓库根执行，环境选用完整 JDK 21）：

```sh
./gradlew --no-daemon --console=plain :engine:test :cli:test :cli:installDist
./gradlew --no-daemon --console=plain :mod-forge:test :mod-neoforge:test
./gradlew --no-daemon --console=plain :mod-forge:build :mod-neoforge:build
./gradlew --no-daemon --console=plain :cli:distZip assembleMods
bash scripts/smoke-test.sh cli/build/install/cct-run/bin/cct-run
```

- 先跑受影响模块和真实失败路径；变更共享 mod 后两变体测试/构建。纯文档、忽略规则等低影响变更不新增镜像实现的测试。
- 协议、取消、trace 故障、配额、快照替换使用行为回归或故障注入；时间敏感用闩锁/可控时钟，不靠长 sleep。OOM 使用小堆独立进程。
- Windows 路径/权限/文件轮换、Java 8 重启、真实第三方外设、游戏生命周期分开列验证；Linux 无法证明的部分保留限制。
- 每批补证据、更新 handoff 相应 D/V/N（只给实际验证分配 V），同步对应 issue 字段和 INDEX，维护统计。技术门槛未过或只修子项时仍 open。
- 完成后按清单 schema 记录 `status: fixed`、真实 `fixed_in` 和 `fixed_at`；存在提交前不要编造 commit 或用工作树状态冒充固定提交。提交后用后续记账提交更新对应修复 SHA。
- 默认不改原 issue ID、不删问题、不新建重复条目、不覆写旧核对结论。需要推翻旧结论时附新的代码/实测证据。

## 本次开工检查

- HEAD：`851f25d`；开工前工作树干净。已读全部 95 条正文、legacy 映射、核对表偏差和动态探针说明；对上述关键修复入口作静态核对，未逐条重新动态复现。
- 系统默认 Java 21 只有运行时能力；完整编译器已确认在 `/workspace/.tools/jdk-21.0.12.1+1`（`javac 21.0.12.1`）。任务缓存使用 `/workspace/.cache/gradle`，避免往只读用户目录写锁文件。
- 本环境默认命令沙箱禁止 Gradle 的本机锁通信，允许所需网络权限后 Gradle 可启动。离线基线因未缓存 `cc.tweaked:cc-tweaked-1.20.1-core:1.120.2` 失败，不计为测试失败或通过。
- 已使用环境代理与系统 Java CA trust store 完成在线依赖获取，TLS 校验保持开启。完整 JDK 自带 trust store 最初不信任代理证书，指定系统 trust store 后解决；无仓库配置修改。
- 基线通过：`:engine:test :cli:test :cli:installDist`，`BUILD SUCCESSFUL in 1m 4s`，13 个任务全部实际执行。JUnit XML：engine 42、cli 30，共 72 次测试，failures/errors/skipped 均为 0。报告位于各模块 `build/test-results/test/`。
- 两个 mod 变体、smoke、Windows/macOS/Java 8、Minecraft 实机本轮未重跑；首批 A1 的 engine/cli 基线已就绪，mod 改动前补相应基线。
- 计划检查通过：95 个原 ID 恰好分派一次，分类计数与批次引用一致，计划内本地 Markdown 链接存在，`git diff --check` 通过。

本环境复用命令（代理与 trust store 仅作用于本次 Gradle，不写入项目）：

```sh
GRADLE_USER_HOME=/workspace/.cache/gradle \
JAVA_HOME=/workspace/.tools/jdk-21.0.12.1+1 \
./gradlew --no-daemon --console=plain \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 \
  -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 \
  :engine:test :cli:test :cli:installDist
```

## 全量 ID 分派

下表每个 ID 恰好出现一次；原条目是证据与状态的正式来源。A/T 表示可规划的范围，不表示已经获得完整运行验证。

<!-- issue-assignment-table -->

| ID | 分类/批次 | 原问题摘要 | 正文 |
|---|---|---|---|
| ENG-RT-01 | T1 | fs 写入不受容量限制，可写爆堆；OOM 归类不可区分 | [engine.md](engine.md) |
| ENG-RT-02 | T1 | debug 工具箱完整可用；受测程序可篡改 `_G` 影响 harness 收尾与状态归因 | [engine.md](engine.md) |
| ENG-RT-03 | A2 | 环境中断被报成 timeout | [engine.md](engine.md) |
| ENG-RT-04 | A6 | context.close 不等线程退出（库嵌入泄漏） | [engine.md](engine.md) |
| ENG-RT-05 | T1 | harness `fs.open` 无 nil 检查 | [engine.md](engine.md) |
| ENG-RT-06 | A6 | CoreConfig 进程级静态跨 run 残留 | [engine.md](engine.md) |
| ENG-RT-07 | A2 | maxTicks 的 int 溢出（库调用面） | [engine.md](engine.md) |
| ENG-RT-08 | K | Trace close 后静默丢记录 | [engine.md](engine.md) |
| ENG-RT-09 | K | 每条记录 flush（有意权衡） | [engine.md](engine.md) |
| ENG-RT-10 | A2 | TraceQuery 缺字段 NPE 且不计 skipped | [engine.md](engine.md) |
| ENG-RT-11 | A2 | storedName 对畸形 programName 不设防 | [engine.md](engine.md) |
| ENG-RT-12 | K | Clock tick/ms 亚毫秒错位 | [engine.md](engine.md) |
| ENG-RT-13 | K | run()/loop() 巨型方法与魔法数 | [engine.md](engine.md) |
| ENG-RT-14 | K | RootMount 两步改名非原子 | [engine.md](engine.md) |
| ENG-RT-15 | T1 | 保护链外 OOM/VM error 不进入状态判定 | [engine.md](engine.md) |
| ENG-RT-16 | A2 | run_id 随机段仅 4 位十六进制 | [engine.md](engine.md) |
| ENG-RT-17 | T6 | 被杀/超时运行无 summary、无 killed 标记 | [engine.md](engine.md) |
| ENG-FX-01 | A2 | 默认值在 validate/build 两阶段重复、靠异常字符串耦合 | [engine.md](engine.md) |
| ENG-FX-02 | A1 | timeline args 深嵌套可致 StackOverflowError（无 summary） | [engine.md](engine.md) |
| ENG-FX-03 | A2 | longValue 只查下限，Infinity/超范围静默饱和 | [engine.md](engine.md) |
| ENG-FX-04 | A2 | timeline 不排序、每 tick 全表扫描、超 timeout 动作静默丢弃 | [engine.md](engine.md) |
| ENG-FX-05 | K | 外设 RuntimeException 细节写入对客户端可见的 trace | [engine.md](engine.md) |
| ENG-FX-06 | A6 | trace 数值类型有界化未文档化 | [engine.md](engine.md) |
| ENG-FX-07 | K | ItemStack 详情用 HashMap，trace 键序非插入序 | [engine.md](engine.md) |
| ENG-FX-08 | A6 | getInt 对小数静默截断（与游戏一致，缺测试） | [engine.md](engine.md) |
| ENG-FX-09 | A6 | ItemStack.count 线程约束未文档化（当前无竞争） | [engine.md](engine.md) |
| CLI-01 | D | endpoint.json 的 URL 未校验即发送 Bearer 令牌 | [cli.md](cli.md) |
| CLI-02 | A1 | stdin 无行长上限、serve() 不捕获 Error | [cli.md](cli.md) |
| CLI-03 | A1 | 深嵌套 JSON-RPC id 在 send() 序列化时 SOE 打死 MCP 服务 | [cli.md](cli.md) |
| CLI-04 | A3 | cancelled 只杀子 JVM，live 工具无人中断 | [cli.md](cli.md) |
| CLI-05 | D | JavaFinder 信任 release 文件并取最高版本 | [cli.md](cli.md) |
| CLI-06 | A6 | JSON-RPC 小偏差（无 id 的 tools/call 静默丢弃等） | [cli.md](cli.md) |
| CLI-07 | D | 声明支持 2025-03-26 但拒绝批量 | [cli.md](cli.md) |
| CLI-08 | D | EOF 后等待 2 小时且不取消在途调用 | [cli.md](cli.md) |
| CLI-09 | K | 错误消息回传 endpoint URL（非令牌） | [cli.md](cli.md) |
| CLI-10 | A3 | wait 超时分类依赖错误文案子串 | [cli.md](cli.md) |
| CLI-11 | D | 参数校验双实现且严格度不一致 | [cli.md](cli.md) |
| CLI-12 | T5 | 测试盲区 | [cli.md](cli.md) |
| CLI-13 | A1 | OOM 逃逸时把 summary JSON 写进 MCP 协议流 | [cli.md](cli.md) |
| CLI-14 | A3 | pool.submit 失败时 active 条目泄漏 | [cli.md](cli.md) |
| CLI-15 | A3 | 强杀后临时 stderr 被删除，超时结果缺现场 | [cli.md](cli.md) |
| CLI-16 | D | pendingLua 无 TTL | [cli.md](cli.md) |
| CLI-17 | A3 | rememberScreens 失败时清空全部基线且无提示 | [cli.md](cli.md) |
| MOD-01 | T2 | ComputerTrace.kinds 内存索引无界增长 | [mod.md](mod.md) |
| MOD-02 | K | 平台层 CctLive/LiveConfig 与两份构建脚本高度重复 | [mod.md](mod.md) |
| MOD-03 | D | HTTP 端口固定无回退，bind 失败文案与会话状态矛盾 | [mod.md](mod.md) |
| MOD-04 | T4 | 反射绑定 CC:T 私有字段，无适配层 | [mod.md](mod.md) |
| MOD-05 | T4 | HTTP/trace 层无自动化测试（平台层测试已接入） | [mod.md](mod.md) |
| MOD-06 | A4 | Host 校验放行 host==null | [mod.md](mod.md) |
| MOD-07 | A4 | token.txt 未设 POSIX 0600 | [mod.md](mod.md) |
| MOD-08 | T4 | 2 线程执行器 + body 无读取超时 | [mod.md](mod.md) |
| MOD-09 | T2 | trace/回放端点全文件读取 | [mod.md](mod.md) |
| MOD-10 | A4 | 错误分类混乱、坏 manifest 拖垮 list | [mod.md](mod.md) |
| MOD-11 | T4 | 字符/字节口径与大整数精度、双重序列化 | [mod.md](mod.md) |
| MOD-12 | A4 | 会话目录秒级碰撞 / endpoint.json 残留 | [mod.md](mod.md) |
| MOD-13 | D | READ_ONLY 白名单是名称启发式 | [mod.md](mod.md) |
| MOD-14 | D | onServer 超时后已开始任务仍执行 | [mod.md](mod.md) |
| MOD-15 | T2 | recordAt 缺半行容错，status?kinds= 可 500 | [mod.md](mod.md) |
| MOD-16 | T2 | screen_hash 32 位 + seq/tick 强转 int | [mod.md](mod.md) |
| MOD-17 | T2 | trace 轮换与读取竞争，写失败一次后永久停写 | [mod.md](mod.md) |
| MOD-18 | D | body 文件与 sessions 目录从不清理 | [mod.md](mod.md) |
| MOD-19 | T3 | 快照 save() 非原子 | [mod.md](mod.md) |
| MOD-20 | D | 重复电脑 ID 时 trace/快照目录冲突 | [mod.md](mod.md) |
| MOD-21 | T2 | 记录模式 attach 时 drain 在 trace 锁/发布 attachment 之前 | [mod.md](mod.md) |
| MOD-22 | T4 | 区块卸载被当成电脑移除 | [mod.md](mod.md) |
| MOD-23 | T2 | peripheral call id 每 VM 一个 | [mod.md](mod.md) |
| MOD-24 | A4 | side 名与网络名同 map，重名静默覆盖 | [mod.md](mod.md) |
| BLD-01 | T5 | CI 只跑单元测试，smoke 与供应链检查未接入 | [build-ci.md](build-ci.md) |
| BLD-02 | A5 | 版本号双源，release 不校验 tag | [build-ci.md](build-ci.md) |
| BLD-03 | T5 | release job 重新检出重建 | [build-ci.md](build-ci.md) |
| BLD-04 | T5 | 无安全扫描/依赖审计/wrapper 校验 | [build-ci.md](build-ci.md) |
| BLD-05 | A5 | mod 构建脚本绕过 version catalog 手写 gson 2.10.1 | [build-ci.md](build-ci.md) |
| BLD-06 | K | 两份 mod 构建脚本约八成逐行重复 | [build-ci.md](build-ci.md) |
| BLD-07 | A5 | .gitattributes 缺 text=auto，行尾导致产物哈希不可复现 | [build-ci.md](build-ci.md) |
| BLD-08 | A5 | smoke 报告落在仓库根目录，.gitignore 未覆盖 | [build-ci.md](build-ci.md) |
| BLD-09 | A5 | push/PR 触发器残留已合并分支 | [build-ci.md](build-ci.md) |
| BLD-10 | T5 | wrapper 无 SHA256、actions 用可变主版本 tag | [build-ci.md](build-ci.md) |
| BLD-11 | T5 | 发布资产无签名/校验和，jar glob 宽松 | [build-ci.md](build-ci.md) |
| BLD-12 | K | THIRD-PARTY-NOTICES 手工维护无 CI 绑定 | [build-ci.md](build-ci.md) |
| BLD-13 | K | smoke 脚本对等性（信息） | [build-ci.md](build-ci.md) |
| BLD-14 | A5 | tag v0.2.3 之后 9 个提交而版本号未变 | [build-ci.md](build-ci.md) |
| BLD-15 | T5 | mod 构建缺 toolchain、cobalt compileOnly 未使用 | [build-ci.md](build-ci.md) |
| DOC-01 | A6 | 测试数字三处各说一套，且过期记录随发布包出厂 | [docs-project.md](docs-project.md) |
| DOC-02 | A6 | 内部过程记录 handoff.md 被对外文档当作 LIMITATIONS 出处 | [docs-project.md](docs-project.md) |
| DOC-03 | K | docs 受众混杂（内部实测记录与用户文档同级） | [docs-project.md](docs-project.md) |
| DOC-04 | A6 | macOS 未测试，但安装节未就地提示 | [docs-project.md](docs-project.md) |
| DOC-05 | A6 | 三处测试记录手工同步（机制） | [docs-project.md](docs-project.md) |
| PROJ-01 | O | 仓库 description/topics/homepage 全空 | [docs-project.md](docs-project.md) |
| PROJ-02 | S | 无 SECURITY.md（已有真实安全修复史） | [docs-project.md](docs-project.md) |
| PROJ-03 | O | 缺社区/治理文件 | [docs-project.md](docs-project.md) |
| PROJ-04 | O | issue 区零使用、PR 同名重跑产物公开 | [docs-project.md](docs-project.md) |
| PROJ-05 | O | Release 命名混杂、0.2.1 跳号无解释 | [docs-project.md](docs-project.md) |
| PROJ-06 | O | 未上架 Modrinth/CurseForge | [docs-project.md](docs-project.md) |
| PROJ-07 | O | 分支/提交身份卫生 | [docs-project.md](docs-project.md) |
| PROJ-08 | O | 单维护者 + AI 主写（bus factor） | [docs-project.md](docs-project.md) |


## 2026-10-19 执行记录（1019fix）

用户后续明确授权：A/T 自主推进并完成，按难易度和关联度修复，验证通过后推送 `1019fix`。因此上文“规划阶段”的限制只描述最初交付，不再限制本轮已授权实施。

A1/A2/A3/T1/T6 先处理输入、可信结果、配额、取消和运行目录状态；A4/T2/T3/T4 随后处理双版本 mod 的追踪/快照/HTTP/能力降级，再完成 A5/T5 构建发布门禁与 A6 文档。技术探针确认：Cobalt 的错误报告器能在 Lua 文案转换前观察原始 Java OOM；Lua 字符串的 dataLength 应保留每字符一字节语义；相同物理外设的多个 wrapper 应允许同名，只有不同目标才报冲突。相关实现与差异已记入 handoff D64–D69、DV10/DV15/DV17/DV18。

完整命令、各 issue 对应测试/实现与提交、机器结果、真实 Linux Forge/NeoForge 开发服务器证据见 [results-1019fix](evidence/dynamic/results-1019fix/README.md)。模块 issue 只在这些门禁完成后追加真实 `fixed_in` / `fixed_at`，保留原证据。此前部分 issue 的纯文本 YAML 含未加引号的反引号/冒号，本轮只将四个字符串字段规范为仓库规定的块字符串，95 个原条目的含义和 ID 保留。

本轮不发布、不推 tag、不合并、不改变 D 类策略。CLI-01 的 URL 范围仍待既有互联决策；默认 EOF drain 保留。Windows/macOS 与真实玩家客户端、生产部署及正式 tag 发布仍未在本轮演练，不能把 Linux 测试写成跨平台实测。
