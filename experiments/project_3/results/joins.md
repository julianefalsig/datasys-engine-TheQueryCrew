| Configuration | Join | Tables (build, then probe) | estRows | rowsOut |
|---|---|---|---|---|
| min | 1 | customers, shipments | 1000 | 1,000 |
| min | 2 | customers, shipments, orders | 100000 | 100,000 |
| max | 1 | orders, shipments | 10000000 | 10,000,000 |
| max | 2 | orders, shipments, customers | 100000 | 100,000 |
| optimized_min_from | 1 | customers, shipments | 1000 | 1,000 |
| optimized_min_from | 2 | customers, shipments, orders | 100000 | 100,000 |
| optimized_max_from | 1 | customers, shipments | 1000 | 1,000 |
| optimized_max_from | 2 | customers, shipments, orders | 100000 | 100,000 |

| Configuration | Chosen join order |
|---|---|
| min | customers, shipments, orders |
| max | orders, shipments, customers |
| optimized_min_from | customers, shipments, orders |
| optimized_max_from | customers, shipments, orders |

```mermaid
flowchart LR
    subgraph good["min, optimized (small to big), optimized (big to small)"]
        direction LR
        c1["customers<br/>c_city = 'city042'"] -->|"build"| j1(("join 1"))
        s1["shipments"] -->|"probe"| j1
        j1 -->|"est 1,000 / actual 1,000<br/>build"| j2(("join 2"))
        o1["orders"] -->|"probe"| j2
        j2 -->|"est 100,000 / actual 100,000"| r1["result"]
    end

    subgraph bad["max: syntax, big to small"]
        direction LR
        o2["orders"] -->|"build"| k1(("join 1"))
        s2["shipments"] -->|"probe"| k1
        k1 -->|"est 10,000,000 / actual 10,000,000<br/>build"| k2(("join 2"))
        c2["customers<br/>c_city = 'city042'"] -->|"probe"| k2
        k2 -->|"est 100,000 / actual 100,000"| r2["result"]
    end
```
