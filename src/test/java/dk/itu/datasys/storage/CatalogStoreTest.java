package dk.itu.datasys.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogStoreTest {

    @Test
    void unknownKeysAreIgnoredAndNdvIsKept(@TempDir Path tableDir) throws IOException {
        Files.writeString(tableDir.resolve("catalog.json"), """
                {
                  "version": 1,
                  "maxPartitionNumber": 0,
                  "columns": [ { "name": "distance", "type": "LONG" } ],
                  "maxRowsPerPartition": 2,
                  "future": true,
                  "partitions": [
                    {
                      "dataFile": "partition-0.bin",
                      "rowCount": 2,
                      "stats": { "distance": { "min": 12, "max": 31, "ndv": 2, "sketch": 4 } }
                    }
                  ]
                }
                """);

        CatalogData catalog = CatalogStore.read(tableDir);

        CatalogData.Range distance = catalog.partitions.getFirst().stats().get("distance");
        assertEquals(12L, distance.min());
        assertEquals(31L, distance.max());
        assertEquals(2L, distance.ndv());
    }

    @Test
    void aCatalogWrittenBeforeNdvStillLoads(@TempDir Path tableDir) throws IOException {
        Files.writeString(tableDir.resolve("catalog.json"), """
                {
                  "version": 1,
                  "maxPartitionNumber": 0,
                  "columns": [ { "name": "distance", "type": "LONG" } ],
                  "maxRowsPerPartition": 2,
                  "partitions": [
                    {
                      "dataFile": "partition-0.bin",
                      "rowCount": 2,
                      "stats": { "distance": { "min": 12, "max": 31 } }
                    }
                  ]
                }
                """);

        CatalogData.Range distance = CatalogStore.read(tableDir).partitions.getFirst().stats().get("distance");
        assertEquals(12L, distance.min());
        assertEquals(31L, distance.max());
        assertNull(distance.ndv());
    }
}
