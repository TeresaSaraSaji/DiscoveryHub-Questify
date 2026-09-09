-- Optimistic locking for the cases table (M1 fix).
--
-- transition() and updateCase() both do a read-modify-write with no lock: two concurrent
-- requests against the same case (e.g. a PATCH racing a transition) could both read the same
-- status, both pass their checks, and the last write silently wins with no error. A version
-- column, checked by JPA on every UPDATE, turns that race into an OptimisticLockException
-- instead of a silent lost update.
ALTER TABLE cases ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
