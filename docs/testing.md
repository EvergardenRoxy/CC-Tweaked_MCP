# cct-run 测试指南（维护者用）

这份指南用于在 **Windows**（必测）和 macOS（选测）上验证 cct-run。开发环境只有 Linux，下面这些问题必须在真机上确认：

- `cct-run.bat` 在 PowerShell 和 cmd 里能否正确接收参数（含空格的参数、`--` 分隔符）
- 控制台编码对 JSON 输出的影响
- 含空格、含中文的路径
- Java 能否被找到（很多玩家的 Java 只在 Minecraft 启动器目录里）
- DeepSeek Harness（dsh）、Claude Code、Codex 能否在 Windows 上实际调用 cct-run

> 测试包里带有 CC: Tweaked 的 jar（CCPL / MPL-2.0 许可，见 `THIRD-PARTY-NOTICES.md`），仅供测试，请勿公开转发。CCPL 要求使用者拥有正版 Minecraft。

---

## 1. 准备

1. **解压测试包**：先解压到**不含中文**的路径，例如 `C:\cct-run`。脚本会自己测试中文路径。解压后的结构：
   ```
   cct-run\
     bin\cct-run.bat      bin\cct-run
     lib\*.jar
     examples\            scripts\            docs\testing.md
     LICENSE  LICENSES\   THIRD-PARTY-NOTICES.md
   ```
2. **确认 Java（17 或更高）**：在 PowerShell 里运行：
   ```powershell
   java -version
   ```
   - 能显示版本，就可以直接进入第 2 步。
   - 提示找不到 `java`：说明 Java 不在 PATH 上。这正是要收集的信息，请**先照常跑第 2 步的脚本**，脚本会搜索本机的 `java.exe` 并写进报告。然后从报告的 `java_candidates` 里挑一个版本 ≥ 17 的路径，设置 `JAVA_HOME`（填 `bin\java.exe` 的上两级目录），再跑一次：
     ```powershell
     $env:JAVA_HOME = "C:\...\java-runtime-gamma"   # 举例：bin\java.exe 所在目录的上一级
     ```

## 2. 运行自动测试脚本（约 30–60 秒）

在解压目录里打开 PowerShell。

**Windows PowerShell 5.1**（系统自带，必测）：
```powershell
cd C:\cct-run
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1 -Report report-ps51.json
```

**PowerShell 7**（如果装了，选测）：
```powershell
pwsh -File scripts\smoke-test.ps1 -Report report-ps7.json
```

**macOS / Linux**（选测）：
```bash
bash scripts/smoke-test.sh          # 生成 cct-run-smoke-report.txt
```

每个用例输出一行 `PASS` 或 `FAIL`。**无论成败，都请把报告文件发回来**（`report-ps51.json` 等）。

### 脚本测了什么

| 用例 | 验证内容 |
|---|---|
| version | 启动器能找到 Java 并运行；CC:T 版本是 1.120.2 |
| args-with-space-and-separator | `--` 分隔符和带空格的参数 `"b c"` 能否正确经过 `.bat` 传给程序 |
| runtime-error / syntax-error | 运行时错误、语法错误能给出行号和错误阶段 |
| fixture-wired-network-monitor | fixture、有线网络、显示器、物品转移 |
| trace-latest / screen-latest-monitor | 时间线查询、显示器屏幕重建 |
| redstone-timeline-terminate | 红石时间线、注入 Ctrl+T |
| scrolled-output | 滚出屏幕的输出被完整记录 |
| timeout / too-long-without-yielding | 超时；死循环约 7 秒后被终止 |
| path-with-space-and-cjk | 程序、fixture、输出目录的路径都含空格和中文 |
| utf8-stdout | 把控制台设为 UTF-8 后，JSON 里的非 ASCII 字符是否正确 |
| usage-error / missing-program | 退出码 64、65 |
| startup-time | 单次运行耗时（看 `seconds`） |

报告里还有几项附加信息：
- `default_encoding_check`：不把控制台改成 UTF-8 时，中文字符会不会乱码。结果是 `false` 不算失败，但说明 AI 读取输出前需要先切换编码。
- `cmd_exe_exit`：从 cmd 直接调用 `.bat` 的结果。
- `code_page`：当前代码页。
- `java_on_path`、`java_candidates`：本机能找到的 Java，只记录路径。

## 3. 手动检查（约 5 分钟）

**cmd.exe**：按 `Win+R` 输入 `cmd`，然后运行：
```bat
cd C:\cct-run
bin\cct-run.bat run examples\hello.lua -- a "b c"
echo %ERRORLEVEL%
```
预期：输出的 JSON 里 `"status": "ok"`，`screen.lines` 第二行是 `args:\ta\tb c`，最后打印 `0`。

**双击 / 资源管理器**：不需要测，cct-run 是命令行工具。

## 4. AI 客户端实测（重点）

在 **Windows** 上用各个客户端打开解压目录，把下面这段话原样发给 AI：

> 在当前目录运行 `bin\cct-run.bat run examples\runtime_error.lua`。它会输出 JSON。告诉我 `status`、`exit_code`、`error.message`，以及程序第几行出错。然后运行 `bin\cct-run.bat trace latest --kind event` 并告诉我记录了几条事件。

每个客户端记录这几项：

| 客户端 | 能否执行（是/否） | 读到的 error.message | 读到的行号（应为 4） | 有无乱码或报错 |
|---|---|---|---|---|
| DeepSeek Harness（dsh） | | | | |
| Codex | | | | |
| Claude Code | | | | |

如果某个客户端失败，请附上它的完整回复，或者终端输出的截图。

## 5. 需要发回的东西

1. `report-ps51.json`（必需）、`report-ps7.json` 或 `cct-run-smoke-report.txt`（如果测了）
2. 第 3 步 cmd 的输出（复制文本即可）
3. 第 4 步的表格
4. 系统信息：Windows 版本、使用的启动器（官方、Prism、CurseForge、HMCL、PCL 等）、是否装了杀毒软件

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| "无法加载文件……因为在此系统上禁止运行脚本" | 用第 2 步的 `-ExecutionPolicy Bypass` 写法运行 |
| `cct-run launcher not found` | 用 `-CctRun C:\cct-run\bin\cct-run.bat` 指定路径 |
| 提示找不到 `java` / `JAVA_HOME is set to an invalid directory` | 见第 1 步，设置 `JAVA_HOME` |
| 所有用例都很慢，或被拦截 | 可能是杀毒软件在扫描 jar，请在报告里注明 |
| 中文路径用例失败 | 请保留报告，这正是需要知道的结果 |
