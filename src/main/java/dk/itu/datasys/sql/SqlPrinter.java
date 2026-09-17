package dk.itu.datasys.sql;

import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.Comparison;

import java.math.BigDecimal;
import java.util.stream.Collectors;

public final class SqlPrinter {

    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement statement) {
        return switch (statement) {
            case CreateTableStatement create -> printCreate(create);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
        };
    }

    private static String printCreate(CreateTableStatement create) {
        String columns = create.columns().stream()
                .map(SqlPrinter::printColumn)
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + create.tableName() + " (" + columns + ");";
    }

    private static String printColumn(ColumnSpec column) {
        return column.name() + " " + column.type().name();
    }

    private static String printCopy(CopyStatement copy) {
        return "COPY " + copy.tableName() + " FROM '" + copy.csvFilePath() + "';";
    }

    private static String printSelect(SelectStatement select) {
        String sql = "SELECT * FROM " + select.tableName();
        if (select.where().isEmpty()) {
            return sql + ";";
        }
        Predicate where = select.where().get();
        return sql + " WHERE " + where.columnName() + " " + operator(where.comparison())
                + " " + literal(where.constant()) + ";";
    }

    private static String operator(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String literal(Object constant) {
        return switch (constant) {
            case String text -> "'" + text + "'";
            case Long number -> number.toString();
            case Double number -> formatDouble(number);
            default -> throw new IllegalStateException("unhandled literal type: " + constant.getClass().getName());
        };
    }

    /** Grammar is `-`? digits `.` digits — never scientific notation, always a decimal point. */
    private static String formatDouble(double value) {
        // BigDecimal.valueOf drops the sign of -0.0, but Double.equals distinguishes it from 0.0.
        if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0)) {
            return "-0.0";
        }
        String plain = BigDecimal.valueOf(value).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }
}
