package dk.itu.datasys.storage;

/**
 * A commit found its table at another version, and the two commits rewrote the same original
 * partition. The statement aborts and changes nothing. Disjoint commits rebase instead.
 */
public final class ConflictException extends RuntimeException {

    private final int base;
    private final int current;

    public ConflictException(String tableName, int base, int current) {
        super("conflict table=%s base=%d current=%d".formatted(tableName, base, current));
        this.base = base;
        this.current = current;
    }

    public int base() {
        return base;
    }

    public int current() {
        return current;
    }
}
