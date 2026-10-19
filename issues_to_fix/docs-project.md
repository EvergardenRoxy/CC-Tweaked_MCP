# docs / project issues

基线：`bf68ac4`（v0.2.3）。字段定义见 `README.md`；保留原始核对条目，当前修复状态以 status/fixed_in 字段为准。
本文件同时覆盖审查报告第七节（文档 5 项）与第八节（项目管理）中应修的部分。

---

## DOC-01 — 测试数字三处各说一套，且过期记录随发布包出厂

```yaml
id: DOC-01
title: "test-results.md 54/54、README 81/81、handoff 41/41 三处不一致；test-results.md 被打进发布包"
severity: medium
status: fixed
confidence: confirmed
module: docs
origin: [REVIEW-DOC1, REVIEW-DOC4, REVIEW-NEW-1, REVIEW-NEW-2]
files:
  - path: docs/test-results.md
    lines: "3, 11, 72"
  - path: README.md
    lines: "124"
  - path: handoff.md
    lines: "15"
  - path: cli/build.gradle.kts
    lines: "53"
evidence: |
  docs/test-results.md:11 仍写 54/54、截至 2026-10-17、引用 handoff V1–V13（handoff 已到 V34）；
  README:124 写 81/81（engine 42/cli 30/mod 9，静态 @Test 精确计数 81，无参数化/禁用，已核实）；
  handoff.md:15 status 写 41/41（engine 28, cli 13）。
  cli/build.gradle.kts:53 的 distZip include 列表含 docs/test-results.md → 过期记录随 0.2.3 发布包出厂。
impact: |
  读者无法判断哪个数字可信；发布物内附的测试记录与实际状态矛盾；后续审查者若信 handoff 会低估测试规模。
fix: |
  以一次测试运行产出的数字为单一来源（脚本生成 test-results 表），README/handoff 引用而非手抄；
  发布前更新 test-results.md，或从 distZip 清单移除该文件直到可自动生成；更新 handoff:15 status 或指向最新 VERIFICATION。
tests: |
  静态 @Test 计数 42+30+9=81 已核实。
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  scripts/test_summary.py 从实际四模块 XML 生成机器报告，README/handoff 指向该源，test-results 明确为历史，zip 携带 validation/verification.json。包内报告与源报告比对通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## DOC-02 — 内部过程记录 handoff.md 被对外文档当作 LIMITATIONS 出处

```yaml
id: DOC-02
title: "127KB 的 handoff.md 放仓库根目录，自述 audience 是 machine reviewer，却被 README 引用为面向用户的 LIMITATIONS 出处"
severity: low
status: fixed
confidence: confirmed
module: docs
origin: [REVIEW-DOC2]
files:
  - path: handoff.md
    lines: "5, 380, 413"
  - path: README.md
    lines: "115"
  - path: docs/building.md
    lines: "54"
evidence: |
  handoff.md 127,670 B；第 5 行自述 audience 为 machine reviewer/next agent（not end users）；README:115 让用户去看其 LIMITATIONS/DIVERGENCES 两节（:380/:413 确实存在）；docs/building.md:54 也指向它。
impact: |
  面向用户的差异清单藏在 127KB 内部记录里，用户难读且易与实现脱节。
fix: |
  抽 docs/limitations.md 作为用户向单一出处；handoff 保留机器记录并引用它。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  docs/limitations.md 作为用户限制单源，README/building 指向它；handoff 保留历史与差异账本并引用当前用户文档。zip 中包含新文件。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## DOC-03 — docs 受众混杂（内部实测记录与用户文档同级）

```yaml
id: DOC-03
title: "docs/ 混放用户文档与内部实测记录：5 份 joint-test*、live-test-mplayer、test-results 与 install/usage 同级"
severity: low
status: open
confidence: partial
module: docs
origin: [REVIEW-DOC3]
files:
  - path: docs/joint-test.md
    lines: "1"
  - path: docs/live-test-mplayer.md
    lines: "1"
  - path: docs/test-results.md
    lines: "1"
evidence: |
  docs 下 16 个 md + images：用户向 install/usage/building/testing/agent-guide/release-notes；
  内部向 joint-test.md、joint-test-factory.md、joint-test-factory-report.md、joint-test-orders.md、
  joint-test-orders-report.md（共 5 份）、live-test-mplayer.md、test-results.md。
  审查称「mod README 与根 README 数千字重复」不成立：difflib 全文字符相似度仅 0.093，连续重复 6 块共 274 字符。
impact: |
  发布包与文档目录的受众边界不清；内部记录被当成正式文档。
fix: |
  内部测试记录移入 docs/internal/ 或 handoff 附录，docs 只留用户向文档。
tests: |
  none
```

## DOC-04 — macOS 未测试，但安装节未就地提示

