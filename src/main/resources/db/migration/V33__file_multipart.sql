-- V33: Multipart (large-file) copy bookkeeping for stage 07E. Additive and inert until a large object is copied.
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

-- The id of the multipart upload a LEASED transfer job has in flight. The stale-multipart sweep treats an upload whose
-- id is recorded on a READY/LEASED job as owned and never reports or aborts it. Not part of the V27 identity guard
-- (guard_immutable only protects job_id, file_id, revision, target_site and created_at), so it is freely updatable by
-- the runtime role through the existing table-level UPDATE grant.
ALTER TABLE eatwhat_file.file_transfer_job
ADD COLUMN multipart_upload_id text;

ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_multipart_upload_id_check
CHECK (multipart_upload_id IS NULL OR length(multipart_upload_id) BETWEEN 1 AND 1024);

-- Ownership lookups by upload id (sweep) touch only the few rows that currently hold an upload.
CREATE INDEX file_transfer_multipart_idx ON eatwhat_file.file_transfer_job(multipart_upload_id)
WHERE multipart_upload_id IS NOT NULL;

-- Per-user presign limits count a user's open intents and the bytes declared in the last hour.
CREATE INDEX upload_intent_owner_idx ON eatwhat_file.upload_intent(owner_gid, created_at);
