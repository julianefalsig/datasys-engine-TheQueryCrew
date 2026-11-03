package dk.itu.datasys.sql;

/** Deletes partition files that no catalog entry points at. */
public record CleanOrphansStatement() implements Statement {
}
