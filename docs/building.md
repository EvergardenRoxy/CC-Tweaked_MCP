# 从源码构建

[← 返回 README](../README.md) · [安装](install.md) · [用法速查](usage.md)

## cct-run

需要 JDK 17 或更高版本，构建时会下载依赖。

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
./gradlew :mod-forge:test :mod-neoforge:test   # 纯 JUnit 测试，各 9 个
```

共享代码在 `mod/src/common/`，加载器相关部分在 `mod/forge/`、`mod/neoforge/`。上面的 `./gradlew build` 不会构建 mod，也不会下载 Minecraft。详见 [`mod/README.md`](../mod/README.md#构建)。

## 自动构建（GitHub Actions）

[`.github/workflows/build.yml`](../.github/workflows/build.yml) 在每次推送、PR 和手动触发时跑三个任务：

| 任务 | 做什么 | 产物 |
|---|---|---|
| `tests` | `./gradlew build`：engine 42 + cli 30 个测试 | 失败时上传测试报告 |
| `cct-live (forge)` | `./gradlew :mod-forge:build`：9 个测试 | `cct-live-forge`（`cct-live-1.20.1-<版本>.jar`） |
| `cct-live (neoforge)` | `./gradlew :mod-neoforge:build`：9 个测试 | `cct-live-neoforge`（`cct-live-1.21.1-<版本>.jar`） |

推送 `v*` 标签时再跑 `release` 任务：构建 `cct-run-<版本>.zip` 和两个 mod jar，附到同名 GitHub Release（说明文件取 `docs/release-notes-<版本>.md`，没有就自动生成）。

两个 mod 任务用 JDK 21（`mod-forge` 仍编译到 Java 17），第一次运行会下载对应版本的 Minecraft 和加载器开发环境，所以比 engine/cli 任务慢；依赖缓存由 `actions/setup-java` 的 `cache: gradle` 复用。CI 只跑单元测试和构建，实机检查仍按 [`testing.md`](testing.md) 手动做。

## README 里的图

`docs/images/` 下的性能图和测试结果图（各有浅色和深色两个版本）由脚本根据其中写明的实测数据生成，只需要 Python 3：

```
python3 scripts/readme_charts.py
```

数据有变化时，改脚本里的数据再重新生成。

## 其他

项目结构、设计决定、已知问题见 [`handoff.md`](../handoff.md)；在自己的机器上测试的方法见 [`testing.md`](testing.md)。