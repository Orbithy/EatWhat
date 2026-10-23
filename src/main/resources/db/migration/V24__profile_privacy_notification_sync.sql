SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- PR-05D keeps these domains separate from account snapshots.  The ownership row
-- survives a temporary feature-flag change and account projection purge.
CREATE TABLE eatwhat_sync.profile_domain (
  subject_gid uuid PRIMARY KEY,
  baseline_id uuid NOT NULL,
  baseline_origin varchar(64) NOT NULL,
  baseline_value jsonb NOT NULL CHECK(jsonb_typeof(baseline_value)='object'),
  baseline_hash char(64) NOT NULL CHECK(baseline_hash ~ '^[0-9a-f]{64}$'),
  vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(vector)='object'),
  active_epoch bigint NOT NULL DEFAULT -1 CHECK(active_epoch>=-1),
  published_epoch bigint NOT NULL DEFAULT -1 CHECK(published_epoch>=-1),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  managed boolean NOT NULL DEFAULT true,
  local_dirty boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.profile_operation (
  event_id uuid PRIMARY KEY,
  subject_gid uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  baseline_id uuid NOT NULL,
  origin_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter>0),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  changes jsonb NOT NULL CHECK(jsonb_typeof(changes)='object'),
  head_bridge jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(head_bridge)='object'),
  resolution_bridge jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(resolution_bridge)='object'),
  resolution jsonb CHECK(resolution IS NULL OR jsonb_typeof(resolution)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.profile_field_state (
  subject_gid uuid NOT NULL,
  field_group varchar(32) NOT NULL CHECK(field_group IN ('avatar','gender','birthday','signature','hometown')),
  value jsonb NOT NULL,
  applied_vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(applied_vector)='object'),
  head_id uuid,
  head_set jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(head_set)='array'),
  local_dirty boolean NOT NULL DEFAULT false,
  conflict_revision bigint NOT NULL DEFAULT 0 CHECK(conflict_revision>=0),
  PRIMARY KEY(subject_gid,field_group)
);
CREATE TABLE eatwhat_sync.profile_conflict (
  conflict_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_gid uuid NOT NULL,
  field_group varchar(32) NOT NULL,
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  heads jsonb NOT NULL CHECK(jsonb_typeof(heads)='array'),
  retired_heads jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(retired_heads)='array'),
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
  resolved_head uuid,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(subject_gid,field_group)
);
CREATE TABLE eatwhat_sync.profile_conflict_audit (
  audit_id uuid PRIMARY KEY,
  conflict_id uuid NOT NULL REFERENCES eatwhat_sync.profile_conflict(conflict_id),
  expected_revision bigint NOT NULL,
  selected_head uuid NOT NULL,
  administrator_id bigint NOT NULL,
  event_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.privacy_domain (
  subject_gid uuid PRIMARY KEY,
  vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(vector)='object'),
  active_epoch bigint NOT NULL DEFAULT -1 CHECK(active_epoch>=-1),
  published_epoch bigint NOT NULL DEFAULT -1 CHECK(published_epoch>=-1),
  following_value boolean NOT NULL DEFAULT true,
  follower_value boolean NOT NULL DEFAULT true,
  following_vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(following_vector)='object'),
  follower_vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(follower_vector)='object'),
  following_false_context jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(following_false_context)='object'),
  follower_false_context jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(follower_false_context)='object'),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  managed boolean NOT NULL DEFAULT true,
  local_dirty boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.privacy_operation (
  event_id uuid PRIMARY KEY,
  subject_gid uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  origin_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter>0),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  following_value boolean,
  following_context jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(following_context)='object'),
  follower_value boolean,
  follower_context jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(follower_context)='object'),
  state_bridge jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(state_bridge)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK(following_value IS NOT NULL OR follower_value IS NOT NULL OR state_bridge<>'{}'::jsonb)
);
CREATE TABLE eatwhat_sync.notification_read_operation (
  event_id uuid PRIMARY KEY,
  numeric_id bigint NOT NULL UNIQUE CHECK(numeric_id>0),
  notification_gid uuid NOT NULL,
  recipient_gid uuid NOT NULL,
  actor_gid uuid NOT NULL,
  actor_epoch bigint NOT NULL CHECK(actor_epoch>=0),
  epoch bigint NOT NULL CHECK(epoch>=0),
  origin_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter>0),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(notification_gid,recipient_gid,origin_site,dot_counter)
);
CREATE SEQUENCE eatwhat_sync.notification_read_operation_numeric_id_seq MAXVALUE 4503599627370495;
CREATE FUNCTION eatwhat_sync.next_notification_read_number() RETURNS bigint
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
  SELECT 2*nextval('eatwhat_sync.notification_read_operation_numeric_id_seq')-
    CASE WHEN (SELECT mode FROM eatwhat_identity.node_state WHERE singleton)='CAMPUS' THEN 1 ELSE 0 END
