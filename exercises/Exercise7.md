# Exercise 7: DELETE, Copy-on-Write, and a Versioned Catalog

How to Build Data Systems – Fall 2026

Exercises 7 to 9 make up Project 2: light-weight DML and transaction support. In this exercise, the engine learns `DELETE` without modifying data in place. Partitions stay immutable: a `DELETE` rewrites the affected partitions into new ones, and a versioned catalog swap makes the change visible atomically. The same machinery allows multiple `COPY` statements to insert data into the same table. This exercise builds on your Part 1 repository.

As a reminder: partitions are immutable. Once written, a partition is never modified, which is what makes rewrites idempotent and readers safe.

## 1. Design before code

Start `docs/transactions-design.md` and answer these questions before implementing. The report in week 9 distills this document.

- **Versioning:** each table gets a version number that a committed `COPY` or `DELETE` increments. Where does the counter live, and what exactly is a version in your metadata: one rewritten catalog file, one catalog file per version, or metadata held in memory only and flushed when the engine exits? The in-memory variant keeps all live versions of all tables in data structures that are safe under concurrent modification. It is the cheapest to commit and the one that loses the most if the engine crashes.
- **Commit:** a commit swaps the table's partition list and increments the version, atomically. Atomic how, in one JVM and on disk? (Writing a temporary file and renaming it over `catalog.json` is atomic on POSIX; an in-memory swap is one reference assignment.)
- **Snapshot:** a statement pins the version it reads when it starts and never re-reads metadata. What exactly does it pin: a reference to a catalog version, or its own copy of the partition list with offsets and counts? And when: at statement start, or when the plan opens?
- **Retention:** how many versions back does the engine keep? A version must not be dropped while a `SELECT` or a `DELETE` still reads it, so how does the engine know that no statement still uses it? Reference counting, a list of running statements, and a fixed number of retained versions are all defensible. Write down which one you pick, and what a statement sees when it asks for a version that is gone.
- **The rewrite set:** `DELETE` must rewrite exactly the partitions that may contain matches, which is the complement of week 4's pruning decision. Where does the shared helper live so that the planner and `DELETE` decide from the same code? And what happens to a partition that may contain a match but turns out to contain none: do you write an identical copy, or carry the original over?
- **New version:** does a `DELETE` that matches nothing still create a new version?
- **Partition placement:** do new partitions go at the end of the table's single data file, or into a file of their own? Either answer can be defended; write yours down.

## 2. Version the catalog and allow repeated COPY

A table's metadata now carries a version number. `CREATE TABLE` produces version 0, the empty table. Every committed `COPY` or `DELETE` builds a new partition list, increments the version, and commits both with one atomic swap. A `SELECT` always reads the last committed version, pinned at statement start.

Remove Part 1's one-`COPY` restriction: a second `COPY` appends its partitions to the table and commits the next version.

Log the version for every SQL statement: each summary line records the version it committed or read, for example:

```
statement=COPY table=trips rows=8 partitions=2 version=2 durationMs=12
statement=DELETE table=trips rowsDeleted=4 partitionsRewritten=1 partitionsCarried=3 version=3 durationMs=9
statement=SELECT table=trips rowsOut=4 partitionsRead=2 partitionsPruned=2 version=3 durationMs=3
```

## 3. DELETE through the front end

Grow the grammar by one statement, with the `WHERE` optional as in DuckDB:

```antlr
statement : createTable | copy | select | delete ;
delete    : DELETE FROM IDENTIFIER (WHERE predicate)? ;
DELETE    : 'DELETE' ;   // lexer: keyword rules before IDENTIFIER, as always
```

```java
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
```

Add `DeleteStatement` to the sealed `Statement`'s permits list, and the compiler writes your to-do list: every `switch` that must learn about `DELETE`, in the printer, the binder, and the executor, stops compiling until you add the `DeleteStatement` case. This is the benefit of Java's sealed interfaces.

The binder checks the same things it checks for `SELECT`: the table exists, and a `WHERE` names an existing column with a constant of the right type. A `DELETE` prints no rows; stdout stays reserved for `SELECT`. Every statement your grammar accepts must still run unchanged in DuckDB.

## 4. DELETE as copy-on-write

Executing a `DELETE`:

1. Compute the rewrite set from the catalog's min/max summaries, with the same helper the planner prunes with, in the opposite role: partitions that may contain a match are rewritten, partitions that cannot are carried over by reference.
2. For each rewritten partition, copy the rows that do not match the predicate into a new partition, and summarize it as `COPY` does.
3. Commit: the new partition list and the incremented version, in one atomic operation.

The originals are never modified. A crash after writing new partitions but before the commit leaves orphaned bytes that no catalog entry points to. They are invisible and harmless, but they waste disk space. Think about how you would find and clean up orphaned partitions.

## 5. Required tests (JUnit 6)

1. Parser: `DELETE` with and without `WHERE` parses to the expected AST; the printer round-trip covers it.
2. Binder: unknown table, unknown column, and a mistyped constant in `DELETE` each throw `IllegalArgumentException`.
3. Versions: `CREATE`, `COPY`, `COPY`, `DELETE` produce versions 0 through 3; a `SELECT` after each commit sees that commit's rows.
4. Rewrite set: sorted golden data with `maxRowsPerPartition = 2`; `DELETE` with a selective predicate rewrites exactly the partitions that can match, and the carried partitions keep their offsets in the catalog.
5. Restart: a fresh engine on the same data directory sees the post-`DELETE` version.
6. Integration: one script with `CREATE`, `COPY`, `COPY`, `DELETE`, `SELECT` runs through `./engine -f` and returns the rows the two `COPY` statements loaded minus the ones the `DELETE` removed.

## 6. Cut release v0.7

```bash
git checkout main && git pull
git tag -a v0.7 -m "Exercise 7: DELETE, copy-on-write, versioned catalog"
git push origin v0.7
```

This creates a tag that the teaching assistant will check out to validate your project state.

## Definition of done

- [ ] `docs/transactions-design.md` merged, all design questions answered.
- [ ] `DELETE` runs end to end through the front door; no partition is ever modified in place.
- [ ] Catalog versioned; commits atomic; `SELECT` reads the last committed version; `version=N` on every summary log line.
- [ ] Repeated `COPY` into one table works and increments the version.
- [ ] All required tests green in CI; PRs reviewed.
- [ ] Tag `v0.7` is pushed.

## Outlook

In week 8, two writers race: commit validation detects the conflict, the loser aborts, and you learn how to test an interleaving deterministically.
