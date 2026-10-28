# Transactions design

Exercise 7: `DELETE`, copy-on-write, and a versioned catalog.
One answer per question, written before the code. The week 9 report distils this document.

Format follows `storage-design.md`: the question in bold, our answer indented below it.

---

1. **Versioning:** 
    One rewritten `catalog.json` per table, with a new `version` field in
    `CatalogData`. `CREATE TABLE` writes version 0; every committed `COPY` or `DELETE` writes the next version over the same file.

    We pick this because the commit mechanism already exists: `CatalogStore.write`
    writes a temp file and renames it over `catalog.json` with `ATOMIC_MOVE`. A
    version is therefore exactly one file, and a commit is exactly one rename — the
    old version is intact until the rename, and committed after it, with no state in between.

    The cost is that older versions do not exist on disk, so a snapshot can only
    point at a version still held in memory. We accept this because no statement
    outlives one run of the engine, and the course needs no time travel. A crash
    before the rename leaves the previous version valid plus orphaned partition
    files; we do not `fsync`, so a power loss can lose the last commit.

    > [!NOTE]
    > We could alternatively make one catalog file per version would keep the history 
    > readable, but needs a retention rule and blocks orphan cleanup. No statement outlives one run of our 
    > engine, so the complexity buys us nothing, but what do you think?? -->
    > (Mie): I think this makes a lot of sense, since the catalog data of actively
    > running processes can be held in-memory, and we avoid any overload of handling
    > some alternate deprecation policy. For the requirements in this course, this
    > follows a "just in time" paradigm, which I am very much a fan of <3

2. **Commit:** 
   A commit happens in two layers.

    On disk: we write the new version to `catalog.json.tmp` and then rename it to
    `catalog.json`. The rename happens in one step, so the file on disk is always
    either the old version or the new one, never a mix of the two.

    In memory: we never change a catalog object that is already in use. We build a
    new one with the new partition list and the version raised by one, and then point
    the `catalogs` map at it. That last step is a single assignment, so a statement
    reading at the same time sees either the old version or the new one.

    We do the disk first and the memory second. If the rename fails, we have not
    already told the rest of the engine that the new version applies.

    > [!NOTE]
    > We could also use a journal (write-ahead log), which is really the safest option
    > and what real databases do, since it survives a power loss too. But it would take
    > quite a bit more code, so maybe it is not needed here?
    > (Mie): I think this adds too much complexity considering the risk for our
    > engine; i.e. it is a very particular situation of complete power loss which
    > will likely be uncommon in our system. Since the behavior would just be that
    > the system would retain the old catalog file with some extra partition files,
    > I do not think the "risk" is too high, and the behavior of the system can be
    > motivated as only being completed actions before power loss.
    > We can always add some kind of clean-up-on-boot-up/down or a custom command.
    > EXTRA: in the slides the write-ahead log is also mentioned as a solution to
    > overwriting bytes in-place on partitions, we are working with immutable
    > partitions so far, which do one metadata swap to ref new files

3. **Snapshot:** 

   <!-- a statement pins the version it reads at start and never re-reads metadata.
   What exactly does it pin, and when?

    What: a reference to a catalog version · or its own copy of the partition list with offsets
    and counts.
    When: at statement start · or when the plan opens.
    Note for exercise 8: a scan that remembers *indices* into the catalog breaks when the list is
    swapped under it. It has to hold the partitions themselves.-->

    The statement pins a copy of a partition list for the version, meaning it has
    a copy of the list in-memory. This ensures the catalog can be safely overwritten
    with a newer version.

    The statement should pin the catalog version at the start of the planning step.
    This is the point where partition existence and statistics become important to
    retain between planning and execution. Since we have no schema-modifications
    or table deletions, the Binder can operate on an earlier version without impacting
    the validity of its conclusions for later versions of the same table.


