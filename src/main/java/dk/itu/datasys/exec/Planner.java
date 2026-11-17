package dk.itu.datasys.exec;

import dk.itu.datasys.sql.ColumnResolver;
import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.CatalogData;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.Pruner;
import dk.itu.datasys.storage.RowPredicate;
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
 * Joins fold the FROM list left to right: the intermediate result is the build side,
 * the next table probes, and a table with no connecting predicate is rejected.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        List<String> tableNames = select.tableNames();
        if (tableNames.isEmpty()) {
            throw new IllegalArgumentException("SELECT needs a table");
        }
        if (tableNames.size() == 1 && !select.joins().isEmpty()) {
            throw new IllegalArgumentException(
                    "join predicates need two tables; " + tableNames.getFirst() + " is alone in the FROM list");
        }

        // One snapshot per table, copied before any pipeline is built.
        List<PinnedTable> pinned = new ArrayList<>(tableNames.size());
        for (String tableName : tableNames) {
            pinned.add(pin(tableName));
        }
        List<List<ColumnSpec>> schemas = new ArrayList<>(pinned.size());
        for (PinnedTable table : pinned) {
            schemas.add(table.columns());
        }
        ColumnResolver columns = new ColumnResolver(tableNames, schemas);

        PinnedTable first = pinned.getFirst();
        TablePipeline left = planTable(first, filtersOn(first.name(), select.filters(), columns));
        Operator joined = left.operator();
        List<ScanStats> tableStats = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();
        tableStats.add(left.stats());
        versions.add(left.version());

        List<String> joinedNames = new ArrayList<>();
        joinedNames.add(first.name());
        int buildWidth = first.columns().size();
        for (int i = 1; i < pinned.size(); i++) {
            PinnedTable next = pinned.get(i);
            JoinKeys keys = connectingJoin(columns, joinedNames, next.name(), select.joins(), buildWidth);
            TablePipeline probe = planTable(next, filtersOn(next.name(), select.filters(), columns));
            joined = new HashJoinOperator(joined, probe.operator(), keys.buildKey(), keys.probeKey());
            tableStats.add(probe.stats());
            versions.add(probe.version());
            joinedNames.add(next.name());
            buildWidth += next.columns().size();
        }
        return new Plan(joined, tableStats.getFirst(), tableStats, versions);
    }

    // The catalog object is replaced on commit, so the copied lists are this statement's snapshot.
    private PinnedTable pin(String tableName) {
        CatalogData catalog = engine.catalog(tableName);
        return new PinnedTable(tableName, catalog.version, List.copyOf(catalog.columns),
                List.copyOf(catalog.partitions), engine.tableDirectory(tableName));
    }

    private static List<Predicate> filtersOn(String tableName, List<Predicate> filters, ColumnResolver columns) {
        List<Predicate> onTable = new ArrayList<>();
        for (Predicate filter : filters) {
            if (columns.resolve(filter.columnName()).tableName().equals(tableName)) {
                onTable.add(filter);
            }
        }
        return onTable;
    }

    private TablePipeline planTable(PinnedTable table, List<Predicate> filters) {
        String tableName = table.name();
        int version = table.version();
        List<CatalogData.Partition> partitions = table.partitions();
        List<ColumnSpec> columns = table.columns();
        Path tableDir = table.tableDir();

        // No where clause on this table -> read all partitions.
        if (filters.isEmpty()) {
            List<Path> allPartitions = new ArrayList<>(partitions.size());
            for (int p = 0; p < partitions.size(); p++) {
                CatalogData.Partition partition = partitions.get(p);
                allPartitions.add(tableDir.resolve(partition.dataFile()));
                LOGGER.debug("partition={} decision=READ reason=noPredicate", p);
            }
            int total = partitions.size();
            ScanStats stats = new ScanStats(total, total, 0);
            logScanStats(tableName, stats, version);
            return new TablePipeline(new ScanOperator(tableName, columns, allPartitions), stats, version);
        }

        // Prune on this table's first filter. Later filters wrap the same scan.
        Predicate predicate = filters.getFirst();
        ColumnSpec predicateColumn = columns.get(columnIndex(columns, predicate.columnName(), tableName));
        Comparison comparison = predicate.comparison();
        Object constant = predicate.constant();

        List<Path> surviving = new ArrayList<>();
        int partitionsTotal = partitions.size();
        int partitionsPruned = 0;

        boolean[] mayMatch = Pruner.mayMatch(
                partitions, predicate.columnName(), comparison, constant, predicateColumn.type());

        for (int p = 0; p < partitionsTotal; p++) {
            CatalogData.Partition entry = partitions.get(p);
            CatalogData.Range range = entry.stats().get(predicate.columnName());
            boolean prune = !mayMatch[p];
            LOGGER.debug("op=select table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    csvSafe(tableName), csvSafe(predicate.columnName()), comparison, csvSafe(constant), p,
                    csvSafe(range.min()), csvSafe(range.max()), prune ? "PRUNED" : "READ");

            if (prune) {
                partitionsPruned++;
            } else {
                surviving.add(tableDir.resolve(entry.dataFile()));
            }
        }

        int partitionsKept = surviving.size();
        ScanStats stats = new ScanStats(partitionsTotal, partitionsKept, partitionsPruned);
        logScanStats(tableName, stats, version);

        Operator pipeline = new ScanOperator(tableName, columns, surviving);
        for (Predicate filter : filters) {
            int filterColumn = columnIndex(columns, filter.columnName(), tableName);
            ColumnSpec column = columns.get(filterColumn);
            pipeline = new FilterOperator(pipeline, new RowPredicate(
                    filterColumn, filter.comparison(), filter.constant(), column.type()));
        }
        return new TablePipeline(pipeline, stats, version);
    }

    /**
     * One equi-join between the new table and the tables already joined.
     * A second connecting predicate is rejected: the hash join takes one key pair.
     */
    private static JoinKeys connectingJoin(ColumnResolver columns, List<String> joinedNames, String newTable,
            List<JoinPredicate> joins, int buildWidth) {
        List<JoinPredicate> connecting = new ArrayList<>();
        JoinKeys keys = null;
        for (JoinPredicate join : joins) {
            ColumnResolver.ResolvedColumn left = columns.resolve(join.leftColumn());
            ColumnResolver.ResolvedColumn right = columns.resolve(join.rightColumn());
            ColumnResolver.ResolvedColumn buildColumn = columnOnJoinedSide(left, right, joinedNames, newTable);
            ColumnResolver.ResolvedColumn probeColumn = columnOnNewTable(left, right, newTable);
            if (buildColumn == null || probeColumn == null) {
                continue;
            }
            connecting.add(join);
            keys = new JoinKeys(buildColumn.rowIndex(), probeColumn.rowIndex() - buildWidth);
        }
        if (connecting.isEmpty()) {
            throw new IllegalArgumentException(
                    "no join predicate connects " + newTable + " to " + joinedNames
                            + "; that would be a cross product, reorder the FROM list"
                            + " so each new table joins one already listed");
        }
        if (connecting.size() > 1) {
            throw new IllegalArgumentException(
                    "more than one join predicate connects " + newTable + " to " + joinedNames
                            + " (" + connecting + "); this engine accepts one equi-join predicate per added table");
        }
        return keys;
    }

    // The build side is the tables already joined, so its key is that column's index in the concatenated row.
    private static ColumnResolver.ResolvedColumn columnOnJoinedSide(ColumnResolver.ResolvedColumn left,
            ColumnResolver.ResolvedColumn right, List<String> joinedNames, String newTable) {
        if (left.tableName().equals(newTable) && joinedNames.contains(right.tableName())) {
            return right;
        }
        if (right.tableName().equals(newTable) && joinedNames.contains(left.tableName())) {
            return left;
        }
        return null;
    }

    private static ColumnResolver.ResolvedColumn columnOnNewTable(ColumnResolver.ResolvedColumn left,
            ColumnResolver.ResolvedColumn right, String newTable) {
        if (left.tableName().equals(newTable)) {
            return left;
        }
        if (right.tableName().equals(newTable)) {
            return right;
        }
        return null;
    }

    private static void logScanStats(String tableName, ScanStats stats, int version) {
        LOGGER.debug("op=select table={} partitionsTotal={} partitionsRead={} partitionsPruned={} version={}",
                csvSafe(tableName), stats.partitionsTotal(), stats.partitionsRead(), stats.partitionsPruned(), version);
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

    private record PinnedTable(String name, int version, List<ColumnSpec> columns,
            List<CatalogData.Partition> partitions, Path tableDir) {
    }

    private record TablePipeline(Operator operator, ScanStats stats, int version) {
    }

    private record JoinKeys(int buildKey, int probeKey) {
    }
}
