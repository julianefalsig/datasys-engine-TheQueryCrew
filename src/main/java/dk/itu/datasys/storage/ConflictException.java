package dk.itu.datasys.storage;

/**
 * A commit found its table at another version, and the two commits rewrote the same original
 * partition. The statement aborts and changes nothing. Disjoint commits rebase instead.
 */
public final class ConflictException extends RuntimeException {

    public ConflictException(String tableName, int base, int current, String type) {
        super("conflict table=%s base=%d current=%d type=%s".formatted(tableName, base, current, type));
    }
}
