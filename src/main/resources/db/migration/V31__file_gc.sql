-- V31: Physical-GC candidates (executor state machine) and the DELETED local replica state.
-- Everything here is inert until app.storage.files.gc.inventory-enabled and the separate execute flags are on.
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

-- Step 1: file_replica gains a DELETED state (physically removed by the GC executor).
-- V28 created file_replica_state_check by exact name; re-create it as a strict superset.
-- file_replica_site_state_valid (V28) stays satisfiable: DELETED is allowed on LOCAL and on PEER.
ALTER TABLE eatwhat_file.file_replica
DROP CONSTRAINT IF EXISTS file_replica_state_check;

ALTER TABLE eatwhat_file.file_replica
ADD CONSTRAINT file_replica_state_check CHECK (state IN ('AVAILABLE_LOCAL','PENDING_SYNC','PEER_VERIFIED','QUARANTINED','PAUSED_CONSENT','R2_AVAILABLE','FAILED','DELETED'));

-- Step 2: GC candidates. One row per (site, object_key); rows are never deleted.
CREATE TABLE eatwhat_file.file_gc_candidate (
    candidate_id uuid PRIMARY KEY,
    site varchar(16) NOT NULL CHECK (site IN ('LOCAL','PEER')),
    object_key text NOT NULL,
    file_id uuid,
    revision integer,
    class varchar(32) NOT NULL CHECK (class IN ('STAGING','ORPHAN_CANDIDATE','UNREFERENCED_REVISION')),
    state varchar(16) NOT NULL DEFAULT 'DETECTED'
        CHECK (state IN ('DETECTED','ELIGIBLE','BLOCKED','LEASED','DELETED','KEPT','REVIVED')),
    block_reason text,
    eligible_after timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    run_id uuid REFERENCES eatwhat_file.file_gc_run(run_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted_at timestamptz,
    CONSTRAINT file_gc_candidate_site_key_unique UNIQUE (site, object_key),
    CONSTRAINT file_gc_candidate_fencing_check CHECK (fencing_token >= 0),
    CONSTRAINT file_gc_candidate_attempts_check CHECK (attempts >= 0),
    CONSTRAINT file_gc_candidate_lease_check CHECK ((state='LEASED') = (lease_until IS NOT NULL)),
    CONSTRAINT file_gc_candidate_deleted_check CHECK (state<>'DELETED' OR deleted_at IS NOT NULL),
    CONSTRAINT file_gc_candidate_revision_check CHECK (class<>'UNREFERENCED_REVISION' OR (file_id IS NOT NULL AND revision IS NOT NULL)),
    FOREIGN KEY (file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

-- Claiming: ready/blocked/eligible rows by age; expired leases.
CREATE INDEX file_gc_candidate_claim ON eatwhat_file.file_gc_candidate(updated_at, candidate_id)
WHERE state IN ('DETECTED','ELIGIBLE','BLOCKED');
CREATE INDEX file_gc_candidate_lease_expired ON eatwhat_file.file_gc_candidate(lease_until, candidate_id)
WHERE state='LEASED';
CREATE INDEX file_gc_candidate_run_idx ON eatwhat_file.file_gc_candidate(run_id);

CREATE FUNCTION eatwhat_file.guard_gc_candidate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file gc candidates are retained' USING ERRCODE='23514';
    END IF;
    IF (NEW.candidate_id, NEW.site, NEW.object_key, NEW.class, NEW.created_at, NEW.file_id, NEW.revision)
       IS DISTINCT FROM (OLD.candidate_id, OLD.site, OLD.object_key, OLD.class, OLD.created_at, OLD.file_id, OLD.revision) THEN
        RAISE EXCEPTION 'file gc candidate identity is immutable' USING ERRCODE='23514';
    END IF;
    -- DELETED and KEPT are terminal: a deleted object is never "undeleted" and a kept one is never reconsidered.
    IF OLD.state IN ('DELETED','KEPT') AND NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'file gc candidate state % is terminal', OLD.state USING ERRCODE='23514';
    END IF;
    -- Only a lease holder may record a deletion.
    IF NEW.state = 'DELETED' AND OLD.state IS DISTINCT FROM 'DELETED' AND OLD.state <> 'LEASED' THEN
        RAISE EXCEPTION 'file gc candidates are deleted only while leased' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_gc_candidate() FROM PUBLIC;
CREATE TRIGGER gc_candidate_guard BEFORE UPDATE OR DELETE ON eatwhat_file.file_gc_candidate FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_candidate();

-- Step 3: a reference can never attach to an object the GC has physically deleted.
-- The GC holds a row lock on file_tombstone while it deletes. This BEFORE INSERT guard takes the same lock first,
-- so a re-reference either blocks until the GC transaction ends or sees the committed DELETED replica and fails.
-- Without it a reference committed right after the GC commit would point at a missing object.
CREATE FUNCTION eatwhat_file.guard_reference_target_live() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target RECORD;
BEGIN
    -- NEW.action only exists on file_reference_event; keep the field access inside the table-specific branch.
    IF TG_TABLE_NAME = 'file_reference_event' THEN
        IF NEW.action <> 'RESTORE' THEN
            RETURN NEW;
        END IF;
    END IF;
    FOR target IN
        SELECT a.file_id, a.revision FROM eatwhat_file.file_key_alias a WHERE a.logical_key = NEW.logical_key
    LOOP
        PERFORM 1 FROM eatwhat_file.file_tombstone t
        WHERE t.file_id = target.file_id AND t.revision = target.revision
        FOR UPDATE;
        IF EXISTS (SELECT 1 FROM eatwhat_file.file_replica r
                   WHERE r.file_id = target.file_id AND r.revision = target.revision
                     AND r.site = 'LOCAL' AND r.state = 'DELETED') THEN
            RAISE EXCEPTION 'file object was physically deleted and can no longer be referenced' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_reference_target_live() FROM PUBLIC;
CREATE TRIGGER reference_target_live BEFORE INSERT ON eatwhat_file.file_reference FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_reference_target_live();
CREATE TRIGGER reference_event_target_live BEFORE INSERT ON eatwhat_file.file_reference_event FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_reference_target_live();

REVOKE ALL ON eatwhat_file.file_gc_candidate FROM PUBLIC;
