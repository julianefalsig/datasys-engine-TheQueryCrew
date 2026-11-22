package dk.itu.datasys.exec;

import dk.itu.datasys.sql.ColumnResolver;
import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.storage.ColumnSpec;
import dk.itu.datasys.storage.ColumnType;
import dk.itu.datasys.storage.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FROM order big, med, small is the worst order: med ⋈ small is smaller than big ⋈ med.
 * The greedy plan starts there, builds the smaller side, and still returns columns in FROM order.
 */
class OptimizerTest {

    private static final Path LOG_FILE = Path.of("logs", "engine.log");

    private static final List<ColumnSpec> BIG = List.of(
            new ColumnSpec("b_id", ColumnType.LONG),
            new ColumnSpec("b_med", ColumnType.LONG));
    private static final List<ColumnSpec> MED = List.of(
            new ColumnSpec("m_id", ColumnType.LONG),
            new ColumnSpec("m_small", ColumnType.LONG));
    private static final List<ColumnSpec> SMALL = List.of(
            new ColumnSpec("s_id", ColumnType.LONG));

    private String sessionId;

    @BeforeEach
    void tagSession() {
        sessionId = "it-" + UUID.randomUUID();
        MDC.put("sessionId", sessionId);
        MDC.put("statementNumber", "0");
    }

    @AfterEach
    void clearSession() {
        MDC.clear();
    }

    @Test
    void greedyStartsAtTheSmallerJoinAndBuildsTheSmallerSide() {
        List<JoinOrder.Join> joins = JoinOrder.greedy(cards(), predicates(), columns());

        assertEquals(List.of("small"), joins.get(0).buildTables());
        assertEquals(List.of("med"), joins.get(0).probeTables());
        assertEquals(10.0, joins.get(0).estRows());
        assertEquals(List.of("small", "med"), joins.get(1).buildTables());
        assertEquals(List.of("big"), joins.get(1).probeTables());
        assertEquals(20.0, joins.get(1).estRows());
    }

    @Test
    void anUnconnectedTableIsNotJoinedWhileAConnectedOneRemains() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JoinOrder.greedy(cards(), List.of(new JoinPredicate("b_med", "m_id")), columns()));
        assertTrue(error.getMessage().contains("small"), error.getMessage());
        assertTrue(error.getMessage().contains("reorder"), error.getMessage());
    }

    @Test
    void aReorderedPlanStillReturnsFromListOrderAndTheSmallerSideBuilds(@TempDir Path dataDir) throws IOException {
        Planner planner = new Planner(load(dataDir));
        SelectStatement query = query();

        Plan optimized = planner.plan(query, JoinMode.OPTIMIZED);
        PermuteOperator permute = assertInstanceOf(PermuteOperator.class, optimized.root());
        HashJoinOperator withBig = assertInstanceOf(HashJoinOperator.class, permute.child());
        HashJoinOperator medSmall = assertInstanceOf(HashJoinOperator.class, withBig.buildSide());
        assertEquals("small", ((ScanOperator) medSmall.buildSide()).tableName());
        assertEquals("med", ((ScanOperator) medSmall.probeSide()).tableName());
        assertEquals("big", ((ScanOperator) withBig.probeSide()).tableName());

        Plan syntax = planner.plan(query, JoinMode.SYNTAX);
        HashJoinOperator syntaxOuter = assertInstanceOf(HashJoinOperator.class, syntax.root());
        HashJoinOperator syntaxInner = assertInstanceOf(HashJoinOperator.class, syntaxOuter.buildSide());
        assertEquals("big", ((ScanOperator) syntaxInner.buildSide()).tableName());

        List<Object[]> rows = optimized.drain();
        assertEquals(sorted(syntax.drain()), sorted(rows));
        assertTrue(rows.stream().anyMatch(row -> row[0].equals(20L)));
        assertEquals(5, rows.getFirst().length);
    }

    @Test
    void theLogShowsTheEstimateBesideTheRowsThatCameOut(@TempDir Path dataDir) throws IOException {
        new Executor(load(dataDir)).execute("""
                SELECT * FROM big, med, small
                WHERE b_med = m_id AND m_small = s_id;
                """);

        String[] first = onlyLineContaining("op=hashJoin join=1");
        String[] second = onlyLineContaining("op=hashJoin join=2");
        assertTrue(first[6].contains("estRows=10"), first[6]);
        assertTrue(first[6].contains("rowsOut=10"), first[6]);
        assertTrue(second[6].contains("estRows=20"), second[6]);
        assertTrue(second[6].contains("rowsOut=20"), second[6]);
        assertEquals(first[2], onlyLineContaining("statement=SELECT joinOrder=")[2]);
        assertTrue(onlyLineContaining("decision=SKIP")[6].contains("candidate=big+med"), 
                onlyLineContaining("decision=SKIP")[6]);
    }

    private static List<JoinOrder.Card> cards() {
        return List.of(
                new JoinOrder.Card("big", 20, Map.of("b_id", 20L, "b_med", 10L)),
                new JoinOrder.Card("med", 10, Map.of("m_id", 10L, "m_small", 2L)),
                new JoinOrder.Card("small", 2, Map.of("s_id", 2L)));
    }

    private static List<JoinPredicate> predicates() {
        return List.of(new JoinPredicate("b_med", "m_id"), new JoinPredicate("m_small", "s_id"));
    }

    private static ColumnResolver columns() {
        return new ColumnResolver(List.of("big", "med", "small"), List.of(BIG, MED, SMALL));
    }

    private static SelectStatement query() {
        return new SelectStatement(List.of("big", "med", "small"), List.of(), predicates());
    }

    private static StorageEngine load(Path dataDir) throws IOException {
        StorageEngine engine = new StorageEngine(dataDir);
        engine.createTable("big", BIG);
        engine.createTable("med", MED);
        engine.createTable("small", SMALL);
        engine.copyFile("big", csv(dataDir, "big.csv", bigCsv()).toString());
        engine.copyFile("med", csv(dataDir, "med.csv", medCsv()).toString());
        engine.copyFile("small", csv(dataDir, "small.csv", "1\n2\n").toString());
        return engine;
    }

    private static String bigCsv() {
        StringBuilder csv = new StringBuilder();
        for (int id = 1; id <= 20; id++) {
            csv.append(id).append(',').append((id - 1) % 10 + 1).append('\n');
        }
        return csv.toString();
    }

    private static String medCsv() {
        StringBuilder csv = new StringBuilder();
        for (int id = 1; id <= 10; id++) {
            csv.append(id).append(',').append(id % 2 == 0 ? 2 : 1).append('\n');
        }
        return csv.toString();
    }

    private static Path csv(Path dataDir, String name, String text) throws IOException {
        Path dest = dataDir.resolve(name);
        Files.writeString(dest, text);
        return dest;
    }

    private static List<List<Object>> sorted(List<Object[]> rows) {
        List<List<Object>> copy = new ArrayList<>();
        for (Object[] row : rows) {
            copy.add(List.of(row));
        }
        copy.sort((left, right) -> Long.compare((Long) left.getFirst(), (Long) right.getFirst()));
        return copy;
    }

    private String[] onlyLineContaining(String needle) throws IOException {
        List<String[]> matches = new ArrayList<>();
        for (String line : Files.readAllLines(LOG_FILE)) {
            String[] fields = line.split(",", -1);
            if (fields.length == 7 && fields[1].equals(sessionId) && fields[6].contains(needle)) {
                matches.add(fields);
            }
        }
        assertEquals(1, matches.size(), "expected exactly one line containing: " + needle);
        return matches.getFirst();
    }
}
