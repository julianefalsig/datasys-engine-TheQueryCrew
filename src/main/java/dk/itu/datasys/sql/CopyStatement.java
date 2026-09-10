package dk.itu.datasys.sql;

public record CopyStatement(String tableName, String csvFilePath)
        implements Statement {
}
