-- One session: everything one run of the engine did.
SELECT * FROM logs WHERE sessionId = '834f354e-d1ba-4d4f-80f9-8615eaab6475';
-- One statement: statement 2 of every session in the file.
SELECT * FROM logs WHERE statementNumber = 2;
-- Every failure.
SELECT * FROM logs WHERE logLevel = 'ERROR';
