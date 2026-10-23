-- V32: CDN invalidation queue, two-phase physical delete (DELETING), and the tombstone fix needed before the
-- had_public_ref GC block can be relaxed.
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

-- Step 1: file_replica gains DELETING (the committed first phase of a physical delete). It is unreadable everywhere:
-- every reader requires AVAILABLE_LOCAL. All earlier values stay valid. file_replica_site_state_valid (V28) stays
-- satisfiable for DELETING on LOCAL and PEER.
ALTER TABLE eatwhat_file.file_replica
DROP CONSTRAINT IF EXISTS file_replica_state_check;

ALTER TABLE eatwhat_file.file_replica
ADD CONSTRAINT file_replica_state_check CHECK (state IN ('AVAILABLE_LOCAL','PENDING_SYNC','PEER_VERIFIED','QUARANTINED','PAUSED_CONSENT','R2_AVAILABLE','FAILED','DELETING','DELETED'));

-- Step 2: file_gc_candidate gains DELETING: the replica is committed DELETING and the object delete is (or must be)
-- completed. Unlike LEASED it carries no lease and is claimable again, so a crashed run is resumable.
ALTER TABLE eatwhat_file.file_gc_candidate
DROP CONSTRAINT IF EXISTS file_gc_candidate_state_check;

ALTER TABLE eatwhat_file.file_gc_candidate
ADD CONSTRAINT file_gc_candidate_state_check CHECK (state IN ('DETECTED','ELIGIBLE','BLOCKED','LEASED','DELETING','DELETED','KEPT','REVIVED'));

CREATE INDEX file_gc_candidate_deleting ON eatwhat_file.file_gc_candidate(updated_at, candidate_id)
WHERE state='DELETING';

-- Step 3: the V31 reference guard now treats DELETING exactly like DELETED (keep the table-specific NEW.action branch).
CREATE OR REPLACE FUNCTION eatwhat_file.guard_reference_target_live() RETURNS trigger
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
        -- Same lock order as maintain_tombstone_on_event: per-revision advisory lock first, tombstone row second.
        PERFORM pg_advisory_xact_lock(hashtextextended(target.file_id::text || ':' || target.revision::text, 0));
        PERFORM 1 FROM eatwhat_file.file_tombstone t
        WHERE t.file_id = target.file_id AND t.revision = target.revision
        FOR UPDATE;
        IF EXISTS (SELECT 1 FROM eatwhat_file.file_replica r
                   WHERE r.file_id = target.file_id AND r.revision = target.revision
                     AND r.site = 'LOCAL' AND r.state IN ('DELETING','DELETED')) THEN
            RAISE EXCEPTION 'file object is being or was physically deleted and can no longer be referenced' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;

