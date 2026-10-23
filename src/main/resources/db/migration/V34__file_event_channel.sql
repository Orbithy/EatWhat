SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Existing business identities keep their original constraints. Only these internal UUID-only families use zero.
ALTER TABLE eatwhat_sync.outbox DROP CONSTRAINT outbox_numeric_id_check;
ALTER TABLE eatwhat_sync.inbox DROP CONSTRAINT inbox_numeric_id_check;
ALTER TABLE eatwhat_sync.entity_state DROP CONSTRAINT entity_state_numeric_id_check;
ALTER TABLE eatwhat_sync.entity_state DROP CONSTRAINT entity_state_aggregate_type_numeric_id_key;
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['outbox','inbox','entity_state'] LOOP
        EXECUTE format('ALTER TABLE eatwhat_sync.%I ADD CONSTRAINT %I CHECK (
          (aggregate_type IN (''file.metadata'',''file.references'',''file.receipt'',''file.replica'') AND numeric_id=0) OR
          (aggregate_type NOT IN (''file.metadata'',''file.references'',''file.receipt'',''file.replica'') AND numeric_id BETWEEN 1 AND 9007199254740991))',t,t||'_numeric_id_check');
    END LOOP;
END $$;
CREATE UNIQUE INDEX entity_state_business_numeric_identity ON eatwhat_sync.entity_state(aggregate_type,numeric_id)
WHERE numeric_id>0;

CREATE TABLE eatwhat_file.file_sync_origin (
    file_id uuid PRIMARY KEY REFERENCES eatwhat_file.file_object(file_id),
    origin_site varchar(64) NOT NULL,
    origin_instance uuid NOT NULL
);
CREATE TRIGGER file_sync_origin_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_sync_origin
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_event_append_only();

