# Design decisions

One entry per decision, newest last: what we decided, why, and what it cost.

---

## Splitting `storage` and `exec`

**Decision.** The Volcano operators and planner live in `dk.itu.datasys.exec`. Only
`PartitionFile.readAllColumns` is opened to them; `PartitionFile.write` stays package-private.
`Pruner` and `CatalogData` are public, and `StorageEngine` exposes `catalog` / `tableDirectory` /
`recordScanStats`, so the planner can prune and build `Filter(Scan(...))` (or a bare `Scan`)
before any data file opens. Week-2 `select` keeps its signature and drains that plan.

**Why.** The packages change for different reasons — `storage` when the file format does, `exec`
when query execution does. Pruning needs catalog min/max, not column data, so it belongs in the
planner — same visibility pattern as Exercise 3's `schema`.

**Why only the read path.** Pruning trusts the catalog's min/max completely. If anything outside
`storage` could write a partition file, it could write one the catalog does not describe, and a
query would prune a partition that does contain matches: too few rows, no error. Keeping `write`
package-private means only `copyFile` creates partitions.

**What it cost.** `PartitionFile`, `Pruner`, and `CatalogData` are public. Tests outside `storage`
go through the test-only helper `TestPartitions`. `select` introduces a small cycle
(`StorageEngine` → `Planner` → `StorageEngine`). `catalog` returns the live object; callers must
not mutate it.
