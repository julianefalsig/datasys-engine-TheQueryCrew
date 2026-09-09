package dk.itu.datasys;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.StorageEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        runGoldenExample();
        LOGGER.debug("engine stopped");
    }

    private static void runGoldenExample() {
        Path dataDir = createTempDataDirectory();
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", "src/test/resources/trips.csv");

        printResult("distance GREATER_THAN 100",
                engine.select("trips", "distance", Comparison.GREATER_THAN, 100L));
        printResult("city EQUALS Copenhagen",
                engine.select("trips", "city", Comparison.EQUALS, "Copenhagen"));
        printResult("price LESS_THAN 50.0",
                engine.select("trips", "price", Comparison.LESS_THAN, 50.0));
    }

    private static Path createTempDataDirectory() {
        try {
            return Files.createTempDirectory("datasys-engine");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create a temporary data directory", e);
        }
    }

    private static void printResult(String predicate, List<Object[]> rows) {
        System.out.println(predicate);
        for (Object[] row : rows) {
            System.out.println(row[0] + "," + row[1] + "," + row[2]);
        }
        System.out.println();
    }

    String teamName() {
        return "The Query Crew";
    }
}
