# Exercise 4: A Volcano Pipeline and the SQL Front Door

How to Build Data Systems – Fall 2026

In this exercise, the bound ASTs from Exercise 3 execute. You refactor `select` into a Volcano-style operator pipeline (`Scan` → `Filter`), add a planner that prunes partitions and maps statements to plans, and turn `Engine.main` into a SQL front door: it runs SQL statements and scripts and prints rows as CSV. This exercise builds on your Exercise 3 repository.

## 1. The operator model

```java
public interface Operator {
    void open();
    Object[] next();   // one row in schema column order, or null when exhausted
    void close();
}
```

Implement two operators, both deliberately simple:

- **`ScanOperator(table, partitions)`** is handed the list of partitions it must read and returns every row in them, in order. It never sees the predicate and makes no decisions.
- **`FilterOperator(child, predicate)`** pulls rows from its child and emits those that pass. Comparison semantics identical to week 2. Logs `rowsIn`/`rowsOut` in `close()`.

Note what is missing from the scan. In week 2, `select` decided partition by partition whether to read. That decision needs only the min/max summaries, never the column data, so it does not belong at runtime at all: it moves to the planner. The scan becomes a plain reader, the filter a plain row test, and the interesting work happens before either one runs.

## 2. The planner and executor

The planner turns a bound statement into a plan, and this is where pruning now happens. For a `SELECT` with a `WHERE`:

1. Look up the table's partitions in the catalog.
2. For each partition, compare the predicate against its min/max summaries, using the same helper you unit-tested in Exercise 2. If your planner lives outside the storage package, widen the helper's visibility the way Exercise 3 exposed `schema`.
3. Keep the partitions that may contain a match and drop the rest.
4. Build `Filter(Scan(table, survivingPartitions), predicate)`.

Without a `WHERE`, all partitions survive and the plan is a bare `Scan(table, allPartitions)`. For `CREATE TABLE` and `COPY` there is no operator tree in Part 1; the executor calls `createTable`/`copyFile` directly.

`select(...)` keeps its exact week 2 signature but now plans and drains a pipeline internally. The planner fills in `ScanStats` (`partitionsTotal` from the catalog, `partitionsRead` from the surviving list, `partitionsPruned` the difference) and emits the `decision=READ|PRUNED` log lines, now before any data file is opened. The refactor is visible in the log's `className` column: the decision lines move from the storage engine to the planner. When the old integration tests pass, the refactor is done.

The executor runs `parse → bind → plan → execute`, statement by statement, stopping at the first error.

## 3. The SQL front door

Extend `Engine.main`:

- No arguments: print the team name and usage information.
- One argument: a single SQL statement to execute. Maven splits `-Dexec.args` on spaces, so the statement needs a second layer of quotes to arrive as one argument: `-Dexec.args="'SELECT * FROM trips'"`, and `-Dexec.args="\"SELECT * FROM trips WHERE city = 'Odense'\""` when the statement carries a string literal of its own.
- Two arguments: `-f` followed by the path to a `.sql` file; the engine executes the whole script.
- Data directory: default to `data/` under the working directory. Add `data/` to `.gitignore`.

In both execution modes, each `SELECT`'s rows go to stdout as headerless CSV. Nothing else may appear on stdout; the console log and errors go to stderr.

The one-argument form is for trying statements by hand; redirect the console log with `2> /dev/null` when it is in the way. The two-argument form makes DuckDB a one-pipe differential oracle:

```bash
mvn -q compile exec:java -Dexec.args="-f q.sql" > ours.csv
duckdb -csv -noheader < q.sql > theirs.csv
```

## 4. statementNumber and the MDC

The session is now a concrete thing: one run of the engine over one SQL script, identified by the random `sessionId` from Exercise 1. Within it, `statementNumber` finally counts: the executor keeps a counter, increments it before each statement, and puts it in the MDC (`MDC.put("statementNumber", String.valueOf(n))`). The first statement of the script is 1. Lines written outside any statement (engine start and stop, script-level parsing) keep the 0 you set at startup; put the 0 back when the script is done, so the engine's stop line is outside the count again.

Every log line of one statement then shares its number, so `WHERE statementNumber = 7` reconstructs that statement's whole story, and `WHERE statementNumber > 0` picks out everything statement-related. You may want to log a summary line per statement:

```
statement=SELECT table=trips rowsOut=4 durationMs=3
```

## 5. Optional extension: column lists

Column lists, `SELECT city, price FROM ...`, via a grammar change plus a `ProjectOperator(child, columns)`.

## 6. Required tests (JUnit 6)

Unit:

1. `FilterOperator` over a stub child (write a small `TestListOperator` test helper that serves rows from a list).
2. `ScanOperator` returns exactly the rows of the partitions it is handed, including the case of an empty partition list: a fully pruned query reads nothing and returns nothing.
3. Planner pruning: sorted golden data with `maxRowsPerPartition = 2`; the planner keeps exactly the partitions that can match, asserted via `ScanStats`.
4. Planner shapes: `WHERE` → `Filter` over `Scan`; no `WHERE` → bare `Scan` over all partitions.

Integration:

5. The Exercise 2 integration tests, unchanged, green.
6. Front door end-to-end: run a `.sql` script, capture stdout, compare CSV byte for byte; also one failing script whose message lands on stderr and leaves stdout clean.

## 7. Cut release v0.4

```bash
git checkout main && git pull
git tag -a v0.4 -m "Exercise 4: Volcano pipeline and SQL front door"
git push origin v0.4
```

This creates a tag that the teaching assistant will check out to validate the state of your project.

## Definition of done

- [ ] `select` runs on `Scan` → `Filter`; the Exercise 2 integration tests untouched and green.
- [ ] Pruning done by the planner, before any data file is read; `Scan` takes a partition list and no predicate; `ScanStats` and the `decision=` log lines intact; `rowsIn`/`rowsOut` from `Filter`.
- [ ] `parse → bind → plan → execute` wired; front door prints CSV on stdout, errors on stderr; `data/` in `.gitignore`.
- [ ] `statementNumber` counted per statement from 1, and 0 outside statements.
- [ ] All required tests green in CI.
- [ ] PRs reviewed.
- [ ] Tag `v0.4` is pushed.

## Outlook

In week 5, the engine ingests and analyzes its own log. The front door you built this week is how those analysis scripts will run.