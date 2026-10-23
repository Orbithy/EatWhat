SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE SCHEMA eatwhat_consent;
REVOKE ALL ON SCHEMA eatwhat_consent FROM PUBLIC;

CREATE TABLE eatwhat_consent.subject (
  subject_id uuid PRIMARY KEY,
  user_id bigint NOT NULL UNIQUE CHECK(user_id BETWEEN 1 AND 9007199254740991),
  origin_site varchar(64) NOT NULL,
  epoch bigint NOT NULL DEFAULT 0 CHECK(epoch>=0),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  status varchar(20) NOT NULL CHECK(status IN ('NOT_GRANTED','PROVISIONAL','GRANTED','WITHDRAWN','CONFLICT')),
  policy_version varchar(128) NOT NULL,
  valid_until timestamptz,
  identity_ready boolean NOT NULL DEFAULT false,
  backfill_status varchar(64) NOT NULL,
  cleanup_status varchar(64) NOT NULL DEFAULT 'NOT_REQUIRED',
  withdrawal_request_id uuid,
  source_archived boolean NOT NULL DEFAULT false,
  local_dirty boolean NOT NULL DEFAULT false,
  effects_pending boolean NOT NULL DEFAULT false,
  effects_version bigint NOT NULL DEFAULT 0 CHECK(effects_version>=0),
  registration_revision bigint,
  registration_hash char(64),
  account_hash char(64),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_consent.job (
  job_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_id uuid NOT NULL REFERENCES eatwhat_consent.subject(subject_id),
  user_id bigint NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  revision bigint NOT NULL CHECK(revision>0),
  kind varchar(24) NOT NULL CHECK(kind IN ('UPSERT','PURGE','REGISTER','WITHDRAW_REQUEST','CLEANUP')),
  status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','DONE','CONFLICT')),
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  UNIQUE(subject_id,epoch,revision,kind),
  CHECK((status='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
     OR (status<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX consent_job_ready ON eatwhat_consent.job(available_at,job_id) WHERE status='READY';
CREATE INDEX consent_job_expired ON eatwhat_consent.job(lease_until,job_id) WHERE status='LEASED';
CREATE TABLE eatwhat_consent.proof (
  token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
  subject_id uuid NOT NULL REFERENCES eatwhat_consent.subject(subject_id),
  epoch bigint NOT NULL,
  source_revision bigint NOT NULL,
  policy_version varchar(128) NOT NULL,
  audience varchar(64) NOT NULL,
  security_fingerprint char(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz
);
CREATE TABLE eatwhat_consent.withdrawal_receipt (
  request_id uuid PRIMARY KEY,
  subject_id uuid NOT NULL REFERENCES eatwhat_consent.subject(subject_id),
  authority_epoch bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE eatwhat_consent.conflict_receipt (
  subject_id uuid NOT NULL,
  user_id bigint NOT NULL,
  epoch bigint NOT NULL,
  revision bigint NOT NULL,
  reason varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(subject_id,epoch,revision)
);
-- Only non-reversible fingerprints are retained above; no request body, password hash,
-- alias, account snapshot or profile is persisted in consent/job/audit records.
CREATE TABLE eatwhat_consent.import_context (
  transaction_id bigint NOT NULL,
  backend_id integer NOT NULL,
  entity_type text NOT NULL,
  numeric_id bigint NOT NULL,
  global_id uuid NOT NULL,
  origin_site varchar(64) NOT NULL,
  subject_id uuid NOT NULL,
  PRIMARY KEY(transaction_id,backend_id,entity_type,numeric_id)
);

CREATE FUNCTION eatwhat_consent.guard_subject() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF NEW.subject_id<>OLD.subject_id OR NEW.user_id<>OLD.user_id OR NEW.origin_site<>OLD.origin_site
    OR NEW.epoch<OLD.epoch OR (NEW.epoch=OLD.epoch AND NEW.revision<OLD.revision)
    OR NEW.effects_version<OLD.effects_version THEN
    RAISE EXCEPTION 'Consent identity or version cannot be reassigned or rewound' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER consent_subject_guard BEFORE UPDATE ON eatwhat_consent.subject
FOR EACH ROW EXECUTE FUNCTION eatwhat_consent.guard_subject();

-- Add a narrowly scoped, owner-only import reservation to the existing identity rules.
-- A client-settable session variable never enables this path.
CREATE OR REPLACE FUNCTION eatwhat_identity.guard_business_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_identity AS $$
DECLARE node_mode text; parity integer; allocated_id bigint;
BEGIN
  IF TG_TABLE_SCHEMA<>'public' OR (TG_TABLE_NAME NOT IN ('users','contacts','verifications','activity_foods','activity_dinners','activity_likes','notifications','follow','privacy','hub','restaurant','foods','browse_history','food_tags','food_taggings','food_favorites','likes')
      AND NOT(TG_TABLE_NAME='user_info' AND TG_OP='UPDATE')) THEN
    RAISE EXCEPTION 'Unsupported identity relation' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'Business numeric identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_TABLE_NAME IN ('users','contacts','verifications','privacy') AND EXISTS(
      SELECT 1 FROM eatwhat_consent.import_context c WHERE c.transaction_id=txid_current()
        AND c.backend_id=pg_backend_pid() AND c.entity_type=TG_TABLE_NAME AND c.numeric_id=NEW.id) THEN RETURN NEW; END IF;
  SELECT mode INTO node_mode FROM eatwhat_identity.node_state WHERE singleton;
  IF FOUND THEN
    parity:=CASE WHEN node_mode='CAMPUS' THEN 1 ELSE 0 END;
    IF NEW.id IS NULL OR NEW.id<=1000000000 OR NEW.id>9007199254740991 OR mod(NEW.id,2)<>parity THEN
      RAISE EXCEPTION 'Numeric identity is outside this site allocation domain' USING ERRCODE='23514'; END IF;
    BEGIN allocated_id:=currval(pg_get_serial_sequence(format('%I.%I',TG_TABLE_SCHEMA,TG_TABLE_NAME),'id')::regclass);
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
      RAISE EXCEPTION 'Explicit numeric identity requires allocation in this connection' USING ERRCODE='23514'; END;
    IF NEW.id<>allocated_id THEN RAISE EXCEPTION 'Explicit numeric identity bypassed the local allocator' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_identity.record_business_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_identity AS $$
DECLARE imported eatwhat_consent.import_context%ROWTYPE;
BEGIN
  IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','contacts','verifications','activity_foods','activity_dinners','activity_likes','notifications','follow','privacy','hub','restaurant','foods','browse_history','food_tags','food_taggings','food_favorites','likes') THEN
    RAISE EXCEPTION 'Unsupported identity relation' USING ERRCODE='23514'; END IF;
  SELECT * INTO imported FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND entity_type=TG_TABLE_NAME AND numeric_id=NEW.id;
  IF FOUND THEN
    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
      VALUES(TG_TABLE_NAME,NEW.id,imported.global_id,imported.origin_site) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=TG_TABLE_NAME AND numeric_id=NEW.id
        AND global_id=imported.global_id AND origin_site=imported.origin_site) THEN
      RAISE EXCEPTION 'Imported account identity conflicts with an existing reservation' USING ERRCODE='23505'; END IF;
  ELSE
    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
      VALUES(TG_TABLE_NAME,NEW.id,gen_random_uuid(),(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton));
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION eatwhat_consent.track_account_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid bigint; s eatwhat_consent.subject%ROWTYPE; node eatwhat_identity.node_state%ROWTYPE; kind text;
BEGIN
  IF TG_OP='UPDATE' AND TG_TABLE_NAME NOT IN ('users','user_info') THEN
    IF NEW.account_id IS DISTINCT FROM OLD.account_id THEN
      RAISE EXCEPTION 'Account relationships cannot be reassigned' USING ERRCODE='23514';
    END IF;
  END IF;
  IF TG_TABLE_NAME IN ('users','user_info') THEN
    IF TG_OP='DELETE' THEN uid:=OLD.id; ELSE uid:=NEW.id; END IF;
  ELSE
    IF TG_OP='DELETE' THEN uid:=OLD.account_id; ELSE uid:=NEW.account_id; END IF;
  END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RETURN NULL; END IF;
  SELECT * INTO s FROM eatwhat_consent.subject WHERE user_id=uid FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND subject_id=s.subject_id) THEN RETURN NULL; END IF;
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
    IF s.status='GRANTED' THEN kind:='UPSERT';
    ELSIF s.status='WITHDRAWN' THEN kind:='PURGE'; END IF;
  ELSIF (s.status IN ('PROVISIONAL','CONFLICT') OR (s.status='WITHDRAWN' AND NOT s.source_archived)) AND s.origin_site=node.site_id THEN
    UPDATE eatwhat_consent.subject SET revision=revision+1,source_archived=false WHERE subject_id=s.subject_id RETURNING * INTO s;
    kind:='REGISTER';
  ELSIF s.status='GRANTED' THEN
    UPDATE eatwhat_consent.subject SET local_dirty=true,backfill_status='CONFLICT',revision=revision+1,
      effects_pending=true,effects_version=effects_version+1
      WHERE subject_id=s.subject_id;
  END IF;
  IF kind IS NOT NULL THEN
    INSERT INTO eatwhat_consent.job(subject_id,user_id,epoch,revision,kind)
      VALUES(s.subject_id,s.user_id,s.epoch,s.revision,kind) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NULL;
END $$;
DO $$ DECLARE name text; BEGIN
  FOREACH name IN ARRAY ARRAY['users','contacts','verifications','privacy','user_info'] LOOP
    EXECUTE format('CREATE TRIGGER consent_account_revision AFTER INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION eatwhat_consent.track_account_change()',name);
  END LOOP;
END $$;

-- The caller has locked/validated the epoch. This function only imports the explicit account scope;
-- unique/FK failures roll back its subtransaction and never overwrite or merge another identity.
CREATE FUNCTION eatwhat_consent.import_account(p_subject uuid, p_uid bigint, payload jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; node eatwhat_identity.node_state%ROWTYPE;
  entity text; item jsonb; fields text; allowed text[]; column_name text; numeric bigint; gid uuid; origin text;
  present boolean; same boolean; updates text;
BEGIN
  SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject AND user_id=p_uid FOR UPDATE;
  IF NOT FOUND OR s.status NOT IN ('GRANTED','PROVISIONAL','WITHDRAWN') THEN
    RAISE EXCEPTION 'Account import requires a current consent operation' USING ERRCODE='23514'; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND OR jsonb_typeof(payload)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(payload))<>5 THEN
    RAISE EXCEPTION 'Invalid account snapshot' USING ERRCODE='23514'; END IF;
  BEGIN
    FOREACH entity IN ARRAY ARRAY['users','contacts','verifications','user_info','privacy'] LOOP
      IF NOT(payload ? entity) OR jsonb_typeof(payload->entity)<>'array' OR jsonb_array_length(payload->entity)>64 THEN
        RAISE EXCEPTION 'Invalid bounded account scope' USING ERRCODE='23514'; END IF;
      IF (entity='users' AND jsonb_array_length(payload->entity)<>1)
        OR (entity IN ('verifications','user_info','privacy') AND jsonb_array_length(payload->entity)>1) THEN
        RAISE EXCEPTION 'Invalid account cardinality' USING ERRCODE='23514'; END IF;
      fields:=CASE entity
        WHEN 'users' THEN 'id,nick_name,password_hash,avatar,created_at,updated_at,banned,ban_reason,banned_at,role'
        WHEN 'contacts' THEN 'id,account_id,email,phone,created_at,updated_at'
        WHEN 'verifications' THEN 'id,account_id,method,verified,student_id,real_name,verified_email,created_at,updated_at'
        WHEN 'user_info' THEN 'id,gender,birthday,signature,location_province_id,location_city_id,hometown_province_id,hometown_city_id,created_at,updated_at'
        WHEN 'privacy' THEN 'id,account_id,following,follower,created_at' END;
      allowed:=string_to_array(fields,',');
      FOR item IN SELECT value FROM jsonb_array_elements(payload->entity) LOOP
        IF jsonb_typeof(item)<>'object' THEN RAISE EXCEPTION 'Invalid account row' USING ERRCODE='23514'; END IF;
        FOR column_name IN SELECT jsonb_object_keys(item) LOOP
          IF NOT(column_name=ANY(allowed)) AND NOT(entity<>'user_info' AND column_name IN ('global_id','origin_site')) THEN
            RAISE EXCEPTION 'Unknown account field' USING ERRCODE='23514'; END IF;
        END LOOP;
        numeric:=(item->>'id')::bigint;
        IF numeric IS NULL OR numeric<=1000000000 OR numeric>9007199254740991 THEN
          RAISE EXCEPTION 'Invalid imported numeric identity' USING ERRCODE='23514'; END IF;
        IF entity IN ('users','user_info') THEN
          IF numeric<>p_uid THEN RAISE EXCEPTION 'Wrong account p_subject' USING ERRCODE='23514'; END IF;
        ELSIF (item->>'account_id')::bigint IS DISTINCT FROM p_uid THEN
          RAISE EXCEPTION 'Wrong account relationship' USING ERRCODE='23514'; END IF;
        IF entity<>'user_info' THEN
          gid:=(item->>'global_id')::uuid; origin:=item->>'origin_site';
          IF gid IS NULL OR gid='00000000-0000-0000-0000-000000000000'::uuid OR origin IS NULL
            OR (entity='users' AND (gid<>p_subject OR origin<>s.origin_site)) THEN
            RAISE EXCEPTION 'Invalid imported global identity' USING ERRCODE='23514'; END IF;
          SELECT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=numeric),
            EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=numeric AND global_id=gid AND origin_site=origin)
            INTO present,same;
          IF (present AND NOT same) OR EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid AND (entity_type<>entity OR numeric_id<>numeric)) THEN
            RAISE EXCEPTION 'Imported identity collision' USING ERRCODE='23505'; END IF;
          IF NOT present AND ((origin=node.site_id) OR mod(numeric,2)=CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END) THEN
            RAISE EXCEPTION 'Cannot reserve new identities in the local allocation domain' USING ERRCODE='23514'; END IF;
          INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),entity,numeric,gid,origin,p_subject);
        END IF;
        -- Existing children must already belong to this account; a numeric collision is never a reassignment.
        IF entity NOT IN ('users','user_info') THEN
          EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1 AND account_id<>$2)',entity) INTO present USING numeric,p_uid;
          IF present THEN RAISE EXCEPTION 'Account child belongs to another p_subject' USING ERRCODE='23505'; END IF;
        END IF;
        SELECT string_agg(format('%1$I=EXCLUDED.%1$I',v),',') INTO updates FROM unnest(allowed) v WHERE v<>'id';
        EXECUTE format('INSERT INTO public.%1$I (%2$s) SELECT %2$s FROM jsonb_populate_record(NULL::public.%1$I,$1) ON CONFLICT(id) DO UPDATE SET %3$s',entity,fields,updates)
          USING item-'global_id'-'origin_site';
      END LOOP;
    END LOOP;
    -- Remove stale account aliases/profile/verification from an older authorized snapshot only.
    FOREACH entity IN ARRAY ARRAY['contacts','verifications','privacy'] LOOP
      EXECUTE format('DELETE FROM public.%I WHERE account_id=$1 AND id NOT IN (SELECT (value->>''id'')::bigint FROM jsonb_array_elements($2))',entity)
        USING p_uid,payload->entity;
    END LOOP;
    IF jsonb_array_length(payload->'user_info')=0 THEN DELETE FROM public.user_info WHERE id=p_uid; END IF;
    DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
    RETURN true;
  EXCEPTION WHEN unique_violation OR foreign_key_violation THEN RETURN false;
  END;
