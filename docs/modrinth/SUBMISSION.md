# Modrinth 提交清单（cct-live）

## 基本信息

| 字段 | 填写 |
|---|---|
| Name | `cct-live` |
| URL slug | `cct-live` |
| Summary | Let AI coding assistants (Claude Code, Codex, etc.) take over your CC: Tweaked computers through MCP, with your permission. They can read screens and traces, edit files, run Lua and type input. No in-game scripts or relay server needed. |
| Icon | **需要你自己画**（见下方“披露与合规”第 2 条），512×512 PNG |
| Description | `description.md` 的全部内容 |
| Categories | Technology、Utility（可选加 Management） |
| Environment | Client: **Optional**，Server: **Required**（单人游戏装在客户端；多人网络桥要客户端和服务端都装） |

## 许可证

**MPL-2.0**（Mozilla Public License 2.0），选 Modrinth 里的 `MPL-2.0`。

依据：mod 的 `mods.toml` 和 `neoforge.mods.toml` 都写的是 `license="MPL-2.0"`，`mod/` 下所有源文件都是 `SPDX-License-Identifier: MPL-2.0`，仓库根目录的 `LICENSE` 也是 MPL-2.0。cct-live 的 jar 不打包 CC: Tweaked（CC: Tweaked 作为依赖单独安装），所以不用选 CCPL。

## 外部链接

| 字段 | 链接 |
|---|---|
| Issues | https://github.com/EvergardenRoxy/CC-Tweaked_MCP/issues |
| Source | https://github.com/EvergardenRoxy/CC-Tweaked_MCP |
| Wiki | https://github.com/EvergardenRoxy/CC-Tweaked_MCP/blob/main/mod/README.md |
| Discord | （暂无，留空） |

## 版本上传（每个 jar 单独一个版本）

| 文件 | Game version | Loader | 依赖 |
|---|---|---|---|
| `cct-live-1.20.1-0.3.0.jar` | 1.20.1 | Forge | CC: Tweaked（Required，版本 1.120.2）；FTB Chunks（Optional） |
| `cct-live-1.21.1-0.3.0.jar` | 1.21.1 | NeoForge | CC: Tweaked（Required，版本 1.120.2）；FTB Chunks（Optional） |

- Release channel 建议选 **Beta**（原型阶段）。
- Changelog 可以从 `docs/releases/0.3.0.md` 摘取。

## 披露与合规（依据 https://modrinth.com/legal/rules ）

1. **勾选 “Contains AI-generated content”**（§6.1，必须）。触发了三条：
   - 第 1 条：项目代码有相当大的部分是 AI 输出；
   - 第 3 条：mod 的功能本身就是供 AI 助手使用；
   - 第 4 条：项目页的描述由 AI 撰写。
   之后如有变化，要保持准确（§5.9）。
2. **图标和图片不能用 AI 生成或改编的**（§6.2 第 1 条）。之前那个 `icon.png` 是 AI 写脚本生成的，已经删除。图标、画廊截图要你自己画或者自己截图；游戏内实拍截图可以用。
3. **注意 §6.2 第 2 条**：如果项目内容“主要或全部”是 AI 输出，就不能公开发布。仓库 README 写着代码“主要……由 Claude Code 编写”，所以审核时有可能被认定为违规。发布之前请自己判断，必要时先在 Modrinth 支持渠道询问；披露必须如实，不要为了过审而改小 AI 的参与程度（§6 要求如实说明）。
4. **数据上传**（§1.11）：mod 不向第三方服务器上传数据，所以不违规；描述页的 “Network and data” 一节已经写清楚了网络行为。
5. **描述**（§2）：有英文版本和纯文本版本，标题没有用作正文，摘要不带格式，都已经满足。
6. **依赖**（§5.6）：每个版本都要在 Dependencies 里填 CC: Tweaked；cct-run 不在 Modrinth 上，所以只在描述里说明。
