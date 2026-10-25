package dk.itu.datasys.storage;

import dk.itu.datasys.exec.Executor;
import dk.itu.datasys.exec.Planner;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end sanity check against the exercise's golden dataset, exercising createTable,
 * copyFile, SQL SELECT, pruning, and restart together.
 */
class StorageEngineSmokeTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void goldenQueriesAndPruning(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2); // tiny partitions to force pruning
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        List<Object[]> distanceOver100 = query(engine, "SELECT * FROM trips WHERE distance > 100;");
        assertEquals(4, distanceOver100.size());
        var distancePlan = new Planner(engine).plan(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L))));
        assertEquals(distancePlan.stats().partitionsTotal(),
                distancePlan.stats().partitionsRead() + distancePlan.stats().partitionsPruned());

        // trips.csv isn't sorted by distance, so every 2-row partition mixes low/high distances and
        // none can be pruned on that column; city is where partitioning happens to produce a prunable range.
        var cityPlan = new Planner(engine).plan(new SelectStatement("trips",
                Optional.of(new Predicate("city", Comparison.EQUALS, "Aalborg"))));
        assertTrue(cityPlan.stats().partitionsPruned() > 0);

        assertEquals(3, query(engine, "SELECT * FROM trips WHERE city = 'Copenhagen';").size());
        assertEquals(2, query(engine, "SELECT * FROM trips WHERE price < 50.0;").size());
    }

    @Test
    void restartSeesPersistedData(@TempDir Path dataDir) {
        StorageEngine first = new StorageEngine(dataDir, 2);
        first.createTable("trips", TRIPS_SCHEMA);
        first.copyFile("trips", "src/test/resources/trips.csv");

        StorageEngine restarted = new StorageEngine(dataDir);
        assertEquals(8, query(restarted, "SELECT * FROM trips;").size());
    }

    @Test
    void secondCopyAppendsAndIncrementsVersion(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        assertEquals(0, engine.createTable("trips", TRIPS_SCHEMA));
        assertEquals(0, engine.catalog("trips").version);

        assertEquals(1, engine.copyFile("trips", "src/test/resources/trips.csv"));
        assertEquals(1, engine.catalog("trips").version);
        assertRowsOnce(query(engine, "SELECT * FROM trips;"));

        assertEquals(2, engine.copyFile("trips", "src/test/resources/trips.csv"));
        assertEquals(2, engine.catalog("trips").version);
        assertEquals("partition-0.bin", engine.catalog("trips").partitions.get(0).dataFile());
        assertEquals("partition-4.bin", engine.catalog("trips").partitions.get(4).dataFile());
        assertRowsTwice(query(engine, "SELECT * FROM trips;"));

        StorageEngine restarted = new StorageEngine(dataDir);
        assertEquals(2, restarted.catalog("trips").version);
        assertRowsTwice(query(restarted, "SELECT * FROM trips;"));
    }

    private static final Object[][] TRIPS = {
            {"Copenhagen", 12L, 23.5},
            {"Aarhus", 187L, 301.0},
            {"Odense", 95L, 120.75},
            {"Copenhagen", 140L, 210.0},
            {"Aalborg", 210L, 340.5},
            {"Roskilde", 31L, 45.0},
            {"Copenhagen", 88L, 99.99},
            {"Esbjerg", 299L, 450.25}
    };

    private static void assertRowsOnce(List<Object[]> rows) {
        assertEquals(TRIPS.length, rows.size());
        for (int i = 0; i < TRIPS.length; i++) {
            assertArrayEquals(TRIPS[i], rows.get(i));
        }
    }

    private static void assertRowsTwice(List<Object[]> rows) {
        assertEquals(TRIPS.length * 2, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertArrayEquals(TRIPS[i % TRIPS.length], rows.get(i));
        }
    }

    private static List<Object[]> query(StorageEngine engine, String sql) {
        List<List<Object[]>> results = new Executor(engine).execute(sql);
        return results.isEmpty() ? List.of() : results.getFirst();
    }
}
