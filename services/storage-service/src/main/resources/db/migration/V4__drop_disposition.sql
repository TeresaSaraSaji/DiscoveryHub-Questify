-- P2 Archive — hand retention and disposition to P2.2. Owner: Sahithi.
--
-- V3 created a disposition ledger in this database, back when P2 ran its own retention sweep. It
-- no longer does: P2.2 owns the retention policy and the sweep, keeps the ledger in its own
-- database, and asks P2 to delete through `disposition.commands`. See
-- retention/DispositionCommandListener.
--
-- These tables have to go rather than linger. Under `ddl-auto: validate` an unmapped table is
-- harmless, so nothing would fail — which is the problem. A second, frozen ledger sitting next to
-- the live one in P2.2 is an invitation to read the wrong answer to "what did the last sweep
-- delete?", and the stale one would look plausible: right column names, right shape, months out
-- of date. FR-5.3 is only worth anything if there is exactly one place that answers it.
--
-- Dropping is safe here because the rows were never the system of record for anything. The audit
-- events P2's sweep emitted went to `audit.events` and live in P5, which is append-only; the
-- messages themselves are untouched by this migration.
DROP TABLE IF EXISTS disposition_items;
DROP TABLE IF EXISTS disposition_runs;