-- A transactional dirty marker, not an event log. Workers publish a complete immutable snapshot.
CREATE TABLE eatwhat_file.file_sync_dirty (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    generation bigint NOT NULL DEFAULT 1 CHECK (generation>0),
    published_generation bigint NOT NULL DEFAULT 0 CHECK (published_generation>=0),
    metadata_epoch bigint NOT NULL DEFAULT -1,
    checked_at timestamptz NOT NULL DEFAULT '-infinity',
    PRIMARY KEY(file_id,revision),
    FOREIGN KEY(file_id,revision) REFERENCES eatwhat_file.file_revision(file_id,revision)
);
-- Peer reference state is separate from local references; a remote zero count never erases a local reference.
-- No revision FK: cleanup must be receivable before metadata, even after consent is withdrawn.
CREATE TABLE eatwhat_file.file_sync_peer_state (
    file_id uuid NOT NULL,
    revision integer NOT NULL CHECK (revision>0),
    origin_site varchar(64) NOT NULL,
    origin_instance uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation>0),
    live_count bigint NOT NULL CHECK (live_count>=0),
    public_count bigint NOT NULL CHECK (public_count BETWEEN 0 AND live_count),
    tombstoned boolean NOT NULL,
    event_id uuid NOT NULL,
    envelope_hash char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(file_id,revision,origin_site,origin_instance),
    CHECK (tombstoned=(live_count=0))
);
-- Application receipts are committed together with handler effects, then published by a separate worker.
CREATE TABLE eatwhat_file.file_sync_receipt (
    event_id uuid PRIMARY KEY,
    envelope_hash char(64) NOT NULL,
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    outcome varchar(16) NOT NULL CHECK(outcome IN ('APPLIED','IGNORED')),
    published boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION eatwhat_file.mark_file_sync_dirty() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE f uuid; r integer;
BEGIN
    IF TG_TABLE_SCHEMA<>'eatwhat_file' OR TG_TABLE_NAME NOT IN ('file_key_alias','file_reference','file_reference_event') THEN
        RAISE EXCEPTION 'Unsupported file capture relation';
    END IF;
    SELECT a.file_id,a.revision INTO f,r FROM eatwhat_file.file_key_alias a WHERE a.logical_key=NEW.logical_key;
    INSERT INTO eatwhat_file.file_sync_dirty(file_id,revision) VALUES(f,r)
    ON CONFLICT(file_id,revision) DO UPDATE SET generation=file_sync_dirty.generation+1;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.mark_file_sync_dirty() FROM PUBLIC;
CREATE TRIGGER zz_file_sync_dirty AFTER INSERT ON eatwhat_file.file_key_alias
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.mark_file_sync_dirty();
CREATE TRIGGER zz_file_sync_dirty AFTER INSERT ON eatwhat_file.file_reference
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.mark_file_sync_dirty();
CREATE TRIGGER zz_file_sync_dirty AFTER INSERT ON eatwhat_file.file_reference_event
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.mark_file_sync_dirty();
INSERT INTO eatwhat_file.file_sync_dirty(file_id,revision) SELECT file_id,revision FROM eatwhat_file.file_revision WHERE state='VERIFIED';

-- Release references for every business write, including SECURITY DEFINER imports and SQL FK cascades.
-- Only existing managed references are touched; arbitrary legacy URLs remain untouched.
CREATE FUNCTION eatwhat_file.release_business_file_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE kind text; entity text; keep_keys text[] := ARRAY[]::text[]; row_json jsonb;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','restaurant','foods','activity_foods','activity_dinners') THEN
        RAISE EXCEPTION 'Unsupported file reference relation';
    END IF;
    kind := CASE TG_TABLE_NAME WHEN 'users' THEN 'user_avatar' WHEN 'restaurant' THEN 'restaurant'
        WHEN 'foods' THEN 'food' WHEN 'activity_foods' THEN 'activity_food' ELSE 'activity_dinner' END;
    entity:=OLD.id::text;
    IF TG_OP='UPDATE' THEN
        row_json:=to_jsonb(NEW);
        IF TG_TABLE_NAME='users' THEN
            keep_keys:=ARRAY[NEW.avatar];
        ELSIF TG_TABLE_NAME NOT IN ('activity_foods','activity_dinners') OR row_json->>'deleted_at' IS NULL THEN
            SELECT coalesce(array_agg(value),ARRAY[]::text[]) INTO keep_keys
            FROM jsonb_array_elements_text(coalesce(nullif(row_json->'picture_url','null'::jsonb),'[]'::jsonb));
        END IF;
    END IF;
    INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason)
    SELECT f.logical_key,f.ref_kind,f.ref_id,'RELEASE',
        CASE WHEN TG_TABLE_NAME='foods' AND TG_OP='DELETE' AND
            NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=(to_jsonb(OLD)->>'restaurant_id')::bigint) THEN 'PARENT_CASCADE'
          WHEN TG_OP='DELETE' OR (TG_TABLE_NAME IN ('activity_foods','activity_dinners') AND to_jsonb(NEW)->>'deleted_at' IS NOT NULL) THEN 'ENTITY_DELETED'
          ELSE 'KEY_REPLACED' END
    FROM eatwhat_file.file_reference_live f
    WHERE f.ref_kind=kind AND (f.ref_id=entity OR f.ref_id LIKE entity||':%')
      AND NOT EXISTS(SELECT 1 FROM unnest(keep_keys) k WHERE k=f.logical_key)
    ORDER BY f.logical_key,f.ref_id;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.release_business_file_references() FROM PUBLIC;
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF avatar ON public.users
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF picture_url ON public.restaurant
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF picture_url ON public.foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF picture_url,deleted_at ON public.activity_foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF picture_url,deleted_at ON public.activity_dinners
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
REVOKE ALL ON eatwhat_file.file_sync_origin,eatwhat_file.file_sync_dirty,eatwhat_file.file_sync_peer_state,eatwhat_file.file_sync_receipt FROM PUBLIC;

