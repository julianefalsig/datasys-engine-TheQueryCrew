package dk.itu.datasys.storage;

/**
 * A commit found its table at another version than the one the statement started from: someone
 * else committed in between. First committer wins, so this statement aborts and changes nothing.
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
