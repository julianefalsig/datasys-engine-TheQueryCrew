package dk.itu.datasys.exec;

import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.ScanStats;
import dk.itu.datasys.storage.StorageEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlannerTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void whereBuildsFilterOverScan(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithSortedTrips(dataDir);
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(new SelectStatement(
                "trips", Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L))));

        FilterOperator filter = assertInstanceOf(FilterOperator.class, plan.root());
        assertInstanceOf(ScanOperator.class, filter.child());
    }

    @Test
    void noWhereBuildsBareScanOverAllPartitions(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithSortedTrips(dataDir);
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(new SelectStatement("trips", Optional.empty()));

        assertInstanceOf(ScanOperator.class, plan.root());
        ScanStats stats = plan.stats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(4, stats.partitionsRead());
        assertEquals(0, stats.partitionsPruned());
    }

    // Sorted golden data, maxRowsPerPartition = 2: distance > 200 keeps only the last partition.
    @Test
    void pruningKeepsOnlyPartitionsThatCanMatch(@TempDir Path dataDir) throws IOException {
        StorageEngine engine = engineWithSortedTrips(dataDir);
        Planner planner = new Planner(engine);

        Plan plan = planner.plan(new SelectStatement(
                "trips", Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L))));

        ScanStats stats = plan.stats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());
    }

    private static StorageEngine engineWithSortedTrips(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", copyResource(dataDir, "trips_sorted.csv").toString());
        return engine;
    }

    private static Path copyResource(Path dataDir, String name) throws IOException {
        Path dest = dataDir.resolve(name);
        try (InputStream in = PlannerTest.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }
}
