package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;

import java.util.List;

public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {
}
