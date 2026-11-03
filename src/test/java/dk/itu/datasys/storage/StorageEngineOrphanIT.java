package dk.itu.datasys.storage;

import dk.itu.datasys.exec.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Partition files that no catalog entry points at are removed on open and on close. */
class StorageEngineOrphanIT {

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
    void openRemovesOrphanPartitionsAndLeavesTheRest(@TempDir Path dataDir) throws IOException {
        StorageEngine first = engineWithTrips(dataDir);
        Path tableDir = first.tableDirectory("trips");
        Path orphan = tableDir.resolve("partition-99.bin");
        Path notes = tableDir.resolve("notes.txt");
        Files.writeString(orphan, "not referenced");
        Files.writeString(notes, "keep");

        StorageEngine restarted = new StorageEngine(dataDir, 2);

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(notes));
        assertTrue(Files.exists(tableDir.resolve("partition-0.bin")));
        assertRows(ALL_TRIPS, query(restarted, "SELECT * FROM trips;"));
    }

    @Test
    void closeRemovesOrphanPartitions(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir);
        Path tableDir = engine.tableDirectory("trips");
        Path orphan = tableDir.resolve("partition-99.bin");
        Path notes = tableDir.resolve("notes.txt");
        Files.writeString(orphan, "not referenced");
        Files.writeString(notes, "keep");

        engine.close();

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(notes));
        assertTrue(Files.exists(tableDir.resolve("partition-0.bin")));
        assertRows(ALL_TRIPS, query(new StorageEngine(dataDir, 2), "SELECT * FROM trips;"));
    }

    @Test
    void closeRemovesThePartitionALostConflictLeftBehind(@TempDir Path dataDir) throws Exception {
        StorageEngine engine = engineWithTrips(dataDir);
        Path tableDir = engine.tableDirectory("trips");
        Path loserFile = tableDir.resolve("partition-4.bin");
        Path winnerFile = tableDir.resolve("partition-5.bin");
        Path loserTrips = writeCsv(dataDir, "loser.csv", "Malmo,10,15.0");
        Path winnerTrips = writeCsv(dataDir, "winner.csv", "Helsingborg,40,60.0");

        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> loser = pool.submit(() -> engine.copyFile("trips", loserTrips.toString()));
        try {
            assertTrue(parked.await(5, TimeUnit.MINUTES));
            assertTrue(Files.exists(loserFile));

            engine.beforeCommit = () -> { };
            engine.copyFile("trips", winnerTrips.toString());
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, loser::get);
            assertInstanceOf(ConflictException.class, failure.getCause());
        } finally {
            winnerDone.countDown();
            pool.shutdownNow();
        }

        assertTrue(Files.exists(loserFile));
        engine.close();

        assertFalse(Files.exists(loserFile));
        assertTrue(Files.exists(winnerFile));
        assertTrue(Files.exists(tableDir.resolve("partition-0.bin")));
        assertRows(append(ALL_TRIPS, new Object[] {"Helsingborg", 40L, 60.0}),
                query(new StorageEngine(dataDir, 2), "SELECT * FROM trips;"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.MINUTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting", e);
        }
    }

    private static StorageEngine engineWithTrips(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, "trips.csv").toString());
        return engine;
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = StorageEngineOrphanIT.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }

    private static Path writeCsv(Path dir, String name, String line) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, line + "\n");
        return path;
    }

    private static List<Object[]> query(StorageEngine engine, String sql) {
        List<List<Object[]>> results = new Executor(engine).execute(sql);
        return results.isEmpty() ? List.of() : results.getFirst();
    }

    private static Object[][] append(Object[][] rows, Object[] extra) {
        Object[][] all = new Object[rows.length + 1][];
        System.arraycopy(rows, 0, all, 0, rows.length);
        all[rows.length] = extra;
        return all;
    }

    private static void assertRows(Object[][] expected, List<Object[]> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i));
        }
    }
}
