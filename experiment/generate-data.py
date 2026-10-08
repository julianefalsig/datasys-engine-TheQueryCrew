#!/usr/bin/env python3
"""Generates the CSV tables for the partition-size sweep in docs/experiment-design.md.

Run from the repository root:

    python3 experiment/generate-data.py          # write the tables, then verify checksums
    python3 experiment/generate-data.py --check  # verify only, without rewriting

One shuffled CSV per table size, all with the trips schema the engine already knows:

    city STRING, distance LONG, price DOUBLE

The design calls for "matching distributions for relevant fields", so that one fixed predicate
returns the same fraction of rows at every table size. That is enforced here rather than left to
sampling: in every file exactly 1% of the rows satisfy `distance > 990`, so the selectivity is 1.00%
at 5,000 rows and at 10,000,000 rows alike, and a throughput curve across table sizes is comparable.

Randomness comes from splitmix64 written out below, not from Python's `random`. A fixed seed into
the standard library is only reproducible for a given Python build; this is reproducible for anyone,
which is what lets a teammate regenerate byte-identical files. `checksums.txt` is committed so they
can prove it.
"""

import argparse
import hashlib
import pathlib
import sys

SEED = 20261006

# docs/experiment-design.md, "Question and x axis"
TABLE_SIZES = [5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000, 5_000_000, 10_000_000]

# The fixed predicate is `distance > THRESHOLD`; distances live in [0, DISTANCE_RANGE).
SELECTIVITY = 0.01
THRESHOLD = 990
DISTANCE_RANGE = 1000

CITIES = ["Copenhagen", "Aarhus", "Odense", "Aalborg", "Esbjerg",
          "Randers", "Kolding", "Horsens", "Vejle", "Roskilde"]

OUT_DIR = pathlib.Path("experiment/data")
CHECKSUMS = pathlib.Path("experiment/checksums.txt")

MASK64 = (1 << 64) - 1


class SplitMix64:
    """A 64-bit PRNG with an explicit, portable definition, so output never depends on the host."""

    def __init__(self, seed):
        self.state = seed & MASK64

    def next_u64(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return z ^ (z >> 31)

    def below(self, bound):
        """Uniform in [0, bound). Rejection sampling, so the result carries no modulo bias."""
        limit = MASK64 - (MASK64 % bound)
        while True:
            value = self.next_u64()
            if value <= limit:
                return value % bound

    def shuffle(self, items):
        """Fisher-Yates, so the matching rows end up spread through the file."""
        for i in range(len(items) - 1, 0, -1):
            j = self.below(i + 1)
            items[i], items[j] = items[j], items[i]


def rows_for(size, rng):
    """`size` rows, of which exactly `round(size * SELECTIVITY)` satisfy the predicate."""
    matching = round(size * SELECTIVITY)
    distances = [THRESHOLD + 1 + rng.below(DISTANCE_RANGE - THRESHOLD - 1) for _ in range(matching)]
    distances += [rng.below(THRESHOLD + 1) for _ in range(size - matching)]
    rng.shuffle(distances)

    rows = []
    for distance in distances:
        city = CITIES[rng.below(len(CITIES))]
        # Price correlates with distance but is not a function of it, so a predicate on one column
        # says little about the other. Two decimals, because the grammar's DOUBLE needs a point.
        price = (distance * 150 + rng.below(5_000)) / 100
        rows.append("%s,%d,%.2f" % (city, distance, price))
    return rows


def write_tables():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for size in TABLE_SIZES:
        # One stream per table, seeded from the size, so adding a size leaves the others untouched.
        rng = SplitMix64(SEED + size)
        path = OUT_DIR / ("trips-%d.csv" % size)
        path.write_text("\n".join(rows_for(size, rng)) + "\n")
        written.append(path)
        matching = round(size * SELECTIVITY)
        print("%-28s %9d rows, %6d matching (%.2f%%)"
              % (path, size, matching, 100.0 * matching / size))
    return written


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    """Compares the files on disk against the committed checksums. Returns an exit code."""
    if not CHECKSUMS.exists():
        lines = ["%s  %s" % (digest(OUT_DIR / ("trips-%d.csv" % s)), "trips-%d.csv" % s)
                 for s in TABLE_SIZES]
        CHECKSUMS.write_text("\n".join(lines) + "\n")
        print("\nwrote %s — commit it, so a teammate can verify their own run" % CHECKSUMS)
        return 0

    expected = {}
    for line in CHECKSUMS.read_text().split("\n"):
        if line.strip():
            want, name = line.split()
            expected[name] = want

    failed = []
    for size in TABLE_SIZES:
        name = "trips-%d.csv" % size
        path = OUT_DIR / name
        if not path.exists():
            failed.append("%s is missing" % name)
        elif name not in expected:
            failed.append("%s is not in %s" % (name, CHECKSUMS))
        elif digest(path) != expected[name]:
            failed.append("%s does not match the committed checksum" % name)

    if failed:
        print("\nverification FAILED:", file=sys.stderr)
        for problem in failed:
            print("  " + problem, file=sys.stderr)
        return 1
    print("\nall %d files match %s" % (len(TABLE_SIZES), CHECKSUMS))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the files already on disk instead of writing them")
    args = parser.parse_args()

    if not args.check:
        write_tables()
    return verify()


if __name__ == "__main__":
    sys.exit(main())
