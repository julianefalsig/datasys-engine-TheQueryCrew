package dk.itu.datasys.storage;

import org.slf4j.MDC;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Two forced races, written to {@code logs/engine.log} under {@code sessionId=non-rebase-demo}.
 * A parked DELETE beside a COPY rebases. Two DELETEs of the same partition abort.
 */
public final class RebaseDemo {

    private static final List<ColumnSpec> SCHEMA = List.of(new ColumnSpec("city", ColumnType.STRING));

    public static void main(String[] args) throws Exception {
        MDC.put("sessionId", "non-rebase-demo");
        MDC.put("statementNumber", "0");
        Path dir = Files.createTempDirectory("non-rebase-demo");

        disjoint(dir.resolve("disjoint"));
        overlapping(dir.resolve("overlap"));

        System.out.println("sessionId=non-rebase-demo");
        System.out.println("grep non-rebase-demo logs/engine.log");
    }

    /** A DELETE parked behind a COPY. The old rule would abort the DELETE; rebase keeps both. */
    private static void disjoint(Path dir) throws Exception {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv(dir, "base.csv", "Copenhagen\nAarhus\n"));

        race(engine,
                () -> engine.delete("trips", "city", Comparison.EQUALS, "Copenhagen"),
                () -> engine.copyFile("trips", csv(dir, "more.csv", "Malmo\n")));

        System.out.println("disjoint: committed version " + engine.catalog("trips").version);
    }

    /** Two DELETEs rewrite the one shared partition. That aborts under either rule. */
    private static void overlapping(Path dir) throws Exception {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", SCHEMA);
        engine.copyFile("trips", csv(dir, "base.csv", "Copenhagen\nAarhus\n"));

        try {
            race(engine,
                    () -> engine.delete("trips", "city", Comparison.EQUALS, "Copenhagen"),
                    () -> engine.delete("trips", "city", Comparison.EQUALS, "Aarhus"));
            System.out.println("overlap: committed, which was not the point");
        } catch (ExecutionException e) {
            System.out.println("overlap: " + e.getCause().getMessage());
        }
    }

    private static void race(StorageEngine engine, Runnable loserWork, Runnable winnerWork) throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        Map<String, String> context = MDC.getCopyOfContextMap();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loser = pool.submit(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                loserWork.run();
            } finally {
                MDC.clear();
            }
        });
        try {
            if (!parked.await(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("the parked writer never reached its commit");
            }
            engine.beforeCommit = () -> { };
            winnerWork.run();
            winnerDone.countDown();
            loser.get();
        } finally {
            winnerDone.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("the winner never finished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String csv(Path dir, String name, String text) {
        Path path = dir.resolve(name);
        try {
            Files.writeString(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path.toString();
    }
}
