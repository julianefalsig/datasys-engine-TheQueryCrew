package dk.itu.datasys.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pulls rows from its child and passes on the ones the predicate accepts. */
public final class FilterOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Operator child;
    private final RowPredicate predicate;
    private int rowsIn;
    private int rowsOut;

    public FilterOperator(Operator child, RowPredicate predicate) {
        this.child = child;
        this.predicate = predicate;
    }

    @Override
    public void open() {
        child.open();
    }

    // Keeps pulling until a row passes, so one next() on the filter can cost many on the child.
    @Override
    public Object[] next() {
        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            if (predicate.test(row)) {
                rowsOut++;
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() {
        LOGGER.debug("op=filter rowsIn={} rowsOut={}", rowsIn, rowsOut);
        child.close();
    }
}
