package dk.itu.datasys.sql;

import dk.itu.datasys.SqlParser;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlPrinterTest {

    private static final String TASK1_SQL = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    @Test
    void parsePrintRoundTripForEveryStatementShape() {
        SqlParser parser = new SqlParser();
        SqlPrinter printer = new SqlPrinter();
        List<Statement> statements = parser.parse(TASK1_SQL);
        assertEquals(4, statements.size());
        for (Statement statement : statements) {
            List<Statement> reprinted = parser.parse(printer.print(statement));
            assertEquals(1, reprinted.size());
            assertEquals(statement, reprinted.get(0));
        }
    }
}
