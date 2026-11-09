#!/usr/bin/env bash
# Generates the worked example for the Versioning section of the Exercise 9 report.
#
# One writer, no concurrency: four rows are COPYed into a single partition (version 1),
# and one row is then DELETEd (version 2). The point of the example is what the catalog
# looks like on either side of that commit, so the two catalogs are copied out:
#   evidence/catalog-before-delete.json   version 1
#   evidence/catalog-after-delete.json    version 2
#   evidence/catalog-conditions.txt       date, machine, Java, and commit
#
# The table is called trips_demo, not trips, so the report cannot confuse this run with
# the 8-row trips table of the two-writer conflict run.
#
# Everything happens in a temporary directory, so the repo's own data/ is untouched.
#
# Usage, from anywhere:
#   experiments/project_2/generate-catalog-evidence.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
cd "$ROOT"

JAR=$ROOT/target/engine.jar
OUT=$HERE/evidence
TABLE=trips_demo
WORK=
ENGINE_JAVA_OPTS=${ENGINE_JAVA_OPTS:-}

cleanup() {
    [[ -n "$WORK" && -d "$WORK" ]] && rm -rf "$WORK"
}
trap cleanup EXIT

write_conditions() {
    {
        echo "date: $(date)"
        sw_vers || true
        java -version 2>&1 || true
        echo "commit: $(git rev-parse --short HEAD)"
        echo "table: $TABLE"
        echo "rows: 4 in 1 partition, one writer"
        echo "statements: CREATE TABLE; COPY (version 1); DELETE city = 'Aarhus' (version 2)"
    } >"$OUT/catalog-conditions.txt"
}

engine() {
    # The engine resolves data/ against the working directory, so run it from $WORK.
    (cd "$WORK" && java $ENGINE_JAVA_OPTS -jar "$JAR" -c "$1")
}

main() {
    mkdir -p "$OUT"

    if [[ ! -f "$JAR" ]]; then
        printf 'building %s\n' "$JAR"
        mvn -q package -DskipTests
    fi

    WORK=$(mktemp -d)
    # Aarhus is deliberately neither the min nor the max of city, but holds the max of
    # both distance and price: the delete narrows those two ranges and leaves city alone.
    cat >"$WORK/trips.csv" <<'CSV'
Copenhagen,12,23.5
Aarhus,187,301.0
Odense,95,120.75
Aalborg,31,45.0
CSV

    write_conditions

    engine "CREATE TABLE $TABLE (city STRING, distance LONG, price DOUBLE); COPY $TABLE FROM 'trips.csv';"
    cp "$WORK/data/$TABLE/catalog.json" "$OUT/catalog-before-delete.json"

    engine "DELETE FROM $TABLE WHERE city = 'Aarhus';"
    cp "$WORK/data/$TABLE/catalog.json" "$OUT/catalog-after-delete.json"

    printf '\nversion 1 -> version 2:\n'
    diff -u "$OUT/catalog-before-delete.json" "$OUT/catalog-after-delete.json" || true
    printf '\nevidence written to %s\n' "$OUT"
}

main "$@"
