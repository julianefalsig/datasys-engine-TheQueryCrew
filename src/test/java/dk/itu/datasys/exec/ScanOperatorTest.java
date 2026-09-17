package dk.itu.datasys.exec;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.TestPartitions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScanOperatorTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // The format is written through a storage-side test helper, because PartitionFile.write is not
    // open outside its package: only copyFile may create a partition the catalog knows about.
    private static Path writePartition(Path dir, String name, Object[]... rows) {
        return TestPartitions.write(dir, name, TRIPS_SCHEMA, List.of(rows));
    }

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        operator.close();
        return rows;
    }

    // Two partitions in, every row out, partition order first and row order within each.
    @Test
    void returnsEveryRowOfEveryPartitionInOrder(@TempDir Path dir) {
        Path first = writePartition(dir, "partition-0.bin",
                new Object[]{"Copenhagen", 12L, 23.5},
                new Object[]{"Aarhus", 187L, 301.0});
        Path second = writePartition(dir, "partition-1.bin",
                new Object[]{"Odense", 95L, 120.75});

        List<Object[]> rows = drain(new ScanOperator("trips", TRIPS_SCHEMA, List.of(first, second)));

        assertEquals(3, rows.size());
        assertEquals("Copenhagen", rows.get(0)[0]);
        assertEquals("Aarhus", rows.get(1)[0]);
        assertEquals("Odense", rows.get(2)[0]);
        assertEquals(95L, rows.get(2)[1]);
        assertEquals(120.75, rows.get(2)[2]);
    }

    // A fully pruned query hands the scan nothing: it must read nothing and return nothing.
    @Test
    void anEmptyPartitionListReadsNothing(@TempDir Path dir) {
        Operator scan = new ScanOperator("trips", TRIPS_SCHEMA, List.of());

        assertEquals(List.of(), drain(scan));
    }

    // Only the partitions handed over are read; the planner already dropped the rest.
    @Test
    void readsOnlyThePartitionsItIsGiven(@TempDir Path dir) {
        Path kept = writePartition(dir, "partition-0.bin", new Object[]{"Copenhagen", 12L, 23.5});
        writePartition(dir, "partition-1.bin", new Object[]{"Aarhus", 187L, 301.0});

        List<Object[]> rows = drain(new ScanOperator("trips", TRIPS_SCHEMA, List.of(kept)));

        assertEquals(1, rows.size());
        assertEquals("Copenhagen", rows.get(0)[0]);
    }

    // Once exhausted the scan keeps saying null rather than running off the end of a partition.
    @Test
    void staysExhaustedAfterTheLastRow(@TempDir Path dir) {
        Path only = writePartition(dir, "partition-0.bin", new Object[]{"Copenhagen", 12L, 23.5});
        Operator scan = new ScanOperator("trips", TRIPS_SCHEMA, List.of(only));

        scan.open();

        assertEquals("Copenhagen", scan.next()[0]);
        assertNull(scan.next());
        assertNull(scan.next());
    }
}
