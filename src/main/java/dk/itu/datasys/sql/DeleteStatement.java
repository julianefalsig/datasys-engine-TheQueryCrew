package dk.itu.datasys.sql;

import java.util.Optional;

public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement {
}
