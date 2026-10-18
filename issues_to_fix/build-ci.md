# build / CI issues

基线：`bf68ac4`（项目版本 0.2.3；`.github/workflows/build.yml` 为 110 行单 workflow）。
字段定义见 `README.md`；只收录核对后确实存在且未修复的问题。

---

## BLD-01 — CI 只跑单元测试，smoke 与供应链检查未接入

```yaml
id: BLD-01
title: "CI 三个 job 只构建与单测；smoke-test.sh/ps1 完全未接入；无 CodeQL/dependency-review/wrapper-validation/dependabot"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-H3]
files:
  - path: .github/workflows/build.yml
    lines: "42, 68, 96"
  - path: README.md
    lines: "3, 127"
  - path: docs/building.md
    lines: "42"
evidence: |
  build.yml 110 行仅 3 个 job（:42 build、:68 :mod-<variant>:build、:96 :cli:distZip assembleMods）；
  全仓库 yml/yaml/kts/toml/properties grep smoke 零命中；.github 递归仅 workflows/build.yml；
  README:127 写 0.2.3 冒烟未重跑；badge 在 README:3 钉 multi-version-build 而非 main；
  handoff.md:411（L30）、docs/building.md:42 已自述该限制。
impact: 冒烟（MCP stdio 会话、超时、内存上限、Java 8 切换）与安全/依赖回归无法被 CI 拦住，发布质量依赖人工；
  项目已有真实安全修复史（0.2.3 修路径逃逸），供应链敏感度高于普通项目。
fix: 1) tests 通过后跑 sh 版 smoke 作为 release 门禁；2) 加 gradle/actions/wrapper-validation + PR dependency-review；
  3) 加 CodeQL/dependabot；4) badge 切回 main。
tests: none
```

## BLD-02 — 版本号双源，release 不校验 tag

```yaml
id: BLD-02
title: "打包版本在 build.gradle.kts:5，运行时自报在 Versions.java:8；release job 不校验 tag↔version"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-H4]
files:
  - path: build.gradle.kts
    lines: "5"
  - path: engine/src/main/java/cctrun/engine/Versions.java
    lines: "8"
  - path: engine/src/test/java/cctrun/engine/RunnerTest.java
    lines: "35"
  - path: handoff.md
    lines: "10"
  - path: .github/workflows/build.yml
    lines: "96-110"
evidence: |
  打包链（zip 名、mod jar、mods.toml 的 ${version}）全部源于 build.gradle.kts:5 version="0.2.3"；
  运行时自报（MCP initialize 的 info.version、summary、version 命令）源于 Versions.java:8 TOOL="0.2.3"；
  唯一的版本测试 RunnerTest.java:35 只硬编码运行时字符串，与 Gradle version 无关联；
  handoff.md:10 把 Versions.java 记为 tool 版本源；
  release job（build.yml:96-110）重新构建后用 `gh release create "$GITHUB_REF_NAME"` 命名发布，无 tag↔version 比较。
  现实版本风险见 BLD-14。
impact: 只 bump 一处会发布「zip 叫 0.2.4、运行时自报 0.2.3」的产物；推任意 v* tag 都会照常发布。
fix: 版本单源化（Gradle 从 Versions.java 反解，或构建把 version 注入 Versions/资源）；release 首步校验
  tag == Gradle version == Versions.TOOL，不等则失败。
tests: RunnerTest.java:35 只锁 Versions.java 字面量。
```

## BLD-03 — release job 重新检出重建

```yaml
id: BLD-03
title: "release job 重新 checkout 重建全部资产，不下载 tests/mod job 的 artifact；gh release 不幂等"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-B1]
files:
  - path: .github/workflows/build.yml
    lines: "82, 88, 96, 104-110"
evidence: |
  release needs: [tests, mod]，但全文无 actions/download-artifact；release 重新 checkout 并重新 :cli:distZip assembleMods；
  mod jar 由 mod job 上传、从未被 release 复用；gh release create 前无存在性判断。
impact: 发布物与 CI 测过的产物不是同一批文件（依赖无锁定时两次构建可能漂移）；重跑同 tag 会失败。
fix: release 下载 mod artifact 复用；创建前检查 release 是否存在或改用 gh release upload --clobber。
tests: none
```

