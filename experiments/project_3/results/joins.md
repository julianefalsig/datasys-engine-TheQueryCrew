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

Each plan once, with the configurations that ran it. Arrows carry estimated / actual rows.

```mermaid
flowchart LR
    subgraph plan1["min, optimized (small to big), optimized (big to small)"]
        direction LR
        plan1_customers["customers<br/>c_city = 'city042'"] -->|"build"| plan1_j1(("join 1"))
        plan1_shipments["shipments"] -->|"probe"| plan1_j1
        plan1_j1 -->|"est 1,000 / actual 1,000<br/>build"| plan1_j2(("join 2"))
        plan1_orders["orders"] -->|"probe"| plan1_j2
        plan1_j2 -->|"est 100,000 / actual 100,000"| plan1_result["result"]
    end
    subgraph plan2["max"]
        direction LR
        plan2_orders["orders"] -->|"build"| plan2_j1(("join 1"))
        plan2_shipments["shipments"] -->|"probe"| plan2_j1
        plan2_j1 -->|"est 10,000,000 / actual 10,000,000<br/>build"| plan2_j2(("join 2"))
        plan2_customers["customers<br/>c_city = 'city042'"] -->|"probe"| plan2_j2
        plan2_j2 -->|"est 100,000 / actual 100,000"| plan2_result["result"]
    end
```
