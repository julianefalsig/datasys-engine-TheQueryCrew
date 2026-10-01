-- run from the root directory: ./engine -f sources/example-script.sql

-- After first time it already exists!
--CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
--COPY trips FROM 'sources/trips.csv';

-- Selects to test the engine with different filters and no filters.
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
SELECT * FROM trips WHERE city = 'Odense';

-- failing statement.
--SELECT * FROM missing;