## BLD-04 — 无安全扫描/依赖审计/wrapper 校验

```yaml
id: BLD-04
title: "无 CodeQL、dependency-review、wrapper-validation、dependabot、gradle/verification-metadata.xml"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-B2]
files:
  - path: .github/
    lines: "workflows/build.yml 为唯一文件"
  - path: gradle/wrapper/gradle-wrapper.properties
    lines: "3"
evidence: absence 证据：.github 递归仅 build.yml；gradle 递归仅 libs.versions.toml 与 wrapper/{jar,properties}；全仓库无 dependabot.yml/codeql.yml/dependency-review.yml/verification-metadata.xml；依赖下载无校验和。
impact: 依赖与构建脚本的供应链风险无自动检查。
fix: 补 dependabot、wrapper-validation-action、dependency-review-action；生成 Gradle verification-metadata；wrapper 加 distributionSha256Sum。
tests: none
```

## BLD-05 — mod 构建脚本绕过 version catalog 手写 gson 2.10.1

```yaml
id: BLD-05
title: "两份 mod 构建脚本 testImplementation gson 2.10.1，catalog/THIRD-PARTY-NOTICES 为 2.11.0"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-B3]
files:
  - path: mod/forge/build.gradle.kts
    lines: "70"
  - path: mod/neoforge/build.gradle.kts
    lines: "67"
  - path: gradle/libs.versions.toml
    lines: "4"
  - path: THIRD-PARTY-NOTICES.md
    lines: "33"
evidence: 两份脚本 testImplementation gson 2.10.1；catalog 的 gson 2.11.0 在 libs.versions.toml:4（:6 是 slf4j）；THIRD-PARTY-NOTICES:33 亦 2.11.0；junit 5.11.4 同样手写坐标而非 catalog。
impact: 依赖版本两处真源，升级时 mod 测试类路径可能静默落后；catalog 机制对 mod 子工程失效。
fix: 两份 mod 脚本改用 libs.versions.toml 的 gson/junit 别名（或至少加对齐检查）。
tests: test scope 不进发布物（均 testImplementation），已静态确认。
```

## BLD-06 — 两份 mod 构建脚本约八成逐行重复

```yaml
id: BLD-06
title: "forge/neoforge build.gradle.kts 完全相同 66 行 = 80.5%；1.120.2 硬编码每份 1 处（共 3 处含 catalog）"
severity: medium
status: open
confidence: partial
module: build-ci
origin: [REVIEW-B4]
files:
  - path: mod/forge/build.gradle.kts
    lines: "1-82"
  - path: mod/neoforge/build.gradle.kts
    lines: "1-79"
evidence: difflib 统计 forge 82 行、neoforge 79 行，完全相同 66 行 = 80.5%/83.5%，Jaccard 69.5%；差异 29 行（插件、MC/loader、release 17↔21、loader 配置块、cobalt、metadata 文件名）；「70%」只对应 Jaccard，「1.120.2 四处」不成立（每份各 1 处，加 catalog 共 3 处）。
impact: 公共逻辑（sourceSets、repositories、JVM 参数、test 配置）易漂移。
fix: 抽 gradle/mod-variant.gradle.kts 或 buildSrc convention plugin，两变体只留差异字段。
tests: none
```

## BLD-07 — .gitattributes 缺 text=auto，行尾导致产物哈希不可复现

