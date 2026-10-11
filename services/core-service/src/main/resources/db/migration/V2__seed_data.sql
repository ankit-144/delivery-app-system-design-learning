-- Deterministic seed data: 3 cities, 6 dark stores, 500 products, 30k customers, 6k riders.
SELECT setseed(0.42);

INSERT INTO cities (id, name) VALUES (1, 'Bengaluru'), (2, 'Mumbai'), (3, 'Delhi');

INSERT INTO stores (id, city_id, name, lat, lon) VALUES
    (1, 1, 'BLR Indiranagar',     12.9784, 77.6408),
    (2, 1, 'BLR Koramangala',     12.9352, 77.6245),
    (3, 2, 'BOM Andheri',         19.1136, 72.8697),
    (4, 2, 'BOM Bandra',          19.0596, 72.8295),
    (5, 3, 'DEL Connaught Place', 28.6315, 77.2167),
    (6, 3, 'DEL Saket',           28.5245, 77.2066);

INSERT INTO products (id, sku, name, category, price_paise)
SELECT g,
       'SKU-' || lpad(g::text, 5, '0'),
       (ARRAY ['Milk', 'Bread', 'Eggs', 'Chips', 'Cola', 'Banana', 'Apple', 'Onion', 'Tomato', 'Soap',
               'Shampoo', 'Detergent', 'Biscuits', 'Coffee', 'Tea', 'Rice', 'Atta', 'Paneer', 'Curd', 'Juice'])[1 + g % 20] || ' #' || g,
       (ARRAY ['dairy', 'bakery', 'dairy', 'snacks', 'beverages', 'fruits', 'fruits', 'vegetables', 'vegetables', 'personal-care',
               'personal-care', 'household', 'snacks', 'beverages', 'beverages', 'staples', 'staples', 'dairy', 'dairy', 'beverages'])[1 + g % 20],
       1000 + floor(random() * 49000)::int
FROM generate_series(1, 500) g;

INSERT INTO inventory (store_id, product_id, quantity, total_stocked)
SELECT s.id, p.id, 1000, 1000
FROM stores s
         CROSS JOIN products p;

INSERT INTO customers (id, city_id, name, phone)
SELECT g, 1 + g % 3, 'Customer ' || g, '+9190' || lpad(g::text, 8, '0')
FROM generate_series(1, 30000) g;

-- 1000 riders around each store, scattered within roughly 3 km.
INSERT INTO riders (id, city_id, name, phone, status, lat, lon)
SELECT g,
       s.city_id,
       'Rider ' || g,
       '+9180' || lpad(g::text, 8, '0'),
       'AVAILABLE',
       s.lat + (random() - 0.5) * 0.054,
       s.lon + (random() - 0.5) * 0.054
FROM generate_series(1, 6000) g
         JOIN stores s ON s.id = 1 + g % 6;
