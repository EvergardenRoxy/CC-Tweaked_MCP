#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Draws the README charts (docs/images/*-light.svg and *-dark.svg) from the measured data below.

Data sources: mod/README.md "性能" (V16 in handoff.md) and docs/joint-test-factory-report.md.
Colours: categorical slots 1-3 of the dataviz reference palette, validated for both modes
(adjacent and all-pairs); every bar carries a visible value label (the light aqua is below 3:1).

Usage: python3 scripts/readme_charts.py
"""
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "docs" / "images"

THEMES = {
    "light": {
        "surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "muted": "#898781",
        "grid": "#e1e0d9", "axis": "#c3c2b7", "band": "#f0efec",
        "series": ["#2a78d6", "#eb6834", "#1baf7a"],
    },
    "dark": {
        "surface": "#1a1a19", "ink": "#f0efec", "ink2": "#c3c2b7", "muted": "#898781",
        "grid": "#2c2c2a", "axis": "#383835", "band": "#2a2a28",
        "series": ["#3987e5", "#d95926", "#199e70"],
    },
}
FONT = 'system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'

# cct-live overhead: change in loop rate against "no mod" (30 computers, tight loops; averages of 2 runs).
PERF_WORKLOADS = ["事件循环", "文件读写", "写屏幕"]
PERF_ROWS = [
    ("没有接管（平时）", "0.94 ms", [-1, 8, 0]),
    ("打开记录模式", "1.01 ms", [-7, -8, -5]),
    ("30 台全部接管", "1.22 ms", [-18, -18, -10]),
]
PERF_NOISE = 5  # run-to-run spread of the no-mod baseline, %

# Joint test 3 (two runs of the factory task) and joint test 4 (order line), see docs/*-report.md.
RUNS = [
    "联合测试 3 第 1 次：开源 DeepSeek（算力中心部署），cct-run 0.1.0",
    "联合测试 3 第 2 次：deepseek-v4.1-flash（官方 API），cct-run 0.1.0",
    "联合测试 4：deepseek-v4.1-flash（官方 API），cct-run 0.2.0 + Skill 1.2.0",
]
METRICS = [
    ("任务时长（分钟）", [117, 60, 38], "{:.0f}"),
    ("工具调用（次）", [576, 268, 187], "{:.0f}"),
    ("总 token（百万）", [92.9, 70.6, 43.8], "{:.1f}"),
    ("输出 token（万）", [75.1, 24.5, 19.9], "{:.1f}"),
    ("上下文压缩（次）", [46, 0, 0], "{:.0f}"),
    ("请玩家配合（次）", [0, 17, 5], "{:.0f}"),
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, fill, size=12, anchor="start", weight="normal"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" fill="{fill}" font-size="{size}" '
            f'text-anchor="{anchor}" font-weight="{weight}">{esc(s)}</text>')


def bar(x0, x1, y, h, fill, surface):
    """Horizontal bar from the baseline x0 to x1, 4px rounded at the data end only."""
    if abs(x1 - x0) < 0.5:
        return ""
    left, right = min(x0, x1), max(x0, x1)
    r = min(4, (right - left) / 2, h / 2)
    if x1 >= x0:  # data end on the right
        d = (f"M{left:.1f},{y:.1f} H{right - r:.1f} Q{right:.1f},{y:.1f} {right:.1f},{y + r:.1f} "
             f"V{y + h - r:.1f} Q{right:.1f},{y + h:.1f} {right - r:.1f},{y + h:.1f} H{left:.1f} Z")
    else:
        d = (f"M{right:.1f},{y:.1f} H{left + r:.1f} Q{left:.1f},{y:.1f} {left:.1f},{y + r:.1f} "
             f"V{y + h - r:.1f} Q{left:.1f},{y + h:.1f} {left + r:.1f},{y + h:.1f} H{right:.1f} Z")
    return f'<path d="{d}" fill="{fill}"/>'


def text_width(s, size=12):
    """Rough width: CJK and full-width characters are one em, everything else about 0.58 em."""
    return sum(size if ord(c) > 0x2e80 else size * 0.58 for c in s)


def legend(x, y, names, t):
    out, cx = [], x
    for i, name in enumerate(names):
        out.append(f'<rect x="{cx:.1f}" y="{y - 9:.1f}" width="10" height="10" rx="2" fill="{t["series"][i]}"/>')
        out.append(text(cx + 16, y, name, t["ink2"], 12))
        cx += 16 + text_width(name) + 24
    return out


def svg(width, height, body, t, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" font-family=\'{FONT}\' role="img" aria-label="{esc(title)}">\n'
            f'<title>{esc(title)}</title>\n'
            f'<rect width="{width}" height="{height}" rx="8" fill="{t["surface"]}"/>\n'
            + "\n".join(b for b in body if b) + "\n</svg>\n")


def perf_chart(t):
    W, H = 760, 356
    left, right = 150, W - 110          # plot area; the right column holds the tick time
    lo, hi = -20, 10
    sx = lambda v: left + (v - lo) / (hi - lo) * (right - left)
    title = "cct-live 的性能开销"
    body = [text(24, 34, title, t["ink"], 17, weight="600"),
            text(24, 56, "30 台电脑一刻不停地跑循环（极端情况），相对“不装 mod”的循环次数变化", t["ink2"], 12)]
    body += legend(24, 84, PERF_WORKLOADS, t)
    top, bh, gap, group = 104, 12, 2, 58
    bottom = top + group * len(PERF_ROWS) - 8
    # Noise band: differences inside it cannot be told apart from run-to-run spread.
    body.append(f'<rect x="{sx(-PERF_NOISE):.1f}" y="{top - 6}" width="{sx(PERF_NOISE) - sx(-PERF_NOISE):.1f}" '
                f'height="{bottom - top + 6}" fill="{t["band"]}"/>')
    for v in range(lo, hi + 1, 5):
        x = sx(v)
        body.append(f'<line x1="{x:.1f}" y1="{top - 6}" x2="{x:.1f}" y2="{bottom}" stroke="{t["grid"]}" stroke-width="1"/>')
        body.append(text(x, bottom + 18, f"{v:+d}%" if v else "0", t["muted"], 11, "middle"))
    body.append(f'<line x1="{sx(0):.1f}" y1="{top - 6}" x2="{sx(0):.1f}" y2="{bottom}" stroke="{t["axis"]}" stroke-width="1.5"/>')
    body.append(text(right + 24, top - 12, "tick 耗时", t["muted"], 11))
    for r, (name, mspt, values) in enumerate(PERF_ROWS):
        y0 = top + r * group
        body.append(text(left - 12, y0 + 1.5 * (bh + gap) + 4, name, t["ink"], 12, "end"))
        body.append(text(right + 24, y0 + 1.5 * (bh + gap) + 4, mspt, t["ink2"], 12))
        for i, v in enumerate(values):
            y = y0 + i * (bh + gap)
            body.append(bar(sx(0), sx(v), y, bh, t["series"][i], t["surface"]))
            lx = sx(v) + (6 if v >= 0 else -6)
            body.append(text(lx, y + bh - 2, f"{v:+d}%" if v else "0%", t["ink2"], 11, "start" if v >= 0 else "end"))
    body.append(text(24, H - 34, f"灰色带：两轮“不装 mod”之间本身就有约 ±{PERF_NOISE}% 的波动，带内的差别测不出来。不装 mod 时 tick 耗时 1.01 ms（预算 50 ms）。", t["muted"], 11))
    body.append(text(24, H - 16, "Forge 47.4.10 正式服务器，Java 17，4 核；每种配置两轮，每轮预热 20 秒后测 30 秒。", t["muted"], 11))
    return svg(W, H, body, t, title)


def runs_chart(t):
    W = 760
    cols, rows = 3, 2
    pw, ph = 226, 104
    left, top = 24, 84 + 20 * len(RUNS) + 4
    H = top + rows * ph + 36
    title = "AI 实测：联合测试 3 与 4"
    body = [text(24, 34, title, t["ink"], 17, weight="600"),
            text(24, 56, "用 Create 和 Advanced Peripherals 外设写工厂控制程序，先在模拟器里测，再到游戏里验收", t["ink2"], 12)]
    for i, name in enumerate(RUNS):                  # one legend row per run: the names are long
        body += legend(24, 84 + 20 * i, [name], {**t, "series": t["series"][i:]})
    bh, gap = 14, 2
    for k, (name, values, fmt) in enumerate(METRICS):
        px = left + (k % cols) * (pw + 16)
        py = top + (k // cols) * ph
        body.append(text(px, py + 14, name, t["ink"], 12, weight="600"))
        span = pw - 50                      # room for the value label
        vmax = max(values) or 1
        base = px
        for i, v in enumerate(values):
            y = py + 26 + i * (bh + gap)
            x1 = base + v / vmax * span
            body.append(bar(base, x1, y, bh, t["series"][i], t["surface"]))
            body.append(text(x1 + 6, y + bh - 3, fmt.format(v), t["ink2"], 11))
        body.append(f'<line x1="{base:.1f}" y1="{py + 22}" x2="{base:.1f}" y2="{py + 26 + 2 * bh + gap + 4}" stroke="{t["axis"]}" stroke-width="1.5"/>')
    body.append(text(24, H - 30, "两次测试的任务不同（工厂看板 / 订单产线），只能粗略比较。联合测试 4 的时长只算任务部分，token 是整个会话。", t["muted"], 11))
    body.append(text(24, H - 14, "数据来自会话记录和客户端统计；每个小图各用自己的刻度。详见 docs/joint-test-factory-report.md、docs/joint-test-orders-report.md。", t["muted"], 11))
    return svg(W, H, body, t, title)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for mode, t in THEMES.items():
        (OUT / f"perf-{mode}.svg").write_text(perf_chart(t), encoding="utf-8")
        (OUT / f"joint-test-{mode}.svg").write_text(runs_chart(t), encoding="utf-8")
    print("wrote", sorted(p.name for p in OUT.glob("*.svg")))


if __name__ == "__main__":
    main()
