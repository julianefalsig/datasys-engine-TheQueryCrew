# Design decisions

One entry per decision, newest last: what we decided, why, and what it cost.

---

## Splitting `storage` and `exec`

**Decision.** The Volcano operators live in `dk.itu.datasys.exec`. Only
`PartitionFile.readAllColumns` is opened to them; `PartitionFile.write` stays package-private.

**Why.** The packages change for different reasons — `storage` when the file format does, `exec`
when query execution does — and the dependency stays one-directional.

**Why only the read path.** Pruning trusts the catalog's min/max completely. If anything outside
`storage` could write a partition file, it could write one the catalog does not describe, and a
query would prune a partition that does contain matches: too few rows, no error. Keeping `write`
package-private means only `copyFile` creates partitions.

**What it cost.** `PartitionFile` is now public. Tests outside `storage` go through the test-only
helper `TestPartitions`.

**Still open.** `Pruner` and `CatalogData` are still package-private; the planner will need both.
