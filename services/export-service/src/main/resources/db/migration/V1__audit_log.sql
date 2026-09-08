-- P5 Evidence Export & Audit — chain of custody (FR-7). Owner: E.
--
-- event_id is the primary key, not a generated one: every emitter derives it deterministically
-- (see AuditEvents in P1/P2), so a Kafka replay raises a constraint violation here instead of a
-- duplicate row. There is deliberately no update path anywhere in this service — FR-7.3 requires
-- that an entry can never be changed through any API, and the simplest way to guarantee that is to
-- never write the SQL that would do it.
CREATE TABLE audit_log
(
    event_id       VARCHAR(36)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    service        VARCHAR(32)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,
    subject_type   VARCHAR(64)  NOT NULL,
    subject_id     VARCHAR(255),
    actor          VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(64),
    detail         TEXT         NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (event_id)
);

CREATE INDEX idx_audit_log_occurred_at  ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_subject      ON audit_log (subject_type, subject_id);
CREATE INDEX idx_audit_log_correlation  ON audit_log (correlation_id);
CREATE INDEX idx_audit_log_service_action ON audit_log (service, action);
