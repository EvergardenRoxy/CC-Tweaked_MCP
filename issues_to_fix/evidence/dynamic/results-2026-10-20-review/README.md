# 2026-10-20：上传工程报告的 main 复核证据

源码基线：`25a392102b1ce47c32743564298df0622b04e9ca`，main，0.2.4。核对对象是用户上传的 2026-10-19 报告，其原基线为 `3808530` / 0.2.2；不是 2026-10-18 的 `bf68ac4` / 0.2.3 报告。

本目录只增加本轮证据，不覆盖以前的 lane、探针、运行结果或核对表。

## 执行

从上述 main 新建文档分支后，在完整 JDK 21 上执行：

```sh
GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --offline --no-daemon --console=plain :cli:installDist --dependency-verification strict
python3 issues_to_fix/evidence/dynamic/results-2026-10-20-review/probe.py \
  --launcher cli/build/install/cct-run/bin/cct-run \
  --jdk /workspace/.tools/cct-jdk17/jdk-17.0.20.1+1 \
  --output issues_to_fix/evidence/dynamic/results-2026-10-20-review/observations.json
```

`JAVA_HOME` 需指向本机完整 JDK；命令中的缓存/JDK 路径只是本轮环境，复现时替换成本机路径。installDist 构建通过，9 个任务中 1 个执行、8 个复用已有输出；不冒充全部源码重新编译或全量测试。探针在 Java 17 上执行，临时文件自动清理，不接入游戏，不读写玩家世界。

## 观察与适用范围

| 观察 | 实际结果 | 解释 |
|---|---|---|
| 非严格 JSON ping | 未加引号的键、单引号字符串被接受 | 确认 CLI-18 |
| 协商 2025-03-26 后发送批量 ping | 协商成功，数组返回 -32600 | 确认 CLI-07 的剩余问题 |
| boolean id 后跟合法 ping | 前者 -32600，后者成功 | 验证拒绝无效 id 后服务继续运行，不是全面 id 测试 |
| fixture 与程序同名 | status=ok，notes 有 fixture_file_overwritten | 原“静默覆盖”描述已过时；不等于禁止覆盖 |
| 公开 Trace writer API | 文件超过 1 MiB，具体字节数在 JSON | 支持 ENG-RT-18；不是整段 CLI 负载或无限增长实测 |

`observations.json` 保存响应、源码基线、jar hash、Java 版本和执行时工作区状态。文档/探针可有未提交变动，脚本额外检查 runtime/build 源码与基线无差异；不把该状态伪装成干净发布验证。

`documentation-checks.json` 记录本轮文档校验：单 issue/单 YAML 块、必需字段与枚举、重复键/ID、模块与索引状态一致、统计、fixed_in 提交存在且属于基线历史、原证据未覆盖、变更文档本地链接以及原报告 hash。原证据目录的 119 个文件字节不变，3 个导航 README 只在末尾追加入口；原 95 个条目的 evidence 文本仍保留在对应字段开头。

## 边界

本轮没有执行 Windows 文件竞争复现、第三方模组外设并发验证或完整游戏服务器回归；没有修改 Java/Lua 实现、依赖版本或批准/取消策略。相关结论分别注明“已有修复”“部分确认”或“无法验证”。本轮无需重新手填项目测试总数，历史 CI/修复报告不作为本次新执行结果。
