# 用法速查

[← 返回 README](../../README.md) · [安装](installation.md) · [从源码构建](../development/build.md)

## 命令

```
cct-run run <程序.lua> [--fixture 文件] [--timeout 秒] [--out 目录] [--term 宽x高] [--basic|--advanced] [-- 程序参数...]
cct-run trace  <运行目录|latest> [--kind 类型1,类型2] [--ticks A-B] [--peripheral 名称] [--source 名称] [--contains 文字] [--offset N] [--limit N]
cct-run screen <运行目录|latest> [--tick N] [--source term|显示器名]
cct-run guide | version | help
cct-run mcp [--out 目录] [--live 游戏目录]   # MCP 服务器，由客户端启动
```

MCP 工具与命令的对应关系：

| 工具 | 对应命令 | 参数 |
|---|---|---|
| `cct_run` | `run` | `file`（路径）或 `code`（Lua 源码，可配 `name`）、`args`、`fixture`（JSON 对象）或 `fixture_file`、`timeout`、`term`、`computer`（`advanced`/`basic`） |
| `cct_trace` | `trace` | `run`（默认 `latest`）、`kind`（数组）、`ticks`、`peripheral`、`source`、`contains`、`offset`、`limit` |
| `cct_screen` | `screen` | `run`、`tick`、`source` |
| `cct_guide` | `guide` | `section`（可选，1 到 7） |
| `cct_live_*`（16 个） | — | 按需接管**真实游戏里**的电脑，需要 cct-live mod（原型）：`computers`、`attach` / `detach`、`status`、`wait`、`screen`、`trace`、`type`、`click`、`files`、`lua`、`body`、`snapshot`、`event`、`power`、`peripheral`（程序运行时读取外设）。接管时聊天栏有提示，有租约，玩家可以用 `/cctlive detach` 收回。用 `--live <游戏目录>` 或环境变量 `CCT_LIVE_DIR` 指定游戏目录。各工具的说明见 [`mod/README.md`](../../mod/README.md) |

工具返回的 JSON 和命令行输出相同。程序本身出错（`status: "error"`）是正常的测试结果；只有参数错误、文件不存在等情况，工具调用才会标记为出错。

- `--timeout` 是游戏时间（秒），默认 15，最大 3600。死循环程序以 `timeout` 结束是正常的。
- 每次运行的结果保存在 `.cct-runs\<运行编号>\` 下：`summary.json`（摘要）、`trace.jsonl`（全程记录）、程序和 fixture 的副本。`latest` 指最新的一次运行。
- `--contains` 按文字过滤记录（文字、事件名、外设名、方法名、参数、返回值和屏幕内容，不区分大小写）。
- 标准输出只有 JSON，而且只包含 ASCII 字符（非 ASCII 字符写成 `\uXXXX`），所以在任何控制台编码下都不会乱码。

| 退出码 | 含义 |
|---|---|
| 0 | 程序正常结束 |
| 1 | Lua 错误（语法错误、运行时错误、Too long without yielding、被 Ctrl+T 终止） |
| 2 | 超时，程序仍在运行 |
| 3 | 程序关机或重启了电脑 |
| 4 | 内存耗尽（超过约 1 GB 的上限） |
| 64 | 命令用法错误 |
| 65 | 输入有问题（文件不存在、fixture 格式错误等，错误信息会指出 JSON 中的具体位置） |
| 69 | 找不到 Java 17+ |
| 70 | 内部错误 |

需要更大的内存上限时，设置环境变量 `CCT_RUN_OPTS=-Xmx2g`（或 `JAVA_OPTS`）后再启动 cct-run；MCP 方式下在客户端配置的环境变量里设置。

## fixture 示例

```json
{
  "schema": "cct-run/fixture@1",
  "computer": { "label": "door", "advanced": true },
  "peripherals": {
    "top":  { "type": "monitor", "blocks": [3, 2] },
    "back": { "type": "wired_modem", "network": {
                "minecraft:chest_0": { "type": "inventory",
                  "items": { "1": { "name": "minecraft:iron_ingot", "count": 64 } } },
                "Create_Stressometer_0": { "type": "scripted", "peripheral_type": "Create_Stressometer",
                  "methods": { "getStress": 11776, "getStressCapacity": 16384 } } } }
  },
  "redstone": { "front": 15 },
  "timeline": [
    { "tick": 20, "do": "redstone", "side": "left", "level": 15 },
    { "tick": 30, "do": "event", "name": "monitor_touch", "args": ["top", 3, 2] },
    { "tick": 60, "do": "method", "peripheral": "Create_Stressometer_0", "method": "getStress", "returns": [16000] },
    { "tick": 80, "do": "terminate" }
  ]
}
```

`scripted` 用来描述其他模组的外设（这里是 Create 的应力表）：给出类型名和每个方法的返回值，数值应该从实机上取（`cct_live_peripheral`）；时间线的 `method` 动作在第 60 刻改变返回值。

完整写法见 [`guides/agent-guide.md`](agent-guide.md) 第 5 节，以及 `examples/` 中的 fixture。
