package dk.itu.datasys.exec;

import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.ScanStats;
import dk.itu.datasys.storage.StorageEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden data is sorted by distance and loaded with maxRowsPerPartition = 2, so the catalog
 * holds four partitions with distance ranges 12–31, 88–95, 140–187 and 210–299. Every pruning
 * assertion below is written against those four windows.
 */
class PlannerTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // ---- Planner shapes -------------------------------------------------------------------

    @Test
    void whereBuildsFilterOverScan(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("distance", Comparison.GREATER_THAN, 200L)));

        assertInstanceOf(FilterOperator.class, plan.root());
    }

    @Test
    void noWhereBuildsBareScanOverAllPartitions(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(null));

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new ScanStats(4, 4, 0), plan.stats());
        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Roskilde", 31L, 45.0},
                {"Copenhagen", 88L, 99.99},
                {"Odense", 95L, 120.75},
                {"Copenhagen", 140L, 210.0},
                {"Aarhus", 187L, 301.0},
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, plan.drain());
    }

    // ---- Pruning --------------------------------------------------------------------------

    // distance > 200 can only be satisfied by the last window, 210–299.
    @Test
    void pruningKeepsOnlyPartitionsThatCanMatch(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("distance", Comparison.GREATER_THAN, 200L)));

        assertEquals(new ScanStats(4, 1, 3), plan.stats());
        assertRows(new Object[][] {
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, plan.drain());
    }

    // An equality lands inside one window only: 95 sits in 88–95, nowhere else.
    @Test
    void equalityKeepsTheSinglePartitionWhoseRangeCoversTheConstant(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("distance", Comparison.EQUALS, 95L)));

        assertEquals(new ScanStats(4, 1, 3), plan.stats());
        assertRows(new Object[][] {
                {"Odense", 95L, 120.75}
        }, plan.drain());
    }

    // A prefix of the table: 12–31 and 88–95 can match distance < 100, the two later windows cannot.
    @Test
    void pruningKeepsEveryPartitionThatOverlapsTheRange(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("distance", Comparison.LESS_THAN, 100L)));

        assertEquals(new ScanStats(4, 2, 2), plan.stats());
        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Roskilde", 31L, 45.0},
                {"Copenhagen", 88L, 99.99},
                {"Odense", 95L, 120.75}
        }, plan.drain());
    }

    // Nothing can match: the scan is handed an empty list and the query reads no data file at all.
    @Test
    void aPredicateNoPartitionCanMatchPrunesEverything(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("distance", Comparison.GREATER_THAN, 1_000L)));

        assertEquals(new ScanStats(4, 0, 4), plan.stats());
        assertEquals(List.of(), plan.drain());
    }

    // Pruning is a min/max decision, so it works on any column, not just the sort column.
    @Test
    void prunesOnANonSortedColumnToo(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithSortedTrips(dataDir));

        Plan plan = planner.plan(select(new Predicate("city", Comparison.EQUALS, "Aarhus")));

        // City ranges are Copenhagen–Roskilde, Copenhagen–Odense, Aarhus–Copenhagen and
        // Aalborg–Esbjerg: only the last two can hold "Aarhus". Filter then drops Aalborg/Esbjerg.
        assertEquals(new ScanStats(4, 2, 2), plan.stats());
        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0}
        }, plan.drain());
    }

    // ---- Joins ----------------------------------------------------------------------------

    // orders | customers | products | cities, filters on the first two scans, then three hash joins.
    @Test
    void aFourTableQueryIsLeftDeepWithFiltersBelowTheJoins(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithJoinTables(dataDir));

        Plan plan = planner.plan(new SelectStatement(
                List.of("orders", "customers", "products", "cities"),
                List.of(new Predicate("o_quantity", Comparison.EQUALS, 1L),
                        new Predicate("c_city", Comparison.EQUALS, "Copenhagen")),
                List.of(new JoinPredicate("o_custname", "c_name"),
                        new JoinPredicate("o_prodname", "p_name"),
                        new JoinPredicate("c_city", "ct_city"))));

        HashJoinOperator cities = assertInstanceOf(HashJoinOperator.class, plan.root());
        HashJoinOperator products = assertInstanceOf(HashJoinOperator.class, cities.buildSide());
        HashJoinOperator customers = assertInstanceOf(HashJoinOperator.class, products.buildSide());
        assertEquals(1, customers.buildKey());
        assertEquals(0, customers.probeKey());
        assertEquals(2, products.buildKey());
        assertEquals(0, products.probeKey());
        assertEquals(5, cities.buildKey());
        assertEquals(0, cities.probeKey());

        FilterOperator ordersFilter = assertInstanceOf(FilterOperator.class, customers.buildSide());
        assertInstanceOf(ScanOperator.class, ordersFilter.child());
        FilterOperator customersFilter = assertInstanceOf(FilterOperator.class, customers.probeSide());
        assertInstanceOf(ScanOperator.class, customersFilter.child());
        assertInstanceOf(ScanOperator.class, products.probeSide());
        assertInstanceOf(ScanOperator.class, cities.probeSide());

        // Quantity 1 misses the 5–9 partition; Copenhagen misses the Aarhus–Aarhus partition.
        assertEquals(List.of(
                new ScanStats(2, 1, 1),
                new ScanStats(2, 1, 1),
                new ScanStats(1, 1, 0),
                new ScanStats(1, 1, 0)), plan.tableStats());
        assertEquals(plan.tableStats().getFirst(), plan.stats());
        assertEquals(List.of(1, 1, 1, 1), plan.versions());
        assertRows(new Object[][] {
                {100L, "Anna", "Bike", 1L, "Anna", "Copenhagen", "Bike", 2999.0, "Copenhagen", "Denmark", 1400000L}
        }, plan.drain());
    }

    // The probe column may be written on the left of the predicate; the build side is still orders.
    @Test
    void theJoinKeyFollowsTheTableNotThePredicateOrder(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithJoinTables(dataDir));

        Plan plan = planner.plan(new SelectStatement(
                List.of("orders", "customers"),
                List.of(),
                List.of(new JoinPredicate("c_name", "o_custname"))));

        HashJoinOperator join = assertInstanceOf(HashJoinOperator.class, plan.root());
        assertInstanceOf(ScanOperator.class, join.buildSide());
        assertInstanceOf(ScanOperator.class, join.probeSide());
        assertEquals(1, join.buildKey());
        assertEquals(0, join.probeKey());
    }

    @Test
    void anUnconnectedTableIsRejected(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithJoinTables(dataDir));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> planner.plan(
                new SelectStatement(List.of("orders", "products", "customers"), List.of(),
                        List.of(new JoinPredicate("o_custname", "c_name")))));

        assertTrue(error.getMessage().contains("products"), error.getMessage());
        assertTrue(error.getMessage().contains("reorder"), error.getMessage());
    }

    @Test
    void aSecondConnectingPredicateIsRejected(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(engineWithJoinTables(dataDir));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> planner.plan(
                new SelectStatement(List.of("orders", "customers"), List.of(),
                        List.of(new JoinPredicate("o_custname", "c_name"),
                                new JoinPredicate("o_prodname", "c_city")))));

        assertTrue(error.getMessage().contains("one equi-join predicate"), error.getMessage());
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static SelectStatement select(Predicate where) {
        List<Predicate> filters = where == null ? List.of() : List.of(where);
        return new SelectStatement(List.of("trips"), filters, List.of());
    }

    private static void assertRows(Object[][] expected, List<Object[]> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i));
        }
    }

    private static StorageEngine engineWithJoinTables(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
        engine.copyFile("orders", writeCsv(dataDir, "orders.csv", """
                100,Anna,Bike,1
                101,Bob,Helmet,1
                102,Carol,Bike,5
                103,Anna,Helmet,9
                """).toString());
        engine.copyFile("customers", writeCsv(dataDir, "customers.csv", """
                Anna,Copenhagen
                Eve,Copenhagen
                Bob,Aarhus
                Dave,Aarhus
                """).toString());
        engine.copyFile("products", writeCsv(dataDir, "products.csv", """
                Bike,2999.0
                Helmet,299.0
                """).toString());
        engine.copyFile("cities", writeCsv(dataDir, "cities.csv", """
                Copenhagen,Denmark,1400000
                Aarhus,Denmark,290000
                """).toString());
        return engine;
    }

    private static Path writeCsv(Path dataDir, String name, String csv) throws IOException {
        Path dest = dataDir.resolve(name);
        Files.writeString(dest, csv);
        return dest;
    }

    private static StorageEngine engineWithSortedTrips(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, "trips_sorted.csv").toString());
        return engine;
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = PlannerTest.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }
}
