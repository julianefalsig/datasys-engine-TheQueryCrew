CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
CREATE TABLE products (p_name STRING, p_price DOUBLE);
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);

COPY orders FROM 'examples/orders.csv';
COPY products FROM 'examples/products.csv';
COPY customers FROM 'examples/customers.csv';
COPY cities FROM 'examples/cities.csv';