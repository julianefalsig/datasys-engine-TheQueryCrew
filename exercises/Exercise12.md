# Exercise 12: The Join-Order Benchmark and the Project 3 Report

How to Build Data Systems – Fall 2026

Project 3 hand-in is on Dec 3. One engine artifact remains: the join-order benchmark. After that, you write the report and ship the engine as v1.0. The benchmark answers Part 3's question with your own numbers: how much does join order matter, and does your optimizer find the good one?

## 1. The benchmark

Use one three-way join, run in its best and worst orders. The forcing mechanism is week 11's flag: `-j syntax` joins in `FROM`-list order, so the two configurations are the same query with the `FROM` list permuted, identical predicates, and identical results.

**Data.** Generate the data with a script and a fixed seed, both committed to the repository. The schema needs care, because not every join-order question has an answer worth measuring. Use **two fact tables and one dimension table**, where both fact tables carry a foreign key into the same dimension:

```sql
CREATE TABLE customers (c_name STRING, c_city STRING);                         -- dimension table
CREATE TABLE orders    (o_orderkey LONG, o_custname STRING, o_quantity LONG);  -- fact table
CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);   -- fact table
```

- `customers` is the **dimension table**: 1,000 rows spread over 100 cities, so an equality filter on the city keeps 1%. Its key `c_name` is unique.
- `orders` is a **fact table**: 100,000 rows, exactly 100 per customer. `o_custname` is a foreign key into `customers`, so it repeats 100 times.
- `shipments` is the second **fact table**: 100,000 rows, exactly 100 per customer. `s_custname` is a foreign key into `customers`, so it repeats 100 times too.

The two fact tables are joined to each other on `o_custname = s_custname`, which is the join where both sides have repeating keys. That is the join that explodes.

```sql
SELECT * FROM orders, shipments, customers
WHERE o_custname = s_custname AND s_custname = c_name AND c_city = 'city042';
```

Joining the two fact tables first pairs every order of a customer with every shipment of that customer, for all 1,000 customers: 1,000 · 100 · 100 = 10,000,000 rows. Joining the filtered dimension first leaves 10 · 100 = 1,000 rows.

Two details of the generator matter. Shuffle the foreign keys instead of writing them in customer order, so that a partition's NDV stays close to the table's and the estimates remain meaningful. Assign the cities round-robin, so that the filter's selectivity is a stated number rather than a measured one.

**The two forced runs**, plus the optimizer's own choice:

- **min:** `FROM customers, shipments, orders ... AND c_city = '...'` under `-j syntax`: the filtered dimension joins first, so the first intermediate result is 1,000 rows.
- **max:** `FROM orders, shipments, customers`, with the same predicates, under `-j syntax`: the two fact tables join first, so the first intermediate result is 10,000,000 rows. This makes the planner choosing the wrong order reproducible.
- **optimized:** the same query under `-j optimized`, run with *both* `FROM` lists. Does your planner pick the min order, and does it pick it whichever way the query was written? If not, that is a finding, not a failure; Leis et al. built a paper on optimizers choosing badly, and your report should explain which estimate misled yours.

Scale the row counts up or down until the two runs differ well beyond noise. At the sizes above the difference is about 3.4x end to end, with no overlap between the spreads.

**Method,** as in week 6: at least 5 executions per configuration, with median and spread reported, and warm vs. cold stated. State the machine, JVM, heap, and commit once. Runtimes and intermediate sizes come from the engine's own log: `durationMs` from the statement summary and `estRows`/`rowsOut` from the per-join lines.

**Analysis through your own engine.** At least one analysis of the benchmark must run through the engine's own joins. One simple pattern is to keep a small `runs.csv` that maps each benchmark session to its configuration, then join it with the ingested log. The repetition number doubles as the numeric column that keeps DuckDB's header sniffing in agreement:

```sql
CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
COPY runs FROM 'runs.csv';
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator';
```

This labels each join line with the configuration that produced it. The report's numbers are then produced by the system they describe.

**Deliverables:** one chart of end-to-end runtime per configuration, with spread visible, and one table of intermediate result sizes, estimated and actual, per join and configuration.

## 2. The report (4–6 pages)

Write for a reader who knows the course material but has not seen your code.

- **Optimizer design (~1–2 pages):** the statistics you collect, where they live, and how per-partition values merge; the estimation formulas and their sources, from the lecture, a paper, or your own reasoning; the ordering heuristic and where it fits among the families surveyed in lecture 11. Then give one worked example: the benchmark query planned by hand, with estimates from your own log, showing why the planner ranks the orders as it does.
- **Benchmark (~1–2 pages):** the setup (schema, scale, filter selectivity, conditions), the runtime chart, the estimated-vs.-actual table, and a discussion: how large is the min-to-max effect and where does it come from; where were the estimates wrong and did that matter; did `-j optimized` pick the min order?
- **Reflections (~1–2 pages):** three questions, a paragraph or two each.
  - *The work:* how much work was Part 3 compared with Parts 1 and 2? Count it: `git diff --stat v0.8..v1.0` gives files and lines. Which changes were genuinely new code, and which were rework because a Part 1 or 2 decision (row layout, operator contract, catalog) did not anticipate joins?
  - *The trust:* your engine now stores data, survives concurrent writers, joins tables, and optimizes plans, and most of its lines were written by AI. How much do you trust a `SELECT` result today, and on what evidence: the test suite, the DuckDB differential checks, or the benchmark? Which single addition from lecture 8's arsenal would raise your trust the most? Compare that with the trust you had in week 1.
  - *AI usage:* did your use of AI differ from Projects 1 and 2, now that the engine was larger and the features cut across it?

**AI policy for the report: do not let AI write the report for you.** Write at least the bullet points yourself, and let AI turn them into paragraphs. The analysis and reflections should be yours, not AI's.

## 3. Ship v1.0

Cut the engine's final course release:

```bash
git checkout main && git pull
git tag -a v1.0 -m "The engine ships: storage, transactions, joins, and an optimizer"
git push origin v1.0
```

This is the tag that the teaching assistant checks out to inspect Part 3.

## 4. Demo (optional, in class)

Five minutes, three steps, live in class. Live means commands typed into a terminal, not screenshots:

1. The min and the max run, back to back, with the runtimes visible.
2. The log analysis join from Task 1, through your own engine: the engine explaining its own experiment.
3. Reflections: a point about optimizers and about trusting AI-written systems.

## Definition of done

- [ ] Benchmark run as designed: script and seed committed, min, max, and optimized configurations, at least 5 executions each, median and spread, conditions stated.
- [ ] At least one benchmark analysis executed through the engine's own joins.
- [ ] Runtime chart with labeled axes and units; estimated vs. actual intermediate sizes reported.
- [ ] Report of 4–6 pages, submitted via LearnIT.
- [ ] Tag `v1.0` pushed, green in CI.
- [ ] Demo ready (optional).
