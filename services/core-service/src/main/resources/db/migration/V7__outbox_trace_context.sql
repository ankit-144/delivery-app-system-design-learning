-- Phase 8 (scenario 8.1): the W3C trace context of the request that wrote the row ("00-<trace id>-<span id>-01").
-- The relay publishes rows later, on another thread and often another instance; without this, the trace ends at
-- the INSERT and the Kafka message starts an unrelated one. Nullable: rows written with tracing off have none.
ALTER TABLE outbox ADD COLUMN trace_parent text;
