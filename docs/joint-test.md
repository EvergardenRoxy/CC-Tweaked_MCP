# 联合测试：Skill + cct-run（模拟器与实机）

同时测试两部分：

- [CC-Tweaked_LLM_skill](https://github.com/EvergardenRoxy/CC-Tweaked_LLM_skill) 是否能引导 AI 规范地使用测试工具；
- cct-run 本身（模拟器 `cct_run`，以及实机工具 `cct_live_*`）是否好用。

一共两个测试：

- **测试 1**：看 AI 写程序时会不会先在模拟器里测试，再在实机上验证；
- **测试 2**：重测 MPlayer 安装脚本，和前几轮比较 token 用量。

## 准备

| 项目 | 要求 |
|---|---|
| Skill | 客户端里安装最新的 `minecraft-cc-tweaked` Skill（包含 `references/testing.md`） |
| cct-run | 最新发布包，MCP 已配置好，并设置了 `CCT_LIVE_DIR`（见 Skill 仓库 INSTALL.md 的“配合 cct-run 使用”一节） |
| cct-live mod | 最新 jar；`config/cctlive-common.toml` 里设置 `files.allow_write = true`（`cct_live_lua` 需要）、`lease_seconds = 1800` |
| 游戏 | 进入世界。测试 1 需要一台高级电脑，后面接一个箱子（里面放几种物品），旁边接一个显示器；测试 2 需要一台新的高级电脑 |
| 确认 | 对 AI 说“列出 cct-run 的工具”，应该能看到 18 个工具（`cct_run`、`cct_guide`、`cct_live_lua` 等） |

每个测试都在**新开的对话**里进行，避免前一个测试的上下文影响结果。

## 测试 1：写程序 → 模拟器测试 → 实机部署

目的：检查 Skill 的流程（先拆解和对齐，再写代码，交付前测试，如实写出没覆盖的部分），以及模拟器和实机工具是否配合得好。

把 `<ID>` 换成那台电脑的 ID：

```text
帮我写一个 CC: Tweaked 程序：电脑后面接了一个箱子，旁边接了一个显示器，
在显示器上显示箱子里每种物品的数量，每 5 秒刷新一次。
写好之后先在模拟器里测试；测试通过后，部署到我游戏里的 <ID> 号电脑上运行，确认显示器显示正确。
<ID> 号电脑可以接管，可以写入程序文件（startup.lua 以外的文件名），测完请释放。
```

需要观察：

1. AI 有没有按 Skill 的要求，先确认需求（比如箱子在哪一面、显示器多大），再写代码。
2. 交付前有没有调用 `cct_run`，fixture 是否模拟了箱子和显示器，回答里有没有【测试】一节，有没有写出模拟没覆盖的部分。
3. 实机部署时：
   - 有没有用 `cct_live_files` 写入程序、用 `cct_live_type` 运行；
   - 有没有用 `cct_live_status` / `cct_live_wait` 等待结果，而不是反复轮询；
   - 有没有用 `source` 查看显示器上的画面；
   - 测完有没有释放。

## 测试 2：MPlayer 安装脚本复测

使用 [`live-test-mplayer.md`](live-test-mplayer.md) 里的提示词，把 `<ID>` 换成新电脑的 ID，并在末尾加一段：

```text
补充要求：
- 在电脑上做检查时用 cct_live_lua（一次调用拿到结果），不要自己写脚本再读输出文件；
- 想看 HTTP 响应的完整内容时用 cct_live_body；
- 测试结束后把电脑恢复成安装器产出的原样，并列出你删除和还原过的文件。
```

需要观察：

- 和上一轮相比：总 token 数、工具调用次数、在被测电脑上写过多少个临时文件；
- 有没有用到 `cct_live_lua`、`cct_live_body` 和 `regions` 的 `labels`；
- 报告的结论是否和前几轮一致：WAF 拦截了 URL 里带 `user` 的请求，导致 `icons/User.lua` 下载到的是 HTML。

## 记录结果

| 项目 | 测试 1 | 测试 2 |
|---|---|---|
| 客户端 / 模型 | | |
| 总 token / 输出 token | | |
| 工具调用次数（能导出会话记录最好） | | |
| 是否先对齐需求 / 是否在模拟器测试 | | — |
| 是否用了 status / wait / lua / body | | |
| 是否释放了电脑、清理了临时文件 | | |
| AI 报告的工具问题 | | |

前几轮的数据（作为对照）：第二轮 1530 万 token（输出 17.9 万）；第三轮 1945 万 token（输出 25.1 万，deepseek-v4.1-flash）。
