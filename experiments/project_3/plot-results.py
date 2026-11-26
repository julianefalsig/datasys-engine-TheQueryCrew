#!/usr/bin/env python3
"""Turns the engine's own analysis output into the benchmark's chart and tables.

    python3 experiments/project_3/plot-results.py

Reads results/analysis-*.csv, which run-benchmark.sh wrote by joining the ingested log with
runs.csv through the engine. Each row is the seven log fields followed by r_session, r_config and
r_repetition. Writes:

    results/runtime.{png,pdf}  end-to-end durationMs per configuration: every run as a dot, the median as a bar
    results/runtime.csv        median, min and max durationMs per configuration
    results/joins.md           estRows against rowsOut per join and configuration, plus the chosen join order

Needs matplotlib (pip3 install matplotlib). Run from the repository root.
"""

import csv
import pathlib
import re
import statistics
import sys

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:
    sys.exit("matplotlib is missing: pip3 install matplotlib")

RESULTS = pathlib.Path("experiments/project_3/results")

CONFIGS = ["min", "max", "optimized_min_from", "optimized_max_from"]
# "small to big" is FROM customers, shipments, orders; "big to small" is FROM orders, shipments, customers.
LABELS = {
    "min": "syntax\nsmall to big",
    "max": "syntax\nbig to small",
    "optimized_min_from": "optimized\nsmall to big",
    "optimized_max_from": "optimized\nbig to small",
}

# One measure, so one hue; the configuration is on the x axis, not in the color.
SERIES = "#2a78d6"
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
GRID = "#e1e0d9"


def rows(class_name):
    path = RESULTS / f"analysis-{class_name}.csv"
    if not path.exists():
        sys.exit(f"missing {path}: run run-benchmark.sh first")
    with path.open() as f:
        # The log message never holds a comma (the engine writes it CSV-safe), so 10 fields per row.
        return [line for line in csv.reader(f) if len(line) == 10]


def field(message, name):
    match = re.search(rf"\b{name}=(\S+)", message)
    return match.group(1) if match else None


def runtimes():
    by_config = {config: [] for config in CONFIGS}
    for line in rows("Executor"):
        message, config = line[6], line[8]
        if message.startswith("select_complete ") and config in by_config:
            by_config[config].append(int(field(message, "durationMs")))
    for config, values in by_config.items():
        if not values:
            sys.exit(f"no select_complete lines for {config}")
    return by_config


def joins():
    """One entry per configuration and join number; a mismatch between repetitions is reported."""
    seen = {}
    for line in rows("HashJoinOperator"):
        message, config = line[6], line[8]
        key = (config, int(field(message, "join")))
        value = (field(message, "tables").replace(";", ", "), field(message, "estRows"), int(field(message, "rowsOut")))
        if key in seen and seen[key] != value:
            print(f"warning: {key} differs between repetitions: {seen[key]} vs {value}", file=sys.stderr)
        seen.setdefault(key, value)
    return seen


def join_orders():
    chosen = {}
    for line in rows("JoinOrder"):
        message, config = line[6], line[8]
        if message.startswith("statement=SELECT joinOrder="):
            chosen.setdefault(config, field(message, "joinOrder").replace(";", ", "))
    return chosen


def write_runtime_csv(by_config):
    with (RESULTS / "runtime.csv").open("w", newline="") as f:
        out = csv.writer(f)
        out.writerow(["config", "runs", "median_ms", "min_ms", "max_ms"])
        for config in CONFIGS:
            values = by_config[config]
            out.writerow([config, len(values), statistics.median(values), min(values), max(values)])


def write_joins_md(join_rows, chosen):
    lines = ["| Configuration | Join | Tables (build, then probe) | estRows | rowsOut |",
             "|---|---|---|---|---|"]
    for (config, number), (tables, est, out) in sorted(join_rows.items(),
                                                        key=lambda item: (CONFIGS.index(item[0][0]), item[0][1])):
        lines.append(f"| {config} | {number} | {tables} | {est} | {out:,} |")
    lines += ["", "| Configuration | Chosen join order |", "|---|---|"]
    lines += [f"| {config} | {chosen.get(config, '?')} |" for config in CONFIGS]
    (RESULTS / "joins.md").write_text("\n".join(lines) + "\n")


def plot(by_config):
    fig, ax = plt.subplots(figsize=(8, 4.5), facecolor=SURFACE)
    ax.set_facecolor(SURFACE)
    for x, config in enumerate(CONFIGS):
        values = by_config[config]
        median = statistics.median(values)
        ax.bar(x, median, width=0.5, color=SERIES, alpha=0.25, zorder=1)
        ax.scatter([x] * len(values), values, s=36, color=SERIES, edgecolor=SURFACE, linewidth=2, zorder=3)
        ax.annotate(f"{median:,.0f} ms", (x, median), xytext=(0, 6), textcoords="offset points",
                    ha="center", color=INK, fontsize=9)
    ax.set_xticks(range(len(CONFIGS)), [LABELS[c] for c in CONFIGS], fontsize=9, color=INK_SECONDARY)
    ax.set_ylabel("End-to-end runtime, durationMs (ms)", color=INK_SECONDARY)
    ax.set_ylim(bottom=0)
    ax.set_title("Join order: runtime per configuration (bar = median, dots = each run)", color=INK, fontsize=11)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=INK_SECONDARY)
    fig.tight_layout()
    for suffix in ("png", "pdf"):
        fig.savefig(RESULTS / f"runtime.{suffix}", dpi=200)
    print(f"wrote {RESULTS}/runtime.png and runtime.pdf")


def main():
    global RESULTS
    if len(sys.argv) > 1:
        RESULTS = pathlib.Path(sys.argv[1])
    by_config = runtimes()
    write_runtime_csv(by_config)
    write_joins_md(joins(), join_orders())
    plot(by_config)
    for config in CONFIGS:
        values = by_config[config]
        print(f"{config}: median {statistics.median(values)} ms, min {min(values)}, max {max(values)}, runs {len(values)}")


if __name__ == "__main__":
    main()
