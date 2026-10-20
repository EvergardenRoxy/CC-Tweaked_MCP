# verification — 审查意见逐条核对

本目录是 `issues_to_fix` 的核对侧：**审查报告每一条意见 → 核对结论 → 证据**。
待修清单（只含确实存在且未修的问题）在上一级目录。

## 文件

| 文件 | 内容 |
|---|---|
| `verification-table.md` | 全量核对表：高级 4 / 中级 19 / 低级 39 / 疑点 3 / 正面 6 / 文档 5 / 项目管理 12；含 NEW 表、报告偏差清单、动态复现记录 |
| `README.md` | 本说明 |

## 基线

- 仓库：`D:/AI/DSH/cc-t/CC-Tweaked_MCP`
- HEAD：`bf68ac4`（= origin/main，审查报告对象）
- 项目版本：0.2.3；`git describe` = `v0.2.3-9-gbf68ac4`（tag `v0.2.3` = `f31082f`，落后 9 个提交）
- 核对日期：2026-10-18
- 方式：6 条并行静态核对 lane + Lead 动态复现（已构建的 `cli/build/install/cct-run` + 真实 CC:T 1.120.2 / Cobalt 0.9.9 jar）

## 结论枚举

| 结论 | 含义 |
|---|---|
| 确认 | 问题成立，按报告（可能修正细节/级别） |
| 部分确认 | 核心成立，但报告的依据、量级、行号或级别需修正 |
| 不成立 | 报告判断错误（静态+动态证据反驳） |
| 无法静态验证 | 需动态/第三方环境，已给验证方法 |
| 已修复 | 0.2.3 代码已修（主要用于旧 fix plan 对比） |

## 读法

1. 先读 `verification-table.md` 的总览与第七节「报告本身的偏差」；
2. 需要某一条的原始证据时，按编号去 `../evidence/lanes/<lane>.md` 找（lane 文件末尾有 `## AI-ISSUES` YAML，便于机读）；
3. 需要复现动态结论时，去 `../evidence/dynamic/`（脚本 + fixture + run 记录）；
4. 要修代码时，回到 `../INDEX.md` 取对应 issue。

## 与 issues 的对应

| 审查编号 | 本目录 issue |
|---|---|
| H1 + NEW-1 | ENG-RT-02 |
| H2 | ENG-RT-01 |
| H3 / H4 | BLD-01 / BLD-02 |
| E1 / E2 / E3 / E4 | ENG-RT-14 / ENG-RT-03 / ENG-RT-04 / ENG-RT-05 |
| F2 / F4 / F3 / F5 / F6 / F9 / F10 | ENG-FX-01 / ENG-FX-02 / ENG-FX-03 / ENG-FX-04 / ENG-FX-05 / ENG-FX-07 / ENG-FX-08 |
| C1 / C2 / C3 / C4 | CLI-01 / CLI-02 / CLI-04 / CLI-05 |
| M1-M5 | MOD-01 / MOD-02 / MOD-03 / MOD-04 / MOD-05 |
| B1-B5 | BLD-03 / BLD-04 / BLD-05 / BLD-06 / BLD-07 |
| DOC1-DOC5 | DOC-01 / DOC-02 / DOC-03 / DOC-04 / DOC-05 |
| 项目管理 | PROJ-01..08 |

（完整映射见每条 issue 的 `origin` 字段。）

## 2026-10-20 新增核对入口

上文保留 2026-10-18 的基线与原编号。用户另上传了 2026-10-19、基线 `3808530` / 0.2.2 的工程报告：

- [当前 main 逐项复核](review-2026-10-19-main.md)：固定 main `25a3921`，19 条发现及总体结论边界。
- [原文](../evidence/reports/review-2026-10-19-0.2.2.md)、[来源/hash](../evidence/reports/review-2026-10-19-0.2.2-source.json)、[本轮观察](../evidence/dynamic/results-2026-10-20-review/README.md)。

原表不覆盖，新来源使用 `REVIEW-20261019-Px-y`。新增问题、当前状态与统计仍以模块 YAML 和 [INDEX](../INDEX.md) 为准；核对表本身不冒充修复。
