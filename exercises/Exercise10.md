# Exercise 10: Multi-Table Hash Joins

How to Build Data Systems – Fall 2026

Exercises 10 to 12 form Project 3: joins and plan reordering. In this exercise, the engine learns multi-table joins. The grammar adds table lists and `AND`, the binder resolves column names across tables, and a hash join operator executes chains of at least four tables. The planner is deliberately naive this week: it joins tables in `FROM`-list order, left to right. That baseline is what week 11 optimizes and week 12 measures. This exercise builds on your Part 2 repository.

One restriction keeps the front end small: column names remain unqualified. There is no `orders.o_custname`, only `o_custname`, so each column name in the query must occur in exactly one listed table. Schemas make that practical by prefixing columns per table (`o_`, `c_`, `p_`, `ct_`), as in TPC-H. Qualified names and column lists are optional extensions.

## 1. Grammar: table lists and AND

```antlr
select    : SELECT '*' FROM IDENTIFIER (',' IDENTIFIER)* (WHERE condition)? ;
delete    : DELETE FROM IDENTIFIER (WHERE filter)? ;
condition : predicate (AND predicate)* ;
predicate : filter | join ;
filter    : IDENTIFIER comparison=('=' | '<' | '>') literal ;
join      : left=IDENTIFIER '=' right=IDENTIFIER ;
AND       : 'AND' ;   // lexer: keyword rules before IDENTIFIER, as always
```

The old `predicate` rule becomes `filter`, and `DELETE` keeps that restricted form: a delete still touches one table and compares one column with one constant. A `SELECT` may now carry a conjunction of predicates, but only with `AND`, not `OR`, and without parentheses. The genuinely new case is the second `predicate` shape: a column compared with a column.

The AST separates the two shapes, and the builder sorts each parsed predicate into the right list:

```java
public record JoinPredicate(String leftColumn, String rightColumn) { }

public record SelectStatement(List<String> tableNames, List<Predicate> filters,
        List<JoinPredicate> joins) implements Statement { }
```

Changing `SelectStatement` breaks every `switch` over the sealed `Statement`, and the compiler again writes the to-do list: printer, binder, planner. The round-trip property `parse(print(s)) = s` survives the two lists: print filters and joins in any fixed order, and reparsing sorts them back into the same lists.

## 2. Binding without qualified names

The binder resolves each column name against the schemas of all tables in the `FROM` list:

- A name found in no table is an unknown column, as before.
- A name found in two or more tables is ambiguous. DuckDB agrees: `Binder Error: Ambiguous reference to column name "id"`.
- The two sides of a `JoinPredicate` must resolve to two different tables and to columns of the same type. The type rule matters because the hash join compares keys with Java `equals`: a `LONG` 3 and a `DOUBLE` 3.0 are never equal, and a type-mismatched join would silently return no rows. DuckDB would coerce; your engine rejects. Rejecting more than DuckDB is acceptable, because the compatibility promise only says that what your engine accepts must run in DuckDB.
- Filters bind as before: an existing column and a constant of that column's type.

Both the binder and the planner need the same answer to the question "which table owns this column, and at which position does it sit in the joined row?" Write that resolution once, as a small helper built from the `FROM`-list schemas, and unit-test it. The joined row is the concatenation of the table schemas in `FROM`-list order, so the helper maps a column name to one table and one index in the concatenated row.

## 3. The hash join operator

```java
/** Inner equi-join: builds a hash table from the build child, streams the probe child. */
public final class HashJoinOperator implements Operator {
    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) { ... }
}
```

- `open()` drains the build child into a `HashMap<Object, List<Object[]>>`, from key to rows. A key maps to a list because keys can repeat.
- `next()` pulls probe rows and looks up the key. A probe row that matches `k` build rows emits `k` output rows, so the operator keeps a cursor into the current match list between calls. Each output row is the build row's columns followed by the probe row's columns.
- Rows without a partner on the other side are dropped: this is an inner join, the only kind this project requires.
- `close()` logs the operator summary: `rowsBuild=4 rowsProbe=3 rowsOut=4`. The `rowsOut` of each join is the size of an intermediate result, the number week 11 optimizes and week 12 measures, so it enters the log now.

Unit-test the operator over `TestListOperator` stubs, including the cases that reveal the cursor: duplicates on both sides multiply (2 build rows and 3 probe rows with the same key emit 6 rows), an empty build side, and an empty probe side.

## 4. Planning a chain of joins

