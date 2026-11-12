package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.StorageEngine;

import java.util.List;
import java.util.Optional;

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
            case DeleteStatement delete -> bindWhere(delete.tableName(), delete.where());
        }
    }
    // The lookup is the check: it throws if the table is unknown. The CSV file is execution's concern.
    private void bindCopy(CopyStatement copy) {
        engine.schema(copy.tableName());
    }

    // A single table, with each filter checked the same way DELETE checks its WHERE.
    // Joins and table lists are resolved in the next part of the exercise.
    private void bindSelect(SelectStatement select) {
        if (select.tableNames().size() != 1) {
            throw new IllegalArgumentException(
                    "SELECT from more than one table is not supported yet: " + select.tableNames());
        }
        if (!select.joins().isEmpty()) {
            throw new IllegalArgumentException("join predicates are not supported yet");
        }
        String tableName = select.tableNames().getFirst();
        if (select.filters().isEmpty()) {
            bindWhere(tableName, Optional.empty());
            return;
        }
        for (Predicate filter : select.filters()) {
            bindWhere(tableName, Optional.of(filter));
        }
    }

    // SELECT and DELETE check the same things: the table exists, and a WHERE fits its schema.
    private void bindWhere(String tableName, Optional<Predicate> where) {
        List<ColumnSpec> schema = engine.schema(tableName);
        // Without a WHERE there is nothing to check beyond the table itself.
        if (where.isEmpty()) {
            return;
        }
        Predicate predicate = where.get();
        ColumnSpec column = schema.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown column: " + predicate.columnName() + " on table " + tableName));
        if (!column.type().accepts(predicate.constant())) {
            throw new IllegalArgumentException(
                    "constant type %s does not match column %s of type %s"
                            .formatted(predicate.constant().getClass().getSimpleName(), column.name(), column.type()));
        }
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
