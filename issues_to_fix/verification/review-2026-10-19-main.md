# 2026-10-19 工程报告：当前 main 逐项复核

```yaml
format: cct-run/review-verification@1
reviewed_at: 2026-10-20
baseline: 25a392102b1ce47c32743564298df0622b04e9ca
version: 0.2.4
source_report_baseline: "3808530"
source_report_version: 0.2.2
source_report: issues_to_fix/evidence/reports/review-2026-10-19-0.2.2.md
observations: issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json
scope: "上传报告的 19 条发现、声明与测试缺口；目录状态附加核对 DOC-03/PROJ-02；不是全部旧 issue 重审"
```

来源：[报告原文](../evidence/reports/review-2026-10-19-0.2.2.md)、[来源与 hash](../evidence/reports/review-2026-10-19-0.2.2-source.json)。原文只按仓库文本策略规范 CRLF 为 LF，不改其结论。原报告的 `3808530` 是 2026-10-17 的历史提交，不是当前 main。

本表与 [verification-table.md](verification-table.md) 分开：后者是 2026-10-18 对 `bf68ac4` / 0.2.3 的历史核对表。两份报告的编号和问题数不能相加后当作唯一缺陷数。新来源统一使用 `REVIEW-20261019-Px-y`，不覆盖原 `REVIEW-*` / `LEGACY-*`。

下列源码链接和行号均对应本表固定基线。结论“已有修复”表示源码/已有回归已处理原路径，不承诺本轮在 Windows 或游戏环境重新验证。动态部分仅以[本轮观察](../evidence/dynamic/results-2026-10-20-review/README.md)为准。

## 19 条发现

