-- P5 Export — how many messages in an export's scope were no longer in the archive when the
-- package was built. Owner: D.
--
-- Almost always zero. It is not zero when a case names evidence that retention has since
-- destroyed on schedule: the package holds what survived, the manifest names what did not, and
-- this column is how the UI can say so without opening the zip.
--
-- Defaulted rather than nullable: every existing job was built under the old behaviour, which
-- refused outright if anything was missing, so zero is the true value for all of them.
ALTER TABLE export_jobs ADD COLUMN missing_count INTEGER NOT NULL DEFAULT 0;
