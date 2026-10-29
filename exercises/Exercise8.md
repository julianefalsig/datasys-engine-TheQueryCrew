# Exercise 8: Concurrent Writers and Conflict Detection

How to Build Data Systems – Fall 2026

In this exercise, two DML statements race for the same table, and the engine detects the conflict. A writer works on the snapshot it pinned at start. At commit time, it validates that no one else committed in between. If someone did, the statement fails. That is the simplest conflict resolution: first committer wins. The lecture discusses better strategies. Optionally, you may implement a strategy that allows concurrent commits of disjoint partitions. Concurrency is tested by forcing the interleaving that matters, not by running threads with sleeps and hoping that the race occurs.

## 1. Validate at commit

A DML statement records the table version it started from, its base version. At commit, compare the base against the current committed version:

- Equal: no one committed in between. Swap, increment, done.
- Moved: someone else won. Throw a `ConflictException` (a `RuntimeException`); the statement aborts and leaves no visible change, because nothing it wrote is reachable until the swap. The partitions it already wrote become orphans, the same kind Exercise 7 discussed: think about how to clean them up.

Log the conflict with a message such as `conflict table=trips base=3 current=4`. The front door needs no change at all: a `ConflictException` is a `RuntimeException` like any other failure, so week 4's front door already prints the message to stderr and leaves the `ERROR` line that week 5's `logLevel = 'ERROR'` query picks up.

Serialize the commit itself, for example with `synchronized`: validate, swap, and save run in one critical section, one writer at a time. This exercise is about one race: two commits modifying the same metadata. There might be a second race, depending on your data storage strategy. If all partitions are written to one file, multiple threads need to synchronize these writes.

## 2. A hook for the test

The two-writer test must park the first writer between its snapshot and its commit, deterministically. Give the engine a hook, called a seam in the testing literature:

```java
/** Runs before a commit's validation. Production never touches it; a test parks a writer here. */
Runnable beforeCommit = () -> { };
```

A package-private field with a no-op default costs nothing in production and gives the test exact control over the schedule. One latch inside the hook stops a writer, and a second latch starts it again. The general point is that code which can be tested is code with hooks at the moments that matter. `Thread.sleep` in a concurrency test makes the test flaky and slow. Running the interleaved operations at an exact point in the code makes the test deterministic and fast. Every `await` still carries a timeout, so a broken engine fails the test instead of hanging CI.

The snapshot test needs no threads at all, because one statement can run inside another: inject the second statement into the hook above, or interleave through the operator API. Open a `SELECT`'s plan, run a whole `DELETE` from the same thread, then drain the scan. The scan must return the rows of its pinned version, which also settles a design point: the scan carries its snapshot's partitions, offsets and counts, not indices into whatever the catalog holds by the time it reads.

## 3. The two-writer test

The choreography, with two latches and the hook:

```java
CountDownLatch parked = new CountDownLatch(1);
CountDownLatch winnerDone = new CountDownLatch(1);
engine.beforeCommit = () -> { parked.countDown(); await(winnerDone); };

Future<?> loser = executor.submit(() -> engine.delete("trips", predicate));
assertTrue(parked.await(5, MINUTES));   // the DELETE sits right before its commit
engine.beforeCommit = () -> { };        // the winner must not park there too
engine.copyFile("trips", moreTrips);    // commits; the version moves
winnerDone.countDown();                 // release the DELETE into validation

ExecutionException failure = assertThrows(ExecutionException.class, loser::get);
assertInstanceOf(ConflictException.class, failure.getCause());
```

The parked writer holds the old hook, so resetting the field before the winner runs is safe. After the abort, assert that the table equals exactly the winner's result, and then rerun the loser's statement: on the fresh base it succeeds. A clean failure is one you can simply retry.

The log tells the same story, and week 1's `threadId` column finally earns its place: two writers, two thread ids, one `version=N` that only the winner commits, and one `ERROR` line for the loser. Ingest the log and look.

## 4. Optional extension: rebase instead of abort

`COPY`/`COPY` races, and most `DELETE`/`COPY` races, touch disjoint partitions by construction: copies only add new partitions, deletes only rewrite existing ones. The loser's work is still valid; aborting throws it away. The extension: when the base version moved, check whether the two commits' partition sets actually intersect. If they do not, rebase: recommit the loser's new partitions on top of the winner's version. Two `DELETE`s that rewrite the same original partition remain a true conflict and still abort. With this extension, required tests 2 and 3 change their assertions: the parked writer commits instead of aborting, and the table ends up with the rows of both statements. Add a test for that remaining conflict: two `DELETE`s from the same base, where the loser still aborts.

## 5. Required tests (JUnit 6)

1. Snapshot read, no threads: open a plan, run a `DELETE`, drain the scan; the rows are the pinned version's.
2. The two-writer test of Task 3: `DELETE` parked, `COPY` commits, the `DELETE` aborts with `ConflictException`.
3. The same race with two `COPY` statements; the parked one aborts.
4. After an abort: the table equals the winner's result, and rerunning the aborted statement succeeds.

## 6. Cut release v0.8

```bash
git checkout main && git pull
git tag -a v0.8 -m "Exercise 8: conflict detection, deterministic concurrency tests"
git push origin v0.8
```

This creates a tag that the teaching assistant will check out to validate your project state.

## Definition of done

- [ ] A conflicting commit aborts with `ConflictException`, leaves no visible change, and leaves an `ERROR` line in the log.
- [ ] The commit section is serialized; snapshot reads under a concurrent commit return the snapshot's rows.
- [ ] The two-writer test runs deterministically on latches and a hook, no sleeps, a timeout on every await, green in CI.
- [ ] All required tests green in CI; PRs reviewed.
- [ ] Tag `v0.8` is pushed.

## Outlook

Week 9 ships Part 2: the report on versioning and conflict detection, with the evidence coming from your own engine's log.