# The join-order benchmark (Exercise 12, task 1)

One three-way join over two fact tables and one dimension, run in its best and worst orders. The
orders are forced with `-j syntax`, which joins in `FROM`-list order, so the configurations differ
only in the `FROM` list:

```sql
SELECT * FROM <FROM list>
WHERE o_custname = s_custname AND s_custname = c_name AND c_city = 'city042';
```

| Configuration | Flag | `FROM` list | First intermediate result |
|---|---|---|---|
| `min` | `-j syntax` | `customers, shipments, orders` | 10 customers · 100 shipments = 1,000 rows |
| `max` | `-j syntax` | `orders, shipments, customers` | 1,000 customers · 100 · 100 = 10,000,000 rows |
| `optimized_min_from` | `-j optimized` | `customers, shipments, orders` | the optimizer's choice |
| `optimized_max_from` | `-j optimized` | `orders, shipments, customers` | the optimizer's choice |

| File | Role |
|---|---|
| `generate-data.py` | Writes `data/customers.csv`, `orders.csv`, `shipments.csv` from a fixed seed. `data/` is gitignored; the script reproduces it. |
| `run-benchmark.sh` | Writes the `CREATE TABLE` and `COPY` for whichever data directory is in use, runs every configuration, and writes `results/runs.csv` (session → configuration) and `results/conditions.txt`. Then runs `analysis.sql` through the engine. |
| `analysis.sql` | Ingests the log and joins it with `runs.csv` in the engine: join lines, statement summaries, chosen join orders. |
| `plot-results.py` | Reads the engine's analysis output and writes `results/runtime.{png,pdf}`, `results/runtime.csv` and `results/joins.md`. |

## Running it

From the repository root:

```bash
mvn package                                        # ./engine runs target/engine.jar
python3 experiments/project_3/generate-data.py     # 1,000 customers, 100,000 orders, 100,000 shipments
export ENGINE_JAVA_OPTS=-Xmx4g                     # the max order holds 10,000,000 joined rows; pin the heap
experiments/project_3/run-benchmark.sh             # 4 warm-up runs, then 5 × 4 measured runs
python3 experiments/project_3/plot-results.py
```

`REPS` and `CITY` override the defaults: `REPS=7 CITY=city007 experiments/project_3/run-benchmark.sh`.
To scale the data, pass `--customers` (a multiple of 100) and `--per-customer` to `generate-data.py`.

## An alternate run, where the estimates are wrong

`generate-data.py` makes `estRows` equal `rowsOut`: every customer has the same number of fact
rows, and every city has the same number of customers, so `|R| · |S| / ndv` is the real
intermediate. `generate-skewed-data.py` keeps the schema and the foreign keys, and drops that
uniformity. A few customers own most of the orders and the shipments, and those customers sit in
`city000`. The formulas still divide by the number of distinct names and the number of cities.

Each table fits in one partition, and the keys are still shuffled, so the catalog NDV is the real
NDV. The gap between `estRows` and `rowsOut` is the skew, not the partition-merge rule. Both
estimates come out low. The filtered dimension is still the smaller intermediate, so `-j optimized`
should keep choosing it; the finding is the size of the error, not a wrong plan.

```bash
python3 experiments/project_3/generate-skewed-data.py   # 200 customers, 8,000 orders, 8,000 shipments
DATA=experiments/project_3/data-skew \
    RESULTS=experiments/project_3/skewed_results \
    CITY=city000 \
    experiments/project_3/run-benchmark.sh
python3 experiments/project_3/plot-results.py experiments/project_3/skewed_results
```

`CITY` has to be `city000`. Any other city is one of the small ones, and the equality estimate,
which is the average city, is then too high instead of too low.

## Method

- **Data.** `c_name` is unique, and every customer has exactly 100 orders and 100 shipments. The
  foreign keys are shuffled, so each partition's NDV is close to the table's. Cities go round-robin
  over 100 cities, so `c_city = 'city042'` keeps exactly 1% of the customers.
- **Runs.** Each execution is its own JVM, so the JIT is always cold, while the OS page cache is warm
  from the load and the earlier runs. One run per configuration comes first and is discarded. The
  measured runs go round-robin over the configurations, so a drift on the machine affects all of
  them alike.
- **Numbers.** Everything comes from the engine's own log: `durationMs` from `select_complete`,
  and `estRows`/`rowsOut` from each `op=hashJoin` line. `results/runs.csv` maps each session to
  its configuration, and `analysis.sql` joins that with the ingested log through the engine.
- **Conditions.** `results/conditions.txt` records the machine, JVM, heap, commit and scale.

The engine runs in `work/`, so the benchmark's `data/` and `logs/` stay out of the repository's
own. `work/` is recreated on every run.