```yaml
id: BLD-07
title: ".gitattributes 仅 2 行；无 * text=auto；smoke-test.sh 不受 eol=lf 保护且随发布包出厂"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-B4, REVIEW-NEW-5]
files:
  - path: .gitattributes
    lines: "1-2"
  - path: cli/build.gradle.kts
    lines: "53"
  - path: handoff.md
    lines: "635"
evidence: |
  .gitattributes 只有 gradlew text eol=lf 与 *.bat text eol=crlf；git ls-files --eol 显示 smoke-test.sh 为 i/lf w/crlf attr/；
  distZip:53 include scripts/**；handoff:635 实测「Linux 与 Windows 构建的 jar 仅 pack.mcmeta 行尾不同，哈希不可比」。
impact: 工作树行尾依赖 core.autocrlf，产物哈希不可复现，脚本可能带 CRLF 出厂。
fix: 加 * text=auto、*.sh text eol=lf、*.toml text eol=lf、*.mcmeta -text；processResources 统一行尾。
tests: none
```

## BLD-08 — smoke 报告落在仓库根目录，.gitignore 未覆盖

```yaml
id: BLD-08
title: "smoke-test.sh/ps1 默认把报告写到当前目录，.gitignore 无对应条目"
severity: low
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-#8]
files:
  - path: scripts/smoke-test.sh
    lines: "26"
  - path: scripts/smoke-test.ps1
    lines: "28"
  - path: .gitignore
    lines: "1-7"
evidence: sh:26 report=$PWD/cct-run-smoke-report.txt；ps1:28 $Report 默认 cct-run-smoke-report.json（相对 CWD）；.gitignore 7 行无该模式。
impact: 在仓库根跑 smoke 产生未跟踪文件，易误提交。
fix: 报告默认落 --out 或临时目录，并把 cct-run-smoke-report.* 加进 .gitignore。
tests: none
```

## BLD-09 — push/PR 触发器残留已合并分支

```yaml
id: BLD-09
title: "push 触发列表含 1018/multi-version-build/neoforge-1.21.1（均已合并）；pull_request 无 branches 过滤会双跑"
severity: low
status: open
confidence: partial
module: build-ci
origin: [REVIEW-#9, REVIEW-L9, REVIEW-NEW-4]
files:
  - path: .github/workflows/build.yml
    lines: "16, 18, 22"
evidence: branches: [main, 1018, multi-version-build, neoforge-1.21.1]；三者均已通过 PR #11/#12/#13 合入 main；pull_request 空过滤匹配所有 PR；concurrency 按 ref 分组，push 与 PR 不互相取消。
impact: 为已合并分支持续消耗 CI，同 commit 可能 push+PR 双跑。
fix: 只保留 main；pull_request 加 branches: [main]。
tests: none
```

## BLD-10 — wrapper 无 SHA256、actions 用可变主版本 tag

```yaml
id: BLD-10
title: "gradle-wrapper.properties 无 distributionSha256Sum；actions 全部用主版本 tag 而非 commit SHA"
severity: low
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-#10, REVIEW-L10, REVIEW-NEW-6]
files:
  - path: gradle/wrapper/gradle-wrapper.properties
    lines: "3"
  - path: .github/workflows/build.yml
    lines: "34, 36, 45, 60"
evidence: properties 仅 7 行，无 distributionSha256Sum；checkout@v7、setup-java@v6、upload-artifact@v7 共 8 处均为可变主版本 tag；gradle-wrapper.jar 已入库且无校验。
impact: 构建输入（Gradle 发行版与 actions）可被上游标签移动而改变。
fix: 加 distributionSha256Sum；actions 钉 commit SHA；启用 gradle wrapper-validation-action。
tests: none
```

## BLD-11 — 发布资产无签名/校验和，jar glob 宽松

```yaml
id: BLD-11
title: "release 无 SHA256/签名；assets 用 *.jar 通配收集；gh release create 不幂等"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-#11]
files:
  - path: build.gradle.kts
    lines: "36, 39"
  - path: .github/workflows/build.yml
    lines: "96, 105-110"
evidence: assembleMods 对两个 libs 目录各 include(*.jar)；release assets=(cli/build/distributions/*.zip build/mods/*.jar)；全仓库构建脚本 grep signing/sourcesJar/checksum 零命中；当前每变体仅一个主 jar，暂无被吞的变体 jar。
impact: 下载者无法校验发布物完整性；未来加 sourcesJar 会被静默带上；重跑 release job 会失败。
fix: 生成 SHA256SUMS 作为 release asset 并考虑签名；glob 改显式文件名或排除 -sources/-javadoc；创建 release 幂等处理。
tests: none
```

