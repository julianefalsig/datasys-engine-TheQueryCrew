-- Run from experiments/project_3/work, which run-benchmark.sh creates fresh for every run.
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_quantity LONG);
CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);
COPY customers FROM '../data/customers.csv';
COPY orders FROM '../data/orders.csv';
COPY shipments FROM '../data/shipments.csv';
