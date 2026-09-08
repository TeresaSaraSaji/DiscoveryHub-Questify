-- P2.2 Disposition — allow a run to be queued before it starts. Owner: Sahithi.
--
-- A sweep makes one HTTP call to P4 per candidate and considers up to batch-size of them, so it is
-- not a request-sized unit of work. Run synchronously it holds the connection until it finishes
-- and the browser times out first, which NFR-3 asks specifically that bulk work over the corpus
-- not do. POST /disposition/runs?async=true now answers 202 with a run id and lets the sweep
-- proceed in the background, watched over SSE.
--
-- QUEUED is persisted rather than kept in memory because the interesting case is the service dying
-- between accepting the request and starting the work: without a row, a sweep that was ordered and
-- never ran leaves no trace at all, and the run history would show nothing where a user watched
-- themselves click the button.
ALTER TABLE disposition_runs
    DROP CONSTRAINT ck_disposition_runs_status;

ALTER TABLE disposition_runs
    ADD CONSTRAINT ck_disposition_runs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'));
