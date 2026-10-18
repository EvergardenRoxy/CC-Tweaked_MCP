# evidence — 核对原始证据

本目录保存 2026-10-18 对 `bf68ac4`（CC-Tweaked_MCP v0.2.3）核对的**原始证据**，按类型分为 lane 报告与动态探针。
结论汇总在 `../verification/verification-table.md`；待修清单在 `../INDEX.md`。

## 分类

| 目录 | 内容 | 用途 |
|---|---|---|
| `lanes/` | A-F 六条核对 lane 的完整报告（每条约 30-46 KB） | 每条审查意见的 file:line 证据、结论、偏差；每份末尾有 `## AI-ISSUES` YAML 便于机读 |
| `lanes/task-cards/` | 派发核对任务时写的任务卡（A-E 五份） | 还原「要核对什么/怎么核对」，含报告原文摘要与验证要点 |
| `dynamic/` | Lead 动态复现的脚本、fixture、run 记录 | 证明 H1/H2/F1/F3/F10/C2/新 SOE 等结论 |
| （上级）`../verification/` | 核对全表 | 结论汇总 |
| （上级）`../*.md` | 待修 issue | 修复侧 |

## lane 报告与模块

| 文件 | 模块 | 审查编号 | 结论计数 |
|---|---|---|---|
| `lanes/A-engine-runtime.md` | engine 运行时 | H1,H2,E1-E4,L-* | 确认 10 / 部分 5 / 无法静态 1（H1 链） |
| `lanes/B-engine-fixture.md` | engine fixture/外设 | F1-F12 | 确认 6 / 部分 5 / 不成立 1（F7） |
| `lanes/C-cli.md` | cli（命令+MCP） | C1-C4,L5-L15 | 确认 12 / 部分 3 |
| `lanes/D-mod.md` | mod（cct-live） | M1-M5,L1-L7,D1-D2,P1-P6 | 确认 7 / 部分 11 / 不成立 1（D2 安全影响）/ 无法静态 1（D1） |
| `lanes/E-build-docs.md` | 构建/CI/文档 | H3,H4,B1-B5,L8-L13,DOC1-5 | 确认 13 / 部分 4 |
| `lanes/F-legacy-plans.md` | 旧 fix plan（v0.2.2） | OLD-* | fixed 18 / open 26 / obsolete 3 |

lane 报告内部结构：概览 → 逐条详细核对 → `## AI-ISSUES`（YAML，每个条目含 id/verdict/severity/files/evidence/impact/fix/tests/source_ids）。

## 基线

- 仓库 `D:/AI/DSH/cc-t/CC-Tweaked_MCP`，HEAD `bf68ac4`（= origin/main；审查报告对象）
- 项目版本 0.2.3；`git describe` = `v0.2.3-9-gbf68ac4`（tag v0.2.3 = `f31082f`，落后 9 提交）
- 工具链：Zulu JDK 21.0.12.1；已构建 `cli/build/install/cct-run`（0.2.3，含 cc-tweaked-core 1.120.2、cobalt 0.9.9、gson 2.11.0）
- 全程未修改仓库源码（仅 `main` fast-forward 对齐审查提交）；lane 核对均只读，未跑 gradlew

## 动态证据

见 `dynamic/README.md`：11 个 run 目录 + 9 个探针脚本 + 29 个 fixture。
