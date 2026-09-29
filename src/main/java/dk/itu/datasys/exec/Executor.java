package dk.itu.datasys.exec;

import dk.itu.datasys.SqlParser;
import dk.itu.datasys.sql.Binder;
import dk.itu.datasys.sql.CopyStatement;
import dk.itu.datasys.sql.CreateTableStatement;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.sql.Statement;
import dk.itu.datasys.storage.StorageEngine;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs SQL statement by statement: parse → bind → plan → execute. Stops at the first error.
 * CREATE TABLE and COPY call the storage API directly; SELECT drains a planned operator tree.
 */
public final class Executor {

    private static final String STATEMENT_NUMBER = "statementNumber";

    /** What the MDC holds while no statement is running; the engine sets it at startup. */
    private static final String OUTSIDE_ANY_STATEMENT = "0";

    private final StorageEngine engine;
    private final SqlParser parser;
    private final Binder binder;
    private final Planner planner;

    public Executor(StorageEngine engine) {
        this.engine = engine;
        this.parser = new SqlParser();
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    /**
     * Executes every statement in {@code sql}, counting {@code statementNumber} from 1. Each SELECT
     * contributes one result list, in order; CREATE TABLE and COPY contribute nothing. Parsing stays
     * outside the count, and the 0 goes back even when a statement throws, so the engine's stop line
     * falls outside too.
     */
    public List<List<Object[]>> execute(String sql) {
        List<Statement> statements = parser.parse(sql);

        List<List<Object[]>> selectResults = new ArrayList<>();
        try {
            int statementNumber = 0;
            for (Statement statement : statements) {
                MDC.put(STATEMENT_NUMBER, String.valueOf(++statementNumber));
                List<Object[]> rows = execute(statement);
                if (rows != null) {
                    selectResults.add(rows);
                }
            }
        } finally {
            MDC.put(STATEMENT_NUMBER, OUTSIDE_ANY_STATEMENT);
        }
        return selectResults;
    }

    /**
     * Binds and runs one statement. Returns the SELECT rows, or {@code null} for DDL/DML with no
     * result set.
     */
    private List<Object[]> execute(Statement statement) {
        binder.bind(statement);
        return switch (statement) {
            case CreateTableStatement create -> {
                engine.createTable(create.tableName(), create.columns());
                yield null;
            }
            case CopyStatement copy -> {
                engine.copyFile(copy.tableName(), copy.csvFilePath());
                yield null;
            }
            case SelectStatement select -> {
                Plan plan = planner.plan(select);
                engine.recordScanStats(plan.stats());
                yield plan.drain();
            }
        };
    }
}
