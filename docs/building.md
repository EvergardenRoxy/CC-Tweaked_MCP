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

## README 里的图

`docs/images/` 下的性能图和测试结果图（各有浅色和深色两个版本）由脚本根据其中写明的实测数据生成，只需要 Python 3：

```
python3 scripts/readme_charts.py
```

数据有变化时，改脚本里的数据再重新生成。

## 其他

项目结构、设计决定、已知问题见 [`handoff.md`](../handoff.md)；在自己的机器上测试的方法见 [`testing.md`](testing.md)。
