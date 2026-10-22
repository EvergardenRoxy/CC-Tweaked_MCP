# 1021：Minecraft 网络桥、FTB 授权与 MCP 独占会话

`1021` 从 `network-test` 的 `649b33b` 创建，在原有连接上加入 FTB 授权、多个 MCP 会话之间的互斥，以及授权后的完整控制（文件、Lua、输入、电源、快照、外设、trace）。支持 Forge 47.4.10 / Minecraft 1.20.1、NeoForge 21.1.262 / Minecraft 1.21.1，均使用 CC:T 1.120.2。

```text
AI / MCP 客户端
  ↕ stdio（每个连接独立的 MCP 会话）
玩家本机 cct-run mcp
  ↕ 带本机令牌的 127.0.0.1 HTTP
玩家 Minecraft 客户端 cct-live
  ↕ 已有 Minecraft 连接的自定义数据包
Minecraft 服务端 cct-live → CC:T
```

远程服务器不增加 HTTP/MCP 监听端口。客户端与服务端安装对应游戏版本的 mod；两种加载器不支持跨 Minecraft 版本互连。协议升级为 **4**，需同时更新 cct-run、客户端及服务端 mod。

## 授权规则

玩家对目标电脑只有“有权限 / 无权限”；功能是否已经实现、目标是否被占用分别判断。

| 情况 | 授权规则 |
| --- | --- |
| 未安装 FTB Chunks（包括只安装 Teams） | 所有非旁观玩家都可主动授权实际可使用的电脑 |
| 未认领 | 所有非旁观玩家可主动授权，即使 FTB 启用了 `no_wilderness` |
| 已认领 | FTB 的有效方块交互和方块编辑检查均不阻止操作才允许 |
| Chunks 已安装但 API 未就绪、查询失败或接口不兼容 | 返回服务不可用；不能降级为无 FTB 全员允许 |
| 已认领、玩家与电脑在不同维度 | 首版拒绝，避免 FTB 通用接口按玩家维度查错领地 |

两个版本都调用 FTB `shouldPreventInteraction` 的 `INTERACT_BLOCK`、`EDIT_BLOCK` 保护入口，包含上游的团队隐私、成员/盟友、标签和显式绕过语义；不只比较是否同队，不添加单独的 OP 放行入口。无领地允许授权不表示自动导出全部电脑；仍需玩家在游戏内明确选择目标。

已移除旧 `network.allow_player_grants` 配置和 OP 门槛，旧配置文件中的此项不再决定授权。FTB 是可选依赖，不随 cct-live 打包，也不要求客户端安装。API 编译基线：Chunks Forge `2001.3.8` / Library `2001.2.9`，Chunks NeoForge `2101.1.22` / Library `2101.1.34`；FTB 自身还需要 Teams、Architectury，按其依赖安装。

读取直接连接的显示器（`source` 为方向名）时，另按显示器整个多方块结构所在的每个区块检查 FTB 双许可；显示器伸入玩家无权的领地时返回 403，且 `status` 的 `monitors` 列表不列出该显示器。显示器被拒只影响该显示器，不撤销电脑授权。授权时、请求目标数据前重新检查 FTB；每秒清理失效授权及对应锁。团队/领地变更无需等 30 分钟授权自然到期。权限查询故障也会清除相应授权，恢复后需要重新主动授权。

## 使用

1. 服务端 `config/cctlive-common.toml` 保持 `[network] enabled = true`。服务端 `[http] enabled = false` 时网络桥仍可用。
2. 玩家打开目标电脑或海龟界面，点击界面左侧外、顶端与终端屏幕上缘齐平的 **AI** 图标按钮（白色=未授权，点击授权；绿色=已授权，点击撤销；灰色=查询中或不支持，悬停有说明）。窗口左侧放不下时，图标移到 CC 电源/终止按钮下方。手持平板电脑暂不支持，图标为灰色。也可在电脑旁执行 `/cctlive network authorize <ID>`：目标必须唯一、已加载、与玩家同维度、距离不超过 8 格且通过 CC:T 可使用检查。该命令解决 `no_wilderness` 阻止原生菜单打开时无法主动授权的问题，不改变原生界面保护。
3. 本机启动 `cct-run mcp --live <客户端游戏目录>`，或设置 `CCT_LIVE_DIR`。
4. 调用 `cct_live_computers` 查看当前玩家的授权目标；首次网络工具调用自动登记此 MCP 连接的独立会话。
5. 调用 `cct_live_control {"computer":1,"op":"acquire"}`，然后使用 `cct_live_status`、`cct_live_screen` 或基于屏幕的 `cct_live_wait`。
6. 用 `cct_live_control {"computer":1,"op":"status"}` 查询是否占用、是否由自己持有；`op:"release"` 释放。其他会话占用时获取失败，不抢占。
7. 玩家点击绿色 AI 图标或执行 `/cctlive network revoke <ID>`，会撤销该玩家授权和关联锁；撤销不要求电脑仍可用或已加载。`/cctlive network list` 仅列本人授权；`/cctlive network policy` 可诊断当前集成策略与 FTB 管理器状态。

