# 从源码构建

[← 返回 README](../../README.md) · [安装](../guides/installation.md) · [用法速查](../guides/usage.md)

## cct-run

建议用 JDK 21 运行 Gradle；engine/cli 目标为 Java 17，启动器目标为 Java 8。构建时会下载依赖。双版本 mod 构建还需安装完整 JDK 17 和 21（Forge 开发环境生成需要 17，编译工具链使用 21）。可用 Gradle 的 `org.gradle.java.installations.paths` 指定安装路径。

```
./gradlew build             # 编译并运行测试
./gradlew :cli:distZip      # 生成 cli/build/distributions/cct-run-<版本>.zip
./gradlew :cli:installDist  # 生成 cli/build/install/cct-run/，可以直接运行
```

CC: Tweaked 使用 Maven 上发布的 `cc.tweaked:cc-tweaked-1.20.1-core:1.120.2`（来自 https://maven.squiddev.cc），不需要自己编译 CC: Tweaked，也不需要下载 Minecraft。

## cct-live mod

同一个工程产出两个版本的 mod：Forge 1.20.1 和 NeoForge 1.21.1。第一次构建会下载对应版本的 Minecraft 和加载器开发环境（各几百 MB）：

```
./gradlew :mod-forge:build      # Forge 1.20.1 -> mod/forge/build/libs/cct-live-1.20.1-<版本>.jar
./gradlew :mod-neoforge:build   # NeoForge 1.21.1 -> mod/neoforge/build/libs/cct-live-1.21.1-<版本>.jar
./gradlew assembleMods          # 两个都构建，并复制到 build/mods/
./gradlew :mod-forge:test :mod-neoforge:test   # 共享源码的纯 JUnit 测试，分别执行
```

共享代码在 `mod/src/common/`，加载器相关部分在 `mod/forge/`、`mod/neoforge/`。上面的 `./gradlew build` 不会构建 mod，也不会下载 Minecraft。详见 [`mod/README.md`](../../mod/README.md#构建)。

## 自动构建（GitHub Actions）

[`.github/workflows/build.yml`](../../.github/workflows/build.yml) 在 main 推送、面向 main 的 PR、手动触发和 `v*` 标签时执行：

| 任务 | 验证与产物 |
|---|---|
| `tests` | engine/cli 单测、Java 8 启动器回归、发布脚本测试；打包并解压 CLI zip，运行 Linux 冒烟，上传同一 zip 和 JUnit 报告 |
| `mod` | 两个加载器变体分别构建、运行共享 JUnit；上传精确版本名的 jar 和 XML |
| `verification` | 汇总四模块 XML 成 `verification.json`，缺失模块显式未验证，数量按执行次数计 |
| `release`（仅标签） | 校验标签必须等于 `v` + `Versions.TOOL`，下载同次已测 zip/jar，不再重建；生成 SHA256SUMS 后发布。已有同名资产必须逐字节校验一致，否则拒绝覆盖 |

完整本地验证与打包顺序：

```bash
./gradlew build :mod-forge:build :mod-neoforge:build --dependency-verification strict
python3 -m unittest discover -s scripts/tests -v
python3 scripts/test_summary.py
./gradlew :cli:distZip assembleMods
# 解压 zip 后，对其中的启动器运行（第二参数是可选报告路径）：
bash scripts/smoke-test.sh /absolute/path/to/cct-run/bin/cct-run
```

`build/reports/verification.json` 是实际 JUnit 结果的单一来源，记录 commit、dirty 状态、报告时间、失败/跳过和各模块执行次数。它可包含缓存测试结果，报告时间供核对；测试数据不存在时不会声称通过。生成后再打包会附入 zip 的 `validation/`。CI 的最终汇总作为单独发布资产附上，覆盖四模块。历史测试文档不替代该报告。

版本只定义在 `engine/src/main/java/cctrun/engine/Versions.java` 的 `TOOL`；Gradle 读取同一值。`./gradlew -q printVersion` 可查询，`RELEASE_TAG=v<版本> ./gradlew verifyReleaseVersion` 可离线验证发布门禁，不创建标签或发布。

维护者的 main 合并、标签与正式发行顺序见 [发行流程](release.md)。

Gradle wrapper 固定发行包 SHA-256；Actions 固定提交；`gradle/verification-metadata.xml` 校验依赖字节。更新依赖时在可信下载来源核对新校验值后提交元数据，不关闭校验来绕过失败。安全工作流包括依赖审查与 CodeQL，Dependabot 定期提供更新。

仓库现有 GitHub CodeQL Default setup 继续管理 Code Scanning。本工作流额外手动构建并分析 engine/cli，把 SARIF 保存为 `codeql-sarif` CI 产物，供下载审阅；不向 Code Scanning 重复上传冲突的 Advanced 配置，也不改动仓库的 Default setup。Minecraft 运行场景按[测试说明](../testing/guide.md)另验。CI 不等于 Windows/macOS 客户端或生产服务器实测。

## README 里的图

`docs/assets/images/` 下的性能图和测试结果图（各有浅色和深色两个版本）由脚本根据其中写明的实测数据生成，只需要 Python 3：

```
python3 scripts/readme_charts.py
```

数据有变化时，改脚本里的数据再重新生成。

## 其他

用户可见的模拟差异和已知限制见[模拟范围与已知限制](../guides/limitations.md)；在自己的机器上测试的方法见[测试指南](../testing/guide.md)。
