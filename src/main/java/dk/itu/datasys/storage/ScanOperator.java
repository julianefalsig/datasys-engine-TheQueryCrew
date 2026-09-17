package dk.itu.datasys.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;


//Reads every row of the partitions it is handed, in order. which partitions are worth reading is settled by the planner, before this runs.
public final class ScanOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    private final String tableName;
    private final List<ColumnSpec> columns;
    private final List<Path> partitionFiles;

    private int partitionIndex;
    private List<List<Object>> currentColumns = List.of();
    private int rowCount;
    private int rowIndex;
    private int rowsOut;

    public ScanOperator(String tableName, List<ColumnSpec> columns, List<Path> partitionFiles) {
        this.tableName = tableName;
        this.columns = List.copyOf(columns);
        this.partitionFiles = List.copyOf(partitionFiles);
    }

    @Override
    public void open() {
        partitionIndex = 0;
        currentColumns = List.of();
        rowCount = 0;
        rowIndex = 0;
        rowsOut = 0;
    }

    /**
     * A partition is opened only once the previous one is spent, and the file stays in the shape it
     * was stored in: one list per column. The row array is assembled here, for the one row being
     * handed out, so rows nobody pulls are never built.
     */
    @Override
    public Object[] next() {
        while (rowIndex == rowCount) {
            if (partitionIndex == partitionFiles.size()) {
                return null;
            }
            currentColumns = PartitionFile.readAllColumns(partitionFiles.get(partitionIndex), columns);
            rowCount = currentColumns.isEmpty() ? 0 : currentColumns.getFirst().size();
            partitionIndex++;
            rowIndex = 0;
        }
        Object[] row = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            row[c] = currentColumns.get(c).get(rowIndex);
        }
        rowIndex++;
        rowsOut++;
        return row;
    }

    @Override
    public void close() {
        LOGGER.debug("op=scan table={} partitions={} rowsOut={}", tableName, partitionFiles.size(), rowsOut);
        currentColumns = List.of();
        rowCount = 0;
    }
}
