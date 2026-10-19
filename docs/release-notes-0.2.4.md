# cct-run 0.2.4

0.2.4 是一次稳定性与资源边界修复版，完成审查清单中的 **63 项 A/T 问题**。重点是让模拟运行的结果可信、取消与超时能够收尾，以及让 cct-live 的长会话记录、快照和 HTTP 接口更可靠。建议 **cct-run 与对应游戏版本的 cct-live 一起升级**。

## 下载与要求

| 文件 | 用途与要求 |
|---|---|
| `cct-run-0.2.4.zip` | 命令行工具、本地 stdio MCP 服务器、文档与测试脚本；运行需要 Java 17+ |
| `cct-live-1.20.1-0.2.4.jar` | Minecraft 1.20.1、Forge 47.x、Java 17+、CC: Tweaked **1.120.2** |
| `cct-live-1.21.1-0.2.4.jar` | Minecraft 1.21.1、NeoForge 21.1.x、Java 21+、CC: Tweaked **1.120.2** |
| `verification.json` | 本次发行 CI 的四模块 JUnit 执行结果，含源码提交与验证状态 |
| `SHA256SUMS` | 上述 zip、两个 jar 和验证报告的 SHA-256 校验值 |

两个 mod 共享实现，选择与游戏加载器匹配的一个，不要同时安装。CC: Tweaked 仍固定 1.120.2，Cobalt 仍为 0.9.9，没有升级或修改上游。构建验证使用 Forge 47.4.10 / NeoForge 21.1.262。

## 模拟器与运行结果

- **磁盘配额实际生效**：根盘容量 1,000,000 字节，每个文件/目录至少计 500 字节；程序、harness 和预置文件计入占用。追加、seek 后写入、截断、删除和打开句柄均受检查；summary 区分当前占用与累计写入。超额预置输入以退出码 65 拒绝。
- **内存耗尽按真实异常判断**：在 Cobalt 转换错误文本前观察 Java OOM；即使 Lua 捕获错误，仍标记 `out_of_memory`。普通 Lua 错误含相同字样不会误报。harness 提前保存报告函数，并只接受一次完成信号。
- **输入有明确边界**：fixture 文件与 MCP 单行最多 16 MiB；JSON 容器深度最多 128、节点最多 100,000。过长协议行有界丢弃后可继续处理下一行；JSON-RPC id 只接受字符串、数字或 null。
- **fixture 更准确**：整数精确校验，默认容器/能量容量与运行时一致；同 tick 时间线顺序稳定，事件分派避免重复扫描。
- **运行目录可追踪**：原子创建带 UUID 后缀的独立目录，`state.json` 记录启动、运行与结束状态及 PID 启动身份。中断会先保存可用结果，summary 补充 `termination_reason`。
- **坏 trace 行不再阻断查询**：缺失或类型不符的记录跳过；屏幕查询保留有效行。

## MCP

- 取消同时覆盖排队任务、等待中的实机调用及模拟子进程，处理子进程创建与取消之间的竞争；清理重复活动 id 和被拒绝任务。
- 取消与超时使用明确的错误类型；子进程墙钟超时会保存有界 stderr 尾部，并更新对应运行目录的状态。
- `cct_live_wait` 的初始屏幕不可用时明确提示，避免把旧文字误判为新结果；需接受已有文字时使用显式选项。
- trace 的 seq/tick 查询支持 long 范围。无 id 的 `tools/call` 给出 stderr 诊断；stdio 的 stdout 保持协议输出。

## cct-live

- **trace 轮换与写入恢复**：保留两个按字节限制的段；索引随保留窗口清理，完整写入后才提交序号。写入失败回滚半行，提供 `trace_health`、退避重试和恢复记录；查询通过磁盘快照读取，避免整份日志复制进堆。
- **查询与屏幕回放**：普通分页在一条前瞻后停止；`matched_at_least: true` 表示 `matched` 是下界，精确总数使用 summary 查询。损坏记录不阻断后续颜色回放；screen hash 现在包含尺寸、文字及前景/背景色。
- **接管与记录衔接**：记录模式历史回放和实时写入同步切换，避免交接丢失；调用 id 在同会话内跨电脑重启保持唯一。区块卸载报告不可用，重新加载不会自动恢复 AI 接管。
- **快照替换可恢复**：先复制并校验暂存内容，再通过备份切换；失败可回滚，中断后可恢复。列表跳过损坏快照。
- **HTTP 边界**：要求 Host 与 Bearer 鉴权；每次请求在回复前按总计 5 秒期限消费 body，包括鉴权失败、GET 和未知路由，避免关闭 exchange 时被慢 body 卡住。保留 1 MiB body 上限，超限有界处理。
- **文件与能力降级**：POSIX token 文件收紧为 0600，session 目录按需创建，endpoint 原子更新并只清理本实例文件。可选反射能力不可用时明确降级；同一物理外设的别名允许共存，不同目标同名仍报冲突。

