package dk.itu.datasys;

import dk.itu.datasys.sql.SqlPrinter;
import dk.itu.datasys.sql.Statement;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    private static final String DEMO_SQL = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        printDemoStatements();
        LOGGER.debug("engine stopped");
    }

    private static void printDemoStatements() {
        SqlParser parser = new SqlParser();
        SqlPrinter printer = new SqlPrinter();
        List<Statement> statements = parser.parse(DEMO_SQL);
        for (Statement statement : statements) {
            System.out.println(printer.print(statement));
        }
    }

    String teamName() {
        return "The Query Crew";
    }
}
