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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFrontDoorIT {

    @TempDir
    Path temp;

    @Test
    void scriptStdoutIsHeaderlessCsv() throws IOException {
        Path csv = copyResource("trips.csv");
        Path script = temp.resolve("q.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE city = 'Copenhagen';
                """.formatted(sqlPath(csv)));

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

    private Path copyResource(String name) throws IOException {
        Path dest = temp.resolve(name);
        try (InputStream in = EngineFrontDoorIT.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dest);
        }
        return dest;
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
