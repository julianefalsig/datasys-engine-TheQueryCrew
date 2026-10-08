-- The log is already headerless CSV with seven fields, so COPY reads it as it stands.
-- Snapshot first: COPY writes to engine.log while it reads.
CREATE TABLE logs (timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG,
                   logLevel STRING, className STRING, logMessage STRING);
COPY logs FROM 'logs/snapshot.csv';
