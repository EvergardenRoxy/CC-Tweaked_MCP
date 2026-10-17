# 实机测试提示词：MPlayer 安装脚本（cct-live）

交给 AI 客户端的测试提示词，用来测试 cct-live 的控制工具和日志工具。使用前把 `<ID>` 换成测试电脑的 ID。

## 准备

1. `mods/` 里放最新的 cct-live jar 和 CC: Tweaked 1.120.2；客户端使用最新的 cct-run 发布包（20 个工具）。
2. MCP 配置里设置环境变量 `CCT_LIVE_DIR`，值为游戏目录（启动器开了版本隔离时是版本文件夹）。
3. `config/cctlive-common.toml`：`files.allow_write = true`（`cct_live_lua` 需要），`lease_seconds = 1800`。
4. 进入世界，准备一台新的**高级**电脑（安装界面要用鼠标操作），开机并记下 ID（在电脑里输入 `id`）；旁边可以接一个显示器（MPlayer 的主界面显示在显示器上）。
5. 可选：config 里打开 `recorder.enabled`，让 AI 也能看到接管之前的记录。
6. 如果客户端装了 `minecraft-cc-tweaked` Skill，保持启用（联合测试时用来检查 Skill 的引导是否有效）。

## 提示词

```text
你可以通过 MCP 服务器 cct-run 的 cct_live_* 工具，操作我正在运行的 Minecraft 游戏里的一台真实 CC: Tweaked 电脑
（Minecraft 1.20.1，CC: Tweaked 1.120.2，cct-live mod）。开始前先调用 cct_guide（section: 7）。

## 任务
在 <ID> 号电脑上测试这个自动安装脚本，它可能有问题：
    wget run https://git.liulikeji.cn/xingluo/MPlayer/raw/branch/master/release/install.lua
目标：完整走一遍安装流程，判断装完的程序能否正常启动；如果失败或表现异常，根据记录找出原因，并用实验证实。

## 授权范围
- <ID> 号是专门用来测试的电脑：可以接管它、输入命令、点击安装界面的按钮、重启它、用 cct_live_lua 做检查。
- 为了验证原因，可以临时修改这台电脑上的文件，但测试结束前要恢复成安装器产出的原样。
- 不要接管或操作其他电脑；不要修改脚本的仓库。
- 尽量不要发送 terminate（它等同于长按 Ctrl+T，会改变程序之后的行为）；确实需要时，在报告里说明之后的现象是它引起的。
- 如果某个工具返回 revoked（我在游戏里收回了控制权），立即停止，并问我是否继续。

## 工作方式（请遵守，这样能节省 token）
- 检查状态用 cct_live_status，等待用 cct_live_wait（等文字出现、等某类记录出现、或等画面停止变化），不要自己 sleep 再反复看屏幕。
- 查 trace 先用 summary: true，再用 kind + after: <seq> 只取需要的记录；程序崩溃先看 printed_error。
- 在电脑上查事实（文件大小、用 load() 检查语法、外设方法等）用 cct_live_lua，一次调用拿到结果；不要自己写脚本再读输出文件。
- 想看 HTTP 响应的完整内容：handle 记录里有 body 编号时，用 cct_live_body 读取。想跟踪某一个文件的下载和写入，用 cct_live_trace 的 contains（例如 contains: "User.lua"）。
- cct_live_lua 超时会返回 pending 名字：之后用 cct_live_lua {result: 名字} 取回结果，不要重新运行。
- 备份和恢复用 cct_live_snapshot，不要手动复制存档文件夹。
- 安装界面是 Basalt 图形界面，中文按钮是像素图，在 lines 里读不出来：用 cct_live_screen（regions: true）找按钮，点区域的 center；按钮和外面的卡片同色时，点区域里 labels 的 center。屏幕上的英文状态行（如 "Click Start."、"x/y 文件名"、"All files written."）可以直接读。
- 主界面可能显示在显示器上：cct_live_attach 返回的 monitors 里有显示器所在的面，查看和点击时加 source。
- 每点一次按钮，先用 cct_live_wait 确认有变化，再进行下一步。

## 步骤
1. cct_live_computers 确认电脑在线；cct_live_attach 接管 <ID> 号电脑（lease_seconds: 3600），记下 last_seq 和 monitors；安装完成后先 cct_live_snapshot {op: "save", name: "installed"} 保存安装器产出的原样，再做实验。
2. cct_live_type 输入上面的 wget run 命令，按界面完成安装：开始安装 → 下载 → 写入 → 重启。
3. 检查安装结果：写入了哪些文件、有没有内容异常的文件、重启后程序能否启动。
4. 如果出错：从 printed_error 出发，结合 api / handle 记录和 cct_live_body、cct_live_lua 找出原因，再用一次实验证实（例如临时替换一个文件后重启）。
5. 恢复电脑：cct_live_snapshot diff 看你改动了什么，restore 恢复成 installed 快照，再 diff 一次确认 identical；然后 cct_live_detach。

## 报告格式
1. 结果：安装成功 / 失败 / 部分成功（分别说明“安装器能否跑完”和“装完能否使用”）。
2. 时间线：关键步骤和对应的 tick / seq（请求了什么、结果如何、写入了哪些文件、点了哪些按钮）。
3. 发现的问题：每条写明现象、证据（记录类型、tick 或 seq、关键内容）、原因，以及你做过的验证实验。
4. 修复建议：针对安装脚本应该怎么改（只给建议）。
5. 测试结束时电脑的状态：恢复了哪些内容、删除了哪些文件（附最后一次 diff 的结果）。
6. 没覆盖到的部分，以及这套工具让你感到不便或不足的地方（这对改进工具很重要）。
```

## 测试后请记录

- 使用的 AI 客户端和模型，是否装了 Skill，是否开启了记录模式；
- 总 token、输出 token、工具调用次数（能导出会话记录最好）；
- AI 是否用了 `cct_live_status` / `cct_live_wait` / `cct_live_lua` / `cct_live_body` / `labels` / `cct_live_snapshot` / `contains`，是否恢复了电脑并释放了控制；
- 聊天栏是否出现了接管和释放的提示；`/cctlive detach` 能否收回控制。
