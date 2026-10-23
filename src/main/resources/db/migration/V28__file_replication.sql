-- V28: File replication support for bidirectional sync between LOCAL and PEER sites
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

-- Step 1: Modify file_replica table
-- Drop the state CHECK constraint by exact name (created in V27 as inline constraint)
ALTER TABLE eatwhat_file.file_replica
DROP CONSTRAINT IF EXISTS file_replica_state_check;

-- Add new columns to file_replica
ALTER TABLE eatwhat_file.file_replica
ADD COLUMN verified_sha256 char(64),
ADD COLUMN verified_size bigint,
ADD COLUMN verified_at timestamptz;

-- Add the updated state CHECK constraint that includes replica states
ALTER TABLE eatwhat_file.file_replica
ADD CONSTRAINT file_replica_state_check CHECK (state IN ('AVAILABLE_LOCAL','PENDING_SYNC','PEER_VERIFIED','QUARANTINED','PAUSED_CONSENT','R2_AVAILABLE','FAILED'));

-- Add site CHECK constraint
ALTER TABLE eatwhat_file.file_replica
ADD CONSTRAINT file_replica_site_check CHECK (site IN ('LOCAL','PEER'));

-- Add row-level constraint ensuring site/state consistency (NOT VALID for existing data)
ALTER TABLE eatwhat_file.file_replica
ADD CONSTRAINT file_replica_site_state_valid CHECK ((site='LOCAL' AND state<>'PEER_VERIFIED') OR (site='PEER' AND state<>'AVAILABLE_LOCAL')) NOT VALID;

-- Step 2: Modify file_transfer_job table
-- Add new columns
ALTER TABLE eatwhat_file.file_transfer_job
ADD COLUMN kind varchar(24) NOT NULL DEFAULT 'COPY_TO_PEER',
ADD COLUMN fencing_token bigint NOT NULL DEFAULT 0,
ADD COLUMN last_error varchar(64),
ADD COLUMN last_error_detail text,
ADD COLUMN consent_epoch bigint,
ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
ADD COLUMN completed_at timestamptz;

-- Add CHECK constraints for file_transfer_job
ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_status_check CHECK (status IN ('READY','LEASED','DONE','PAUSED_CONSENT','QUARANTINED'));

ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_kind_check CHECK (kind IN ('COPY_TO_PEER','PULL_FROM_PEER'));

ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_lease_check CHECK ((status='LEASED') = (lease_until IS NOT NULL));

ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_fencing_check CHECK (fencing_token >= 0);

-- Migrate legacy rows: quarantine any row with invalid target_site or status
UPDATE eatwhat_file.file_transfer_job
SET status='QUARANTINED', last_error='LEGACY_ROW'
WHERE status NOT IN ('READY','LEASED','DONE','PAUSED_CONSENT','QUARANTINED') OR target_site NOT IN ('LOCAL','PEER');

-- For rows with target_site='LOCAL', set kind='PULL_FROM_PEER'
UPDATE eatwhat_file.file_transfer_job
SET kind='PULL_FROM_PEER'
WHERE target_site='LOCAL';

-- Add identity CHECK after legacy rows are rewritten (NOT VALID rows are still checked on UPDATE)
ALTER TABLE eatwhat_file.file_transfer_job
ADD CONSTRAINT file_transfer_job_target_site_valid CHECK (target_site IN ('LOCAL','PEER')) NOT VALID;

-- Step 3: Create file_publication table
CREATE TABLE eatwhat_file.file_publication (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    global_state varchar(24) NOT NULL CHECK (global_state IN ('LOCAL_ONLY','PEER_PENDING','AVAILABLE_GLOBAL','PAUSED')),
    peer_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(file_id, revision),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision),
    CHECK (global_state<>'AVAILABLE_GLOBAL' OR peer_confirmed_at IS NOT NULL)
);

-- Step 4: Create file_replica_alert table (append-only)
CREATE TABLE eatwhat_file.file_replica_alert (
    alert_id uuid PRIMARY KEY,
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    site varchar(16) NOT NULL CHECK (site IN ('LOCAL','PEER')),
    alert_type varchar(32) NOT NULL,
    message text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

CREATE INDEX file_replica_alert_idx ON eatwhat_file.file_replica_alert(file_id, revision);

-- Step 5: Create triggers for file_publication immutability
CREATE FUNCTION eatwhat_file.guard_publication_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'file publication records are retained' USING ERRCODE='23514'; END IF;
    IF (NEW.file_id,NEW.revision,NEW.created_at) IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.created_at) THEN
        RAISE EXCEPTION 'file publication identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_publication_immutable() FROM PUBLIC;
CREATE TRIGGER publication_identity_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_publication FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_publication_immutable();

-- Step 6: Create trigger for file_replica_alert append-only
CREATE FUNCTION eatwhat_file.guard_alert_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file replica alerts are append-only' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_alert_append_only() FROM PUBLIC;
CREATE TRIGGER alert_append_only BEFORE UPDATE OR DELETE ON eatwhat_file.file_replica_alert FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_alert_append_only();

-- Step 7: Drop old index and create new indexes for efficient job claiming
DROP INDEX IF EXISTS eatwhat_file.file_transfer_claim;

CREATE INDEX file_transfer_ready ON eatwhat_file.file_transfer_job(next_attempt_at, job_id)
WHERE status='READY';

CREATE INDEX file_transfer_expired ON eatwhat_file.file_transfer_job(lease_until, job_id)
WHERE status='LEASED';

CREATE INDEX file_transfer_paused ON eatwhat_file.file_transfer_job(job_id)
WHERE status='PAUSED_CONSENT';

-- Step 8: Backfill LOCAL_ONLY publication rows for VERIFIED revisions that lack one
INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
SELECT DISTINCT fr.file_id, fr.revision, 'LOCAL_ONLY', now(), now()
FROM eatwhat_file.file_revision fr
WHERE fr.state='VERIFIED'
  AND NOT EXISTS (
    SELECT 1 FROM eatwhat_file.file_publication fp
    WHERE fp.file_id=fr.file_id AND fp.revision=fr.revision
  )
ON CONFLICT DO NOTHING;
