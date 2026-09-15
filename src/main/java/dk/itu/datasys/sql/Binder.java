package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.StorageEngine;

import java.util.List;

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
        }
    }
    // The lookup is the check: it throws if the table is unknown. The CSV file is execution's concern.
    private void bindCopy(CopyStatement copy) {
        engine.schema(copy.tableName());
    }

    private void bindSelect(SelectStatement select) {
        List<ColumnSpec> schema = engine.schema(select.tableName());
        // Without a WHERE there is nothing to check beyond the table itself.
        if (select.where().isEmpty()) {
            return;
        }
        Predicate predicate = select.where().get();
        ColumnSpec column = schema.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown column: " + predicate.columnName() + " on table " + select.tableName()));
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
