-- P2.2 Disposition — retention policy and the run ledger (FR-5). Owner: Saketh.
--
-- This schema lives in its own PostgreSQL instance (postgres-disposition, host port 5436), not in
-- P2's archive database. NFR-1: one datastore, one owner. Two Flyway histories in one database
-- would also make P2 fail on startup under ddl-auto=validate, so the separation is not optional.
--
-- Nothing here stores message content. The ledger keeps identifiers and outcomes only — enough to
-- prove what happened to a message after it is gone, which is the entire point of FR-5.3.

-- FR-5.1: retention period per communication type, configurable at runtime.
--
-- Stored rather than read from application.yml because the demo has to be able to drop email
-- retention from seven years to two minutes and see the next sweep pick it up. Seeded from
-- `discoveryhub.retention.defaults` on first start (see RetentionPolicySeeder) and edited over the
-- API afterwards; the config is the seed, the table is the truth.
--
-- Eligibility is computed at run time from `period_seconds`, never stored per message, so a policy
-- change takes effect on the next sweep with no backfill.
CREATE TABLE retention_policies
(
    message_type   VARCHAR(16)  NOT NULL,
    period_seconds BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by     VARCHAR(255) NOT NULL DEFAULT 'system',
    CONSTRAINT pk_retention_policies PRIMARY KEY (message_type),
    -- A zero or negative period would make every message in the corpus instantly eligible. That
    -- is never a legitimate policy, and the constraint is cheaper than the incident.
    CONSTRAINT ck_retention_period_positive CHECK (period_seconds > 0)
);

-- FR-5.3: one row per sweep.
CREATE TABLE disposition_runs
(
    run_id             VARCHAR(36) NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    status             VARCHAR(16) NOT NULL,
    trigger_source     VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    -- A dry run evaluates and records every decision but issues no deletes. It is how you show
    -- what a sweep *would* do before letting it destroy anything.
    dry_run            BOOLEAN     NOT NULL DEFAULT FALSE,
    candidate_count    INT         NOT NULL DEFAULT 0,
    deleted_count      INT         NOT NULL DEFAULT 0,
    skipped_hold_count INT         NOT NULL DEFAULT 0,
    failed_count       INT         NOT NULL DEFAULT 0,
    error              TEXT,
    CONSTRAINT pk_disposition_runs PRIMARY KEY (run_id),
    CONSTRAINT ck_disposition_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_disposition_runs_started ON disposition_runs (started_at DESC);

-- FR-5.3: one row per message per sweep — the durable answer to "why is this message gone?" and,
-- more importantly for a regulator, "why is this one still here?".
CREATE TABLE disposition_items
(
    item_id      BIGSERIAL    PRIMARY KEY,
    run_id       VARCHAR(36)  NOT NULL,
    message_id   VARCHAR(36)  NOT NULL,
    external_id  VARCHAR(255) NOT NULL,
    custodian_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(16)  NOT NULL,
    sent_at      TIMESTAMPTZ  NOT NULL,
    outcome      VARCHAR(24)  NOT NULL,
    reason       TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_disposition_items_run FOREIGN KEY (run_id) REFERENCES disposition_runs (run_id) ON DELETE CASCADE,
    CONSTRAINT ck_disposition_items_outcome CHECK (
        outcome IN ('DELETED', 'DELETE_REQUESTED', 'SKIPPED_HOLD', 'WOULD_DELETE', 'FAILED'))
);

CREATE INDEX idx_disposition_items_run     ON disposition_items (run_id);
CREATE INDEX idx_disposition_items_message ON disposition_items (message_id);
-- The compliance question is almost always "show me everything a hold saved", across all runs.
CREATE INDEX idx_disposition_items_outcome ON disposition_items (outcome, occurred_at DESC);
