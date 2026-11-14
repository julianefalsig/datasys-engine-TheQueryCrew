package dk.itu.datasys.exec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashJoinOperatorTest {

    // c_name, c_city: the build side, keyed on c_name (index 0).
    private static final List<Object[]> CUSTOMERS = List.of(
            new Object[]{"Anna", "Copenhagen"},
            new Object[]{"Bob", "Aarhus"},
            new Object[]{"Carol", "Copenhagen"});

    // o_orderkey, o_custname: the probe side, keyed on o_custname (index 1).
    private static final List<Object[]> ORDERS = List.of(
            new Object[]{100L, "Anna"},
            new Object[]{101L, "Bob"},
            new Object[]{102L, "Carol"},
            new Object[]{103L, "Anna"});

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

    private static Operator join(List<Object[]> build, List<Object[]> probe, int buildKey, int probeKey) {
        return new HashJoinOperator(new TestListOperator(build), new TestListOperator(probe), buildKey, probeKey);
    }

    // Every order finds its customer, so there is one output row per order, in probe order.
    @Test
    void joinsEachProbeRowWithItsBuildRow() {
        List<Object[]> rows = drain(join(CUSTOMERS, ORDERS, 0, 1));

        assertEquals(4, rows.size());
        assertArrayEquals(new Object[]{"Anna", "Copenhagen", 100L, "Anna"}, rows.get(0));
        assertArrayEquals(new Object[]{"Bob", "Aarhus", 101L, "Bob"}, rows.get(1));
        assertArrayEquals(new Object[]{"Carol", "Copenhagen", 102L, "Carol"}, rows.get(2));
        assertArrayEquals(new Object[]{"Anna", "Copenhagen", 103L, "Anna"}, rows.get(3));
    }

    // 2 build rows and 3 probe rows share one key: 2 × 3 = 6 rows, which only works if the
    // cursor into the match list survives between next() calls.
    @Test
    void duplicatesOnBothSidesMultiply() {
        List<Object[]> build = List.of(new Object[]{"k", "b1"}, new Object[]{"k", "b2"});
        List<Object[]> probe = List.of(new Object[]{"k", "p1"}, new Object[]{"k", "p2"}, new Object[]{"k", "p3"});

        List<Object[]> rows = drain(join(build, probe, 0, 0));

        assertEquals(6, rows.size());
        assertArrayEquals(new Object[]{"k", "b1", "k", "p1"}, rows.get(0));
        assertArrayEquals(new Object[]{"k", "b2", "k", "p1"}, rows.get(1));
        assertArrayEquals(new Object[]{"k", "b1", "k", "p2"}, rows.get(2));
        assertArrayEquals(new Object[]{"k", "b2", "k", "p3"}, rows.get(5));
    }

    // Dave has no customer row and Bob has no order: both are dropped, as in an inner join.
    @Test
    void dropsRowsWithoutAPartner() {
        List<Object[]> build = List.of(new Object[]{"Anna", "Copenhagen"}, new Object[]{"Bob", "Aarhus"});
        List<Object[]> probe = List.of(new Object[]{100L, "Anna"}, new Object[]{104L, "Dave"});

        List<Object[]> rows = drain(join(build, probe, 0, 1));

        assertEquals(1, rows.size());
        assertArrayEquals(new Object[]{"Anna", "Copenhagen", 100L, "Anna"}, rows.get(0));
    }

    // An empty hash table matches nothing, however many rows the probe side has.
    @Test
    void anEmptyBuildSideEmitsNothing() {
        assertEquals(List.of(), drain(join(List.of(), ORDERS, 0, 1)));
    }

    @Test
    void anEmptyProbeSideEmitsNothing() {
        assertEquals(List.of(), drain(join(CUSTOMERS, List.of(), 0, 1)));
    }

    // A LONG key matches a LONG key: Java equals on the boxed values, not identity.
    @Test
    void matchesLongKeysByValue() {
        // A single array would be read as varargs, so the element type is spelled out.
        List<Object[]> build = List.<Object[]>of(new Object[]{1000L, "a"});
        List<Object[]> probe = List.<Object[]>of(new Object[]{Long.valueOf(1000L), "b"});

        List<Object[]> rows = drain(join(build, probe, 0, 0));

        assertEquals(1, rows.size());
    }

    // open() and close() have to reach both children, or a scan would never open its files.
    @Test
    void passesOpenAndCloseDownToBothChildren() {
        TestListOperator build = new TestListOperator(CUSTOMERS);
        TestListOperator probe = new TestListOperator(ORDERS);

        drain(new HashJoinOperator(build, probe, 0, 1));

        assertTrue(build.wasOpened());
        assertTrue(build.wasClosed());
        assertTrue(probe.wasOpened());
        assertTrue(probe.wasClosed());
    }

    // Once exhausted the join keeps saying null instead of falling over.
    @Test
    void staysExhaustedAfterTheLastRow() {
        Operator join = join(CUSTOMERS, List.<Object[]>of(new Object[]{100L, "Anna"}), 0, 1);
        join.open();

        assertArrayEquals(new Object[]{"Anna", "Copenhagen", 100L, "Anna"}, join.next());
        assertNull(join.next());
        assertNull(join.next());
    }
}
