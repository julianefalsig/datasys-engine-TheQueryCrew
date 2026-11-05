#!/usr/bin/env bash
# Runs the partition-size x table-size sweep from docs/experiment-design.md.
#
# Each cell is a fresh data directory: CREATE with --max-rows-per-partition, COPY the
# matching CSV, then six SELECTs. The first SELECT is the cold run and is dropped.
# The result row is the median, min, and max of the other five.
#
# A partition size that already holds the whole table is one partition. The first such
# size is measured; larger sizes for that table are skipped, because they repeat it.
#
# Usage, from anywhere:
#   experiment/run-sweep.sh                 # the full grid; rewrites experiment/results.csv
#   experiment/run-sweep.sh 5000 4096       # one cell, same summary
#
# Heap is whatever ENGINE_JAVA_OPTS already says (JVM default when unset). A failed
# 10,000,000-row COPY stops the sweep so the heap can be raised once and written down.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

PARTITIONS=(8 64 512 2048 4096 8192 16384 32768)
TABLE_ROWS=(5000 10000 50000 100000 500000 1000000 5000000 10000000)
SELECTS=6
RESULTS=experiment/results.csv
CONDITIONS=experiment/conditions.txt

run_engine() {
    local err
    err=$(mktemp)
    if ! "$ROOT/engine" "$@" >/dev/null 2>"$err"; then
        cat "$err" >&2
        rm -f "$err"
        exit 1
    fi
    rm -f "$err"
}

grab() {
    sed -n "s/.*$1=\\([0-9][0-9]*\\).*/\\1/p" <<<"$2"
}

# Prints: partitionsTotal partitionsRead partitionsPruned rowsIn rowsOut executeMs
read_select() {
    local blob summary filter complete
    blob=$(tail -n 30 logs/engine.log)
    summary=$(printf '%s\n' "$blob" | grep 'partitionsTotal=' | tail -n 1 || true)
    filter=$(printf '%s\n' "$blob" | grep 'op=filter ' | tail -n 1 || true)
    complete=$(printf '%s\n' "$blob" | grep 'select_complete ' | tail -n 1 || true)
    if [[ -z "$summary" || -z "$filter" || -z "$complete" ]]; then
        printf 'could not read the SELECT summary from logs/engine.log\n%s\n' "$blob" >&2
        exit 1
    fi
    printf '%s %s %s %s %s %s\n' \
        "$(grab partitionsTotal "$summary")" \
        "$(grab partitionsRead "$summary")" \
        "$(grab partitionsPruned "$summary")" \
        "$(grab rowsIn "$filter")" \
        "$(grab rowsOut "$filter")" \
        "$(grab executeMs "$complete")"
}

median_of() {
    printf '%s\n' "$@" | sort -n | awk '{ v[NR] = $1 } END { print v[int((NR + 1) / 2)] }'
}

lowest_of() {
    printf '%s\n' "$@" | sort -n | head -n 1
}

highest_of() {
    printf '%s\n' "$@" | sort -n | tail -n 1
}

rate() {
    awk -v rows="$1" -v ms="$2" 'BEGIN {
        if (ms + 0 == 0) print "na"
        else printf "%.2f", rows / ms
    }'
}

summarize() {
    local kind=$1
    shift
    local median min max
    median=$(median_of "$@")
    min=$(lowest_of "$@")
    max=$(highest_of "$@")
    printf '%s_%s=%s min=%s max=%s\n' "$kind" "median" "$median" "$min" "$max" >&2
    printf '%s,%s,%s' "$min" "$median" "$max"
}

run_cell() {
    local rows=$1
    local part=$2
    local csv="experiment/data/trips-${rows}.csv"
    local run stats
    local -a execute_ms=() rows_in=() rows_out=() t_in=() t_out=()
    local total read pruned

    printf 'cell rows=%s partition=%s\n' "$rows" "$part" >&2
    rm -rf data
    run_engine -c "CREATE TABLE t (city STRING, distance LONG, price DOUBLE);" \
        --max-rows-per-partition "$part"
    run_engine -c "COPY t FROM '${ROOT}/${csv}';"

    for ((run = 1; run <= SELECTS; run++)); do
        run_engine -c "SELECT * FROM t WHERE distance > 990;"
        stats=$(read_select)
        # shellcheck disable=SC2086
        set -- $stats
        if ((run == 1)); then
            printf '  cold executeMs=%s\n' "$6" >&2
            continue
        fi
        total=$1
        read=$2
        pruned=$3
        execute_ms+=("$6")
        rows_in+=("$4")
        rows_out+=("$5")
        t_in+=("$(rate "$4" "$6")")
        t_out+=("$(rate "$5" "$6")")
        printf '  warm %s executeMs=%s\n' "$((run - 1))" "$6" >&2
    done

    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$rows" "$part" "$total" "$read" "$pruned" \
        "$(median_of "${rows_in[@]}")" "$(median_of "${rows_out[@]}")" \
        "$(summarize execute_ms "${execute_ms[@]}")" \
        "$(summarize t_in "${t_in[@]}")" \
        "$(summarize t_out "${t_out[@]}")" >>"$RESULTS"
}

write_conditions() {
    {
        echo "date: $(date)"
        sw_vers || true
        sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize 2>&1 || true
        java -version 2>&1 || true
        echo "commit: $(git rev-parse --short HEAD)"
        echo "ENGINE_JAVA_OPTS: ${ENGINE_JAVA_OPTS:-<jvm default>}"
    } >"$CONDITIONS"
}

main() {
    local rows part covered csv
    [[ -x "$ROOT/engine" ]] || {
        printf 'missing %s/engine; run mvn package first\n' "$ROOT" >&2
        exit 1
    }
    for rows in "${TABLE_ROWS[@]}"; do
        csv="experiment/data/trips-${rows}.csv"
        [[ -f "$csv" ]] || {
            printf 'missing %s; run experiment/generate-data.py first\n' "$csv" >&2
            exit 1
        }
    done

    write_conditions
    printf 'table_rows,max_rows_per_partition,partitions_total,partitions_read,partitions_pruned,rows_in,rows_out,execute_ms_min,execute_ms_median,execute_ms_max,t_in_min,t_in_median,t_in_max,t_out_min,t_out_median,t_out_max\n' >"$RESULTS"

    if [[ $# -eq 2 ]]; then
        run_cell "$1" "$2"
        return
    fi
    if [[ $# -ne 0 ]]; then
        printf 'usage: experiment/run-sweep.sh [table_rows max_rows_per_partition]\n' >&2
        exit 1
    fi

    for rows in "${TABLE_ROWS[@]}"; do
        covered=0
        for part in "${PARTITIONS[@]}"; do
            if ((part >= rows)); then
                if ((covered)); then
                    printf 'skip rows=%s partition=%s (same single partition as a smaller size)\n' "$rows" "$part" >&2
                    continue
                fi
                covered=1
            fi
            run_cell "$rows" "$part"
        done
    done
}

main "$@"
