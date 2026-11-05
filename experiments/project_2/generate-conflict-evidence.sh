#!/usr/bin/env bash
# Generates the conflict evidence for Exercise 9, Task 1.
#
# Runs the one two-writer test that still aborts after rebase (#94): two DELETEs that
# rewrite the same partition. The parked DELETE ('Copenhagen') loses to the DELETE
# ('Aarhus') that commits first. The test asserts the loser threw ConflictException.
#
# Everything the run appended to logs/engine.log is copied out, so the evidence holds
# only this run:
#   evidence/test-run.txt       Maven output, including the passing assertion
#   evidence/conflict-log.csv   every log line of the run
#   evidence/conflict-story.csv the lines that tell the story: the writes, the winning
#                               version, the conflict, and the loser's ERROR line
#   evidence/conditions.txt     date, machine, Java, and commit
#
# Usage, from anywhere:
#   experiments/project_2/generate-conflict-evidence.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
cd "$ROOT"

TEST=StorageEngineConcurrencyIT#aParkedDeleteAbortsWhenAnotherDeleteRewroteTheSamePartition
LOG=logs/engine.log
OUT=$HERE/evidence

write_conditions() {
    {
        echo "date: $(date)"
        sw_vers || true
        sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize 2>&1 || true
        java -version 2>&1 || true
        echo "commit: $(git rev-parse --short HEAD)"
        echo "test: $TEST"
    } >"$OUT/conditions.txt"
}

main() {
    local before after
    mkdir -p "$OUT"
    write_conditions

    # The log is appended to across runs; remember where this run starts.
    before=0
    [[ -f "$LOG" ]] && before=$(wc -l <"$LOG")

    # Only this IT: no unit tests, no other integration tests.
    if ! mvn verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
        -Dit.test="$TEST" 2>&1 | tee "$OUT/test-run.txt"; then
        printf 'the test failed; see %s/test-run.txt\n' "$OUT" >&2
        exit 1
    fi

    after=$(wc -l <"$LOG")
    # A rollover during the run leaves a shorter file: then the whole file is this run.
    if ((after < before)); then
        before=0
    fi
    tail -n "$((after - before))" "$LOG" >"$OUT/conflict-log.csv"

    grep -E 'op=delete table=trips rowsDeleted=|conflict table=|,ERROR,' \
        "$OUT/conflict-log.csv" >"$OUT/conflict-story.csv" || true

    printf '\nwriters (threadId -> lines):\n'
    cut -d, -f4 "$OUT/conflict-story.csv" | sort | uniq -c
    printf '\nstory:\n'
    cat "$OUT/conflict-story.csv"
    printf '\nevidence written to %s\n' "$OUT"
}

main "$@"
