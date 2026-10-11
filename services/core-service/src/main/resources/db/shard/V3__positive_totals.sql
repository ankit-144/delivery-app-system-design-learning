-- Scenario 5.8: a constraint that is fine on every shard... unless one shard has a bad row.
ALTER TABLE sharded_orders ADD CONSTRAINT sharded_orders_positive_total CHECK (total_paise > 0);
