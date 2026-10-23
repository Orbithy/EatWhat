SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
-- Preserve the complete bounded projection functions. Only allocation proofs change in cluster mode.
CREATE FUNCTION eatwhat_cluster.valid_origin_number(n bigint,origin text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE; slot integer;
BEGIN
 IF n IS NULL OR origin IS NULL OR n<=1000000000 OR n>9007199254740991 THEN RETURN false; END IF;
 SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
 IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.allocation WHERE singleton) THEN
   RETURN mod(n,2)=CASE WHEN origin=node.site_id THEN CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END
     ELSE CASE WHEN node.mode='CAMPUS' THEN 0 ELSE 1 END END;
 END IF;
 SELECT a.slot INTO slot FROM eatwhat_identity.allocation a WHERE a.singleton AND a.site_id=origin;
 IF slot IS NULL THEN
   SELECT (p->>'allocationSlot')::integer INTO slot FROM eatwhat_cluster.local_change c,
      jsonb_array_elements(c.plan->'participants') p WHERE p->>'siteId'=origin LIMIT 1;
 END IF;
 IF slot IS NULL THEN RETURN false; END IF;
 IF slot<2 THEN RETURN n<4000000000000 AND mod(n,2)=CASE WHEN slot=0 THEN 1 ELSE 0 END; END IF;
 RETURN n>=4000000000000+slot AND mod(n-4000000000000-slot,1024)=0;
END $$;
REVOKE ALL ON FUNCTION eatwhat_cluster.valid_origin_number(bigint,text) FROM PUBLIC;

CREATE OR REPLACE FUNCTION eatwhat_consent.import_account(p_subject uuid, p_uid bigint, payload jsonb) RETURNS boolean
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
          IF NOT present AND (origin=node.site_id OR NOT eatwhat_cluster.valid_origin_number(numeric,origin)) THEN
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

CREATE OR REPLACE FUNCTION eatwhat_sync.apply_activity_created(p_table text, p_payload jsonb) RETURNS text
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
  IF numeric IS NULL OR numeric<=1000000000 OR numeric>9007199254740991 OR NOT eatwhat_cluster.valid_origin_number(numeric,origin)
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

