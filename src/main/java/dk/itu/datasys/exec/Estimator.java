package dk.itu.datasys.exec;

import dk.itu.datasys.storage.CatalogData;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.Pruner;

import java.util.List;

/**
 * Row-count estimates from catalog numbers alone, as in docs/optimizer-design.md. Nothing here reads
 * a data file or the catalog, so every formula is unit-testable with hand-made statistics.
 * A missing NDV ({@code null}, from a partition copied before NDV existed) counts as the row count:
 * every value is assumed distinct.
 */
public final class Estimator {

    /** Selinger et al. (1979): the selectivity of a range predicate when nothing better is known. */
    static final double DEFAULT_RANGE_SELECTIVITY = 1.0 / 3.0;

    private Estimator() {
    }

    /** Base table: the rows of the partitions that survive pruning. */
    public static double baseRows(List<CatalogData.Partition> survivingPartitions) {
        long rows = 0;
        for (CatalogData.Partition partition : survivingPartitions) {
            rows += partition.rowCount();
        }
        return rows;
    }

    /**
     * Filter: the rows of {@code rows} that survive {@code column comparison constant}, given the
     * column's min, max and NDV over the same partitions. A constant outside [min, max] keeps nothing.
     */
    public static double filterRows(double rows, Comparison comparison, Object constant,
                                    Object min, Object max, Long ndv, ColumnType type) {
        if (rows == 0 || Pruner.canPrune(comparison, constant, min, max, type)) {
            return 0;
        }
        return switch (comparison) {
            // |R| / ndv(col): uniformity, every distinct value occurs equally often.
            case EQUALS -> rows / ndvOrRows(ndv, rows);
            case LESS_THAN, GREATER_THAN -> rows * rangeSelectivity(comparison, constant, min, max, type);
        };
    }

    /**
     * Equi-join: |R| · |S| / max(ndv(R.a), ndv(S.b)), from lecture 10. For a later join in a chain,
     * the caller passes as the intermediate result's NDV the NDV of the base table the column came from.
     */
    public static double joinRows(double leftRows, double rightRows, Long leftNdv, Long rightNdv) {
        if (leftRows == 0 || rightRows == 0) {
            return 0;
        }
        double ndv = Math.max(ndvOrRows(leftNdv, leftRows), ndvOrRows(rightNdv, rightRows));
        return leftRows * rightRows / ndv;
    }

    // Called only when pruning left some rows, so the constant is not wholly outside [min, max].
    private static double rangeSelectivity(Comparison comparison, Object constant,
                                           Object min, Object max, ColumnType type) {
        // Every row is on the matching side: col < x with max < x, or col > x with min > x.
        Object bound = comparison == Comparison.LESS_THAN ? max : min;
        if (comparison.matches(bound, constant, type)) {
            return 1.0;
        }
        if (type == ColumnType.STRING) {
            // Strings have no distance between them to interpolate over.
            return DEFAULT_RANGE_SELECTIVITY;
        }
        double x = ((Number) constant).doubleValue();
        double low = ((Number) min).doubleValue();
        double high = ((Number) max).doubleValue();
        // high > low here: with high == low, the checks above already returned 0 or 1.
        double fraction = comparison == Comparison.LESS_THAN
                ? (x - low) / (high - low)
                : (high - x) / (high - low);
        return Math.min(1.0, Math.max(0.0, fraction));
    }

    private static double ndvOrRows(Long ndv, double rows) {
        return Math.max(1.0, ndv == null ? rows : ndv);
    }
}
