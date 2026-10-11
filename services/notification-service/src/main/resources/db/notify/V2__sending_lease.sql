-- Claiming must be atomic and recoverable: a worker marks rows SENDING (with the time it claimed them)
-- in one UPDATE; rows still SENDING after 30 s belonged to a worker that died, and are claimed again.
ALTER TABLE notifications DROP CONSTRAINT notifications_status_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_status_check CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'));
ALTER TABLE notifications ADD COLUMN claimed_at TIMESTAMPTZ;
CREATE INDEX notifications_open_idx ON notifications (id) WHERE status IN ('PENDING', 'SENDING');
