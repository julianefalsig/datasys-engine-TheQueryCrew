#!/usr/bin/env python3
"""A skewed alternate of the Exercise 12 benchmark data.

The uniform generator gives every customer the same number of orders and shipments, and every
city the same number of customers. The join formula |R| · |S| / max(ndv) is then exact, which is
why the published run's estRows and rowsOut are the same number.

This generator keeps the same schema and the same foreign-key domain, and breaks only the
uniformity assumption:

- A few customers account for most orders and most shipments. The two facts use the same ranking,
  so a customer who orders a lot also ships a lot. The formula still divides by the number of
  customers and therefore misses that explosion.
- Cities are filled the same way, heaviest customers first. `c_city = 'city000'` keeps far more
  than |customers| / ndv(city). The equality estimate stays |R| / ndv, which is the average city.

Keys are still shuffled inside that multiset, and at this scale each table fits in one partition,
so the catalog NDV is the real NDV. Whatever estRows and rowsOut disagree by is the skew.

    python3 experiments/project_3/generate-skewed-data.py

Writes experiments/project_3/data-skew/{customers,orders,shipments}.csv and prints the uniform
estimate next to the actual intermediate size. Run the benchmark with:

    DATA=experiments/project_3/data-skew RESULTS=experiments/project_3/skewed_results \\
        CITY=city000 experiments/project_3/run-benchmark.sh
"""

import argparse
import pathlib

SEED = 20261127
CITIES = 20

OUT_DIR = pathlib.Path("experiments/project_3/data-skew")

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
        limit = MASK64 - (MASK64 % bound)
        while True:
            value = self.next_u64()
            if value <= limit:
                return value % bound

    def shuffle(self, items):
        for i in range(len(items) - 1, 0, -1):
            j = self.below(i + 1)
            items[i], items[j] = items[j], items[i]


def customer_name(i):
    return f"cust{i:05d}"


def zipf_counts(n, total):
    """Positive integers summing to `total`, proportional to 1, 1/2, ..., 1/n."""
    weights = [1 / (rank + 1) for rank in range(n)]
    mass = sum(weights)
    counts = [max(1, int(total * weight / mass)) for weight in weights]
    counts[0] += total - sum(counts)
    if min(counts) < 1:
        raise SystemExit(f"total {total} is too small to give all {n} keys a row")
    return counts


def assign_cities(customers, city_sizes):
    """Heaviest customers fill the heaviest city, then the next city, and so on."""
    city_of = [0] * customers
    cursor = 0
    for city, size in enumerate(city_sizes):
        for _ in range(size):
            city_of[cursor] = city
            cursor += 1
    return city_of


def expand(counts, rng):
    keys = [customer_name(i) for i, count in enumerate(counts) for _ in range(count)]
    rng.shuffle(keys)
    return keys


def write(name, lines):
    path = OUT_DIR / name
    with path.open("w", newline="\n") as f:
        f.writelines(line + "\n" for line in lines)
    print(f"wrote {path} ({len(lines):,} rows)")


def report(customers, orders, shipments, city_of, city_sizes):
    """What the uniform formulas say, and what the rows actually produce."""
    ndv = customers
    fact_estimate = len(orders) * len(shipments) / ndv
    fact_actual = sum(o * s for o, s in zip(orders, shipments))
    heavy = city_sizes[0]
    filter_estimate = customers / CITIES
    ship_in_city = sum(shipments[i] for i, city in enumerate(city_of) if city == 0)
    dim_estimate = filter_estimate * len(shipments) / ndv
    print()
    print(f"city000 keeps {heavy} customers; the equality estimate says {filter_estimate:.0f}")
    print(f"customers ⋈ shipments: estimate {dim_estimate:,.0f}, actual {ship_in_city:,}")
    print(f"orders ⋈ shipments:    estimate {fact_estimate:,.0f}, actual {fact_actual:,}")
    print("both estimates are low, and the filtered dimension is still the smaller intermediate")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--customers", type=int, default=200)
    parser.add_argument("--facts", type=int, default=8_000, help="rows in orders, and the same number in shipments")
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()

    rng = SplitMix64(args.seed)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    order_counts = zipf_counts(args.customers, args.facts)
    ship_counts = list(order_counts)
    city_sizes = zipf_counts(CITIES, args.customers)
    city_of = assign_cities(args.customers, city_sizes)

    write("customers.csv",
          [f"{customer_name(i)},city{city_of[i]:03d}" for i in range(args.customers)])

    orders = expand(order_counts, rng)
    write("orders.csv",
          [f"{key},{name},{1 + rng.below(10)}" for key, name in enumerate(orders, start=1)])

    shipments = expand(ship_counts, rng)
    write("shipments.csv",
          [f"{key},{name},{rng.below(100)}.{rng.below(100):02d}"
           for key, name in enumerate(shipments, start=1)])

    report(args.customers, order_counts, ship_counts, city_of, city_sizes)


if __name__ == "__main__":
    main()
