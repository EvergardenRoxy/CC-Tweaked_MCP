# E lane 核对结果：构建/CI + 文档

- 核对对象：`D:\AI\DSH\cc-t\CC-Tweaked_MCP`，`git rev-parse HEAD` = `bf68ac478f50065e9d18808201f0e2071a580b37`（Merge PR #13, multi-version-build），`git describe --tags` = `v0.2.3-9-gbf68ac4`，工作区干净。**注意**：HEAD 比 `v0.2.3` tag（cdf45e2）晚 9 个提交——v0.2.3 已 tag，之后又合入 neoforge 移植、双变体构建、CI 三个提交，所以"HEAD=v0.2.3"指的是版本号仍是 0.2.3，不是 tag 指向。
- 方法：只读静态核对（read/grep/glob/git/PowerShell `Get-Content`、`Compare-Object`、`git ls-files --eol`、Python `difflib`）。**未修改仓库任何文件，未运行 `gradlew`**。
- 审查报告原文说明：`D:\AI\DSH\cc-t\review-report.md` 是 0.2.2 期报告（B0–B7/M/L 编号，54/54 测试），全盘未找到本 lane 所称的 `build-ci.md`；本表以任务卡 `evidence/lanes/task-cards/E-build-docs.md` 转述的条目为审查意见基准。
- 结论计数：**确认 13 / 部分确认 4 / 不成立 0 / 已修复 0 / 无法静态验证 0**（个别"行为类"子项已在证据列注明需运行验证）。

## 汇总表

