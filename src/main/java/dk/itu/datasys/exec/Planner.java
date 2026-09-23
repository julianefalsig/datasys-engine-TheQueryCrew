package dk.itu.datasys.exec;

import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.CatalogData;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.Pruner;
import dk.itu.datasys.storage.ScanStats;
import dk.itu.datasys.storage.StorageEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a bound SELECT into a Volcano plan.
 * Partition pruning happens here from catalog min/max only
 * - no data file is opened.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        String tableName = select.tableName();
        CatalogData catalog = engine.catalog(tableName);
        List<ColumnSpec> columns = List.copyOf(catalog.columns);
        Path tableDir = engine.tableDirectory(tableName);

        // No where clause -> read all partitions.
        // Operator expects a list of Paths to the partition files, we build it here.
        if (select.where().isEmpty()) {
            List<Path> allPartitions = new ArrayList<>(catalog.partitions.size());
            for (CatalogData.Partition partition : catalog.partitions) {
                allPartitions.add(tableDir.resolve(partition.dataFile()));
            }
            int total = catalog.partitions.size();
            ScanStats stats = new ScanStats(total, total, 0); // all partitions read
            return new Plan(new ScanOperator(tableName, columns, allPartitions), stats);
        }

        // There is a where clause -> we prune partitions
        // We also construct the row predicate for the filter operator.
        Predicate predicate = select.where().get();
        int columnIndex = columnIndex(columns, predicate.columnName(), tableName);
        ColumnSpec predicateColumn = columns.get(columnIndex);
        Comparison comparison = predicate.comparison();
        Object constant = predicate.constant();

        List<Path> surviving = new ArrayList<>();
        int partitionsTotal = catalog.partitions.size();
        int partitionsPruned = 0;

        for (int p = 0; p < partitionsTotal; p++) {
            // Prune if the partition is outside the range of the predicate.
            CatalogData.Partition entry = catalog.partitions.get(p);
            CatalogData.Range range = entry.stats().get(predicate.columnName());
            Object min = range.min();
            Object max = range.max();

            boolean prune = Pruner.canPrune(comparison, constant, min, max, predicateColumn.type());
            LOGGER.debug("op=select table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    csvSafe(tableName), csvSafe(predicate.columnName()), comparison, csvSafe(constant), p,
                    csvSafe(min), csvSafe(max), prune ? "PRUNED" : "READ");

            if (prune) {
                partitionsPruned++;
            } else {
                surviving.add(tableDir.resolve(entry.dataFile()));
            }
        }

        int partitionsKept = surviving.size();
        ScanStats stats = new ScanStats(partitionsTotal, partitionsKept, partitionsPruned);

        // Build the plan from the leaf -> root.
        Operator scan = new ScanOperator(tableName, columns, surviving);
        RowPredicate rowPredicate = new RowPredicate(
                columnIndex, comparison, constant, predicateColumn.type());
        return new Plan(new FilterOperator(scan, rowPredicate), stats);
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName, String tableName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName + " on table " + tableName);
    }

    private static String csvSafe(Object value) {
        return value == null ? "none" : String.valueOf(value).replace(',', ';').replaceAll("\\s+", " ");
    }
}
