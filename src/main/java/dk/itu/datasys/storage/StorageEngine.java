package dk.itu.datasys.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final Path dataDirectory;
    private final int defaultMaxRowsPerPartition;
    private final Map<String, CatalogData> catalogs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> nextPartitionNumbers = new ConcurrentHashMap<>();
    private final Map<String, Object> commitLocks = new ConcurrentHashMap<>();

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int defaultMaxRowsPerPartition) {
        ensureLogContext();
        this.dataDirectory = dataDirectory;
        this.defaultMaxRowsPerPartition = defaultMaxRowsPerPartition;
        try {
            Files.createDirectories(dataDirectory);
            loadCatalogs();
        } catch (IOException e) {
            throw failed("open", "dir=" + dataDirectory, new UncheckedIOException(e));
        } catch (RuntimeException e) {
            throw failed("open", "dir=" + dataDirectory, e);
        }
    }

  
    private static void ensureLogContext() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString().substring(0, 8));
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
    }

    private void loadCatalogs() {
        try (DirectoryStream<Path> tableDirs = Files.newDirectoryStream(dataDirectory, Files::isDirectory)) {
            for (Path tableDir : tableDirs) {
                if (CatalogStore.exists(tableDir)) {
                    catalogs.put(tableDir.getFileName().toString(), CatalogStore.read(tableDir));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the catalog version committed for {@code tableName} */
    public int createTable(String tableName, List<ColumnSpec> columns) {
        try {
            if (catalogs.containsKey(tableName)) {
                throw new IllegalArgumentException("table already exists: " + tableName);
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("a table needs at least one column");
            }
            long distinctNames = columns.stream().map(ColumnSpec::name).distinct().count();
            if (distinctNames != columns.size()) {
                throw new IllegalArgumentException("duplicate column names in schema for table: " + tableName);
            }

            CatalogData catalog = new CatalogData();
            catalog.version = 0;
            catalog.maxPartitionNumber = -1;
            catalog.maxRowsPerPartition = defaultMaxRowsPerPartition;
            catalog.columns.addAll(List.copyOf(columns));

            // Base -1: the table must still not exist when the commit runs.
            commit(tableName, -1, catalog);
            LOGGER.debug("op=createTable table={} columns={} version={}",
                    csvSafe(tableName), columns.size(), catalog.version);
            return catalog.version;
        } catch (RuntimeException e) {
            throw failed("createTable", "table=" + tableName, e);
        }
    }

    /** @return the catalog version this copy committed */
    public int copyFile(String tableName, String csvFilePath) {
        long start = System.currentTimeMillis();
        try {
            CatalogData current = requireCatalog(tableName);
            List<ColumnSpec> columns = current.columns;
            List<Object[]> rows = readCsv(csvFilePath, columns);

            List<CatalogData.Partition> partitions = new ArrayList<>(current.partitions);
            int maxPartitionNumber = current.maxPartitionNumber;
            int partitionsWritten = 0;
            for (int rowStart = 0; rowStart < rows.size(); rowStart += current.maxRowsPerPartition) {
                int rowEnd = Math.min(rowStart + current.maxRowsPerPartition, rows.size());
                maxPartitionNumber = reservePartitionNumber(tableName, current);
                partitions.add(writePartition(
                        "copyFile", tableName, columns, rows.subList(rowStart, rowEnd), maxPartitionNumber));
                partitionsWritten++;
            }

            CatalogData next = new CatalogData();
            next.version = current.version + 1;
            next.maxPartitionNumber = maxPartitionNumber;
            next.maxRowsPerPartition = current.maxRowsPerPartition;
            next.columns.addAll(columns);
            next.partitions.addAll(partitions);
            commit(tableName, current.version, next);

            long durationMs = System.currentTimeMillis() - start;
            LOGGER.debug("op=copyFile table={} file={} rows={} partitions={} version={} durationMs={}",
                    csvSafe(tableName), csvSafe(csvFilePath), rows.size(), partitionsWritten, next.version, durationMs);
            return next.version;
        } catch (RuntimeException e) {
            throw failed("copyFile", "table=%s file=%s durationMs=%d"
                    .formatted(tableName, csvFilePath, System.currentTimeMillis() - start), e);
        }
    }

    /** Deletes every row of {@code tableName}: every partition drops out, and no file is written. */
    public DeleteResult deleteAll(String tableName) {
        return delete(tableName, null);
    }

    /** Deletes the rows of {@code tableName} where {@code columnName comparison constant} holds. */
    public DeleteResult delete(String tableName, String columnName, Comparison comparison, Object constant) {
        RowPredicate predicate;
        try {
            List<ColumnSpec> columns = requireCatalog(tableName).columns;
            int columnIndex = columnIndex(columns, columnName, tableName);
            predicate = new RowPredicate(columnIndex, comparison, constant, columns.get(columnIndex).type());
        } catch (RuntimeException e) {
            throw failed("delete", "table=" + tableName, e);
        }
        return delete(tableName, predicate);
    }

    /**
     * Copy-on-write: a partition whose min/max rules out a match, or that turns out to hold none, is
     * carried over by reference. Any other partition is read, and the rows that survive go into a new
     * file; the original is never touched. A partition with no surviving rows drops out of the list.
     * {@code predicate} is null for a DELETE without WHERE, which matches every row.
     */
    private DeleteResult delete(String tableName, RowPredicate predicate) {
        long start = System.currentTimeMillis();
        try {
            CatalogData current = requireCatalog(tableName);
            List<ColumnSpec> columns = current.columns;

            // The rewrite set: the same call the planner prunes with, read the other way round.
            // Without a WHERE every partition may match.
            boolean[] mayMatch = predicate == null
                    ? null
                    : Pruner.mayMatch(current.partitions, columns.get(predicate.columnIndex()).name(),
                            predicate.comparison(), predicate.constant(), predicate.columnType());

            List<CatalogData.Partition> partitions = new ArrayList<>(current.partitions.size());
            int maxPartitionNumber = current.maxPartitionNumber;
            int rowsDeleted = 0;
            int partitionsRewritten = 0;
            int partitionsCarried = 0;

            for (int p = 0; p < current.partitions.size(); p++) {
                CatalogData.Partition partition = current.partitions.get(p);

                if (mayMatch != null && !mayMatch[p]) {
                    partitions.add(partition);
                    partitionsCarried++;
                    LOGGER.debug("op=delete table={} partition={} decision=CARRY reason=pruned",
                            csvSafe(tableName), p);
                    continue;
                }

                List<List<Object>> data = PartitionFile.readAllColumns(
                        tableDirectory(tableName).resolve(partition.dataFile()), columns);
                List<Object[]> kept = new ArrayList<>();
                for (int r = 0; r < partition.rowCount(); r++) {
                    Object[] row = new Object[columns.size()];
                    for (int c = 0; c < columns.size(); c++) {
                        row[c] = data.get(c).get(r);
                    }
                    // The same row test the filter operator applies to a SELECT.
                    boolean matches = predicate == null || predicate.test(row);
                    if (!matches) {
                        kept.add(row);
                    }
                }

                int deleted = partition.rowCount() - kept.size();
                if (deleted == 0) {
                    // It may have contained a match, but did not: the original is still exactly right.
                    partitions.add(partition);
                    partitionsCarried++;
                    LOGGER.debug("op=delete table={} partition={} decision=CARRY reason=noMatch",
                            csvSafe(tableName), p);
                    continue;
                }

                rowsDeleted += deleted;
                partitionsRewritten++;
                if (!kept.isEmpty()) {
                    maxPartitionNumber = reservePartitionNumber(tableName, current);
                    partitions.add(writePartition("delete", tableName, columns, kept, maxPartitionNumber));
                }
                LOGGER.debug("op=delete table={} partition={} decision=REWRITE rowsDeleted={} rowsKept={}",
                        csvSafe(tableName), p, deleted, kept.size());
            }

            // Commit only if rows were deleted. If nothing matched, no file was written and the version stays.
            int version = current.version;
            if (rowsDeleted > 0) {
                CatalogData next = new CatalogData();
                next.version = current.version + 1;
                next.maxPartitionNumber = maxPartitionNumber;
                next.maxRowsPerPartition = current.maxRowsPerPartition;
                next.columns.addAll(columns);
                next.partitions.addAll(partitions);
                commit(tableName, current.version, next);
                version = next.version;
            }

            long durationMs = System.currentTimeMillis() - start;
            LOGGER.debug("op=delete table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                    csvSafe(tableName), rowsDeleted, partitionsRewritten, partitionsCarried, version, durationMs);
            return new DeleteResult(version, rowsDeleted, partitionsRewritten, partitionsCarried);
        } catch (RuntimeException e) {
            throw failed("delete", "table=%s durationMs=%d"
                    .formatted(tableName, System.currentTimeMillis() - start), e);
        }
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName, String tableName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName + " on table " + tableName);
    }

    /**
     * First committer wins. {@code base} is the version the statement started from, or -1 for a table
     * that does not exist yet. If the committed version has moved since, the statement throws
     * {@link ConflictException}; the partitions it wrote are never reachable and become orphans.
     * Validate, save and swap run under the table's lock, one writer per table at a time. Disk first,
     * then the map, so a failed rename leaves the previous catalog in place.
     */
    private void commit(String tableName, int base, CatalogData next) {
        synchronized (commitLocks.computeIfAbsent(tableName, t -> new Object())) {
            CatalogData committed = catalogs.get(tableName);
            int current = committed == null ? -1 : committed.version;
            if (current != base) {
                LOGGER.debug("conflict table={} base={} current={}", csvSafe(tableName), base, current);
                throw new ConflictException(tableName, base, current);
            }
            CatalogStore.write(tableDirectory(tableName), next);
            catalogs.put(tableName, next);
        }
    }

    /**
     * The next partition file number for {@code tableName}, never handed out twice in this run. Two
     * writers that start from the same version would otherwise both pick maxPartitionNumber + 1, and
     * the second would overwrite the first one's file before either commits. Numbers taken by an
     * aborted statement stay unused; after a restart the count resumes from the committed catalog,
     * so at worst an orphan file is overwritten, never a referenced one.
     */
    private int reservePartitionNumber(String tableName, CatalogData current) {
        return nextPartitionNumbers
                .computeIfAbsent(tableName, t -> new AtomicInteger(current.maxPartitionNumber + 1))
                .getAndIncrement();
    }

    private CatalogData.Partition writePartition(String op, String tableName, List<ColumnSpec> columns,
                                 List<Object[]> partitionRows, int partitionIndex) {
        int columnCount = columns.size();
        List<List<Object>> columnData = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            columnData.add(new ArrayList<>(partitionRows.size()));
        }
        for (Object[] row : partitionRows) {
            for (int c = 0; c < columnCount; c++) {
                columnData.get(c).add(row[c]);
            }
        }

        String dataFileName = "partition-%d.bin".formatted(partitionIndex);
        PartitionFile.write(tableDirectory(tableName).resolve(dataFileName), columns, columnData, partitionRows.size());

        Map<String, CatalogData.Range> statsByColumn = new LinkedHashMap<>();
        for (int c = 0; c < columnCount; c++) {
            ColumnSpec column = columns.get(c);
            ColumnStats stats = ColumnStats.of(columnData.get(c), column.type());
            statsByColumn.put(column.name(), new CatalogData.Range(stats.min, stats.max));
            LOGGER.debug("op={} table={} partition={} column={} min={} max={}",
                    op, csvSafe(tableName), partitionIndex, csvSafe(column.name()), csvSafe(stats.min), csvSafe(stats.max));
        }
        return new CatalogData.Partition(dataFileName, partitionRows.size(), statsByColumn);
    }

    //method used for the binder. The table's schema, in column order. Throws IllegalArgumentException if the table is unknown.

    public List<ColumnSpec> schema(String tableName) {
        try {
            List<ColumnSpec> columns = List.copyOf(requireCatalog(tableName).columns);
            LOGGER.debug("op=schema table={} columns={}", csvSafe(tableName), columns.size());
            return columns;
        } catch (RuntimeException e) {
            throw failed("schema", "table=" + tableName, e);
        }
    }

    /** Live catalog for planning; callers must not mutate it. */
    public CatalogData catalog(String tableName) {
        return requireCatalog(tableName);
    }

    /** Directory that holds this table's catalog and partition files. */
    public Path tableDirectory(String tableName) {
        return dataDirectory.resolve(tableName);
    }

    private static <E extends RuntimeException> E failed(String op, String context, E e) {
        LOGGER.error("op={} {} outcome=FAILED error={} message={}",
                op, csvSafe(context), e.getClass().getSimpleName(), csvSafe(e.getMessage()));
        return e;
    }

    private static String csvSafe(Object value) {
        return value == null ? "none" : String.valueOf(value).replace(',', ';').replaceAll("\\s+", " ");
    }

    private CatalogData requireCatalog(String tableName) {
        CatalogData catalog = catalogs.get(tableName);
        if (catalog == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return catalog;
    }

    private static List<Object[]> readCsv(String csvFilePath, List<ColumnSpec> columns) {
        List<Object[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                rows.add(CsvParser.parseLine(line, columns, csvFilePath, lineNumber));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
