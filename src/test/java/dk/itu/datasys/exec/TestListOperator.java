package dk.itu.datasys.exec;

import java.util.List;

/**
 * A stub child for operator tests: serves rows from a list, and records whether it was opened and
 * closed, so a test can assert that an operator passes those calls down to its child.
 */
final class TestListOperator implements Operator {

    private final List<Object[]> rows;
    private int nextIndex;
    private boolean opened;
    private boolean closed;

    TestListOperator(List<Object[]> rows) {
        this.rows = rows;
    }

    @Override
    public void open() {
        opened = true;
        nextIndex = 0;
    }

    @Override
    public Object[] next() {
        return nextIndex < rows.size() ? rows.get(nextIndex++) : null;
    }

    @Override
    public void close() {
        closed = true;
    }

    boolean wasOpened() {
        return opened;
    }

    boolean wasClosed() {
        return closed;
    }
}
