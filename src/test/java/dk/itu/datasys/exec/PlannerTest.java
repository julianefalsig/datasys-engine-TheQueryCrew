package dk.itu.datasys.exec;

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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    // ---- Helpers --------------------------------------------------------------------------

    private static SelectStatement select(Predicate where) {
        return new SelectStatement("trips", Optional.ofNullable(where));
    }

    private static void assertRows(Object[][] expected, List<Object[]> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i));
        }
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