4. **Retention:** 
   
   <!-- how many versions back does the engine keep?

    A version must not be dropped while a `SELECT` or `DELETE` still reads it. Defensible answers:
    reference counting · a list of running statements · a fixed number of retained versions.
    Also answer: what does a statement see when it asks for a version that is gone?-->

    Since our engine always overwrites the catalog.json, no previous versions are
    retained. As argued in section (1) we do not need time travel and existing runs
    will hold their catalog version in-memory until done. Thus, no previous versions
    are needed to be retained.
    The only retention is while the new version is being built but not yet committed.
    As described in (2) a different reader will also always read either the old
    or new version, there is no accessible intermediary state, due to the ATOMIC rename.

    A statement asking for a version that is gone should not happen, but if it does
    it will return a custom error and produce a log line.
    Partition Files live until the engine is shut-down, ensuring no referenced
    partitions are removed before references have been retired.

    > [!NOTE]
    > I'm unsure if this fully captures "the gist" of it, since it is not one of
    > the specified defensible answers ? 

5. **The rewrite set:** 
   
   <!-- `DELETE` rewrites exactly the partitions that may contain matches — the
   complement of week 4's pruning decision. Where does the shared helper live?

    The planner and `DELETE` must decide from the same code, or they will disagree about which
    partitions matter and `DELETE` will remove the wrong rows.
    Also answer: a partition that may contain a match but turns out to contain none — do we write
    an identical copy, or carry the original over?

    *Where we stand today:* `Pruner.canPrune` is public and used by `Planner`.
    -->

    Pruning already lives as its own class within the `storage` module.
    The branching (predicate vs. no-predicate) and looping over partitions could
    be shared, as this should remain the same. The main difference is the execution
    tree created by the `Planner` class in the `exec` module vs. what is the pattern
    for a delete statement. -> let's extend the Planner with an overloaded `plan`
    function for `DeleteStatement` and consider moving the iteration over partitions
    upstream to the `Pruner` class.

    For non-pruned partitions which contains no matches we carry the original over,
    i.e. we leave it untouched and retain the reference.

    > [!NOTE]
    > This part changed slightly in the code. Instead of extending the `Planner` with an
    > overloaded `plan` function for `DeleteStatement`, as the decision above says, we moved
    > the loop over the partitions into `Pruner.mayMatch`. The planner reads the partitions it
    > marks true, and `DELETE` rewrites them, so both still decide from the same code.
    > `DELETE` itself runs in `StorageEngine`, next to `COPY`.
    > Had we followed the original decision, the `Planner` would have had to write partition
    > files and commit a new catalog version, even though a `Plan` is built to return rows,
    > and a `DELETE` returns none. Writing to disk would then be split between two classes.
    > We therefore kept the `Planner` for reading only, and kept everything that writes to
    > disk and commits in one place.


6. **New version:** 
   <!-- does a `DELETE` that matches nothing still create a new version?
   --> 
   A DELETE that matches nothing produces a log-line but no new table version.

7. **Partition placement:** 
   
   <!-- do new partitions go at the end of the table's single data file, or
   into a file of their own?

    *Where we stand today:* one file per partition, named `partition-<index>.bin`, where the index
    restarts at 0 on every `copyFile`. Whatever we choose here, the naming has to become unique
    per table before repeated `COPY` is allowed, or the second `COPY` overwrites the first one's
    files.-->

    Since each partition in our system is its own file, new files are simply created
    at every new partition.
    We will ensure that indexes are increased appropriately to allow multiple COPY
    statements without marking the first one as 0 in each COPY statement execution.
    We will consider whether we should hex-encode our "partition-number" to have
    fixed-length file naming standard, since the partition number is never actively
    used except to ensure uniqueness of filenames.

---

## Orphans

<!-- A crash after writing new partitions but before the commit leaves bytes that no catalog entry
points to. They are invisible and harmless, but they waste disk space.

How would we find and clean them up?-->

On start-up / close-down non-referenced partitions are cleaned up.
This avoids cleaning referenced partitions before they have been used by the workflow
referencing them, as the processes have not yet started or have already finished
at the point of which we clean up.
