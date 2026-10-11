-- Scenario 3.2: a fencing token. Each holder of the Redis stock lock gets an ever-increasing number.
-- A write is accepted only if its token is newer than the last one written, so a holder whose lock
-- expired while it was paused (GC, network) cannot overwrite the work of the holder that came after it.
ALTER TABLE inventory ADD COLUMN fence_token BIGINT NOT NULL DEFAULT 0;
