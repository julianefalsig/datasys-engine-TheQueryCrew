package dk.itu.datasys.storage;

/**
 * What one DELETE did. {@code version} is the version it committed, or the current one when nothing
 * matched and no new version was written.
 */
public record DeleteResult(int version, int rowsDeleted, int partitionsRewritten, int partitionsCarried) {
}
