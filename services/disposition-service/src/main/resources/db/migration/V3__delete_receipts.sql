-- P2.2 Disposition — settle the ledger from P2's delete receipts. Owner: Sahithi.
--
-- In KAFKA delete mode the sweep publishes a command and records DELETE_REQUESTED. Until now that
-- was where the row stopped: the ledger could say "we asked P2 to destroy this" and never say
-- whether P2 did. For the one table in the system whose job is to answer "was this message
-- destroyed, and if not, why not?", that is not an answer.
--
-- P2 now replies on `disposition.results` and DeleteReceiptListener settles the row to the outcome
-- P2 actually achieved — most importantly SKIPPED_HOLD, when a hold landed between the sweep's
-- check and the delete.
--
-- `settled_at` is a new column rather than a reuse of `occurred_at` because the interval between
-- them is diagnostic: occurred_at is when this service decided, settled_at is when P2 acted, and
-- rows still null hours later mean P2 is not consuming the topic. Nullable by necessity — every
-- SKIPPED_HOLD, WOULD_DELETE and directly-confirmed DELETED row is settled the moment it is
-- written and never has a separate confirmation.
ALTER TABLE disposition_items
    ADD COLUMN settled_at TIMESTAMPTZ;

-- The receipt lookup: one row, by the run that ordered it and the message it names. Partial,
-- because receipts only ever settle rows still awaiting one, and that set is small and empty at
-- rest — indexing the whole ledger to find it would grow with every sweep forever.
CREATE INDEX idx_disposition_items_awaiting_receipt
    ON disposition_items (run_id, message_id)
    WHERE outcome = 'DELETE_REQUESTED';
