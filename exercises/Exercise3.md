# Exercise 3: Parsing SQL Text into a Bound AST

How to Build Data Systems – Fall 2026

In this exercise, you put a SQL front end in front of last week's storage engine, as text processing only. SQL text becomes a typed, validated AST; nothing executes yet (wiring the AST to the engine is week 4). ANTLR generates the lexer and parser from your grammar. You need to provide the AST, the visitor, and the binder. No SQL-processing libraries are allowed. This exercise builds on your Exercise 2 repository.

Every statement your grammar accepts must also run unchanged in DuckDB.

## 1. The SQL subset

```sql
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;              -- WHERE is optional, as in DuckDB
```

- Keywords are case-insensitive; identifiers are letters, digits, and underscores, starting with a letter or underscore.
- Literals: single-quoted ASCII strings, whole numbers, numbers with a decimal point; both numeric forms may be negative. Their Java types after parsing are `String`, `Long`, `Double`, exactly the constant types `select` demands.
- Comparisons: `=`, `<`, `>`. Statements end with `;`. `--` starts a comment to end of line.
- Out of scope: escaped quotes inside string literals (`''`).

## 2. Generate the parser with ANTLR

Add to `pom.xml`:

```xml
<properties>
  <antlr.version>4.13.2</antlr.version>
</properties>

<dependency>
  <groupId>org.antlr</groupId>
  <artifactId>antlr4-runtime</artifactId>
  <version>${antlr.version}</version>
</dependency>

<plugin>
  <groupId>org.antlr</groupId>
  <artifactId>antlr4-maven-plugin</artifactId>
  <version>${antlr.version}</version>
  <configuration>
    <visitor>true</visitor>
  </configuration>
  <executions>
    <execution>
      <goals><goal>antlr4</goal></goals>
    </execution>
  </executions>
</plugin>
```

The plugin generates a listener but no visitor unless `<visitor>` says so, and Task 3 builds the AST
with a visitor. Without it the build fails on a missing `SqlBaseVisitor`.

The grammar lives at `src/main/antlr4/dk/itu/datasys/sql/Sql.g4`; the directory path below `antlr4/` becomes the Java package of the generated classes. Generated sources land in `target/generated-sources/antlr4` and join the compile path automatically. Never commit ANTLR-generated code.

Grammar skeleton:

```antlr
grammar Sql;

options { caseInsensitive = true; }   // ANTLR ≥ 4.10

script      : (statement ';')+ EOF ;
statement   : createTable | copy | select ;

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

select      : SELECT '*' FROM IDENTIFIER (WHERE predicate)? ;
predicate   : IDENTIFIER comparison=('=' | '<' | '>') literal ;
literal     : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;

// Lexer. Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ;   TABLE : 'TABLE' ;   COPY : 'COPY' ;   FROM : 'FROM' ;
SELECT : 'SELECT' ;   WHERE : 'WHERE' ;
STRING : 'STRING' ;   LONG : 'LONG' ;   DOUBLE : 'DOUBLE' ;

IDENTIFIER      : [A-Z_] [A-Z_0-9]* ;        // caseInsensitive covers a–z
LONG_LITERAL    : '-'? [0-9]+ ;
DOUBLE_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
STRING_LITERAL  : '\'' ~['\r\n]* '\'' ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
WS              : [ \t\r\n]+ -> skip ;
```

Note that the type keywords `STRING`/`LONG`/`DOUBLE` are tokens distinct from the literal tokens, and that `caseInsensitive` affects how rules match, not the token text: identifier casing is preserved as written.

## 3. Build the AST

```java
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }

public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement { }

public record CopyStatement(String tableName, String csvFilePath)
        implements Statement { }

public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }

public record Predicate(String columnName, Comparison comparison, Object constant) { }
```

Reuse `ColumnSpec`, `ColumnType`, and `Comparison` from Exercise 2; do not duplicate them. The AST speaks the storage API's vocabulary, which makes next week's wiring a near-trivial mapping. Records also give you generated `equals`, so an expected AST is one `assertEquals` away.

Build the AST in one visitor class (e.g. `SqlAstBuilder extends SqlBaseVisitor<Object>`). Type the literals while building: `'Odense'` → `String`, `12` → `Long`, `23.5` → `Double`.

The parser facade:

