package dk.itu.datasys.sql;

public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement {
}
