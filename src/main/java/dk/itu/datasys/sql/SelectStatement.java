package dk.itu.datasys.sql;

import java.util.Optional;

public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement {
}
