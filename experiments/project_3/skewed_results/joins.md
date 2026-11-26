| Configuration | Join | Tables (build, then probe) | estRows | rowsOut |
|---|---|---|---|---|
| min | 1 | customers, shipments | 400 | 6,563 |
| min | 2 | customers, shipments, orders | 16000 | 3,290,633 |
| max | 1 | orders, shipments | 320000 | 3,307,846 |
| max | 2 | orders, shipments, customers | 16000 | 3,290,633 |
| optimized_min_from | 1 | customers, shipments | 400 | 6,563 |
| optimized_min_from | 2 | customers, shipments, orders | 16000 | 3,290,633 |
| optimized_max_from | 1 | customers, shipments | 400 | 6,563 |
| optimized_max_from | 2 | customers, shipments, orders | 16000 | 3,290,633 |

| Configuration | Chosen join order |
|---|---|
| min | customers, shipments, orders |
| max | orders, shipments, customers |
| optimized_min_from | customers, shipments, orders |
| optimized_max_from | customers, shipments, orders |
