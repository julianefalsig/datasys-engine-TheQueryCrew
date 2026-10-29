package dk.itu.datasys.storage;

/**
 * A WHERE clause as a row test: a column position rather than a name, because rows are plain
 * arrays. The filter operator tests SELECT rows with it, and DELETE tests the rows it rewrites.
 */
public record RowPredicate(int columnIndex, Comparison comparison, Object constant, ColumnType columnType) {

    public boolean test(Object[] row) {
        return comparison.matches(row[columnIndex], constant, columnType);
    }
}
