-- P1 Ingestion — durable (externalId -> messageId) mapping. Owner: A.
--
-- Redis (RedisDedupeStore) is the fast dedupe path and fails open, so it is an optimisation, not
-- a guarantee. This table is P1's own durable idempotency guarantee (FR-1.6): external_id is the
-- primary key, so a re-submitted file/message is rejected by the constraint itself rather than
-- silently inserting a second row, exactly like P2's UNIQUE(external_id) on `messages`.
CREATE TABLE message_id_map
(
    external_id VARCHAR(255) NOT NULL,
    message_id  VARCHAR(36)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_id_map PRIMARY KEY (external_id),
    CONSTRAINT uq_message_id_map_message_id UNIQUE (message_id)
);