CREATE TABLE eatwhat_file.file_sync_ack (
    event_id uuid PRIMARY KEY REFERENCES eatwhat_sync.outbox(event_id),
    receipt_event_id uuid NOT NULL,
    envelope_hash char(64) NOT NULL,
    outcome varchar(16) NOT NULL CHECK(outcome IN ('APPLIED','IGNORED')),
    acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER file_sync_ack_immutable BEFORE UPDATE OR DELETE ON eatwhat_file.file_sync_ack
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_event_append_only();
REVOKE ALL ON eatwhat_file.file_sync_ack FROM PUBLIC;

-- Every newly projected image must also acquire a reference. A deterministic separate ref_id avoids reindexing
-- existing service-generated list references when an array changes order. Exact managed alias match only.
CREATE FUNCTION eatwhat_file.attach_business_file_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE kind text; keys text[]; k text; row_json jsonb; rid text; owner_id bigint; expected_biz text;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','restaurant','foods','activity_foods','activity_dinners') THEN
        RAISE EXCEPTION 'Unsupported file reference relation';
    END IF;
    kind:=CASE TG_TABLE_NAME WHEN 'users' THEN 'user_avatar' WHEN 'restaurant' THEN 'restaurant'
        WHEN 'foods' THEN 'food' WHEN 'activity_foods' THEN 'activity_food' ELSE 'activity_dinner' END;
    row_json:=to_jsonb(NEW);
    IF TG_TABLE_NAME='users' THEN keys:=ARRAY[NEW.avatar];
    ELSIF TG_TABLE_NAME IN ('activity_foods','activity_dinners') AND row_json->>'deleted_at' IS NOT NULL THEN RETURN NULL;
    ELSE
        SELECT coalesce(array_agg(value),ARRAY[]::text[]) INTO keys
        FROM jsonb_array_elements_text(coalesce(nullif(row_json->'picture_url','null'::jsonb),'[]'::jsonb));
    END IF;
    owner_id:=CASE WHEN TG_TABLE_NAME='users' THEN NEW.id ELSE (row_json->>'account_id')::bigint END;
    expected_biz:=CASE TG_TABLE_NAME WHEN 'users' THEN 'avatar' WHEN 'restaurant' THEN 'restaurants' WHEN 'foods' THEN 'foods' ELSE 'activity' END;
    FOR k IN SELECT DISTINCT unnest(keys) ORDER BY 1 LOOP
        IF k IS NULL OR NOT EXISTS(SELECT 1 FROM eatwhat_file.file_key_alias a JOIN eatwhat_identity.entity_identity i ON i.entity_type='users' AND i.global_id=a.owner_gid WHERE a.logical_key=k AND i.numeric_id=owner_id AND a.biz=expected_biz) THEN CONTINUE; END IF;
        IF EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f WHERE f.logical_key=k AND f.ref_kind=kind
            AND (f.ref_id=NEW.id::text OR f.ref_id LIKE NEW.id::text||':%')) THEN CONTINUE; END IF;
        rid:=NEW.id::text||':db:'||md5(k);
        INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(k,kind,rid,'PUBLIC') ON CONFLICT DO NOTHING;
        INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason)
        SELECT k,kind,rid,'RESTORE','RECONCILE'
        WHERE (SELECT e.action FROM eatwhat_file.file_reference_event e
            WHERE e.logical_key=k AND e.ref_kind=kind AND e.ref_id=rid ORDER BY e.event_id DESC LIMIT 1)='RELEASE';
    END LOOP;
    RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.attach_business_file_references() FROM PUBLIC;
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF avatar ON public.users
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF picture_url ON public.restaurant
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF picture_url ON public.foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF picture_url,deleted_at ON public.activity_foods
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF picture_url,deleted_at ON public.activity_dinners
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
-- Byte verification is a separate, consent-gated attestation, never inferred from a transport ACK.
ALTER TABLE eatwhat_file.file_sync_dirty ADD COLUMN replica_generation bigint NOT NULL DEFAULT 0;
ALTER TABLE eatwhat_file.file_sync_dirty ADD COLUMN replica_epoch bigint NOT NULL DEFAULT -1;
CREATE FUNCTION eatwhat_file.mark_replica_sync_dirty() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_TABLE_SCHEMA<>'eatwhat_file' OR TG_TABLE_NAME<>'file_replica' THEN RAISE EXCEPTION 'Unsupported replica relation'; END IF;
    INSERT INTO eatwhat_file.file_sync_dirty(file_id,revision) VALUES(NEW.file_id,NEW.revision)
    ON CONFLICT(file_id,revision) DO UPDATE SET generation=file_sync_dirty.generation+1;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.mark_replica_sync_dirty() FROM PUBLIC;
