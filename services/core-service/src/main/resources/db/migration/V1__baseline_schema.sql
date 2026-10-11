-- QuickDrop baseline schema.
--
-- Some things are missing ON PURPOSE because later scenarios are about discovering them:
--   * no CHECK (quantity >= 0) on inventory               -> scenario 1.1 discusses why it would not even help
--   * no index on orders(customer_id)                     -> scenario 2.4 (missing index)
--   * the "one active order per rider" index is not unique -> scenario 1.2 (defence in depth)

CREATE TABLE cities (
    id   SMALLINT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- Dark stores: small warehouses a customer orders from.
CREATE TABLE stores (
    id      BIGINT PRIMARY KEY,
    city_id SMALLINT NOT NULL REFERENCES cities (id),
    name    TEXT NOT NULL,
    lat     DOUBLE PRECISION NOT NULL,
    lon     DOUBLE PRECISION NOT NULL
);

CREATE TABLE products (
    id          BIGINT PRIMARY KEY,
    sku         TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    category    TEXT NOT NULL,
    price_paise INTEGER NOT NULL CHECK (price_paise > 0)
);

CREATE TABLE inventory (
    store_id       BIGINT NOT NULL REFERENCES stores (id),
    product_id     BIGINT NOT NULL REFERENCES products (id),
    quantity       INTEGER NOT NULL,
    -- Everything put on the shelf since stock_reset_at. Invariant: total_stocked - quantity = units sold.
    total_stocked  INTEGER NOT NULL,
    stock_reset_at TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, product_id)
);

CREATE TABLE customers (
    id         BIGINT PRIMARY KEY,
    city_id    SMALLINT NOT NULL REFERENCES cities (id),
    name       TEXT NOT NULL,
    phone      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE riders (
    id                  BIGINT PRIMARY KEY,
    city_id             SMALLINT NOT NULL REFERENCES cities (id),
    name                TEXT NOT NULL,
    phone               TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('AVAILABLE', 'BUSY', 'OFFLINE')),
    lat                 DOUBLE PRECISION NOT NULL,
    lon                 DOUBLE PRECISION NOT NULL,
    current_order_id    BIGINT,
    location_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX riders_city_status_idx ON riders (city_id, status);

CREATE TABLE orders (
    id                BIGSERIAL PRIMARY KEY,
    customer_id       BIGINT NOT NULL REFERENCES customers (id),
    store_id          BIGINT NOT NULL REFERENCES stores (id),
    status            TEXT NOT NULL CHECK (status IN ('PENDING_RIDER', 'RIDER_ASSIGNED', 'DELIVERED', 'CANCELLED')),
    total_paise       INTEGER NOT NULL,
    rider_id          BIGINT REFERENCES riders (id),
    -- The Idempotency-Key header, kept for auditing even when idempotency is switched off.
    client_request_id TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at       TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ
);
CREATE INDEX orders_created_idx ON orders (created_at);
CREATE INDEX orders_store_created_idx ON orders (store_id, created_at);
CREATE INDEX orders_assigned_idx ON orders (assigned_at) WHERE assigned_at IS NOT NULL;
CREATE INDEX orders_active_rider_idx ON orders (rider_id) WHERE status = 'RIDER_ASSIGNED';
CREATE INDEX orders_pending_rider_idx ON orders (created_at) WHERE status = 'PENDING_RIDER';
CREATE INDEX orders_client_request_idx ON orders (customer_id, client_request_id) WHERE client_request_id IS NOT NULL;

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders (id),
    product_id  BIGINT NOT NULL REFERENCES products (id),
    quantity    INTEGER NOT NULL CHECK (quantity > 0),
    price_paise INTEGER NOT NULL
);
CREATE INDEX order_items_order_idx ON order_items (order_id);

-- Used when idempotency is on: the first request with a key claims it, retries find it.
CREATE TABLE idempotency_keys (
    customer_id     BIGINT NOT NULL,
    idempotency_key TEXT NOT NULL,
    order_id        BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);

-- One row: when the current scenario run started. Invariant checks only look at data after it.
CREATE TABLE scenario_marker (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO scenario_marker DEFAULT VALUES;
