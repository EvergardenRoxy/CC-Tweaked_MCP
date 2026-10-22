# 文档索引

[← 返回项目 README](../README.md)

文档按用途分类；测试方案、可直接使用的提示词和实际报告分别存放，避免把设计预期当作实测结果。

## 使用指南 · `guides/`

| 文档 | 内容 |
|---|---|
| [安装与接入](guides/installation.md) | 安装、MCP 客户端配置、连接游戏电脑 |
| [用法速查](guides/usage.md) | CLI 参数、MCP 工具、退出码与 fixture |
| [模拟范围与已知限制](guides/limitations.md) | 模拟差异、输入限制与验证边界 |
| [AI 使用指南](guides/agent-guide.md) | 英文 ASCII 指南，也可用 `cct-run guide` 获取 |

## 开发维护 · `development/`

| 文档 | 内容 |
|---|---|
| [从源码构建](development/build.md) | 工具链、双版本 mod、CI、图表生成 |
| [发行流程](development/release.md) | 合并、版本检查、标签与发行资产核验 |

## 测试 · `testing/`

- [测试指南](testing/guide.md)：维护者冒烟测试与客户端验证方法。
- [测试记录](testing/results.md)：历次测试结果和适用版本。

| 轮次 | 方案 · `plans/` | 提示词 · `prompts/` | 报告 · `reports/` |
|---|---|---|---|
| 01–02 | [Skill 联合测试](testing/plans/01-02-skill-integration.md) | [02 · MPlayer](testing/prompts/02-mplayer.md) | 结果汇入[测试记录](testing/results.md) |
| 03 | [Create 工厂](testing/plans/03-factory.md) | 包含在方案中 | [工厂测试报告](testing/reports/03-factory.md) |
| 04 | [订单产线](testing/plans/04-orders.md) | 包含在方案中 | [订单测试报告](testing/reports/04-orders.md) |
| 05 | [精密构件自动化](testing/plans/05-precision-mechanism.md) | [精密构件提示词](testing/prompts/05-precision-mechanism.md) | [按用户修改后的条件核验](testing/reports/05-precision-mechanism.md) |

测试编号沿用仓库原有联合测试轮次；上传附件的文件名不作为轮次编号。第五轮的原设计和用户实际修改后的测试条件分别保留，实际结果以报告中的证据边界为准。

当前源码构建的 CLI 发行包包含完整 `docs/`；已发布旧版包保留发布时的目录。报告引用的原始审计证据、`handoff.md`、mod 源码及 GitHub 工作流位于[源码仓库](https://github.com/EvergardenRoxy/CC-Tweaked_MCP)，未全部收入 CLI 发行包；这些源码链接需要在仓库中查看。实际 JUnit 汇总见发行包的 `validation/verification.json`，历史报告不替代本次验证。

## 发行说明 · `releases/`

[0.3.0](releases/0.3.0.md) · [0.2.4](releases/0.2.4.md) · [0.2.3](releases/0.2.3.md) · [0.2.2](releases/0.2.2.md) · [0.2.0](releases/0.2.0.md) · [0.1.0](releases/0.1.0.md)

## 图片资源 · `assets/images/`

| 图表 | 浅色 | 深色 |
|---|---|---|
| 性能开销 | [performance-light.svg](assets/images/performance-light.svg) | [performance-dark.svg](assets/images/performance-dark.svg) |
| 联合测试对比 | [joint-tests-light.svg](assets/images/joint-tests-light.svg) | [joint-tests-dark.svg](assets/images/joint-tests-dark.svg) |

图片由 `scripts/readme_charts.py` 生成，数据来源和生成方法见[构建说明](development/build.md#readme-里的图)。

## 命名约定

- 目录按用途设置；除索引 `README.md` 外，文件名使用小写英文字母、数字和连字符，扩展名小写。
- 测试文件使用 `两位轮次编号-主题.md`，相同测试在方案、提示词和报告目录使用相同主题；跨轮次方案使用 `01-02-主题.md`。
- 发行说明使用 `releases/版本号.md`，保留版本中的点号。
- 图片使用 `主题-light.svg` / `主题-dark.svg`；调整生成资源时同步修改生成脚本和引用。
- 移动或重命名文档时，同步检查相对链接、README 图片、指南资源打包和发行工作流。`cct-run guide` 的命令及 jar 内资源 `/cctrun/agent-guide.md` 保持兼容。

## 旧路径对照

以下路径均相对仓库根目录，用于查找历史审计和 handoff 中的旧引用。固定到旧标签或提交的 GitHub 链接仍指向对应版本的原目录，不随本次迁移改写。

| 旧路径 | 新路径 |
|---|---|
| `docs/install.md` | [docs/guides/installation.md](guides/installation.md) |
| `docs/usage.md` | [docs/guides/usage.md](guides/usage.md) |
| `docs/limitations.md` | [docs/guides/limitations.md](guides/limitations.md) |
| `docs/agent-guide.md` | [docs/guides/agent-guide.md](guides/agent-guide.md) |
| `docs/building.md` | [docs/development/build.md](development/build.md) |
| `docs/releasing.md` | [docs/development/release.md](development/release.md) |
| `docs/testing.md` | [docs/testing/guide.md](testing/guide.md) |
| `docs/test-results.md` | [docs/testing/results.md](testing/results.md) |
| `docs/joint-test.md` | [docs/testing/plans/01-02-skill-integration.md](testing/plans/01-02-skill-integration.md) |
| `docs/live-test-mplayer.md` | [docs/testing/prompts/02-mplayer.md](testing/prompts/02-mplayer.md) |
| `docs/joint-test-factory.md` | [docs/testing/plans/03-factory.md](testing/plans/03-factory.md) |
| `docs/joint-test-factory-report.md` | [docs/testing/reports/03-factory.md](testing/reports/03-factory.md) |
| `docs/joint-test-orders.md` | [docs/testing/plans/04-orders.md](testing/plans/04-orders.md) |
| `docs/joint-test-orders-report.md` | [docs/testing/reports/04-orders.md](testing/reports/04-orders.md) |
| `docs/joint-test-neoforge.md` | [docs/testing/plans/05-precision-mechanism.md](testing/plans/05-precision-mechanism.md) |
| `docs/joint-test-neoforge-prompt.md` | [docs/testing/prompts/05-precision-mechanism.md](testing/prompts/05-precision-mechanism.md) |
| `docs/joint-test-neoforge-report.md` | [docs/testing/reports/05-precision-mechanism.md](testing/reports/05-precision-mechanism.md) |
| `docs/release-notes-0.1.0.md` | [docs/releases/0.1.0.md](releases/0.1.0.md) |
| `docs/release-notes-0.2.0.md` | [docs/releases/0.2.0.md](releases/0.2.0.md) |
| `docs/release-notes-0.2.2.md` | [docs/releases/0.2.2.md](releases/0.2.2.md) |
| `docs/release-notes-0.2.3.md` | [docs/releases/0.2.3.md](releases/0.2.3.md) |
| `docs/release-notes-0.2.4.md` | [docs/releases/0.2.4.md](releases/0.2.4.md) |
| `docs/images/perf-light.svg` | [docs/assets/images/performance-light.svg](assets/images/performance-light.svg) |
| `docs/images/perf-dark.svg` | [docs/assets/images/performance-dark.svg](assets/images/performance-dark.svg) |
| `docs/images/joint-test-light.svg` | [docs/assets/images/joint-tests-light.svg](assets/images/joint-tests-light.svg) |
| `docs/images/joint-test-dark.svg` | [docs/assets/images/joint-tests-dark.svg](assets/images/joint-tests-dark.svg) |
