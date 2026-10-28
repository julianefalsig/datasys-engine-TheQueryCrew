package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrunerTest {

    // One answer per partition, in list order, and true exactly where canPrune says false.
    @Test
    void mayMatchMarksThePartitionsWhoseRangeAllowsAMatch() {
        List<CatalogData.Partition> partitions = List.of(
                distanceRange(12L, 31L),
                distanceRange(88L, 95L),
                distanceRange(140L, 187L),
                distanceRange(210L, 299L));

        boolean[] mayMatch = Pruner.mayMatch(partitions, "distance", Comparison.GREATER_THAN, 150L, ColumnType.LONG);

        assertArrayEquals(new boolean[] {false, false, true, true}, mayMatch);
    }

    private static CatalogData.Partition distanceRange(long min, long max) {
        return new CatalogData.Partition("unused.bin", 2, Map.of("distance", new CatalogData.Range(min, max)));
    }

    @Test
    void equalsPrunesLongOutsideRange() {
        assertTrue(Pruner.canPrune(Comparison.EQUALS, 5L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void equalsKeepsLongInsideRange() {
        assertFalse(Pruner.canPrune(Comparison.EQUALS, 15L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void equalsPrunesDoubleOutsideRange() {
        assertTrue(Pruner.canPrune(Comparison.EQUALS, 5.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void equalsKeepsDoubleInsideRange() {
        assertFalse(Pruner.canPrune(Comparison.EQUALS, 15.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void equalsPrunesStringOutsideRange() {
        assertTrue(Pruner.canPrune(Comparison.EQUALS, "Odense", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void equalsKeepsStringInsideRange() {
        assertFalse(Pruner.canPrune(Comparison.EQUALS, "Aarhus", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void lessThanPrunesLongWhenMinIsAtLeastTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.LESS_THAN, 10L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void lessThanKeepsLongWhenMinIsBelowTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.LESS_THAN, 15L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void lessThanPrunesDoubleWhenMinIsAtLeastTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.LESS_THAN, 10.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void lessThanKeepsDoubleWhenMinIsBelowTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.LESS_THAN, 15.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void lessThanPrunesStringWhenMinIsAtLeastTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.LESS_THAN, "Aalborg", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void lessThanKeepsStringWhenMinIsBelowTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.LESS_THAN, "Copenhagen", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void greaterThanPrunesLongWhenMaxIsAtMostTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.GREATER_THAN, 20L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void greaterThanKeepsLongWhenMaxIsAboveTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.GREATER_THAN, 15L, 10L, 20L, ColumnType.LONG));
    }

    @Test
    void greaterThanPrunesDoubleWhenMaxIsAtMostTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.GREATER_THAN, 20.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void greaterThanKeepsDoubleWhenMaxIsAboveTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.GREATER_THAN, 15.0, 10.0, 20.0, ColumnType.DOUBLE));
    }

    @Test
    void greaterThanPrunesStringWhenMaxIsAtMostTheConstant() {
        assertTrue(Pruner.canPrune(Comparison.GREATER_THAN, "Copenhagen", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void greaterThanKeepsStringWhenMaxIsAboveTheConstant() {
        assertFalse(Pruner.canPrune(Comparison.GREATER_THAN, "Aarhus", "Aalborg", "Copenhagen", ColumnType.STRING));
    }
}
