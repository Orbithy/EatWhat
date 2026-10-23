SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Controlled inbound activity import. Remote numeric identities may only enter through the two
-- SECURITY DEFINER functions below, which record an owner-only import context for this transaction.
-- The runtime role receives EXECUTE on those functions only (see SyncSchemaMigrationCallback);
-- it cannot write the import context, the identity registry or rewrite another site's allocation domain.

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
  IF TG_TABLE_NAME IN ('users','contacts','verifications','privacy','activity_foods','activity_dinners') AND EXISTS(
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

-- Applies one remote activity creation. Returns APPLIED or ALREADY_APPLIED; identity, parity, author
-- and field-shape violations raise and roll back the caller's Inbox transaction.
CREATE FUNCTION eatwhat_sync.apply_activity_created(p_table text, p_payload jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE;
  allowed text[]; k text; numeric bigint; gid uuid; origin text; author_gid uuid; author_id bigint; existing uuid;
  pictures text[]; created timestamptz; local_parity integer;
BEGIN
  IF p_table NOT IN ('activity_foods','activity_dinners') OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Unsupported activity import' USING ERRCODE='23514'; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Activity import requires an explicit site' USING ERRCODE='23514'; END IF;
  allowed:=CASE p_table
    WHEN 'activity_foods' THEN ARRAY['numeric_id','global_id','origin_site','author_global_id','food_name','description','province_id','city_id','picture_url','created_at']
    ELSE ARRAY['numeric_id','global_id','origin_site','author_global_id','description','picture_url','created_at'] END;
  FOR k IN SELECT jsonb_object_keys(p_payload) LOOP
    IF NOT(k=ANY(allowed)) THEN RAISE EXCEPTION 'Unknown activity field' USING ERRCODE='23514'; END IF;
  END LOOP;
  numeric:=(p_payload->>'numeric_id')::bigint; gid:=(p_payload->>'global_id')::uuid;
  origin:=p_payload->>'origin_site'; author_gid:=(p_payload->>'author_global_id')::uuid;
  local_parity:=CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END;
  IF numeric IS NULL OR numeric<=1000000000 OR numeric>9007199254740991 OR mod(numeric,2)=local_parity
     OR gid IS NULL OR gid='00000000-0000-0000-0000-000000000000'::uuid OR author_gid IS NULL
     OR origin IS NULL OR origin=node.site_id THEN
    RAISE EXCEPTION 'Invalid imported activity identity' USING ERRCODE='23514'; END IF;
  IF jsonb_typeof(p_payload->'picture_url')<>'array' OR jsonb_array_length(p_payload->'picture_url') NOT BETWEEN 1 AND 9 THEN
    RAISE EXCEPTION 'Imported activity requires one to nine pictures' USING ERRCODE='23514'; END IF;
  SELECT array_agg(value) INTO pictures FROM jsonb_array_elements_text(p_payload->'picture_url');
  created:=to_timestamp(((p_payload->>'created_at')::bigint)/1000.0);
  SELECT numeric_id INTO author_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=author_gid;
  IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=author_id) THEN
    RAISE EXCEPTION 'Activity author is not available on this site' USING ERRCODE='23503'; END IF;
  SELECT global_id INTO existing FROM eatwhat_identity.entity_identity WHERE entity_type=p_table AND numeric_id=numeric;
  IF FOUND THEN
    IF existing<>gid THEN RAISE EXCEPTION 'Imported activity identity collision' USING ERRCODE='23505'; END IF;
    RETURN 'ALREADY_APPLIED';
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid) THEN
    RAISE EXCEPTION 'Imported activity identity collision' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),p_table,numeric,gid,origin,author_gid);
  IF p_table='activity_foods' THEN
    INSERT INTO public.activity_foods(id,account_id,food_name,description,province_id,city_id,picture_url,created_at)
    VALUES(numeric,author_id,p_payload->>'food_name',p_payload->>'description',
           (p_payload->>'province_id')::int,(p_payload->>'city_id')::int,pictures,created);
  ELSE
    INSERT INTO public.activity_dinners(id,account_id,description,picture_url,created_at)
    VALUES(numeric,author_id,p_payload->>'description',pictures,created);
  END IF;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
  RETURN 'APPLIED';
END $$;

-- Soft deletion only; the row, its identity and any later duplicate creation stay in place, so an old
-- creation can never resurrect the activity. Repeating a deletion is a no-op.
CREATE FUNCTION eatwhat_sync.apply_activity_deleted(p_table text, p_numeric bigint, p_gid uuid, p_deleted_at timestamptz) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE changed integer; present boolean;
BEGIN
  IF p_table NOT IN ('activity_foods','activity_dinners') THEN
    RAISE EXCEPTION 'Unsupported activity deletion' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=p_table AND numeric_id=p_numeric AND global_id=p_gid) THEN
    RAISE EXCEPTION 'Deleted activity identity is unknown' USING ERRCODE='23503'; END IF;
  EXECUTE format('UPDATE public.%I SET deleted_at=$2 WHERE id=$1 AND deleted_at IS NULL',p_table) USING p_numeric,p_deleted_at;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=1 THEN RETURN 'DELETED'; END IF;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',p_table) INTO present USING p_numeric;
  -- A withdrawn author's replica may have been purged; the identity tombstone remains, so this is a no-op.
  IF NOT present THEN RETURN 'ABSENT'; END IF;
  RETURN 'ALREADY_DELETED';
END $$;

-- Withdrawal cleanup now owns replicated activity content. Everything else in purge_account is unchanged:
-- other domains still block the purge, and nothing cascades. Activities (and their identity tombstones and
-- sync state) are removed first, but only when no outbound activity event is still undelivered, so a
-- withdrawal can never destroy the only copy of content the peer has not received yet.
CREATE OR REPLACE FUNCTION eatwhat_consent.purge_account(p_subject uuid, p_uid bigint) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE s eatwhat_consent.subject%ROWTYPE; dependency record; blocked boolean;
BEGIN
  SELECT * INTO s FROM eatwhat_consent.subject WHERE subject_id=p_subject AND user_id=p_uid FOR UPDATE;
  IF NOT FOUND OR s.status<>'WITHDRAWN' OR (SELECT mode FROM eatwhat_identity.node_state WHERE singleton) IS DISTINCT FROM 'EXTERNAL' THEN
    RAISE EXCEPTION 'Only withdrawn external account projections may be purged' USING ERRCODE='23514'; END IF;
  IF s.origin_site=(SELECT site_id FROM eatwhat_identity.node_state WHERE singleton) AND NOT s.source_archived THEN RETURN false; END IF;
  PERFORM id FROM public.users WHERE id=p_uid FOR UPDATE;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
      WHERE d.status<>'ACKED' AND ((o.aggregate_type='activity_foods' AND o.numeric_id IN (SELECT id FROM public.activity_foods WHERE account_id=p_uid))
        OR (o.aggregate_type='activity_dinners' AND o.numeric_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid)))) THEN
    RETURN false; END IF;
  DELETE FROM public.activity_foods WHERE account_id=p_uid;
  DELETE FROM public.activity_dinners WHERE account_id=p_uid;
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
REVOKE ALL ON FUNCTION eatwhat_consent.purge_account(uuid,bigint) FROM PUBLIC;

REVOKE ALL ON FUNCTION eatwhat_sync.apply_activity_created(text,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.apply_activity_deleted(text,bigint,uuid,timestamptz) FROM PUBLIC;
