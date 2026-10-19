# 1019fix 验证证据

日期：2026-10-19。基线 `851f25d`；范围：REPAIR_PLAN 的 A/T 63 项。原有探针/结果未覆盖。每项修复提交及证据见本目录 `ISSUE_MATRIX.md` 和各模块 issue 的 `fixed_in`。

## 环境与边界

Linux 托管环境，完整 JDK 8/17/21（Adoptium 官方资产，下载校验），Gradle 9.7.1，CC:T 1.120.2、Cobalt 0.9.9。构建使用正常证书链与会话代理，未关闭 TLS 或依赖校验。Gradle 命令需提供本机 JDK 17/21 路径；本机临时 `/tmp/cct-gradle` 仅设置这些路径、代理与证书，不属于交付依赖。

本轮未验证 Windows/macOS 客户端、真实玩家存档、生产服务器部署或正式 tag 发布。依赖校验元数据来自实际构建/开发服务器解析的工件，逐项记录 SHA-256；它不是第三方供应链安全审计，也不保证未来平台/profile 无新增依赖。签名未配置，仅产出 SHA256SUMS。D/K/O 策略问题保留原状态，包含 CLI-01 和默认 EOF drain。

## 自动回归与包验证

```bash
./gradlew build :mod-forge:build :mod-neoforge:build assembleMods --dependency-verification strict
python3 -m unittest discover -s scripts/tests -v
python3 scripts/test_summary.py
./gradlew :cli:distZip
# 解压实际 zip 后运行其中的脚本和启动器
bash /tmp/<extracted>/scripts/smoke-test.sh /tmp/<extracted>/bin/cct-run <report-path>
RELEASE_TAG=v0.2.3 ./gradlew verifyReleaseVersion
# 不匹配的 RELEASE_TAG=v0.2.4 必须拒绝
```

- JUnit 实际执行结果从 XML 生成到 `verification-verified.json`，保留模块明细、失败/跳过、commit、dirty、报告时间。复用缓存会如实保留报告时间，不声称同一 commit 上全新执行。
- 发布脚本的两个标准库测试验证：已有资产 hash 不同，在任何上传前拒绝；相同资产跳过并稳定生成清单。prepare-only 只计算本地产物校验和，不调用发布操作。
- `actionlint` 1.7.12 静态检查通过，下载二进制对照该 release 的 SHA256 清单。GitHub CI 的实际运行状态另列；本地静态检查不代表线上运行。
- `git diff --check`、英文 ASCII guide/PowerShell、zip 脚本权限/LF、Java class 目标版本等通过结果在 `local-checks-verified.json`。

## 游戏运行场景

脚本：`../scripts/live_regression.py`。只允许带 `.cct-repair-test-world` 标记的专用测试世界；仅 loopback HTTP/RCON。配置与令牌在忽略的 `mod/{forge,neoforge}/run` 下，报告无密码/令牌。

| 报告 | 覆盖 |
|---|---|
| `forge-live.json` / `neoforge-live.json` | 首轮通过，保留未 attach 前无 session 目录的验证；旧版脚本/代码，只用于该轮证明 |
| `forge-live-final.json` | 中间轮次 Forge 1.20.1 / 47.4.10，recording 关闭；两台电脑共享 full wired modem + 网络箱子 + 显示器，所有命名检查通过 |
| `neoforge-live-final.json` | 中间轮次 NeoForge 1.21.1 / 21.1.262，recording 开启；同上，额外检查历史转实时事件连续 |

最新确认轮次 `forge-live-verified.json` / `neoforge-live-verified.json` 包含损坏 source 回放补强之后的源码，以及拒绝/忽略 body 路径的实机回归；模块源码 hash 以这两份报告为准。

两版覆盖：401/403、缺 Host、0600；文件与快照 save/diff/restore、坏 manifest 单独跳过、路径拦截；2 MiB POST 413、认证慢 body 在 5 秒断开后服务恢复；反射能力与有线外设；reboot 不复用 call id；安装目录的 MCP 客户端读取 Long.MAX_VALUE seq，并通过 Lua 返回 42；1 MiB 双段轮转、颜色屏幕回放、保留窗口连续事件；真实区块 unload/reload 不恢复 AI 控制；等待真实租约 31 秒后到期；关闭服务器清理 owned endpoint。

