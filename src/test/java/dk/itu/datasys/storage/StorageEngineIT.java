package dk.itu.datasys.storage;

import dk.itu.datasys.exec.Executor;
import dk.itu.datasys.exec.Planner;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageEngineIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final Object[][] ALL_TRIPS = {
            {"Copenhagen", 12L, 23.5},
            {"Aarhus", 187L, 301.0},
            {"Odense", 95L, 120.75},
            {"Copenhagen", 140L, 210.0},
            {"Aalborg", 210L, 340.5},
            {"Roskilde", 31L, 45.0},
            {"Copenhagen", 88L, 99.99},
            {"Esbjerg", 299L, 450.25}
    };

    @Test
    void schemaPersistence(@TempDir Path dataDir) {
        StorageEngine first = new StorageEngine(dataDir);
        first.createTable("trips", TRIPS_SCHEMA);

        StorageEngine restarted = new StorageEngine(dataDir);
        assertThrows(IllegalArgumentException.class, () -> restarted.createTable("trips", TRIPS_SCHEMA));
        assertEquals(List.of(), query(restarted, "SELECT * FROM trips;"));
    }

    @Test
    void duplicateTable(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("trips", TRIPS_SCHEMA));
    }

    @Test
    void roundTrip(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");
        assertRows(ALL_TRIPS, query(engine, "SELECT * FROM trips;"));
    }

    @Test
    void allComparisonsAgainstAllTypes(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Copenhagen", 140L, 210.0},
                {"Copenhagen", 88L, 99.99}
        }, query(engine, "SELECT * FROM trips WHERE city = 'Copenhagen';"));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Aalborg", 210L, 340.5}
        }, query(engine, "SELECT * FROM trips WHERE city < 'Copenhagen';"));

        assertRows(new Object[][] {
                {"Odense", 95L, 120.75},
                {"Roskilde", 31L, 45.0},
                {"Esbjerg", 299L, 450.25}
        }, query(engine, "SELECT * FROM trips WHERE city > 'Copenhagen';"));

        assertRows(new Object[][] {
                {"Copenhagen", 140L, 210.0}
        }, query(engine, "SELECT * FROM trips WHERE distance = 140;"));

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Odense", 95L, 120.75},
                {"Roskilde", 31L, 45.0},
                {"Copenhagen", 88L, 99.99}
        }, query(engine, "SELECT * FROM trips WHERE distance < 100;"));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Copenhagen", 140L, 210.0},
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, query(engine, "SELECT * FROM trips WHERE distance > 100;"));

        assertRows(new Object[][] {
                {"Copenhagen", 88L, 99.99}
        }, query(engine, "SELECT * FROM trips WHERE price = 99.99;"));

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Roskilde", 31L, 45.0}
        }, query(engine, "SELECT * FROM trips WHERE price < 50.0;"));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, query(engine, "SELECT * FROM trips WHERE price > 300.0;"));
    }

    @Test
    void emptyResult(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");
        assertEquals(List.of(), query(engine, "SELECT * FROM trips WHERE city = 'Paris';"));
    }

    @Test
    void errors(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");
        Executor executor = new Executor(engine);
        assertThrows(IllegalArgumentException.class, () -> executor.execute("SELECT * FROM missing;"));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("SELECT * FROM trips WHERE missing > 100;"));
    }

    @Test
    void partitioning(@TempDir Path dataDir) throws IOException {
        engineWithTrips(dataDir, "trips.csv");
        CatalogData catalog = CatalogStore.read(dataDir.resolve("trips"));
        assertEquals(4, catalog.partitions.size());

        assertRange(catalog.partitions.get(0), "city", "Aarhus", "Copenhagen");
        assertRange(catalog.partitions.get(0), "distance", 12L, 187L);
        assertRange(catalog.partitions.get(0), "price", 23.5, 301.0);

        assertRange(catalog.partitions.get(1), "city", "Copenhagen", "Odense");
        assertRange(catalog.partitions.get(1), "distance", 95L, 140L);
        assertRange(catalog.partitions.get(1), "price", 120.75, 210.0);

        assertRange(catalog.partitions.get(2), "city", "Aalborg", "Roskilde");
        assertRange(catalog.partitions.get(2), "distance", 31L, 210L);
        assertRange(catalog.partitions.get(2), "price", 45.0, 340.5);

        assertRange(catalog.partitions.get(3), "city", "Copenhagen", "Esbjerg");
        assertRange(catalog.partitions.get(3), "distance", 88L, 299L);
        assertRange(catalog.partitions.get(3), "price", 99.99, 450.25);
    }

    @Test
    void pruning(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips_sorted.csv");
        var plan = new Planner(engine).plan(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L))));

        assertTrue(plan.stats().partitionsPruned() >= 2);
        assertRows(new Object[][] {
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, plan.drain());
    }

    @Test
    void dataPersistence(@TempDir Path dataDir) throws IOException {
        engineWithTrips(dataDir, "trips.csv");
        StorageEngine restarted = new StorageEngine(dataDir);
        assertRows(ALL_TRIPS, query(restarted, "SELECT * FROM trips;"));
    }

    private static StorageEngine engineWithTrips(Path dataDir, String sourceCsv) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, sourceCsv).toString());
        return engine;
    }

    private static List<Object[]> query(StorageEngine engine, String sql) {
        List<List<Object[]>> results = new Executor(engine).execute(sql);
        return results.isEmpty() ? List.of() : results.getFirst();
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = StorageEngineIT.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }

    private static void assertRange(CatalogData.Partition partition, String column, Object min, Object max) {
        CatalogData.Range range = partition.stats().get(column);
        assertEquals(min, range.min());
        assertEquals(max, range.max());
    }

    private static void assertRows(Object[][] expected, List<Object[]> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i));
            assertInstanceOf(String.class, actual.get(i)[0]);
            assertInstanceOf(Long.class, actual.get(i)[1]);
            assertInstanceOf(Double.class, actual.get(i)[2]);
        }
    }

    @Test
    void returnsTheColumnsInSchemaOrder(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);

        assertEquals(TRIPS_SCHEMA, engine.schema("trips"));
    }

    @Test
    void unknownTableThrows(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);

        assertThrows(IllegalArgumentException.class, () -> engine.schema("missing"));
    }

    @Test
    void theReturnedListCannotChangeTheCatalog(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);

        List<ColumnSpec> columns = engine.schema("trips");
        assertThrows(UnsupportedOperationException.class,
                () -> columns.add(new ColumnSpec("sneaked_in", ColumnType.LONG)));
        assertEquals(3, engine.schema("trips").size());
    }
}
