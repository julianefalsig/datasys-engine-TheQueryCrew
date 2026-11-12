package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.StorageEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//Checks a parsed statement against the catalog

public final class Binder {

    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = engine;
    }

    // Validates s against the catalog; throws IllegalArgumentException on the first violation.
    public void bind(Statement statement) {
        switch (statement) {
            case CopyStatement copy -> bindCopy(copy);
            case SelectStatement select -> bindSelect(select);
            case CreateTableStatement create -> bindCreateTable(create);
            case DeleteStatement delete -> bindDelete(delete);
        }
    }
    // The CSV file is execution's concern. The table is not: a missing one fails here.
    private void bindCopy(CopyStatement copy) {
        requireTable(copy.tableName());
    }

    // Each name must land on exactly one column of the FROM list. A join must pair two tables of one type.
    private void bindSelect(SelectStatement select) {
        List<String> tableNames = select.tableNames();
        requireDistinctTables(tableNames);
        List<List<ColumnSpec>> schemas = new ArrayList<>(tableNames.size());
        for (String tableName : tableNames) {
            schemas.add(requireTable(tableName));
        }
        ColumnResolver columns = new ColumnResolver(tableNames, schemas);
        for (Predicate filter : select.filters()) {
            requireConstantMatchesColumn(columns.resolve(filter.columnName()), filter);
        }
        for (JoinPredicate join : select.joins()) {
            bindJoin(columns, join);
        }
    }

    // The table must exist, and a WHERE must fit its schema.
    private void bindDelete(DeleteStatement delete) {
        String tableName = delete.tableName();
        ColumnResolver columns = new ColumnResolver(List.of(tableName), List.of(requireTable(tableName)));
        // Without a WHERE there is nothing to check beyond the table itself.
        if (delete.where().isEmpty()) {
            return;
        }
        Predicate predicate = delete.where().get();
        requireConstantMatchesColumn(columns.resolve(predicate.columnName()), predicate);
    }

    private static void requireDistinctTables(List<String> tableNames) {
        Set<String> seen = new HashSet<>();
        for (String tableName : tableNames) {
            if (seen.add(tableName)) {
                continue;
            }
            throw new IllegalArgumentException("table listed more than once: " + tableName);
        }
    }

    private List<ColumnSpec> requireTable(String tableName) {
        try {
            return engine.schema(tableName);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown table: " + tableName, failure);
        }
    }

    private static void requireConstantMatchesColumn(ColumnResolver.ResolvedColumn column, Predicate predicate) {
        if (column.column().type().accepts(predicate.constant())) {
            return;
        }
        throw new IllegalArgumentException(
                "constant type %s does not match column %s of type %s"
                        .formatted(predicate.constant().getClass().getSimpleName(),
                                column.tableName(), column.column().name(), column.column().type()));
    }

    // Java equals never treats a LONG 3 as a DOUBLE 3.0, so a mismatched join would return no rows.
    private static void bindJoin(ColumnResolver columns, JoinPredicate join) {
        ColumnResolver.ResolvedColumn left = columns.resolve(join.leftColumn());
        ColumnResolver.ResolvedColumn right = columns.resolve(join.rightColumn());
        requireDifferentTables(join, left, right);
        requireSameType(join, left, right);
    }

    private static void requireDifferentTables(JoinPredicate join, ColumnResolver.ResolvedColumn left,
            ColumnResolver.ResolvedColumn right) {
        if (!left.tableName().equals(right.tableName())) {
            return;
        }
        throw new IllegalArgumentException(
                "join columns %s and %s are both on table %s"
                        .formatted(join.leftColumn(), join.rightColumn(), left.tableName()));
    }

    private static void requireSameType(JoinPredicate join, ColumnResolver.ResolvedColumn left,
            ColumnResolver.ResolvedColumn right) {
        if (left.column().type() == right.column().type()) {
            return;
        }
        throw new IllegalArgumentException(
                "join columns have different types: %s.%s is %s, %s.%s is %s"
                        .formatted(left.tableName(), join.leftColumn(), left.column().type(),
                                right.tableName(), join.rightColumn(), right.column().type()));
    }

    // Whether the table already exists is execution's concern: it checks and writes in one step.
    private static void bindCreateTable(CreateTableStatement create) {
        if (create.columns().isEmpty()) {
            throw new IllegalArgumentException("a table needs at least one column: " + create.tableName());
        }
        long distinctNames = create.columns().stream().map(ColumnSpec::name).distinct().count();
        if (distinctNames != create.columns().size()) {
            throw new IllegalArgumentException("duplicate column names in schema for table: " + create.tableName());
        }
    }
}
