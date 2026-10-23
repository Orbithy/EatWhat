-- Durable two-site deletion fences. No timers or receipt of a transport envelope can release a fence.
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['outbox','inbox','entity_state'] LOOP
    EXECUTE format('ALTER TABLE eatwhat_sync.%I DROP CONSTRAINT %I',t,t||'_numeric_id_check');
    EXECUTE format('ALTER TABLE eatwhat_sync.%I ADD CONSTRAINT %I CHECK (
      (aggregate_type IN (''file.metadata'',''file.references'',''file.receipt'',''file.replica'',''file.gc'') AND numeric_id=0) OR
      (aggregate_type NOT IN (''file.metadata'',''file.references'',''file.receipt'',''file.replica'',''file.gc'') AND numeric_id BETWEEN 1 AND 9007199254740991))',t,t||'_numeric_id_check');
  END LOOP;
END $$;
CREATE TABLE eatwhat_file.file_gc_coordination (
  file_id uuid NOT NULL, revision integer NOT NULL, operation_id uuid NOT NULL UNIQUE,
  coordinator_site varchar(64) NOT NULL, coordinator_instance uuid NOT NULL,
  participant_site varchar(64) NOT NULL, participant_instance uuid NOT NULL,
  sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'), size_bytes bigint NOT NULL CHECK(size_bytes>0),
  role varchar(16) NOT NULL CHECK(role IN ('COORDINATOR','PARTICIPANT')),
  state varchar(16) NOT NULL CHECK(state IN ('REQUESTED','PREPARING','PREPARED','COMMITTED','DELETED')),
  peer_prepared boolean NOT NULL DEFAULT false, peer_deleted boolean NOT NULL DEFAULT false,
  published_phase integer NOT NULL DEFAULT 0 CHECK(published_phase BETWEEN 0 AND 4),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(file_id,revision), FOREIGN KEY(file_id,revision) REFERENCES eatwhat_file.file_revision(file_id,revision),
  CHECK(coordinator_site<>participant_site AND coordinator_instance<>participant_instance),
  CHECK((role='COORDINATOR' AND state IN ('PREPARING','COMMITTED','DELETED')) OR
        (role='PARTICIPANT' AND state IN ('REQUESTED','PREPARED','COMMITTED','DELETED'))),
  CHECK(role<>'COORDINATOR' OR state='PREPARING' OR peer_prepared)
);
CREATE FUNCTION eatwhat_file.guard_gc_coordination() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'GC decisions are retained' USING ERRCODE='23514'; END IF;
  IF (NEW.file_id,NEW.revision,NEW.operation_id,NEW.coordinator_site,NEW.coordinator_instance,
      NEW.participant_site,NEW.participant_instance,NEW.sha256,NEW.size_bytes,NEW.role,NEW.created_at)
    IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.operation_id,OLD.coordinator_site,OLD.coordinator_instance,
      OLD.participant_site,OLD.participant_instance,OLD.sha256,OLD.size_bytes,OLD.role,OLD.created_at)
    OR (OLD.peer_prepared AND NOT NEW.peer_prepared) OR (OLD.peer_deleted AND NOT NEW.peer_deleted)
    OR NEW.published_phase<OLD.published_phase THEN
    RAISE EXCEPTION 'GC identity and progress are immutable' USING ERRCODE='23514';
  END IF;
  IF NEW.state<>OLD.state AND NOT ((OLD.state='REQUESTED' AND NEW.state='PREPARED')
      OR (OLD.state IN ('PREPARING','PREPARED') AND NEW.state='COMMITTED')
      OR (OLD.state='COMMITTED' AND NEW.state='DELETED')) THEN
    RAISE EXCEPTION 'GC fence cannot be reverted or skipped' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER gc_coordination_guard BEFORE UPDATE OR DELETE ON eatwhat_file.file_gc_coordination
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_coordination();
CREATE TRIGGER retain_gc_coordination BEFORE TRUNCATE ON eatwhat_file.file_gc_coordination
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON eatwhat_file.file_gc_coordination FROM PUBLIC;

-- Every acquisition path, including projection writes that cannot attach an owner-matched reference,
-- uses this fence. Release/removal remains possible. Stable ordering avoids multi-file deadlocks.
CREATE FUNCTION eatwhat_file.assert_keys_referenceable(keys text[]) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE t record; BEGIN
  FOR t IN SELECT DISTINCT a.file_id,a.revision FROM eatwhat_file.file_key_alias a
           WHERE a.logical_key=ANY(keys) ORDER BY a.file_id,a.revision LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(t.file_id::text||':'||t.revision::text,0));
    IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=t.file_id
              AND c.revision=t.revision AND c.state<>'REQUESTED')
       OR EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=t.file_id
                 AND r.revision=t.revision AND r.state IN ('DELETING','DELETED')) THEN
      RAISE EXCEPTION 'file revision is frozen for physical deletion' USING ERRCODE='23514';
    END IF;
  END LOOP;
