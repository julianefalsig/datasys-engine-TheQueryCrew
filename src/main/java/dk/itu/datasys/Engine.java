package dk.itu.datasys;

import dk.itu.datasys.exec.Executor;
import dk.itu.datasys.storage.StorageEngine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try {
            sql = resolveSql(args);
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
            StorageEngine engine = new StorageEngine(dataDir);
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

    private static String resolveSql(String[] args) throws IOException {
        if (args.length == 1) {
            return args[0];
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
        dest.println("  <sql>                  execute one SQL statement or script");
        dest.println("  -f <path.sql>          execute a SQL file");
        dest.println("Data directory defaults to ./data/");
    }

    String teamName() {
        return "The Query Crew";
    }
}