```yaml
id: DOC-04
title: "macOS 分支从未测试：README 测试表已标注，但安装节直接给 macOS 命令未提示；仓库无 bin/（由 application 插件生成）"
severity: low
status: fixed
confidence: partial
module: docs
origin: [REVIEW-DOC5]
files:
  - path: README.md
    lines: "74-84, 128"
  - path: docs/test-results.md
    lines: "14, 81"
  - path: cli/src/launcher/java/cctrun/cli/JavaFinder.java
    lines: "141-143, 165-167"
evidence: |
  README:128 与 test-results:14/:81 均写 macOS 尚未测试；README:74-84 安装节直接给 macOS 命令且无就地提示；仓库无 bin/；JavaFinder 有 macOS 分支（:141-143/:165-167）。
impact: |
  macOS 用户按安装节操作时不知道该路径完全未经验证。
fix: |
  安装节 macOS 命令旁显式标注未测试，并把 macOS 列入已知缺口。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  README 安装命令与 docs/install 的 macOS 命令旁就地标注尚未实测，limitations/测试说明保持相同边界。静态文档/包内容核对通过。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## DOC-05 — 三处测试记录手工同步（机制）

```yaml
id: DOC-05
title: "test-results.md / README 测试表 / handoff VERIFICATION 三处手工同步同一事实"
severity: low
status: fixed
confidence: confirmed
module: docs
origin: [REVIEW-DOC4]
files:
  - path: docs/test-results.md
    lines: "11"
  - path: README.md
    lines: "124"
  - path: handoff.md
    lines: "15"
evidence: |
  同一“测试通过数”需人工改三处；DOC-01 已出现实际漂移（54/81/41）。
impact: |
  漂移会继续发生；发布物里的数字可能与实际不符。
fix: |
  由测试脚本生成一份机器可读结果（如 build/test-results.json），其余文档引用该结果。
tests: |
  none
fixed_in: e5f721eaed2945cdcc099ea4f73547de441f4b65
fixed_at: 2026-10-19
resolution: |
  以实际 JUnit XML -> verification.json 为唯一当前计数源，记录 commit/dirty/report time/失败与跳过，缺模块明确 unverified；CI 汇总，其他文档不再手工同步数字。
  验证索引：issues_to_fix/evidence/dynamic/results-1019fix/ISSUE_MATRIX.md；本轮限制和实际报告见同目录 README.md。
```

## PROJ-01 — 仓库 description/topics/homepage 全空

```yaml
id: PROJ-01
title: "GitHub 仓库 description、topics、homepage 均为空，可发现性为零"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (GitHub metadata)
    lines: "description=null, homepage=null, topics=[]"
evidence: |
  GitHub API（2026-10-18）：description=null、homepage=null、topics=[]；stars=1（审查时为 0）、forks=0、watchers=1、subscribers=0。
impact: |
  搜索/推荐几乎不可达；与项目实际完成度不匹配。
fix: |
  补 description 与 topics（minecraft、computercraft、cc-tweaked、mcp、model-context-protocol、testing）；homepage 指向文档；考虑上架 Modrinth。
tests: |
  none
```

## PROJ-02 — 无 SECURITY.md（已有真实安全修复史）

```yaml
id: PROJ-02
title: "无 SECURITY.md / 安全披露渠道，而项目已有 0.2.3 路径逃逸修复史"
severity: medium
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (repo root)
    lines: "缺少 SECURITY.md"
evidence: |
  根目录只有 .gitattributes/.gitignore/build.gradle.kts/gradlew(.bat)/handoff.md/LICENSE/README.md/settings.gradle.kts/THIRD-PARTY-NOTICES.md；无 SECURITY.md；release-notes-0.2.3.md:3 记录修复 cct-live 文件接口路径逃逸。
impact: |
  发现者只能开公开 issue，等于公开 0day；常驻游戏进程的 HTTP 服务 + AI 执行环境对披露渠道尤其敏感。
fix: |
  添加 SECURITY.md（邮箱/私有渠道）；对 0.2.3 的修复考虑补 GitHub Security Advisory；Modrinth 页面开启漏洞报告入口。
tests: |
  none
```

## PROJ-03 — 缺社区/治理文件

```yaml
id: PROJ-03
title: "无 CONTRIBUTING、CODE_OF_CONDUCT、CHANGELOG、issue/PR 模板、dependabot"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: .github/
    lines: "仅 workflows/build.yml"
  - path: (repo root)
    lines: "无 CONTRIBUTING/CODE_OF_CONDUCT/CHANGELOG"
evidence: |
  根目录与 .github 枚举均无这些文件；.github 递归仅 workflows/build.yml。
impact: |
  外部协作与治理面缺失；AI 贡献验收流程只存在于维护者脑内与 handoff 中。
