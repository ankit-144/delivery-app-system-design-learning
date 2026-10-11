-- Transactional outbox (scenario 6.6): events are written in the same transaction as the order, then a
-- relay publishes them to Kafka. Either both the order and its event exist, or neither does.
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT NOT NULL,
    msg_key      TEXT,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