## 升级时需要注意

MCP 配置和已有合法 fixture 的基本用法延续，但以下行为会改变：

- 以前能超过逻辑磁盘容量的程序现在会得到空间不足；删除文件会关闭该路径的已跟踪句柄，这是防止内存配额逃逸的模拟差异。
- 超大/超深输入、非整数数值和非法运行名会被提前拒绝；依赖这些输入的调用方需要修正数据。
- screen hash 的值会变化，只用于检测当前会话变化；不要持久化后与旧版比较。
- trace 仅能查询保留窗口；关注 `first_seq` 和 `trace_health`。普通页的 `matched` 可能是下界。
- trace 的参数/结果仍是有界预览，数值仍遵循 Lua double；字符串 `dataLength` 保留 Lua 每字符一字节的语义，不改成 UTF-8 字节数。

升级步骤：替换 cct-run 解压目录，把游戏 `mods/` 中旧 cct-live 替换为对应的 0.2.4 jar，然后重启游戏与 MCP 客户端。配置值会保留；文件写入仍默认关闭，无需为了升级启用它。

## 构建与发行

- 版本只定义一次，运行时、Gradle、zip/jar 文件名与 mod 元数据一致；标签必须等于 `v` + 该版本，否则发布失败。
- CI 对实际解压的 zip 做冒烟测试，发行任务复用同次已经测试的 zip/jar，生成 `SHA256SUMS`，拒绝覆盖内容不同的同名资产。
- Gradle 发行包和依赖校验、固定提交的 Actions、依赖审查及 CodeQL 纳入验证。仓库现有 CodeQL Default setup 保留；补充手动分析以 `codeql-sarif` CI 产物提供。
- zip 内 `validation/verification.json` 是 CLI 打包时的报告，CI 中只覆盖 engine/cli；**发行页单独的 `verification.json` 才是四模块最终汇总**。

## 验证范围与已知限制

本轮 Linux 回归执行 engine 53、cli 39、Forge 22、NeoForge 22，共 **136 次 JUnit 测试执行**，全部通过；两个 mod 变体分别执行共享测试，不代表 136 个互不重复的逻辑用例。实际 zip 的 Linux 冒烟 **21 项全部通过**，包含 Java 8 启动器自动切换；发行报告记录实际提交、时间及失败/跳过状态。

Linux Forge 与 NeoForge 开发服务器还验证了接管/租约、记录交接、trace 轮换及颜色回放、重启调用 id、文件/快照、慢 body 与鉴权恢复、有线外设/显示器、卸载及 endpoint 清理。实机记录来自本轮 A/T 修复后的相同 mod 逻辑源码；0.2.4 的版本与文档打包变更另经构建和发行包验证。

**本轮未重跑 Windows 客户端、macOS 或生产服务器**。harness 加固不构成恶意 Lua debug 隔离；外部 SIGKILL/断电可能来不及写 summary；模拟器 trace 仍无磁盘总量上限。模拟范围和资源限制见[限制说明](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/v0.2.4/docs/limitations.md)。

95 项审查问题中，本版完成 63 项 A/T，**其余 32 项 D/K/O/S 未在本轮关闭**，包括待决策略与治理事项。不要把本版当作全部审查问题已解决；范围及逐项证据见[修复索引](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/v0.2.4/issues_to_fix/INDEX.md)和[验证记录](https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/v0.2.4/issues_to_fix/evidence/dynamic/results-1019fix/README.md)。

许可证延续：本项目 MPL-2.0，`harness.lua` 为 CCPL，附带依赖遵循各自许可证，详见发布包中的 `LICENSES/` 和 `THIRD-PARTY-NOTICES.md`。
