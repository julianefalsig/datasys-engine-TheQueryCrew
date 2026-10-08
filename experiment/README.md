# Running the experiment

The design is in [../docs/experiment-design.md](../docs/experiment-design.md); this file is how to
carry it out. The sweep is `maxRowsPerPartition` × table size, 8 × 8, with one fixed predicate, and
every number comes from the engine's own log.

| File | Role |
|---|---|
| `generate-data.py` | Writes one CSV per table size from a fixed seed. |
| `checksums.txt` | SHA-256 per generated file, so a teammate can prove they got identical data. |
| `data/` | The generated CSVs. Gitignored — 31 MB, and `generate-data.py` reproduces them exactly. |

## 1. Generate the data

```bash
python3 experiment/generate-data.py          # writes the tables, then verifies checksums
python3 experiment/generate-data.py --check  # verifies what is on disk, writes nothing
```

Eight files, about 3.5 seconds, 31 MB. Run `--check` before you measure: it fails with exit 1 if a
single byte differs from `checksums.txt`, which is what keeps two teammates' runs comparable.

Randomness is splitmix64, written out in the script rather than taken from Python's `random`, so the
output does not depend on the Python build. In every file exactly 1% of rows satisfy
`distance > 990` — 5 rows at 500, 10,000 rows at 1,000,000 — so selectivity is held fixed by
construction and not left to sampling.

## 2. Build the engine

```bash
mvn package
```

`./engine` runs `target/engine.jar`, not your working tree. Any change to the Java has to be
packaged again before it shows up in a measurement — the timing line below was missing from a run
for exactly this reason.

## 3. Run one cell

`maxRowsPerPartition` has no command-line flag; `StorageEngine` defaults it to 10,000. But
`CREATE TABLE` writes it into the table's catalog, and `COPY` reads it from there, so a cell is set
up by editing the catalog between the two:

```bash
rm -rf data logs/engine.log
./engine -c "CREATE TABLE t (city STRING, distance LONG, price DOUBLE);"

python3 -c "
import json, pathlib
p = pathlib.Path('data/t/catalog.json'); c = json.loads(p.read_text())
c['maxRowsPerPartition'] = 8
p.write_text(json.dumps(c))"

./engine -c "COPY t FROM 'experiment/data/trips-500.csv';"
./engine -c "SELECT * FROM t WHERE distance > 990;" > /dev/null
```

The `SELECT` output goes to `/dev/null` because the measurement is in the log, and 10,000 rows of
CSV on the terminal is just noise.

Skip the cells where `maxRowsPerPartition` is larger than the table size: the table is one partition
either way, so the cell repeats a measurement you already have.

## 4. Read the numbers out of the log

Two lines per cell carry everything:

```
Planner,op=select table=t partitionsTotal=63 partitionsRead=5 partitionsPruned=58
Executor,select_complete rowsOut=5 parseMs=9 bindMs=1 planMs=5 executeMs=0 durationMs=7
```

| Number | Line | Used for |
|---|---|---|
| `rowsIn` | `FilterOperator,op=filter rowsIn=… rowsOut=…` | `T_in = rowsIn / executeMs` |
| `rowsOut` | same line, or `select_complete` | `T_out = rowsOut / executeMs` |
| `executeMs` | `select_complete` | the denominator — drain only, excluding parse, bind and plan |
| `partitionsRead`, `partitionsPruned` | `Planner,op=select … partitionsTotal=…` | the pruning analysis |

`durationMs` on the same line is the whole statement including parse and bind, so it is *not* the
denominator the design asks for. `parseMs` is roughly 9 ms on every run — JVM and ANTLR startup,
which is why it stays out.

The log is CSV, so the engine can read it itself; see
[../docs/system-analysis.md](../docs/system-analysis.md) for the `logs` table, and `../examples/`
for the scripts.

## 5. Conditions to record once

Needed in the report, and measured from one machine only:

```bash
sw_vers                      # macOS version
sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize
java -version
git rev-parse --short HEAD   # the commit you measured at
echo $ENGINE_JAVA_OPTS       # the heap, if you pinned it
```

Pin the heap rather than taking the default, which is a quarter of physical RAM and therefore
differs per machine:

```bash
export ENGINE_JAVA_OPTS=-Xmx2g
```

Plug the laptop in. The full grid is hundreds of runs, and a MacBook that throttles partway through
produces a downward drift that looks like a result.

## Two things that will bite

**`executeMs` has millisecond resolution, and small cells finish inside one millisecond.** The
500-row cell above reported `executeMs=0`, so `T_in` and `T_out` divide by zero. Either report
microseconds from the log, or treat the smallest table sizes as unmeasurable and say so. This is the
one open issue that affects whether the smallest cells can be reported at all.

**Pruning does work at small partition sizes, so the curve has a real gradient.** At 500 rows with
`maxRowsPerPartition = 8`, 58 of 63 partitions were pruned; at 1,000,000 rows with the default
10,000, none of the 100 were. Shuffled input does not flatten the effect the way we first assumed —
with 1% selectivity each matching row tends to sit in its own small partition.

## Not automated yet

There is no sweep runner. Steps 3 and 4 are per cell, and the grid is 64 cells before repetitions,
so a script that loops the grid, repeats each cell, and writes one row of results per cell is the
obvious next piece.
