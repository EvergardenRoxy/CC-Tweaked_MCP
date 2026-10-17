# 实机测试提示词：MPlayer 安装脚本（cct-live）

交给 AI 客户端的测试提示词，用来测试 cct-live 的控制工具和日志工具。使用前把 `<ID>` 换成测试电脑的 ID。

## 准备

1. `mods/` 里放最新的 cct-live jar 和 CC: Tweaked 1.120.2；客户端使用最新的 cct-run 发布包。
2. MCP 配置里设置环境变量 `CCT_LIVE_DIR`，值为游戏目录（启动器开了版本隔离时是版本文件夹）。
3. 进入世界，准备一台新的**高级**电脑（安装界面要用鼠标操作），开机并记下 ID（在电脑里输入 `id`）。
4. 可选：config 里打开 `recorder.enabled`，让 AI 也能看到接管之前的记录。

## 提示词

```text
你可以通过 MCP 服务器 cct-run 的 cct_live_* 工具，操作我正在运行的 Minecraft 游戏里的一台真实 CC: Tweaked 电脑
（Minecraft 1.20.1，CC: Tweaked 1.120.2，cct-live mod）。第一次调用前，先调用 cct_guide（参数 section: 7）阅读第 7 节。
注意节省 token：日常检查用 cct_live_status，等待用 cct_live_wait（不要自己 sleep 再反复看屏幕），需要细节时再用 cct_live_trace（先用 summary: true）或 cct_live_screen。

## 任务
在 <ID> 号电脑上测试这个自动安装脚本，它可能有问题：
    wget run https://git.liulikeji.cn/xingluo/MPlayer/raw/branch/master/release/install.lua
目标：完整走一遍安装流程，判断它能否成功安装；如果失败或表现异常，根据记录找出原因。

## 授权范围
- <ID> 号是专门用来测试的电脑，你可以接管它、在里面运行命令、点击安装界面的按钮，安装完成后也可以重启它。
- 不要接管或操作其他电脑；不要删除这台电脑上与本次安装无关的文件。
- 如果某个工具返回 revoked（我在游戏里收回了控制权），立即停止，并问我是否继续。

## 操作步骤
1. 用 cct_live_computers 确认 <ID> 号电脑存在并且已开机；用 cct_live_screen 看它当前的屏幕，确认处在 shell 提示符下。
2. 用 cct_live_attach 接管 <ID> 号电脑（可以传 lease_seconds: 3600），并记下返回的 last_seq。
3. 用 cct_live_type 在 shell 里输入上面那条 wget run 命令（一行，会自动回车）。
4. 用 cct_live_screen 和 cct_live_trace 观察进度。trace 记录很多，请按类型筛选，并用 ticks="<上次的 tick>-" 或 after=<seq> 只看新增的记录：
   - 网络：kind=["api","event"]，看 http.request 请求了哪些 URL，以及对应的 http_success / http_failure 事件；
   - 下载和写入的内容：kind=["handle"]（readAll 是读到的内容，write 是写进文件的内容，都只保留前 200 个字符）；
   - 文件操作：kind=["api"]，看 fs.open / fs.makeDir 的路径和结果；
   - 出错：kind=["vm_error"]，以及屏幕上的错误信息。
5. 这个安装程序是 Basalt 图形界面，要用鼠标操作：
   - 点击方法：用 cct_live_screen（regions: true）找到按钮所在的颜色区域，再用 cct_live_click 点它的 center。坐标从 1 开始，x 是列，y 是行。
   - 按钮和标题上的中文是画成像素图的，在屏幕的 lines 里读不出来，只能靠颜色区域定位。按钮在屏幕下方，左右各一个。
   - 如果电脑旁边有显示器，程序的主界面可能显示在显示器上：用 cct_live_screen / cct_live_status 的 source 参数（显示器所在的面，见 cct_live_attach 返回的 monitors）查看，用 cct_live_click 的 source 参数触摸。
   - 屏幕中间那行英文状态是普通文字，可以直接读到（例如 "Click Start."、"x/y 文件名"、"All files written."），用它来判断进度。
   - 预期流程：开始安装 → 下载 → 写入 → 完成后选择重启或稍后。每点一次按钮，都要先确认屏幕或 trace 有变化，再进行下一步；没有变化时先查原因，不要连续乱点。
6. 安装完成后（或失败时），用 cct_live_type 输入 ls（必要时加上目录名）检查写入了哪些文件。如果流程成功，可以重启电脑，看安装的程序能否正常启动。
7. 测试结束后用 cct_live_detach 释放这台电脑。

## 注意
- trace 只保留数据的前 200 个字符。如果你能访问网络，可以按 trace 里的 URL 自己读取完整内容（安装脚本、install_list.lua 等）来辅助判断，但结论要以 trace 和屏幕上的实际记录为准。
- 网络请求可能要几秒到几十秒。等待时每隔几秒查看一次屏幕，不要频繁重复同一个操作。
- 电脑存储空间有限（默认 1 MB）。如果出现写入失败或空间不足，记下 fs.getFreeSpace 相关的提示和已经写入的文件。

## 报告格式
1. 结果：安装成功 / 失败 / 部分成功。
2. 时间线：关键步骤和对应的 tick（发起了哪些请求、各自的结果、写入了哪些文件、点击了哪些按钮）。
3. 发现的问题：每条写明现象、trace 或屏幕上的证据（记录类型、tick、关键内容）、你判断的原因。
4. 修复建议：针对安装脚本应该怎么改（只给建议，不要去改脚本的仓库）。
5. 这次测试没覆盖到的部分，以及这套工具让你感到不便或不足的地方（这对改进工具很重要）。
```

## 测试后请记录

- 使用的 AI 客户端，以及是否开启了记录模式；
- AI 的报告，以及它是否正确完成了接管、输入、点击、查询 trace 和释放；
- 聊天栏是否出现了接管和释放的提示；`/cctlive detach` 能否收回控制。