复用世界第一次复跑时，旧 startup 绘屏程序的序列混入新一轮，导致测试计数断言失败。核对实际 trace 无丢弃、保留窗口连续，随后给每次脚本运行分配独立事件名，并等该轮 checkpoint 轮转后再检查。关闭 recording 期间不应要求保存离线事件。最终报告注明 `dormant_checked: false`，不重复冒称验证过已存在 sessions 的空目录条件。模块源文件 SHA-256 记录在最终 JSON 中；脚本源码与结果均提交供复核。

## 解释与剩余验证

- 真实 OOM 与伪造 OOM 文案分开验证；完整 debug 权限不是安全沙箱。磁盘删除时关闭句柄是明确的模拟差异，见 handoff DV17。
- 取消测试检查子进程终止、failed/cancelled state；中断回归检查 summary 落盘及恢复 interrupt。外部 SIGKILL/断电只能保留最后已知 state，不能保证 summary。
- trace 读取使用锁内磁盘快照，未依赖 Windows 允许移动已打开文件。此设计和单测通过不等于已在 Windows 压力测试。
- 墙钟超时 stderr 尾部路径经过代码核对；未人为卡死 JVM 等满 120 秒宽限来模拟该触发条件。


最后补强中，单测新增坏 source/坏行号/截断记录后仍恢复有效彩色屏幕；并实测发现旧 HTTP 处理在两条 wrong-token 慢 body 返回 401 后仍被 JDK close/drain 卡住。新实现覆盖所有响应路径，认证慢 body、两条错误 token 慢 body、恶意 Host 慢 body和携带慢 body 的 GET 都必须在期限断开，随后 GET 正常。旧 `*-final.json` 和首次校验/包报告仍保留历史，最新完整结果使用 `*-verified` 名称，不覆盖旧轮次。


最终本地完整验收：`verification-verified.json` 为实际 XML 汇总，`smoke-linux-verified.txt` 为最后打包 zip 的冒烟；`local-checks-verified.json` 包括包内文件/报告比对、真实 Java 17 运行、目标 class 版本、与最新游戏报告的源码 hash 比对、版本门禁和 prepare-only 校验和；`SHA256SUMS-verified` 只对应该轮本地产物。早一轮无 verified 后缀的文件保留各自 commit/time，不替代最新报告。构建/打包实际输出：`build-verified.txt` / `package-verified.txt`。


## 提交身份与冷缓存 CI 补验

按维护者指定身份核对本轮全部 author/committer；采用远端已修正的 11 条历史，避免覆盖同内容的并行身份修正。`fixed_in`、矩阵和旧报告中的 commit 引用已同步到该历史，不公开旧→新映射；原执行数、时间与游戏源码 hash 保留，报告注明引用修正。原 SHA256SUMS 对应原始本地包，后续重新打包应采用新一轮报告与清单，不能把旧包校验和用于新包。

首次 GitHub 冷缓存构建暴露 BOM 的 POM/Gradle module 路径与本地暖缓存不同：Kotlin BOM 2.3.21 POM、JUnit BOM 5.7.1/5.7.2/5.9.0/5.9.3/5.10.0/5.10.1 module，以及刷新依赖后出现的 JUnit BOM 5.11.4 POM。新增八项 metadata 校验值均从 Maven Central 核对，且与 CI 的 NeoForged 镜像字节一致；来源与 SHA256 见 `dependency-metadata-verified.json`。不调整依赖版本、不忽略工件、不关闭 strict。

严格校验补验命令：`./gradlew build :mod-forge:build :mod-neoforge:build assembleMods --dependency-verification strict --refresh-dependencies`，本地成功；测试任务可复用既有结果，报告保留原时间。GitHub 最新实际执行状态另记。

GitHub 实测还确认已有 CodeQL Default setup 会拒绝 Advanced 配置重复提交 SARIF；扫描查询本身成功。补充工作流保留手动构建/分析，改为 `upload: never`、不上传数据库，并要求保存 `codeql-sarif` 构建产物；不关闭 Default setup，不忽略分析失败。
