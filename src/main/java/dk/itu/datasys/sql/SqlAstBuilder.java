package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.Comparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqlAstBuilder extends SqlBaseVisitor<Object> {

    @Override
    public List<Statement> visitScript(SqlParser.ScriptContext ctx) {
        List<Statement> statements = new ArrayList<>();
        for (SqlParser.StatementContext statement : ctx.statement()) {
            statements.add((Statement) visit(statement));
        }
        return statements;
    }

    @Override
    public CreateTableStatement visitCreateTable(SqlParser.CreateTableContext ctx) {
        List<ColumnSpec> columns = new ArrayList<>();
        for (SqlParser.ColumnDefContext columnDef : ctx.columnDef()) {
            columns.add(visitColumnDef(columnDef));
        }
        return new CreateTableStatement(ctx.IDENTIFIER().getText(), columns);
    }

    @Override
    public ColumnSpec visitColumnDef(SqlParser.ColumnDefContext ctx) {
        return new ColumnSpec(ctx.IDENTIFIER().getText(), visitColumnType(ctx.columnType()));
    }

    @Override
    public ColumnType visitColumnType(SqlParser.ColumnTypeContext ctx) {
        return switch (ctx.getStart().getType()) {
            case SqlParser.STRING -> ColumnType.STRING;
            case SqlParser.LONG -> ColumnType.LONG;
            case SqlParser.DOUBLE -> ColumnType.DOUBLE;
            default -> throw new IllegalStateException("unhandled column type");
        };
    }

    @Override
    public CopyStatement visitCopy(SqlParser.CopyContext ctx) {
        return new CopyStatement(ctx.IDENTIFIER().getText(), unquote(ctx.STRING_LITERAL().getText()));
    }

    @Override
    public SelectStatement visitSelect(SqlParser.SelectContext ctx) {
        Optional<Predicate> where = ctx.predicate() == null
                ? Optional.empty()
                : Optional.of(visitPredicate(ctx.predicate()));
        return new SelectStatement(ctx.IDENTIFIER().getText(), where);
    }

    @Override
    public Predicate visitPredicate(SqlParser.PredicateContext ctx) {
        return new Predicate(
                ctx.IDENTIFIER().getText(),
                comparison(ctx.COMPARISON().getText()),
                visitLiteral(ctx.literal()));
    }

    @Override
    public Object visitLiteral(SqlParser.LiteralContext ctx) {
        String text = ctx.getStart().getText();
        return switch (ctx.getStart().getType()) {
            case SqlParser.STRING_LITERAL -> unquote(text);
            case SqlParser.LONG_LITERAL -> Long.parseLong(text);
            case SqlParser.DOUBLE_LITERAL -> Double.parseDouble(text);
            default -> throw new IllegalStateException("unhandled literal");
        };
    }

    private static String unquote(String stringLiteral) {
        return stringLiteral.substring(1, stringLiteral.length() - 1);
    }

    private static Comparison comparison(String operator) {
        return switch (operator) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("unhandled comparison: " + operator);
        };
    }
}
