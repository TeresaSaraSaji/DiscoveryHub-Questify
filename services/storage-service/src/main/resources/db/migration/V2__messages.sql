-- P2 Archive — hold/retention bookkeeping. Owner: A.
--
-- Message content (including attachment bytes) lives in MongoDB now — one document per message,
-- keyed by message_id, in the `messages` collection. This table is deliberately slim: just enough
-- for the legal-hold guard and the retention/disposition sweep to run against Postgres without
-- touching Mongo at all.
--
--   1. UNIQUE (external_id) is P2's own idempotency backstop (FR-1.6). P1's message_id_map table
--      is the first line of defence; this constraint is the second, the same role the old
--      `messages` table's constraint played.
--
--   2. on_hold / hold_count mirror P4's hold state for fast local skipping. This flag is an
--      optimisation, not the guarantee: the disposition job still asks P4 synchronously before
--      deleting anything, because a stale flag here would destroy evidence (architecture decision 4).
--
-- `custodian_id` is the mailbox the copy came from, so the same conversation legitimately appears
-- more than once with different `external_id`s. Do not add a constraint that collapses them.
CREATE TABLE message_hold_status
(
    message_id   VARCHAR(36)  NOT NULL,
    external_id  VARCHAR(255) NOT NULL,
    custodian_id VARCHAR(255) NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    sent_at      TIMESTAMPTZ  NOT NULL,
    on_hold      BOOLEAN      NOT NULL DEFAULT FALSE,
    hold_count   INT          NOT NULL DEFAULT 0,
    archived_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_hold_status PRIMARY KEY (message_id),
    CONSTRAINT uq_message_hold_status_external_id UNIQUE (external_id)
);

CREATE INDEX idx_message_hold_status_custodian ON message_hold_status (custodian_id);
CREATE INDEX idx_message_hold_status_sent_at   ON message_hold_status (sent_at);
-- Partial index: disposition only ever scans held rows to skip them; the eligible set is the
-- complement, so index the small held subset rather than the whole table.
CREATE INDEX idx_message_hold_status_on_hold   ON message_hold_status (message_id) WHERE on_hold;
