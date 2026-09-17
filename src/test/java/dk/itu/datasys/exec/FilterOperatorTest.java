package dk.itu.datasys.exec;

import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterOperatorTest {

    // city, distance, price — the golden schema, so the column indices read the same as elsewhere.
    private static final List<Object[]> TRIPS = List.of(
            new Object[]{"Copenhagen", 12L, 23.5},
            new Object[]{"Aarhus", 187L, 301.0},
            new Object[]{"Odense", 95L, 120.75},
            new Object[]{"Copenhagen", 140L, 210.0});

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        operator.close();
        return rows;
    }

    @Test
    void keepsOnlyTheRowsThatPass() {
        Operator filter = new FilterOperator(new TestListOperator(TRIPS),
                new RowPredicate(1, Comparison.GREATER_THAN, 100L, ColumnType.LONG));

        List<Object[]> rows = drain(filter);

        assertEquals(2, rows.size());
        assertEquals("Aarhus", rows.get(0)[0]);
        assertEquals("Copenhagen", rows.get(1)[0]);
    }

    // Rows come out whole, in child order: the filter tests one column but forwards the whole row.
    @Test
    void passesRowsThroughUnchanged() {
        Operator filter = new FilterOperator(new TestListOperator(TRIPS),
                new RowPredicate(0, Comparison.EQUALS, "Odense", ColumnType.STRING));

        List<Object[]> rows = drain(filter);

        assertEquals(1, rows.size());
        assertEquals("Odense", rows.get(0)[0]);
        assertEquals(95L, rows.get(0)[1]);
        assertEquals(120.75, rows.get(0)[2]);
    }

    // A predicate nothing satisfies still drains cleanly rather than blocking or throwing.
    @Test
    void emitsNothingWhenNoRowPasses() {
        Operator filter = new FilterOperator(new TestListOperator(TRIPS),
                new RowPredicate(0, Comparison.EQUALS, "Paris", ColumnType.STRING));

        assertEquals(List.of(), drain(filter));
    }

    // STRING comparisons are lexicographic, as in Exercise 2: everything before "Copenhagen".
    @Test
    void comparesStringsLexicographically() {
        Operator filter = new FilterOperator(new TestListOperator(TRIPS),
                new RowPredicate(0, Comparison.LESS_THAN, "Copenhagen", ColumnType.STRING));

        List<Object[]> rows = drain(filter);

        assertEquals(1, rows.size());
        assertEquals("Aarhus", rows.get(0)[0]);
    }

    // open() and close() have to reach the child, or a scan would never open its files.
    @Test
    void passesOpenAndCloseDownToTheChild() {
        TestListOperator child = new TestListOperator(TRIPS);
        Operator filter = new FilterOperator(child,
                new RowPredicate(1, Comparison.GREATER_THAN, 0L, ColumnType.LONG));

        drain(filter);

        assertTrue(child.wasOpened());
        assertTrue(child.wasClosed());
    }

    // Once exhausted the filter keeps saying null instead of falling over.
    @Test
    void staysExhaustedAfterTheLastRow() {
        Operator filter = new FilterOperator(new TestListOperator(TRIPS),
                new RowPredicate(1, Comparison.GREATER_THAN, 1000L, ColumnType.LONG));

        filter.open();

        assertNull(filter.next());
        assertNull(filter.next());
    }
}
