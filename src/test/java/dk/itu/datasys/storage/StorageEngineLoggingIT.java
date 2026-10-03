package dk.itu.datasys.storage;

import dk.itu.datasys.exec.Executor;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class StorageEngineLoggingIT {

    private static final Path LOG_FILE = Path.of("logs", "engine.log");

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

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
    void everyFailingApiCallLogsOneErrorLine(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);

        assertThrows(IllegalArgumentException.class, () -> engine.createTable("trips", TRIPS_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> engine.copyFile("missing", "nope.csv"));
        assertThrows(java.io.UncheckedIOException.class,
                () -> engine.copyFile("trips", dataDir.resolve("nope.csv").toString()));

        List<String> ops = new ArrayList<>();
        for (String[] fields : myLogLines()) {
            if (fields[4].equals("ERROR")) {
                assertTrue(fields[6].contains("outcome=FAILED"), fields[6]);
                ops.add(fields[6].split(" ")[0]);
            }
        }
        assertEquals(List.of("op=createTable", "op=copyFile", "op=copyFile"), ops);
    }

    @Test
    void everySuccessfulApiCallLogsOneSummaryLine(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, "trips.csv").toString());
        new Executor(engine).execute("SELECT * FROM trips WHERE distance > 100;");

        assertEquals(1, countMessagesContaining("op=createTable table=trips columns=3"));
        assertEquals(1, countMessagesContaining("op=copyFile table=trips file="));
        assertEquals(4, countMessagesContaining("op=select table=trips column=distance comparison=GREATER_THAN "
                + "const=100"));
        assertEquals(1, countMessagesContaining(
                "op=select table=trips partitionsTotal=4 partitionsRead=4 partitionsPruned=0"));
    }

    @Test
    void commasInMessagesAndValuesNeverBreakTheSevenFields(@TempDir Path dataDir) throws IOException {
        Path badCsv = dataDir.resolve("bad.csv");
        Files.writeString(badCsv, "Copenhagen,12,23.5,extra\n");
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);

        assertThrows(IllegalArgumentException.class, () -> engine.copyFile("trips", badCsv.toString()));
        engine.copyFile("trips", copyResource(dataDir, "trips.csv").toString());
        new Executor(engine).execute("SELECT * FROM trips WHERE city = 'Copen,hagen';");

        for (String[] fields : myLogLines()) {
            assertEquals(7, fields.length, "line does not parse into seven fields: " + String.join(",", fields));
        }
        assertEquals(1, countMessagesContaining("expected 3; got 4"));
        assertEquals(1, countMessagesContaining("const=Copen;hagen"));
    }

    private List<String[]> myLogLines() throws IOException {
        List<String[]> mine = new ArrayList<>();
        for (String line : Files.readAllLines(LOG_FILE)) {
            String[] fields = line.split(",", -1);

            if (fields.length >= 2 && fields[1].equals(sessionId)) {
                mine.add(fields);
            }
        }
        return mine;
    }

    private int countMessagesContaining(String needle) throws IOException {
        int count = 0;
        for (String[] fields : myLogLines()) {
            if (fields.length == 7 && fields[6].contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = StorageEngineLoggingIT.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }
}
