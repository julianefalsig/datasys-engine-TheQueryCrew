-- Loads the golden trips data. maxRowsPerPartition is the engine default.
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'examples/trips.csv';
