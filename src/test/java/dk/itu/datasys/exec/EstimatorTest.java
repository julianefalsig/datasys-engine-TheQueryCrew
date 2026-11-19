package dk.itu.datasys.exec;

import dk.itu.datasys.storage.CatalogData;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hand-made statistics, mostly the week 10 testbed's, with every expected number worked out by hand. */
class EstimatorTest {

    private static final double DELTA = 1e-9;

    // ---- Base table ------------------------------------------------------------------------

    @Test
    void baseRowsSumsTheSurvivingPartitions() {
        assertEquals(7.0, Estimator.baseRows(List.of(partition(4), partition(3))), DELTA);
    }

    @Test
    void aFullyPrunedTableHasNoRows() {
        assertEquals(0.0, Estimator.baseRows(List.of()), DELTA);
    }

    // ---- Equality filter -------------------------------------------------------------------

    // c_city = 'Copenhagen': 3 customers over 2 cities, 3 / 2.
    @Test
    void equalityDividesByNdv() {
        assertEquals(1.5, Estimator.filterRows(3, Comparison.EQUALS, "Copenhagen",
                "Aarhus", "Copenhagen", 2L, ColumnType.STRING), DELTA);
    }

    @Test
    void equalityOutsideMinMaxKeepsNothing() {
        assertEquals(0.0, Estimator.filterRows(3, Comparison.EQUALS, "Odense",
                "Aarhus", "Copenhagen", 2L, ColumnType.STRING), DELTA);
    }

    // Copied before NDV existed: every value is assumed distinct, so one row matches.
    @Test
    void aMissingNdvCountsAsTheRowCount() {
        assertEquals(1.0, Estimator.filterRows(3, Comparison.EQUALS, "Copenhagen",
                "Aarhus", "Copenhagen", null, ColumnType.STRING), DELTA);
    }

    // ---- Range filter ----------------------------------------------------------------------

    // o_orderkey in [100, 103]: < 102 covers 2 of the 3 units, > 101 covers 2 of 3.
    @Test
    void aLongRangeInterpolatesBetweenMinAndMax() {
        assertEquals(4.0 * 2 / 3, Estimator.filterRows(4, Comparison.LESS_THAN, 102L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
        assertEquals(4.0 * 2 / 3, Estimator.filterRows(4, Comparison.GREATER_THAN, 101L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
    }

    // p_price in [299.0, 2999.0]: > 1000.0 covers 1999 of 2700.
    @Test
    void aDoubleRangeInterpolatesBetweenMinAndMax() {
        assertEquals(2.0 * 1999 / 2700, Estimator.filterRows(2, Comparison.GREATER_THAN, 1000.0,
                299.0, 2999.0, 2L, ColumnType.DOUBLE), DELTA);
    }

    @Test
    void aRangeBeyondMinOrMaxKeepsNothingOrEverything() {
        assertEquals(0.0, Estimator.filterRows(4, Comparison.LESS_THAN, 100L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
        assertEquals(4.0, Estimator.filterRows(4, Comparison.LESS_THAN, 200L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
        assertEquals(0.0, Estimator.filterRows(4, Comparison.GREATER_THAN, 103L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
        assertEquals(4.0, Estimator.filterRows(4, Comparison.GREATER_THAN, 50L,
                100L, 103L, 4L, ColumnType.LONG), DELTA);
    }

    // min = max leaves no interval to interpolate over: every row is on one side or the other.
    @Test
    void aSingleValueColumnKeepsAllOrNothing() {
        assertEquals(4.0, Estimator.filterRows(4, Comparison.LESS_THAN, 5L,
                1L, 1L, 1L, ColumnType.LONG), DELTA);
        assertEquals(0.0, Estimator.filterRows(4, Comparison.GREATER_THAN, 5L,
                1L, 1L, 1L, ColumnType.LONG), DELTA);
    }

    // c_name in ['Anna', 'Carol']: strings cannot be interpolated, so Selinger's 1/3 of 3 rows.
    @Test
    void aStringRangeUsesSelingersConstant() {
        assertEquals(1.0, Estimator.filterRows(3, Comparison.GREATER_THAN, "B",
                "Anna", "Carol", 3L, ColumnType.STRING), DELTA);
    }

    @Test
    void aStringRangeCoveringEveryValueKeepsEverything() {
        assertEquals(3.0, Estimator.filterRows(3, Comparison.GREATER_THAN, "A",
                "Anna", "Carol", 3L, ColumnType.STRING), DELTA);
    }

    // ---- Equi-join -------------------------------------------------------------------------

    // orders ⋈ customers on o_custname = c_name: 4 · 3 / max(3, 3), one row per order.
    @Test
    void aJoinDividesByTheLargerNdv() {
        assertEquals(4.0, Estimator.joinRows(4, 3, 3L, 3L), DELTA);
        assertEquals(4.0 * 2 / 3, Estimator.joinRows(4, 2, 3L, 2L), DELTA);
    }

    // The 4-row intermediate orders ⋈ customers joins cities on c_city = ct_city. The intermediate's
    // NDV of c_city is that of customers, the base table it came from: 4 · 2 / max(2, 2).
    @Test
    void aLaterJoinUsesTheBaseTableNdvOfTheIntermediateColumn() {
        double ordersCustomers = Estimator.joinRows(4, 3, 3L, 3L);
        assertEquals(4.0, Estimator.joinRows(ordersCustomers, 2, 2L, 2L), DELTA);
    }

    // A missing NDV on the 4-row side counts as 4: 4 · 3 / max(4, 3).
    @Test
    void aJoinWithAMissingNdvUsesTheRowCount() {
        assertEquals(3.0, Estimator.joinRows(4, 3, null, 3L), DELTA);
    }

    @Test
    void aJoinWithAnEmptySideIsEmpty() {
        assertEquals(0.0, Estimator.joinRows(0, 3, 0L, 3L), DELTA);
        assertEquals(0.0, Estimator.joinRows(4, 0, 3L, 0L), DELTA);
    }

    private static CatalogData.Partition partition(int rows) {
        return new CatalogData.Partition("unused.bin", rows, Map.of());
    }
}
