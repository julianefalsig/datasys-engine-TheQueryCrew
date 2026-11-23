package dk.itu.datasys;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercise 11's metamorphic test: {@code -j syntax} and {@code -j optimized} run different plans, read
 * from the log, and return the same rows. The engine runs as its own process in a fresh directory, so
 * its log file holds that one run and nothing else.
 */
class JoinMetamorphicIT {

    private static final Pattern JOIN_LINE = Pattern.compile("op=hashJoin join=(\\d+) tables=(\\S+)"
            + " estRows=(\\S+) rowsBuild=\\d+ rowsProbe=\\d+ rowsOut=(\\d+)");

    @TempDir
    Path temp;

    @ParameterizedTest(name = "query {0}")
    @MethodSource("dk.itu.datasys.JoinTestbedIT#testbedQueries")
    void bothModesReturnTheSameRowsFromDifferentPlans(int number, String query)
            throws IOException, InterruptedException {
        Run syntax = run("syntax", query);
        Run optimized = run("optimized", query);

        assertEquals(syntax.rows(), optimized.rows());
        assertNotEquals(syntax.plan(), optimized.plan(), "the optimizer left the plan unchanged");

        List<String> from = fromList(query);
        assertEquals(leftDeep(from), syntax.plan());
        assertEveryJoinIsLogged(syntax, from.size() - 1);
        assertEveryJoinIsLogged(optimized, from.size() - 1);
    }

    private static void assertEveryJoinIsLogged(Run run, int expectedJoins) {
        assertEquals(expectedJoins, run.joins().size());
        for (int i = 0; i < expectedJoins; i++) {
            JoinLine join = run.joins().get(i);
            assertEquals(i + 1, join.number());
            assertEquals(run.statementNumber(), join.statementNumber());
            assertTrue(join.estRows() > 0, join::toString);
        }
        // The last join's output is the query's output.
        assertEquals(run.rows().size(), run.joins().getLast().rowsOut());
    }

    /** The load script and one query through {@code -j mode -f}, with the sorted rows and the logged joins. */
    private Run run(String mode, String query) throws IOException, InterruptedException {
        Path dir = Files.createDirectory(temp.resolve(mode));
        Path examples = Files.createDirectory(dir.resolve("examples"));
        for (String csv : List.of("orders.csv", "products.csv", "customers.csv", "cities.csv")) {
            Files.copy(Path.of("examples", csv), examples.resolve(csv));
        }
        Files.writeString(dir.resolve("testbed.sql"), Files.readString(JoinTestbedIT.LOAD) + "\n" + query + "\n");

        Path stdout = dir.resolve("stdout.csv");
        Path stderr = dir.resolve("stderr.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        int exit = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                Engine.class.getName(), "-j", mode, "-f", "testbed.sql")
                .directory(dir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
                .waitFor();
        String errors = Files.readString(stderr);
        assertEquals(0, exit, () -> "stderr was:\n" + errors);

        List<JoinLine> joins = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("logs").resolve("engine.log"))) {
            String[] fields = line.split(",", -1);
            if (fields.length != 7) {
                continue;
            }
            Matcher join = JOIN_LINE.matcher(fields[6]);
            if (join.matches()) {
                joins.add(new JoinLine(fields[2], Integer.parseInt(join.group(1)), join.group(2),
                        Double.parseDouble(join.group(3)), Integer.parseInt(join.group(4))));
            } else if (fields[6].startsWith("statement=SELECT joinOrder=")) {
                assertTrue(fields[6].endsWith("mode=" + mode), fields[6]);
                choices.add(fields[2]);
            }
        }
        assertEquals(1, choices.size(), "one chosen order per SELECT");
        // close() runs from the outermost join inwards, so the lines are not in join order.
        joins.sort(Comparator.comparingInt(JoinLine::number));
        return new Run(Files.readAllLines(stdout).stream().sorted().toList(), choices.getFirst(), joins);
    }

    private static List<String> fromList(String query) {
        return List.of(query.replaceAll("(?s).*FROM (.*?)\\s+WHERE.*", "$1").split(",\\s*"));
    }

    /** Week 10's plan: the tables joined so far build, and the next table in the FROM list probes. */
    private static List<String> leftDeep(List<String> from) {
        List<String> plan = new ArrayList<>();
        for (int tables = 2; tables <= from.size(); tables++) {
            plan.add(String.join(";", from.subList(0, tables)));
        }
        return plan;
    }

    private record JoinLine(String statementNumber, int number, String tables, double estRows, int rowsOut) {
    }

    private record Run(List<String> rows, String statementNumber, List<JoinLine> joins) {

        List<String> plan() {
            return joins.stream().map(JoinLine::tables).toList();
        }
    }
}
