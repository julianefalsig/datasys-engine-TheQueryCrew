#!/usr/bin/env python3
"""Plots experiment/results.csv as the two charts for the report.

    python3 experiment/plot-results.py            # writes experiment/plots/*.png and *.pdf
    python3 experiment/plot-results.py --linear-y # throughput on a linear y axis instead

throughput.{png,pdf}: maxRowsPerPartition (log x) against T_in = rowsIn / executeMs, one curve
per table size. The point is the median of the five warm runs; the bar spans their min and max.

pruning.{png,pdf}: maxRowsPerPartition (log x) against the share of partitions the planner
skipped, one curve per table size. This is why the throughput curve bends where it does.

Needs matplotlib (pip3 install matplotlib). Run from the repository root, like generate-data.py.
"""

import argparse
import csv
import pathlib
import sys

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import FuncFormatter, LogLocator, NullLocator
except ImportError:
    sys.exit("matplotlib is missing: pip3 install matplotlib")

RESULTS = pathlib.Path("experiment/results.csv")
CONDITIONS = pathlib.Path("experiment/conditions.txt")
OUT_DIR = pathlib.Path("experiment/plots")

# Fixed categorical order, one hue per table size from smallest to largest, so a size keeps its
# color in both charts.
SERIES_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100",
                 "#e87ba4", "#008300", "#6250d6", "#e34948"]
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"


def load_results():
    by_size = {}
    with RESULTS.open() as f:
        for row in csv.DictReader(f):
            size = int(row["table_rows"])
            by_size.setdefault(size, []).append({
                "partition_rows": int(row["max_rows_per_partition"]),
                "t_in_min": float(row["t_in_min"]),
                "t_in_median": float(row["t_in_median"]),
                "t_in_max": float(row["t_in_max"]),
                "pruned_pct": 100.0 * int(row["partitions_pruned"]) / int(row["partitions_total"]),
            })
    for cells in by_size.values():
        cells.sort(key=lambda c: c["partition_rows"])
    return dict(sorted(by_size.items()))


def machine_note():
    """'Apple M4, commit 808eb03' from conditions.txt, so a chart says where it was measured."""
    if not CONDITIONS.exists():
        return ""
    lines = CONDITIONS.read_text().splitlines()
    chip = next((l.strip() for l in lines if l.strip().startswith("Apple")), "")
    commit = next((l.split(":", 1)[1].strip() for l in lines if l.startswith("commit:")), "")
    return ", ".join(p for p in (chip, "commit " + commit if commit else "") if p)


def size_label(rows):
    if rows >= 1_000_000:
        return "%gM rows" % (rows / 1_000_000)
    return "%gK rows" % (rows / 1_000)


def thousands(value, _pos):
    return "{:,.0f}".format(value)


def partition_size(value, _pos):
    """2048 -> '2K': the full numbers collide at the right end of a log-2 axis."""
    return "%dK" % (value // 1024) if value >= 1024 else "%d" % value


def style_axes(ax, xs):
    ax.set_facecolor(SURFACE)
    ax.set_xscale("log", base=2)
    ax.set_xticks(xs)
    ax.xaxis.set_major_formatter(FuncFormatter(partition_size))
    ax.xaxis.set_minor_locator(NullLocator())
    ax.grid(True, which="major", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(MUTED)
    ax.tick_params(colors=INK_SECONDARY, labelsize=9)
    ax.xaxis.label.set_color(INK)
    ax.yaxis.label.set_color(INK)


def finish(fig, ax, name, title, note):
    ax.set_title(title, loc="left", color=INK, fontsize=12, pad=12)
    ax.legend(title="Table size", frameon=False, fontsize=9, title_fontsize=9,
              labelcolor=INK_SECONDARY, loc="center left", bbox_to_anchor=(1.01, 0.5))
    if note:
        fig.text(0.01, 0.01, note, color=MUTED, fontsize=8)
    fig.tight_layout(rect=(0, 0.03, 1, 1))
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for ext in ("png", "pdf"):
        path = OUT_DIR / ("%s.%s" % (name, ext))
        fig.savefig(path, dpi=200, facecolor=SURFACE)
        print("wrote", path)
    plt.close(fig)


def plot_throughput(by_size, xs, note, linear_y):
    fig, ax = plt.subplots(figsize=(8, 5), facecolor=SURFACE)
    for color, (size, cells) in zip(SERIES_COLORS, by_size.items()):
        x = [c["partition_rows"] for c in cells]
        median = [c["t_in_median"] for c in cells]
        below = [c["t_in_median"] - c["t_in_min"] for c in cells]
        above = [c["t_in_max"] - c["t_in_median"] for c in cells]
        ax.errorbar(x, median, yerr=[below, above], color=color, linewidth=2,
                    marker="o", markersize=5, markeredgecolor=SURFACE, markeredgewidth=1,
                    elinewidth=1, capsize=3, label=size_label(size))
    style_axes(ax, xs)
    if not linear_y:
        ax.set_yscale("log")
        ax.yaxis.set_major_locator(LogLocator(base=10, subs=(1, 2, 5)))
        ax.yaxis.set_minor_locator(NullLocator())
    ax.yaxis.set_major_formatter(FuncFormatter(thousands))
    ax.set_xlabel("maxRowsPerPartition (rows per partition, log scale, K = 1,024)")
    ax.set_ylabel("Throughput T_in (rows / ms%s)" % ("" if linear_y else ", log scale"))
    finish(fig, ax, "throughput",
           "SELECT throughput by partition size, WHERE distance > 990 (1% selectivity)", note)


def plot_pruning(by_size, xs, note):
    fig, ax = plt.subplots(figsize=(8, 5), facecolor=SURFACE)
    for color, (size, cells) in zip(SERIES_COLORS, by_size.items()):
        ax.plot([c["partition_rows"] for c in cells], [c["pruned_pct"] for c in cells],
                color=color, linewidth=2, marker="o", markersize=5,
                markeredgecolor=SURFACE, markeredgewidth=1, label=size_label(size))
    style_axes(ax, xs)
    ax.set_ylim(-2, 100)
    ax.set_xlabel("maxRowsPerPartition (rows per partition, log scale, K = 1,024)")
    ax.set_ylabel("Partitions pruned (% of partitions)")
    finish(fig, ax, "pruning",
           "Share of partitions skipped by min/max pruning, WHERE distance > 990", note)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--linear-y", action="store_true",
                        help="draw throughput on a linear instead of a log y axis")
    args = parser.parse_args()

    if not RESULTS.exists():
        sys.exit("%s not found - run this from the repository root" % RESULTS)
    by_size = load_results()
    if len(by_size) > len(SERIES_COLORS):
        sys.exit("more table sizes than colors (%d > %d)" % (len(by_size), len(SERIES_COLORS)))
    xs = sorted({c["partition_rows"] for cells in by_size.values() for c in cells})
    note = machine_note()

    plot_throughput(by_size, xs, note, args.linear_y)
    plot_pruning(by_size, xs, note)


if __name__ == "__main__":
    main()
