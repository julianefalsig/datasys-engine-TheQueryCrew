package dk.itu.datasys.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a partition file on behalf of tests outside this package. PartitionFile.write stays
 * package-private on purpose — only copyFile may create a partition, so the catalog always knows
 * about every file — and a test that needs something to read comes through here instead.
 */
public final class TestPartitions {

    private TestPartitions() {
    }

    public static Path write(Path directory, String fileName, List<ColumnSpec> columns, List<Object[]> rows) {
        List<List<Object>> columnData = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            List<Object> values = new ArrayList<>();
            for (Object[] row : rows) {
                values.add(row[c]);
            }
            columnData.add(values);
        }
        Path file = directory.resolve(fileName);
        PartitionFile.write(file, columns, columnData, rows.size());
        return file;
    }
}
