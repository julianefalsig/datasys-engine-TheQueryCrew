package dk.itu.datasys.exec;

import dk.itu.datasys.sql.ColumnResolver;
import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.CatalogData;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;
import dk.itu.datasys.storage.Pruner;
import dk.itu.datasys.storage.RowPredicate;
import dk.itu.datasys.storage.ScanStats;
import dk.itu.datasys.storage.StorageEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a bound SELECT into a Volcano plan.
 * Partition pruning happens here from catalog min/max only
 * - no data file is opened.
 * {@link JoinMode#OPTIMIZED} orders joins by the smallest estimate and lets the smaller input build (greedy algorithm).
 * {@link JoinMode#SYNTAX} is week 10: left to right in FROM-list order. Either way, SELECT * comes
 * back in FROM-list column order, and a table with no connecting predicate is rejected.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement select) {
        return plan(select, JoinMode.OPTIMIZED);
    }

    public Plan plan(SelectStatement select, JoinMode mode) {
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

        List<TablePipeline> pipelines = new ArrayList<>();
        List<ScanStats> tableStats = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();
        for (PinnedTable table : pinned) {
            TablePipeline planned = planTable(table, filtersOn(table.name(), select.filters(), columns));
            pipelines.add(planned);
            tableStats.add(planned.stats());
            versions.add(planned.version());
        }
        if (pinned.size() == 1) {
            return new Plan(pipelines.getFirst().operator(), tableStats.getFirst(), tableStats, versions);
        }

        List<JoinOrder.Card> cards = new ArrayList<>();
        for (int i = 0; i < pinned.size(); i++) {
            cards.add(new JoinOrder.Card(pinned.get(i).name(), pipelines.get(i).estimate(), pipelines.get(i).ndv()));
        }
        List<JoinOrder.Join> joins = mode == JoinMode.SYNTAX
                ? JoinOrder.syntax(cards, select.joins(), columns)
                : JoinOrder.greedy(cards, select.joins(), columns);

        Map<String, Integer> width = new HashMap<>();
        Map<String, Integer> fromOffset = new HashMap<>();
        int offset = 0;
        for (PinnedTable table : pinned) {
            width.put(table.name(), table.columns().size());
            fromOffset.put(table.name(), offset);
            offset += table.columns().size();
        }
        Map<String, Built> leaves = new HashMap<>();
        for (int i = 0; i < pinned.size(); i++) {
            String name = pinned.get(i).name();
            leaves.put(name, Built.leaf(name, pipelines.get(i).operator(), width.get(name), fromOffset.get(name)));
        }
        Built accumulated = null;
        int joinNumber = 1;
        for (JoinOrder.Join join : joins) {
            Built build = take(join.buildTables(), leaves, accumulated);
            Built probe = take(join.probeTables(), leaves, accumulated);
            Operator operator = new HashJoinOperator(build.operator, probe.operator,
                    keyIndex(build, join.buildColumn(), columns),
                    keyIndex(probe, join.probeColumn(), columns),
                    joinNumber, String.join(",", build.tables) + "," + String.join(",", probe.tables),
                    join.estRows());
            accumulated = Built.joined(build, probe, operator);
            joinNumber++;
        }
        return new Plan(permuteToFromOrder(accumulated), tableStats.getFirst(), tableStats, versions);
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

        List<CatalogData.Partition> surviving;
        ScanStats stats;
        if (filters.isEmpty()) {
            surviving = partitions;
            for (int p = 0; p < partitions.size(); p++) {
                LOGGER.debug("table={} partition={} decision=READ reason=noPredicate", csvSafe(tableName), p);
            }
            int total = partitions.size();
            stats = new ScanStats(total, total, 0);
        } else {
            Predicate predicate = filters.getFirst();
            ColumnSpec predicateColumn = columns.get(columnIndex(columns, predicate.columnName(), tableName));
            Comparison comparison = predicate.comparison();
            Object constant = predicate.constant();
            boolean[] mayMatch = Pruner.mayMatch(
                    partitions, predicate.columnName(), comparison, constant, predicateColumn.type());
            surviving = new ArrayList<>();
            int partitionsPruned = 0;
            for (int p = 0; p < partitions.size(); p++) {
                CatalogData.Partition entry = partitions.get(p);
                CatalogData.Range range = entry.stats().get(predicate.columnName());
                boolean prune = !mayMatch[p];
                LOGGER.debug(
                        "op=select table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        csvSafe(tableName), csvSafe(predicate.columnName()), comparison, csvSafe(constant), p,
                        csvSafe(range.min()), csvSafe(range.max()), prune ? "PRUNED" : "READ");
                if (prune) {
                    partitionsPruned++;
                } else {
                    surviving.add(entry);
                }
            }
            stats = new ScanStats(partitions.size(), surviving.size(), partitionsPruned);
        }
        logScanStats(tableName, stats, version);

        List<Path> files = new ArrayList<>(surviving.size());
        for (CatalogData.Partition partition : surviving) {
            files.add(tableDir.resolve(partition.dataFile()));
        }
        Operator pipeline = new ScanOperator(tableName, columns, files);
        double estimate = Estimator.baseRows(surviving);
        for (Predicate filter : filters) {
            int filterColumn = columnIndex(columns, filter.columnName(), tableName);
            ColumnSpec column = columns.get(filterColumn);
            pipeline = new FilterOperator(pipeline, new RowPredicate(
                    filterColumn, filter.comparison(), filter.constant(), column.type()));
            Object[] bounds = bounds(surviving, filter.columnName(), column.type());
            estimate = Estimator.filterRows(estimate, filter.comparison(), filter.constant(),
                    bounds[0], bounds[1], columnNdv(surviving, filter.columnName()), column.type());
        }
        return new TablePipeline(pipeline, stats, version, estimate, columnNdvs(surviving, columns));
    }

    /** Max of the partition NDVs. A missing count is that partition's row count. */
    private static Map<String, Long> columnNdvs(List<CatalogData.Partition> partitions, List<ColumnSpec> columns) {
        Map<String, Long> ndv = new LinkedHashMap<>();
        for (ColumnSpec column : columns) {
            ndv.put(column.name(), columnNdv(partitions, column.name()));
        }
        return Map.copyOf(ndv);
    }

    private static Long columnNdv(List<CatalogData.Partition> partitions, String column) {
        if (partitions.isEmpty()) {
            return 0L;
        }
        List<Long> counts = new ArrayList<>(partitions.size());
        for (CatalogData.Partition partition : partitions) {
            Long stored = partition.stats().get(column).ndv();
            counts.add(stored == null ? (long) partition.rowCount() : stored);
        }
        long max = counts.getFirst();
        for (Long count : counts) {
            max = Math.max(max, count);
        }
        return max;
    }

    private static Object[] bounds(List<CatalogData.Partition> partitions, String column, ColumnType type) {
        Object min = null;
        Object max = null;
        for (CatalogData.Partition partition : partitions) {
            CatalogData.Range range = partition.stats().get(column);
            if (min == null || Comparison.LESS_THAN.matches(range.min(), min, type)) {
                min = range.min();
            }
            if (max == null || Comparison.GREATER_THAN.matches(range.max(), max, type)) {
                max = range.max();
            }
        }
        return new Object[] { min, max };
    }

    private static Built take(List<String> tables, Map<String, Built> leaves, Built accumulated) {
        if (tables.size() == 1 && leaves.containsKey(tables.getFirst())) {
            return leaves.remove(tables.getFirst());
        }
        return accumulated;
    }

    /** Where this side stores the named column, after earlier build sides have moved it. */
    private static int keyIndex(Built side, String column, ColumnResolver columns) {
        int schemaIndex = columns.resolve(column).rowIndex();
        for (int physical = 0; physical < side.fromIndex.length; physical++) {
            if (side.fromIndex[physical] == schemaIndex) {
                return physical;
            }
        }
        throw new IllegalArgumentException("column " + column + " is not on this side of the join");
    }

    private static Operator permuteToFromOrder(Built built) {
        int width = 0;
        for (int from : built.fromIndex) {
            width = Math.max(width, from + 1);
        }
        int[] sources = new int[width];
        boolean identity = built.fromIndex.length == width;
        for (int physical = 0; physical < built.fromIndex.length; physical++) {
            sources[built.fromIndex[physical]] = physical;
            identity = identity && built.fromIndex[physical] == physical;
        }
        if (identity) {
            return built.operator;
        }
        return new PermuteOperator(built.operator, sources);
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

    private record TablePipeline(Operator operator, ScanStats stats, int version, double estimate,
            Map<String, Long> ndv) {
    }

    private record Built(List<String> tables, Operator operator, int[] fromIndex) {
        static Built leaf(String name, Operator operator, int columns, int fromOffset) {
            int[] fromIndex = new int[columns];
            for (int i = 0; i < columns; i++) {
                fromIndex[i] = fromOffset + i;
            }
            return new Built(List.of(name), operator, fromIndex);
        }

        static Built joined(Built build, Built probe, Operator operator) {
            List<String> tables = new ArrayList<>(build.tables);
            tables.addAll(probe.tables);
            int[] fromIndex = new int[build.fromIndex.length + probe.fromIndex.length];
            System.arraycopy(build.fromIndex, 0, fromIndex, 0, build.fromIndex.length);
            System.arraycopy(probe.fromIndex, 0, fromIndex, build.fromIndex.length, probe.fromIndex.length);
            return new Built(List.copyOf(tables), operator, fromIndex);
        }
    }
}
