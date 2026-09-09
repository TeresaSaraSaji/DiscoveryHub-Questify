-- P2 Archive — move attachment bytes out of PostgreSQL to local disk (+ optional S3 offload).
-- Owner: A.
--
-- Attachment bytes previously lived inline in `content` (BYTEA). They now live on local disk (the
-- primary copy the read API serves) and, when `discoveryhub.archive.storage.s3.enabled=true`, in
-- an S3 offload bucket. The table keeps metadata, the chain-of-custody `sha256`, and pointers:
--
--   storage_location  relative path of the local copy  (<messageId>/<attachmentId>)
--   s3_key            S3 object key for the offload copy (nullable when S3 is not enabled)
--   s3_bucket         S3 bucket for the offload copy     (nullable when S3 is not enabled)
--
-- `content` is retained for forward-compatibility with any pre-existing rows but is no longer
-- NOT NULL: new rows no longer populate it (the entity does not map it). A fresh install has no
-- corpus, so there is nothing to backfill; a live system migrating to this layout must run a
-- one-off job to stream `content` -> local files and set `storage_location` first.
ALTER TABLE attachments ADD COLUMN storage_location VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE attachments ADD COLUMN s3_key         VARCHAR(1024);
ALTER TABLE attachments ADD COLUMN s3_bucket      VARCHAR(255);
ALTER TABLE attachments ALTER COLUMN content DROP NOT NULL;
