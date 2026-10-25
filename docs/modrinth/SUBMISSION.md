# Modrinth 提交清单（cct-live）

## 基本信息

| 字段 | 填写 |
|---|---|
| Name | `cct-live` |
| URL slug | `cct-live` |
| Summary | Let AI coding assistants (Claude Code, Codex, etc.) take over your CC: Tweaked computers through MCP, with your permission. They can read screens and traces, edit files, run Lua and type input. No in-game scripts or relay server needed. |
| Icon | `icon.png`（512×512 PNG） |
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
