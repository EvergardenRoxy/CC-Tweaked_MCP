# 目录结构

```text
issues_to_fix/ 目录结构（v0.2.3 核对，HEAD bf68ac4）

issues_to_fix/
├── README.md                        目录说明、issue schema、状态机、统计（95 条）
├── INDEX.md                         全量索引（95 行）
├── engine.md                        ENG-RT-* / ENG-FX-*（26 条）
├── cli.md                           CLI-*（17 条）
├── mod.md                           MOD-*（24 条）
├── build-ci.md                      BLD-*（15 条）
├── docs-project.md                  DOC-* / PROJ-*（13 条）
├── legacy-open.md                   旧 fix plan open 26 条的映射（fixed 18 / obsolete 3）
│
├── verification/                    核对侧（审查意见 → 结论 → 证据）
│   ├── README.md                    基线、结论枚举、与 issue 的映射
│   └── verification-table.md        全量核对表（高4/中19/低39/疑点3/正面6/文档5/项管12 + NEW + 偏差 + 动态记录）
│
└── evidence/                        原始证据
    ├── README.md                    证据分类、lane↔模块、基线、用法
    ├── lanes/                       A-F 六条核对 lane 完整报告
    │   ├── A-engine-runtime.md
    │   ├── B-engine-fixture.md
    │   ├── C-cli.md
    │   ├── D-mod.md
    │   ├── E-build-docs.md
    │   ├── F-legacy-plans.md
    │   └── task-cards/              派发任务卡 A-E（待核对条目与验证要求）
    │       ├── A-engine-runtime.md
    │       ├── B-engine-fixture.md
    │       ├── C-cli.md
    │       ├── D-mod.md
    │       └── E-build-docs.md
    └── dynamic/                     动态探针与运行记录
        ├── README.md                探针清单、命令、11 个 run 的观察、BOM 误差说明
        ├── scripts/                 9 个探针（lua/ps1/java/class）
        ├── fixtures/                17 个 fixture（nest_*、event_deep_*、energy_inf、inventory 等）
        └── runs/                    11 个 run 目录（01-08 正常记录，09-11 为 SOE 崩溃记录）
            ├── 01-debug_probe-*
            ├── 02-debug_probe2-*
            ├── 03-fs_write-*
            ├── 04-fs_move-*
            ├── 05-fs_move2-*
            ├── 06-energy_probe-*
            ├── 07-inventory_probe-*
            ├── 08-event_probe-*（BOM 导致语法错误，仅作历史）
            ├── 09-event_deep_20000-SOE-*
            ├── 10-event_deep_100000-SOE-*
            └── 11-event_deep_300000-SOE-*
```n