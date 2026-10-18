# INDEX — issues_to_fix（95 条）

字段：`id | severity | confidence | status | module | title | origin`。正文见对应模块文件。基线 `bf68ac4`（v0.2.3）。

| id | severity | confidence | status | module | title | origin |
|---|---|---|---|---|---|---|
| ENG-RT-01 | high | confirmed | open | engine-runtime | fs 写入无容量限制可写爆堆；OOM 按文案归因 | REVIEW-H2/L-Oom |
| ENG-RT-02 | high | partial | open | engine-runtime | debug 工具箱完整可用；可写 _G 伪造 OOM/timeout | REVIEW-H1/NEW-1 |
| ENG-RT-03 | low | confirmed | open | engine-runtime | 中断被报成 timeout | REVIEW-E2 |
| ENG-RT-04 | low | partial | open | engine-runtime | close(50ms) 不等线程退出（嵌入泄漏） | REVIEW-E3 |
| ENG-RT-05 | low | confirmed | open | engine-runtime | harness fs.open 无 nil 检查 | REVIEW-E4 |
| ENG-RT-06 | low | confirmed | open | engine-runtime | CoreConfig 静态跨 run 残留 | REVIEW-L-Cfg |
| ENG-RT-07 | low | confirmed | open | engine-runtime | maxTicks int 溢出（库调用面） | REVIEW-L-Ovf |
| ENG-RT-08 | low | confirmed | open | engine-runtime | Trace close 后静默丢记录 | REVIEW-L-Close |
| ENG-RT-09 | info | confirmed | open | engine-runtime | 每条记录 flush（有意权衡） | REVIEW-L-Flush |
| ENG-RT-10 | low | confirmed | open | engine-runtime | TraceQuery 缺字段 NPE 不计 skipped | REVIEW-L-TQ |
| ENG-RT-11 | low | partial | open | engine-runtime | storedName 畸形 programName | REVIEW-L-Name |
| ENG-RT-12 | low | confirmed | open | engine-runtime | Clock tick/ms 亚毫秒错位 | REVIEW-L-Clock |
| ENG-RT-13 | low | confirmed | open | engine-runtime | run/loop 巨型方法与魔法数 | REVIEW-L-Big |
| ENG-RT-14 | low | confirmed | open | engine-runtime | RootMount 两步改名非原子 | REVIEW-NEW-2 |
| ENG-RT-15 | low | confirmed | open | engine-runtime | 保护链外 OOM 不进入状态判定 | REVIEW-NEW-4 |
| ENG-RT-16 | low | confirmed | open | engine-runtime | run_id 随机段仅 4 位 | LEGACY-L-runid |
| ENG-RT-17 | medium | confirmed | open | engine-runtime | 被杀/超时 run 无 summary/标记 | LEGACY-B7 |
| ENG-FX-01 | medium | confirmed | open | engine-fixture | 默认值两阶段重复、异常串耦合 | REVIEW-F2 |
| ENG-FX-02 | medium | confirmed | open | engine-fixture | 深嵌套 event.args → SOE 无 summary | REVIEW-F4/NEW-1 |
| ENG-FX-03 | low | confirmed | open | engine-fixture | longValue 只查下限、Infinity 饱和 | REVIEW-F3 |
| ENG-FX-04 | low | partial | open | engine-fixture | timeline 不排序/全表扫描/过期静默 | REVIEW-F5/NEW-2 |
| ENG-FX-05 | low | confirmed | open | engine-fixture | internal 错误入 trace；notes 配额 | REVIEW-F6/NEW-6 |
| ENG-FX-06 | info | partial | open | engine-fixture | trace 数值类型有界化未文档化 | REVIEW-F8 |
| ENG-FX-07 | low | partial | open | engine-fixture | HashMap 键序非插入序 | REVIEW-F9 |
| ENG-FX-08 | low | partial | open | engine-fixture | getInt 小数截断（与游戏一致，缺测试） | REVIEW-F10 |
| ENG-FX-09 | low | confirmed | open | engine-fixture | ItemStack.count 线程约束未文档化 | REVIEW-F7/NEW-5 |
| CLI-01 | medium | confirmed | open | cli | endpoint.json URL 未校验即发 token | REVIEW-C1/LEGACY-A9 |
| CLI-02 | low | partial | open | cli | stdin 无行长上限、serve 不捕 Error | REVIEW-C2 |
| CLI-03 | medium | confirmed | open | cli | 深嵌套 id 序列化 SOE 打死服务 | REVIEW-NEW-1 |
| CLI-04 | low | confirmed | open | cli | cancelled 只杀子 JVM，live 不中断 | REVIEW-C3 |
| CLI-05 | low | confirmed | open | cli | JavaFinder 信任 release/取最高版本 | REVIEW-C4 |
| CLI-06 | low | confirmed | open | cli | JSON-RPC 小偏差（无 id 静默丢弃等） | REVIEW-L5 |
| CLI-07 | low | confirmed | open | cli | 声明 2025-03-26 但拒绝批量 | REVIEW-L6 |
| CLI-08 | low | confirmed | open | cli | EOF 后 awaitTermination 2h 不取消 | REVIEW-L7 |
| CLI-09 | info | confirmed | open | cli | 错误消息含 endpoint URL（非令牌） | REVIEW-L10 |
| CLI-10 | low | confirmed | open | cli | wait 超时分类依赖文案子串 | REVIEW-L11 |
| CLI-11 | low | partial | open | cli | 参数校验双实现/严格度不一致 | REVIEW-L14 |
| CLI-12 | low | partial | open | cli | 测试盲区（Launcher/伪造 endpoint 等） | REVIEW-L15 |
| CLI-13 | low | confirmed | open | cli | OOM 逃逸时 summary 写进 MCP 流 | REVIEW-NEW-2 |
| CLI-14 | low | confirmed | open | cli | pool.submit 失败时 active 泄漏 | LEGACY-L-active-leak |
| CLI-15 | low | confirmed | open | cli | 强杀后删 stderr，超时缺现场 | LEGACY-L-kill-files |
| CLI-16 | low | confirmed | open | cli | pendingLua 无 TTL | LEGACY-L-pending |
| CLI-17 | low | confirmed | open | cli | rememberScreens 失败清空基线 | LEGACY-L-remember |
| MOD-01 | medium | partial | open | mod | kinds 索引无界增长、rotate 不裁剪 | REVIEW-M1/LEGACY-B3 |
| MOD-02 | low | confirmed | open | mod | 平台层与构建脚本高度重复 | REVIEW-M2 |
| MOD-03 | medium | partial | open | mod | 端口固定无回退、bind 失败文案矛盾 | REVIEW-M3 |
| MOD-04 | low | confirmed | open | mod | 反射绑定 CC:T 私有字段 | REVIEW-M4/LEGACY-L-reflect |
| MOD-05 | low | partial | open | mod | HTTP/trace 层无自动化测试 | REVIEW-M5 |
| MOD-06 | low | confirmed | open | mod | Host 缺失时放行 | REVIEW-L1/LEGACY-L-host |
| MOD-07 | low | confirmed | open | mod | token.txt 未设 POSIX 0600 | REVIEW-L2/LEGACY-L-token-perm |
| MOD-08 | low | partial | open | mod | 2 线程 + body 无读超时 | REVIEW-L3/LEGACY-L-2threads |
| MOD-09 | low | partial | open | mod | trace/回放全文件扫描 | REVIEW-L4 |
| MOD-10 | low | partial | open | mod | 500 回显内部消息；坏 manifest 拖垮 list | REVIEW-L5 |
| MOD-11 | low | partial | open | mod | 字符/字节、大整数、双重序列化 | REVIEW-L6/LEGACY-L-datalength |
| MOD-12 | low | partial | open | mod | 会话目录秒级碰撞/endpoint 残留 | REVIEW-L7/NEW-2 |
| MOD-13 | low | cannot-static | open | mod | READ_ONLY 名称启发式 | REVIEW-D1/LEGACY-L-readonly |
| MOD-14 | low | confirmed | open | mod | onServer 超时后已开始任务仍执行 | REVIEW-NEW-1 |
| MOD-15 | low | confirmed | open | mod | recordAt 半行容错缺失可 500 | REVIEW-NEW-3 |
| MOD-16 | low | confirmed | open | mod | screen_hash 32 位；seq/tick 强转 int | REVIEW-NEW-4/LEGACY-L-hash/ticks |
| MOD-17 | medium | confirmed | open | mod | trace 轮换/读取竞争、写失败永久停写 | LEGACY-B4 |
| MOD-18 | medium | confirmed | open | mod | body 文件与 sessions 从不清理 | LEGACY-M2 |
| MOD-19 | medium | confirmed | open | mod | 快照 save() 非原子 | LEGACY-M3 |
| MOD-20 | medium | confirmed | open | mod | 重复电脑 ID 的 trace/快照冲突 | LEGACY-M4 |
| MOD-21 | low | confirmed | open | mod | attach 时 drain 竞态 | LEGACY-L-drain |
| MOD-22 | low | confirmed | open | mod | 区块卸载误报 removed | LEGACY-L-unload |
| MOD-23 | low | confirmed | open | mod | peripheral call id 每 VM 一个 | LEGACY-L-callid |
| MOD-24 | low | confirmed | open | mod | side/网络名重名静默覆盖 | LEGACY-L-available-collision |
| BLD-01 | medium | confirmed | open | build-ci | CI 只跑单测，smoke/供应链未接入 | REVIEW-H3 |
| BLD-02 | medium | confirmed | open | build-ci | 版本双源，release 不校验 tag | REVIEW-H4 |
| BLD-03 | medium | confirmed | open | build-ci | release 重新构建、不复用 artifact | REVIEW-B1 |
| BLD-04 | medium | confirmed | open | build-ci | 无 CodeQL/dependency-review/wrapper 校验 | REVIEW-B2 |
| BLD-05 | medium | confirmed | open | build-ci | mod 手写 gson 2.10.1 vs catalog 2.11.0 | REVIEW-B3 |
| BLD-06 | medium | partial | open | build-ci | 两份 mod 构建脚本 ~80% 重复 | REVIEW-B4 |
| BLD-07 | medium | confirmed | open | build-ci | .gitattributes 缺 text=auto，行尾漂移 | REVIEW-B4/NEW-5 |
| BLD-08 | low | confirmed | open | build-ci | smoke 报告落仓库根、gitignore 未覆盖 | REVIEW-#8 |
| BLD-09 | low | partial | open | build-ci | 触发器残留已合并分支、PR 双跑 | REVIEW-#9/NEW-4 |
| BLD-10 | low | confirmed | open | build-ci | wrapper 无 SHA256；actions 用主版本 tag | REVIEW-#10/NEW-6 |
| BLD-11 | medium | confirmed | open | build-ci | 发布物无签名/校验和、jar glob 宽松 | REVIEW-#11 |
| BLD-12 | info | confirmed | open | build-ci | NOTICES 手工维护无 CI 绑定 | REVIEW-#12 |
| BLD-13 | info | confirmed | open | build-ci | smoke sh/ps1 对等性（信息） | REVIEW-#13 |
| BLD-14 | medium | confirmed | open | build-ci | tag 后 9 提交版本号未变，新 tag 资产名不符 | REVIEW-NEW-3 |
| BLD-15 | low | confirmed | open | build-ci | mod 构建缺 toolchain、cobalt 未用 | LEGACY-L-build |
| DOC-01 | medium | confirmed | open | docs | 测试数字 54/81/41 三方漂移；过期记录随包 | REVIEW-DOC1/NEW-1/2 |
| DOC-02 | low | confirmed | open | docs | handoff.md 被当作用户 LIMITATIONS 出处 | REVIEW-DOC2 |
| DOC-03 | low | partial | open | docs | docs 受众混杂（5 份 joint-test 等） | REVIEW-DOC3 |
| DOC-04 | low | partial | open | docs | macOS 未测但安装节无提示 | REVIEW-DOC5 |
| DOC-05 | low | confirmed | open | docs | 三处测试记录手工同步 | REVIEW-DOC4 |
| PROJ-01 | low | confirmed | open | project | description/topics/homepage 全空 | REVIEW-PROJECT |
| PROJ-02 | medium | confirmed | open | project | 无 SECURITY.md（已有安全修复史） | REVIEW-PROJECT |
| PROJ-03 | low | confirmed | open | project | 缺 CONTRIBUTING/COC/CHANGELOG/模板 | REVIEW-PROJECT |
| PROJ-04 | low | confirmed | open | project | issues 零使用、同名 PR 噪声 | REVIEW-PROJECT |
| PROJ-05 | low | confirmed | open | project | release 命名混杂、0.2.1 跳号 | REVIEW-PROJECT |
| PROJ-06 | low | confirmed | open | project | 未上架 Modrinth/CurseForge | REVIEW-PROJECT |
| PROJ-07 | low | confirmed | open | project | 分支/提交身份卫生 | REVIEW-PROJECT |
| PROJ-08 | low | confirmed | open | project | bus factor=1、AI 贡献流程未文档化 | REVIEW-PROJECT |
