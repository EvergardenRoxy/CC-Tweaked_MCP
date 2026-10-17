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

`mod/` 是独立的 Gradle 构建，上面的命令不会构建它。第一次构建会下载 Minecraft 和 Forge 的开发环境（几百 MB）：

```
./gradlew -p mod build      # 生成 mod/build/libs/cct-live-1.20.1-<版本>.jar；Windows: gradlew.bat -p mod build
```

详见 [`mod/README.md`](../mod/README.md#构建)。

## README 里的图

`docs/images/` 下的性能图和测试结果图（各有浅色和深色两个版本）由脚本根据其中写明的实测数据生成，只需要 Python 3：

```
python3 scripts/readme_charts.py
```

数据有变化时，改脚本里的数据再重新生成。

## 其他

项目结构、设计决定、已知问题见 [`handoff.md`](../handoff.md)；在自己的机器上测试的方法见 [`testing.md`](testing.md)。