授权固定 30 分钟，读取和 MCP 心跳不续授权；重新点击授权会重新计时。每个游戏连接最多 32 台授权电脑，断线、换连接 epoch、实例卸载/替换后失效。当前支持有实际方块实体的电脑目标；口袋电脑不开放。海龟位置会按实际方块重新校验，移动场景尚需游戏验收。

## MCP 会话与锁

同一玩家的不同 MCP 连接获得不同的服务端随机 `agent_session_id`。身份绑定真实玩家、游戏连接 epoch 和独立 MCP 会话，不能按玩家 UUID 共享控制权。每个游戏连接最多 8 个 MCP 会话。

服务端在一次世界运行中按电脑数字 ID 互斥，同时校验实例 UUID；重复 ID 不能借不同实例并发操作同一存储。持锁会话的每个目标读取都携带 `lease_id` 和递增 `lease_epoch`，旧凭据在释放、重新获取后不能继续使用。授权列表与自己的锁查询不要求先持锁，屏幕与状态要求持锁；首版没有并行旁观模式。

| 参数 | 当前默认 | 配置 |
| --- | --- | --- |
| 会话/租约到期 | 60 秒 | `network.lease_seconds`，30～300 秒 |
| MCP 心跳 | 15 秒 | 由 cct-run 会话发送，客户端 mod 不代为续期 |
| 无目标读取后释放锁 | 300 秒 | `network.idle_seconds`，300～86400 秒 |

这些是本轮可调整的实现默认值，非新增用户规则。心跳、锁查询及重复获取已有锁不重置业务空闲时间。正常 MCP 退出显式关闭会话；进程崩溃、心跳停止则由服务端到期释放。失权、撤销、卸载、断线及世界关闭同样使旧锁失效。暂停或连接故障超过期限后需要重新获取锁。

不拦截原生 CC:T 界面，不因打开界面撤锁，不冻结电脑程序。本轮目标读取都在服务端线程同步完成，没有后台写任务；未来开放写入/执行前，仍须实现释放中状态、在途任务收束和提交点版本检查。

## 当前功能边界

授权并持有锁的会话，可使用与本机管理员端点相同的功能：状态与屏幕（含历史屏幕 `tick`）、文件（list/stat/read/write/delete）、事件与输入（`cct_live_type`/`click`/`event`）、开关机、快照、外设查询、trace 和 HTTP body。`cct_live_lua` 与“运行 Lua 文件”不需要专门接口：它们由文件写入加输入事件组成，因此同样可用，结果通过屏幕和文件读取返回。

- **取得锁即开始记录**：`control_acquire` 会让服务端 attach 该电脑（等同本机 `attach`），所以 trace、`file_write` 等记录都带 `by: ai`；聊天栏会按 `chat` 配置提示“AI 已接管”。`control_release`、租约过期、撤销授权或断线后，由服务端自动 detach。CLI 的 `cct_live_attach`/`cct_live_detach` 在网络桥上等同 acquire/release。
- **服务器开关**：写文件、恢复快照仍由服务端 `files.allow_write` 控制（默认关闭），与本机一致；`network.enabled = false` 整体关闭网络桥。玩家无权限、被其他会话占用、玩家用 `/cctlive detach` 收回时分别返回 403 / 409 / 409，三者不混用。
- **FTB 与显示器**：每次请求前仍重新检查电脑的 FTB 双许可；读取直接连接显示器、对显示器发送 `monitor_touch` 事件时另检查显示器覆盖的每个区块。
- **大文件写入**：游戏请求上限 10000 字符（应用协议 4）。客户端桥把超出的 `files` 写入按约 8000 字符拆成连续追加，逐段由服务端校验租约，遇到 429 自动等待；中途失败时响应带 `partial` 与 `written_so_far`，文件可能已写入一部分。单次写入上限与本机一致（1 MiB）。
- **同 ID 电脑**：两台已加载电脑使用同一数字 ID 时，操作返回 409，直到只剩一台。
- 同一把锁内的所有操作都在服务端线程同步完成；仍没有后台写任务。锁释放后不会有迟到的写入。

仍需实机验证的边界：运行中编辑与玩家同时手动编辑的覆盖（后写者胜，与本机一致）；`partial` 写入的恢复；高延迟线路下的拆分写入耗时。数据保护项见 [网络待办](../../issues_to_fix/network-interaction-security.md)。

