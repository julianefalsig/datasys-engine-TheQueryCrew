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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void secondCopyFileIsRejected(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        assertThrows(UnsupportedOperationException.class,
                () -> engine.copyFile("trips", "src/test/resources/trips.csv"));
    }

    private static List<Object[]> query(StorageEngine engine, String sql) {
        List<List<Object[]>> results = new Executor(engine).execute(sql);
        return results.isEmpty() ? List.of() : results.getFirst();
    }
}
