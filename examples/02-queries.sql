-- Normal work: a pruning decision, a filter and a scan per statement.
SELECT * FROM trips WHERE city = 'Odense';
SELECT * FROM trips WHERE distance > 200;
SELECT * FROM trips;
