package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

//what is tested here is the read-only accessor the binder looks tables up through
class StorageEngineSchemaTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // testing that the columns come back in schema order            
    @Test
    void returnsTheColumnsInSchemaOrder(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("trips", TRIPS_SCHEMA);

        assertEquals(TRIPS_SCHEMA, engine.schema("trips"));
    }

    //testing that an unknown table is an error rather than an empty result
    @Test
    void unknownTableThrows(@TempDir Path dataDir) {
        StorageEngine engine = new StorageEngine(dataDir);

        assertThrows(IllegalArgumentException.class, () -> engine.schema("missing"));
    }

    // testing that List.copyOf is working (returned list cannot be used to change the catalog)
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
