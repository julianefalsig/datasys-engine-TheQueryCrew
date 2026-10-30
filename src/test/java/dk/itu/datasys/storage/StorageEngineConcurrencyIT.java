package dk.itu.datasys.storage;

import dk.itu.datasys.exec.Executor;
import dk.itu.datasys.exec.Operator;
import dk.itu.datasys.exec.Plan;
import dk.itu.datasys.exec.Planner;
import dk.itu.datasys.sql.SelectStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The races Exercise 8 asks for. A writer is parked in {@code beforeCommit}, so the interleaving
 * is the one the test asked for, and every wait has a timeout.
 */
class StorageEngineConcurrencyIT {

    private static final Path LOG_FILE = Path.of("logs", "engine.log");

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

    private static final Object[] MALMO = {"Malmo", 10L, 15.0};
    private static final Object[] HELSINGBORG = {"Helsingborg", 40L, 60.0};

    private String sessionId;

    @BeforeEach
    void tagSession() {
        sessionId = "it-" + UUID.randomUUID();
        MDC.put("sessionId", sessionId);
        MDC.put("statementNumber", "0");
    }

    @AfterEach
    void clearSession() {
        MDC.clear();
    }

    @Test
    void aScanKeepsTheRowsOfTheVersionItPinned(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithTrips(dataDir);

        Plan plan = new Planner(engine).plan(new SelectStatement("trips", Optional.empty()));
        Operator scan = plan.root();
        scan.open();
        engine.delete("trips", "city", Comparison.EQUALS, "Copenhagen");
        List<Object[]> pinned = pull(scan);
        scan.close();

        assertRows(ALL_TRIPS, pinned);
        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Odense", 95L, 120.75},
                {"Aalborg", 210L, 340.5},
                {"Roskilde", 31L, 45.0},
                {"Esbjerg", 299L, 450.25}
        }, query(engine, "SELECT * FROM trips;"));
    }

    @Test
    void aParkedDeleteAbortsWhenACopyCommitsFirst(@TempDir Path dataDir) throws Exception {
        StorageEngine engine = engineWithTrips(dataDir);
        Path moreTrips = writeCsv(dataDir, "more.csv", "Malmo,10,15.0");

        loseTheRace(engine,
                () -> engine.delete("trips", "city", Comparison.EQUALS, "Copenhagen"),
                () -> engine.copyFile("trips", moreTrips.toString()));

        assertConflict(1, 2);
        assertEquals(2, engine.catalog("trips").version);
        assertRows(append(ALL_TRIPS, MALMO), query(engine, "SELECT * FROM trips;"));

        engine.delete("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, engine.catalog("trips").version);
        assertRows(new Object[][] {
                {"Aarhus", 187L, 301.0},
                {"Odense", 95L, 120.75},
                {"Aalborg", 210L, 340.5},
                {"Roskilde", 31L, 45.0},
                {"Esbjerg", 299L, 450.25},
                MALMO
        }, query(engine, "SELECT * FROM trips;"));
    }

    @Test
    void aParkedCopyAbortsWhenAnotherCopyCommitsFirst(@TempDir Path dataDir) throws Exception {
        StorageEngine engine = engineWithTrips(dataDir);
        Path loserTrips = writeCsv(dataDir, "loser.csv", "Malmo,10,15.0");
        Path winnerTrips = writeCsv(dataDir, "winner.csv", "Helsingborg,40,60.0");

        loseTheRace(engine,
                () -> engine.copyFile("trips", loserTrips.toString()),
                () -> engine.copyFile("trips", winnerTrips.toString()));

        assertConflict(1, 2);
        assertEquals(2, engine.catalog("trips").version);
        assertRows(append(ALL_TRIPS, HELSINGBORG), query(engine, "SELECT * FROM trips;"));

        engine.copyFile("trips", loserTrips.toString());
        assertEquals(3, engine.catalog("trips").version);
        assertRows(append(append(ALL_TRIPS, HELSINGBORG), MALMO), query(engine, "SELECT * FROM trips;"));
    }

    /**
     * Parks {@code loserWork} inside {@code beforeCommit}, lets {@code winnerWork} commit, then
     * releases the loser into validation. The loser comes back as a {@link ConflictException}.
     */
    private static void loseTheRace(StorageEngine engine, Runnable loserWork, Runnable winnerWork) throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Map<String, String> context = MDC.getCopyOfContextMap();
        Future<?> loser = pool.submit(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                loserWork.run();
            } finally {
                MDC.clear();
            }
        });
        try {
            assertTrue(parked.await(5, TimeUnit.MINUTES));
            engine.beforeCommit = () -> { };
            winnerWork.run();
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, loser::get);
            assertInstanceOf(ConflictException.class, failure.getCause());
        } finally {
            winnerDone.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.MINUTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting", e);
        }
    }

    /** The winner's commit line and the loser's ERROR line, on two different threads. */
    private void assertConflict(int base, int current) throws IOException {
        String needle = "conflict table=trips base=%d current=%d".formatted(base, current);
        String[] error = null;
        String[] winner = null;
        for (String[] fields : myLogLines()) {
            String message = fields[6];
            if ("ERROR".equals(fields[4]) && message.contains(needle)) {
                error = fields;
            }
            if (message.contains("op=copyFile") && message.contains("version=" + current)) {
                winner = fields;
            }
        }
        assertNotNull(error, "no ERROR line for " + needle);
        assertNotNull(winner, "no committed version=" + current);
        assertNotEquals(winner[3], error[3]);
    }

    private List<String[]> myLogLines() throws IOException {
        List<String[]> mine = new ArrayList<>();
        for (String line : Files.readAllLines(LOG_FILE)) {
            String[] fields = line.split(",", -1);
            if (fields.length == 7 && fields[1].equals(sessionId)) {
                mine.add(fields);
            }
        }
        return mine;
    }

    private static StorageEngine engineWithTrips(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, "trips.csv").toString());
        return engine;
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = StorageEngineConcurrencyIT.class.getResourceAsStream("/" + name)) {
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

    private static List<Object[]> pull(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
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
