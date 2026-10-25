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

public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final Path dataDirectory;
    private final int defaultMaxRowsPerPartition;
    private final Map<String, CatalogData> catalogs = new ConcurrentHashMap<>();

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

            commit(tableName, catalog);
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
            int partitionIndex = current.maxPartitionNumber + 1;
            int partitionsWritten = 0;
            for (int rowStart = 0; rowStart < rows.size(); rowStart += current.maxRowsPerPartition) {
                int rowEnd = Math.min(rowStart + current.maxRowsPerPartition, rows.size());
                partitions.add(writePartition(
                        tableName, columns, rows.subList(rowStart, rowEnd), partitionIndex));
                partitionIndex++;
                partitionsWritten++;
            }

            CatalogData next = new CatalogData();
            next.version = current.version + 1;
            next.maxPartitionNumber = partitionIndex - 1;
            next.maxRowsPerPartition = current.maxRowsPerPartition;
            next.columns.addAll(columns);
            next.partitions.addAll(partitions);
            commit(tableName, next);

            long durationMs = System.currentTimeMillis() - start;
            LOGGER.debug("op=copyFile table={} file={} rows={} partitions={} version={} durationMs={}",
                    csvSafe(tableName), csvSafe(csvFilePath), rows.size(), partitionsWritten, next.version, durationMs);
            return next.version;
        } catch (RuntimeException e) {
            throw failed("copyFile", "table=%s file=%s durationMs=%d"
                    .formatted(tableName, csvFilePath, System.currentTimeMillis() - start), e);
        }
    }

    /** Disk first, then the map. A failed rename leaves the previous catalog in place. */
    private void commit(String tableName, CatalogData next) {
        CatalogStore.write(tableDirectory(tableName), next);
        catalogs.put(tableName, next);
    }

    private CatalogData.Partition writePartition(String tableName, List<ColumnSpec> columns,
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
            LOGGER.debug("op=copyFile table={} partition={} column={} min={} max={}",
                    csvSafe(tableName), partitionIndex, csvSafe(column.name()), csvSafe(stats.min), csvSafe(stats.max));
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
