package dk.itu.datasys.exec;

/**
 * One stage of a Volcano-style pipeline. A consumer calls open() once, next() until it returns
 * null, then close(). Rows are pulled one at a time, so no stage ever holds the whole result.
 */
public interface Operator {

    void open();

    /** One row in schema column order, or null when the operator is exhausted. */
    Object[] next();

    void close();
}
