-- The benchmark's analysis through the engine's own joins. Run from experiments/project_3/work,
-- after run-benchmark.sh has written logs/snapshot.csv and ../results/runs.csv.
-- run-benchmark.sh runs each SELECT on its own, so each one gets its own output file.
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/snapshot.csv';
CREATE TABLE runs (r_session STRING, r_config STRING, r_repetition LONG);
COPY runs FROM '../results/runs.csv';
-- Every join's estRows and rowsOut, labeled with the configuration that ran it.
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'HashJoinOperator';
-- Every statement summary: durationMs is the end-to-end runtime.
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'Executor';
-- The join order each run chose, and the candidates the optimizer weighed.
SELECT * FROM logs, runs WHERE sessionId = r_session AND className = 'JoinOrder';
