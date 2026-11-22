%md

## Statistics. what does the planner know per table and per column? Row counts per partition are the minimum, and your catalog already has them. The recommended next step is NDV, the number of distinct values per column, collected during `COPY` like min/max. Without NDV, every equi-join looks alike; with it, the formula below can rank them.

Per partition, the catalog already stores the row count and min/max per column. We add NDV (number of distinct values) per column, counted exactly with a HashSet while COPY (or a DELETE rewrite) writes the partition, for all column types, including STRING, since join keys are often strings. Only per-partition numbers are stored; table-level row counts and NDVs are derived when the planner reads the catalog, so they can never go out of sync with the partition list. We keep no histograms and no cross-column correlations, so the estimator has to assume uniformity and independence. At a billion rows, an exact set per column would be too large, and we would switch to HyperLogLog sketches, which are small and merge exactly.

## Where they live and how they merge: NDV is collected per partition, but the estimate needs one number per table. Unlike min/max, NDV does not merge exactly: the same value may appear in many partitions, so the maximum of the per-partition NDVs is a lower bound and their sum is an upper bound. Pick a rule and defend it. Sketches such as HyperLogLog merge exactly, which is why industry uses them; that is an optional aside from lecture 10.

The maximum is a lower bound on the table's NDV, so the join estimate |R|·|S| / max(ndv) is pessimistic: it may be too large, but never too small. We prefer that, because underestimated join sizes lead to bad join choices early in the plan.

## Roll forward: tables copied before this release carry no NDV. Lecture 6's question returns: what does a missing statistic mean, and how can you roll forward and roll back safely?

First, we release an intermediate version that ignores catalog keys it doesn't know, so older engines can read catalogs with NDV. Then we add NDV. partitions copied before that have no NDV, so we estimate it as the number of rows. The table NDV is recomputed from the partitions on every read.

## The estimator: which formulas do you use, and where do they come from? State them in the document with their source, whether lecture 10, a paper, or your own reasoning.

Equality filter col = x :
|R| / ndv(col) 

Range filter col\<x, col>c :
|R| times the fraction of [min, max] on the matching side. A string has no distance to interpolate, so a range that does not already cover every value keeps Selinger's 1/3.

Equivalence join:
|R| · |S| / max(ndv(R.a), ndv(S.b))

We chose these because they are simple estimates that assume uniformity. They also assume that every key of the smaller key domain appears in the larger one, so all keys on the smaller side find a partner. This matches the dimensional models that are common in analytics.

## The heuristic: greedy smallest-intermediate-first is the course baseline. With at most four tables, exhaustive enumeration is also cheap.

We chose greedy and leave other search algorithms for future work. Greedy does not guarantee the optimal choice, but it makes a good choice without needing as many comparisons.