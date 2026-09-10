-- P2.2 Disposition — record the hold scope a run was evaluated against. Owner: Saketh.
--
-- A run that deleted nothing has two very different explanations: everything was under hold, or
-- P4 could not be reached and the run correctly refused to guess. Without these columns the
-- ledger cannot tell them apart after the fact, and "why did the sweep skip 500 messages?" is
-- exactly the question asked weeks later.
ALTER TABLE disposition_runs
    ADD COLUMN active_hold_count INT NOT NULL DEFAULT 0;

-- False when P4 could not be asked. A run with hold_scope_available = false that deleted nothing
-- was failing closed, not idle.
ALTER TABLE disposition_runs
    ADD COLUMN hold_scope_available BOOLEAN NOT NULL DEFAULT TRUE;

-- Which hold, on which case, blocked a given delete. Null for any other outcome. This is the
-- column that answers "prove the hold on case X protected its messages" without joining across to
-- a service that may have released the hold since.
ALTER TABLE disposition_items
    ADD COLUMN blocking_hold_id VARCHAR(36);

ALTER TABLE disposition_items
    ADD COLUMN blocking_case_id VARCHAR(36);

CREATE INDEX idx_disposition_items_blocking_case ON disposition_items (blocking_case_id)
    WHERE blocking_case_id IS NOT NULL;
