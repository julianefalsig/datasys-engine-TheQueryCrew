package dk.itu.datasys.storage;

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
        List<Object[]> rows = restarted.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(List.of(), rows);
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
        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertRows(ALL_TRIPS, rows);
    }

    @Test
    void allComparisonsAgainstAllTypes(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Copenhagen", 140L, 210.0},
                {"Copenhagen", 88L, 99.99}
        }, engine.select("trips", "city", Comparison.EQUALS, "Copenhagen"));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Aalborg", 210L, 340.5}
        }, engine.select("trips", "city", Comparison.LESS_THAN, "Copenhagen"));

        assertRows(new Object[][] {
                {"Odense", 95L, 120.75},
                {"Roskilde", 31L, 45.0},
                {"Esbjerg", 299L, 450.25}
        }, engine.select("trips", "city", Comparison.GREATER_THAN, "Copenhagen"));

        assertRows(new Object[][] {
                {"Copenhagen", 140L, 210.0}
        }, engine.select("trips", "distance", Comparison.EQUALS, 140L));

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Odense", 95L, 120.75},
                {"Roskilde", 31L, 45.0},
                {"Copenhagen", 88L, 99.99}
        }, engine.select("trips", "distance", Comparison.LESS_THAN, 100L));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Copenhagen", 140L, 210.0},
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, engine.select("trips", "distance", Comparison.GREATER_THAN, 100L));

        assertRows(new Object[][] {
                {"Copenhagen", 88L, 99.99}
        }, engine.select("trips", "price", Comparison.EQUALS, 99.99));

        assertRows(new Object[][] {
                {"Copenhagen", 12L, 23.5},
                {"Roskilde", 31L, 45.0}
        }, engine.select("trips", "price", Comparison.LESS_THAN, 50.0));

        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, engine.select("trips", "price", Comparison.GREATER_THAN, 300.0));
    }

    @Test
    void emptyResult(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");
        List<Object[]> rows = engine.select("trips", "city", Comparison.EQUALS, "Paris");
        assertEquals(List.of(), rows);
    }

    @Test
    void errors(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir, "trips.csv");
        // unknown table
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("missing", "distance", Comparison.GREATER_THAN, 100L));
        // unknown column
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "missing", Comparison.GREATER_THAN, 100L));
        // wrong data type of constant
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.GREATER_THAN, 100));
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

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        assertTrue(engine.lastScanStats().partitionsPruned() >= 2);
        assertRows(new Object[][] {
                {"Aalborg", 210L, 340.5},
                {"Esbjerg", 299L, 450.25}
        }, rows);
    }

    @Test
    void dataPersistence(@TempDir Path dataDir) throws IOException {
        engineWithTrips(dataDir, "trips.csv");
        StorageEngine restarted = new StorageEngine(dataDir);
        List<Object[]> rows = restarted.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertRows(ALL_TRIPS, rows);
    }

    private static StorageEngine engineWithTrips(Path dataDir, String sourceCsv) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, sourceCsv).toString());
        return engine;
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
}
