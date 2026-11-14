package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Unqualified column names against the schemas of a FROM list.
 * The row index is the column's position when those schemas are concatenated in FROM-list order.
 */
public final class ColumnResolver {

    public record ResolvedColumn(String tableName, int rowIndex, ColumnSpec column) {
    }

    private final List<String> tableNames;
    private final List<List<ColumnSpec>> schemas;

    public ColumnResolver(List<String> tableNames, List<List<ColumnSpec>> schemas) {
        if (tableNames.size() != schemas.size()) {
            throw new IllegalArgumentException(
                    "FROM list has " + tableNames.size() + " tables but " + schemas.size() + " schemas");
        }
        this.tableNames = List.copyOf(tableNames);
        this.schemas = schemas.stream().map(List::copyOf).toList();
    }

    public ResolvedColumn resolve(String columnName) {
        ResolvedColumn found = null;
        List<String> foundIn = new ArrayList<>();
        int offset = 0;
        for (int table = 0; table < schemas.size(); table++) {
            List<ColumnSpec> schema = schemas.get(table);
            for (int column = 0; column < schema.size(); column++) {
                if (!schema.get(column).name().equals(columnName)) {
                    continue;
                }
                foundIn.add(tableNames.get(table));
                if (found == null) {
                    found = new ResolvedColumn(tableNames.get(table), offset + column, schema.get(column));
                }
            }
            offset += schema.size();
        }
        if (foundIn.size() > 1) {
            throw new IllegalArgumentException("ambiguous column: " + columnName + " in tables " + foundIn);
        }
        if (found == null) {
            throw new IllegalArgumentException("unknown column: " + columnName + " in tables " + tableNames);
        }
        return found;
    }
}
