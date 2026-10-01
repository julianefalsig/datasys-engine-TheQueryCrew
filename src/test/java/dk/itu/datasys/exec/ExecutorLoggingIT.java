package dk.itu.datasys.exec;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.StorageEngine;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@code statementNumber} is for: reading one statement's story back out of the log. 
 * The assertions go through {@code logs/engine.log}
 */
class ExecutorLoggingIT {

    private static final Path LOG_FILE = Path.of("logs", "engine.log");
    private static final String STATEMENT_NUMBER = "statementNumber";

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private String sessionId;

    /** A session of its own, so this test can pick its lines out of a shared log file. */
    @BeforeEach
    void tagSession() {
        sessionId = "it-" + UUID.randomUUID();
        MDC.put("sessionId", sessionId);
        MDC.put(STATEMENT_NUMBER, "0");
    }

    @AfterEach
    void clearSession() {
        MDC.clear();
    }

    @Test
    void everyLineOfAStatementCarriesThatStatementsNumber(@TempDir Path dataDir) throws IOException {
        Executor executor = new Executor(new StorageEngine(dataDir));

        executor.execute("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE city = 'Odense';
                SELECT * FROM trips;
                """.formatted(sqlPath(copyResource(dataDir, "trips.csv"))));

        // Parsing is the script's own work, so it still carries the 0 set before the run.
        assertEquals("0", numberOfLineContaining("statements=4"));

        assertEquals("1", numberOfLineContaining("op=createTable"));
        assertEquals("2", numberOfLineContaining("op=copyFile table=trips file="));

        // The third statement is a whole pipeline: planner and filter both answer to its number.
        assertEquals("3", numberOfLineContaining("const=Odense"));
        assertEquals("3", numberOfLineContaining("op=filter"));

        // Both SELECTs scan, and the two scans belong to different statements.
        assertEquals(List.of("3", "4"), numbersOfLinesContaining("op=scan"));

        // Counted from 1, one number per statement, in order, with no gaps and no repeats.
        assertEquals(List.of("0", "1", "2", "3", "4"), numbersInOrderOfFirstAppearance());
    }

    /**
     * The failing half: the error belongs to the statement that caused it, and the count still ends
     * at 0 so the engine's own stop line falls outside it.
     */
    @Test
    void aFailingStatementKeepsItsNumberAndTheCountStillEndsAtZero(@TempDir Path dataDir) throws IOException {
        Executor executor = new Executor(new StorageEngine(dataDir));

        assertThrows(IllegalArgumentException.class, () -> executor.execute("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM missing;
                """.formatted(sqlPath(copyResource(dataDir, "trips.csv")))));

        assertEquals("3", numberOfLineContaining("table=missing outcome=FAILED"));
        assertEquals("0", MDC.get(STATEMENT_NUMBER));
    }

    // ---- Reading the log -------------------------------------------------------------------

    /** Field 3 of the CSV log is statementNumber; field 7 is the message. */
    private String numberOfLineContaining(String needle) throws IOException {
        List<String> matches = numbersOfLinesContaining(needle);
        assertEquals(1, matches.size(), "expected exactly one line containing: " + needle);
        return matches.getFirst();
    }

    private List<String> numbersOfLinesContaining(String needle) throws IOException {
        List<String> numbers = new ArrayList<>();
        for (String[] fields : myLogLines()) {
            if (fields[6].contains(needle)) {
                numbers.add(fields[2]);
            }
        }
        return numbers;
    }

    private List<String> numbersInOrderOfFirstAppearance() throws IOException {
        Set<String> numbers = new LinkedHashSet<>();
        for (String[] fields : myLogLines()) {
            numbers.add(fields[2]);
        }
        return List.copyOf(numbers);
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

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = ExecutorLoggingIT.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
