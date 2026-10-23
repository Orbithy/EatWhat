SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- Immutable causal facts are separate from the query projection. Removing a follow/favorite/tagging
-- may delete its public projection, but never its operation history, aliases or identity reservation.
CREATE TABLE eatwhat_sync.relation_state (
  relation_id uuid PRIMARY KEY,
  vector jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(vector)='object')
);
CREATE TABLE eatwhat_sync.relation_operation (
  event_id uuid PRIMARY KEY,
  relation_id uuid NOT NULL REFERENCES eatwhat_sync.relation_state(relation_id),
  dot_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter BETWEEN 1 AND 9007199254740991),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  adding boolean NOT NULL,
  payload jsonb NOT NULL,
  UNIQUE(relation_id,dot_site,dot_counter)
);
CREATE INDEX relation_operation_lookup ON eatwhat_sync.relation_operation(relation_id,adding);
CREATE TABLE eatwhat_sync.relation_projection (
  relation_key text PRIMARY KEY,
  entity_type text NOT NULL,
  numeric_id bigint NOT NULL,
  global_id uuid NOT NULL,
  origin_site text NOT NULL,
  UNIQUE(entity_type,numeric_id), UNIQUE(global_id)
);
CREATE TABLE eatwhat_sync.relation_alias (
  entity_type text NOT NULL,
  numeric_id bigint NOT NULL,
  global_id uuid NOT NULL,
  relation_key text NOT NULL,
  PRIMARY KEY(entity_type,numeric_id), UNIQUE(global_id)
);
CREATE TABLE eatwhat_sync.relation_notification (
  source_event_id uuid NOT NULL,
  recipient_gid uuid NOT NULL,
  kind text NOT NULL,
  numeric_id bigint NOT NULL UNIQUE,
  global_id uuid NOT NULL UNIQUE,
  payload jsonb NOT NULL,
  PRIMARY KEY(source_event_id,recipient_gid,kind)
);
CREATE SEQUENCE eatwhat_sync.relation_event_number MAXVALUE 4503599627370495;
CREATE FUNCTION eatwhat_sync.next_relation_number() RETURNS bigint
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
  SELECT 2*nextval('eatwhat_sync.relation_event_number')-
    CASE WHEN (SELECT mode FROM eatwhat_identity.node_state WHERE singleton)='CAMPUS' THEN 1 ELSE 0 END
$$;

CREATE FUNCTION eatwhat_sync.relation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Relation causal facts and identity aliases are immutable' USING ERRCODE='23514'; END $$;
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['relation_operation','relation_projection','relation_alias','relation_notification'] LOOP
    EXECUTE format('CREATE TRIGGER relation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON eatwhat_sync.%I FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.relation_immutable()',t);
  END LOOP;
END $$;

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
  IF TG_TABLE_NAME IN ('users','contacts','verifications','privacy','activity_foods','activity_dinners','likes','activity_likes','follow','food_favorites','food_taggings','notifications') AND EXISTS(
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

-- Trigger functions inherit the caller's SECURITY DEFINER search_path. Keep public relation
-- projection safe by pinning these existing counter functions and schema-qualifying their tables.
CREATE OR REPLACE FUNCTION public.update_likes_count()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' AND NEW.deleted_at IS NULL THEN
    IF NEW.target_type='food' THEN
      UPDATE public.activity_foods SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
    ELSIF NEW.target_type='dinner' THEN
      UPDATE public.activity_dinners SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
    END IF;
  ELSIF TG_OP='UPDATE' THEN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      IF NEW.target_type='food' THEN
        UPDATE public.activity_foods SET likes_count=likes_count-1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='dinner' THEN
        UPDATE public.activity_dinners SET likes_count=likes_count-1 WHERE id=NEW.activity_id;
      END IF;
    ELSIF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
      IF NEW.target_type='food' THEN
        UPDATE public.activity_foods SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='dinner' THEN
        UPDATE public.activity_dinners SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
      END IF;
    END IF;
  ELSIF TG_OP='DELETE' AND OLD.deleted_at IS NULL THEN
    IF OLD.target_type='food' THEN
      UPDATE public.activity_foods SET likes_count=likes_count-1 WHERE id=OLD.activity_id;
    ELSIF OLD.target_type='dinner' THEN
      UPDATE public.activity_dinners SET likes_count=likes_count-1 WHERE id=OLD.activity_id;
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.update_foods_likes_count()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' AND NEW.deleted_at IS NULL THEN
    UPDATE public.foods SET likes_count=likes_count+1 WHERE id=NEW.target_id;
  ELSIF TG_OP='UPDATE' THEN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      UPDATE public.foods SET likes_count=GREATEST(likes_count-1,0) WHERE id=NEW.target_id;
    ELSIF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
      UPDATE public.foods SET likes_count=likes_count+1 WHERE id=NEW.target_id;
    END IF;
  ELSIF TG_OP='DELETE' AND OLD.deleted_at IS NULL THEN
    UPDATE public.foods SET likes_count=GREATEST(likes_count-1,0) WHERE id=OLD.target_id;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;


-- Only this owner function can reserve/import numeric identities for relationship projections.
-- Existing local rows win identity selection; alternative source IDs remain resolvable as aliases.
CREATE FUNCTION eatwhat_sync.project_relation(p_kind text,p_actor bigint,p_target bigint,p_tag bigint,
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
      IF mod(p_row,2)<>expected_parity THEN
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

CREATE FUNCTION eatwhat_sync.import_relation_notification(
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
     OR mod(nid,2)<>expected_parity OR gid IS NULL OR p_payload->>'globalId' IS NULL
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
REVOKE ALL ON FUNCTION eatwhat_sync.next_relation_number(),
  eatwhat_sync.project_relation(text,bigint,bigint,bigint,boolean,bigint,uuid,text,text),
  eatwhat_sync.import_relation_notification(uuid,bigint,bigint,text,jsonb),
  eatwhat_sync.relation_immutable() FROM PUBLIC;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON eatwhat_sync.relation_projection,
  eatwhat_sync.relation_alias, eatwhat_sync.relation_notification FROM PUBLIC;

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
  -- Do not remove the only source of unacknowledged relationship operations.
  IF EXISTS(SELECT 1 FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
      WHERE d.status<>'ACKED'
        AND NOT(d.status='QUARANTINED' AND d.last_error='CONSENT_EPOCH_BLOCKED')
        AND o.aggregate_type='relation.operation'
        AND (o.envelope->'payloadJson'->>'actor'=p_subject::text OR o.envelope->'payloadJson'->>'owner'=p_subject::text)) THEN
    RETURN false; END IF;
  DELETE FROM public.activity_likes WHERE account_id=p_uid
      OR (target_type='food' AND activity_id IN (SELECT id FROM public.activity_foods WHERE account_id=p_uid))
      OR (target_type='dinner' AND activity_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid));
  DELETE FROM public.likes WHERE account_id=p_uid;
  DELETE FROM public.food_favorites WHERE account_id=p_uid;
  DELETE FROM public.food_taggings WHERE account_id=p_uid;
  DELETE FROM public.follow WHERE account_id=p_uid OR target_id=p_uid;
  DELETE FROM public.notifications WHERE account_id=p_uid OR actor_id=p_uid;
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
