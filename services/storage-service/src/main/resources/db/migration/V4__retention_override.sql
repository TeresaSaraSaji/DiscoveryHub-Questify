-- P2 Archive — per-message retention override. Owner: A.
--
-- Set only for a message P1's upload UI tagged with RetentionLabels.DEMO_RETENTION: an absolute
-- timestamp at which that one message becomes disposition-eligible, independent of
-- discoveryhub.retention.per-type. NULL (the default, and the case for every ordinary message)
-- means "use the type-based cutoff" — this column exists purely to let one demoed document be
-- shown going through disposition without changing the retention period for anything else of its
-- type.
ALTER TABLE message_hold_status ADD COLUMN retention_override_at TIMESTAMPTZ;

-- Mirrors idx_message_hold_status_on_hold: the overridden set is always a tiny minority of the
-- table, so a partial index on it is cheap and keeps the eligibility query index-only for that
-- half of its OR clause.
CREATE INDEX idx_message_hold_status_retention_override
    ON message_hold_status (retention_override_at) WHERE retention_override_at IS NOT NULL;
