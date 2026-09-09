-- P4 Case Management — cases, custodians, evidence. Owner: Teresa.
--
-- The case is the hub entity: holds are placed against a case, exports are run against a case, and
-- the audit trail is filtered by case. Three tables, one owning service — no other service reads
-- these tables (NFR-1).
--
--   cases            one row per case, with its lifecycle status
--   case_custodians  the employees whose communications are in scope for the case
--   evidence_items   messages (or saved-search result sets) added to the case as evidence
--
-- `cases` is plural deliberately: `case` is a SQL reserved word. The table is named, never the
-- keyword.
CREATE TABLE cases
(
    case_id      VARCHAR(36)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    matter_type  VARCHAR(32)  NOT NULL,
    owner        VARCHAR(255) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    closed_at    TIMESTAMPTZ,
    CONSTRAINT pk_cases PRIMARY KEY (case_id),
    CONSTRAINT ck_cases_status CHECK (status IN ('DRAFT', 'ACTIVE', 'UNDER_REVIEW', 'CLOSED')),
    CONSTRAINT ck_cases_matter CHECK (matter_type IN ('INVESTIGATION', 'LITIGATION', 'REGULATORY_INQUIRY'))
);

CREATE INDEX idx_cases_status ON cases (status);

CREATE TABLE case_custodians
(
    id           BIGSERIAL    PRIMARY KEY,
    case_id      VARCHAR(36)  NOT NULL,
    custodian_id VARCHAR(255) NOT NULL,
    added_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_case_custodian UNIQUE (case_id, custodian_id),
    CONSTRAINT fk_case_custodian_case FOREIGN KEY (case_id) REFERENCES cases (case_id) ON DELETE CASCADE
);

CREATE INDEX idx_case_custodians_case ON case_custodians (case_id);
CREATE INDEX idx_case_custodians_custodian ON case_custodians (custodian_id);

CREATE TABLE evidence_items
(
    id          BIGSERIAL    PRIMARY KEY,
    case_id     VARCHAR(36)  NOT NULL,
    message_id  VARCHAR(36)  NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    search_ref  VARCHAR(255),
    added_by    VARCHAR(255),
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_case_evidence UNIQUE (case_id, message_id),
    CONSTRAINT fk_evidence_case FOREIGN KEY (case_id) REFERENCES cases (case_id) ON DELETE CASCADE,
    CONSTRAINT ck_evidence_source CHECK (source IN ('MANUAL', 'SEARCH'))
);

CREATE INDEX idx_evidence_case ON evidence_items (case_id);
CREATE INDEX idx_evidence_message ON evidence_items (message_id);
