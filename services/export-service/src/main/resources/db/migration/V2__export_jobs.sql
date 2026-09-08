-- P5 Evidence Export & Audit — export job tracking (FR-6). Owner: E.
--
-- requested_scope carries the original request JSON verbatim, so a job's origin is reconstructable
-- without a second table. object_key is only ever populated once the job is COMPLETED — see
-- ObjectStorageClient's staging/packages split — so its presence alone tells you whether there is
-- anything to download.
CREATE TABLE export_jobs
(
    job_id              VARCHAR(36)  NOT NULL,
    case_id             VARCHAR(255),
    requested_scope     TEXT         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    item_count          INT          NOT NULL DEFAULT 0,
    object_key          VARCHAR(255),
    package_sha256      VARCHAR(64),
    package_size_bytes  BIGINT,
    attempts            INT          NOT NULL DEFAULT 0,
    error               TEXT,
    queued_at           TIMESTAMPTZ  NOT NULL,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    CONSTRAINT pk_export_jobs PRIMARY KEY (job_id)
);

CREATE INDEX idx_export_jobs_status ON export_jobs (status);
CREATE INDEX idx_export_jobs_queued ON export_jobs (queued_at DESC);
