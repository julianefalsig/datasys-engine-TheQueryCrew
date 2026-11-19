# Exercise 11: The Join Optimizer

How to Build Data Systems – Fall 2026

In this exercise, the `FROM`-list order stops being the plan. The planner estimates each join output from catalog statistics and orders joins so that expected intermediate results stay small. Both the estimates and the actual sizes go to the log, because week 12's benchmark and report depend on that comparison. A flag preserves last week's naive order; week 12 uses it to force a plan. This exercise builds on your Exercise 10 repository.

The heuristic is fixed, but the estimator is your design decision and should be informed by some research. Lecture 10 gave the landscape: greedy ordering by smallest intermediate result, the course baseline and Fegaras' Greedy Operator Ordering; Selinger's dynamic programming and its magic constants; and the warning from Leis et al. that optimizer failures usually come from estimates rather than search. Your design document should state what you adopted and why.

## 1. Design before code

Start `docs/optimizer-design.md` and answer these questions before you implement. The report in week 12 distills this document.

- **Statistics:** what does the planner know per table and per column? Row counts per partition are the minimum, and your catalog already has them. The recommended next step is NDV, the number of distinct values per column, collected during `COPY` like min/max. Without NDV, every equi-join looks alike; with it, the formula below can rank them.
- **Where they live and how they merge:** NDV is collected per partition, but the estimate needs one number per table. Unlike min/max, NDV does not merge exactly: the same value may appear in many partitions, so the maximum of the per-partition NDVs is a lower bound and their sum is an upper bound. Pick a rule and defend it. Sketches such as HyperLogLog merge exactly, which is why industry uses them; that is an optional aside from lecture 10.
- **Roll forward:** tables copied before this release carry no NDV. Lecture 6's question returns: what does a missing statistic mean, and how can you roll forward and roll back safely?
- **The estimator:** which formulas do you use, and where do they come from? State them in the document with their source, whether lecture 10, a paper, or your own reasoning.
- **The heuristic:** greedy smallest-intermediate-first is the course baseline. With at most four tables, exhaustive enumeration is also cheap.

## 2. NDV during COPY

Collect the number of distinct values per column while writing each partition, next to min/max. At course scale, an exact count with a `HashSet` per column is sufficient; note in the design document what you would switch to at a billion rows. `STRING` columns count too, because join keys are often strings. Statistics should remain observable: each NDV gets its own log line when it is created, like each min/max since week 2, for example `table=orders partition=3 column=o_custname rows=1000 ndv=997`.

The merge rule from your design document becomes a small helper, `long tableNdv(List<Long> partitionNdvs)` or similar, unit-tested like every shared helper.

## 3. The estimator

Define three estimates, each as a small pure function over catalog numbers, unit-testable without data files:

- **Base table:** the row counts of the partitions that survive pruning.
- **Filter:** an equality filter keeps about |R| / ndv(column) rows. For `<` and `>`, either interpolate from min/max or use a fixed fraction. Selinger's 1979 paper used magic constants such as 1/3, and naming that origin in your design document is exactly the kind of research this week asks for.
- **Equi-join:** |R ⋈ S| ≈ |R| · |S| / max(ndv(R.a), ndv(S.b)), the formula from lecture 10. For later joins in a chain, approximate the intermediate result's NDV for a column by the NDV of the base table that contributed that column; write that assumption down, because the report may revisit it where the estimates go wrong.

## 4. The greedy planner

Replace week 10's join order, which was defined by the order of tables in the `FROM` list:

1. Estimate every table's size after pruning and filters.
2. Among all join predicates, start with the one whose estimated output is smallest.
3. Repeat: among the tables connected by a predicate to the tables joined so far, add the one whose estimated join output is smallest. Never pick a cross product while a connected choice exists. Write a log line for every combination checked. This will help you understand the optimizer's choice of join order.
4. Build side: the input with the smaller estimated size builds the hash table, the larger probes.

Two details matter. First, a reordered plan must still output columns in `FROM`-list order. `SELECT *` must not depend on the reordered plan. Second, the engine may get a new flag, `-j optimized` (the default) and `-j syntax`, where `-j syntax` reproduces week 10's plan exactly. Forcing a specific join order is then trivial: run `-j syntax` and write the `FROM` list in the order you want. That is the mechanism week 12's benchmark uses.

## 5. Estimates and actuals, side by side in the log

Pass each `HashJoinOperator` its estimate at plan time, so `close()` writes one line carrying both numbers, and log the chosen order per statement:

```
statement=SELECT joinOrder=products,orders,customers mode=optimized
join=1 tables=products,orders estRows=4 rowsBuild=2 rowsProbe=4 rowsOut=4
join=2 tables=products,orders,customers estRows=4 rowsBuild=3 rowsProbe=4 rowsOut=4
```

The exact fields are your choice. The requirement is that, for every executed join, both the estimated and actual output sizes are recoverable from the log and attributable to the statement through `statementNumber`. One line per join with both numbers is the simplest design that satisfies this requirement. This is Part 3's core metric: `estRows` against `rowsOut` is the estimate error, and week 12 will ask your engine to find its own worst estimate.

## 6. The metamorphic test

The optimizer rearranges the plan; the result must not change. Run the week 10 testbed queries, with three and four tables, under `-j syntax` and `-j optimized` and assert that the result rows are identical, while the plan, read from the log, is different.

## 7. Optional extension: GROUP BY

`GROUP BY` with `min()`, `max()`, `count()`, and `sum()`, using DuckDB syntax, for example `SELECT city, count(*) FROM trips GROUP BY city` (this extension includes the column-list grammar for the output). Use hash aggregation as in lecture 11: one accumulator per group, with init, update, and finish. DuckDB remains the oracle.

## 8. Required tests (JUnit 6)

1. NDV: a partition with known duplicates yields the exact per-partition count; the merge helper implements your documented rule.
2. Estimator: hand-made statistics produce the expected numbers for a base table, a filtered table, and a join.
3. Ordering: hand-made statistics under which the `FROM`-list order is the worst; the planner picks the expected order, and never a cross product while a connected join exists.
4. Build side: the smaller estimated input builds.
5. Column order: under a rearranged plan, `SELECT *` still emits columns in `FROM`-list order.
6. Metamorphic integration: testbed queries under both flags return identical sorted rows, and every executed join leaves a log line with `estRows` and `rowsOut`.

## 9. Cut release v0.11

```bash
git checkout main && git pull
git tag -a v0.11 -m "Exercise 11: statistics and the join optimizer"
git push origin v0.11
```

This creates the tag that the teaching assistant will check out to validate your project state.

## Definition of done

- [ ] `docs/optimizer-design.md` merged: statistics, merge rule, estimator with named sources, heuristic, cross-product guardrail.
- [ ] NDV collected during `COPY`, each value logged as created, merged by a unit-tested rule.
- [ ] Planner orders joins by smallest estimated intermediate result; the smaller estimated input builds.
- [ ] `-j syntax` reproduces week 10's plan; `SELECT *` output identical under both flags.
- [ ] One log line per executed join carries `estRows` and `rowsOut`.
- [ ] All required tests green in CI; PRs reviewed.
- [ ] Tag `v0.11` is pushed.

## Outlook

Week 12 completes Part 3: the benchmark that forces the same join into its best and worst orders, the measured difference, and the report.
