package dk.itu.datasys.sql;

import dk.itu.datasys.storage.Comparison;

public record Predicate(String columnName, Comparison comparison, Object constant) {
}
