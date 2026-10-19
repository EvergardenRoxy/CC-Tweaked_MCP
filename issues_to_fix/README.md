# issues_to_fix

本目录是 **AI 可机读的待修问题清单**（无需人类可读性）。由 2026-10-18 对 `bf68ac4`（项目版本 0.2.3）的静态+动态核对产出，并合并了本工作区 v0.2.2 期旧 fix plan 的 open 项。

## 文件

| 文件 | 内容 |
|---|---|
| `README.md` | 本说明：schema、状态机、使用方式、统计 |
| `TREE.md` | 目录结构总览 |
| `INDEX.md` | 全量索引（一行一个 issue） |
| `REPAIR_PLAN.md` | 2026-10-19 自主修复计划：95 条分派、执行批次、依赖与验收标准（不替代 issue 状态） |
| `engine.md` | ENG-RT-*（engine 运行时）/ ENG-FX-*（fixture/外设） |
| `cli.md` | CLI-*（cct-run 命令 + stdio MCP 服务器） |
| `mod.md` | MOD-*（cct-live mod） |
| `build-ci.md` | BLD-*（Gradle / GitHub Actions / 发布） |
| `docs-project.md` | DOC-*（文档）/ PROJ-*（GitHub 仓库治理） |
| `legacy-open.md` | 旧 fix plan 的 26 条 open 项 → 本目录 issue 的映射与状态 |
| `verification/` | 审查意见逐条核对全表（结论/证据/偏差/动态记录），见 `verification/README.md` |
| `evidence/lanes/` | A-F 六条核对 lane 的完整原始报告（含每条的 file:line 证据与 AI-ISSUES YAML） |
| `evidence/lanes/task-cards/` | 派发给核对 lane 的任务卡（含待核对条目与验证要求） |
| `evidence/dynamic/` | 动态探针脚本、fixture、运行记录（11 个 run 目录），见 `evidence/dynamic/README.md` |

## 每条 issue 的格式

````markdown
## <ID> — <短标题>

```yaml
id: ENG-RT-01
title: "..."
severity: high | medium | low | info
status: open
confidence: confirmed | partial | cannot-static
module: engine-runtime | engine-fixture | cli | mod | build-ci | docs | project
origin: [REVIEW-H2, LEGACY-A9]      # 来源编号，见下表
files:
  - path: 相对仓库根的路径
    lines: "56-58, 88, 134"
evidence: |
  一句话级证据；静态核对给 file:line，动态核对给命令与观察值。
impact: |
  为什么是问题。
fix: |
  建议改法（可多步）。
tests: |
  需要的回归测试；none 表示暂无。
```
````

机器解析约定：
- issue 边界 = 行首 `## <ID> —`（em dash 分隔）；ID 正则 `(ENG-RT|ENG-FX|CLI|MOD|BLD|DOC|PROJ)-\d{2}`。
- 每个 issue 正文是**一个** ` ```yaml ` fenced block；字段为扁平的 YAML 键（`files` 是对象数组）。
- `evidence/impact/fix/tests` 为 `|` 块字符串。
- 修改时只改字段值，不删 issue；修完后 `status: fixed` 并追加 `fixed_in: <commit>`、`fixed_at: <date>`。

## 枚举

- `severity`：`high`（正确性/安全/信任底线，先修）、`medium`（资源/可用性/工程风险）、`low`（边界/健壮性/可维护性）、`info`（记录用，不强制修）。
- `status`：`open`（未修）、`fixed`（已修，带 fixed_in）、`wontfix`（决定不修，需写 reason）。
- `confidence`：`confirmed`（代码/实测确认）、`partial`（核心成立但报告细节需修正）、`cannot-static`（静态无法判定，需动态/第三方环境）。

## origin 来源标记

| 前缀 | 含义 |
|---|---|
| `REVIEW-<编号>` | 本次审查报告编号（H1-H4 / E1-E4 / F1-F12 / C1-C4 / M1-M5 / B1-B5 / 低级别 L-xxx / DOC / NEW-n） |
| `LEGACY-<编号>` | v0.2.2 旧计划编号（OLD-*，见 `legacy-open.md`）；旧编号与新 M1-M5 撞名时一律加 `OLD-` |
| `REVIEW-#n` | 构建/CI 分报告的发现编号（build-ci.md 的 1-13） |

## 统计（95 条）

| severity | 数量 | 分布 |
|---|---|---|
| high | 2 | engine 2 |
| medium | 22 | engine 3、cli 2、mod 6、build 9、docs/project 2 |
| low | 66 | engine 19、cli 14、mod 18、build 4、docs/project 11 |
| info | 5 | engine 2、cli 1、build 2 |

核对结论分布（见 `D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/verification/verification-table.md`）：审查报告 67 条代码/工程发现中，确认/部分确认 52 条、不成立或降级 9 条、无法静态验证 6 条；另有 NEW 发现 20+ 条；文档/项目管理 12 项中确认 10 项、修正 2 项。

## 2026-10-19 修复状态

A/T 63 项已验证修复，附真实 `fixed_in` / `fixed_at`；其余 D/K/O/S 32 项保留原状态，不以消除计数为由改变策略。`PROJ-02` 的 SECURITY.md 已在原提交加入，S 类尚未进行独立状态同步，不能重复实现或计入本轮修复。原始证据保留，字符串字段已规范为合法 YAML 块字符串。

| severity | fixed | open |
|---|---:|---:|
| high | 2 | 0 |
| medium | 16 | 6 |
| low | 44 | 22 |
| info | 1 | 4 |

证据：[results-1019fix](evidence/dynamic/results-1019fix/README.md)，矩阵：[ISSUE_MATRIX](evidence/dynamic/results-1019fix/ISSUE_MATRIX.md)。历史 severity 总数不变；95 是全部原始问题数量，并非仍有 95 项 open。

## 使用方式（AI）

1. `grep -h '^## ' engine.md cli.md mod.md build-ci.md docs-project.md` 取全部 ID。
2. 按 `severity` + `status: open` 过滤；`confidence: cannot-static` 的先做动态验证再改。
3. 修复时改对应模块 md 里的 `status`/追加 `fixed_in`；不要新建重复条目。
4. 需要旧计划细节时读 `legacy-open.md` 的映射，再回 `D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/lanes/F-legacy-plans.md`。

## 核对产物位置

- 核对表（全部审查条目 + 结论）：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/verification/verification-table.md`
- 分 lane 明细与原始证据：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/lanes/{A-engine-runtime,B-engine-fixture,C-cli,D-mod,E-build-docs,F-legacy-plans}.md`
- 动态探针与运行记录：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/dynamic/`（`scripts/` 探针、`fixtures/` 输入、`runs/` 11 个 run 目录，说明见其 `README.md`）
- 基线：仓库 `CC-Tweaked_MCP` HEAD `bf68ac4`；`git describe` = `v0.2.3-9-gbf68ac4`（tag v0.2.3 落后 9 个提交，见 BLD-14）。
