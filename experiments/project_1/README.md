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

Eight files, about a minute, 305 MB. Run `--check` before you measure: it fails with exit 1 if a
single byte differs from `checksums.txt`, which is what keeps two teammates' runs comparable.

Randomness is splitmix64, written out in the script rather than taken from Python's `random`, so the
output does not depend on the Python build. In every file exactly 1% of rows satisfy
`distance > 990` — 50 rows at 5,000, 100,000 rows at 10,000,000 — so selectivity is held fixed by
construction and not left to sampling.

## 2. Build the engine

```bash
mvn package
```

`./engine` runs `target/engine.jar`, not your working tree. Any change to the Java has to be
packaged again before it shows up in a measurement — the timing line below was missing from a run
for exactly this reason.

## 3. Run one cell

`CREATE TABLE` stores `maxRowsPerPartition` in the catalog, and `COPY` reads it from there. Pass
the size on the process that creates the table; later `COPY` and `SELECT` processes pick it up
from the catalog and do not need the flag again.

```bash
rm -rf data logs/engine.log
./engine -c "CREATE TABLE t (city STRING, distance LONG, price DOUBLE);" --max-rows-per-partition 8
./engine -c "COPY t FROM 'experiment/data/trips-5000.csv';"
./engine -c "SELECT * FROM t WHERE distance > 990;" > /dev/null
```

The `SELECT` output goes to `/dev/null` because the measurement is in the log, and 10,000 rows of
CSV on the terminal is just noise.

When `maxRowsPerPartition` is at least the table size the table is one partition. The sweep keeps
the first such size and skips the larger ones, which would repeat that same cell.

## 4. Read the numbers out of the log

Two lines per cell carry everything:

```
Planner,op=select table=t partitionsTotal=625 partitionsRead=49 partitionsPruned=576
Executor,select_complete rowsOut=50 parseMs=18 bindMs=2 planMs=36 executeMs=4 durationMs=44
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

`COPY` holds the whole file in memory, so the 10,000,000-row file may not fit in 2g. If that `COPY` fails, bump the heap once and write down the value you actually used.

Plug the laptop in. The full grid is hundreds of runs, and a MacBook that throttles partway through
produces a downward drift that looks like a result.

## Two things that will bite

**The grid starts at 5,000 rows so `executeMs` is not a zero.** A 500-row cell measured `executeMs=0`,
and 1,000 rows measured `executeMs=1`. The 5,000-row cell above measured `executeMs=4` on the same
predicate with `maxRowsPerPartition = 8`, and the larger tables measured higher, so none of the
eight sizes divide the throughput by zero.

**Pruning does work at small partition sizes, so the curve has a real gradient.** At 5,000 rows with
`maxRowsPerPartition = 8`, 576 of 625 partitions were pruned; at 1,000,000 rows with the default
10,000, none of the 100 were. Shuffled input does not flatten the effect the way we first assumed —
with 1% selectivity each matching row tends to sit in its own small partition.

## 6. Run the sweep

`experiment/run-sweep.sh` loops the grid. Each cell deletes `data/`, creates the table at that
partition size, copies the matching CSV, then runs the `SELECT` six times. The first run is the
cold start and is dropped. The row written for the cell is the median, min, and max of the other
five: `executeMs`, `T_in = rowsIn / executeMs`, and `T_out = rowsOut / executeMs`.

```bash
experiment/run-sweep.sh                  # full grid; rewrites experiment/results.csv
experiment/run-sweep.sh 5000 4096        # one cell, same six-run summary
```

The script also writes `experiment/conditions.txt` once: OS, CPU, memory, `java -version`, the
commit, and `ENGINE_JAVA_OPTS` (or `<jvm default>` when that is unset). It does not raise the heap
itself. If the 10,000,000-row `COPY` fails, set `ENGINE_JAVA_OPTS` and start the sweep again.
