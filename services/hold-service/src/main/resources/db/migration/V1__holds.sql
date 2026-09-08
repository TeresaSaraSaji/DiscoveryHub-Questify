-- P4 Legal Hold — holds and per-message coverage. Owner: Teresa.
--
-- Two tables, one owning service (NFR-1):
--
--   holds           one row per hold: its scope, status, and the case it belongs to
--   hold_coverage   every messageId a given active hold protects — the authoritative source for
--                   GET /holds/check (FR-4.2). P2's local on_hold flag is an optimisation that this
--                   service's holds.events keep in sync; the coverage table is the guarantee.
--
-- The scope is stored as scalar columns rather than JSON so a future query (holds by custodian,
-- holds by date) does not need a JSON parser, and the custodians list is a comma-separated string
-- (custodian ids in the corpus contain no commas — documented in HoldEntity).
CREATE TABLE holds
(
    hold_id         VARCHAR(36)  NOT NULL,
    case_id         VARCHAR(36)  NOT NULL,
    custodians      TEXT         NOT NULL,
    date_from       TIMESTAMPTZ,
    date_to         TIMESTAMPTZ,
    search_terms    TEXT,
    status          VARCHAR(16)  NOT NULL,
    placed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ,
    released_at     TIMESTAMPTZ,
    released_reason TEXT,
    message_count   INT          NOT NULL DEFAULT 0,
    error           TEXT,
    CONSTRAINT pk_holds PRIMARY KEY (hold_id),
    CONSTRAINT ck_holds_status CHECK (status IN ('RESOLVING', 'ACTIVE', 'RELEASED', 'FAILED'))
);

CREATE INDEX idx_holds_case ON holds (case_id);
CREATE INDEX idx_holds_status ON holds (status);

CREATE TABLE hold_coverage
(
    hold_id     VARCHAR(36)  NOT NULL,
    message_id  VARCHAR(36)  NOT NULL,
    covered_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_hold_coverage PRIMARY KEY (hold_id, message_id),
    CONSTRAINT fk_coverage_hold FOREIGN KEY (hold_id) REFERENCES holds (hold_id) ON DELETE CASCADE
);

-- The hot path: GET /holds/check?messageId=... joins coverage to active holds. Index the message
-- side so the check is an index lookup, not a scan, even over a full-corpus hold.
CREATE INDEX idx_coverage_message ON hold_coverage (message_id);
CREATE INDEX idx_coverage_hold ON hold_coverage (hold_id);
