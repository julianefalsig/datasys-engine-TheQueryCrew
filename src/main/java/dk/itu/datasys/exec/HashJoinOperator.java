package dk.itu.datasys.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Inner equi-join: builds a hash table from the build child, streams the probe child. */
public final class HashJoinOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;

    // A key maps to a list, because the build side can hold the same key more than once.
    private final Map<Object, List<Object[]>> table = new HashMap<>();

    // The cursor: the probe row being joined, its build matches, and the next match to emit.
    private Object[] probeRow;
    private List<Object[]> matches = List.of();
    private int matchIndex;

    private int rowsBuild;
    private int rowsProbe;
    private int rowsOut;

    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
    }

    // The whole build side is drained here, before the first probe row is pulled.
    @Override
    public void open() {
        build.open();
        Object[] row;
        while ((row = build.next()) != null) {
            rowsBuild++;
            table.computeIfAbsent(row[buildKey], key -> new ArrayList<>()).add(row);
        }
        probe.open();
    }

    // A probe row with k matches emits k rows, one per call, so the cursor survives between calls.
    @Override
    public Object[] next() {
        while (matchIndex >= matches.size()) {
            probeRow = probe.next();
            if (probeRow == null) {
                return null;
            }
            rowsProbe++;
            // A probe row without a partner is dropped: this is an inner join.
            matches = table.getOrDefault(probeRow[probeKey], List.of());
            matchIndex = 0;
        }
        rowsOut++;
        return concat(matches.get(matchIndex++), probeRow);
    }

    @Override
    public void close() {
        LOGGER.debug("op=hashJoin rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
        build.close();
        probe.close();
    }

    // The build row's columns, then the probe row's.
    private static Object[] concat(Object[] buildRow, Object[] probeRow) {
        Object[] joined = new Object[buildRow.length + probeRow.length];
        System.arraycopy(buildRow, 0, joined, 0, buildRow.length);
        System.arraycopy(probeRow, 0, joined, buildRow.length, probeRow.length);
        return joined;
    }
}
