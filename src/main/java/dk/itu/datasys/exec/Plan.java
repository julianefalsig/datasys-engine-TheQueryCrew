package dk.itu.datasys.exec;

import dk.itu.datasys.storage.ScanStats;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned SELECT: the operator tree, and one pinned catalog snapshot per FROM table.
 * {@code stats} is the leftmost table. {@code tableStats} and {@code versions} follow the FROM list.
 */
public record Plan(Operator root, ScanStats stats, List<ScanStats> tableStats, List<Integer> versions) {

    public Plan {
        tableStats = List.copyOf(tableStats);
        versions = List.copyOf(versions);
        if (tableStats.isEmpty() || tableStats.size() != versions.size()) {
            throw new IllegalArgumentException("a plan pins one catalog version and one ScanStats per table");
        }
    }

    // Drain the plan => execute it and return the rows.
    // Opens the root, pulls every row, then closes.
    public List<Object[]> drain() {
        List<Object[]> rows = new ArrayList<>();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null) {
                rows.add(row);
            }
        } finally {
            root.close();
        }
        return rows;
    }
}