CREATE TRIGGER zz_replica_sync_dirty AFTER UPDATE ON eatwhat_file.file_replica
FOR EACH ROW WHEN ((NEW.state,NEW.verified_sha256,NEW.verified_size) IS DISTINCT FROM (OLD.state,OLD.verified_sha256,OLD.verified_size))
EXECUTE FUNCTION eatwhat_file.mark_replica_sync_dirty();

-- Upload history and orphan candidates are retained after an account projection is removed. A live FK would
-- prevent withdrawal cleanup forever. Preserve insertion validation and serialize it with account deletion.
ALTER TABLE eatwhat_file.upload_intent DROP CONSTRAINT upload_intent_owner_id_fkey;
CREATE FUNCTION eatwhat_file.require_upload_owner() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF TG_TABLE_SCHEMA<>'eatwhat_file' OR TG_TABLE_NAME<>'upload_intent' THEN RAISE EXCEPTION 'Unsupported upload relation'; END IF;
    PERFORM 1 FROM public.users WHERE id=NEW.owner_id FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Upload owner must exist' USING ERRCODE='23503'; END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.require_upload_owner() FROM PUBLIC;
CREATE TRIGGER upload_owner_exists BEFORE INSERT ON eatwhat_file.upload_intent
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.require_upload_owner();