CREATE OR REPLACE FUNCTION eatwhat_sync.project_relation(p_kind text,p_actor bigint,p_target bigint,p_tag bigint,
  p_present boolean,p_row bigint DEFAULT NULL,p_gid uuid DEFAULT NULL,p_origin text DEFAULT NULL,
  p_event_origin text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE tbl text; typ text; col text; condition text; k text; row_id bigint; row_gid uuid; row_origin text;
  existing eatwhat_identity.entity_identity%ROWTYPE; saved eatwhat_sync.relation_projection%ROWTYPE;
  node eatwhat_identity.node_state%ROWTYPE; expected_parity integer; identity_found boolean;
  business_row_found boolean; business_row_matches boolean; projected_matches boolean;
BEGIN
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Relationship import needs an explicit site' USING ERRCODE='23514'; END IF;
  CASE p_kind
    WHEN 'food_like' THEN tbl:='likes';typ:='food';col:='target_id';
    WHEN 'activity_food_like' THEN tbl:='activity_likes';typ:='food';col:='activity_id';
    WHEN 'activity_dinner_like' THEN tbl:='activity_likes';typ:='dinner';col:='activity_id';
    WHEN 'favorite' THEN tbl:='food_favorites';col:='food_id';
    WHEN 'follow' THEN tbl:='follow';col:='target_id';
    WHEN 'tagging' THEN tbl:='food_taggings';col:='food_id';
    ELSE RAISE EXCEPTION 'Unsupported relationship' USING ERRCODE='23514';
  END CASE;
  k:=p_kind||':'||p_actor||':'||p_target||':'||coalesce(p_tag,0);
  PERFORM pg_advisory_xact_lock(hashtextextended(k,0));
  IF p_actor IS NULL OR p_target IS NULL OR p_event_origin IS NULL
    OR (p_row IS NOT NULL AND p_event_origin<>node.site_id
        AND p_origin IS DISTINCT FROM node.site_id AND p_origin IS DISTINCT FROM p_event_origin)
    OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=p_actor)
    OR (p_kind='follow' AND (p_actor=p_target OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=p_target))) THEN
    RAISE EXCEPTION 'Relationship dependency unavailable' USING ERRCODE='23503'; END IF;
  IF p_kind IN ('food_like','favorite','tagging') THEN
    PERFORM 1 FROM public.foods WHERE id=p_target FOR UPDATE;
  ELSIF p_kind='activity_food_like' THEN
    PERFORM 1 FROM public.activity_foods WHERE id=p_target FOR UPDATE;
  ELSIF p_kind='activity_dinner_like' THEN
    PERFORM 1 FROM public.activity_dinners WHERE id=p_target FOR UPDATE;
  ELSE
    PERFORM 1 FROM public.users WHERE id=p_target FOR KEY SHARE;
  END IF;
  IF NOT FOUND THEN RAISE EXCEPTION 'Relationship dependency unavailable' USING ERRCODE='23503'; END IF;
  IF p_kind='tagging' THEN
    PERFORM 1 FROM public.food_tags WHERE id=p_tag
      AND (tag_type='system' OR (tag_type='custom' AND owner_id=p_actor)) FOR KEY SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Relationship tag dependency unavailable' USING ERRCODE='23503'; END IF;
  END IF;
  IF p_kind LIKE 'activity_%' THEN
    IF p_kind='activity_food_like' AND NOT EXISTS(SELECT 1 FROM public.activity_foods WHERE id=p_target AND deleted_at IS NULL)
       OR p_kind='activity_dinner_like' AND NOT EXISTS(SELECT 1 FROM public.activity_dinners WHERE id=p_target AND deleted_at IS NULL) THEN
      p_present:=false;
    END IF;
  END IF;
  condition:=format('account_id=%s AND %I=%s',p_actor,col,p_target);
  IF typ IS NOT NULL THEN condition:=condition||format(' AND target_type=%L',typ); END IF;
  IF p_kind='tagging' THEN condition:=condition||format(' AND tag_id=%s',p_tag); END IF;
  -- Bind every received row identity to this business key, even if a different local row already exists.
  IF p_row IS NOT NULL THEN
    expected_parity:=CASE WHEN p_origin=node.site_id THEN CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END
                         ELSE CASE WHEN node.mode='CAMPUS' THEN 0 ELSE 1 END END;
    IF p_row<1 OR p_row>9007199254740991 OR p_gid IS NULL OR p_origin IS NULL THEN
      RAISE EXCEPTION 'Invalid relationship identity' USING ERRCODE='23514'; END IF;
    SELECT * INTO existing FROM eatwhat_identity.entity_identity WHERE entity_type=tbl AND numeric_id=p_row;
    identity_found:=FOUND;
    IF identity_found AND (existing.global_id<>p_gid OR existing.origin_site<>p_origin) THEN
      RAISE EXCEPTION 'Relationship identity collision' USING ERRCODE='23505'; END IF;
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',tbl)
      INTO business_row_found USING p_row;
    IF tbl='likes' THEN
      EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.likes WHERE id=$1 AND account_id=$2 AND target_type=$3 AND target_id=$4)'
        INTO business_row_matches USING p_row,p_actor,typ,p_target;
    ELSIF tbl='activity_likes' THEN
      EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.activity_likes WHERE id=$1 AND account_id=$2 AND target_type=$3 AND activity_id=$4)'
        INTO business_row_matches USING p_row,p_actor,typ,p_target;
    ELSIF tbl='food_favorites' THEN
      EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.food_favorites WHERE id=$1 AND account_id=$2 AND food_id=$3)'
        INTO business_row_matches USING p_row,p_actor,p_target;
    ELSIF tbl='follow' THEN
      EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.follow WHERE id=$1 AND account_id=$2 AND target_id=$3)'
        INTO business_row_matches USING p_row,p_actor,p_target;
    ELSE
      EXECUTE 'SELECT EXISTS(SELECT 1 FROM public.food_taggings WHERE id=$1 AND account_id=$2 AND food_id=$3 AND tag_id=$4)'
        INTO business_row_matches USING p_row,p_actor,p_target,p_tag;
    END IF;
    IF business_row_found AND NOT business_row_matches THEN
      RAISE EXCEPTION 'Relationship identity belongs to another natural key' USING ERRCODE='23505'; END IF;
    IF identity_found AND NOT business_row_found
       AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_alias
           WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND relation_key=k)
       AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_projection
           WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND origin_site=p_origin AND relation_key=k) THEN
      RAISE EXCEPTION 'Unbound relationship identity tombstone' USING ERRCODE='23514'; END IF;
    IF p_row<=1000000000 THEN
      IF NOT identity_found OR existing.origin_site IS NULL
         OR (NOT business_row_matches AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_alias
             WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND relation_key=k)
             AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_projection
             WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND origin_site=p_origin AND relation_key=k)) THEN
        RAISE EXCEPTION 'LEGACY_BASELINE_NOT_ACTIVATED' USING ERRCODE='23514'; END IF;
    ELSE
      IF NOT eatwhat_cluster.valid_origin_number(p_row,p_origin) THEN
        RAISE EXCEPTION 'Invalid relationship identity parity' USING ERRCODE='23514'; END IF;
      IF p_origin=node.site_id AND NOT identity_found THEN
        RAISE EXCEPTION 'Unknown local relationship identity' USING ERRCODE='23514'; END IF;
      IF p_origin<>p_event_origin AND NOT identity_found THEN
        RAISE EXCEPTION 'Non-origin relationship identity is not reserved' USING ERRCODE='23514'; END IF;
    END IF;
    INSERT INTO eatwhat_sync.relation_alias VALUES(tbl,p_row,p_gid,k) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_alias WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND relation_key=k) THEN
      RAISE EXCEPTION 'Relationship alias collision' USING ERRCODE='23505'; END IF;
    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
      VALUES(tbl,p_row,p_gid,p_origin) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=tbl AND numeric_id=p_row AND global_id=p_gid AND origin_site=p_origin) THEN
      RAISE EXCEPTION 'Relationship global identity collision' USING ERRCODE='23505'; END IF;
  END IF;
  SELECT * INTO saved FROM eatwhat_sync.relation_projection WHERE relation_key=k;
  IF FOUND THEN row_id:=saved.numeric_id;row_gid:=saved.global_id;row_origin:=saved.origin_site;
  ELSE
    EXECUTE format('SELECT id FROM public.%I WHERE %s',tbl,condition) INTO row_id;
    IF row_id IS NOT NULL THEN
      SELECT global_id,origin_site INTO row_gid,row_origin FROM eatwhat_identity.entity_identity WHERE entity_type=tbl AND numeric_id=row_id;
      IF row_gid IS NULL OR row_origin IS NULL THEN
        RAISE EXCEPTION 'LEGACY_BASELINE_NOT_ACTIVATED' USING ERRCODE='23514';
      END IF;
    ELSIF p_row IS NOT NULL THEN row_id:=p_row;row_gid:=p_gid;row_origin:=p_origin;
    ELSE
      row_id:=nextval(pg_get_serial_sequence('public.'||tbl,'id')::regclass);
      row_gid:=gen_random_uuid();row_origin:=node.site_id;
    END IF;
    INSERT INTO eatwhat_sync.relation_projection VALUES(k,tbl,row_id,row_gid,row_origin);
    INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
      VALUES(tbl,row_id,row_gid,row_origin) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity
        WHERE entity_type=tbl AND numeric_id=row_id AND global_id=row_gid AND origin_site=row_origin) THEN
      RAISE EXCEPTION 'Relationship projection identity collision' USING ERRCODE='23505'; END IF;
    INSERT INTO eatwhat_sync.relation_alias VALUES(tbl,row_id,row_gid,k) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_alias
        WHERE entity_type=tbl AND numeric_id=row_id AND global_id=row_gid AND relation_key=k) THEN
      RAISE EXCEPTION 'Relationship projection alias collision' USING ERRCODE='23505'; END IF;
  END IF;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),tbl,row_id,row_gid,row_origin,
      (SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=p_actor));
  IF tbl IN ('likes','activity_likes') THEN
    EXECUTE format('INSERT INTO public.%I(id,account_id,target_type,%I,deleted_at) VALUES($1,$2,$3,$4,CASE WHEN $5 THEN NULL ELSE clock_timestamp() END) ON CONFLICT(account_id,target_type,%I) DO UPDATE SET deleted_at=CASE WHEN $5 THEN NULL ELSE coalesce(%I.deleted_at,clock_timestamp()) END',tbl,col,col,tbl)
      USING row_id,p_actor,typ,p_target,p_present;
  ELSIF p_present THEN
    IF tbl='food_taggings' THEN
      INSERT INTO public.food_taggings(id,account_id,food_id,tag_id) VALUES(row_id,p_actor,p_target,p_tag) ON CONFLICT DO NOTHING;
    ELSE
      EXECUTE format('INSERT INTO public.%I(id,account_id,%I) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',tbl,col) USING row_id,p_actor,p_target;
    END IF;
  ELSE EXECUTE format('DELETE FROM public.%I WHERE %s',tbl,condition);
  END IF;
  IF tbl IN ('likes','activity_likes') THEN
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1 AND %s AND ((deleted_at IS NULL)=$2))',tbl,condition)
      INTO projected_matches USING row_id,p_present;
  ELSE
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1 AND %s)',tbl,condition)
      INTO projected_matches USING row_id;
    projected_matches:=projected_matches=p_present;
  END IF;
  IF NOT projected_matches THEN
    RAISE EXCEPTION 'Relationship projection did not match its natural key' USING ERRCODE='23505'; END IF;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
  RETURN jsonb_build_object('row_id',row_id,'row_gid',row_gid,'row_origin',row_origin);
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.import_relation_notification(
  p_event uuid,p_actor bigint,p_target bigint,p_event_origin text,p_payload jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE nid bigint; gid uuid; recipient bigint; recipient_gid uuid; origin text; v_kind text;
  node eatwhat_identity.node_state%ROWTYPE; v_target_type text; expected_target bigint; expected_data jsonb;
  identity_row eatwhat_identity.entity_identity%ROWTYPE; existing public.notifications%ROWTYPE;
  expected_parity integer; millis_created bigint; millis_expires bigint;
BEGIN
  IF p_payload IS NULL OR p_payload='null'::jsonb THEN RETURN; END IF;
  nid:=(p_payload->>'numericId')::bigint;gid:=(p_payload->>'globalId')::uuid;
  recipient_gid:=(p_payload->>'recipientGid')::uuid;origin:=p_payload->>'originSite';v_kind:=p_payload->>'kind';
  v_target_type:=p_payload->>'targetType';
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND OR p_event_origin IS NULL OR origin<>p_event_origin THEN
    RAISE EXCEPTION 'Invalid notification origin' USING ERRCODE='23514'; END IF;
  SELECT numeric_id INTO recipient FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=recipient_gid;
  expected_target:=CASE WHEN v_kind='follow' THEN p_actor ELSE p_target END;
  expected_parity:=CASE WHEN p_event_origin=node.site_id
    THEN CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END
    ELSE CASE WHEN node.mode='CAMPUS' THEN 0 ELSE 1 END END;
  millis_created:=(p_payload->>'createdAt')::bigint;
  millis_expires:=(p_payload->>'expiresAt')::bigint;
  expected_data:=CASE WHEN v_kind='follow' THEN '{}'::jsonb
    ELSE jsonb_build_object('targetType',v_target_type,'targetId',expected_target) END;
  IF recipient IS NULL OR recipient=p_actor OR v_kind NOT IN ('like','follow')
     OR v_target_type NOT IN ('food','dinner','user') OR nid<=1000000000 OR nid>9007199254740991
     OR NOT eatwhat_cluster.valid_origin_number(nid,origin) OR gid IS NULL OR p_payload->>'globalId' IS NULL
     OR expected_target IS NULL OR millis_created<1 OR millis_expires-millis_created<>2592000000
     OR (v_kind='follow' AND v_target_type<>'user')
     OR (v_kind='like' AND v_target_type NOT IN ('food','dinner')) THEN
    RAISE EXCEPTION 'Invalid derived notification' USING ERRCODE='23514'; END IF;
  INSERT INTO eatwhat_sync.relation_notification VALUES(p_event,recipient_gid,v_kind,nid,gid,p_payload) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_notification rn WHERE rn.source_event_id=p_event AND rn.recipient_gid=(p_payload->>'recipientGid')::uuid
       AND rn.kind=v_kind AND rn.payload=p_payload) THEN
    RAISE EXCEPTION 'Derived notification identity collision' USING ERRCODE='23505'; END IF;
  -- Retain the dedup fact even for expired notifications. Replays cannot extend the source expiry.
  SELECT * INTO identity_row FROM eatwhat_identity.entity_identity
    WHERE entity_type='notifications' AND numeric_id=nid;
  IF FOUND AND (identity_row.global_id<>gid OR identity_row.origin_site<>origin) THEN
    RAISE EXCEPTION 'Notification identity collision' USING ERRCODE='23505'; END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid
      AND (entity_type<>'notifications' OR numeric_id<>nid)) THEN
    RAISE EXCEPTION 'Notification global identity collision' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
    VALUES('notifications',nid,gid,origin) ON CONFLICT DO NOTHING;
  IF (p_payload->>'expiresAt')::bigint <= (extract(epoch FROM clock_timestamp())*1000)::bigint THEN RETURN; END IF;
  SELECT * INTO existing FROM public.notifications WHERE id=nid;
  IF FOUND AND (existing.account_id<>recipient OR existing.actor_id<>p_actor OR existing.type<>v_kind
      OR existing.target_type<>v_target_type OR existing.target_id<>expected_target OR existing.data<>expected_data
      OR (extract(epoch FROM existing.created_at)*1000)::bigint<>millis_created
      OR (extract(epoch FROM existing.expires_at)*1000)::bigint<>millis_expires) THEN
    RAISE EXCEPTION 'Notification content collision' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'notifications',nid,gid,origin,recipient_gid);
  INSERT INTO public.notifications(id,account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
    VALUES(nid,recipient,p_actor,v_kind,v_target_type,expected_target,expected_data,
      to_timestamp(millis_created/1000.0),to_timestamp(millis_expires/1000.0)) ON CONFLICT(id) DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM public.notifications AS n WHERE n.id=nid AND n.account_id=recipient
      AND n.actor_id=p_actor AND n.type=v_kind AND n.target_type=v_target_type AND n.target_id=expected_target
      AND n.data=expected_data
      AND (extract(epoch FROM n.created_at)*1000)::bigint=millis_created
      AND (extract(epoch FROM n.expires_at)*1000)::bigint=millis_expires) THEN
    RAISE EXCEPTION 'Notification import did not match its identity' USING ERRCODE='23505'; END IF;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.apply_catalog_projection(
  p_kind text,p_gid uuid,p_numeric bigint,p_origin text,p_author uuid,p_parent uuid,p_operation text,p_payload jsonb
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE; entity text; local_parity integer;
  mapped_gid uuid; mapped_id bigint; mapped_origin text; v_author_id bigint; v_parent_id bigint;
  v_owner_id bigint; v_hub_id bigint; picture_keys text[]; restaurant_gid uuid; owner_gid uuid;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_gid IS NULL OR p_numeric IS NULL OR p_numeric<1 OR p_numeric>9007199254740991
      OR p_origin IS NULL OR length(p_origin) NOT BETWEEN 1 AND 64
      OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Invalid catalog projection identity' USING ERRCODE='23514';
  END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Catalog projection requires an explicit site' USING ERRCODE='55000'; END IF;
  entity:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' ELSE 'food_tags' END;
  local_parity:=CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END;
  SELECT global_id,origin_site INTO mapped_gid,mapped_origin FROM eatwhat_identity.entity_identity
    WHERE entity_type=entity AND numeric_id=p_numeric;
  IF p_numeric<=1000000000 AND NOT FOUND THEN
    RAISE EXCEPTION 'Unregistered legacy catalog ID is not importable' USING ERRCODE='23514';
  END IF;
  IF FOUND AND (mapped_gid<>p_gid OR mapped_origin IS DISTINCT FROM p_origin) THEN
    RAISE EXCEPTION 'Catalog numeric identity is already reserved' USING ERRCODE='23505';
  END IF;
  SELECT numeric_id INTO mapped_id FROM eatwhat_identity.entity_identity WHERE global_id=p_gid;
  IF FOUND AND (mapped_id<>p_numeric OR NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity
       WHERE global_id=p_gid AND entity_type=entity AND numeric_id=p_numeric AND origin_site=p_origin)) THEN
    RAISE EXCEPTION 'Catalog GID is already reserved' USING ERRCODE='23505';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=p_numeric) THEN
    IF p_origin=node.site_id OR NOT eatwhat_cluster.valid_origin_number(p_numeric,p_origin) THEN
      RAISE EXCEPTION 'Catalog source ID is outside its allocation domain' USING ERRCODE='23514';
    END IF;
  END IF;

  -- The function is callable only for the immutable payload currently selected by the ledger.
  -- This makes the import context unusable as a general explicit-ID insertion escape hatch.
  IF p_operation='DELETE' THEN
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=p_kind AND o.object_gid=p_gid AND o.numeric_id=p_numeric AND o.origin_site=p_origin
          AND o.author_gid IS NOT DISTINCT FROM p_author AND o.parent_gid IS NOT DISTINCT FROM p_parent
          AND o.tombstone AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h
            WHERE r.operation_id=(h->>'operationId')::uuid AND r.kind=p_kind AND r.object_gid=p_gid
              AND r.tombstone AND r.payload=p_payload
              AND (h->>'tombstone')::boolean)) THEN
      RAISE EXCEPTION 'Catalog delete is not the active ledger state' USING ERRCODE='23514';
    END IF;
  ELSE
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=p_kind AND o.object_gid=p_gid AND o.numeric_id=p_numeric AND o.origin_site=p_origin
          AND o.author_gid IS NOT DISTINCT FROM p_author AND o.parent_gid IS NOT DISTINCT FROM p_parent
          AND NOT o.tombstone AND NOT o.rejected AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            CROSS JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h
            WHERE r.operation_id=(h->>'operationId')::uuid AND r.kind=p_kind AND r.object_gid=p_gid
              AND NOT r.tombstone AND r.payload=p_payload
              AND NOT (h->>'tombstone')::boolean)) THEN
      RAISE EXCEPTION 'Catalog payload is not an active ledger head' USING ERRCODE='23514';
    END IF;
  END IF;

  IF p_operation='DELETE' THEN
    INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id)
      VALUES(txid_current(),pg_backend_pid(),entity,p_numeric,p_gid,p_origin,COALESCE(p_author,p_gid));
    IF p_kind='HUB' THEN
      INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
          actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
        SELECT txid_current(),pg_backend_pid(),'RESTAURANT',r.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
          NULL,p_numeric,'HUB_ASSIGNMENT',COALESCE(ro.revision,0),false
        FROM public.restaurant r LEFT JOIN eatwhat_sync.catalog_object ro
          ON ro.kind='RESTAURANT' AND ro.numeric_id=r.id AND ro.managed
        WHERE r.hub_id=p_numeric
        ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
    ELSIF p_kind='RESTAURANT' THEN
      INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
          actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
        SELECT txid_current(),pg_backend_pid(),'FOOD',f.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
          f.account_id,p_numeric,'DELETE',COALESCE(fo.revision,0),false
        FROM public.foods f LEFT JOIN eatwhat_sync.catalog_object fo
          ON fo.kind='FOOD' AND fo.numeric_id=f.id AND fo.managed
        WHERE f.restaurant_id=p_numeric
        ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
    END IF;
    CASE p_kind
      WHEN 'HUB' THEN DELETE FROM public.hub WHERE id=p_numeric;
      WHEN 'RESTAURANT' THEN DELETE FROM public.restaurant WHERE id=p_numeric;
      WHEN 'FOOD' THEN DELETE FROM public.foods WHERE id=p_numeric;
      ELSE DELETE FROM public.food_tags WHERE id=p_numeric;
    END CASE;
    DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current()
      AND backend_id=pg_backend_pid() AND entity_type=entity AND numeric_id=p_numeric;
    RETURN 'TERMINAL';
  END IF;

  -- An active identity with no public row is a withdrawn projection that must be restored in place.
  -- Only a catalog tombstone/rejection suppresses the row permanently.
  IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=p_kind AND object_gid=p_gid
      AND (tombstone OR rejected)) THEN RETURN 'TERMINAL'; END IF;

  IF p_kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') THEN
    IF p_author IS NULL THEN RAISE EXCEPTION 'Catalog author is required' USING ERRCODE='23514'; END IF;
    SELECT numeric_id INTO v_author_id FROM eatwhat_identity.entity_identity
      WHERE entity_type='users' AND global_id=p_author;
    IF v_author_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=v_author_id) THEN RETURN 'WAIT_PARENT'; END IF;
  END IF;

  IF p_kind='RESTAURANT' THEN
    IF p_payload->>'hubGid' IS NOT NULL THEN
      SELECT numeric_id INTO v_hub_id FROM eatwhat_identity.entity_identity WHERE entity_type='hub'
        AND global_id=(p_payload->>'hubGid')::uuid;
      IF v_hub_id IS NULL THEN RETURN 'WAIT_PARENT'; END IF;
      IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind='HUB'
          AND object_gid=(p_payload->>'hubGid')::uuid AND (tombstone OR rejected)) THEN
        -- Hub deletion means unbound. Keep the restaurant and immutable hubGid evidence,
        -- while projecting the nullable FK to NULL, matching ON DELETE SET NULL semantics.
        v_hub_id:=NULL;
      ELSIF NOT EXISTS(SELECT 1 FROM public.hub WHERE id=v_hub_id) THEN RETURN 'WAIT_PARENT'; END IF;
    END IF;
    IF p_payload->>'poi' IS NOT NULL AND EXISTS(SELECT 1 FROM public.restaurant
       WHERE poi=p_payload->>'poi' AND id<>p_numeric) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='FOOD' THEN
    restaurant_gid:=(p_payload->>'restaurantGid')::uuid;
    SELECT numeric_id INTO v_parent_id FROM eatwhat_identity.entity_identity WHERE entity_type='restaurant'
      AND global_id=restaurant_gid;
    IF v_parent_id IS NULL THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT'
        AND object_gid=restaurant_gid AND (tombstone OR rejected)) THEN RETURN 'TERMINAL'; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=v_parent_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.foods WHERE restaurant_id=v_parent_id AND name=p_payload->>'name'
       AND id<>p_numeric) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='SYSTEM_TAG' THEN
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='system'
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='CUSTOM_TAG' THEN
    owner_gid:=(p_payload->>'ownerGid')::uuid;
    IF owner_gid<>p_author THEN RAISE EXCEPTION 'Custom tag owner mismatch' USING ERRCODE='23514'; END IF;
    SELECT numeric_id INTO v_owner_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=owner_gid;
    IF v_owner_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=v_owner_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='custom' AND food_tags.owner_id=v_owner_id
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric) THEN RETURN 'KEY_CONFLICT'; END IF;
  END IF;

  INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id)
    VALUES(txid_current(),pg_backend_pid(),entity,p_numeric,p_gid,p_origin,COALESCE(p_author,p_gid));
  IF p_kind='HUB' THEN
    IF EXISTS(SELECT 1 FROM public.hub WHERE id=p_numeric) THEN
      UPDATE public.hub SET name=p_payload->>'name',
        center=public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        gcj_lng=(p_payload->'centerGcj'->>'lng')::float8,gcj_lat=(p_payload->'centerGcj'->>'lat')::float8,
        boundary=CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.hub(id,name,center,gcj_lng,gcj_lat,boundary) VALUES(p_numeric,p_payload->>'name',
        public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        (p_payload->'centerGcj'->>'lng')::float8,(p_payload->'centerGcj'->>'lat')::float8,
        CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END);
    END IF;
  ELSIF p_kind='RESTAURANT' THEN
    picture_keys:=ARRAY(SELECT jsonb_array_elements_text(p_payload->'pictureKeys'));
    IF EXISTS(SELECT 1 FROM public.restaurant WHERE id=p_numeric) THEN
      UPDATE public.restaurant SET account_id=v_author_id,name=p_payload->>'name',address=p_payload->>'address',
        city_id=(p_payload->>'cityId')::integer,
        location=public.ST_SetSRID(public.ST_MakePoint((p_payload->'locationWgs'->>'lng')::float8,(p_payload->'locationWgs'->>'lat')::float8),4326)::public.geography,
        gcj_lng=(p_payload->'locationGcj'->>'lng')::float8,gcj_lat=(p_payload->'locationGcj'->>'lat')::float8,
        hub_id=v_hub_id,poi=p_payload->>'poi',picture_url=picture_keys WHERE id=p_numeric;
    ELSE
      INSERT INTO public.restaurant(id,account_id,name,address,city_id,location,gcj_lng,gcj_lat,hub_id,poi,picture_url)
        VALUES(p_numeric,v_author_id,p_payload->>'name',p_payload->>'address',(p_payload->>'cityId')::integer,
          public.ST_SetSRID(public.ST_MakePoint((p_payload->'locationWgs'->>'lng')::float8,(p_payload->'locationWgs'->>'lat')::float8),4326)::public.geography,
          (p_payload->'locationGcj'->>'lng')::float8,(p_payload->'locationGcj'->>'lat')::float8,v_hub_id,p_payload->>'poi',picture_keys);
    END IF;
  ELSIF p_kind='FOOD' THEN
    picture_keys:=ARRAY(SELECT jsonb_array_elements_text(p_payload->'pictureKeys'));
    IF EXISTS(SELECT 1 FROM public.foods WHERE id=p_numeric) THEN
      UPDATE public.foods SET account_id=v_author_id,restaurant_id=v_parent_id,name=p_payload->>'name',
        description=p_payload->>'description',price=(p_payload->>'price')::numeric,category=p_payload->>'category',picture_url=picture_keys
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.foods(id,account_id,restaurant_id,name,description,price,category,picture_url)
        VALUES(p_numeric,v_author_id,v_parent_id,p_payload->>'name',p_payload->>'description',
          (p_payload->>'price')::numeric,p_payload->>'category',picture_keys);
    END IF;
  ELSE
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE id=p_numeric) THEN
      UPDATE public.food_tags SET name=p_payload->>'name',normalized_name=p_payload->>'normalizedName',
        tag_type=p_payload->>'tagType',owner_id=CASE WHEN p_kind='CUSTOM_TAG' THEN v_owner_id ELSE NULL END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.food_tags(id,name,normalized_name,tag_type,owner_id)
        VALUES(p_numeric,p_payload->>'name',p_payload->>'normalizedName',p_payload->>'tagType',
          CASE WHEN p_kind='CUSTOM_TAG' THEN v_owner_id ELSE NULL END);
    END IF;
  END IF;
  DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid()
    AND entity_type=entity AND numeric_id=p_numeric;
  RETURN 'APPLIED';
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.next_relation_number() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE n bigint; slot integer; mode text; result bigint;
BEGIN
 SELECT a.slot INTO slot FROM eatwhat_identity.allocation a WHERE a.singleton;
 n:=nextval('eatwhat_sync.relation_event_number');
 IF slot IS NULL OR slot<2 THEN
   SELECT node.mode INTO mode FROM eatwhat_identity.node_state node WHERE node.singleton;
   result:=2*n-CASE WHEN mode='CAMPUS' THEN 1 ELSE 0 END;
   IF slot IS NOT NULL AND result>=4000000000000 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
 ELSE
   IF n>(9007199254740991-4000000000000-slot)/1024+1 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
   result:=4000000000000+(n-1)*1024+slot;
 END IF;
 RETURN result;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.next_notification_read_number() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE n bigint; slot integer; mode text; result bigint;