```java
public final class SqlParser {
    /** Parses a whole script of ';'-terminated statements. */
    public List<Statement> parse(String sqlText) { /* ... */ }
}

public final class SqlParseException extends RuntimeException {
    public int line()   { /* 1-based */ }
    public int column() { /* 0-based, ANTLR's convention */ }
}
```

Out of the box, ANTLR prints syntax errors to stderr and tries to recover. Call `removeErrorListeners()` on both lexer and parser and register a listener that throws `SqlParseException` at the first syntax error.

## 4. The binder

The binder needs schema lookups, so `StorageEngine` grows one read-only accessor:

```java
/** The table's schema, in column order. Throws IllegalArgumentException if unknown. */
public List<ColumnSpec> schema(String tableName) { /* ... */ }
```

```java
public final class Binder {
    public Binder(StorageEngine engine) { /* ... */ }
    /** Validates s against the catalog; throws IllegalArgumentException on the first violation. */
    public void bind(Statement s) { /* ... */ }
}
```

Binding checks:

- `SELECT`: the table exists; if a `WHERE` is present, the column exists and the constant's Java type matches the column type.
- `COPY`: the table exists. (Whether the file exists is execution's concern.)
- `CREATE TABLE`: non-empty column list, no duplicate names. Whether the table already exists is left to execution, where the check can be made atomically.

Binder errors throw `IllegalArgumentException`, the same family as the engine API.

## 5. Pretty-printer and runnable demo

```java
public final class SqlPrinter {
    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) { /* ... */ }
}
```

The printer renders from the AST, not from the input text: it never keeps the original string around. That is what makes it evidence that the parse captured everything. Normalization is expected and welcome, for instance uppercased keywords and a dropped comment, as long as `parse(print(s))` gives back an equal statement (test 6).

**Requirement:** `mvn compile exec:java`, with no further arguments, parses the four statements from Task 1 and prints their pretty-printed form, one statement per line. This replaces Exercise 2's golden-query printout. Nothing executes yet.

```sql
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
```

## 6. Logging

At least one log line per `parse` call: the success line at `debug`, the failure line at `error`:

```
statements=3 durationMs=2
failed line=1 col=22 durationMs=0
```

## 7. Required tests (JUnit 6)

Unit (parser):

1. Each statement shape, including `SELECT` with and without `WHERE`, parses to the expected AST.
2. Literal typing: `12` → `Long`, `12.0` → `Double`, `'12'` → `String`; negative `-1` and `-1.5`.
3. Keyword case-insensitivity: `select * from trips;` parses; identifier casing is preserved.
4. At least five malformed inputs, each asserting line and column: missing `;`, unbalanced parens, unknown type name (`city TEXT`), unterminated string literal, missing `FROM`.
5. Comments and whitespace are skipped.

Unit (pretty-printer):

6. The `parse(print(s))` round-trip property over every statement shape.

Integration (binder, engine on `@TempDir`):

7. Valid statements bind; unknown table, unknown column, type-mismatched constant (`distance = 'x'`), and duplicate columns in `CREATE TABLE` each throw `IllegalArgumentException`.

## 8. Cut release v0.3

```bash
git checkout main && git pull
git tag -a v0.3 -m "Exercise 3: SQL front end"
git push origin v0.3
```

This creates a tag that the teaching assistant will check out to validate the state of your project.

## Definition of done

- [ ] ANTLR wired into the Maven build; generated sources not committed.
- [ ] Grammar covers the subset: case-insensitive keywords, optional `WHERE`, comments, negative literals.
- [ ] `SqlParser.parse` returns `List<Statement>` or throws `SqlParseException` with line and column.
- [ ] AST records reuse `ColumnSpec`, `ColumnType`, `Comparison` from week 2.
- [ ] Binder validates against the catalog via the new `schema(...)` accessor.
- [ ] Pretty-printer with the round-trip property test.
- [ ] `mvn compile exec:java` prints the pretty-printed AST of the four Task 1 statements.
- [ ] All required tests green in CI.
- [ ] All PRs reviewed and merged into main, which is branch-protected.
- [ ] Tag `v0.3` is pushed.

## Outlook

In week 4, a planner and executor turn bound ASTs into calls to the week 2 API, and the engine gets a SQL front door that runs whole `.sql` scripts. The grammar keeps growing later: `DELETE` in Part 2, `JOIN` in Part 3.