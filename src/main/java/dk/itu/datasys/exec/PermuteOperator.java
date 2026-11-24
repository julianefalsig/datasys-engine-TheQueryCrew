package dk.itu.datasys.exec;

/** Reorders a row so column {@code i} comes from the child column {@code sources[i]}. */
/** Necessary because we may swap around build- and probe side such that the column order becomes wrong */
final class PermuteOperator implements Operator {

    private final Operator child;
    private final int[] sources;

    PermuteOperator(Operator child, int[] sources) {
        this.child = child;
        this.sources = sources.clone();
    }

    Operator child() {
        return child;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] row = child.next();
        if (row == null) {
            return null;
        }
        Object[] out = new Object[sources.length];
        for (int i = 0; i < sources.length; i++) {
            out[i] = row[sources[i]];
        }
        return out;
    }

    @Override
    public void close() {
        child.close();
    }
}
