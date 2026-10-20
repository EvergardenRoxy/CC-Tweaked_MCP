# network-test：第一版 Minecraft 网络桥

本分支顺序实现网络方案的第 1、2 步：客户端入口、双向通道、版本/能力握手，以及游戏内单台电脑的查看授权、授权列表、状态和当前屏幕。覆盖 Forge 47 / Minecraft 1.20.1 与 NeoForge 21.1 / Minecraft 1.21.1；两者都使用 CC:T 1.120.2。

```text
AI / MCP 客户端
  ↕ stdio
玩家本机 cct-run mcp
  ↕ 带本机令牌的 127.0.0.1 HTTP
玩家 Minecraft 客户端 cct-live
  ↕ 已有 Minecraft 连接的自定义数据包
Minecraft 服务端 cct-live → CC:T
```

远程服务器不需要额外开放 HTTP 或 MCP 端口。客户端和服务端都安装对应版本的 mod。Forge 与 NeoForge 是两个独立的游戏版本，网络实现各自适配加载器，不支持跨 Minecraft 版本互连。

## 使用

1. 构建并安装对应版本的 jar，客户端同时使用本分支构建的 `cct-run`。
2. 服主在服务端 `config/cctlive-common.toml` 中确认 `[network] enabled = true`。默认只有权限等级 2 的 OP 能授予查看权限；允许普通玩家自己授权时，设置 `network.allow_player_grants = true` 并重启服务器。菜单访问不代表所有权；开启前应确认服务器现有电脑界面/领地访问规则符合需求。
3. 玩家进入服务器，客户端自动握手并建立本机桥。打开目标 CC 电脑界面，点击右上角的“允许 AI 查看”。服务端验证当前菜单、菜单编号、`stillValid`、CC:T `checkUsable` 和玩家权限。聊天栏显示授权结果。
4. 使用原来的 MCP 配置：`cct-run mcp --live <玩家客户端游戏目录>`，或设置 `CCT_LIVE_DIR`。无需填写服务器 HTTP 地址或复制服务端令牌。
5. 调用 `cct_live_computers`：只列出当前玩家连接已授权的电脑。然后调用 `cct_live_status` 或 `cct_live_screen`。屏幕支持 `source`、`colors` 和 `regions`，包括直接连接的显示器。
6. 在界面点击“撤销 AI 查看”，或执行 `/cctlive network revoke <电脑 ID>`。`/cctlive network list` 只列出自己的网络查看授权。

授权固定持续 30 分钟，读取不续期，重新点击允许会重新计时。离开电脑界面后仍可查看。撤销、断线、服务器关闭、电脑实例移除/卸载后失效；重新连接或电脑重新加载都需要再次授权。每个连接最多 32 台授权电脑。

本轮只授予 `observe`，不创建控制 attachment 或 trace。`attached: false` 表示没有网络控制 attachment，不影响已授权查看。状态包含当前屏幕和 `screen_hash`，并标注 `trace_available: false`；不会读取其他会话的历史 trace。`cct_live_wait` 可以基于当前屏幕的文本或静止时间等待，没有 trace 事件条件可用。

文件、Lua、输入、开关机、快照、trace、HTTP body、外设查询和 HTTP attach/detach 都被网络桥拒绝，后续步骤另行实现。MCP 工具目录暂时保留这些已有工具；网络列表中的 `read_only` 和 `capabilities` 说明本轮可用范围。授权操作只能从游戏界面触发，HTTP 入口没有授权接口。

## 本机发现和兼容

客户端桥使用动态回环端口，写入：

```text
<客户端游戏目录>/cct-live/client/endpoint.json
<客户端游戏目录>/cct-live/client/token.txt
```

令牌只存在玩家本机，不通过游戏数据包发送；POSIX 系统使用仅文件所有者可读写的权限。桥验证 Host、Bearer 令牌和空请求体，拒绝所有非 GET 操作。额外的 `GET /network` 返回当前服务器协商的协议和能力。

`LiveTools` 检测到 `cct-live/client/` 目录后始终使用客户端桥；断线删除 endpoint 后返回未连接，不会降级使用根目录的旧接口。旧的纯服务端/未启用过客户端桥的本机环境仍使用 `cct-live/endpoint.json`。单人世界也可以通过集成服务器上的同一套游戏通道查看。