-- Step 4: the CDN invalidation queue. A row stores NO url and NO path: the worker derives the CDN-relative path
-- from the revision's final key and the configured public base URL when it submits.
CREATE TABLE eatwhat_file.file_cdn_invalidation (
    task_id uuid PRIMARY KEY,
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    reason varchar(16) NOT NULL CHECK (reason IN ('REVOKE','DELETE')),
    status varchar(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','LEASED','DONE','SKIPPED','QUARANTINED')),
    generation integer NOT NULL DEFAULT 1 CHECK (generation >= 1),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    provider_request_id text,
    skip_reason text,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CONSTRAINT file_cdn_invalidation_generation_unique UNIQUE (file_id, revision, reason, generation),
    CONSTRAINT file_cdn_invalidation_lease_check CHECK ((status='LEASED') = (lease_until IS NOT NULL)),
    FOREIGN KEY (file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

CREATE INDEX file_cdn_invalidation_ready ON eatwhat_file.file_cdn_invalidation(next_attempt_at, task_id)
WHERE status='READY';
CREATE INDEX file_cdn_invalidation_expired ON eatwhat_file.file_cdn_invalidation(lease_until, task_id)
WHERE status='LEASED';

CREATE FUNCTION eatwhat_file.guard_cdn_invalidation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file cdn invalidation tasks are retained' USING ERRCODE='23514';
    END IF;
    IF (NEW.task_id, NEW.file_id, NEW.revision, NEW.reason, NEW.generation, NEW.created_at)
       IS DISTINCT FROM (OLD.task_id, OLD.file_id, OLD.revision, OLD.reason, OLD.generation, OLD.created_at) THEN
        RAISE EXCEPTION 'file cdn invalidation task identity is immutable' USING ERRCODE='23514';
    END IF;
    -- DONE and SKIPPED are terminal: a finished invalidation is never reopened.
    IF OLD.status IN ('DONE','SKIPPED') AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'file cdn invalidation state % is terminal', OLD.status USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_cdn_invalidation() FROM PUBLIC;
CREATE TRIGGER cdn_invalidation_guard BEFORE UPDATE OR DELETE ON eatwhat_file.file_cdn_invalidation FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_cdn_invalidation();

-- Step 5: V29's tombstone maintenance, with these changes only:
--  (a) when a revision becomes unreferenced AGAIN after a revive, unreferenced_since is set to clock_timestamp()
--      (V29 only used COALESCE(existing, now) for the inserted row, so an ON CONFLICT update left it NULL forever);
--  (b) a REVOKE CDN invalidation task is enqueued in the same statement as the release when the last live PUBLIC
--      reference of a revision is released, regardless of remaining PRIVATE references (the CDN-visible exposure ends
--      with the last PUBLIC reference; the tombstone/unreferenced logic still needs ALL live references to be gone).
--      generation = 1 + max existing REVOKE generation. The only dedupe is against a READY REVOKE task (a task that is
--      already LEASED may have decided "revived" before this release, so it must not swallow it);
--  (c) a per-revision advisory transaction lock is taken BEFORE live references are counted, so two concurrent
--      releases of two references cannot both observe "one live reference remains". Lock order everywhere:
--      advisory lock, then the tombstone row. It needs no table privilege.
-- A revive deletes nothing: the worker skips tasks whose revision is publicly referenced again.
-- NOTE (default config): with the CDN worker off (the default) these tasks are created and simply stay READY, which
-- keeps the GC from deleting such revisions (CDN_INVALIDATION_PENDING). DONE/SKIPPED rows are retained: there is no
-- retention job for the queue yet.
CREATE OR REPLACE FUNCTION eatwhat_file.maintain_tombstone_on_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_file_id uuid;
  v_revision integer;
  v_live_count integer;
  v_live_public integer;
  v_had_public_ref boolean;
  v_existing_unreferenced timestamptz;
  v_existing_revived_at timestamptz;
  v_generation integer;
  v_released_public boolean := false;
  v_was_live boolean := false;
  v_previous_action text;
BEGIN
  IF NEW.action = 'RELEASE' THEN
    SELECT (f.visibility = 'PUBLIC') INTO v_released_public
    FROM eatwhat_file.file_reference f
    WHERE f.logical_key = NEW.logical_key AND f.ref_kind = NEW.ref_kind AND f.ref_id = NEW.ref_id;
    v_released_public := COALESCE(v_released_public, false);
    -- The reference counts as "was live" when its previous event (if any) was a RESTORE; a repeated RELEASE of an
    -- already released reference must not enqueue again.
    SELECT e.action INTO v_previous_action
    FROM eatwhat_file.file_reference_event e
    WHERE e.logical_key = NEW.logical_key AND e.ref_kind = NEW.ref_kind AND e.ref_id = NEW.ref_id
      AND e.event_id < NEW.event_id
    ORDER BY e.event_id DESC LIMIT 1;
    v_was_live := v_previous_action IS NULL OR v_previous_action = 'RESTORE';
  END IF;

  FOR v_file_id, v_revision IN
    SELECT a.file_id, a.revision
    FROM eatwhat_file.file_key_alias a
    WHERE a.logical_key = NEW.logical_key
  LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(v_file_id::text || ':' || v_revision::text, 0));

    SELECT COUNT(*) INTO v_live_count
    FROM eatwhat_file.file_reference_live f
    JOIN eatwhat_file.file_key_alias a ON a.logical_key = f.logical_key
    WHERE a.file_id = v_file_id AND a.revision = v_revision;

    SELECT COUNT(*) INTO v_live_public
    FROM eatwhat_file.file_reference_live f
    JOIN eatwhat_file.file_key_alias a ON a.logical_key = f.logical_key
    WHERE a.file_id = v_file_id AND a.revision = v_revision AND f.visibility = 'PUBLIC';

    SELECT COALESCE(bool_or(f.visibility = 'PUBLIC'), false) INTO v_had_public_ref
    FROM eatwhat_file.file_reference f
    WHERE f.logical_key IN (
      SELECT a.logical_key FROM eatwhat_file.file_key_alias a
      WHERE a.file_id = v_file_id AND a.revision = v_revision
    );

    SELECT unreferenced_since, revived_at INTO v_existing_unreferenced, v_existing_revived_at
    FROM eatwhat_file.file_tombstone
    WHERE file_id = v_file_id AND revision = v_revision;

    IF v_live_count = 0 THEN
      INSERT INTO eatwhat_file.file_tombstone(file_id, revision, unreferenced_since, had_public_ref, updated_at)
      VALUES (v_file_id, v_revision, COALESCE(v_existing_unreferenced, clock_timestamp()), v_had_public_ref, clock_timestamp())
      ON CONFLICT(file_id, revision) DO UPDATE SET
        unreferenced_since = COALESCE(file_tombstone.unreferenced_since, clock_timestamp()),
        had_public_ref = GREATEST(file_tombstone.had_public_ref, v_had_public_ref),
        updated_at = clock_timestamp();
    ELSIF v_live_count > 0 THEN
      UPDATE eatwhat_file.file_tombstone
      SET unreferenced_since = NULL,
          revived_at = CASE WHEN v_existing_unreferenced IS NOT NULL THEN clock_timestamp() ELSE v_existing_revived_at END,
          updated_at = clock_timestamp()
      WHERE file_id = v_file_id AND revision = v_revision;
    END IF;

    IF NEW.action = 'RELEASE' AND v_released_public AND v_was_live AND v_live_public = 0
       AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_cdn_invalidation c
                       WHERE c.file_id = v_file_id AND c.revision = v_revision AND c.reason = 'REVOKE'
                         AND c.status = 'READY') THEN
      SELECT COALESCE(MAX(c.generation), 0) + 1 INTO v_generation
      FROM eatwhat_file.file_cdn_invalidation c
      WHERE c.file_id = v_file_id AND c.revision = v_revision AND c.reason = 'REVOKE';
      INSERT INTO eatwhat_file.file_cdn_invalidation(task_id, file_id, revision, reason, generation)
      VALUES (gen_random_uuid(), v_file_id, v_revision, 'REVOKE', v_generation)
      ON CONFLICT DO NOTHING;
    END IF;
  END LOOP;

  RETURN NEW;
END;
$$;

-- Step 6: replica state machine. DELETING may only move to DELETED, and DELETED never moves again (a same-state
-- update such as updated_at is allowed). Nothing can resurrect a replica the GC started to delete.
CREATE FUNCTION eatwhat_file.guard_replica_delete_states() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'DELETING' AND NEW.state NOT IN ('DELETING','DELETED') THEN
        RAISE EXCEPTION 'file replica in DELETING may only become DELETED' USING ERRCODE='23514';
    END IF;
    IF OLD.state = 'DELETED' AND NEW.state <> 'DELETED' THEN
        RAISE EXCEPTION 'file replica state DELETED is terminal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_replica_delete_states() FROM PUBLIC;
CREATE TRIGGER replica_delete_states BEFORE UPDATE ON eatwhat_file.file_replica FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_replica_delete_states();

-- Step 7: backfill. Revisions that were already unreferenced with a PUBLIC history before this migration have no
-- REVOKE task; without one the GC would hold them as CDN_INVALIDATION_UNAVAILABLE forever. One READY task each.
-- BACKFILL-BEGIN
INSERT INTO eatwhat_file.file_cdn_invalidation(task_id, file_id, revision, reason, generation)
SELECT gen_random_uuid(), t.file_id, t.revision, 'REVOKE', 1
FROM eatwhat_file.file_tombstone t
WHERE t.unreferenced_since IS NOT NULL
  AND t.had_public_ref
  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_cdn_invalidation c
                  WHERE c.file_id = t.file_id AND c.revision = t.revision AND c.reason = 'REVOKE')
ON CONFLICT DO NOTHING;
-- BACKFILL-END

REVOKE ALL ON eatwhat_file.file_cdn_invalidation FROM PUBLIC;
