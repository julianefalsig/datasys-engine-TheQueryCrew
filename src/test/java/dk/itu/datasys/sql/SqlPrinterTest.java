package dk.itu.datasys.sql;

import dk.itu.datasys.SqlParser;
import dk.itu.datasys.storage.Comparison;

import java.util.List;
import java.util.Optional;

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

    @Test
    void doubleLiteralAvoidsScientificNotation() {
        Statement statement = new SelectStatement(
                "trips",
                Optional.of(new Predicate("price", Comparison.GREATER_THAN, 1.0e20)));
        assertEquals(statement, roundTrip(statement));
    }

    @Test
    void negativeZeroDoubleRoundTrips() {
        Statement statement = new SelectStatement(
                "trips",
                Optional.of(new Predicate("price", Comparison.EQUALS, -0.0)));
        assertEquals(statement, roundTrip(statement));
    }

    private static Statement roundTrip(Statement statement) {
        SqlParser parser = new SqlParser();
        List<Statement> reprinted = parser.parse(new SqlPrinter().print(statement));
        assertEquals(1, reprinted.size());
        return reprinted.get(0);
    }
}
