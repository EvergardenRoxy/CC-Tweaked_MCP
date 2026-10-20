# 2026-10-20 六项约定范围：实现与验证

源码提交：`c94928bceb451e9a9ce444f5faf0b51a64a0d389`，分支 `1020`；基于 main `25a3921` 和独立的审查整理提交 `66de0d2`。用户指定作者/提交者 `EvergardenRoxy <fubukisanta@163.com>`，仅提交并推送 1020，不建 PR、不合并、不发布。

## 实际交付范围

| ID | 结果 | 状态 |
|---|---|---|
| CLI-18 | 严格 Gson MCP JSON 解析；拒绝非法语法与尾随内容，下一条合法请求仍可处理 | fixed |
| ENG-FX-07 | ItemStack 使用插入顺序构建详情；保留字段值，不承诺 Lua pairs 顺序 | fixed |
| MOD-03 | Forge/NeoForge 区分 session 创建、HTTP 端口绑定、其他 HTTP 启动失败，日志给出端口与处理办法 | open，诊断子项完成；端口策略/工具状态等其余范围不冒充完成 |
| CLI-11 | 工具描述引用 Versions 常量；参数转换规则保持 | open，参数校验去重与兼容范围未实施 |
| ENG-RT-13 | 每 run 最多一条反射 fallback 诊断；保留原 reboot 时序规则 | open，整体重构未实施 |
| ENG-FX-05 | guide/limitations 说明首 10 条 notes、notes_dropped 和 trace 查询 | open，其余范围及现有本地错误诊断保留 |

不升级依赖，不改 Java 目标，不自动换端口或删除记录，不改 EOF drain、协议支持列表和现有参数转换。

## 构建与测试

使用完整 JDK 21 构建，提供 JDK 17/21 工具链；测试中通过 `CCT_SMOKE_JAVA8_ROOT` 提供真实 Java 8。网络使用环境代理和系统 CA，保持 TLS 与严格依赖校验。缓存和 JDK 路径按本机实际位置替换。

```sh
./gradlew --offline --no-daemon --console=plain :engine:test :cli:test :cli:distZip --dependency-verification strict --max-workers=2
./gradlew --no-daemon --console=plain :mod-forge:build :mod-neoforge:build --dependency-verification strict --max-workers=2
python3 scripts/test_summary.py
./gradlew --offline --no-daemon --console=plain :cli:distZip --dependency-verification strict
```

- [verification.json](verification.json)：实际 XML 汇总 139 次执行，engine 55、CLI 40、Forge 22、NeoForge 22；四模块均无失败、错误、跳过。
- [engine-cli-build.txt](engine-cli-build.txt)：engine/CLI 全部 test 任务执行通过，新增严格 JSON、fallback 故障注入与字段序列化回归。Java 8 启动器回归未跳过。
- [mod-build.txt](mod-build.txt)：两变体编译/构建/test 执行通过；部分依赖准备和测试编译任务复用缓存，不能把它写成完全冷构建。
- [package-build.txt](package-build.txt)：说明文档最后修改后重新生成 zip，包含最新嵌入 guide；不是测试之外另发一个未核对包。

源码提交是在 engine/CLI 测试之后创建；归档报告保留实际 XML 时间。归档时工作区有本次 issue/证据文档更新，因此 verification 的 dirty 如实为 true；[checks.json](checks.json) 另核对运行/构建源码仍与源码提交一致，不伪装成干净发布验收，也不把时间早于提交的测试说成提交后重新执行。

## 实际 zip 验证

解压 `cct-run-0.2.4.zip` 后，以真实 Java 17 运行包内 `scripts/smoke-test.sh` 和启动器：

- [smoke-linux.txt](smoke-linux.txt)：21 通过、0 失败、0 跳过，包含 Java 8 自动切换、MCP stdio、内存上限、参数、错误与 fixture 场景。
- [checks.json](checks.json)：包内 MCP 对非标准 JSON 回 -32700，下一条合法 ping 成功；包内 guide 含 notes 限额，源 guide 仍为英文 ASCII。
- 字节码：Launcher/JavaFinder 为 52（Java 8），Main/engine/Forge 为 61（Java 17），NeoForge 为 65（Java 21）。

本轮没有执行 Windows 客户端/文件锁验证、游戏世界启动或实际端口冲突故障注入。mod 失败诊断经源码差异审阅与双变体构建验证，不冒充游戏日志现场复现。历史 review/1019fix/发行证据均保留在各自目录。
