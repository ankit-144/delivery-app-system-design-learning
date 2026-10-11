-- notification-service's own table, in its own schema, on the shared Postgres (scenario 6.4).
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    message_id  TEXT NOT NULL UNIQUE,      -- idempotency: the same message is stored once
    type        TEXT NOT NULL,             -- ORDER_PLACED, OTP, PROMO
    recipient   TEXT NOT NULL,
    body        TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts    INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX notifications_pending_idx ON notifications (id) WHERE status = 'PENDING';