END $$;
CREATE OR REPLACE FUNCTION eatwhat_file.guard_reference_target_live() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  IF TG_TABLE_NAME='file_reference_event' THEN IF NEW.action<>'RESTORE' THEN RETURN NEW; END IF; END IF;
  PERFORM eatwhat_file.assert_keys_referenceable(ARRAY[NEW.logical_key]);
  RETURN NEW;
END $$;
CREATE FUNCTION eatwhat_file.guard_business_gc_fence() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE keys text[]; data jsonb; t record; BEGIN
  data=to_jsonb(NEW);
  IF TG_TABLE_NAME IN ('activity_foods','activity_dinners') AND data->>'deleted_at' IS NOT NULL THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='users' THEN
    -- Restoring an account activates retained conflict heads even when NEW.avatar is NULL.
    SELECT array_agg(k) INTO keys FROM (
      SELECT NEW.avatar AS k
      UNION ALL SELECT f.value #>> '{}' FROM eatwhat_sync.profile_field_state f
        JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
        WHERE i.numeric_id=NEW.id AND f.field_group='avatar'
      UNION ALL SELECT h->>'value' FROM eatwhat_sync.profile_field_state f
        JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
        CROSS JOIN LATERAL jsonb_array_elements(f.head_set) h
        WHERE i.numeric_id=NEW.id AND f.field_group='avatar') active_heads;
  ELSE SELECT array_agg(v) INTO keys FROM jsonb_array_elements_text(COALESCE(NULLIF(data->'picture_url','null'::jsonb),'[]'::jsonb)) v; END IF;
  IF TG_TABLE_NAME='users' THEN
    -- Account activation serializes with GC for every retained head. Already-frozen heads stay
    -- historical in file_profile_avatar_keys; a fresh account snapshot must still be restorable.
    FOR t IN SELECT DISTINCT a.file_id,a.revision FROM eatwhat_file.file_key_alias a
             WHERE a.logical_key=ANY(keys) ORDER BY a.file_id,a.revision LOOP
      PERFORM pg_advisory_xact_lock(hashtextextended(t.file_id::text||':'||t.revision::text,0));
    END LOOP;
    PERFORM eatwhat_file.assert_keys_referenceable(ARRAY[NEW.avatar]);
  ELSE PERFORM eatwhat_file.assert_keys_referenceable(keys); END IF;
  RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['users','restaurant','foods','activity_foods','activity_dinners'] LOOP
    EXECUTE format('CREATE TRIGGER zz_file_gc_projection_fence BEFORE INSERT OR UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_business_gc_fence()',t);
  END LOOP;
END $$;
CREATE FUNCTION eatwhat_file.guard_profile_gc_fence() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE keys text[]; BEGIN
  IF NEW.field_group<>'avatar' THEN RETURN NEW; END IF;
  IF TG_OP='UPDATE' THEN
    IF (NEW.subject_gid,NEW.field_group,NEW.value,NEW.head_set) IS NOT DISTINCT FROM
       (OLD.subject_gid,OLD.field_group,OLD.value,OLD.head_set) THEN RETURN NEW; END IF;
  END IF;
  SELECT array_agg(k) INTO keys FROM (SELECT NEW.value #>> '{}' AS k
    UNION ALL SELECT h->>'value' FROM jsonb_array_elements(NEW.head_set) h) s;
  PERFORM eatwhat_file.assert_keys_referenceable(keys);
  RETURN NEW;
END $$;
CREATE TRIGGER file_gc_profile_fence BEFORE INSERT OR UPDATE ON eatwhat_sync.profile_field_state
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_profile_gc_fence();

-- Transfer admission and byte attestations are also serialized with the same deletion decision.
CREATE FUNCTION eatwhat_file.guard_gc_transfer() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  IF TG_TABLE_NAME='file_transfer_job' THEN
    IF NEW.status NOT IN ('READY','LEASED') THEN RETURN NEW; END IF;
  ELSE
    IF NEW.state IN ('DELETING','DELETED') THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN IF NEW.state=OLD.state THEN RETURN NEW; END IF; END IF;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=NEW.file_id
            AND c.revision=NEW.revision AND c.state<>'REQUESTED') THEN
    RAISE EXCEPTION 'file transfer is fenced by coordinated deletion' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=NEW.file_id
            AND r.revision=NEW.revision AND r.state IN ('DELETING','DELETED')) THEN
    -- An old job can be claimed solely to quarantine it without object I/O. No new admission/retry
    -- and no new replica are allowed after even a LOCAL-only deletion fence.
    IF TG_TABLE_NAME='file_transfer_job' THEN
      IF TG_OP='UPDATE' THEN
        IF NEW.status='LEASED' AND OLD.status IN ('READY','LEASED') THEN RETURN NEW; END IF;
      END IF;
    END IF;
    RAISE EXCEPTION 'file transfer is fenced by physical deletion' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER file_gc_job_fence BEFORE INSERT OR UPDATE ON eatwhat_file.file_transfer_job
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_transfer();
CREATE TRIGGER file_gc_replica_fence BEFORE INSERT OR UPDATE ON eatwhat_file.file_replica
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_transfer();
REVOKE ALL ON FUNCTION eatwhat_file.guard_gc_coordination(),eatwhat_file.assert_keys_referenceable(text[]),
 eatwhat_file.guard_business_gc_fence(),eatwhat_file.guard_profile_gc_fence(),eatwhat_file.guard_gc_transfer() FROM PUBLIC;
