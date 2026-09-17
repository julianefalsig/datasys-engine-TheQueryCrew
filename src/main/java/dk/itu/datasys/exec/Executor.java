package dk.itu.datasys.exec;

import dk.itu.datasys.SqlParser;
import dk.itu.datasys.sql.Binder;
import dk.itu.datasys.sql.CopyStatement;
import dk.itu.datasys.sql.CreateTableStatement;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.sql.Statement;
import dk.itu.datasys.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs SQL statement by statement: parse → bind → plan → execute. Stops at the first error.
 * CREATE TABLE and COPY call the storage API directly; SELECT drains a planned operator tree.
 */
public final class Executor {

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
     * Executes every statement in {@code sql}. Each SELECT contributes one result list, in order;
     * CREATE TABLE and COPY contribute nothing.
     */
    public List<List<Object[]>> execute(String sql) {
        List<List<Object[]>> selectResults = new ArrayList<>();
        for (Statement statement : parser.parse(sql)) {
            List<Object[]> rows = execute(statement);
            if (rows != null) {
                selectResults.add(rows);
            }
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