CREATE FUNCTION eatwhat_file.guard_file_sync_state() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'File sync state is retained' USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME='file_sync_dirty' THEN
        IF (NEW.file_id,NEW.revision) IS DISTINCT FROM (OLD.file_id,OLD.revision)
            OR NEW.generation<OLD.generation OR NEW.published_generation<OLD.published_generation
            OR NEW.published_generation>NEW.generation OR NEW.metadata_epoch<OLD.metadata_epoch
            OR NEW.replica_generation<OLD.replica_generation OR NEW.replica_epoch<OLD.replica_epoch THEN
            RAISE EXCEPTION 'Invalid file publication progress' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='file_sync_peer_state' THEN
        IF (NEW.file_id,NEW.revision,NEW.origin_site,NEW.origin_instance) IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.origin_site,OLD.origin_instance)
            OR NEW.generation<=OLD.generation THEN RAISE EXCEPTION 'Peer file generations must advance' USING ERRCODE='23514'; END IF;
    ELSIF TG_TABLE_NAME='file_sync_receipt' THEN
        IF (NEW.event_id,NEW.envelope_hash,NEW.file_id,NEW.revision,NEW.outcome,NEW.created_at)
            IS DISTINCT FROM (OLD.event_id,OLD.envelope_hash,OLD.file_id,OLD.revision,OLD.outcome,OLD.created_at)
            OR (OLD.published AND NOT NEW.published) THEN RAISE EXCEPTION 'File receipt facts are immutable' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION eatwhat_file.guard_file_sync_state() FROM PUBLIC;
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['file_sync_dirty','file_sync_peer_state','file_sync_receipt'] LOOP
        EXECUTE format('CREATE TRIGGER guard_file_sync_state BEFORE UPDATE OR DELETE ON eatwhat_file.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_file_sync_state()',t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['file_sync_origin','file_sync_dirty','file_sync_peer_state','file_sync_receipt','file_sync_ack'] LOOP
        EXECUTE format('CREATE TRIGGER retain_file_sync_state BEFORE TRUNCATE ON eatwhat_file.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation()',t);
    END LOOP;
END $$;

-- A narrow, owner-executed view lets the GC check active profile heads without broader sync-schema grants.
CREATE VIEW eatwhat_file.file_profile_avatar_keys WITH (security_barrier=true) AS
SELECT f.value #>> '{}' AS logical_key FROM eatwhat_sync.profile_field_state f
JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
JOIN public.users u ON u.id=i.numeric_id WHERE f.field_group='avatar'
UNION ALL
SELECT h->>'value' FROM eatwhat_sync.profile_field_state f
JOIN eatwhat_identity.entity_identity i ON i.global_id=f.subject_gid AND i.entity_type='users'
JOIN public.users u ON u.id=i.numeric_id
CROSS JOIN LATERAL jsonb_array_elements(f.head_set) h WHERE f.field_group='avatar';
REVOKE ALL ON eatwhat_file.file_profile_avatar_keys FROM PUBLIC;

-- Reconcile only the five registered business kinds and numeric business ref ids. Historical events are retained.
-- This repairs leaks left by pre-V34 cascades/projections, including a public key replaced before the upgrade.
DO $$ DECLARE tab text; kind text; col text; active text; expected_biz text; owner_col text; BEGIN
    FOR tab,kind,col,active,expected_biz,owner_col IN VALUES
      ('users','user_avatar','ARRAY[b.avatar]','true','avatar','b.id'),
      ('restaurant','restaurant','b.picture_url','true','restaurants','b.account_id'),
      ('foods','food','b.picture_url','true','foods','b.account_id'),
      ('activity_foods','activity_food','b.picture_url','b.deleted_at IS NULL','activity','b.account_id'),
      ('activity_dinners','activity_dinner','b.picture_url','b.deleted_at IS NULL','activity','b.account_id')
    LOOP
      EXECUTE format('INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason)
        SELECT f.logical_key,f.ref_kind,f.ref_id,''RELEASE'',''RECONCILE'' FROM eatwhat_file.file_reference_live f
        WHERE f.ref_kind=%L AND split_part(f.ref_id,'':'',1) ~ ''^[0-9]+$'' AND NOT EXISTS(
          SELECT 1 FROM public.%I b WHERE b.id::text=split_part(f.ref_id,'':'',1) AND %s AND f.logical_key=ANY(%s))
        ORDER BY f.logical_key,f.ref_id',kind,tab,active,col);
      EXECUTE format('INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility)
        SELECT DISTINCT a.logical_key,%L,b.id::text||'':db:''||md5(a.logical_key),''PUBLIC''
        FROM public.%I b JOIN eatwhat_identity.entity_identity i ON i.entity_type=''users'' AND i.numeric_id=%s
        JOIN eatwhat_file.file_key_alias a ON a.owner_gid=i.global_id AND a.biz=%L AND a.logical_key=ANY(%s)
        WHERE %s AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f
          WHERE f.logical_key=a.logical_key AND f.ref_kind=%L AND (f.ref_id=b.id::text OR f.ref_id LIKE b.id::text||'':%%''))
        ON CONFLICT DO NOTHING',kind,tab,owner_col,expected_biz,col,active,kind);
      EXECUTE format('INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason)
        SELECT f.logical_key,f.ref_kind,f.ref_id,''RESTORE'',''RECONCILE'' FROM eatwhat_file.file_reference f
        WHERE f.ref_kind=%L AND f.ref_id ~ ''^[0-9]+:db:[0-9a-f]{32}$''
        AND (SELECT e.action FROM eatwhat_file.file_reference_event e WHERE e.logical_key=f.logical_key
          AND e.ref_kind=f.ref_kind AND e.ref_id=f.ref_id ORDER BY e.event_id DESC LIMIT 1)=''RELEASE''
        AND EXISTS(SELECT 1 FROM public.%I b WHERE b.id::text=split_part(f.ref_id,'':'',1) AND %s AND f.logical_key=ANY(%s))',kind,tab,active,col);
    END LOOP;
END $$;

-- Retain the already-computed canonical verification proof for pre-V34 local uploads. Never infer byte
-- verification for imported PENDING replicas, legacy URLs, or incomplete upload intents.
UPDATE eatwhat_file.file_replica p SET verified_sha256=r.sha256,verified_size=r.size_bytes,verified_at=r.verified_at
FROM eatwhat_file.file_revision r JOIN eatwhat_file.file_object o USING(file_id)
WHERE p.file_id=r.file_id AND p.revision=r.revision AND p.site='LOCAL' AND p.state='AVAILABLE_LOCAL'
  AND r.state='VERIFIED' AND p.verified_sha256 IS NULL
  AND EXISTS(SELECT 1 FROM eatwhat_file.upload_intent i WHERE i.logical_key=o.logical_key AND i.status='COMPLETE');
