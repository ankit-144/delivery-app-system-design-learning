-- Schema of every shard in the sharding lab (Phase 5). Each shard holds a slice of the orders.
CREATE TABLE sharded_orders (
    id          BIGINT PRIMARY KEY,      -- from the app (Snowflake) or from this shard's own sequence (id-strategy)
    customer_id BIGINT NOT NULL,
    city_id     SMALLINT NOT NULL,
    store_id    BIGINT NOT NULL,
    total_paise INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Used by id-strategy=sequence: every shard counts 1, 2, 3, ... on its own (scenario 5.4).
CREATE SEQUENCE sharded_orders_seq;
CREATE INDEX sharded_orders_customer_idx ON sharded_orders (customer_id, created_at DESC);
CREATE INDEX sharded_orders_store_idx ON sharded_orders (store_id, created_at DESC);
