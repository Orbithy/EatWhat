-- V29: File reference release, restore, and tombstone for garbage collection
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '300s';

-- Step 1: Create file_reference_event append-only table
CREATE TABLE eatwhat_file.file_reference_event (
    event_id bigserial PRIMARY KEY,
    logical_key text NOT NULL,
    ref_kind varchar(32) NOT NULL,
    ref_id text NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('RELEASE','RESTORE')),
    reason varchar(32) NOT NULL CHECK (reason IN ('ENTITY_DELETED','KEY_REPLACED','PARENT_CASCADE','ADMIN','RECONCILE')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(logical_key, ref_kind, ref_id) REFERENCES eatwhat_file.file_reference(logical_key, ref_kind, ref_id)
);

CREATE INDEX file_reference_event_live_lookup ON eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, event_id DESC);

-- Step 2: Create guard trigger for file_reference_event append-only enforcement
CREATE FUNCTION eatwhat_file.guard_event_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file reference events are append-only' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_event_append_only() FROM PUBLIC;
CREATE TRIGGER reference_event_append_only BEFORE UPDATE OR DELETE ON eatwhat_file.file_reference_event FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_event_append_only();

-- Step 3: Create file_reference_live view
-- Includes only rows where the latest event is absent or action='RESTORE'
-- A reference is live if there is NO 'RELEASE' event without a later 'RESTORE'
CREATE VIEW eatwhat_file.file_reference_live AS
SELECT f.logical_key, f.ref_kind, f.ref_id, f.visibility, f.created_at
FROM eatwhat_file.file_reference f
WHERE NOT EXISTS (
    SELECT 1 FROM eatwhat_file.file_reference_event e
    WHERE e.logical_key=f.logical_key
      AND e.ref_kind=f.ref_kind
      AND e.ref_id=f.ref_id
      AND e.action='RELEASE'
      AND NOT EXISTS (
        SELECT 1 FROM eatwhat_file.file_reference_event e2
        WHERE e2.logical_key=e.logical_key
          AND e2.ref_kind=e.ref_kind
          AND e2.ref_id=e.ref_id
          AND e2.event_id > e.event_id
          AND e2.action='RESTORE'
      )
);

-- Step 4: Create file_tombstone table
CREATE TABLE eatwhat_file.file_tombstone (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    unreferenced_since timestamptz,
    had_public_ref boolean NOT NULL DEFAULT false,
    revived_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(file_id, revision),
    FOREIGN KEY(file_id, revision) REFERENCES eatwhat_file.file_revision(file_id, revision)
);

-- Step 5: Guard trigger forbidding DELETE and identity changes on file_tombstone
CREATE FUNCTION eatwhat_file.guard_tombstone_mutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'file tombstones are not deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.file_id, NEW.revision) IS DISTINCT FROM (OLD.file_id, OLD.revision) THEN
        RAISE EXCEPTION 'file tombstone identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_tombstone_mutable() FROM PUBLIC;
CREATE TRIGGER tombstone_mutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_tombstone FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_tombstone_mutable();

-- Step 6: Trigger function to maintain file_tombstone on file_reference_event INSERT
-- Recomputes tombstone state for all logical_key aliases of the affected (file_id, revision)
CREATE FUNCTION eatwhat_file.maintain_tombstone_on_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_file_id uuid;
  v_revision integer;
  v_live_count integer;
  v_had_public_ref boolean;
  v_existing_unreferenced timestamptz;
  v_existing_revived_at timestamptz;
