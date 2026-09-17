package dk.itu.datasys.storage;

/**
 * A WHERE clause as the pipeline sees it: a column position rather than a name, because the rows
 * flowing through the operators are plain arrays. Resolving the name is the planner's job.
 */
public record RowPredicate(int columnIndex, Comparison comparison, Object constant, ColumnType columnType) {

    public boolean test(Object[] row) {
        return comparison.matches(row[columnIndex], constant, columnType);
    }
}
