-- P2 Archive — messages and attachments. Owner: A.
--
-- This is the system of record. Two things to notice:
--
--   1. UNIQUE (external_id) is the idempotency guarantee (FR-1.6). The Redis check in P1 is an
--      optimisation; this constraint is what actually prevents duplicates, so a flushed cache
--      costs throughput, not correctness. A duplicate insert raises a constraint violation that
--      the consumer turns into a `message.deduped` audit event, not an error.
--
--   2. Attachment bytes live here, not in object storage. Per the storage-service brief we keep
--      everything in PostgreSQL: `content` is the raw bytes and `sha256` is the chain-of-custody
--      anchor that downstream services reason about. No MinIO/S3 dependency from P2.
--
-- `custodian_id` is the mailbox the copy came from, so the same conversation legitimately appears
-- more than once with different `external_id`s. Do not add a constraint that collapses them.
CREATE TABLE messages
(
    message_id        VARCHAR(36)  NOT NULL,
    external_id       VARCHAR(255) NOT NULL,
    source            VARCHAR(64)  NOT NULL,
    type              VARCHAR(16)  NOT NULL,
    custodian_id      VARCHAR(255) NOT NULL,
    from_addr         VARCHAR(255) NOT NULL,
    to_list           TEXT         NOT NULL,
    cc_list           TEXT         NOT NULL,
    subject           TEXT,
    body              TEXT         NOT NULL,
    sent_at           TIMESTAMPTZ  NOT NULL,
    thread_id         VARCHAR(36)  NOT NULL,
    in_reply_to       VARCHAR(36),
    labels            TEXT         NOT NULL,
    on_hold           BOOLEAN      NOT NULL DEFAULT FALSE,
    hold_count        INT          NOT NULL DEFAULT 0,
    attachment_count  INT          NOT NULL DEFAULT 0,
    archived_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_messages PRIMARY KEY (message_id),
    CONSTRAINT uq_messages_external_id UNIQUE (external_id)
);

CREATE INDEX idx_messages_custodian ON messages (custodian_id);
CREATE INDEX idx_messages_sent_at   ON messages (sent_at);
CREATE INDEX idx_messages_thread    ON messages (thread_id);
-- Partial index: disposition only ever scans held rows to skip them; the eligible set is the
-- complement, so index the small held subset rather than the whole table.
CREATE INDEX idx_messages_on_hold   ON messages (message_id) WHERE on_hold;

CREATE TABLE attachments
(
    attachment_id  VARCHAR(36)  NOT NULL,
    message_id     VARCHAR(36)  NOT NULL,
    ordinal        INT          NOT NULL,
    filename       TEXT         NOT NULL,
    content_type   VARCHAR(255),
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    content        BYTEA        NOT NULL,
    CONSTRAINT pk_attachments PRIMARY KEY (attachment_id),
    CONSTRAINT fk_attachments_message FOREIGN KEY (message_id) REFERENCES messages (message_id) ON DELETE CASCADE,
    CONSTRAINT uq_attachments_message_ordinal UNIQUE (message_id, ordinal)
);

CREATE INDEX idx_attachments_message ON attachments (message_id);
