package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.StorageEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinderIT {

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

    // Distinct column names and at least one of them: nothing for the binder to object to.
    @Test
    void createTableWithDistinctColumnsBinds(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("cities", List.of(
                new ColumnSpec("name", ColumnType.STRING),
                new ColumnSpec("population", ColumnType.LONG)))));
    }

    // Two columns called "city": the table could never be queried unambiguously.
    @Test
    void duplicateColumnNamesThrow(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("cities", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("city", ColumnType.LONG)))));
    }

    // The grammar cannot produce this, but bind() takes any Statement, so it is checked anyway.
    @Test
    void anEmptyColumnListThrows(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("cities", List.of())));
    }

    // "Already exists" is execution's concern: another session can create the table between binding
    // and running, so the check only means anything where it happens together with the write.
    @Test
    void anExistingTableIsNotTheBindersProblem(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("trips", TRIPS_SCHEMA)));
    }

    // No WHERE, so the table is the only thing there is to check.
    @Test
    void selectWithoutWhereBinds(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips", Optional.empty())));
    }

    // distance is a LONG column and 100L is a Long, so the predicate lines up.
    @Test
    void selectWithAMatchingPredicateBinds(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)))));
    }

    // No such table, exactly as for COPY.
    @Test
    void selectFromAnUnknownTableThrows(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("missing", Optional.empty())));
    }

    // The table exists but has no such column, so the predicate can never be evaluated.
    @Test
    void selectOnAnUnknownColumnThrows(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips",
                        Optional.of(new Predicate("missing", Comparison.EQUALS, "Copenhagen")))));
    }

    // distance = 'x' compares a LONG column against a String.
    @Test
    void aTypeMismatchedConstantThrows(@TempDir Path dataDir) {
        Binder binder = binderWithTrips(dataDir);

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips",
                        Optional.of(new Predicate("distance", Comparison.EQUALS, "x")))));
    }
}
