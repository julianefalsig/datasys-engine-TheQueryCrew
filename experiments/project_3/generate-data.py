#!/usr/bin/env python3
"""Generates the three tables for Exercise 12's join-order benchmark.

Run from the repository root:

    python3 experiments/project_3/generate-data.py                       # the sizes from the exercise
    python3 experiments/project_3/generate-data.py --customers 2000 --per-customer 50

    customers (c_name STRING, c_city STRING)                         -- dimension table
    orders    (o_orderkey LONG, o_custname STRING, o_quantity LONG)  -- fact table
    shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE)   -- fact table

`c_name` is unique. Every customer has exactly `--per-customer` orders and as many shipments, so
`o_custname` and `s_custname` are foreign keys that repeat that often. The foreign keys are
shuffled rather than written in customer order, so a partition's NDV stays close to the table's.
Cities are assigned round-robin over CITIES cities, so `c_city = 'city042'` keeps exactly
1 / CITIES of the customers: the selectivity is stated, not measured.

Randomness is splitmix64, written out below as in Project 1, so the files do not depend on the
Python build and a teammate regenerates byte-identical data from the same seed.
"""

import argparse
import pathlib

SEED = 20261126
CITIES = 100

OUT_DIR = pathlib.Path("experiments/project_3/data")

MASK64 = (1 << 64) - 1


class SplitMix64:
    """A 64-bit PRNG with an explicit, portable definition."""

    def __init__(self, seed):
        self.state = seed & MASK64

    def next_u64(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return z ^ (z >> 31)

    def below(self, bound):
        """Uniform in [0, bound), by rejection sampling, so without modulo bias."""
        limit = MASK64 - (MASK64 % bound)
        while True:
            value = self.next_u64()
            if value <= limit:
                return value % bound

    def shuffle(self, items):
        """Fisher-Yates."""
        for i in range(len(items) - 1, 0, -1):
            j = self.below(i + 1)
            items[i], items[j] = items[j], items[i]


def customer_name(i):
    return f"cust{i:05d}"


def foreign_keys(customers, per_customer, rng):
    """Each customer exactly `per_customer` times, in shuffled order."""
    keys = [customer_name(i) for i in range(customers) for _ in range(per_customer)]
    rng.shuffle(keys)
    return keys


def write(name, lines):
    path = OUT_DIR / name
    with path.open("w", newline="\n") as f:
        f.writelines(line + "\n" for line in lines)
    print(f"wrote {path} ({len(lines):,} rows)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--customers", type=int, default=1_000)
    parser.add_argument("--per-customer", type=int, default=100)
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    if args.customers % CITIES != 0:
        parser.error(f"--customers must be a multiple of {CITIES}, so every city gets the same share")

    rng = SplitMix64(args.seed)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    write("customers.csv",
          [f"{customer_name(i)},city{i % CITIES:03d}" for i in range(args.customers)])

    orders = foreign_keys(args.customers, args.per_customer, rng)
    write("orders.csv",
          [f"{key},{name},{1 + rng.below(10)}" for key, name in enumerate(orders, start=1)])

    shipments = foreign_keys(args.customers, args.per_customer, rng)
    # Two decimals, written out so the value always has the decimal point the engine's DOUBLE needs.
    write("shipments.csv",
          [f"{key},{name},{rng.below(100)}.{rng.below(100):02d}"
           for key, name in enumerate(shipments, start=1)])


if __name__ == "__main__":
    main()
