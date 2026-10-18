# 安全政策

## 支持的版本

| 版本 | 支持状态 |
|---|---|
| 最新 0.2.x release | ✅ 接收安全报告 |
| 更早版本 | ❌ 请先升级到最新 release |

## 报告漏洞

**请不要开公开 issue、不要在讨论区贴 PoC。**

唯一渠道：GitHub 仓库的 **Security → Report a vulnerability**（私密安全公告，仅维护者可见）。

提交时请尽量包含：

- 受影响版本 / commit，以及组件（cct-run / cct-live mod / 构建发布物）
- 环境：操作系统、Java 版本、Minecraft / Forge / NeoForge / CC: Tweaked 版本
- 复现步骤或最小 PoC（fixture、Lua 程序、HTTP 请求）
- 影响：读取/写入宿主文件、绕过鉴权、RCE、崩溃或拒绝服务等
- 是否已公开、是否希望匿名、是否愿意在致谢中署名

如果 Security 页面暂时没有 “Report a vulnerability” 按钮，说明私密报告尚未启用；请先不要公开细节，待维护者启用后再提交。

## 响应预期

本项目由个人维护，响应为尽力而为：

- 3 个工作日内确认收到；
- 7 天内给出初步评估（是否在范围内、严重性）；
- 修复时间视严重性而定，目标 30 天内；复杂问题会同步进展；
- 不提供漏洞赏金。

## 安全模型与范围

本项目包含：本地 CLI 与无头引擎、本地 stdio MCP 服务器、游戏内 cct-live HTTP 服务（仅绑定 127.0.0.1，Bearer token，Host 校验）。

### 在范围内

- cct-live：鉴权绕过、Host / DNS rebinding、路径穿越（读 / 写 / 快照）、越权外设调用、RCE；
- cct-run / MCP：逃出内存文件系统、读取宿主文件、协议处理导致的崩溃或内存耗尽；
- 发布物与构建链：release 资产被替换、CI 注入；
- 默认安装或默认配置导致的权限问题。

### 不在范围内

- 已经能读写同一用户文件或进程的本机攻击者（`token.txt`、`endpoint.json` 本身可被同权限进程读取）；
- Minecraft / CC: Tweaked / Forge / NeoForge 上游漏洞（请报给对应项目）；
- 需要物理接触、社会工程，或仅影响本机且无跨用户影响的拒绝服务；
- 未按文档配置的用法（例如把 HTTP 端点暴露到公网、自行修改绑定地址）。

## 协调披露

修复版本发布后公开细节；默认在修复发布后 90 天内披露，或与报告者协商提前/延后。会在 release notes 或 GitHub Security Advisory 中致谢（除非要求匿名）。

历史：0.2.2 及更早的 files 端点存在 Windows 盘符路径逃逸，已在 0.2.3 修复。

## 用户侧加固建议

- 不要修改 127.0.0.1 绑定，不要把端口转发到公网；
- `token.txt` 仅本人可读（POSIX 下 0600）；
- 只从 GitHub Releases 或 Modrinth 下载发布物。