The planner first builds one pipeline per table, exactly as in Part 1: a `Scan` over the partitions that survive pruning, wrapped in a `Filter` if the query has filters on that table's columns. Pruning still works per table because each filter binds to exactly one table. A multi-table statement pins one snapshot per table at statement start.

Then it folds the `FROM` list from left to right. For each new table, find a join predicate that connects it to the tables already joined, and build `HashJoinOperator(joinedSoFar, newTable, buildKey, probeKey)`: the intermediate result is the build side, and the new table probes. That choice is deliberately naive; week 11 turns it into an optimization problem. Because the fold follows the `FROM` list and output rows are build columns followed by probe columns, `SELECT *` emits columns in `FROM`-list order, which matches DuckDB.

One case the planner must reject with a clear error is a table for which no join predicate connects it to the tables before it. That case would create a cross product, and the message should suggest reordering the `FROM` list. One simplification is acceptable: support only one connecting predicate per new table, but document what your engine does with a second one, either reject it cleanly or apply it as a filter on the join output.

## 5. The testbed

Use a small hand-checkable dataset, with per-table prefixes so unqualified names resolve:

```sql
CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
CREATE TABLE products (p_name STRING, p_price DOUBLE);
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
```

```
orders.csv:    100,Anna,Bike,1 / 101,Bob,Helmet,2 / 102,Carol,Bike,1 / 103,Anna,Helmet,1
products.csv:  Bike,2999.0 / Helmet,299.0
customers.csv: Anna,Copenhagen / Bob,Aarhus / Carol,Copenhagen
cities.csv:    Copenhagen,Denmark,1400000 / Aarhus,Denmark,290000
```

```sql
SELECT * FROM orders, customers WHERE o_custname = c_name;
SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name;
SELECT * FROM orders, customers, products, cities
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;
SELECT * FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
```

Each of the first three queries returns 4 rows, one per order, because every order finds its customer, its product, and its city. The filtered query returns 3. DuckDB remains the oracle, with two footnotes. First, a hash join emits rows in the order produced by the probe side, and DuckDB makes no promise either, so compare sorted results: `sort ours.csv` against `sort theirs.csv`. Second, `cities` carries a `LONG` population column on purpose: an all-`STRING` CSV makes DuckDB's sniffer read line 1 as a header and silently drop a row, the divergence discussed in lecture 2.

## 6. Optional extensions

- **Qualified names and table aliases:** `orders.o_custname`, and `FROM customers c1, customers c2` with `c1.c_city = c2.c_city`. This removes the no-clashing-names restriction and makes self-joins possible.
- **Column lists over joins:** `SELECT c_name, o_quantity FROM ...`, the Part 1 extension applied to multiple tables; teams that built a `ProjectOperator` in Part 1 mostly need binder work.

## 7. Required tests (JUnit 6)

1. Parser: table lists and `AND` chains parse; column-to-column lands in `joins`, column-to-constant in `filters`; the printer round-trip covers the new shapes.
2. Binder: unknown column, ambiguous column (two tables sharing a name), a join across mismatched types, and a join whose two sides land in the same table each throw `IllegalArgumentException`.
3. Column resolution helper: name to table and to index in the concatenated row, including the error cases.
4. `HashJoinOperator` over `TestListOperator` stubs: duplicates multiply, unmatched rows are dropped, empty build side, empty probe side.
5. Planner: a four-table query builds three hash joins, left-deep in `FROM`-list order, with filters below the joins; per-table pruning still visible in `ScanStats`; an unconnected `FROM` list is rejected.
6. Integration: the testbed script through `./engine -f`; sorted output equals the sorted DuckDB output for all four queries.

## 8. Cut release v0.10

```bash
git checkout main && git pull
git tag -a v0.10 -m "Exercise 10: multi-table hash joins"
git push origin v0.10
```

This creates the tag that the teaching assistant will check out to validate your project state.

## Definition of done

- [ ] Grammar and AST grown: table lists, `AND` conjunctions, join predicates; round-trip property still holds.
- [ ] Binder resolves unqualified names to exactly one table; ambiguity and type-mismatched joins rejected with clear errors.
- [ ] Joins of at least four tables execute through the main query path, left-deep in `FROM`-list order.
- [ ] Filters pushed below the joins; partition pruning still works per table.
- [ ] Every join logs `rowsBuild`, `rowsProbe`, `rowsOut` in `close()`.
- [ ] All required tests green in CI; PRs reviewed.
- [ ] Tag `v0.10` is pushed.

## Outlook

In week 11, the planner stops trusting the `FROM` list: statistics in the catalog estimate each join output, and the engine chooses the order with the smallest intermediate results.
