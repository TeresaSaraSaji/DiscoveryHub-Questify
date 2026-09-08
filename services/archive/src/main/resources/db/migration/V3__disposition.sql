-- P2 Archive — retention and disposition audit (FR-5.3). Owner: A.
--
-- Every disposition run is recorded with what was deleted, what was skipped because a hold was in
-- force, and when. The per-item rows are the durable, queryable counterpart to the `audit.events`
-- the run also emits — the audit log is append-only and owned by P5, but P2's own read API serves
-- the run history and the UI's "what happened in the last sweep?" view.
CREATE TABLE disposition_runs
(
    run_id             VARCHAR(36)  NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    status             VARCHAR(16)  NOT NULL,
    deleted_count      INT          NOT NULL DEFAULT 0,
    skipped_hold_count INT          NOT NULL DEFAULT 0,
    error              TEXT,
    CONSTRAINT pk_disposition_runs PRIMARY KEY (run_id)
);

CREATE INDEX idx_disposition_runs_started ON disposition_runs (started_at DESC);

CREATE TABLE disposition_items
(
    item_id      BIGSERIAL    PRIMARY KEY,
    run_id       VARCHAR(36)  NOT NULL,
    message_id   VARCHAR(36)  NOT NULL,
    external_id  VARCHAR(255) NOT NULL,
    custodian_id VARCHAR(255) NOT NULL,
    outcome      VARCHAR(16)  NOT NULL,
    reason       TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_disposition_items_run FOREIGN KEY (run_id) REFERENCES disposition_runs (run_id) ON DELETE CASCADE
);

CREATE INDEX idx_disposition_items_run ON disposition_items (run_id);
