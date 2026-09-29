package dk.itu.datasys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front door, from both sides. The in-process tests call {@link Engine#run} with captured
 * streams and are the fast ones; the process tests start a real JVM, because only there do the
 * console log and the CSV compete for the same stdout, which is the contract this exercise makes.
 */
class EngineFrontDoorIT {

    @TempDir
    Path temp;

    // ---- In process -----------------------------------------------------------------------

    @Test
    void scriptStdoutIsHeaderlessCsv() throws IOException {
        Path script = writeScript("q.sql", "SELECT * FROM trips WHERE city = 'Copenhagen';");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Engine.run(
                new String[] {"-f", script.toString()},
                temp.resolve("data"),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(0, exit);
        assertEquals("""
                Copenhagen,12,23.5
                Copenhagen,140,210.0
                Copenhagen,88,99.99
                """, stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void failingScriptLeavesStdoutClean() throws IOException {
        Path script = temp.resolve("bad.sql");
        Files.writeString(script, "SELECT * FROM missing;");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Engine.run(
                new String[] {"-f", script.toString()},
                temp.resolve("data"),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(1, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertFalse(stderr.toString(StandardCharsets.UTF_8).isBlank());
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("missing"));
    }

    // ---- As a process ---------------------------------------------------------------------

    /**
     * The `mvn exec:java ... > ours.csv` case: stdout has to be the CSV and nothing else, so it can
     * be diffed against DuckDB. Compared as bytes, because a stray log line or a header would be
     * exactly the kind of difference this has to catch.
     */
    @Test
    void aScriptRunAsAProcessPutsOnlyCsvOnStdout() throws IOException, InterruptedException {
        Path script = writeScript("q.sql", "SELECT * FROM trips WHERE distance > 100;");

        Result result = runEngine("-f", script.toString());

        assertEquals(0, result.exitCode(), () -> "stderr was:\n" + result.stderrText());
        assertArrayEquals(csv(
                "Aarhus,187,301.0",
                "Copenhagen,140,210.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25"), result.stdout());

        // The console log ran during that query; if stdout is still clean, it went to stderr.
        assertTrue(result.stderrText().contains("op=scan"),
                () -> "expected the console log on stderr, got:\n" + result.stderrText());
    }

    /**
     * The failing half of the same contract: the error is reported on stderr, the exit code is
     * non-zero, and stdout stays empty rather than holding a half-written result.
     */
    @Test
    void aFailingScriptRunAsAProcessWritesNothingToStdout() throws IOException, InterruptedException {
        Path script = temp.resolve("bad.sql");
        Files.writeString(script, "SELECT * FROM missing;");

        Result result = runEngine("-f", script.toString());

        assertNotEquals(0, result.exitCode());
        assertArrayEquals(new byte[0], result.stdout());
        assertTrue(result.stderrText().contains("missing"),
                () -> "expected the failure on stderr, got:\n" + result.stderrText());
    }

    /**
     * A script that fails halfway: the statements before the bad one have already printed, but
     * nothing of the failing statement reaches stdout, and the run still reports failure.
     */
    @Test
    void aScriptStopsAtTheFirstErrorWithTheMessageOnStderr() throws IOException, InterruptedException {
        Path script = writeScript("half.sql",
                "SELECT * FROM trips WHERE city = 'Odense';",
                "SELECT * FROM missing;",
                "SELECT * FROM trips WHERE city = 'Roskilde';");

        Result result = runEngine("-f", script.toString());

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderrText().contains("missing"));
        // Nothing from the statement after the error, and no partial line from the failing one.
        assertFalse(result.stdoutText().contains("Roskilde"),
                () -> "execution continued past the error:\n" + result.stdoutText());
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** A script that always starts from the same table, so each test only writes its own query. */
    private Path writeScript(String name, String... queries) throws IOException {
        Path csv = copyResource("trips.csv");
        StringBuilder sql = new StringBuilder()
                .append("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);\n")
                .append("COPY trips FROM '%s';\n".formatted(sqlPath(csv)));
        for (String query : queries) {
            sql.append(query).append('\n');
        }
        Path script = temp.resolve(name);
        Files.writeString(script, sql.toString());
        return script;
    }

    private record Result(int exitCode, byte[] stdout, byte[] stderr) {

        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String stderrText() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }

    /**
     * Starts the engine the way a shell does. The working directory is the test's temp directory,
     * so the default {@code data/} directory and the log file land there and not in the repository.
     */
    private Result runEngine(String... args) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(
                List.of(java.toString(), "-cp", System.getProperty("java.class.path"), Engine.class.getName()));
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).directory(temp.toFile()).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        return new Result(process.waitFor(), stdout, stderr);
    }

    /** The exact bytes the engine should print: one line per row, in the platform's line ending. */
    private static byte[] csv(String... lines) {
        StringBuilder expected = new StringBuilder();
        for (String line : lines) {
            expected.append(line).append(System.lineSeparator());
        }
        return expected.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Path copyResource(String name) throws IOException {
        Path dest = temp.resolve(name);
        if (Files.notExists(dest)) {
            try (InputStream in = EngineFrontDoorIT.class.getResourceAsStream("/" + name)) {
                Files.copy(in, dest);
            }
        }
        return dest;
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
