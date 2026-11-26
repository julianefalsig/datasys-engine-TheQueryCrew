#!/usr/bin/env bash
# Exercise 12's join-order benchmark: one three-way join in its min and max orders, forced with
# -j syntax, plus the optimizer's own choice from both FROM lists.
#
# Usage, from anywhere, after `mvn package` and generate-data.py:
#   experiments/project_3/run-benchmark.sh
#   REPS=7 CITY=city007 experiments/project_3/run-benchmark.sh
#   DATA=experiments/project_3/data-skew RESULTS=experiments/project_3/skewed_results \
#       CITY=city000 experiments/project_3/run-benchmark.sh
#
# Every execution is a fresh JVM (cold JIT) over an OS page cache that the load and the earlier
# runs have warmed. One extra run per configuration comes first and is discarded. The measured
# runs go round-robin over the configurations, so a drift on the machine hits all of them alike.
#
# The engine runs in work/, so its data/ and logs/ stay out of the repository's own. Afterwards the
# log is ingested and joined with results/runs.csv through the engine itself (analysis.sql).

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$HERE/work
DATA=${DATA:-$HERE/data}
RESULTS=${RESULTS:-$HERE/results}
# DATA and RESULTS may be given relative to the caller's cwd.
DATA=$(cd "$DATA" && pwd)
RESULTS=$(mkdir -p "$RESULTS" && cd "$RESULTS" && pwd)
LOG=$WORK/logs/engine.log
REPS=${REPS:-5}
CITY=${CITY:-city042}

# name | -j mode | FROM list. The predicates are the same in every configuration.
CONFIGS=(
    "min|syntax|customers, shipments, orders"
    "max|syntax|orders, shipments, customers"
    "optimized_min_from|optimized|customers, shipments, orders"
    "optimized_max_from|optimized|orders, shipments, customers"
)

engine() {
    # Runs the engine in work/ and fails loudly, with its stderr, if the engine does.
    local out=$1
    shift
    if ! (cd "$WORK" && "$ROOT/engine" "$@" >"$out" 2>"$WORK/stderr.txt"); then
        tail -n 20 "$WORK/stderr.txt" >&2
        exit 1
    fi
}

log_lines() {
    if [[ -f $LOG ]]; then wc -l <"$LOG"; else echo 0; fi
}

query() {
    echo "SELECT * FROM $1 WHERE o_custname = s_custname AND s_custname = c_name AND c_city = '$CITY';"
}

# One execution. Prints "sessionId durationMs" from the statement summary it logged.
run_once() {
    local mode=$1 from=$2 before line
    before=$(log_lines)
    engine /dev/null -j "$mode" -c "$(query "$from")"
    line=$(tail -n +"$((before + 1))" "$LOG" | grep ',Executor,select_complete ' | tail -n 1 || true)
    if [[ -z $line ]]; then
        echo "no select_complete line in $LOG" >&2
        exit 1
    fi
    echo "$(cut -d, -f2 <<<"$line") $(sed -n 's/.*durationMs=\([0-9][0-9]*\).*/\1/p' <<<"$line")"
}

write_conditions() {
    {
        echo "date: $(date)"
        sw_vers 2>/dev/null || uname -a
        sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize 2>/dev/null || true
        java -version 2>&1
        echo "commit: $(git -C "$ROOT" rev-parse --short HEAD)"
        echo "ENGINE_JAVA_OPTS: ${ENGINE_JAVA_OPTS:-<jvm default>}"
        echo "data: $(wc -l <"$DATA/customers.csv") customers, $(wc -l <"$DATA/orders.csv") orders, $(wc -l <"$DATA/shipments.csv") shipments"
        echo "data directory: $DATA"
        echo "filter: c_city = '$CITY'"
        echo "repetitions: $REPS per configuration, after 1 discarded run; round-robin order"
        echo "each run: a fresh JVM (cold JIT), OS page cache warm"
    } >"$RESULTS/conditions.txt"
}

main() {
    [[ -f $ROOT/target/engine.jar ]] || { echo "missing target/engine.jar: run mvn package" >&2; exit 1; }
    [[ -f $DATA/orders.csv ]] || { echo "missing $DATA/orders.csv: run generate-data.py" >&2; exit 1; }

    rm -rf "$WORK"
    mkdir -p "$WORK" "$RESULTS"
    write_conditions

    # COPY paths are relative to work/. The default data directory resolves to ../data.
    local rel
    rel=$(python3 -c 'import os, sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$DATA" "$WORK")
    cat >"$WORK/load.sql" <<EOF
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_quantity LONG);
CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);
COPY customers FROM '$rel/customers.csv';
COPY orders FROM '$rel/orders.csv';
COPY shipments FROM '$rel/shipments.csv';
EOF

    echo "loading the tables" >&2
    engine /dev/null -f "$WORK/load.sql"

    local config name mode from rep result
    for config in "${CONFIGS[@]}"; do
        IFS='|' read -r name mode from <<<"$config"
        result=$(run_once "$mode" "$from")
        echo "warm-up $name: ${result#* } ms (discarded)" >&2
    done

    : >"$RESULTS/runs.csv"
    for ((rep = 1; rep <= REPS; rep++)); do
        for config in "${CONFIGS[@]}"; do
            IFS='|' read -r name mode from <<<"$config"
            result=$(run_once "$mode" "$from")
            echo "${result% *},$name,$rep" >>"$RESULTS/runs.csv"
            echo "run $rep $name: ${result#* } ms" >&2
        done
    done

    # The analysis, through the engine's own joins. Snapshot first: the engine writes to
    # engine.log while COPY reads it, and rotated files (engine-1.log, ...) are older lines.
    # analysis.sql names ../results/runs.csv, which is only right for the default RESULTS.
    cat $(ls -r "$WORK"/logs/engine-*.log 2>/dev/null) "$LOG" >"$WORK/logs/snapshot.csv"
    local runs_csv ddl
    runs_csv=$(python3 -c 'import os, sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$RESULTS/runs.csv" "$WORK")
    ddl=$(grep -v -e '^--' -e '^SELECT' "$HERE/analysis.sql" | sed "s|'../results/runs.csv'|'$runs_csv'|")
    engine /dev/null -c "$ddl"
    local select class
    while IFS= read -r select; do
        class=$(sed -n "s/.*className = '\([A-Za-z]*\)'.*/\1/p" <<<"$select")
        engine "$RESULTS/analysis-$class.csv" -c "$select"
        echo "wrote $RESULTS/analysis-$class.csv ($(wc -l <"$RESULTS/analysis-$class.csv") rows)" >&2
    done < <(grep '^SELECT' "$HERE/analysis.sql")
}

main "$@"
