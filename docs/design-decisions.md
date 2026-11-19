# Design decisions

One entry per decision, newest last: what we decided, why, and what it cost.

---

## Splitting `storage` and `exec`

**Decision.** The Volcano operators and planner live in `dk.itu.datasys.exec`. Only
`PartitionFile.readAllColumns` is opened to them; `PartitionFile.write` stays package-private.
`Pruner` and `CatalogData` are public, and `StorageEngine` exposes `catalog` / `tableDirectory`,
so the planner can prune and build `Filter(Scan(...))` (or a bare `Scan`)
before any data file opens. `Executor` drains that plan for SQL `SELECT`.

**Why.** The packages change for different reasons — `storage` when the file format does, `exec`
when query execution does. Pruning needs catalog min/max, not column data, so it belongs in the
planner — same visibility pattern as Exercise 3's `schema`.

**Why only the read path.** Pruning trusts the catalog's min/max completely. If anything outside
`storage` could write a partition file, it could write one the catalog does not describe, and a
query would prune a partition that does contain matches: too few rows, no error. Keeping `write`
package-private means only `copyFile` creates partitions.

**What it cost.** `PartitionFile`, `Pruner`, and `CatalogData` are public. Tests outside `storage`
go through the test-only helper `TestPartitions`. `catalog` returns the live object; callers must
not mutate it.

---

## One equi-join predicate per added table

**Decision.** The planner joins in `FROM`-list order. Each new table must have exactly one
equi-join predicate that reaches a table already in the chain. Zero predicates is a cross
product: the error tells the caller to reorder the `FROM` list. Two or more are rejected as
well, rather than applied as a filter on the join output.

**Why.** `HashJoinOperator` takes one build key and one probe key. A second predicate between
the same pair of tables would need another operator, and week 11 is the point where join
order becomes a choice. Rejecting it keeps this week's plan a straight left-deep chain.

**What it cost.** `SELECT * FROM orders, customers WHERE o_custname = c_name AND o_prodname = c_city`
does not run. The same query with the tables ordered so each one joins the prefix does.
