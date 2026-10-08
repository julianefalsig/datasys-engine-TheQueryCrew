package dk.itu.datasys;

import dk.itu.datasys.exec.Executor;
import dk.itu.datasys.storage.StorageEngine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);
    private static final Path DEFAULT_DATA_DIR = Path.of("data");

    public static void main(String[] args) {
        int exitCode = run(args, DEFAULT_DATA_DIR, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * CLI entry used by {@link #main} and tests. Returns 0 on success, non-zero on failure.
     * Does not call {@link System#exit}.
     */
    static int run(String[] args, Path dataDir, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            printUsage(out);
            return 0;
        }

        final String sql;
        Integer maxRows = null;
        try {
            int end = args.length;
            if (args.length >= 2 && "--max-rows-per-partition".equals(args[end - 2])) {
                maxRows = partitionSize(args[end - 1]);
                end -= 2;
            }
            sql = resolveSql(Arrays.copyOfRange(args, 0, end));
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            printUsage(err);
            return 1;
        } catch (IOException e) {
            err.println(e.getMessage());
            return 1;
        }

        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        try {
            StorageEngine engine = maxRows == null
                    ? new StorageEngine(dataDir)
                    : new StorageEngine(dataDir, maxRows);
            Executor executor = new Executor(engine);
            writeCsv(executor.execute(sql), out);
            return 0;
        } catch (RuntimeException e) {
            err.println(e.getMessage() != null ? e.getMessage() : e.toString());
            return 1;
        } finally {
            LOGGER.debug("engine stopped");
            MDC.clear();
        }
    }

    /** The flag and its value, when present, are the last two arguments. CREATE TABLE stores the value; COPY reads the catalog. */
    private static int partitionSize(String text) {
        final int size;
        try {
            size = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--max-rows-per-partition needs a positive integer", e);
        }
        if (size < 1) {
            throw new IllegalArgumentException("--max-rows-per-partition needs a positive integer");
        }
        return size;
    }

    private static String resolveSql(String[] args) throws IOException {
        if (args.length == 2 && "-c".equals(args[0])) {
            return args[1];
        }
        if (args.length == 2 && "-f".equals(args[0])) {
            return Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("unexpected arguments");
    }

    private static void writeCsv(List<List<Object[]>> results, PrintStream out) {
        for (List<Object[]> rows : results) {
            for (Object[] row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    line.append(row[i]);
                }
                out.println(line);
            }
        }
    }

    private static void printUsage(PrintStream dest) {
        dest.println(new Engine().teamName());
        dest.println("Usage:");
        dest.println("  (no args)              print this help");
        dest.println("  -c <sql>               execute one SQL statement or script");
        dest.println("  -f <path.sql>          execute a SQL file");
        dest.println("  --max-rows-per-partition <n>");
        dest.println("                         comes last; partition size stored by CREATE TABLE (default 10000)");
        dest.println("Data directory defaults to ./data/");
    }

    String teamName() {
        return "The Query Crew";
    }
}