原来的服务端管理员本机 HTTP 功能保留，可在服务端设置 `[http] enabled = false`；这不影响新的网络通道。网络模式不能访问它，也不会得到其地址或令牌。

## 协议与边界

- Forge 使用可选的 `SimpleChannel`，NeoForge 使用可选的 play payload，允许对端未安装网络桥时正常游戏。客户端对未响应的握手设有超时，不开放可用的 RPC。
- 传输封装版本为 `1`。应用握手包含协议版本 `1`，返回 `computers/status/screen` 能力；客户端同时校验版本和必需能力。未来若修改数据包编码，应修改封装版本和兼容策略。
- 每个 RPC 带随机请求 UUID 与连接 epoch。服务端身份取自数据包上下文的玩家，授权绑定实际连接和电脑实例对象/UUID。重连、重新握手会清空旧授权；数字 ID 重复时拒绝按 ID 读取。
- 请求上限 4096 个字符；响应以 16 KiB 分块，总计不超过 256 KiB，客户端严格顺序组装。超大结果返回 413，不传任意文件路径或任意 HTTP 目标。
- 每个客户端最多 8 个待处理 RPC，单请求 10 秒超时。每个服务端玩家连接每秒最多处理 20 个网络请求/授权操作，超过部分丢弃。HTTP 使用有界工作队列和请求体截止时间。
- 客户端发送调度到客户端线程；服务端处理调度到服务端线程。网络线程和客户端主线程不阻塞等待 RPC 返回。

实现分布：共享的 `NetworkProtocol/NetworkClient/NetworkBridge/NetworkGrants/NetworkServer`，以及两个加载器各自的 `LiveNetwork/LiveClient`。`NetworkClientUi` 仅由物理客户端订阅器加载，专用服务器入口不引用客户端类。

## 验证

自动检查：

```sh
./gradlew :mod-forge:test :mod-neoforge:test :cli:test assembleMods
```

新增检查覆盖授权隔离、撤销、固定到期、电脑 ID 复用、重复 ID、授权数量上限、分块 Unicode/边界、乱序/重复/旧会话响应、握手能力验证，以及真实回环 HTTP 的令牌、操作白名单、断线清理和 MCP 发现优先级。

本轮实际验证（2026-10-20）：

| 项目 | 结果 |
| --- | --- |
| Forge 1.20.1 / 47.4.10 | 构建通过；33 个测试全部通过 |
| NeoForge 1.21.1 / 21.1.262 | 构建通过；33 个测试全部通过 |
| CLI | 40 个通过、1 个跳过；跳过的是需要额外 Java 8 安装的旧启动器检查 |
| 两种专用开发服务器 | 均在 `http.enabled = false` 下启动完成，CC:T terminal/wired_query 能力可用，无客户端类加载失败；检查后通过终止信号触发保存与关闭 |
| 真实客户端联调 | 当前环境没有图形客户端，未验证界面按钮与真实玩家连接的数据包往返；按下方清单验收 |

专用服务器检查使用离线模式和回环地址，只验证启动与客户端类隔离。Java 单测里的回环 HTTP/RPC 使用内存游戏传输替身，不代表已完成真实游戏端到端测试。

以下真实游戏验收应分别在 Forge 1.20.1 和 NeoForge 1.21.1 执行；单元测试不代替客户端/服务器联调：

1. 专用服务器设置 `http.enabled = false`，两个客户端进入；两边握手后列表均为空。
2. OP 打开电脑并允许查看，仅该玩家本机 MCP 能列出它；另一玩家仍看不到。
3. 非 OP 在默认配置下授权被拒绝；服主允许普通玩家授权后，只有打开有效电脑菜单时才能授权。
4. 电脑运行打印、变色、移动光标的 Lua 程序，比较 MCP status/screen 与游戏界面；验证直接连接显示器的 source/colors/regions。
5. 打开界面撤销、命令撤销、断线重连、切服、卸载区块和授权到期后，列表及读取立即失效；同数字 ID 的替代电脑不继承授权。
6. HTTP POST、文件/trace/body/历史屏幕访问被拒绝；停用服务端 HTTP 时三种读取仍可用。
7. 客户端连接没有 cct-live 的服务器仍能游戏，MCP 返回未就绪/握手超时。没有客户端 mod 的玩家可正常进入启用 cct-live 的服务器。
8. 客户端退出后确认 `client/endpoint.json` 删除，MCP 不会落回集成服务器旧接口。
