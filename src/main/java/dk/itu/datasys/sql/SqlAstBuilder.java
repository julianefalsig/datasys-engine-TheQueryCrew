package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;

import java.util.List;

public final class SqlAstBuilder extends SqlBaseVisitor<Object> {

    @Override
    public List<Statement> visitScript(SqlParser.ScriptContext ctx) {
        throw new UnsupportedOperationException("visitScript");
    }

    @Override
    public CreateTableStatement visitCreateTable(SqlParser.CreateTableContext ctx) {
        throw new UnsupportedOperationException("visitCreateTable");
    }

    @Override
    public ColumnSpec visitColumnDef(SqlParser.ColumnDefContext ctx) {
        throw new UnsupportedOperationException("visitColumnDef");
    }

    @Override
    public ColumnType visitColumnType(SqlParser.ColumnTypeContext ctx) {
        throw new UnsupportedOperationException("visitColumnType");
    }

    @Override
    public CopyStatement visitCopy(SqlParser.CopyContext ctx) {
        throw new UnsupportedOperationException("visitCopy");
    }

    @Override
    public SelectStatement visitSelect(SqlParser.SelectContext ctx) {
        throw new UnsupportedOperationException("visitSelect");
    }

    @Override
    public Predicate visitPredicate(SqlParser.PredicateContext ctx) {
        throw new UnsupportedOperationException("visitPredicate");
    }

    @Override
    public Object visitLiteral(SqlParser.LiteralContext ctx) {
        throw new UnsupportedOperationException("visitLiteral");
    }
}