CREATE FUNCTION eatwhat_file.guard_gc_alias() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
  IF EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination c WHERE c.file_id=NEW.file_id AND c.revision=NEW.revision AND c.state<>'REQUESTED')
    OR EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=NEW.file_id AND r.revision=NEW.revision AND r.state IN ('DELETING','DELETED')) THEN
    RAISE EXCEPTION 'cannot alias a revision frozen for deletion' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER file_gc_alias_fence BEFORE INSERT ON eatwhat_file.file_key_alias
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_gc_alias();
REVOKE ALL ON FUNCTION eatwhat_file.guard_gc_alias() FROM PUBLIC;

-- A copied revision may never acquire a local business reference. Start its local retention clock
-- conservatively when replication is first catalogued; do not invent historical unreferenced ages.
CREATE FUNCTION eatwhat_file.initialize_replica_tombstone() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  IF NOT EXISTS(SELECT 1 FROM eatwhat_file.file_replica r WHERE r.file_id=NEW.file_id AND r.revision=NEW.revision AND r.site='PEER') THEN RETURN NULL; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.file_id::text||':'||NEW.revision::text,0));
  INSERT INTO eatwhat_file.file_tombstone(file_id,revision,unreferenced_since,had_public_ref)
  SELECT NEW.file_id,NEW.revision,
    CASE WHEN NOT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f JOIN eatwhat_file.file_key_alias a USING(logical_key)
                         WHERE a.file_id=NEW.file_id AND a.revision=NEW.revision) THEN clock_timestamp() END,
    EXISTS(SELECT 1 FROM eatwhat_file.file_reference f JOIN eatwhat_file.file_key_alias a USING(logical_key)
           WHERE a.file_id=NEW.file_id AND a.revision=NEW.revision AND f.visibility='PUBLIC')
  WHERE EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a WHERE a.file_id=NEW.file_id AND a.revision=NEW.revision)
  ON CONFLICT(file_id,revision) DO NOTHING;
  RETURN NULL;
END $$;
CREATE TRIGGER initialize_replica_tombstone AFTER INSERT ON eatwhat_file.file_replica
FOR EACH ROW WHEN(NEW.site='PEER') EXECUTE FUNCTION eatwhat_file.initialize_replica_tombstone();
CREATE TRIGGER initialize_alias_tombstone AFTER INSERT ON eatwhat_file.file_key_alias
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.initialize_replica_tombstone();
REVOKE ALL ON FUNCTION eatwhat_file.initialize_replica_tombstone() FROM PUBLIC;
DO $$ DECLARE t record; BEGIN
  FOR t IN SELECT r.file_id,r.revision FROM eatwhat_file.file_replica r
           WHERE r.site='PEER' AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_tombstone b WHERE b.file_id=r.file_id AND b.revision=r.revision)
           ORDER BY r.file_id,r.revision LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(t.file_id::text||':'||t.revision::text,0));
    INSERT INTO eatwhat_file.file_tombstone(file_id,revision,unreferenced_since,had_public_ref)
    SELECT t.file_id,t.revision,
      CASE WHEN NOT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f JOIN eatwhat_file.file_key_alias a USING(logical_key)
                           WHERE a.file_id=t.file_id AND a.revision=t.revision) THEN clock_timestamp() END,
      EXISTS(SELECT 1 FROM eatwhat_file.file_reference f JOIN eatwhat_file.file_key_alias a USING(logical_key)
             WHERE a.file_id=t.file_id AND a.revision=t.revision AND f.visibility='PUBLIC')
    WHERE EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a WHERE a.file_id=t.file_id AND a.revision=t.revision)
    ON CONFLICT DO NOTHING;
  END LOOP;
END $$;

-- A deleted account's old avatar heads remain audit history when that version is irreversibly frozen.
-- Activation before PREPARE takes the version lock above and pins the file. Activation after PREPARE
-- cannot revive the old avatar, but can import a fresh avatar/account and then apply fresh profile heads.
CREATE OR REPLACE VIEW eatwhat_file.file_profile_avatar_keys WITH (security_barrier=true) AS
SELECT historical.logical_key FROM (
  SELECT f.value #>> '{}' AS logical_key FROM eatwhat_sync.profile_field_state f
  JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
  JOIN public.users u ON u.id=i.numeric_id WHERE f.field_group='avatar'
  UNION ALL
  SELECT h->>'value' FROM eatwhat_sync.profile_field_state f
  JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
  JOIN public.users u ON u.id=i.numeric_id
  CROSS JOIN LATERAL jsonb_array_elements(f.head_set) h WHERE f.field_group='avatar'
) historical
WHERE NOT EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a
  JOIN eatwhat_file.file_gc_coordination c USING(file_id,revision)
  WHERE a.logical_key=historical.logical_key AND c.state<>'REQUESTED')
AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a
  JOIN eatwhat_file.file_replica r USING(file_id,revision)
  WHERE a.logical_key=historical.logical_key AND r.state IN ('DELETING','DELETED'));
