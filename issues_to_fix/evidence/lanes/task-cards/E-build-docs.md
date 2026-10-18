# 核对任务 E：构建/CI + 文档 lane

基线：D:\AI\DSH\cc-t\CC-Tweaked_MCP，git HEAD = bf68ac4（v0.2.3，含新加的 .github/workflows/build.yml 与 mod 目录重构）。只读核对，**不要修改仓库文件**。
产物：写到 `D:\AI\DSH\cc-t\CC-Tweaked_MCP\issues_to_fix\evidence\lanes\E-build-docs.md`（表格 + 偏差说明）。
结论用：确认 / 部分确认 / 不成立 / 已修复 / 无法静态验证（附方法）/ 降级。证据给 文件:行号 + 关键内容。

## 待核对条目（build-ci.md 编号 H3/H4/B1-B5/L8-L13 + 审查报告第七节文档 5 项）

| ID | 级别 | 审查位置 | 审查意见摘要 | 核对要点 |
|---|---|---|---|---|
| H3/B1(构建报告#1) | 高 | .github/workflows/build.yml:42,68,96 | CI 三个 job 只跑 gradlew build/distZip；scripts/smoke-test.sh|ps1（21/22 用例）完全未接入；无 CodeQL/dependency-review/wrapper-validation/dependabot；README 测试表自认 0.2.3 冒烟未重跑；README build badge 钉在 multi-version-build 而非 main。 | 读 build.yml 全文；grep 全仓库 yml/yaml/kts/toml/properties 中 "smoke"；检查 .github 下是否只有 build.yml；核对 README.md:5 badge 与测试表、README 是否声明 smoke 覆盖。 |
| H4(构建报告#2) | 高 | build.gradle.kts:5；engine/src/main/java/cctrun/engine/Versions.java:8 | 打包版本（zip 名、mod jar、mods.toml）源于 build.gradle.kts；运行时自报源于 Versions.java:8；release job 不校验 tag 与版本一致；唯一版本测试 RunnerTest.java:35 只锁 Versions.java；handoff.md:10 把 Versions.java 记为版本源；该风险已有现实版本（gson 2.10.1 vs 2.11.0）。 | 核对版本传播链（改后行号可能变：build.gradle.kts 的 version 行、cli/build.gradle.kts applicationName/archive、mod/forge|neoforge/build.gradle.kts 的 mods.toml 展开、build.yml release job 是否校验 tag）；核对 RunnerTest 断言。 |
| B1(构建报告#5) | 中 | build.yml:82-110 | release job 重新检出重建，发布物与 CI 测过的产物不保证位一致；tests job 上传的 mod jar 从未被 release 复用；gh release create 不幂等。 | 核对 needs/uploads/downloads 与 release job 构建命令、assets glob。 |
| B2(构建报告#6) | 中 | .github/workflows/（仅 build.yml） | 无 CodeQL/dependency-review/wrapper-validation/dependabot/verification-metadata。 | 枚举 .github、仓库根、gradle/ 下的文件；给出 absence 证据。 |
| B3(构建报告#3) | 中 | mod/forge/build.gradle.kts:70 / mod/neoforge/build.gradle.kts:67 vs gradle/libs.versions.toml:6 | mod 两构建脚本绕过 catalog 手写 gson 2.10.1，catalog/THIRD-PARTY-NOTICES 为 2.11.0；junit 系亦手写。 | 核对实际行号与版本字符串；确认 test scope 不进发布物。 |
| B4(构建报告#4) | 中 | mod/forge/build.gradle.kts vs mod/neoforge/build.gradle.kts | 两脚本约 70% 逐行重复（连行号都一致）；"1.120.2" 硬编码四处。 | 统计两份脚本行数与差异；列出重复块与实质差异（插件、MC/loader 版本、options.release、依赖、metadata 文件名）。 |
| B5(构建报告#7) | 中 | .gitattributes:1-2（+ handoff.md:635） | 无 `* text=auto`；smoke-test.sh 不受 eol=lf 保护；发布 zip 打包 scripts/**；handoff.md:635 实测记录 pack.mcmeta 行尾导致跨平台产物哈希不可比。 | 读 .gitattributes 与 cli/build.gradle.kts 的 distZip 打包规则；核对 handoff.md:635 原文。 |
| L8 | 低 | scripts/smoke-test.sh:26; scripts/smoke-test.ps1:28 | 两份 smoke 报告默认写到仓库根目录，.gitignore 未覆盖。 | 核对报告路径与 .gitignore 条目。 |
| L9/B5b | 低 | build.yml:16 | push 触发分支硬编码含临时分支 "1018"；pull_request 无分支过滤会双跑。 | 核对 branches 列表与实际分支（git branch -a、git ls-remote）。 |
| L10 | 低 | gradle/wrapper/gradle-wrapper.properties:3; build.yml actions | wrapper 9.7.1 无 distributionSha256Sum；actions 用主版本 tag 不是 SHA；gradle-wrapper.jar 入库不校验。 | 核对 properties 与 workflow 引用。 |
| L11 | 疑点 | build.yml:96,105-110 | 发布物无签名/校验和；`*.jar` glob 可能吞入变体 jar；gh release create 不幂等。 | 核对 assets 收集与 build.gradle.kts assembleMods include("*.jar")；静态判断是否有 sourcesJar 注册。 |
| L12 | 信息 | THIRD-PARTY-NOTICES.md | 与 toml/LICENSES 对齐良好（gson 2.11.0 等），但手工维护、无 CI 绑定。 | 核对表格版本与 toml/catalog、LICENSES/third-party 文件清单。 |
| L13 | 信息 | scripts/smoke-test.{sh,ps1} | ps1/sh 用例集合对等，差集仅 mcp-via-cmd（Windows-only）。 | 提取两侧用例名并对比。 |
| DOC1 | 文档 | docs/test-results.md; README.md 测试表; handoff.md | 文档间已实际漂移：test-results.md 仍停在 0.2.2 期"单元测试 54/54"，README 为 0.2.3 "81/81"；test-results/README/handoff 三处手工同步同一事实。 | 核对 docs/test-results.md 当前结论表、README 测试表、handoff 中的测试数字；确认 81 个 @Test 是否属实（统计 engine/cli/mod 的 @Test）。 |
| DOC2 | 文档 | handoff.md（约 127KB，根目录） | 过程记录放公开仓库根目录，却被 README 引用为面向用户的 LIMITATIONS 出处；建议抽 docs/limitations.md。 | 核对 handoff.md 大小、README 引用位置、handoff 自述 audience。 |
| DOC3 | 文档 | README / docs/ / mod/README.md | 受众混杂：mod README 与根 README 数千字重复；joint-test 四份 + live-test-mplayer 属内部记录，与 install/usage 混在 docs/。 | 枚举 docs/ 文件、比较 mod/README.md 与根 README 重复内容长度。 |
| DOC4 | 文档 | docs/*, README, handoff | 测试记录机制不可持续（三处手工同步）。 | 与 DOC1 合并核对。 |
| DOC5 | 文档 | README | macOS 从未测试，bin/cct-run 与 JavaFinder macOS 分支无实测证据；建议显著标注。 | 核对 README 声明与 bin/cct-run 是否存在（仓库内是否有 bin/？）。 |

## 额外要求
1. 所有"absence"结论要给枚举证据（如 `Get-ChildItem .github -Recurse` 输出、grep 结果）。
2. 核对 README 关键数字：20 个 MCP 工具（4+16）、81 个 @Test（engine 42/cli 30/mod 9）、1GB 内存上限、任意格子任意物品上限 64，是否与代码一致。
3. 行号不符给实际行号；最后给"偏差/修正"小节（级别升降、描述不准确）。
