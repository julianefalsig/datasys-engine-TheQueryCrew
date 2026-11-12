package dk.itu.datasys.sql;

import java.util.List;

public record SelectStatement(List<String> tableNames, List<Predicate> filters,
        List<JoinPredicate> joins) implements Statement {
}
