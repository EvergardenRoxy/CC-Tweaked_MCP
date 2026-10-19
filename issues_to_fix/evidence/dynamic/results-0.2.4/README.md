# 0.2.4 发行准备验证

2026-10-19，源码提交 `d159cc58547d5ef9a7252352a96262fb969a833e`，Linux / JDK 8、17、21。报告在该提交的干净工作区生成；本目录后续归档提交只记录已完成验证，不冒充被测源码提交。

- `verification-local.json`：显式重新执行四个 test 任务的 JUnit XML 汇总，136 次执行，四模块全部验证，无失败/错误/跳过。
- `smoke-linux.txt`：实际解压 `cct-run-0.2.4.zip` 中的脚本和启动器，21 项通过，无跳过，含真实 Java 8 启动器切换。
- `local-checks.json`：包内文档/报告与源文件一致，英文 ASCII、LF/executable、launcher 52 / Main 与 engine 61 / Forge 61 / NeoForge 65；真实 Java 17 运行及 MCP initialize 报告 0.2.4；两个 mod 元数据版本正确、CC:T 仍锁定 1.120.2，Java 源文件 hash 与上一轮双开发服务器报告一致。
- `SHA256SUMS-local`：仅对应该轮本地 zip、两个 jar、原文件名 `verification.json`，使用发布脚本 `--prepare-only` 生成并逐项校验。正式发行必须采用标签 CI 实际上传资产的校验清单；不能拿本清单验证后续 CI 构建。

命令（JDK 路径、代理和证书按环境配置，未关闭 TLS 或 strict）：

```bash
./gradlew build :mod-forge:build :mod-neoforge:build assembleMods --dependency-verification strict
./gradlew :engine:test --rerun :cli:test --rerun :mod-forge:test --rerun :mod-neoforge:test --rerun --dependency-verification strict
python3 -m unittest discover -s scripts/tests -v
python3 scripts/test_summary.py
./gradlew :cli:distZip assembleMods --dependency-verification strict
RELEASE_TAG=v0.2.4 ./gradlew verifyReleaseVersion
# RELEASE_TAG=v0.2.3 必须失败，实际确认已拒绝
# 解压生成的 0.2.4 ZIP 后运行其 scripts/smoke-test.sh 和 bin/cct-run
```

发布脚本两项单测、actionlint、`git diff --check`、新增文档相对链接检查通过。运行时 `version`、summary.environment.tool、MCP serverInfo.version 均核对为 0.2.4。原先 0.2.3 版本引用均检查：当前版本入口已更新，历史 release notes、审查记录、安全修复历史和旧报告保留原版本。

实机行为覆盖沿用 `../results-1019fix/{forge,neoforge}-live-verified.json` 的相同 mod Java 源码，不声称重新运行了 0.2.4 游戏服务器。Windows/macOS 客户端及生产服务器本轮未重跑。此次维护者另已明确授权：验证通过后合并 main，并正式发布 v0.2.4；正式 CI、发布资产及校验状态以 PR #17、main/标签工作流和 GitHub Release 为准。
