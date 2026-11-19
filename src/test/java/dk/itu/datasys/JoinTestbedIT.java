package dk.itu.datasys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercise 10's testbed with DuckDB as the oracle. Each query in {@code examples/21-query-multiple.sql}
 * runs after {@code examples/20-load-multiple.sql}, once through the engine's {@code -f} path and once
 * in DuckDB, and the sorted rows must be equal. Sorted, because a hash join emits rows in probe order
 * and DuckDB promises no order either.
 */
class JoinTestbedIT {

    private static final Path LOAD = Path.of("examples", "20-load-multiple.sql");
    private static final Path QUERIES = Path.of("examples", "21-query-multiple.sql");

    // Hand-checked: every order finds its customer, product and city; 3 of the 4 orders are from Copenhagen.
    private static final List<Integer> EXPECTED_ROWS = List.of(4, 4, 4, 3);

    @TempDir
    Path temp;

    static Stream<Arguments> testbedQueries() throws IOException {
        List<String> queries = statements(QUERIES);
        return IntStream.range(0, queries.size())
                .mapToObj(i -> Arguments.of(i + 1, queries.get(i)));
    }

    // Guards the parameterized test below: a lost query would otherwise just mean one test fewer.
    @Test
    void theScriptHoldsTheFourTestbedQueries() throws IOException {
        assertEquals(EXPECTED_ROWS.size(), statements(QUERIES).size());
    }

    @ParameterizedTest(name = "query {0}")
    @MethodSource("testbedQueries")
    void sortedOutputEqualsDuckDb(int number, String query) throws IOException, SQLException {
        List<String> ours = ours(query);
        List<String> theirs = theirs(query);

        assertEquals(theirs, ours, () -> "query " + number + ":\n" + query);
        // DuckDB agreeing is not enough: had its CSV sniffer eaten a row, both sides could still be wrong.
        assertEquals(EXPECTED_ROWS.get(number - 1), ours.size(), () -> "query " + number + ":\n" + query);
    }

    /** The load script and one query through {@code -f}, into a fresh data directory. */
    private List<String> ours(String query) throws IOException {
        Path script = temp.resolve("testbed.sql");
        Files.writeString(script, Files.readString(LOAD) + "\n" + query + "\n");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Engine.run(
                new String[] {"-f", script.toString()},
                temp.resolve("data"),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(0, exit, () -> "stderr was:\n" + stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8).lines().sorted().toList();
    }

    /** The same statements in an in-memory DuckDB, each row printed the way the engine prints it. */
    private static List<String> theirs(String query) throws IOException, SQLException {
        try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:");
                java.sql.Statement statement = duckdb.createStatement()) {
            for (String load : statements(LOAD)) {
                statement.execute(headerless(load));
            }
            List<String> lines = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(query)) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 1; i <= columns; i++) {
                        if (i > 1) {
                            line.append(',');
                        }
                        line.append(rows.getObject(i));
                    }
                    lines.add(line.toString());
                }
            }
            return lines.stream().sorted().toList();
        }
    }

    /**
     * DuckDB's CSV sniffer reads the first line of an all-STRING file as a header, so
     * {@code customers.csv} would lose Anna (the lecture 2 divergence). Our CSVs never have a header,
     * so the oracle is told so; the script the engine runs is unchanged.
     */
    private static String headerless(String statement) {
        if (!statement.toUpperCase().startsWith("COPY")) {
            return statement;
        }
        return statement.substring(0, statement.length() - 1) + " (HEADER false);";
    }

    /** The statements of a script. The testbed has no semicolons inside string literals. */
    private static List<String> statements(Path script) throws IOException {
        return Arrays.stream(Files.readString(script).split(";"))
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .map(text -> text + ";")
                .toList();
    }
}