| ID | 审查级别 | 实际位置 | 审查意见(简) | 核对结论 | 证据与说明 | 建议级别 |
|---|---|---|---|---|---|---|
| H3 / B1(构建#1) | 高 | `.github/workflows/build.yml:42,68,96`；`README.md:3,127`；`docs/building.md:42` | CI 三 job 只跑 `gradlew build` / `distZip+assembleMods`，smoke-test 完全未接入；无 CodeQL/dependency-review/wrapper-validation/dependabot；README 测试表自认 0.2.3 冒烟未重跑；build badge 钉 `multi-version-build` 而非 main | **确认** | `build.yml` 全文 110 行只有 3 个 job：`:42` `run: ./gradlew --no-daemon --console=plain build`、`:68` `:mod-${{ matrix.variant }}:build`、`:96` `:cli:distZip assembleMods`；全仓库 `.yml/.yaml/.toml/.properties/.kts` grep `smoke` **零命中**（排除 .git/build/.gradle）；`docs/building.md:42` 原文"CI 只跑单元测试和构建，实机检查仍按 testing.md 手动做"。`.github` 递归枚举仅 `workflows\build.yml`（3549 B），`gradle/` 递归仅 `libs.versions.toml` + `wrapper/{jar,properties}` → 四类安全/供应链 job 与 `verification-metadata.xml` 全部缺失。`README.md:127` 原文"…0.2.3 未重跑"（在 0.2.2 行）；`README.md:3` badge 带 `?branch=multi-version-build`（`b5e81a1` diff 证实该 badge 是本提交新加）。**行号偏差：badge 在 `README.md:3`，非 `:5`**。"21/22 用例"准确：sh 19 个 `check` + `old-java-switch`(SKIP) + `mcp-stdio` = 21；ps1 `$cases` 20 + `mcp-stdio`(+Windows-only `mcp-via-cmd`) = 21/22。**handoff 已自述**：`handoff.md:411`（L30）"no in-game check, no Windows or macOS job…badge keeps `?branch=multi-version-build` until merged"，`handoff.md:679`（N16）列为后续项 → 属"已知限制"而非隐藏缺陷 | 高→**中** |
| H4(构建#2) | 高 | `build.gradle.kts:5`；`engine/src/main/java/cctrun/engine/Versions.java:8`；`RunnerTest.java:35`；`handoff.md:10` | 打包版本源于 build.gradle.kts，运行时自报源于 Versions.java:8，release job 不校验 tag↔版本，唯一版本测试只锁 Versions.java；handoff:10 记 Versions.java 为版本源；gson 2.10.1 vs 2.11.0 是该风险的现实版本 | **确认** | 传播链实测：`build.gradle.kts:5 version = "0.2.3"`（allprojects）→ ① cli distZip 名 `cct-run-0.2.3.zip`（application 插件 + `cli/build.gradle.kts:40 applicationName="cct-run"`）② mod jar 名 `cct-live-<mc>-0.2.3.jar`（`base{archivesName}` + project.version）③ `mods.toml:9`/`neoforge.mods.toml:9 version="${version}"` 由 `processResources` expand 注入。运行时：`Versions.java:8 TOOL="0.2.3"`、`:10 CC_TWEAKED="1.120.2"`，经 `RunnerEnvironment.java:55 "ComputerCraft " + Versions.CC_TWEAKED + " (cct-run " + Versions.TOOL + ")"` 输出。`RunnerTest.java:35` 断言字面量 `"ComputerCraft 1.120.2 (cct-run 0.2.3)"`——全仓库测试只有这一处硬编码版本（grep `0.2.3` 仅命中 `Versions.java:8` 与 `RunnerTest.java:35`），它锁的是运行时串，读不到 `build.gradle.kts`。release job（`:79-110`）无任何 tag↔version 比较，只在 `:104-109` 用 `$GITHUB_REF_NAME` 取 release 名与 notes 文件名。`handoff.md:10` 原文 `tool_version: 0.2.3 (engine/src/main/java/cctrun/engine/Versions.java)`。gson 2.10.1 见 B3。**现实风险已可构造**：HEAD 比 v0.2.3 多 9 个提交而版本号未变，此时推 `v0.2.4` 会得到名为 v0.2.4 的 release 挂着 `cct-run-0.2.3.zip`。当前三处（build.gradle.kts/Versions/README）值一致，属"一致但无守卫" | 高→**中**（加一条 CI 校验即可闭合） |
| B1(构建#5) | 中 | `.github/workflows/build.yml:82,88-96,99-110` | release job 重新检出、重建，发布物与 CI 测过的产物不保证位一致；tests job 上传的 mod jar 从未被 release 复用；`gh release create` 不幂等 | **确认**（措辞需修正） | `:82 needs: [tests, mod]` 但全文**无 `actions/download-artifact`**；`:88` 重新 checkout、`:96` 重新 `:cli:distZip assembleMods`。**描述不准**：上传 mod jar 的是 `mod` job（`:71-77 name: cct-live-${{ matrix.variant }} path: mod/<variant>/build/libs/*.jar`），不是 tests job（tests 只在失败时传测试报告，`:43-49`）。`:105 assets=(cli/build/distributions/*.zip build/mods/*.jar)`；`:107/:109 gh release create` 之前无 `gh release view` 判断，同 tag 重跑必失败（非幂等）。位一致性：`handoff.md:635` 实测 CI 与本地 jar 仅 `pack.mcmeta` 行尾不同（大小 107,690/107,180 vs 107,705/107,195），本仓库也未启用 reproducible/`--preserve-file-timestamps` 之类设置，纯 Gradle 打包不保证位一致 | 中 |
| B2(构建#6) | 中 | `.github/workflows/`（仅 build.yml） | 无 CodeQL/dependency-review/wrapper-validation/dependabot/verification-metadata | **确认** | 枚举证据：`Get-ChildItem .github -Recurse` → 仅 `.github\workflows\build.yml`；`Get-ChildItem gradle -Recurse` → `gradle\libs.versions.toml`、`gradle\wrapper\gradle-wrapper.jar`、`gradle\wrapper\gradle-wrapper.properties`；全仓库 yml/yaml/toml/properties 清单只有上述 2 个配置文件 + `mod/*/src/main/resources/META-INF/*.toml` → 不存在 `.github/dependabot.yml`、`.github/workflows/codeql.yml`、`dependency-review.yml`、`gradle/verification-metadata.xml`、`.github/ISSUE_TEMPLATE` 等 | 中 |
| B3(构建#3) | 中 | `mod/forge/build.gradle.kts:70`、`mod/neoforge/build.gradle.kts:67`、`gradle/libs.versions.toml:4`、`THIRD-PARTY-NOTICES.md:33` | 两份 mod 脚本绕过 version catalog 手写 gson 2.10.1；catalog 与 THIRD-PARTY-NOTICES 为 2.11.0；junit 系亦手写 | **确认**（catalog 行号偏差） | `mod/forge/build.gradle.kts:70 testImplementation("com.google.code.gson:gson:2.10.1")`、`mod/neoforge/build.gradle.kts:67 testImplementation("com.google.code.gson:gson:2.10.1")`——两个行号**完全准确**。catalog 实测 `gson = "2.11.0"` 在 **`gradle/libs.versions.toml:4`**（`:3 cctweaked`、`:5 junit`、`:6 slf4j` 2.0.16）→ 审查写的 `:6` 偏 2 行。`THIRD-PARTY-NOTICES.md:33 com.google.code.gson:gson | 2.11.0`。junit 手写确认：两份脚本各写 `platform("org.junit:junit-bom:5.11.4")`（forge:68 / neoforge:65），版本值与 catalog `:5` 相同。**test scope 不进发布物**：mod 两份脚本里 gson/junit 都是 `testImplementation`（非 `implementation`/`modImplementation`），`assembleMods` 只 copy `build/libs/*.jar` → mod jar 不含 gson；engine/cli 用 `libs.gson`（`engine/build.gradle.kts:9`、`cli/build.gradle.kts:27`）进 dist zip 的 `lib/`，与 THIRD-PARTY-NOTICES 一致 | 中 |
| B4(构建#4) | 中 | `mod/forge/build.gradle.kts`（82 行）vs `mod/neoforge/build.gradle.kts`（79 行） | 两脚本约 70% 逐行重复（连行号都一致）；"1.120.2" 硬编码四处 | **部分确认** | Python `difflib.unified_diff`：forge 82 行、neoforge 79 行，**完全相同 66 行**（占 forge 80.5% / neoforge 83.5%），差异 29 行（删 16/增 13）→ "约 70%" **低估了重复度**。一致行号区间确实对齐（如 14-26 sourceSets、33-38 repositories、62、68-72 依赖骨架）。实质差异=插件（`:6 legacyforge`↔`moddev`）、MC/loader（`:9-10 1.20.1+47.4.10`↔`1.21.1+21.1.262`）、`options.release`（`:29 17`↔`21`）、loader 配置块（`legacyForge{enable{forgeVersion}}`↔`neoForge{version}`）、依赖（forge 有 `modImplementation` + `compileOnly cobalt:0.9.9`，neoforge 仅 `implementation`）、metadata 文件名（`:81 mods.toml`↔`:78 neoforge.mods.toml`）、注释 3 处。**"1.120.2 硬编码四处"不成立**：两份脚本各 1 处（`:12` 的 `val ccTweakedVersion`）=2 处；算上 `gradle/libs.versions.toml:3` 才 3 处（其余 40+ 命中均为文档/注释/`Versions.java:10`） | 中（描述修正） |
| B5(构建#7) | 中 | `.gitattributes:1-2`、`cli/build.gradle.kts:53`、`handoff.md:635` | 无 `* text=auto`；smoke-test.sh 不受 eol=lf 保护；发布 zip 打包 `scripts/**`；handoff:635 实测 pack.mcmeta 行尾致跨平台哈希不可比 | **确认** | `.gitattributes` 全文仅 2 行（42 B）：`1: gradlew text eol=lf`、`2: *.bat text eol=crlf`——无 `* text=auto`、无 `*.sh text eol=lf`。`git ls-files --eol`：`scripts/smoke-test.sh` = `i/lf w/crlf attr/`（无属性），`gradlew` = `i/lf w/lf attr/text eol=lf`（有保护）→ 对比成立；两个 mod `*.toml` 资源同为 `w/crlf attr/`，正是 635 行现象的来源。`cli/build.gradle.kts:53` include 列表含 `"scripts/**"`（并 `:56-58` 给 `scripts/*.sh` 加可执行位）→ 发布 zip 确实带脚本。`handoff.md:635` 原文："The local Windows jars are 107,705 / 107,195 B: the Linux builds differ only in `pack.mcmeta` line endings, so the hashes are not comparable -- compare entries, not hashes." | 中 |
| L8 | 低 | `scripts/smoke-test.sh:26`；`scripts/smoke-test.ps1:28` | 两份 smoke 默认把报告写到仓库根目录，.gitignore 未覆盖 | **确认** | `smoke-test.sh:26 report="$PWD/cct-run-smoke-report.txt"`（`:9` 注释同）；`smoke-test.ps1:28 [string]$Report = "cct-run-smoke-report.json"`（相对 CWD）。`.gitignore` 7 行仅 `.gradle/ build/ .cct-runs/ *.jsa .idea/ *.iml run/` → 无 `cct-run-smoke-report.*`。注：`scripts/smoke-test.sh` 在仓库根运行时才会落到根目录，`$PWD` 语义如此 | 低 |
| L9 / B5b | 低 | `build.yml:16` | push 触发分支硬编码含临时分支 "1018"；`pull_request` 无分支过滤会双跑 | **部分确认** | `:16 branches: [main, "1018", multi-version-build, neoforge-1.21.1]`，`:17 tags: ["v*"]`，`:18 pull_request:`（值为空=所有 PR，合法 YAML）。双跑成立：head 分支在 push 列表中时，push 与 `pull_request` 各触发一次（concurrency group 是 `build-${{ github.ref }}`，push 与 PR 的 ref 不同，不会互相取消）。但 **"临时分支"用词不准**：`1018` 是真实功能分支（origin/1018 = `06743b7`"Version 0.2.3…"，经 PR #11/#12 合入 main），当前是已合并残留分支；`multi-version-build`、`neoforge-1.21.1` 同理（PR #13 已合并）。真实问题应是"已合并分支仍留在常驻触发列表" | 低 |
| L10 | 低 | `gradle/wrapper/gradle-wrapper.properties:3`；`build.yml:34,36,45,60,62,72,88,90` | wrapper 9.7.1 无 distributionSha256Sum；actions 用主版本 tag 不是 SHA；gradle-wrapper.jar 入库不校验 | **确认** | `gradle-wrapper.properties` 全文 7 行：`:3 distributionUrl=…gradle-9.7.1-bin.zip`，仅有 `distributionBase/Path`、`networkTimeout`、`validateDistributionUrl`、`zipStore*`，**无 `distributionSha256Sum`**（审查写的 `:3` 即 distributionUrl 行，正确）。actions 引用：`actions/checkout@v7`（34/60/88）、`actions/setup-java@v6`（36/62/90）、`actions/upload-artifact@v7`（45/72）——全是可变主版本 tag，未钉 commit SHA。`gradle/wrapper/gradle-wrapper.jar` 已入库（枚举可见），无校验/无 wrapper-validation 工作流 | 低 |
| L11 | 疑点 | `build.yml:96,105-110`；`build.gradle.kts:33-42` | 发布物无签名/校验和；`*.jar` glob 可能吞入变体 jar；`gh release create` 不幂等 | **确认**（风险为前瞻性） | `build.gradle.kts:35-40` `assembleMods` 从两个 `layout.buildDirectory.dir("libs")` 各 `include("*.jar")`，`:41 into(build/mods)`；`build.yml:105 assets=(cli/build/distributions/*.zip build/mods/*.jar)`；全仓库构建脚本 grep `signing|sourcesJar|withSourcesJar|checksum|sha256` **零命中** → 无签名、无 SHA256SUMS、无 provenance/attestation。当前每个变体 `libs/` 只有一个主 jar（未注册 `withSourcesJar`），所以"吞入变体 jar"目前不会发生，属未来风险。"不幂等"同 B1。**发布物缺少校验和是实质问题**（handoff:635 已证明哈希不可跨平台比，更需要独立校验文件） | 疑点→**低/中** |
| L12 | 信息 | `THIRD-PARTY-NOTICES.md:33`、`gradle/libs.versions.toml:4`、`LICENSES/**` | 与 toml/LICENSES 对齐良好（gson 2.11.0 等），手工维护、无 CI 绑定 | **确认** | `THIRD-PARTY-NOTICES.md:33 gson 2.11.0` 与 `libs.versions.toml:4` 一致；`LICENSES/` 递归：`Apache-2.0.txt`、`LicenseRef-CCPL.txt`、`MPL-2.0.txt` + `third-party/{cobalt-MIT,jzlib-BSD-3-Clause,checker-qual-MIT,slf4j-MIT,netty-NOTICE}`，与表格引用逐项对得上（cobalt 0.9.9 在 `mod/forge/build.gradle.kts:65`）。无 junit 行是合理的（仅 test scope）。"无 CI 绑定"成立——没有任何 task/CI 步校验 notices 与实际依赖一致；`gradle` 也无 license 插件 | 信息（保持） |
| L13 | 信息 | `scripts/smoke-test.sh`、`scripts/smoke-test.ps1` | 两侧用例集合对等，差集仅 `mcp-via-cmd`（Windows-only） | **确认** | sh：`check` 19 条（version、args-with-space-and-separator、runtime-error、syntax-error、fixture-wired-network-monitor、trace-latest、screen-latest-monitor、redstone-timeline-terminate、scrolled-output、timeout、tick-rate、too-long-without-yielding、path-with-space-and-cjk、utf8-stdout、usage-error、missing-program、startup-time、guide、memory-limit）+ `old-java-switch`（`:112` 无 Java 8 时 SKIP）+ `mcp-stdio`（`:115-136`）=21。ps1：`$cases` 20 条（`:121-162`，含 `old-java-switch` 的 `SkipIf`）+ `mcp-stdio` + `mcp-via-cmd`（`:251` 仅 `$onWindows`）= Windows 22 / 非 Windows 21。逐名比对差集确实只有 `mcp-via-cmd` | 信息 |
| DOC1（含 DOC4） | 文档 | `docs/test-results.md:11,72`；`README.md:124`；`handoff.md:15` | 文档已实际漂移：test-results 停在 0.2.2"54/54"，README 为 0.2.3"81/81"；三处手工同步同一事实 | **确认** | 三处数字：`docs/test-results.md:11 单元测试 | 54/54`（`:72` 又写"单元测试 54 个"，`:3`"截至 2026-10-17…V1–V13"）；`README.md:124 单元测试 81/81（engine 42、cli 30、mod 9）`；`handoff.md:15 status … "41/41 tests (engine 28, cli 13)"` → 是**三方 41/81/54 漂移**，比审查描述更严重（还多一个 41）。静态统计验证 81：engine 42（RunnerTest 18 + PeripheralTest 11 + FixtureTest 5 + TraceQueryTest 4 + TickPacerTest 2 + TraceTest 2）、cli 30（LiveToolsTest 12 + MainTest 8 + McpServerTest 6 + JavaFinderTest 4）、mod 9（ComputerPathsTest 4 + SnapshotManifestTest 4 + Utf8Test 1），无 `@ParameterizedTest/@Disabled/@Nested`（`@Test` 计数=执行数）→ **81/81 属实**；`README.md:125`"90 次"=42+30+9×2 亦成立。另：`docs/test-results.md` 会被打进发布包（`cli/build.gradle.kts:53` include `docs/test-results.md`）→ 陈旧数字随 0.2.3 发布物出厂 | 文档 |
| DOC2 | 文档 | `handoff.md`（127,670 B）；`README.md:115`；`docs/building.md:54` | 过程记录放公开仓库根，却被 README 引用为面向用户的 LIMITATIONS 出处；建议抽 docs/limitations.md | **确认** | `handoff.md` 实测 127,670 B（≈124.7 KiB / 127.7 KB，"约 127KB"成立）。`handoff.md:5 audience: machine reviewer / next agent (not end users)`、`:16 "README.md (user-facing, Chinese); this file is the machine-oriented record"`。但 `README.md:115`"完整的差异清单见 `handoff.md` 的 LIMITATIONS 和 DIVERGENCES 两节"（两节确实存在：`handoff.md:380 LIMITATIONS`、`:413 DIVERGENCES`），`docs/building.md:54` 也指向 `../handoff.md` → 对外文档实质依赖内部记录 | 文档 |
| DOC3 | 文档 | `README.md`、`docs/`（16 个 .md + images/4 svg）、`mod/README.md`（22,420 B） | 受众混杂；mod README 与根 README 数千字重复；joint-test 四份 + live-test-mplayer 属内部记录，与 install/usage 混在 docs/ | **部分确认** | docs/ 清单：agent-guide 27889、building 2981、install 5593、joint-test-factory-report 25718、joint-test-factory 9202、joint-test-orders-report 12414、joint-test-orders 16204、joint-test 4301、live-test-mplayer 5935、release-notes 0.1.0/0.2.0/0.2.2/0.2.3、test-results 8203、testing 8973、usage 4548 + images/4 → **受众混杂成立**（面向用户：install/usage/building/testing/agent-guide/release-notes；内部：5 份 joint-test*、live-test-mplayer、test-results）。**"joint-test 四份"少算**：实为 5 份（另有总纲 `joint-test.md`）。**"数千字重复"不成立**：`difflib` 全文字符相似度 0.093，≥20 字符的连续匹配块仅 6 块共 274 字符；按去空行/去短行比较，相同长行仅 3 行 107 字符。真实情况是同一事实两处复述（性能表数字、实机测试结论）且措辞不同，非逐段复制 | 文档（描述修正） |
| DOC5 | 文档 | `README.md:74,77,115,127-128`；`docs/test-results.md:14,81`；`cli/src/launcher/java/cctrun/cli/JavaFinder.java:141-143,165-167` | macOS 从未测试，`bin/cct-run` 与 JavaFinder macOS 分支无实测证据；建议显著标注 | **部分确认** | **macOS 未测已标注**：`README.md:128 | macOS | 尚未测试`、`docs/test-results.md:14` 与 `:81` 同；但 `README.md:74-84` 安装节直接给出 `bin/cct-run`（macOS/Linux）命令，未就地提示未测 → "建议显著标注"仍有空间，措辞应是"已在测试表标注、安装节未标注"。**仓库内无 `bin/`**（`Test-Path bin` = False）——`bin/cct-run`、`bin/cct-run.bat` 由 Gradle `application` 插件生成在发布 zip 里（`cli/build.gradle.kts:40-46`），对解压后的发布包成立，不是仓库路径。JavaFinder 实际在 `cli/src/launcher/java/cctrun/cli/JavaFinder.java`（187 行，**不在** `cli/src/main/java/…`）：`:141-143` 处理 macOS bundle 的 `Contents/Home`，`:165-167 os.contains("mac")` 加 `/Library/Java/JavaVirtualMachines` 与 `~/Library/Java/JavaVirtualMachines` → 分支存在、无 mac 实机验证记录 | 文档（描述修正） |

## README 关键数字核对（额外要求 2）

| 数字 | README 位置 | 代码/构建证据 | 结论 |
|---|---|---|---|
| 20 个 MCP 工具（4 模拟器 + 16 实机） | `README.md:17` | `cli/src/main/java/cctrun/cli/McpTools.java` 的 `"name"`: cct_run/cct_trace/cct_screen/cct_guide（4）；`LiveTools.java`: cct_live_computers/status/wait/screen/attach/detach/trace/type/click/files/lua/body/event/snapshot/peripheral/power（16）→ 共 20 | **属实** |
| 81 个 @Test（engine 42 / cli 30 / mod 9） | `README.md:124` | `grep -c '@Test'` 逐文件：engine 42、cli 30、mod 9，合计 81；无参数化/禁用测试 | **属实** |
| 90 次（含两 mod 变体各 9） | `README.md:125` | 42+30+9×2=90（同一份 `mod/src/common/test` 被两个变体各跑一遍，见两脚本 `sourceSets.test java.srcDir(mod/src/common/test/java)`） | **属实** |
| 内存上限约 1 GB，`out_of_memory`/退出码 4 | `README.md:35` | `cli/build.gradle.kts:45 applicationDefaultJvmArgs … "-Xmx1g"`；`Main.java:59`；`Runner.java:133,336-346`；`RunResult.java:13`；`McpTools.java:33` | **属实**（`-Xmx1g` 可被 `JAVA_OPTS`/`CCT_RUN_OPTS` 覆盖，README 已说明） |
| 任意格子任意物品、上限 64 | `README.md:108` | `engine/.../Fixture.java:365 new Inventory(size, 64)`、`:421-422` maxCount/count 限 1..64；`peripheral/Inventory.java:14,25-26,67`；`InventoryPeripheral.java:36 getItemLimit` | **属实** |
| Linux CI "engine 42 + cli 30" | `README.md:126` | `build.yml:4` 注释 "72 tests"、`:42 ./gradlew build`；`docs/building.md:36` 表同 | **属实** |

## 偏差 / 修正

行号偏差（审查报告行号以重构后为准，下列为实测值）：

1. **H3**：badge 在 `README.md:3`（审查写 `:5`）。其余行号 `build.yml:42,68,96` 精确。
2. **B3**：catalog 里 `gson = "2.11.0"` 在 `gradle/libs.versions.toml:4`，不是 `:6`（`:6` 是 `slf4j = "2.0.16"`）。两份 mod 脚本的 `:70`/`:67` 精确。
3. **B5**：`.gitattributes:1-2` 精确；`handoff.md:635` 精确；发布包打包 `scripts/**` 的实际依据是 `cli/build.gradle.kts:53`（审查未给，建议补）。
4. **L10**：`gradle-wrapper.properties:3` 即 `distributionUrl` 行，精确。
5. **DOC1**：`docs/test-results.md` 的 54/54 在 `:11`（另 `:72` 重述）；`README.md` 的 81/81 在 `:124`；`handoff.md` 还有第三个数 **41/41 在 `:15`**（审查未提，漂移实为三方三个数）。
6. **DOC2**：`handoff.md` 为 127,670 B；README 引用在 `:115`；另 `docs/building.md:54` 也引用。
7. **DOC5**：`JavaFinder` 实际路径 `cli/src/launcher/java/cctrun/cli/JavaFinder.java`（审查未给路径）；`bin/` 在仓库中不存在。
8. **H4**：`Versions.java:8` 精确；但"gson 2.10.1 vs 2.11.0 是该风险的现实版本"属归因偏差——gson 不一致是依赖声明不一致（B3），不是"打包版本↔运行时自报版本"不一致；版本传播风险当前**未实际发生**（build.gradle.kts、Versions.java、README 三处都是 0.2.3），属前瞻风险。

描述准确性偏差：

9. **B1**："tests job 上传的 mod jar 从未被 release 复用"——上传 mod jar 的是 `mod` job（`build.yml:71-77`），tests job 仅在失败时上传测试报告；正确说法是"`mod` job 上传的 `cct-live-*` artifact 从未被 release job 下载复用"。
10. **B4**：重复度实测 **80.5%（66/82 行）**，不是"约 70%"；"1.120.2 硬编码四处"不成立，两份脚本各 1 处，加 catalog 共 3 处。
11. **L9**："临时分支 1018"不准确——它是已合并的真实功能分支（origin/1018=06743b7），问题在于合并后仍留在常驻触发列表；`pull_request` 空值=匹配所有 PR，"双跑"成立（concurrency group 按 ref 区分，不会互相取消）。
12. **DOC3**："mod README 与根 README 数千字重复"不成立（相似度 0.093、连续重复仅 274 字符）；"joint-test 四份"应为 5 份。
13. **DOC5**：macOS 未测**已在** `README.md:128` 与 `docs/test-results.md:14,81` 标注，缺的是安装节就地提示；`bin/cct-run` 是发布 zip 内由 application 插件生成的路径，不是仓库文件。

级别建议（升降）：

14. **H3 高→中**：CI 无 smoke/无供应链 job 属实，但 `docs/building.md:42`、`handoff.md:411`（L30）、`handoff.md:679`（N16）已把"CI 只做构建+单测、未覆盖实机/smoke、badge 待合并"写成已知限制与后续项，属已披露差距而非隐藏缺陷。
15. **H4 高→中**：版本链当前自洽，风险是"无 tag↔version 校验 + 唯一版本测试读不到 build.gradle.kts"；补一条 `test "${GITHUB_REF_NAME#v}" = "$(./gradlew -q properties | ...)"` 式校验＋让版本号单点化即可关闭，成本极低。
16. **L11 疑点→低/中**：签名/校验和缺失是实质改进点（尤其 handoff:635 已证明跨平台哈希不可比，更需要独立 `SHA256SUMS`）；"`*.jar` glob 吞 jar"当前不会发生（无 sourcesJar）。
17. **新增（NEW）建议条目**：
    - **NEW-1（中，DOC1 延伸）** `docs/test-results.md` 被 `cli/build.gradle.kts:53` 打进发布包，却仍写 54/54、截至 2026-10-17、引用 handoff V1–V13（handoff 已到 V34）→ 随 0.2.3 发布物出厂的是过期测试记录。
    - **NEW-2（低，DOC1 延伸）** `handoff.md:15 status` 写 41/41（engine 28, cli 13），与当前 81 相差两代；handoff 是"machine reviewer"入口，该行会直接误导后续审查者。
    - **NEW-3（中，H4 延伸）** `v0.2.3` tag 之后 HEAD 有 9 个未发版提交而版本号仍 0.2.3：此时推任何 `v*` tag，release 资产名与 tag 名会不一致（release job 无校验）。建议在 release job 首步校验 `$GITHUB_REF_NAME` 与 `build.gradle.kts` 的 `version` 相等。
    - **NEW-4（低，L9 延伸）** `build.yml:16` 同时保留 `1018`、`multi-version-build`、`neoforge-1.21.1` 三个已合并分支，`push` 会持续为死分支烧 CI；`pull_request` 空过滤 + 这些分支会造成同 commit 双跑。
    - **NEW-5（低，B5 延伸）** `.gitattributes` 自身与 `*.toml` 资源在工作区是 CRLF（`git ls-files --eol` 显示 `w/crlf attr/`），而 index 为 LF；缺 `* text=auto` 意味着跨平台工作树行尾依赖各机器 `core.autocrlf`，这正是 `pack.mcmeta` 哈希不可比的根因，建议加 `* text=auto` + `*.sh text eol=lf` + `*.toml text eol=lf`（或对 `processResources` 规范化行尾）。
    - **NEW-6（信息）** `build.yml` 的 3 个 job 都用 `actions/checkout@v7 / setup-java@v6 / upload-artifact@v7` 主版本 tag，且无 `wrapper-validation-action`；与 L10 同源，建议合并为一条供应链加固项。
    - **NEW-7（信息）** 审查所称 `build-ci.md` 在 `D:\AI\DSH\cc-t` 全盘未找到（仅 `review-report.md` 0.2.2 版），本 lane 以任务卡转述为基准；若后续报告存在，H3/H4 的"高"定级可能需按同一份来源复核。

## absence 证据附录（可复现命令）

```
PS> Get-ChildItem .github -Recurse -Force            # → .github\workflows\build.yml（唯一）
PS> Get-ChildItem gradle -Recurse -Force             # → libs.versions.toml, wrapper\{gradle-wrapper.jar,gradle-wrapper.properties}
PS> <config extensions>.grep('smoke')                 # → 0 命中（.yml/.yaml/.toml/.properties/.kts，排除 .git/build/.gradle）
PS> Test-Path bin                                    # → False
PS> Get-ChildItem LICENSES -Recurse -Force           # → 3 个 txt + third-party/ 5 个 txt
PS> git ls-files --eol scripts/smoke-test.sh         # → i/lf w/crlf attr/
PS> (git rev-parse HEAD; git describe --tags)        # → bf68ac4…; v0.2.3-9-gbf68ac4
```

> 全部核对为静态证据；`gh release create` 幂等性、release job 端到端、macOS 运行属行为类，未运行验证（任务要求不跑 gradlew，且无凭据/无 mac 环境）。建议级别一栏中"高→中"等仅为本 lane 的建议，最终定级由 lead 汇总。

## AI-ISSUES

- id: H3
  verdict: confirmed
  severity_report: 高
  severity_suggested: medium
  title: CI 三个 job 只构建与单测，smoke 与供应链检查完全未接入
  module: build-ci
  files: [".github/workflows/build.yml:42", ".github/workflows/build.yml:68", ".github/workflows/build.yml:96", "README.md:3", "README.md:127", "docs/building.md:42"]
  evidence: "build.yml 110 行仅 3 个 job（:42 build、:68 :mod-<变体>:build、:96 :cli:distZip assembleMods）；配置类文件 grep smoke 零命中；.github 递归仅 workflows/build.yml；README:127 写 0.2.3 未重跑；badge 在 README:3 钉 multi-version-build"
  impact: 冒烟测试与安全/依赖回归无法被 CI 拦住，发布质量依赖人工
  fix: 增加 smoke job（sh/ps1）与 CodeQL、dependency-review、wrapper-validation、dependabot，合并后 badge 切回 main
  tests: none
  source_ids: [构建报告#1]
- id: H4
  verdict: confirmed
  severity_report: 高
  severity_suggested: medium
  title: 版本号分散在 build.gradle.kts 与 Versions.java 两处且 release 不校验 tag
  module: build-ci
  files: ["build.gradle.kts:5", "engine/src/main/java/cctrun/engine/Versions.java:8", "engine/src/test/java/cctrun/engine/RunnerTest.java:35", "handoff.md:10", ".github/workflows/build.yml:104"]
  evidence: "打包名与 mods.toml 的 ${version} 均来自 build.gradle.kts:5；运行时自报来自 Versions.java:8；唯一版本测试 RunnerTest.java:35 只硬编码运行时串、读不到构建脚本；release job 无 tag↔version 比较"
  impact: 版本号漏改时发布包、运行时自报与 tag 名可能互相不一致
  fix: release 首步校验 GITHUB_REF_NAME 与 build.gradle.kts 的 version 相等，并让版本号收敛到单点（由构建注入 Versions）
  tests: RunnerTest.java:35 只锁 Versions.java 的 TOOL/CC_TWEAKED 字面量
  source_ids: [构建报告#2]
- id: B1
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: release job 重新检出重建，且不下载已上传的 mod artifact
  module: build-ci
  files: [".github/workflows/build.yml:82", ".github/workflows/build.yml:88", ".github/workflows/build.yml:96", ".github/workflows/build.yml:107"]
  evidence: "needs: [tests, mod] 但全文无 actions/download-artifact；release 重新 checkout 并重新 :cli:distZip assembleMods；上传 jar 的是 mod job 而非 tests job；gh release create 前无 gh release view 判断"
  impact: 发布物与 CI 测过的产物不是同一批文件，重跑同 tag 会失败
  fix: release 改为下载 mod artifact 复用，并在创建前检查 release 是否已存在（或 gh release upload --clobber）
  tests: none
  source_ids: [构建报告#5]
- id: B2
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 缺 CodeQL、dependency-review、wrapper-validation、dependabot 与 verification-metadata
  module: build-ci
  files: [".github/workflows/", "gradle/wrapper/gradle-wrapper.properties:3"]
  evidence: ".github 递归仅 workflows/build.yml；gradle 递归仅 libs.versions.toml 与 wrapper/{jar,properties}；全仓库无 dependabot.yml、codeql.yml、dependency-review.yml、gradle/verification-metadata.xml"
  impact: 依赖与构建脚本的供应链风险没有自动检查
  fix: 按需补 dependabot、wrapper-validation-action、dependency-review-action，并对 Gradle 依赖生成 verification-metadata
  tests: none
  source_ids: [构建报告#6]
- id: B3
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: 两份 mod 构建脚本绕过 version catalog 手写 gson 2.10.1
  module: build-ci
  files: ["mod/forge/build.gradle.kts:70", "mod/neoforge/build.gradle.kts:67", "gradle/libs.versions.toml:4", "THIRD-PARTY-NOTICES.md:33"]
  evidence: "两份脚本 testImplementation gson 2.10.1；catalog 的 gson 2.11.0 在 libs.versions.toml:4（:6 是 slf4j），THIRD-PARTY-NOTICES:33 亦 2.11.0；junit 5.11.4 同样手写"
  impact: 依赖版本两处真源，升级时 mod 测试类路径可能静默落后
  fix: 两份 mod 脚本改用 libs.versions.toml 的 gson/junit，或至少加注释与对齐检查
  tests: test scope 不进发布物（均为 testImplementation），已静态确认
  source_ids: [构建报告#3]
- id: B4
  verdict: partial
  severity_report: 中
  severity_suggested: medium
  title: 两份 mod 构建脚本约八成逐行重复（审查称 70%、1.120.2 四处均不准）
  module: build-ci
  files: ["mod/forge/build.gradle.kts:1", "mod/neoforge/build.gradle.kts:1"]
  evidence: "difflib：forge 82 行、neoforge 79 行，完全相同 66 行 = 80.5%/83.5%，差异 29 行（插件、MC/loader、release 17↔21、loader 配置块、cobalt、metadata 文件名）；1.120.2 每份各 1 处，加 catalog 共 3 处"
  impact: 双份脚本的公共逻辑（sourceSets、repositories、JVM 参数、test 配置）易漂移
  fix: 抽 gradle/mod-variant.gradle.kts 或 buildSrc 约定插件，两变体只留差异字段
  tests: none
  source_ids: [构建报告#4]
- id: B5
  verdict: confirmed
  severity_report: 中
  severity_suggested: medium
  title: .gitattributes 无 text=auto，smoke-test.sh 无 eol=lf 保护且随发布包出厂
  module: build-ci
  files: [".gitattributes:1", ".gitattributes:2", "cli/build.gradle.kts:53", "handoff.md:635"]
  evidence: ".gitattributes 仅 2 行（gradlew eol=lf、*.bat eol=crlf）；git ls-files --eol 显示 smoke-test.sh 为 i/lf w/crlf attr/；distZip 第 53 行 include scripts/**；handoff:635 实测 Linux/Windows jar 仅 pack.mcmeta 行尾不同、哈希不可比"
  impact: 跨平台工作树行尾依赖 core.autocrlf，产物哈希不可复现，脚本可能带 CRLF 出厂
  fix: 加 * text=auto、*.sh text eol=lf、*.toml text eol=lf，并对 processResources 规范化行尾
  tests: none
  source_ids: [构建报告#7]
- id: L8
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: 两份 smoke 脚本默认把报告写到当前目录，.gitignore 未覆盖
  module: build-ci
  files: ["scripts/smoke-test.sh:26", "scripts/smoke-test.ps1:28", ".gitignore:1"]
  evidence: "sh 第 26 行 report=$PWD/cct-run-smoke-report.txt；ps1 第 28 行 $Report 默认 cct-run-smoke-report.json（相对 CWD）；.gitignore 7 行无该模式"
  impact: 在仓库根运行时产生未跟踪文件，容易误提交
  fix: 报告默认落到 --out 或临时目录，并把 cct-run-smoke-report.* 加进 .gitignore
  tests: none
  source_ids: []
- id: L9
  verdict: partial
  severity_report: 低
  severity_suggested: low
  title: push 触发列表含已合并分支，pull_request 无过滤会与 push 双跑
  module: build-ci
  files: [".github/workflows/build.yml:16", ".github/workflows/build.yml:18", ".github/workflows/build.yml:22"]
  evidence: "branches: [main, 1018, multi-version-build, neoforge-1.21.1]；1018 是已合并真实分支（origin/1018=06743b7）而非临时分支；pull_request 空值匹配所有 PR；concurrency group 按 ref 区分，push 与 PR 不互相取消"
  impact: 为已合并分支持续消耗 CI，同 commit 可能双跑
  fix: 删掉已合并分支只留 main，并给 pull_request 加 branches: [main]
  tests: none
  source_ids: [B5b]
- id: L10
  verdict: confirmed
  severity_report: 低
  severity_suggested: low
  title: wrapper 9.7.1 无 distributionSha256Sum，actions 用可变主版本 tag
  module: build-ci
  files: ["gradle/wrapper/gradle-wrapper.properties:3", ".github/workflows/build.yml:34", ".github/workflows/build.yml:36", ".github/workflows/build.yml:45"]
  evidence: "properties 仅 7 行，无 distributionSha256Sum；checkout@v7、setup-java@v6、upload-artifact@v7 均为可变主版本 tag；gradle-wrapper.jar 已入库且无校验"
  impact: 构建输入（Gradle 发行版与 actions）可被上游标签移动而改变
  fix: 加 distributionSha256Sum，actions 钉 commit SHA，并启用 gradle wrapper-validation-action
  tests: none
  source_ids: []
- id: L11
  verdict: confirmed
  severity_report: 疑点
  severity_suggested: medium
  title: 发布物无签名与校验和，assets 用 *.jar 通配收集
  module: build-ci
  files: ["build.gradle.kts:36", "build.gradle.kts:39", ".github/workflows/build.yml:105", ".github/workflows/build.yml:96"]
  evidence: "assembleMods 对两个 libs 目录各 include(*.jar)；release assets=(cli/build/distributions/*.zip build/mods/*.jar)；全仓库构建脚本 grep signing/sourcesJar/checksum 零命中；当前每变体仅一个主 jar，暂无被吞的变体 jar"
  impact: 下载者无法校验发布物完整性，未来加 sourcesJar 后会被静默带上
  fix: 生成 SHA256SUMS 作为 release asset 并考虑签名；glob 改为显式文件名或排除 -sources/-javadoc
  tests: none
  source_ids: []
- id: L12
  verdict: confirmed
  severity_report: 信息
  severity_suggested: info
  title: THIRD-PARTY-NOTICES 与 catalog/LICENSES 对齐良好但纯手工、无 CI 绑定
  module: build-ci
  files: ["THIRD-PARTY-NOTICES.md:33", "gradle/libs.versions.toml:4", "LICENSES/"]
  evidence: "gson 2.11.0、cobalt 0.9.9 等与 catalog 及 LICENSES/third-party 逐项对得上；无 junit 行属合理（测试 scope）；无插件或 CI 步骤校验其与实际依赖一致"
  impact: 依赖升级后 notices 可能静默过期
  fix: 用 Gradle 依赖报告生成或校验 notices（license 插件 + CI diff 检查）
  tests: none
  source_ids: []
- id: L13
  verdict: confirmed
  severity_report: 信息
  severity_suggested: info
  title: 两份 smoke 脚本用例集合对等，差集仅 Windows 专属的 mcp-via-cmd
  module: build-ci
  files: ["scripts/smoke-test.sh:77", "scripts/smoke-test.ps1:121", "scripts/smoke-test.ps1:251"]
  evidence: "sh 19 个 check + old-java-switch(SKIP) + mcp-stdio = 21；ps1 $cases 20 + mcp-stdio（+ 仅 Windows 的 mcp-via-cmd）= 21/22；逐名比对差集仅 mcp-via-cmd"
  impact: 无实质问题，仅用例命名与执行环境差异需知悉
  fix: none
  tests: none
  source_ids: []
- id: DOC1
  verdict: confirmed
  severity_report: 文档
  severity_suggested: medium
  title: 测试数字三处各说一套（54/54、81/81、41/41）且手工同步
  module: docs
  files: ["docs/test-results.md:11", "docs/test-results.md:72", "README.md:124", "handoff.md:15"]
  evidence: "test-results 仍写 54/54 与截至 2026-10-17、V1–V13；README:124 写 81/81（engine 42/cli 30/mod 9）；handoff:15 status 写 41/41（engine 28/cli 13）；静态 @Test 计数精确 81，无参数化/禁用"
  impact: 读者无法判断哪个数字可信，过期记录还随发布包出厂
  fix: 以一次测试运行产出的数字为单一来源（脚本生成 test-results 表），README/handoff 引用而非手抄
  tests: 静态 @Test 计数 42+30+9=81 已核实
  source_ids: [DOC4]
- id: DOC2
  verdict: confirmed
  severity_report: 文档
  severity_suggested: low
  title: 内部过程记录 handoff.md 被对外文档当作 LIMITATIONS 出处
  module: docs
  files: ["handoff.md:5", "handoff.md:380", "handoff.md:413", "README.md:115", "docs/building.md:54"]
  evidence: "handoff.md 127670 B，第 5 行自述 audience 为 machine reviewer/next agent（not end users）；README:115 让用户去看其 LIMITATIONS/DIVERGENCES 两节（:380/:413 确实存在）；docs/building.md:54 也指向它"
  impact: 面向用户的差异清单藏在 127KB 内部记录里，用户难以阅读且易与实现脱节
  fix: 抽 docs/limitations.md 作为用户向单一出处，handoff 保留机器记录并引用它
  tests: none
  source_ids: []
- id: DOC3
  verdict: partial
  severity_report: 文档
  severity_suggested: low
  title: docs 受众混杂属实，但 mod/README 与根 README 并非数千字重复（审查夸大）
  module: docs
  files: ["README.md:1", "mod/README.md:1", "docs/joint-test.md:1", "docs/live-test-mplayer.md:1"]
  evidence: "docs 有 16 个 md + images：用户向 install/usage/building/testing/agent-guide/release-notes，内部向 5 份 joint-test*、live-test-mplayer、test-results；difflib 全文字符相似度仅 0.093，连续重复 6 块共 274 字符、相同长行 3 行"
  impact: 内部记录与用户文档混在一处，发布包与文档目录的受众边界不清
  fix: 内部测试记录移入 docs/internal/ 或 handoff 附录，docs 只留用户向文档
  tests: none
  source_ids: []
- id: DOC5
  verdict: partial
  severity_report: 文档
  severity_suggested: low
  title: macOS 未测已在测试表标注，缺的是安装节提示；仓库无 bin/，JavaFinder 有 macOS 分支
  module: docs
  files: ["README.md:128", "docs/test-results.md:14", "README.md:77", "cli/src/launcher/java/cctrun/cli/JavaFinder.java:165"]
  evidence: "README:128 与 test-results:14/:81 均写 macOS 尚未测试，但 README:74-84 安装节直接给 macOS 命令且未就地提示；仓库无 bin/（Test-Path 为 False），bin/cct-run 由 application 插件生成在发布 zip；JavaFinder 在 cli/src/launcher/java/…，:141-143 与 :165-167 有 macOS 分支"
  impact: macOS 用户按安装节操作时不知道该路径完全未经验证
  fix: 在安装节 macOS 命令旁显式标注未测试，并将 macOS 列入已知缺口
  tests: none
  source_ids: []
- id: NEW-1
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: medium
  title: 过期测试记录 docs/test-results.md 被打进 0.2.3 发布包
  module: docs
  files: ["cli/build.gradle.kts:53", "docs/test-results.md:11", "docs/test-results.md:3"]
  evidence: "distZip 的 include 列表含 docs/test-results.md；该文件仍是 54/54、截至 2026-10-17、引用 handoff V1–V13（handoff 已到 V34）"
  impact: 发布物内附的测试记录与实际 81/81 状态矛盾
  fix: 发布前更新 test-results.md，或从 distZip 清单移除该文件直到其可自动生成
  tests: none
  source_ids: [DOC1]
- id: NEW-2
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: low
  title: handoff.md 头部 status 仍写 41/41，会误导后续审查者
  module: docs
  files: ["handoff.md:15"]
  evidence: "status 行写 41/41 tests (engine 28, cli 13)，与当前 81 相差两代；handoff 是机器审查入口"
  impact: 后续审查者若信该行会低估测试规模
  fix: 更新 status 行或改为指向 VERIFICATION 最新记录
  tests: none
  source_ids: [DOC1]
- id: NEW-3
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: medium
  title: v0.2.3 tag 之后有 9 个未发版提交而版本号仍为 0.2.3，推新 tag 会产生名不副实的资产
  module: build-ci
  files: ["build.gradle.kts:5", "engine/src/main/java/cctrun/engine/Versions.java:8", ".github/workflows/build.yml:104"]
  evidence: "git describe 为 v0.2.3-9-gbf68ac4；build.gradle.kts:5 与 Versions.java:8 仍 0.2.3；release job 只用 GITHUB_REF_NAME 命名且不校验版本，故推 v0.2.4 会发布 cct-run-0.2.3.zip"
  impact: release 资产名与 tag 不一致，用户可能下错版本
  fix: release 首步比较 tag 与构建中的 version，不等则失败
  tests: none
  source_ids: [H4, B1]
- id: NEW-4
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: low
  title: 三个已合并分支仍留在 push 触发列表，PR 触发无分支过滤
  module: build-ci
  files: [".github/workflows/build.yml:16", ".github/workflows/build.yml:18"]
  evidence: "列表含 1018、multi-version-build、neoforge-1.21.1，三者均已通过 PR #11/#12/#13 合入 main；pull_request 空过滤匹配全部 PR"
  impact: 持续为死分支跑 CI，同 commit 可能 push+PR 双跑
  fix: 只保留 main 并给 pull_request 加 branches: [main]
  tests: none
  source_ids: [L9]
- id: NEW-5
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: low
  title: 工作区行尾依赖 core.autocrlf 是跨平台哈希不可比的根因
  module: build-ci
  files: [".gitattributes:1", "mod/forge/src/main/resources/META-INF/mods.toml:1", "handoff.md:635"]
  evidence: "git ls-files --eol 显示 *.toml 资源与 smoke-test.sh 为 i/lf w/crlf 且 attr 为空，只有 gradlew 有 text eol=lf；.gitattributes 无 * text=auto"
  impact: 不同机器的 pack.mcmeta 行尾不同，导致同样的源码产出不同字节的 jar
  fix: 加 * text=auto 与关键类型的 eol 规则（.sh/.toml eol=lf），并在 processResources 统一行尾
  tests: none
  source_ids: [B5]
- id: NEW-6
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: info
  title: actions 全部使用主版本 tag 且无 wrapper 校验工作流（与 L10 同源）
  module: build-ci
  files: [".github/workflows/build.yml:34", ".github/workflows/build.yml:36", ".github/workflows/build.yml:45", ".github/workflows/build.yml:60"]
  evidence: "checkout@v7、setup-java@v6、upload-artifact@v7 共 8 处；无 wrapper-validation-action，gradle-wrapper.jar 入库无校验"
  impact: 供应链输入随上游标签漂移
  fix: 钉 commit SHA + 启用 wrapper-validation + distributionSha256Sum
  tests: none
  source_ids: [L10]
- id: NEW-7
  verdict: cannot-static
  severity_report: 新发现
  severity_suggested: info
  title: 审查所称 build-ci.md 在磁盘不存在，本 lane 基准是任务卡转述
  module: project
  files: ["evidence/lanes/task-cards/E-build-docs.md:7"]
  evidence: "D:/AI/DSH/cc-t 全盘（排除 .git、CC-Tweaked_MCP、.graphflow）未找到 build-ci.md；仅有 review-report.md 为 0.2.2 期报告（B0–B7/M/L、54/54）"
  impact: H3/H4 的高定级无原文可对照，只能按任务卡摘要复核
  fix: 若后续找到原始构建审查报告，按其原文复核 H3/H4 定级与行号
  tests: none
  source_ids: []
- id: NEW-8
  verdict: confirmed
  severity_report: 新发现
  severity_suggested: info
  title: 文档类审查描述修正后的准确事实
  module: docs
  files: ["README.md:3", "README.md:124", "docs/test-results.md:11", "handoff.md:15", "mod/README.md:1", "docs/joint-test.md:1"]
  evidence: "badge 在 README:3；测试数字为 41/81/54 三方；mod README 与根 README 相似度 0.093、非数千字重复；joint-test 共 5 份；macOS 未测已标注；仓库无 bin/（由 application 插件生成）；README 的 20 工具/81 测试/1GB/64 四项均属实"
  impact: 按修正后事实归类文档问题，避免把已标注项或不存在项当新缺陷
  fix: 报告与后续修复清单采用修正后的行号与数字，删除不成立的重复度与四份 joint-test 说法
  tests: none
  source_ids: [DOC1, DOC3, DOC5]
