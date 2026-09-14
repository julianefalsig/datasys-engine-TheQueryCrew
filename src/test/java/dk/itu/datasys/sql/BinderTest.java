package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.StorageEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinderTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static Binder binderWithTrips(Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);
        return new Binder(engine);
    }

    // The table is in the catalog, so the binder has nothing to complain about.
    @Test
    void copyIntoAKnownTableBinds(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "trips.csv")));
    }

    // No such table: the schema lookup throws, and the binder lets it through.
    @Test
    void copyIntoAnUnknownTableThrows(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CopyStatement("missing", "trips.csv")));
    }

    // The file is execution's concern, so a missing one must still bind cleanly.
    @Test
    void aMissingCsvFileIsNotTheBindersProblem(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "does-not-exist.csv")));
    }
}
