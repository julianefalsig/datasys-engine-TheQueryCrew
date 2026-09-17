package dk.itu.datasys.exec;

import dk.itu.datasys.storage.ScanStats;

import java.util.ArrayList;
import java.util.List;

/** A planned SELECT: the operator tree to run, plus the prune/read stats that produced it. */
public record Plan(Operator root, ScanStats stats) {

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
