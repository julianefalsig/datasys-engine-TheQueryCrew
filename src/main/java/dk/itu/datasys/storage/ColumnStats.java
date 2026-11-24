package dk.itu.datasys.storage;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-column min, max, and distinct count over a list of values. */
final class ColumnStats {

    final Object min;
    final Object max;
    final long ndv;

    private ColumnStats(Object min, Object max, long ndv) {
        this.min = min;
        this.max = max;
        this.ndv = ndv;
    }

    static ColumnStats of(List<Object> values, ColumnType type) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot compute min/max of an empty column");
        }
        Comparator<Object> comparator = comparatorFor(type);
        Object min = values.get(0);
        Object max = values.get(0);
        Set<Object> distinct = new HashSet<>();
        for (Object value : values) {
            distinct.add(value);
            if (comparator.compare(value, min) < 0) {
                min = value;
            }
            if (comparator.compare(value, max) > 0) {
                max = value;
            }
        }
        return new ColumnStats(min, max, distinct.size());
    }

    /**
     * The same value can sit in several partitions, so the sum would over-count and the max cannot.
     * A lower bound makes |R| · |S| / ndv too large rather than too small.
     */
    static long tableNdv(List<Long> partitionNdvs) {
        if (partitionNdvs.isEmpty()) {
            throw new IllegalArgumentException("table NDV needs at least one partition");
        }
        long max = Long.MIN_VALUE;
        for (Long ndv : partitionNdvs) {
            if (ndv == null) {
                throw new IllegalArgumentException("partition NDV must not be null");
            }
            if (ndv > max) {
                max = ndv;
            }
        }
        return max;
    }

    static Comparator<Object> comparatorFor(ColumnType type) {
        return switch (type) {
            case LONG -> (a, b) -> Long.compare((Long) a, (Long) b);
            case DOUBLE -> (a, b) -> Double.compare((Double) a, (Double) b);
            case STRING -> (a, b) -> ((String) a).compareTo((String) b);
        };
    }
}
