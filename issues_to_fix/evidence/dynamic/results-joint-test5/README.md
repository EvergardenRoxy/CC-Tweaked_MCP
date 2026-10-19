# 精密构件测试材料复核证据

这是对用户上传 `测试3.zip` 和 DSH 会话导出的核验，不是对用户游戏重新执行测试。完整解释见 [核验报告](../../../../docs/testing/reports/05-precision-mechanism.md)。原始附件保留在上传文件中，此处不复制私人路径、完整会话、截图或令牌。

- `session-evidence.json`：附件 SHA-256、有效条件的 seq、最后写入载荷比对、截断预览误读、基础配方、快照与释放结果。
- `recorded-base-recipe.json`：会话 seq 311 实际读取的 jar 基础配方，不能冒充服务器最终数据包覆盖导出。
- `session-metrics.json`：正式任务时间窗口、分层工具计数、用户提供的用量；Asia/Seoul。
- `reproduction.json`：Linux / Java 17 / cct-run 0.2.4 / CC:T 1.120.2 复跑三套原测试与六个定向探针的实际结果，含交付 Lua 文件 SHA-256。
- `findings.json`：针对上传 Lua 交付物的六项已确认问题与修复方向；与原仓库 95 条 issue 分开，不改其状态。
- `review-probes.lua`、`reproduce.py`：只在模拟器的内存文件系统测试状态、备份、失败发送、队列和显示错误；不调用实机工具。假硬件仅在标明的探针中用于制造故障，Store 探针使用真实 CC 文件 API。

解压完整测试材料，安装 cct-run 0.2.4 / Java 17+ 后可复现：

```bash
python3 reproduce.py \
  --materials-root /absolute/path/to/extracted-test3 \
  --cct /absolute/path/to/cct-run/bin/cct-run \
  --out /absolute/path/to/review-output
```

需要完整解压树（含 `tests/fixture-tests.json` 和 `deliver/program`）。脚本会先比对 fixture 中的模块与交付模块，防止测试了不同版本；输出及运行目录写入 `--out`。Windows 使用相应的启动命令/路径时可按本机环境调整。本轮实际复现环境是 Linux，未在 Windows 重跑该复核脚本。

三个原脚本的 310 条断言通过；新增探针揭示原覆盖之外的问题。复现成功不表示故障已经修好，亦不替代上传会话中的 NeoForge 实机证据。未改交付程序、未改原 95 条仓库问题的状态、未推送或发行。