## BLD-12 — THIRD-PARTY-NOTICES 手工维护无 CI 绑定

```yaml
id: BLD-12
title: "THIRD-PARTY-NOTICES 与 catalog/LICENSES 对齐良好，但纯手工、无 CI 校验"
severity: info
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-#12]
files:
  - path: THIRD-PARTY-NOTICES.md
    lines: "33"
  - path: gradle/libs.versions.toml
    lines: "4"
  - path: LICENSES/
    lines: "third-party/"
evidence: gson 2.11.0、cobalt 0.9.9 等与 catalog 及 LICENSES/third-party 逐项对得上；无 junit 行属合理（test scope）；无插件或 CI 步骤校验其与实际依赖一致。
impact: 依赖升级后 notices 可能静默过期（BLD-05 的 gson 分叉即同类漂移）。
fix: 用 Gradle 依赖报告生成或校验 notices（license 插件 + CI diff 检查）。
tests: none
```

## BLD-13 — smoke 脚本对等性（信息）

```yaml
id: BLD-13
title: "smoke-test 的 sh/ps1 用例集合对等，差集仅 Windows 专属 mcp-via-cmd"
severity: info
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-#13]
files:
  - path: scripts/smoke-test.sh
    lines: "77"
  - path: scripts/smoke-test.ps1
    lines: "121, 251"
evidence: sh 19 个 check + old-java-switch(SKIP) + mcp-stdio = 21；ps1 $cases 20 + mcp-stdio（+ 仅 Windows 的 mcp-via-cmd）= 21/22；逐名比对差集仅 mcp-via-cmd。
impact: 无实质问题，仅需知悉平台差异。
fix: none
```

## BLD-14 — tag v0.2.3 之后 9 个提交而版本号未变

```yaml
id: BLD-14
title: "v0.2.3 tag 之后有 9 个未发版提交，版本号仍 0.2.3；推 v0.2.4 会发布名为 0.2.3 的资产"
severity: medium
status: open
confidence: confirmed
module: build-ci
origin: [REVIEW-NEW-3]
files:
  - path: build.gradle.kts
    lines: "5"
  - path: engine/src/main/java/cctrun/engine/Versions.java
    lines: "8"
  - path: .github/workflows/build.yml
    lines: "104"
evidence: git describe = v0.2.3-9-gbf68ac4（tag f31082f 在 9 个提交之前）；build.gradle.kts:5 与 Versions.java:8 仍 0.2.3；release job 只用 GITHUB_REF_NAME 命名且不校验版本。
impact: 现在推 v0.2.4 会发布 cct-run-0.2.3.zip，资产名与 tag 不一致。
fix: release 首步比较 tag 与构建中的 version，不等则失败（与 BLD-02 同修）。
tests: none
```

## BLD-15 — mod 构建缺 toolchain、cobalt compileOnly 未使用

```yaml
id: BLD-15
title: "mod 构建只有 options.release 无 java.toolchain；forge 仍有未使用的 compileOnly cobalt"
severity: low
status: open
confidence: confirmed
module: build-ci
origin: [LEGACY-L-build]
files:
  - path: mod/forge/build.gradle.kts
    lines: "29, 65"
  - path: mod/neoforge/build.gradle.kts
    lines: "29"
evidence: 两份构建只有 options.release（17/21），无 java.toolchain 块；forge 仍 compileOnly("cc.tweaked:cobalt:0.9.9") 而源码未直接引用；test source set 已补。
impact: 工具链靠运行环境 JDK，构建可复现性弱；多一个未用依赖。
fix: 显式声明 java.toolchain；删除 cobalt compileOnly。
tests: CI 构建可覆盖。
```