注意：授权等于允许该玩家的 AI 在该电脑上运行任意 Lua，与玩家坐在电脑前能做的事一致，但 AI 可以更快、更大范围地操作；授权前请确认玩家理解这一点，必要时服务器管理员可关闭 `network.enabled` 或保持 `files.allow_write = false` 限制写入。

## 本机发现与协议

玩家自己的集成服务器（单人游戏或以单人世界开放局域网）不启动客户端桥，继续使用原有管理员本机 HTTP 端点；局域网中的其他玩家仍走网络桥。客户端桥使用随机回环端口及独立发现目录：

```text
<客户端游戏目录>/cct-live/client/endpoint.json
<客户端游戏目录>/cct-live/client/token.txt
```

本机令牌不通过游戏连接传输。桥验证 Host、Bearer；只有下表的路由，没有 HTTP 授权入口；除文件写入和下表标注 JSON 的 POST 外，请求体必须为空，查询参数按路由白名单校验。CLI 发现 URL 必须是本机 HTTP 根地址，不允许远程地址、用户信息、查询或 fragment。存在 `client/endpoint.json` 时优先使用客户端桥；某个 MCP 连接一旦用过客户端桥，断线后对该游戏目录不回退到管理员本机接口（需重启 MCP 服务才能改用），断线删除 endpoint；仅残留 `client/` 目录（无 endpoint）不会遮蔽单人世界的端点。新桥标识变化时重新登记 MCP 会话。

| HTTP | 游戏 RPC |
| --- | --- |
| `GET /network` | capabilities |
| `POST /network/sessions/open` | session_open（`X-CCT-Open-Key`） |
| `POST /network/sessions/heartbeat`、`close` | session_heartbeat、session_close |
| `GET /computers` | computers |
| `POST /computers/{id}/control/acquire`、`release` | control_acquire、control_release |
| `GET /computers/{id}/control` | control_status |
| `GET /computers/{id}/status`、`screen` | status、screen（`after`、`kinds`、`tick`、`source`、`colors`、`regions`） |
| `GET /computers/{id}/files`、`trace`、`body` | files、trace、body（查询参数同本机端点） |
| `POST /computers/{id}/files`（JSON） | files_write（写入/追加/`op:"delete"`） |
| `POST /computers/{id}/event`、`power`、`peripheral`、`snapshot`（JSON） | event、power、peripheral、snapshot |

除 capabilities/open 外需 `X-CCT-Session`；除 acquire/control 查询外，对电脑的操作还需 `X-CCT-Lease`、`X-CCT-Lease-Epoch`。CLI 自动保管与附带这些凭据。它们用于 MCP 协作隔离，不提供同一操作系统用户下恶意进程隔离。

握手（hello）在同一连接内遇到超时、丢包或暂时不可用时按 1、2、4…30 秒退避重试，工具调用也会触发一次重试；只有协议不兼容（426）不再重试。握手完成前游戏内不显示授权按钮。

Forge 可选 SimpleChannel、NeoForge 可选 play payload 的封装编码维持 1，应用协议为 4（3 起有仅供游戏内图标查询本人当前界面授权状态的 `menu_grant`，本机 HTTP 桥没有对应路由；4 起开放控制操作，握手以 `control: true` 判定，`read_only` 恒为 false）；旧协议握手失败，不绕过会话锁。RPC 请求上限 10000 字符，响应 16 KiB 分块、总计 256 KiB，客户端最多 8 个待处理请求、10 秒超时，服务端每连接每秒 20 次请求/授权操作，本秒第一次超限回复 429，其余丢弃。主线程入队前限流和全服预算仍是待办。

## 构建与验收

```sh
./gradlew :mod-forge:test :mod-neoforge:test :cli:test assembleMods :cli:distZip
# 可选集成开发运行，带入 Chunks 自身的 Teams / Library / Architectury 依赖
./gradlew :mod-forge:runServer -PftbIntegration
./gradlew :mod-neoforge:runServer -PftbIntegration
```

测试替身覆盖二元授权策略、无领地例外、会话/锁生命周期与旧凭据、真实回环 HTTP 转发、不同 MCP 实例隔离和退出清理。它们不能替代有实际玩家和 FTB 领地的联调。具体执行结果见 [1021 验证记录](../../issues_to_fix/verification/network-1021-2026-10-21.md)。

真实游戏还需在两版分别验收：不同团队与盟友设置、交互/编辑四种组合、OP 绕过、`no_wilderness` 下的命令授权、同玩家两个 MCP、不同玩家抢锁、权限动态变更、电脑卸载/移动、断线重连、原生界面并存及超时恢复。跨维度已认领目标适配、口袋电脑、管理员强制交接和后台写任务仍未实现。