fix: |
  添加 CONTRIBUTING（含 AI 贡献验收流程：哪些必须人测、冒烟如何跑、V 编号如何记）、CODE_OF_CONDUCT、CHANGELOG 或 release notes 索引、issue/PR 模板；启用 dependabot。
tests: |
  none
```

## PROJ-04 — issue 区零使用、PR 同名重跑产物公开

```yaml
id: PROJ-04
title: "Issues 0 条（从未使用）；13 个 PR 全部自开自合，其中 #6-#9 四个同名 'Claude/ecstatic ritchie g164bm'"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (GitHub)
    lines: "issues=0; pulls=13"
evidence: |
  GitHub API：real issues=0（13 个 item 全是 PR）；13 个 PR 全部 closed 且 merged，user 均为 EvergardenRoxy；#6-#9 标题完全相同 "Claude/ecstatic ritchie g164bm"；#5/#3 "Claude/zealous ramanujan rrt0qh"；#13 "Multi version build SUPPORTED!!!"、#11 "1018安全性更新"、#12/#10 版本发布、#2/#1 "Claude/cct run cli"、#4 docs README。
impact: |
  问题追踪与外部协作面缺失；AI 工作流重试产物进入公开历史，展示噪声大。
fix: |
  非平凡改动至少开 issue/草稿 PR 记录意图；retitle/close-duplicate 清理同名 PR 噪声；启用 issue 模板。
tests: |
  none
```

## PROJ-05 — Release 命名混杂、0.2.1 跳号无解释

```yaml
id: PROJ-05
title: "4 个 release 命名中英混杂（'首个测试版发布'、'cct-run 0.2.2发布'、'cct-run 0.2.3 修复安全性等问题'），v0.2.1 跳号无说明"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (GitHub releases)
    lines: "v0.2.3/v0.2.2/v0.2.0/v0.1.0"
evidence: |
  GitHub API：4 个 release、各 2 资产、无 prerelease；tag 只有 v0.1.0/v0.2.0/v0.2.2/v0.2.3（无 v0.2.1）；release title 中英混杂。
impact: |
  版本历史不易读；用户不知道 0.2.1 是否存在/为何跳过。
fix: |
  统一 release 命名格式（如 "cct-run 0.2.x"）；0.2.1 若为废弃版本在 release notes 一句话说明。
tests: |
  none
```

## PROJ-06 — 未上架 Modrinth/CurseForge

```yaml
id: PROJ-06
title: "仅 GitHub Releases 分发，未上架 Modrinth/CurseForge（CC:T 生态事实主渠道）"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (distribution)
    lines: "GitHub Releases only"
evidence: |
  审查联网检索 Modrinth/CurseForge 无结果；CC:T 本体已从 CurseForge 撤至 Modrinth；mod 只在 GitHub Releases 分发。
impact: |
  目标用户（CC:T 玩家）难以发现；更新分发依赖手动下载。
fix: |
  mod 上架 Modrinth（首选），补 README/install 的 Modrinth 链接与版本说明。
tests: |
  none
```

## PROJ-07 — 分支/提交身份卫生

```yaml
id: PROJ-07
title: "远程残留 multi-version-build 分支；本地临时分支 1018/neoforge-1.21.1；两个个人邮箱并存；占位身份 cct-run review 提交"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: (git)
    lines: "remote branches: main, multi-version-build"
evidence: |
  GitHub 远程分支 2 个：main、multi-version-build（已合并未删）；本地另有 1018、neoforge-1.21.1；
  git shortlog：Claude <noreply@anthropic.com> 40、Evergarden_Roxy <fubukisanta@163.com> 21、
  Evergarden_Roxy <zenyuedu@163.com> 10、cct-run review <review@example.invalid> 1（共 72 commits）。
impact: |
  提交历史身份不统一；死分支持续消耗 CI（见 BLD-09）。
fix: |
  合并后删远程分支；统一 git 身份；CI 触发器收敛到 main + release tag。
tests: |
  none
```

## PROJ-08 — 单维护者 + AI 主写（bus factor）

```yaml
id: PROJ-08
title: "40/72 commit 由 Claude 完成，项目流程只存在于维护者与 handoff.md 中（bus factor=1）"
severity: low
status: open
confidence: confirmed
module: project
origin: [REVIEW-PROJECT]
files:
  - path: handoff.md
    lines: "D/V/N 编号体系"
evidence: |
  git shortlog：Claude 40、Evergarden_Roxy 31、占位 1；README 有 AI 制作声明；handoff.md 的 D/V 编号 + 冒烟 + 游戏内实测构成事实上的验证流程，但只写在 127KB 过程文档里。
impact: |
  外部贡献者无法复现验收流程；维护中断时项目难接手。
fix: |
  把「AI 贡献验收流程」写成 CONTRIBUTING 一节（哪些必须人测、冒烟如何跑、V 编号如何记）；在 README 简述。
tests: |
  none
```
