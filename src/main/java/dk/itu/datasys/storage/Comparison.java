package dk.itu.datasys.storage;

import java.util.Comparator;

public enum Comparison {
    EQUALS, LESS_THAN, GREATER_THAN;

    // Whether a column value satisfies this comparison against a constant. Numeric for LONG and
    // DOUBLE, lexicographic for STRING, exactly as in Exercise 2.
    public boolean matches(Object value, Object constant, ColumnType type) {
        int c = ColumnStats.comparatorFor(type).compare(value, constant);
        return switch (this) {
            case EQUALS -> c == 0;
            case LESS_THAN -> c < 0;
            case GREATER_THAN -> c > 0;
        };
    }
}