END $$;

CREATE FUNCTION eatwhat_consent.purge_account(p_subject uuid, p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; dependency record; blocked boolean;
BEGIN
  SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject AND user_id=p_uid FOR UPDATE;
  IF NOT FOUND OR s.status<>'WITHDRAWN' OR (SELECT mode FROM eatwhat_identity.node_state WHERE singleton) IS DISTINCT FROM 'EXTERNAL' THEN
    RAISE EXCEPTION 'Only withdrawn external account projections may be purged' USING ERRCODE='23514'; END IF;
  IF s.origin_site=(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton) AND NOT s.source_archived THEN RETURN false; END IF;
  -- Serialize foreign-key insertion with the dependency scan, so an unseen concurrent
  -- ON DELETE CASCADE row cannot be deleted by this account-only cleanup.
  PERFORM id FROM public.users WHERE id=p_uid FOR UPDATE;
  -- Other domains are not implemented. Never cascade-delete their data as an account cleanup shortcut.
  FOR dependency IN
    SELECT n.nspname,c.relname,a.attname FROM pg_constraint fk JOIN pg_class c ON c.oid=fk.conrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=fk.conkey[1]
    WHERE fk.contype='f' AND fk.confrelid='public.users'::regclass
      AND NOT(n.nspname='public' AND c.relname IN ('contacts','verifications','privacy','user_info'))
  LOOP
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.%I WHERE %I=$1)',dependency.nspname,dependency.relname,dependency.attname) INTO blocked USING p_uid;
    IF blocked THEN RETURN false; END IF;
  END LOOP;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'users',p_uid,p_subject,s.origin_site,p_subject);
  DELETE FROM public.contacts WHERE account_id=p_uid;
  DELETE FROM public.verifications WHERE account_id=p_uid;
  DELETE FROM public.privacy WHERE account_id=p_uid;
  DELETE FROM public.user_info WHERE id=p_uid;
  DELETE FROM public.users WHERE id=p_uid;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
  RETURN true;
END $$;

REVOKE ALL ON ALL TABLES IN SCHEMA eatwhat_consent FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA eatwhat_consent FROM PUBLIC;
