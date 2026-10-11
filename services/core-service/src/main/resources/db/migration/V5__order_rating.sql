-- Customers rate past deliveries (1-5 stars). Used by scenario 4.5: a big backfill locks old rows, and
-- ratings are writes to old rows.
ALTER TABLE orders ADD COLUMN rating SMALLINT CHECK (rating BETWEEN 1 AND 5);
