# 发行流程（维护者）

当前准备版本为 **0.2.4**，发行说明为 [0.2.4](../releases/0.2.4.md)。`1019fix` 包含已完成的 A/T 修复与发行准备，先验证并合并到 main，再从 main 的确切提交推送 `v0.2.4`。**推送 `v*` 标签会自动正式发布，不是演练。**

## 合并前

1. 确认工作区干净、main/1019fix 与远端同步；检查 PR 完整差异与剩余问题范围，不重开已关闭的旧 PR #16。
2. 按 [构建说明](build.md)执行四模块构建与测试、发布脚本测试、实际解压 zip 的 Linux 冒烟；Java 8 启动器切换须有真实 Java 8 环境。
3. `RELEASE_TAG=v0.2.4 ./gradlew verifyReleaseVersion` 必须通过；用错误标签验证拒绝，不推送测试标签。
4. 在干净源码提交上生成 `verification.json`，再打包。核对运行时 `version`、MCP initialize 版本、zip 内部目录/lib 名称及两个 mod 元数据均为 0.2.4；launcher/engine/Forge/NeoForge class major 为 52/61/61/65。
5. 检查该提交的 GitHub build、安全工作流（含适用的 dependency-review），下载核对最终四模块 verification 和 CodeQL SARIF。CI 不替代尚未执行的 Windows/macOS/生产服务器测试。

只准备本地产物和校验清单，不创建标签或发布：

```bash
python3 scripts/release_assets.py v0.2.4 \
  build/release/cct-run-0.2.4.zip \
  build/release/cct-live-1.20.1-0.2.4.jar \
  build/release/cct-live-1.21.1-0.2.4.jar \
  --verification build/release/verification.json \
  --prepare-only
```

验证报告必须记录当前 HEAD、干净工作区、四模块均有测试且无失败/错误/跳过；旧提交的报告不能冒充当前发行验证。提交/推送前必须按 handoff D70 核验所有新提交的 **author 和 committer**，合并提交同样核验。不要依赖全局 Git 默认身份。

## 合并与标签

经维护者授权后，合并 PR 到 main 并推送；保留可追踪的修复提交。不强推 main，不改写既有标签。若本地创建合并提交，显式设置批准的 author/committer，再检查提交字段后推送。

确认 main 包含已验证内容、版本门禁通过，再在该确切提交创建 `v0.2.4` 注释标签。显式设置批准的 tagger 并核验标签对象身份，确认远端不存在同名标签后仅推送这个标签。

## 正式发行

标签触发 [build 工作流](../../.github/workflows/build.yml)：

1. engine/cli 和双版本 mod 构建测试，实际 zip 冒烟，汇总四模块报告。
2. `release` 校验 tag = `v` + `Versions.TOOL`，下载同次测试产物，**不重建**。
3. 以本版发行说明创建草稿，上传 zip、两个 jar、`verification.json`、`SHA256SUMS`；全部上传成功后正式发布。已有同名资产必须与待上传内容逐字节相同，否则停止，不能覆盖。
4. 发行后检查发布状态、标签指向的 main 提交、五个资产名称；下载资产，验证 GitHub digest 与 `SHA256SUMS`，并确认四模块报告的 commit 对应标签。

本地预交付包与正式 CI 包可能因构建环境、嵌入报告不同而有不同 SHA-256。正式发行以标签工作流实际测试并上传的字节及其校验清单为准，不能用本地预交付清单替代。

若标签工作流失败，先定位失败阶段；未发布时修正后重新验证，不移动已存在的发行标签。已发布的同名资产内容不同则停止处理，交由维护者决定下一版本，避免破坏用户已有下载的可追溯性。