BEGIN
 SELECT a.slot INTO slot FROM eatwhat_identity.allocation a WHERE a.singleton;
 n:=nextval('eatwhat_sync.notification_read_operation_numeric_id_seq');
 IF slot IS NULL OR slot<2 THEN
   SELECT node.mode INTO mode FROM eatwhat_identity.node_state node WHERE node.singleton;
   result:=2*n-CASE WHEN mode='CAMPUS' THEN 1 ELSE 0 END;
   IF slot IS NOT NULL AND result>=4000000000000 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
 ELSE
   IF n>(9007199254740991-4000000000000-slot)/1024+1 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
   result:=4000000000000+(n-1)*1024+slot;
 END IF;
 RETURN result;
END $$;

CREATE OR REPLACE FUNCTION eatwhat_sync.next_catalog_number() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE n bigint; slot integer; mode text; result bigint;
BEGIN
 SELECT a.slot INTO slot FROM eatwhat_identity.allocation a WHERE a.singleton;
 n:=nextval('eatwhat_sync.catalog_local_order_seq');
 IF slot IS NULL OR slot<2 THEN
   SELECT node.mode INTO mode FROM eatwhat_identity.node_state node WHERE node.singleton;
   result:=2*n-CASE WHEN mode='CAMPUS' THEN 1 ELSE 0 END;
   IF slot IS NOT NULL AND result>=4000000000000 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
 ELSE
   IF n>(9007199254740991-4000000000000-slot)/1024+1 THEN RAISE EXCEPTION 'cluster event allocation exhausted' USING ERRCODE='23514'; END IF;
   result:=4000000000000+(n-1)*1024+slot;
 END IF;
 RETURN result;
END $$;
