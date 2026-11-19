SELECT * FROM orders, customers, products, cities
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;