BEGIN
  -- For each alias of the logical_key, find its file_id and revision
  FOR v_file_id, v_revision IN
    SELECT a.file_id, a.revision
    FROM eatwhat_file.file_key_alias a
    WHERE a.logical_key = NEW.logical_key
  LOOP
    -- Count live references for this (file_id, revision) from ALL its aliases
    SELECT COUNT(*) INTO v_live_count
    FROM eatwhat_file.file_reference_live f
    JOIN eatwhat_file.file_key_alias a ON a.logical_key = f.logical_key
    WHERE a.file_id = v_file_id AND a.revision = v_revision;

    -- Check if this alias has any PUBLIC references (including released ones)
    SELECT COALESCE(bool_or(f.visibility = 'PUBLIC'), false) INTO v_had_public_ref
    FROM eatwhat_file.file_reference f
    WHERE f.logical_key IN (
      SELECT a.logical_key FROM eatwhat_file.file_key_alias a
      WHERE a.file_id = v_file_id AND a.revision = v_revision
    );

    -- Get existing tombstone state if any
    SELECT unreferenced_since, revived_at INTO v_existing_unreferenced, v_existing_revived_at
    FROM eatwhat_file.file_tombstone
    WHERE file_id = v_file_id AND revision = v_revision;

    IF v_live_count = 0 THEN
      -- No live references: upsert tombstone
      INSERT INTO eatwhat_file.file_tombstone(file_id, revision, unreferenced_since, had_public_ref, updated_at)
      VALUES (v_file_id, v_revision, COALESCE(v_existing_unreferenced, clock_timestamp()), v_had_public_ref, clock_timestamp())
      ON CONFLICT(file_id, revision) DO UPDATE SET
        had_public_ref = GREATEST(file_tombstone.had_public_ref, v_had_public_ref),
        updated_at = clock_timestamp();
    ELSIF v_live_count > 0 THEN
      -- Live references exist: clear unreferenced_since and set revived_at if it was unreferenced
      UPDATE eatwhat_file.file_tombstone
      SET unreferenced_since = NULL,
          revived_at = CASE WHEN v_existing_unreferenced IS NOT NULL THEN clock_timestamp() ELSE v_existing_revived_at END,
          updated_at = clock_timestamp()
      WHERE file_id = v_file_id AND revision = v_revision;
    END IF;
  END LOOP;

  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.maintain_tombstone_on_event() FROM PUBLIC;
CREATE TRIGGER tombstone_maintain_on_event AFTER INSERT ON eatwhat_file.file_reference_event FOR EACH ROW EXECUTE FUNCTION eatwhat_file.maintain_tombstone_on_event();

-- Step 7: Trigger function to maintain file_tombstone on file_reference INSERT
-- Only runs on the first reference of a new file to avoid creating a tombstone
CREATE FUNCTION eatwhat_file.maintain_tombstone_on_reference() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_file_id uuid;
  v_revision integer;
  v_live_count integer;
  v_had_public_ref boolean;
BEGIN
  -- For each alias of the logical_key, find its file_id and revision
  FOR v_file_id, v_revision IN
    SELECT a.file_id, a.revision
    FROM eatwhat_file.file_key_alias a
    WHERE a.logical_key = NEW.logical_key
  LOOP
    -- Count live references for this (file_id, revision)
    SELECT COUNT(*) INTO v_live_count
    FROM eatwhat_file.file_reference_live f
    JOIN eatwhat_file.file_key_alias a ON a.logical_key = f.logical_key
    WHERE a.file_id = v_file_id AND a.revision = v_revision;

    -- If this is the first reference (count=1) or count > 1, do not create tombstone
    -- Only maintain an existing tombstone if it was previously unreferenced
    IF v_live_count > 0 THEN
      UPDATE eatwhat_file.file_tombstone
      SET unreferenced_since = NULL,
          revived_at = CASE WHEN unreferenced_since IS NOT NULL THEN clock_timestamp() ELSE revived_at END,
          updated_at = clock_timestamp()
      WHERE file_id = v_file_id AND revision = v_revision;
    END IF;
  END LOOP;

  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION eatwhat_file.maintain_tombstone_on_reference() FROM PUBLIC;
CREATE TRIGGER tombstone_maintain_on_reference AFTER INSERT ON eatwhat_file.file_reference FOR EACH ROW EXECUTE FUNCTION eatwhat_file.maintain_tombstone_on_reference();

-- Step 8: Grant permissions to runtime role
-- These will be added to FileSchemaMigrationCallback via update
-- For now, REVOKE ALL and rely on callback to grant specific permissions
GRANT USAGE ON SCHEMA eatwhat_file TO PUBLIC;
REVOKE ALL ON eatwhat_file.file_reference_event FROM PUBLIC;
REVOKE ALL ON eatwhat_file.file_reference_live FROM PUBLIC;
REVOKE ALL ON eatwhat_file.file_tombstone FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.guard_event_append_only() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.guard_tombstone_mutable() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.maintain_tombstone_on_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_file.maintain_tombstone_on_reference() FROM PUBLIC;