| 原编号 | 结论 | 依据与当前边界 | 对应 issue / 处理 |
|---|---|---|---|
| P1-1 强杀后清理输出文件 | 已有修复 | [McpServer.java](../../cli/src/main/java/cctrun/cli/McpServer.java) 294–349：有界 waitFor、best-effort 删除、最终检查存活子进程。删除异常不再顶掉超时结果；不保证无临时残留，Windows 未重测。 | CLI-15；保留 fixed，不重复建项 |
| P1-2 trace 轮换/读取竞争 | 已有修复 | [ComputerTrace.java](../../mod/src/common/java/cctrun/live/ComputerTrace.java) 69–86、108–132：锁内建立磁盘读者快照、写失败退避恢复；RecorderSafetyTest 有对应回归。Windows 原路径未重测。 | MOD-17；保留 fixed |
| P1-3 没有游戏内批准步骤 | 部分确认；不能据此认定当前承诺被破坏 | [LiveSession.java](../../mod/src/common/java/cctrun/live/LiveSession.java) 159–208 确实直接 attach 后告知；但 [agent-guide.md](../../docs/guides/agent-guide.md) 245 明确要求 AI 仅在用户授权时 attach，handoff D42 约定现有接管/撤销流程。事前授权与游戏内二次批准不是同一需求。 | 不新增软件缺陷；不能未经授权引入 pending/allow 流程 |
| P2-1 错误渲染 OOM 误报 shutdown | 已有修复 | [Runner.java](../../engine/src/main/java/cctrun/engine/Runner.java) 146–154、RecordingMachine/VmFailures：typed VM 故障优先进入最终结果。未重跑所有渲染故障组合。 | ENG-RT-15；保留 fixed |
| P2-2 模拟器 trace 无上限 | 确认 | [Trace.java](../../engine/src/main/java/cctrun/engine/Trace.java) 31–80 无字节配额；[README](../../README.md) 178 仍列为待做。公开 API 探针记录文件超过 1 MiB；有限探针不等于无限负载证明。 | 新增 ENG-RT-18，medium/open |
| P2-3 服务器任务超时后仍执行 | 部分处理；剩余问题确认 | [LiveHttp.java](../../mod/src/common/java/cctrun/live/LiveHttp.java) 708–725 已 cancel(false)，可取消未开始的任务，不能回滚已开始的服务器线程操作。503 后仍可能生效；没有游戏内重复操作实测。 | MOD-14；保留 open，不将已有 cancel 代码当作缺失 |
| P2-4 快照覆盖先删后写 | 已有修复 | [ComputerSnapshots.java](../../mod/src/common/java/cctrun/live/ComputerSnapshots.java) 95–131 使用 [SnapshotStore](../../mod/src/common/java/cctrun/live/SnapshotStore.java) 的 stage/backup/rollback/recover。不是所有文件系统上的单步原子事务。 | MOD-19；保留 fixed |
| P2-5 外设方法白名单/线程 | 部分确认；第三方竞态无法验证 | [PeripheralQuery.java](../../mod/src/common/java/cctrun/live/PeripheralQuery.java) 100–120 仍按名字过滤，在服务器线程调用 target.apply。名称不是只读保证；没有实际外设证明跨线程冲突，不可称已复现竞争。 | MOD-13 保留 cannot-static/open，补核对说明；不重复登记已确认竞态 |
| P2-6 live 工具不响应取消 | 已有修复 | [McpServer.Call](../../cli/src/main/java/cctrun/cli/McpServer.java) 516–535 绑定 Future 并中断；LiveToolsTest 114–134 验证取消后停止轮询且服务仍响应。本轮仅核对已有实现/回归。 | CLI-04；保留 fixed |
| P2-7 旧协议与批量不一致 | 确认剩余问题 | [McpServer](../../cli/src/main/java/cctrun/cli/McpServer.java) 47、115–123、217；本轮实测协商 2025-03-26 后数组返回 -32600。该版本[规范](https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/2025-03-26/basic/index.mdx#batching)要求接收批量，需移除不完整版本或补支持，不能只写“不支持”。未泛化到所有历史版本。 | CLI-07；保留 open |
| P3-1 无行长上限 | 已有修复 | ProtocolReader 与 JsonLimits 提供 UTF-8 字节/深度/节点限制；本轮 boolean id 被拒后下一条 ping 成功。不把观察解释为全部异常输入都验证完。 | CLI-02 / CLI-03；保留 fixed |
| P3-2 接受非严格 JSON | 确认 | [McpServer](../../cli/src/main/java/cctrun/cli/McpServer.java) 115 使用 JsonParser.parseString；本轮未加引号的键和单引号字符串 ping 被接受。 | 新增 CLI-18，low/open |
| P3-3 重复 id 覆盖旧 Call | 已有修复 | [McpServer](../../cli/src/main/java/cctrun/cli/McpServer.java) 176–178 使用 putIfAbsent 拒绝在途同 id，191/198 使用 remove(key, call)。未另做并发探针。 | 已修路径直接映射源码；CLI-14 补相关生命周期说明，不将两个问题混为一个 |
| P3-4 屏幕基线异常被吞 | 已有修复 | LiveTools 的 baseline sentinel/warning 与 unavailableActionBaselineNeverAcceptsStaleText 回归存在。 | CLI-17；保留 fixed |
| P3-5 缺 Host 放行 | 已有修复 | [HttpPolicy](../../mod/src/common/java/cctrun/live/HttpPolicy.java) 13–14 明确要求非 null 回环 Host。 | MOD-06；保留 fixed |
| P3-6 两线程/重查询阻塞 | 部分改善，保留容量选择 | LiveHttp 的线程池仍为 2；HttpPolicy.read 已有截止时间，trace 有窗口/分页/磁盘快照。不能把“2 线程”单独认定为 bug；剩余重查询成本未做新压力实测。 | MOD-08 / MOD-09；不因有意保留的容量选择重开旧问题 |
| P3-7 token.txt 权限 | 已有修复 | [LocalFiles.token](../../mod/src/common/java/cctrun/live/LocalFiles.java) 13–30 在 POSIX 创建/修正 rw-------；不把它当作 Windows ACL 验证。 | MOD-07；保留 fixed |
| P3-8 同名文件覆盖/事件误标 | 第一项已处理；第二项未证明 | Runner 100–107 已发 fixture_file_overwritten，本轮真实运行确认 note；RecordingMachine 99–104 仍按 pendingSettings 首次匹配，但没有证明正常启动时程序事件能抢在真实设置事件前。不能把可能性登记为已复现缺陷。 | 覆盖行为不改，只记已修；事件误标留本表验证边界，不新增 open 项 |
| P3-9 旧测试数/工具数 | 主要已处理 | handoff 15–22 已区分历史快照与当前报告；README 17、mod/README 97 均写 16 实机工具。历史数字应保留，不重新手写“当前通过总数”。 | DOC-01 / DOC-05；保留 fixed |

## 原报告总体结论的边界

- “未发现 P0”是有限审查结果，不是无条件安全证明；本轮不重新为全部依赖或所有环境背书。
- 模拟器纯内存根盘与 cct-live 的宿主存档挂载不同。“服务器真实文件系统不可达”不能概括后者；handoff D50/L26 已说明路径输入过滤不是完整的 mount 包含性证明。本轮没有确认新的逃逸漏洞。
- 无 CORS、回环绑定、Host 与 Bearer 是组合防护，不能仅因无 CORS 就推导一切 CSRF 不可能。
- 旧部署自报 0.2.4、旧仓库 0.2.2 不能证明部署源码的来源；没有部署提交/资产 hash 时不能直接要求合回部署版。当前 main 已为 0.2.4。
- 原报告 54/54、64 KiB、旧测试缺口等只属于其历史基线。当前请求体上限由 ComputerFiles.MAX_READ 定义为 1 MiB；已有 Launcher、取消、TickPacer、输入限制、HTTP 策略与快照恢复测试，不能原样登记“均无覆盖”。

## 本轮目录状态同步

1. DOC-03：原状态 open 已被后续用户决策 D74 和提交 `88c3e7721d334426e01155abafa8e1cec335cb86` 解决。本轮核对 guides/development/testing/{plans,prompts,reports}/releases/assets 分层、docs 索引和旧路径映射后同步 fixed；修复日期/提交采用原实际修复，不冒充本次新修。
2. PROJ-02：SECURITY.md 已由 `dc57617` 加入；其私密报告入口是否启用未验证。将标题/说明调整为剩余验证，保留 open，不重复新建文件、不更改 GitHub 设置。
3. 其他未被本报告覆盖的旧问题不作“已重审”声明；本轮不顺手关闭治理项、重分配全部旧 severity 或覆盖原 95 项执行计划。
4. BLD-13 原 YAML 缺少 README 要求的 `tests` 字段，本轮补为明确的 none 说明；不改变该信息项的 severity、status 或历史测试证据。

本轮共保留原 95 个 ID，新增 ENG-RT-18 / CLI-18；合计 97 项，fixed 64 / open 33。数量从模块 YAML 汇总，索引同源核对。与 2026-10-19 的 63 fixed / 32 open 相比，增加两个 open，另同步关闭原已完成的 DOC-03。
