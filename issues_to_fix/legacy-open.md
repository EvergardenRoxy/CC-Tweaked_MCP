# 旧 fix plan 状态与合并映射（v0.2.2 遗留）

本文件合并本工作区上一轮（v0.2.2，2026-10-18 早）的三份旧计划：
- `D:/AI/DSH/cc-t/review-report.md`（旧审查报告 47 条唯一问题）
- `D:/AI/DSH/cc-t/fix-plan.md`（B0/B1 修复 + P1-P7）
- `D:/AI/DSH/cc-t/easy-fixes-plan.md`（A 批 A1-A12 / B 批 / C 批）

核对基线：当前 HEAD `bf68ac4`（v0.2.3）。**旧计划中仍存在的问题已合并进本目录的模块文件**，本文件只保留状态与映射，不再重复 issue 正文。
核对明细：`D:/AI/DSH/cc-t/CC-Tweaked_MCP/issues_to_fix/evidence/lanes/F-legacy-plans.md`。

## 统计（47 条唯一旧问题）

| 状态 | 数量 | 说明 |
|---|---|---|
| fixed | 18 | 0.2.3 代码已修（含 `8c72a78`/`c144c72`/`edb13b3`/`0fdf64f`/`325f25b`/`ddea1a6` 等提交） |
| open | 26 | 已全部映射到本目录 issue（见下表） |
| obsolete | 3 | L-memory（非 bug）、L-badrun（随 B2 容错消失）、L-mirror-iteration（mod 类已移除，engine 侧 A8 已修） |

A 批 12 条：11 fixed、仅 A9（endpoint token）未落地；B 批 6 条：1 fixed（L-body）、5 open；C 批 6 条：全 open；fix-plan P1-P7：全 fixed（P4 属 note 级缓解而非彻底拒绝覆盖）。

## open 26 条的映射（旧编号 → 本目录 issue）

| 旧编号 | 旧级别 | 摘要 | 合并到 |
|---|---|---|---|
| OLD-B3 | 中 | cct-live trace 内存索引 kinds 无上限 | MOD-01 |
| OLD-B4 | 中 | trace 轮换/读取竞争 + 写失败永久停写 | MOD-17 |
| OLD-B7 | 中 | 被杀/超时运行无 summary、无 killed 标记 | ENG-RT-17 |
| OLD-M2 | 中 | body 文件与 sessions 目录从不清理 | MOD-18 |
| OLD-M3 | 中 | 快照 save() 非原子 | MOD-19 |
| OLD-M4 | 中 | 重复电脑 ID 时 trace/快照目录冲突 | MOD-20 |
| OLD-L-runid | 低 | run_id 随机段仅 4 位 hex | ENG-RT-16 |
| OLD-L-pacer-interrupt | 低 | 中断被报成 timeout | ENG-RT-03 |
| OLD-L-active-leak | 低 | pool.submit 失败时 active 泄漏 | CLI-14 |
| OLD-L-kill-files | 低 | 强杀后删临时 stderr，缺现场 | CLI-15 |
| OLD-L-pending | 低 | pendingLua 无 TTL | CLI-16 |
| OLD-L-remember | 低 | rememberScreens 失败清空基线无提示 | CLI-17 |
| OLD-L-endpoint-token | 低 | endpoint.json URL 未校验可外发 token（A9，handoff N14 标 deferred） | CLI-01 |
| OLD-L-reflect | 低 | 三处 CC:T 私有字段反射无适配层 | MOD-04 |
| OLD-L-host | 低 | Host 头缺失时放行 | MOD-06 |
| OLD-L-hash | 低 | screen_hash 32 位 | MOD-16 |
| OLD-L-token-perm | 低 | token.txt 未设 0600 | MOD-07 |
| OLD-L-callid | 低 | peripheral call id 每 VM 一个 | MOD-23 |
| OLD-L-datalength | 低 | dataLength(String) 用字符数非字节数 | MOD-11 |
| OLD-L-readonly-regex | 低 | READ_ONLY 名称启发式 | MOD-13 |
| OLD-L-available-collision | 低 | side/网络名同 map 静默覆盖 | MOD-24 |
| OLD-L-2threads | 低 | 2 线程处理 + body 无超时 | MOD-08 |
| OLD-L-ticks-int | 低 | summary seq/tick 强转 int | MOD-16 |
| OLD-L-drain | 低 | attach 时 drain 早于 trace 锁 | MOD-21 |
| OLD-L-unload | 低 | 区块卸载误报 removed | MOD-22 |
| OLD-L-build | 低 | mod 构建缺 toolchain / cobalt 未用 | BLD-15 |

## fixed 18 条（不再跟踪，供回归参考）

旧 report 中 B0（Windows 盘符路径逃逸，真实复现并修复）、B1（timeline item 槽越界崩溃）、B2（trace 半行容错）、B5（trace 配额按字节）、B6（cct_live_wait 条件/总时长）、M1（onServer Future 取消，cancel(false) 已加）、旧 A1-A8、A10-A12、P1-P7 等已在 0.2.3 代码中修复；详见 `evidence/lanes/F-legacy-plans.md` 的逐条表。
注意：`handoff.md:677`（N14）与 `docs/release-notes-0.2.3.md:77` 的遗留清单已滞后（B5/A4、旧 M1/A12、body 上限、ticks=- 其实已修），修复清单以本目录与 F 报告为准。