$$;
CREATE TABLE eatwhat_sync.notification_read_state (
  notification_gid uuid NOT NULL,
  recipient_gid uuid NOT NULL,
  vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(vector)='object'),
  PRIMARY KEY(notification_gid,recipient_gid)
);
CREATE TABLE eatwhat_sync.notification_read_domain (
  recipient_gid uuid PRIMARY KEY,
  managed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_sync.notification_read_batch (
  batch_id uuid PRIMARY KEY,
  recipient_gid uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','DONE','WAIT')),
  lease_token uuid,
  lease_until timestamptz,
  cursor_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((status='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
     OR (status<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE TABLE eatwhat_sync.notification_read_member (
  batch_id uuid NOT NULL REFERENCES eatwhat_sync.notification_read_batch(batch_id),
  notification_gid uuid NOT NULL,
  notification_id bigint NOT NULL,
  recipient_epoch bigint NOT NULL DEFAULT 0 CHECK(recipient_epoch>=0),
  actor_gid uuid,
  actor_epoch bigint,
  event_id uuid,
  status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','DONE','WAIT','SKIP')),
  last_attempt_at timestamptz,
  PRIMARY KEY(batch_id,notification_gid),
  UNIQUE(batch_id,notification_id),
  CHECK((actor_gid IS NULL)=(actor_epoch IS NULL))
);
CREATE INDEX notification_read_member_ready ON eatwhat_sync.notification_read_member(batch_id,notification_gid) WHERE status='READY';

-- Domain operations are immutable facts. Mutable projections and leases remain narrowly writable.
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['profile_operation','profile_conflict_audit','privacy_operation','notification_read_operation'] LOOP
    EXECUTE format('CREATE TRIGGER %I_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON eatwhat_sync.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.relation_immutable()',t,t);
  END LOOP;
END $$;

REVOKE ALL ON eatwhat_sync.profile_domain,eatwhat_sync.profile_operation,eatwhat_sync.profile_field_state,
  eatwhat_sync.profile_conflict,eatwhat_sync.profile_conflict_audit,eatwhat_sync.privacy_domain,
  eatwhat_sync.privacy_operation,eatwhat_sync.notification_read_operation,eatwhat_sync.notification_read_state,
  eatwhat_sync.notification_read_batch,eatwhat_sync.notification_read_member FROM PUBLIC;
REVOKE ALL ON eatwhat_sync.notification_read_domain FROM PUBLIC;
REVOKE ALL ON SEQUENCE eatwhat_sync.notification_read_operation_numeric_id_seq FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.next_notification_read_number() FROM PUBLIC;

-- Account snapshots remain authoritative for account/security data. Once a domain row
-- exists, snapshot imports restore its managed fields from the durable domain projection.
CREATE FUNCTION eatwhat_sync.preserve_profile_snapshot_fields() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_subject uuid; v_managed boolean; v_value jsonb; v_domain jsonb; v_status text;
BEGIN
  IF TG_OP='DELETE' THEN
    SELECT i.global_id INTO v_subject FROM eatwhat_identity.entity_identity i
      WHERE i.entity_type='users' AND i.numeric_id=OLD.id;
  ELSE
    SELECT i.global_id INTO v_subject FROM eatwhat_identity.entity_identity i
      WHERE i.entity_type='users' AND i.numeric_id=NEW.id;
  END IF;
  IF v_subject IS NULL OR NOT EXISTS(SELECT 1 FROM eatwhat_consent.import_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid() AND c.subject_id=v_subject) THEN
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
  END IF;
  SELECT managed,baseline_value INTO v_managed,v_domain FROM eatwhat_sync.profile_domain WHERE subject_gid=v_subject;
  IF NOT COALESCE(v_managed,false) THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
  IF TG_TABLE_NAME='users' THEN
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    SELECT value INTO v_value FROM eatwhat_sync.profile_field_state WHERE subject_gid=v_subject AND field_group='avatar';
    NEW.avatar:=COALESCE(v_value,v_domain->'avatar') #>> '{}';
  ELSIF TG_TABLE_NAME='user_info' THEN
    IF TG_OP='DELETE' THEN
      SELECT status INTO v_status FROM eatwhat_consent.subject WHERE subject_id=v_subject;
      IF v_status IS DISTINCT FROM 'WITHDRAWN' THEN RETURN NULL; END IF;
      RETURN OLD;
    END IF;
    SELECT value INTO v_value FROM eatwhat_sync.profile_field_state WHERE subject_gid=v_subject AND field_group='gender';
    NEW.gender:=COALESCE(v_value,v_domain->'gender') #>> '{}';
    SELECT value INTO v_value FROM eatwhat_sync.profile_field_state WHERE subject_gid=v_subject AND field_group='birthday';
    NEW.birthday:=NULLIF(COALESCE(v_value,v_domain->'birthday') #>> '{}','')::date;
    SELECT value INTO v_value FROM eatwhat_sync.profile_field_state WHERE subject_gid=v_subject AND field_group='signature';
    NEW.signature:=COALESCE(v_value,v_domain->'signature') #>> '{}';
    SELECT value INTO v_value FROM eatwhat_sync.profile_field_state WHERE subject_gid=v_subject AND field_group='hometown';
    v_value:=COALESCE(v_value,v_domain->'hometown');
    NEW.hometown_province_id:=NULLIF(v_value->>'provinceId','null')::integer;
    NEW.hometown_city_id:=NULLIF(v_value->>'cityId','null')::integer;
    -- IP-derived location is node-local and is never part of the profile stream.
    IF TG_OP='UPDATE' THEN
      NEW.location_province_id:=OLD.location_province_id;
      NEW.location_city_id:=OLD.location_city_id;
    ELSE
      NEW.location_province_id:=NULL;
      NEW.location_city_id:=NULL;
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION eatwhat_sync.preserve_privacy_snapshot_fields() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_subject uuid; v_managed boolean; v_following boolean; v_follower boolean; v_status text;
BEGIN
  SELECT i.global_id INTO v_subject FROM eatwhat_identity.entity_identity i
    WHERE i.entity_type='users' AND i.numeric_id=CASE WHEN TG_OP='DELETE' THEN OLD.account_id ELSE NEW.account_id END;
  IF v_subject IS NULL OR NOT EXISTS(SELECT 1 FROM eatwhat_consent.import_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid() AND c.subject_id=v_subject) THEN
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
  END IF;
  SELECT managed,following_value,follower_value INTO v_managed,v_following,v_follower
    FROM eatwhat_sync.privacy_domain WHERE subject_gid=v_subject;
  IF NOT COALESCE(v_managed,false) THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
  IF TG_OP='DELETE' THEN
    SELECT status INTO v_status FROM eatwhat_consent.subject WHERE subject_id=v_subject;
    IF v_status IS DISTINCT FROM 'WITHDRAWN' THEN RETURN NULL; END IF;
    RETURN OLD;
  END IF;
  NEW.following:=v_following; NEW.follower:=v_follower;
  RETURN NEW;
END $$;

CREATE TRIGGER sync_preserve_profile_user_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.users
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.preserve_profile_snapshot_fields();
CREATE TRIGGER sync_preserve_profile_info_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.user_info
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.preserve_profile_snapshot_fields();
CREATE TRIGGER sync_preserve_privacy_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.privacy
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.preserve_privacy_snapshot_fields();
REVOKE ALL ON FUNCTION eatwhat_sync.preserve_profile_snapshot_fields(),
  eatwhat_sync.preserve_privacy_snapshot_fields() FROM PUBLIC;

-- Profile/privacy-only edits are emitted by their own operation streams and must not
-- create account snapshot conflicts or overwrite their authorization state.
CREATE OR REPLACE FUNCTION eatwhat_consent.track_account_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid bigint; s eatwhat_consent.subject%ROWTYPE; node eatwhat_identity.node_state%ROWTYPE; kind text;
  gid uuid; managed boolean;
BEGIN
  IF TG_OP='UPDATE' AND TG_TABLE_NAME NOT IN ('users','user_info') THEN
    IF NEW.account_id IS DISTINCT FROM OLD.account_id THEN
      RAISE EXCEPTION 'Account relationships cannot be reassigned' USING ERRCODE='23514';
    END IF;
  END IF;
  IF TG_TABLE_NAME IN ('users','user_info') THEN uid:=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
  ELSE uid:=CASE WHEN TG_OP='DELETE' THEN OLD.account_id ELSE NEW.account_id END; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RETURN NULL; END IF;
  SELECT * INTO s FROM eatwhat_consent.subject WHERE user_id=uid FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND subject_id=s.subject_id) THEN RETURN NULL; END IF;

  IF TG_TABLE_NAME='users' AND TG_OP='UPDATE' THEN
    IF (to_jsonb(NEW)-'avatar'-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'avatar'-'updated_at')
        AND EXISTS(SELECT 1 FROM eatwhat_sync.profile_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;
  IF TG_TABLE_NAME='user_info' AND TG_OP IN ('INSERT','UPDATE') THEN
    IF EXISTS(SELECT 1 FROM eatwhat_sync.profile_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;
  IF TG_TABLE_NAME='privacy' AND TG_OP IN ('INSERT','UPDATE') THEN
    IF EXISTS(SELECT 1 FROM eatwhat_sync.privacy_domain d WHERE d.subject_gid=s.subject_id AND d.managed) THEN
      RETURN NULL;
    END IF;
  END IF;

  IF TG_OP='DELETE' AND TG_TABLE_NAME='users' THEN
    UPDATE eatwhat_consent.subject SET epoch=epoch+CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END,
      revision=revision+1,status='WITHDRAWN',valid_until=NULL,identity_ready=false,backfill_status='WITHDRAWN',
      cleanup_status='PENDING',effects_pending=true,effects_version=effects_version+1,
      withdrawal_request_id=CASE WHEN node.mode='EXTERNAL' THEN gen_random_uuid() ELSE withdrawal_request_id END
      WHERE subject_id=s.subject_id RETURNING * INTO s;
    kind:=CASE WHEN node.mode='CAMPUS' THEN 'PURGE' ELSE 'WITHDRAW_REQUEST' END;
  ELSIF node.mode='CAMPUS' THEN
    UPDATE eatwhat_consent.subject SET revision=revision+1,
      backfill_status=CASE WHEN status='GRANTED' THEN 'ACCOUNT_PENDING' ELSE backfill_status END
      WHERE subject_id=s.subject_id RETURNING * INTO s;
    IF s.status='GRANTED' THEN kind:='UPSERT'; ELSIF s.status='WITHDRAWN' THEN kind:='PURGE'; END IF;
  ELSIF (s.status IN ('PROVISIONAL','CONFLICT') OR (s.status='WITHDRAWN' AND NOT s.source_archived)) AND s.origin_site=node.site_id THEN
    UPDATE eatwhat_consent.subject SET revision=revision+1,source_archived=false WHERE subject_id=s.subject_id RETURNING * INTO s;
    kind:='REGISTER';
  ELSIF s.status='GRANTED' THEN
    UPDATE eatwhat_consent.subject SET local_dirty=true,backfill_status='CONFLICT',revision=revision+1,
      effects_pending=true,effects_version=effects_version+1 WHERE subject_id=s.subject_id;
  END IF;
  IF kind IS NOT NULL THEN
    INSERT INTO eatwhat_consent.job(subject_id,user_id,epoch,revision,kind)
      VALUES(s.subject_id,s.user_id,s.epoch,s.revision,kind) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NULL;
END $$;
