SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

-- 通用活动：管理员在后台建活动（public.activities，目录同步 kind=ACTIVITY），用户在活动下发内容
-- （public.activity_posts，活动同步）。点赞沿用 activity_likes，target_type='post'。
-- 旧的 activity_foods / activity_dinners 表、数据和同步接收都保留，只是不再有对外接口。

CREATE TABLE public.activities (
  id               bigserial PRIMARY KEY,
  code             varchar(32) NOT NULL CHECK (code ~ '^[a-z][a-z0-9-]{1,31}$'),
  name             varchar(64) NOT NULL CHECK (length(name) >= 1),
  description      text CHECK (description IS NULL OR length(description) <= 2000),
  -- 封面是运营给的 https 图片地址，不走文件上传和文件同步。
  cover_url        varchar(512) CHECK (cover_url IS NULL OR cover_url ~ '^https://[^[:space:]]+$'),
  status           varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ONLINE', 'OFFLINE')),
  -- 发布时间窗，只限制发内容，不影响读取。
  starts_at        timestamptz,
  ends_at          timestamptz,
  sort             integer NOT NULL DEFAULT 0,
  require_location boolean NOT NULL DEFAULT false,
  max_pictures     integer NOT NULL DEFAULT 9 CHECK (max_pictures BETWEEN 1 AND 9),
  -- 扩展字段定义，格式由 ActivityFieldSchema 校验。
  field_schema     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(field_schema) = 'array'),
  catalog_visible  boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at)
);
-- 与其他目录对象一样，只对可见行要求自然键唯一，冲突由目录同步裁决。
CREATE UNIQUE INDEX uk_activities_code_visible ON public.activities (code) WHERE catalog_visible;
CREATE INDEX idx_activities_status_sort ON public.activities (status, sort, id);
CREATE TRIGGER update_activities_updated_at BEFORE UPDATE ON public.activities
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE public.activity_posts (
  id           bigserial PRIMARY KEY,
  -- 不级联：有内容的活动不能删除，只能下线。
  activity_id  bigint      NOT NULL REFERENCES public.activities (id),
  account_id   bigint      NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
  title        varchar(64),
  description  text CHECK (description IS NULL OR length(description) <= 2000),
  province_id  int         REFERENCES public.provinces (id),
  city_id      int         REFERENCES public.cities (id),
  picture_url  text[]      NOT NULL CHECK (cardinality(picture_url) BETWEEN 1 AND 9),
  extra        jsonb       NOT NULL DEFAULT '{}'::jsonb
                 CHECK (jsonb_typeof(extra) = 'object' AND octet_length(extra::text) <= 8192),
  likes_count  int         NOT NULL DEFAULT 0,
  deleted_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_activity_posts_activity_created ON public.activity_posts (activity_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_activity_posts_activity_likes ON public.activity_posts (activity_id, likes_count DESC, id DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_activity_posts_account ON public.activity_posts (account_id);
CREATE TRIGGER update_activity_posts_updated_at BEFORE UPDATE ON public.activity_posts
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Every enumeration of business tables, catalog kinds and relation kinds gains the new names.
ALTER TABLE eatwhat_identity.entity_identity DROP CONSTRAINT entity_identity_entity_type_check,
  ADD CONSTRAINT entity_identity_entity_type_check CHECK (entity_type IN ('users', 'contacts', 'verifications',
    'activity_foods', 'activity_dinners', 'activity_likes', 'notifications', 'follow', 'privacy', 'hub', 'restaurant',
    'foods', 'browse_history', 'food_tags', 'food_taggings', 'food_favorites', 'likes', 'activities', 'activity_posts'));

-- The LEGACY historical baseline tool checkpoints every independent identity type.
ALTER TABLE eatwhat_identity.baseline_checkpoint DROP CONSTRAINT baseline_checkpoint_entity_type_check,
  ADD CONSTRAINT baseline_checkpoint_entity_type_check CHECK (entity_type IN ('users', 'contacts', 'verifications',
    'activity_foods', 'activity_dinners', 'activity_likes', 'notifications', 'follow', 'privacy', 'hub', 'restaurant',
    'foods', 'browse_history', 'food_tags', 'food_taggings', 'food_favorites', 'likes', 'activities', 'activity_posts'));

ALTER TABLE eatwhat_sync.activity_intent DROP CONSTRAINT activity_intent_table_name_check,
  ADD CONSTRAINT activity_intent_table_name_check CHECK (table_name IN ('activity_foods','activity_dinners','activity_posts'));
ALTER TABLE eatwhat_sync.activity_source_proof DROP CONSTRAINT activity_source_proof_table_name_check,
  ADD CONSTRAINT activity_source_proof_table_name_check CHECK (table_name IN ('activity_foods','activity_dinners','activity_posts'));

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['catalog_object','catalog_revision','catalog_natural_key','catalog_candidate',
      'catalog_head_conflict','catalog_managed_domain','catalog_projection_state','catalog_decision_operation',
      'catalog_backfill_publication'] LOOP
    EXECUTE format('ALTER TABLE eatwhat_sync.%I DROP CONSTRAINT %I', t, t || '_kind_check');
    EXECUTE format('ALTER TABLE eatwhat_sync.%I ADD CONSTRAINT %I CHECK (kind IN (''HUB'',''RESTAURANT'',''FOOD'',''SYSTEM_TAG'',''CUSTOM_TAG'',''ACTIVITY''))',
      t, t || '_kind_check');
  END LOOP;
  FOREACH t IN ARRAY ARRAY['relation_intent','relation_causal_state'] LOOP
    EXECUTE format('ALTER TABLE eatwhat_sync.%I DROP CONSTRAINT %I', t, t || '_kind_check');
    EXECUTE format('ALTER TABLE eatwhat_sync.%I ADD CONSTRAINT %I CHECK (kind IN (''food_like'',''activity_food_like'',''activity_dinner_like'',''activity_post_like'',''favorite'',''follow'',''tagging''))',
      t, t || '_kind_check');
  END LOOP;
END $$;

-- Relation backfill jobs walk RelationKind ordinals; activity_post_like is appended as phase 6, so 7 means done.
ALTER TABLE eatwhat_sync.relation_backfill_job DROP CONSTRAINT relation_backfill_job_phase_check,
  ADD CONSTRAINT relation_backfill_job_phase_check CHECK (phase >= 0 AND phase <= 7);

ALTER TABLE public.activity_likes DROP CONSTRAINT activity_likes_target_type_check,
  ADD CONSTRAINT activity_likes_target_type_check CHECK (target_type IN ('food', 'dinner', 'post'));
ALTER TABLE public.notifications DROP CONSTRAINT notifications_target_type_check,
  ADD CONSTRAINT notifications_target_type_check CHECK (target_type IN ('food', 'dinner', 'post', 'user'));

-- Identity allocation covers the two new business tables; imported rows keep their source identity.
-- Copied from V37__cluster_identity_guard.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_identity.guard_business_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_identity,eatwhat_consent AS $$
DECLARE node_mode text; parity integer; allocated_id bigint; allocation eatwhat_identity.allocation%ROWTYPE;
BEGIN
  IF TG_TABLE_SCHEMA<>'public' OR (TG_TABLE_NAME NOT IN ('users','contacts','verifications','activity_foods','activity_dinners','activity_likes','notifications','follow','privacy','hub','restaurant','foods','browse_history','food_tags','food_taggings','food_favorites','likes','activities','activity_posts')
      AND NOT(TG_TABLE_NAME='user_info' AND TG_OP='UPDATE')) THEN
    RAISE EXCEPTION 'Unsupported identity relation' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'Business numeric identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
  END IF;
  IF TG_TABLE_NAME IN ('hub','restaurant','foods','food_tags','activity_foods','activity_dinners','likes','activities','activity_posts','activity_likes','follow','food_favorites','food_taggings','notifications') AND EXISTS(
      SELECT 1 FROM eatwhat_consent.import_context c WHERE c.transaction_id=txid_current()
        AND c.backend_id=pg_backend_pid() AND c.entity_type=TG_TABLE_NAME AND c.numeric_id=NEW.id) THEN
    RETURN NEW;
  END IF;
  IF TG_TABLE_NAME IN ('users','contacts','verifications','privacy') AND EXISTS(
      SELECT 1 FROM eatwhat_consent.import_context c WHERE c.transaction_id=txid_current()
        AND c.backend_id=pg_backend_pid() AND c.entity_type=TG_TABLE_NAME AND c.numeric_id=NEW.id) THEN
    RETURN NEW;
  END IF;
  SELECT mode INTO node_mode FROM eatwhat_identity.node_state WHERE singleton;
  IF FOUND THEN
    SELECT * INTO allocation FROM eatwhat_identity.allocation WHERE singleton;
    IF FOUND THEN
      IF NEW.id IS NULL OR NEW.id<allocation.minimum_id OR NEW.id>allocation.maximum_id
        OR mod(NEW.id-allocation.minimum_id,allocation.increment_by)<>0 THEN
        RAISE EXCEPTION 'Numeric identity is outside this cluster allocation slot' USING ERRCODE='23514'; END IF;
    ELSE
    parity:=CASE WHEN node_mode='CAMPUS' THEN 1 ELSE 0 END;
    IF NEW.id IS NULL OR NEW.id<=1000000000 OR NEW.id>9007199254740991 OR mod(NEW.id,2)<>parity THEN
      RAISE EXCEPTION 'Numeric identity is outside this site allocation domain' USING ERRCODE='23514'; END IF;
    END IF;
    BEGIN allocated_id:=currval(pg_get_serial_sequence(format('%I.%I',TG_TABLE_SCHEMA,TG_TABLE_NAME),'id')::regclass);
    EXCEPTION WHEN object_not_in_prerequisite_state THEN
      RAISE EXCEPTION 'Explicit numeric identity requires allocation in this connection' USING ERRCODE='23514'; END;
    IF NEW.id<>allocated_id THEN RAISE EXCEPTION 'Explicit numeric identity bypassed the local allocator' USING ERRCODE='23514'; END IF;
  END IF;
  RETURN NEW;
END $$;

-- Identity registry records the two new business tables.
-- Copied from V20__account_consent.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_identity.record_business_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_identity AS $$
DECLARE imported eatwhat_consent.import_context%ROWTYPE;
BEGIN
  IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','contacts','verifications','activity_foods','activity_dinners','activity_likes','notifications','follow','privacy','hub','restaurant','foods','browse_history','food_tags','food_taggings','food_favorites','likes','activities','activity_posts') THEN
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

-- Legacy activity events may carry generic activity posts.
-- Copied from V48__cluster_projection_allocations.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.apply_activity_created(p_table text, p_payload jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE;
  allowed text[]; k text; numeric bigint; gid uuid; origin text; author_gid uuid; author_id bigint; existing uuid;
  pictures text[]; created timestamptz; local_parity integer; v_activity_id bigint;
BEGIN
  IF p_table NOT IN ('activity_foods','activity_dinners','activity_posts') OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Unsupported activity import' USING ERRCODE='23514'; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Activity import requires an explicit site' USING ERRCODE='23514'; END IF;
  allowed:=CASE p_table
    WHEN 'activity_posts' THEN ARRAY['numeric_id','global_id','origin_site','author_global_id','activity_global_id','title','description','province_id','city_id','picture_url','extra','created_at']
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
  IF p_table='activity_posts' THEN
    SELECT i.numeric_id INTO v_activity_id FROM eatwhat_identity.entity_identity i JOIN public.activities a ON a.id=i.numeric_id
      WHERE i.entity_type='activities' AND i.global_id=(p_payload->>'activity_global_id')::uuid;
    IF NOT FOUND THEN RAISE EXCEPTION 'Activity definition is not available on this site' USING ERRCODE='23503'; END IF;
    IF jsonb_typeof(p_payload->'extra')<>'object' OR octet_length((p_payload->'extra')::text)>8192 THEN
      RAISE EXCEPTION 'Imported activity post extra must be a bounded object' USING ERRCODE='23514'; END IF;
    INSERT INTO public.activity_posts(id,activity_id,account_id,title,description,province_id,city_id,picture_url,extra,created_at)
    VALUES(numeric,v_activity_id,author_id,p_payload->>'title',p_payload->>'description',
           (p_payload->>'province_id')::int,(p_payload->>'city_id')::int,pictures,p_payload->'extra',created);
  ELSIF p_table='activity_foods' THEN
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

-- Durable activity snapshots may carry generic activity posts.
-- Copied from V67__durable_activity_intents.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.apply_activity_state_created(p_table text, p_payload jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE;
  allowed text[]; k text; numeric bigint; gid uuid; origin text; author_gid uuid; author_id bigint; existing uuid; identity_exists boolean; business_exists boolean;
  pictures text[]; created timestamptz; local_parity integer; v_activity_id bigint;
BEGIN
  IF p_table NOT IN ('activity_foods','activity_dinners','activity_posts') OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Unsupported activity import' USING ERRCODE='23514'; END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Activity import requires an explicit site' USING ERRCODE='23514'; END IF;
  allowed:=CASE p_table
    WHEN 'activity_posts' THEN ARRAY['numeric_id','global_id','origin_site','author_global_id','activity_global_id','title','description','province_id','city_id','picture_url','extra','created_at']
    WHEN 'activity_foods' THEN ARRAY['numeric_id','global_id','origin_site','author_global_id','food_name','description','province_id','city_id','picture_url','created_at']
    ELSE ARRAY['numeric_id','global_id','origin_site','author_global_id','description','picture_url','created_at'] END;
  FOR k IN SELECT jsonb_object_keys(p_payload) LOOP
    IF NOT(k=ANY(allowed)) THEN RAISE EXCEPTION 'Unknown activity field' USING ERRCODE='23514'; END IF;
  END LOOP;
  numeric:=(p_payload->>'numeric_id')::bigint; gid:=(p_payload->>'global_id')::uuid;
  origin:=p_payload->>'origin_site'; author_gid:=(p_payload->>'author_global_id')::uuid;
  local_parity:=CASE WHEN node.mode='CAMPUS' THEN 1 ELSE 0 END;
  IF numeric IS NULL OR numeric<1 OR numeric>9007199254740991 OR (numeric>1000000000 AND NOT eatwhat_cluster.valid_origin_number(numeric,origin))
     OR gid IS NULL OR gid='00000000-0000-0000-0000-000000000000'::uuid OR author_gid IS NULL
     OR origin IS NULL THEN
    RAISE EXCEPTION 'Invalid imported activity identity' USING ERRCODE='23514'; END IF;
  IF jsonb_typeof(p_payload->'picture_url')<>'array' OR jsonb_array_length(p_payload->'picture_url') NOT BETWEEN 1 AND 9 THEN
    RAISE EXCEPTION 'Imported activity requires one to nine pictures' USING ERRCODE='23514'; END IF;
  SELECT array_agg(value) INTO pictures FROM jsonb_array_elements_text(p_payload->'picture_url');
  created:=to_timestamp(((p_payload->>'created_at')::bigint)/1000.0);
  SELECT numeric_id INTO author_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=author_gid;
  IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=author_id) THEN
    RAISE EXCEPTION 'Activity author is not available on this site' USING ERRCODE='23503'; END IF;
  SELECT global_id INTO existing FROM eatwhat_identity.entity_identity WHERE entity_type=p_table AND numeric_id=numeric;
  identity_exists:=FOUND;
  IF identity_exists THEN
    IF existing<>gid THEN RAISE EXCEPTION 'Imported activity identity collision' USING ERRCODE='23505'; END IF;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE entity_type=p_table AND numeric_id=numeric AND origin_site=origin) THEN RAISE EXCEPTION 'Activity origin mismatch' USING ERRCODE='23505'; END IF;
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',p_table) INTO business_exists USING numeric;
    IF business_exists THEN RETURN 'ALREADY_APPLIED'; END IF;
  END IF;
  IF NOT identity_exists AND (numeric<=1000000000 OR origin=node.site_id) THEN RAISE EXCEPTION 'Missing verified activity baseline identity' USING ERRCODE='23514'; END IF;
  IF NOT identity_exists AND EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid) THEN
    RAISE EXCEPTION 'Imported activity identity collision' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),p_table,numeric,gid,origin,author_gid);
  IF p_table='activity_posts' THEN
    SELECT i.numeric_id INTO v_activity_id FROM eatwhat_identity.entity_identity i JOIN public.activities a ON a.id=i.numeric_id
      WHERE i.entity_type='activities' AND i.global_id=(p_payload->>'activity_global_id')::uuid;
    IF NOT FOUND THEN RAISE EXCEPTION 'Activity definition is not available on this site' USING ERRCODE='23503'; END IF;
    IF jsonb_typeof(p_payload->'extra')<>'object' OR octet_length((p_payload->'extra')::text)>8192 THEN
      RAISE EXCEPTION 'Imported activity post extra must be a bounded object' USING ERRCODE='23514'; END IF;
    INSERT INTO public.activity_posts(id,activity_id,account_id,title,description,province_id,city_id,picture_url,extra,created_at)
    VALUES(numeric,v_activity_id,author_id,p_payload->>'title',p_payload->>'description',
           (p_payload->>'province_id')::int,(p_payload->>'city_id')::int,pictures,p_payload->'extra',created);
  ELSIF p_table='activity_foods' THEN
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

-- Activity post deletion is a soft delete like the other activity tables.
-- Copied from V21__activity_sync.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.apply_activity_deleted(p_table text, p_numeric bigint, p_gid uuid, p_deleted_at timestamptz) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE changed integer; present boolean;
BEGIN
  IF p_table NOT IN ('activity_foods','activity_dinners','activity_posts') THEN
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

-- Like counters cover activity posts.
-- Copied from V22__relation_sync.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION public.update_likes_count()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' AND NEW.deleted_at IS NULL THEN
    IF NEW.target_type='post' THEN
      UPDATE public.activity_posts SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
    ELSIF NEW.target_type='food' THEN
      UPDATE public.activity_foods SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
    ELSIF NEW.target_type='dinner' THEN
      UPDATE public.activity_dinners SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
    END IF;
  ELSIF TG_OP='UPDATE' THEN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      IF NEW.target_type='post' THEN
        UPDATE public.activity_posts SET likes_count=likes_count-1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='food' THEN
        UPDATE public.activity_foods SET likes_count=likes_count-1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='dinner' THEN
        UPDATE public.activity_dinners SET likes_count=likes_count-1 WHERE id=NEW.activity_id;
      END IF;
    ELSIF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
      IF NEW.target_type='post' THEN
        UPDATE public.activity_posts SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='food' THEN
        UPDATE public.activity_foods SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
      ELSIF NEW.target_type='dinner' THEN
        UPDATE public.activity_dinners SET likes_count=likes_count+1 WHERE id=NEW.activity_id;
      END IF;
    END IF;
  ELSIF TG_OP='DELETE' AND OLD.deleted_at IS NULL THEN
    IF OLD.target_type='post' THEN
      UPDATE public.activity_posts SET likes_count=likes_count-1 WHERE id=OLD.activity_id;
    ELSIF OLD.target_type='food' THEN
      UPDATE public.activity_foods SET likes_count=likes_count-1 WHERE id=OLD.activity_id;
    ELSIF OLD.target_type='dinner' THEN
      UPDATE public.activity_dinners SET likes_count=likes_count-1 WHERE id=OLD.activity_id;
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

-- Withdrawn account cleanup also removes replicated activity posts once nothing is undelivered.
-- Copied from V74__withdrawn_control_credential_cleanup.sql; only the activity additions differ.
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
        OR (o.aggregate_type='activity_dinners' AND o.numeric_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid))
        OR (o.aggregate_type='activity_posts' AND o.numeric_id IN (SELECT id FROM public.activity_posts WHERE account_id=p_uid)))) THEN
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
      OR (target_type='dinner' AND activity_id IN (SELECT id FROM public.activity_dinners WHERE account_id=p_uid))
      OR (target_type='post' AND activity_id IN (SELECT id FROM public.activity_posts WHERE account_id=p_uid));
  DELETE FROM public.likes WHERE account_id=p_uid;
  DELETE FROM public.food_favorites WHERE account_id=p_uid;
  DELETE FROM public.food_taggings WHERE account_id=p_uid;
  DELETE FROM public.follow WHERE account_id=p_uid OR target_id=p_uid;
  DELETE FROM public.notifications WHERE account_id=p_uid OR actor_id=p_uid;
  DELETE FROM public.activity_foods WHERE account_id=p_uid;
  DELETE FROM public.activity_dinners WHERE account_id=p_uid;
  DELETE FROM public.activity_posts WHERE account_id=p_uid;
  FOR dependency IN
    SELECT n.nspname,c.relname,a.attname FROM pg_constraint fk JOIN pg_class c ON c.oid=fk.conrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=fk.conkey[1]
    WHERE fk.contype='f' AND fk.confrelid='public.users'::regclass
      AND NOT(n.nspname='public' AND c.relname IN ('contacts','verifications','privacy','user_info','user_food_preferences'))
      AND NOT(n.nspname='public' AND c.relname='browse_history' AND a.attname='account_id' AND fk.confdeltype='c')
      AND NOT(n.nspname='eatwhat_control' AND c.relname='credential_version' AND a.attname='user_id' AND fk.confdeltype='c')
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

-- Activity post pictures are tracked file references.
-- Copied from V34__file_event_channel.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_file.attach_business_file_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE kind text; keys text[]; k text; row_json jsonb; rid text; owner_id bigint; expected_biz text;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','restaurant','foods','activity_foods','activity_dinners','activity_posts') THEN
        RAISE EXCEPTION 'Unsupported file reference relation';
    END IF;
    kind:=CASE TG_TABLE_NAME WHEN 'users' THEN 'user_avatar' WHEN 'restaurant' THEN 'restaurant'
        WHEN 'foods' THEN 'food' WHEN 'activity_foods' THEN 'activity_food' WHEN 'activity_posts' THEN 'activity_post' ELSE 'activity_dinner' END;
    row_json:=to_jsonb(NEW);
    IF TG_TABLE_NAME='users' THEN keys:=ARRAY[NEW.avatar];
    ELSIF TG_TABLE_NAME IN ('activity_foods','activity_dinners','activity_posts') AND row_json->>'deleted_at' IS NOT NULL THEN RETURN NULL;
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

-- Soft-deleting an activity post releases its pictures.
-- Copied from V34__file_event_channel.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_file.release_business_file_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE kind text; entity text; keep_keys text[] := ARRAY[]::text[]; row_json jsonb;
BEGIN
    IF TG_TABLE_SCHEMA<>'public' OR TG_TABLE_NAME NOT IN ('users','restaurant','foods','activity_foods','activity_dinners','activity_posts') THEN
        RAISE EXCEPTION 'Unsupported file reference relation';
    END IF;
    kind := CASE TG_TABLE_NAME WHEN 'users' THEN 'user_avatar' WHEN 'restaurant' THEN 'restaurant'
        WHEN 'foods' THEN 'food' WHEN 'activity_foods' THEN 'activity_food' WHEN 'activity_posts' THEN 'activity_post' ELSE 'activity_dinner' END;
    entity:=OLD.id::text;
    IF TG_OP='UPDATE' THEN
        row_json:=to_jsonb(NEW);
        IF TG_TABLE_NAME='users' THEN
            keep_keys:=ARRAY[NEW.avatar];
        ELSIF TG_TABLE_NAME NOT IN ('activity_foods','activity_dinners','activity_posts') OR row_json->>'deleted_at' IS NULL THEN
            SELECT coalesce(array_agg(value),ARRAY[]::text[]) INTO keep_keys
            FROM jsonb_array_elements_text(coalesce(nullif(row_json->'picture_url','null'::jsonb),'[]'::jsonb));
        END IF;
    END IF;
    INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason)
    SELECT f.logical_key,f.ref_kind,f.ref_id,'RELEASE',
        CASE WHEN TG_TABLE_NAME='foods' AND TG_OP='DELETE' AND
            NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=(to_jsonb(OLD)->>'restaurant_id')::bigint) THEN 'PARENT_CASCADE'
          WHEN TG_OP='DELETE' OR (TG_TABLE_NAME IN ('activity_foods','activity_dinners','activity_posts') AND to_jsonb(NEW)->>'deleted_at' IS NOT NULL) THEN 'ENTITY_DELETED'
          ELSE 'KEY_REPLACED' END
    FROM eatwhat_file.file_reference_live f
    WHERE f.ref_kind=kind AND (f.ref_id=entity OR f.ref_id LIKE entity||':%')
      AND NOT EXISTS(SELECT 1 FROM unnest(keep_keys) k WHERE k=f.logical_key)
    ORDER BY f.logical_key,f.ref_id;
    RETURN NULL;
END $$;

-- A soft-deleted activity post no longer pins its pictures against GC.
-- Copied from V35__coordinated_file_gc.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_file.guard_business_gc_fence() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE keys text[]; data jsonb; t record; BEGIN
  data=to_jsonb(NEW);
  IF TG_TABLE_NAME IN ('activity_foods','activity_dinners','activity_posts') AND data->>'deleted_at' IS NOT NULL THEN RETURN NEW; END IF;
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

-- Activity post likes are a replicated relationship.
-- Copied from V48__cluster_projection_allocations.sql; only the activity additions differ.
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
    WHEN 'activity_post_like' THEN tbl:='activity_likes';typ:='post';col:='activity_id';
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
  ELSIF p_kind='activity_post_like' THEN
    PERFORM 1 FROM public.activity_posts WHERE id=p_target FOR UPDATE;
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
       OR p_kind='activity_dinner_like' AND NOT EXISTS(SELECT 1 FROM public.activity_dinners WHERE id=p_target AND deleted_at IS NULL)
       OR p_kind='activity_post_like' AND NOT EXISTS(SELECT 1 FROM public.activity_posts WHERE id=p_target AND deleted_at IS NULL) THEN
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

-- Like notifications may target activity posts.
-- Copied from V48__cluster_projection_allocations.sql; only the activity additions differ.
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
     OR v_target_type NOT IN ('food','dinner','post','user') OR nid<=1000000000 OR nid>9007199254740991
     OR NOT eatwhat_cluster.valid_origin_number(nid,origin) OR gid IS NULL OR p_payload->>'globalId' IS NULL
     OR expected_target IS NULL OR millis_created<1 OR millis_expires-millis_created<>2592000000
     OR (v_kind='follow' AND v_target_type<>'user')
     OR (v_kind='like' AND v_target_type NOT IN ('food','dinner','post')) THEN
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

-- Historical notifications may target activity posts.
-- Copied from V69__historical_notification_intents.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.import_notification_history(p jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE nid bigint:=(p->>'id')::bigint; gid uuid:=(p->>'gid')::uuid;
 origin text:=p->>'origin'; actor bigint; recipient bigint; target bigint; existing public.notifications%ROWTYPE;
 expected jsonb; identity eatwhat_identity.entity_identity%ROWTYPE;
BEGIN
 IF nid<1 OR gid IS NULL OR origin IS NULL OR (p->>'kind') NOT IN ('follow','like')
   OR (p->>'targetType') NOT IN ('user','food','dinner','post')
   OR (p->>'createdAt')::bigint<1 OR (p->>'expiresAt')::bigint<=(p->>'createdAt')::bigint THEN
   RAISE EXCEPTION 'Invalid historical notice' USING ERRCODE='23514'; END IF;
 SELECT numeric_id INTO actor FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=(p->>'actor')::uuid;
 SELECT numeric_id INTO recipient FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=(p->>'recipient')::uuid;
 SELECT numeric_id INTO target FROM eatwhat_identity.entity_identity WHERE entity_type=CASE p->>'targetType' WHEN 'user' THEN 'users' WHEN 'food' THEN 'activity_foods' WHEN 'post' THEN 'activity_posts' ELSE 'activity_dinners' END AND global_id=(p->>'target')::uuid;
 IF actor IS NULL OR recipient IS NULL OR target IS NULL OR actor=recipient OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=actor) OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=recipient) THEN
   RAISE EXCEPTION 'Historical notice dependencies unavailable' USING ERRCODE='23503'; END IF;
 IF p->>'kind'='follow' AND (p->>'targetType'<>'user' OR target<>actor)
    OR p->>'kind'='like' AND p->>'targetType' NOT IN ('food','dinner','post') THEN
    RAISE EXCEPTION 'Historical notice target mismatch' USING ERRCODE='23514'; END IF;
 expected:=CASE WHEN p->>'kind'='follow' THEN '{}'::jsonb ELSE jsonb_build_object('targetType',p->>'targetType','targetId',target) END;
 SELECT * INTO identity FROM eatwhat_identity.entity_identity WHERE entity_type='notifications' AND numeric_id=nid;
 IF FOUND AND (identity.global_id<>gid OR identity.origin_site<>origin) THEN RAISE EXCEPTION 'Historical notice identity collision' USING ERRCODE='23505'; END IF;
 IF EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity WHERE global_id=gid AND (entity_type<>'notifications' OR numeric_id<>nid)) THEN RAISE EXCEPTION 'Historical notice identity collision' USING ERRCODE='23505'; END IF;
 IF nid>1000000000 AND NOT eatwhat_cluster.valid_origin_number(nid,origin) THEN RAISE EXCEPTION 'Historical notice allocation mismatch' USING ERRCODE='23514'; END IF;
 INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site) VALUES('notifications',nid,gid,origin) ON CONFLICT DO NOTHING;
 IF (p->>'expiresAt')::bigint<=(extract(epoch FROM clock_timestamp())*1000)::bigint THEN RETURN 'EXPIRED'; END IF;
 SELECT * INTO existing FROM public.notifications WHERE id=nid;
 IF FOUND THEN
   IF existing.account_id<>recipient OR existing.actor_id<>actor OR existing.type<>p->>'kind' OR existing.target_type<>p->>'targetType' OR existing.target_id<>target OR existing.data<>expected
    OR (extract(epoch FROM existing.created_at)*1000)::bigint<>(p->>'createdAt')::bigint OR (extract(epoch FROM existing.expires_at)*1000)::bigint<>(p->>'expiresAt')::bigint THEN
    RAISE EXCEPTION 'Historical notice content collision' USING ERRCODE='23505'; END IF;
   RETURN 'ALREADY_APPLIED';
 END IF;
 INSERT INTO eatwhat_consent.import_context VALUES(txid_current(),pg_backend_pid(),'notifications',nid,gid,origin,(p->>'recipient')::uuid);
 INSERT INTO public.notifications(id,account_id,actor_id,type,target_type,target_id,data,created_at,expires_at)
 VALUES(nid,recipient,actor,p->>'kind',p->>'targetType',target,expected,to_timestamp((p->>'createdAt')::bigint/1000.0),to_timestamp((p->>'expiresAt')::bigint/1000.0));
 DELETE FROM eatwhat_consent.import_context WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid();
 RETURN 'APPLIED';
END $$;

-- Legacy activity post events belong to their author.
-- Copied from V69__historical_notification_intents.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.personal_event_subjects(e jsonb)
RETURNS TABLE(subject_id uuid,consent_epoch bigint,legacy_unscoped boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE p jsonb:=CASE WHEN jsonb_typeof(e->'payloadJson')='string' THEN (e->>'payloadJson')::jsonb ELSE e->'payloadJson' END;
 a text:=e->>'aggregateType'; op jsonb;
BEGIN
 IF a IN ('profile.operation','privacy.operation','preference.operation') THEN
  op:=CASE WHEN a='profile.operation' AND p ? 'operation' THEN p->'operation' ELSE p END;
  RETURN QUERY SELECT (op->>'subject')::uuid,(op->>'epoch')::bigint,false;
 ELSIF a IN ('relation.operation','browse.observation') THEN
  RETURN QUERY SELECT (p->>'actor')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->>'owner')::uuid,(p->>'ownerEpoch')::bigint,false;
 ELSIF a='notification.read.operation' THEN
  RETURN QUERY SELECT (p->>'actorGid')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->>'recipientGid')::uuid,(p->>'recipientEpoch')::bigint,false;
 ELSIF a='notification.history' THEN
  RETURN QUERY SELECT (p->'notice'->>'actor')::uuid,(p->>'actorEpoch')::bigint,false UNION ALL SELECT (p->'notice'->>'recipient')::uuid,(p->>'recipientEpoch')::bigint,false;
 ELSIF a='activity_state' THEN
  RETURN QUERY SELECT (p->'created'->>'author_global_id')::uuid,(p->>'epoch')::bigint,false;
 ELSIF a IN ('activity_foods','activity_dinners','activity_posts') THEN
  RETURN QUERY SELECT coalesce(p->>'author_global_id',e->>'actorId')::uuid,NULL::bigint,true;
 ELSIF a IN ('catalog.operation','catalog.decision') THEN
  RETURN QUERY SELECT (v->>'subjectGid')::uuid,(v->>'epoch')::bigint,false FROM jsonb_array_elements(coalesce(p->'consentEpochs','[]'::jsonb)) v;
 ELSIF a='file.metadata' THEN
  RETURN QUERY SELECT (p->'file'->>'ownerGid')::uuid,(p->>'epoch')::bigint,false;
 ELSIF a='file.replica' THEN
  RETURN QUERY SELECT (p->>'ownerGid')::uuid,(p->>'epoch')::bigint,false;
 END IF;
END $$;

-- Catalog ACTIVITY objects project into public.activities.
-- Copied from V26__catalog_sync.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_entity_type(p_kind text) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
  SELECT CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'ACTIVITY' THEN 'activities' ELSE 'food_tags' END
$$;

-- Catalog visibility covers activities.
-- Copied from V65__catalog_decisions_and_import_claims.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.refresh_catalog_visibility(p_kind text,p_numeric bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_visible boolean; v_table text;
BEGIN
  v_table:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'SYSTEM_TAG' THEN 'food_tags' WHEN 'CUSTOM_TAG' THEN 'food_tags' WHEN 'ACTIVITY' THEN 'activities' END;
  IF v_table IS NULL THEN RAISE EXCEPTION 'Invalid catalog visibility kind' USING ERRCODE='23514'; END IF;
  SELECT NOT o.tombstone AND NOT o.rejected AND COALESCE(s.status,'VISIBLE')='VISIBLE' INTO v_visible
    FROM eatwhat_sync.catalog_object o LEFT JOIN eatwhat_sync.catalog_projection_state s
    ON s.kind=o.kind AND s.object_gid=o.object_gid WHERE o.kind=p_kind AND o.numeric_id=p_numeric;
  IF NOT FOUND THEN RAISE EXCEPTION 'Catalog visibility needs ledger identity' USING ERRCODE='23514'; END IF;
  INSERT INTO eatwhat_sync.catalog_visibility_context(transaction_id,backend_id,kind,numeric_id,target_visible)
    VALUES(txid_current(),pg_backend_pid(),p_kind,p_numeric,v_visible);
  EXECUTE format('UPDATE public.%I SET catalog_visible=$1 WHERE id=$2 AND catalog_visible IS DISTINCT FROM $1',v_table)
    USING v_visible,p_numeric;
  DELETE FROM eatwhat_sync.catalog_visibility_context WHERE transaction_id=txid_current()
    AND backend_id=pg_backend_pid() AND kind=p_kind AND numeric_id=p_numeric;
END $$;

-- A rejected duplicate activity is cleared like other rejected catalog objects.
-- Copied from V26__catalog_sync.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.clear_catalog_projection(p_kind text,p_numeric bigint,p_gid uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE entity text; mapped uuid; present boolean;
BEGIN
  entity:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'SYSTEM_TAG' THEN 'food_tags' WHEN 'CUSTOM_TAG' THEN 'food_tags' WHEN 'ACTIVITY' THEN 'activities' ELSE NULL END;
  IF entity IS NULL OR p_numeric<=0 OR p_gid IS NULL THEN
    RAISE EXCEPTION 'Invalid rejected catalog projection' USING ERRCODE='23514'; END IF;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',entity) INTO present USING p_numeric;
  IF NOT present THEN RETURN; END IF;
  SELECT global_id INTO mapped FROM eatwhat_identity.entity_identity WHERE entity_type=entity AND numeric_id=p_numeric;
  IF mapped IS NULL THEN RETURN; END IF;
  IF mapped IS DISTINCT FROM p_gid THEN RAISE EXCEPTION 'Rejected projection identity mismatch' USING ERRCODE='23505'; END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=p_kind AND object_gid=p_gid
      AND numeric_id=p_numeric AND rejected AND NOT tombstone) THEN
    RAISE EXCEPTION 'Catalog projection is not rejected' USING ERRCODE='23514'; END IF;
  INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
      actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
    SELECT txid_current(),pg_backend_pid(),p_kind,p_numeric,
      COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
      (SELECT ui.numeric_id FROM eatwhat_sync.catalog_object o LEFT JOIN eatwhat_identity.entity_identity ui
        ON ui.entity_type='users' AND ui.global_id=o.author_gid WHERE o.kind=p_kind AND o.object_gid=p_gid),
      NULL,'DELETE',o.revision,false
    FROM eatwhat_sync.catalog_object o WHERE o.kind=p_kind AND o.object_gid=p_gid
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
  IF p_kind='RESTAURANT' THEN
    INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
        actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
      SELECT txid_current(),pg_backend_pid(),'FOOD',f.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
        f.account_id,p_numeric,'DELETE',COALESCE(fo.revision,0),false
      FROM public.foods f LEFT JOIN eatwhat_sync.catalog_object fo ON fo.kind='FOOD' AND fo.numeric_id=f.id
      WHERE f.restaurant_id=p_numeric
      ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
  ELSIF p_kind='HUB' THEN
    INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
        actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
      SELECT txid_current(),pg_backend_pid(),'RESTAURANT',r.id,COALESCE((SELECT min(id) FROM public.users WHERE role='admin'),1),
        r.account_id,p_numeric,'HUB_ASSIGNMENT',COALESCE(ro.revision,0),false
      FROM public.restaurant r LEFT JOIN eatwhat_sync.catalog_object ro ON ro.kind='RESTAURANT' AND ro.numeric_id=r.id
      WHERE r.hub_id=p_numeric
      ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
  END IF;
  CASE p_kind
    WHEN 'HUB' THEN DELETE FROM public.hub WHERE id=p_numeric;
    WHEN 'RESTAURANT' THEN DELETE FROM public.restaurant WHERE id=p_numeric;
    WHEN 'FOOD' THEN DELETE FROM public.foods WHERE id=p_numeric;
    WHEN 'ACTIVITY' THEN DELETE FROM public.activities WHERE id=p_numeric;
    ELSE DELETE FROM public.food_tags WHERE id=p_numeric;
  END CASE;
END $$;

-- Activities are written by administrators, with the same site rule as hubs and system tags.
-- Copied from V66__catalog_multiwriter_write_guard.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.begin_catalog_business_write(
  p_kind text,p_numeric bigint,p_actor_user_id bigint,p_author_user_id bigint,
  p_parent_numeric_id bigint,p_operation text,p_expect_outbox boolean
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_entity text; v_revision bigint; v_managed boolean; v_node text; v_multi boolean;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG','ACTIVITY')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_actor_user_id IS NULL OR p_expect_outbox IS NULL OR (p_numeric IS NOT NULL AND p_numeric<=0) THEN
    RAISE EXCEPTION 'Invalid catalog business write context' USING ERRCODE='23514';
  END IF;
  v_entity:=eatwhat_sync.catalog_entity_type(p_kind);
  SELECT mode INTO v_node FROM eatwhat_identity.node_state WHERE singleton;
  IF v_node IS NULL THEN RAISE EXCEPTION 'Catalog write requires explicit site identity' USING ERRCODE='55000'; END IF;
  SELECT enabled INTO v_multi FROM eatwhat_sync.catalog_multiwriter_config WHERE singleton;
  IF p_kind IN ('HUB','SYSTEM_TAG','ACTIVITY') AND ((v_node<>'CAMPUS' AND NOT COALESCE(v_multi,false)) OR NOT EXISTS(
      SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF p_kind IN ('RESTAURANT','FOOD') AND p_operation IN ('UPDATE','DELETE','HUB_ASSIGNMENT')
      AND ((v_node<>'CAMPUS' AND NOT COALESCE(v_multi,false)) OR NOT (
        EXISTS(SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')
        OR (p_kind='FOOD' AND p_operation IN ('UPDATE','DELETE')
          AND p_author_user_id=p_actor_user_id
          AND EXISTS(SELECT 1 FROM public.foods
            WHERE id=p_numeric AND account_id=p_actor_user_id)))) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF (p_kind IN ('RESTAURANT','FOOD') AND p_operation='CREATE'
      OR p_kind='CUSTOM_TAG') AND p_author_user_id IS DISTINCT FROM p_actor_user_id THEN
    RAISE EXCEPTION 'Catalog personal write actor mismatch' USING ERRCODE='42501';
  END IF;
  SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=p_kind)
      OR (p_numeric IS NOT NULL AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object
        WHERE kind=p_kind AND numeric_id=p_numeric AND managed)) INTO v_managed;
  IF NOT v_managed AND NOT COALESCE(v_multi,false) THEN RETURN false; END IF;
  IF COALESCE(v_multi,false) THEN
    INSERT INTO eatwhat_sync.catalog_managed_domain(kind) VALUES(p_kind) ON CONFLICT(kind) DO NOTHING;
  END IF;
  IF p_numeric IS NULL AND p_operation<>'CREATE' THEN
    RAISE EXCEPTION 'Managed catalog update requires numeric identity' USING ERRCODE='23514';
  END IF;
  v_revision:=0;
  IF p_numeric IS NOT NULL THEN
    SELECT revision INTO v_revision FROM eatwhat_sync.catalog_object
      WHERE kind=p_kind AND numeric_id=p_numeric AND managed;
    IF NOT FOUND THEN
      SELECT COALESCE(max(revision),0) INTO v_revision FROM eatwhat_sync.catalog_revision
        WHERE kind=p_kind AND numeric_id=p_numeric;
    END IF;
    IF p_author_user_id IS NOT NULL AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        JOIN eatwhat_identity.entity_identity ui ON ui.entity_type='users' AND ui.numeric_id=p_author_user_id
        WHERE o.kind=p_kind AND o.numeric_id=p_numeric AND o.author_gid IS NOT NULL
          AND o.author_gid IS DISTINCT FROM ui.global_id) THEN
      RAISE EXCEPTION 'Catalog write author does not match managed object' USING ERRCODE='42501';
    END IF;
  END IF;
  INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
      actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
    VALUES(txid_current(),pg_backend_pid(),p_kind,COALESCE(p_numeric,0),p_actor_user_id,p_author_user_id,
      p_parent_numeric_id,p_operation,v_revision,p_expect_outbox)
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;

  -- Restaurant FK deletion cascades foods. Record exact current children without creating
  -- child catalog operations; deferred validation requires the parent tombstone.
  IF p_kind='RESTAURANT' AND p_operation='DELETE' AND p_numeric IS NOT NULL THEN
    INSERT INTO eatwhat_sync.catalog_business_write_context(transaction_id,backend_id,kind,numeric_id,
        actor_user_id,author_user_id,parent_numeric_id,operation,revision_before,expect_outbox)
      SELECT txid_current(),pg_backend_pid(),'FOOD',f.id,p_actor_user_id,f.account_id,p_numeric,
        'DELETE',COALESCE(fo.revision,0),false
      FROM public.foods f LEFT JOIN eatwhat_sync.catalog_object fo ON fo.kind='FOOD'
        AND fo.numeric_id=f.id AND fo.managed
      WHERE f.restaurant_id=p_numeric
      ON CONFLICT(transaction_id,backend_id,kind,numeric_id,operation) DO NOTHING;
  END IF;
  RETURN true;
END $$;

-- Managed activity rows require catalog command context.
-- Copied from V66__catalog_multiwriter_write_guard.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_public_write_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_kind text; v_id bigint; v_operation text; v_ctx eatwhat_sync.catalog_business_write_context%ROWTYPE;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE;
  v_imported boolean:=false; v_withdrawal boolean:=false; v_count bigint;
BEGIN
  v_kind:=CASE TG_TABLE_NAME WHEN 'hub' THEN 'HUB' WHEN 'restaurant' THEN 'RESTAURANT'
    WHEN 'foods' THEN 'FOOD' WHEN 'activities' THEN 'ACTIVITY' WHEN 'food_tags' THEN CASE
      WHEN COALESCE(to_jsonb(NEW)->>'tag_type',to_jsonb(OLD)->>'tag_type')='system' THEN 'SYSTEM_TAG' ELSE 'CUSTOM_TAG' END END;
  v_id:=COALESCE((to_jsonb(NEW)->>'id')::bigint,(to_jsonb(OLD)->>'id')::bigint);
  IF TG_OP='UPDATE' AND (to_jsonb(NEW)->'catalog_visible') IS DISTINCT FROM (to_jsonb(OLD)->'catalog_visible') THEN
    IF (to_jsonb(NEW)-'catalog_visible') IS DISTINCT FROM (to_jsonb(OLD)-'catalog_visible')
      OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_visibility_context c
        WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
          AND c.kind=v_kind AND c.numeric_id=v_id AND c.target_visible=(to_jsonb(NEW)->>'catalog_visible')::boolean) THEN
      RAISE EXCEPTION 'Catalog visibility requires controlled ledger projection' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='INSERT' AND NOT (to_jsonb(NEW)->>'catalog_visible')::boolean THEN
    RAISE EXCEPTION 'Catalog insert cannot forge hidden projection' USING ERRCODE='42501';
  END IF;

  IF TG_OP='UPDATE' AND TG_TABLE_NAME='foods'
      AND (to_jsonb(NEW)-'likes_count'-'updated_at')=(to_jsonb(OLD)-'likes_count'-'updated_at')
      AND (to_jsonb(NEW)->>'likes_count') IS DISTINCT FROM (to_jsonb(OLD)->>'likes_count') THEN
    SELECT count(*) INTO v_count FROM public.likes l WHERE l.target_type='food' AND l.target_id=v_id AND l.deleted_at IS NULL;
    IF (to_jsonb(NEW)->>'likes_count')::bigint=v_count THEN RETURN NEW; END IF;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=v_kind)
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind=v_kind AND numeric_id=v_id AND managed) THEN
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP='DELETE' THEN
    SELECT * INTO v_withdrawal_ctx FROM eatwhat_sync.catalog_withdrawal_write_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
        AND c.kind=v_kind AND c.numeric_id=v_id FOR UPDATE;
    v_withdrawal:=FOUND;
  END IF;
  IF v_withdrawal THEN
    NULL;
  ELSIF EXISTS(SELECT 1 FROM eatwhat_consent.import_context c WHERE c.transaction_id=txid_current()
      AND c.backend_id=pg_backend_pid() AND c.entity_type=TG_TABLE_NAME AND c.numeric_id=v_id) THEN
    v_imported:=true;
  ELSE
    SELECT * INTO v_ctx FROM eatwhat_sync.catalog_business_write_context c
      WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
        AND c.kind=v_kind AND (c.numeric_id=v_id OR c.numeric_id=0)
        AND ((TG_OP='INSERT' AND c.operation='CREATE') OR c.operation=TG_OP
          OR (TG_OP='UPDATE' AND c.operation='HUB_ASSIGNMENT'))
      ORDER BY (c.numeric_id IS NOT NULL) DESC LIMIT 1 FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Managed catalog row requires catalog command context' USING ERRCODE='42501'; END IF;
    IF v_ctx.numeric_id=0 THEN
      IF TG_OP<>'INSERT' OR (v_ctx.author_user_id IS NULL AND v_kind NOT IN ('HUB','SYSTEM_TAG','ACTIVITY')) THEN
        RAISE EXCEPTION 'Catalog create context does not match inserted row' USING ERRCODE='42501'; END IF;
    END IF;
    UPDATE eatwhat_sync.catalog_business_write_context SET touched=true
      WHERE transaction_id=txid_current() AND backend_id=pg_backend_pid()
        AND kind=v_ctx.kind AND numeric_id=v_ctx.numeric_id AND operation=v_ctx.operation;
  END IF;
  v_operation:=CASE WHEN TG_OP='DELETE' THEN 'DELETE' WHEN TG_OP='INSERT' THEN 'CREATE' ELSE 'UPDATE' END;
  IF v_withdrawal THEN
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported,withdrawal)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,'DELETE',v_withdrawal_ctx.revision_before,false,false,true)
      ON CONFLICT DO NOTHING;
  ELSIF v_imported THEN
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,v_operation,0,false,true)
      ON CONFLICT DO NOTHING;
  ELSE
    INSERT INTO eatwhat_sync.catalog_business_write_pending(transaction_id,backend_id,kind,numeric_id,
      operation,revision_before,expect_outbox,imported)
      VALUES(txid_current(),pg_backend_pid(),v_kind,v_id,v_operation,v_ctx.revision_before,v_ctx.expect_outbox,false)
      ON CONFLICT DO NOTHING;
  END IF;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

-- A rejected activity may be cleared without a delete head.
-- Copied from V66__catalog_multiwriter_write_guard.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_business_write_check() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_head eatwhat_sync.catalog_revision%ROWTYPE; v_delete_head eatwhat_sync.catalog_revision%ROWTYPE;
  v_gid uuid; v_kind text; v_event boolean;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE; v_present boolean;
BEGIN
  v_kind:=NEW.kind;
  SELECT global_id INTO v_gid FROM eatwhat_identity.entity_identity
    WHERE entity_type=eatwhat_sync.catalog_entity_type(v_kind) AND numeric_id=NEW.numeric_id;
  IF NEW.imported AND NOT NEW.withdrawal AND NEW.operation<>'DELETE' AND EXISTS(
      SELECT 1 FROM eatwhat_sync.catalog_decision_projection_context c
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id) THEN
    SELECT r.* INTO v_head FROM eatwhat_sync.catalog_decision_projection_context c
      JOIN eatwhat_sync.catalog_revision r ON r.operation_id=c.winner_operation_id
      JOIN eatwhat_sync.catalog_object o ON o.kind=r.kind AND o.object_gid=r.object_gid
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id
        AND eatwhat_sync.catalog_decision_proven(c.decision_id)
        AND o.numeric_id=NEW.numeric_id AND NOT o.tombstone AND NOT o.rejected
        AND o.causal_state->'heads' @> jsonb_build_array(jsonb_build_object('operationId',r.operation_id::text));
    IF NOT FOUND OR NOT eatwhat_sync.catalog_payload_matches_public(NEW.kind,NEW.numeric_id,v_head.payload)
      OR (NEW.kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') AND NOT EXISTS(
        SELECT 1 FROM eatwhat_identity.entity_identity ui WHERE ui.entity_type='users' AND ui.global_id=v_head.author_gid
        AND ui.numeric_id=CASE NEW.kind WHEN 'RESTAURANT' THEN (SELECT account_id FROM public.restaurant WHERE id=NEW.numeric_id)
          WHEN 'FOOD' THEN (SELECT account_id FROM public.foods WHERE id=NEW.numeric_id)
          ELSE (SELECT owner_id FROM public.food_tags WHERE id=NEW.numeric_id) END)) THEN
      RAISE EXCEPTION 'Catalog decision projection does not match selected immutable head' USING ERRCODE='23514';
    END IF;
    DELETE FROM eatwhat_sync.catalog_decision_projection_context WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id;
    DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
    RETURN NULL;
  END IF;
  IF NEW.withdrawal THEN
    SELECT * INTO v_withdrawal_ctx FROM eatwhat_sync.catalog_withdrawal_write_context c
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id
        AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id;
    IF NOT FOUND OR v_gid IS DISTINCT FROM v_withdrawal_ctx.object_gid
      OR NOT EXISTS(SELECT 1 FROM eatwhat_consent.subject s JOIN eatwhat_identity.node_state n ON n.singleton
        JOIN eatwhat_sync.catalog_object o ON o.kind=NEW.kind AND o.numeric_id=NEW.numeric_id
          AND o.object_gid=v_withdrawal_ctx.object_gid AND o.managed
        WHERE s.subject_id=v_withdrawal_ctx.subject_gid AND s.status='WITHDRAWN'
          AND n.mode='EXTERNAL' AND (
            (NEW.kind IN ('RESTAURANT','CUSTOM_TAG') AND o.author_gid=s.subject_id)
            OR (NEW.kind='FOOD' AND (o.author_gid=s.subject_id OR EXISTS(
              SELECT 1 FROM eatwhat_sync.catalog_object p WHERE p.kind='RESTAURANT'
                AND p.object_gid=o.parent_gid AND p.author_gid=s.subject_id))))) THEN
      RAISE EXCEPTION 'Catalog withdrawal context is invalid' USING ERRCODE='42501';
    END IF;
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',eatwhat_sync.catalog_entity_type(NEW.kind))
      INTO v_present USING NEW.numeric_id;
    IF v_present OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=NEW.kind AND o.numeric_id=NEW.numeric_id AND o.object_gid=v_gid
          AND jsonb_typeof(o.causal_state->'heads')='array'
          AND jsonb_array_length(o.causal_state->'heads')>0
          AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(o.causal_state->'heads') h
            WHERE NOT eatwhat_sync.catalog_head_archive_proven(o.kind,o.object_gid,o.authority_site,h))) THEN
      RAISE EXCEPTION 'Catalog withdrawal projection lacks verified archive evidence' USING ERRCODE='23514';
    END IF;
    DELETE FROM eatwhat_sync.catalog_withdrawal_write_context
      WHERE transaction_id=NEW.transaction_id AND backend_id=NEW.backend_id
        AND kind=NEW.kind AND numeric_id=NEW.numeric_id;
    DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
      AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
    RETURN NULL;
  END IF;
  IF NEW.imported THEN
    IF v_gid IS NULL OR NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=v_kind AND o.object_gid=v_gid AND o.numeric_id=NEW.numeric_id) THEN
      RAISE EXCEPTION 'Inbound catalog write has no persistent identity' USING ERRCODE='23514';
    END IF;
  END IF;
  SELECT r.* INTO v_head FROM eatwhat_sync.catalog_object o
    JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h ON true
    JOIN eatwhat_sync.catalog_revision r ON r.operation_id=(h->>'operationId')::uuid
    WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed
      AND r.revision>NEW.revision_before AND NOT (h->>'tombstone')::boolean
      AND NOT o.tombstone AND NOT o.rejected
    ORDER BY r.revision DESC LIMIT 1;
  IF NEW.operation='DELETE' THEN
    -- DELETE projections select a tombstone head, not the non-tombstone projection head
    -- selected above. Keep its immutable revision so an inbound bridge-only terminal state
    -- can be tied to the exact object and head carried by the authenticated inbox event.
    SELECT r.* INTO v_delete_head FROM eatwhat_sync.catalog_object o
      JOIN LATERAL jsonb_array_elements(o.causal_state->'heads') h ON true
      JOIN eatwhat_sync.catalog_revision r ON r.operation_id=(h->>'operationId')::uuid
      WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed AND o.tombstone
        AND r.revision>NEW.revision_before AND r.tombstone
        AND (h->>'tombstone')::boolean
      ORDER BY r.revision DESC LIMIT 1;
    IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o
        WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id AND o.managed AND o.tombstone
          AND o.revision>NEW.revision_before AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_revision r
            WHERE r.kind=o.kind AND r.object_gid=o.object_gid AND r.tombstone AND r.revision=o.revision))
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.hub WHERE id=NEW.numeric_id)
          AND v_kind='HUB')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=NEW.numeric_id)
          AND v_kind='RESTAURANT')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.foods WHERE id=NEW.numeric_id)
          AND v_kind='FOOD')
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.food_tags WHERE id=NEW.numeric_id)
          AND v_kind IN ('SYSTEM_TAG','CUSTOM_TAG'))
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind=v_kind AND o.numeric_id=NEW.numeric_id
        AND o.managed AND o.rejected AND NOT EXISTS(SELECT 1 FROM public.activities WHERE id=NEW.numeric_id)
          AND v_kind='ACTIVITY')
      AND NOT (v_kind='FOOD' AND NOT EXISTS(SELECT 1 FROM public.foods WHERE id=NEW.numeric_id)
        AND (EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object child
          JOIN eatwhat_sync.catalog_object parent ON parent.kind='RESTAURANT' AND parent.object_gid=child.parent_gid
          WHERE child.kind='FOOD' AND child.numeric_id=NEW.numeric_id AND (parent.tombstone OR parent.rejected))
          OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_business_write_context child_ctx
            JOIN eatwhat_identity.entity_identity parent_identity
              ON parent_identity.entity_type='restaurant' AND parent_identity.numeric_id=child_ctx.parent_numeric_id
            JOIN eatwhat_sync.catalog_object parent
              ON parent.kind='RESTAURANT' AND parent.object_gid=parent_identity.global_id
                AND parent.numeric_id=child_ctx.parent_numeric_id AND parent.managed
            WHERE child_ctx.transaction_id=NEW.transaction_id AND child_ctx.backend_id=NEW.backend_id
              AND child_ctx.kind='FOOD' AND child_ctx.numeric_id=NEW.numeric_id
              AND child_ctx.operation='DELETE' AND child_ctx.touched
              AND (parent.tombstone OR parent.rejected)))) THEN
      RAISE EXCEPTION 'Managed catalog delete lacks a newer tombstone' USING ERRCODE='23514';
    END IF;
    IF NEW.imported AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
        CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
          THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
        WHERE i.aggregate_type='catalog.operation' AND i.status IN ('PROCESSING','APPLIED')
          AND v_delete_head.operation_id IS NOT NULL
          AND ((i.envelope->>'eventType'='catalog.action' AND i.event_id=v_delete_head.operation_id
                AND e.payload->>'kind'=v_kind AND e.payload->>'objectGid'=v_gid::text
                AND e.payload->>'numericId'=NEW.numeric_id::text
                AND e.payload->>'operationId'=v_delete_head.operation_id::text
                AND e.payload->>'contentHash'=v_delete_head.content_hash AND e.payload->>'operation'='DELETE')
            OR (i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=v_kind
                AND e.payload->>'objectGid'=v_gid::text AND e.payload->>'numericId'=NEW.numeric_id::text
                AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                  WHERE h->>'operationId'=v_delete_head.operation_id::text
                    AND h->>'numericId'=NEW.numeric_id::text
                    AND h->>'contentHash'=v_delete_head.content_hash
                    AND h->>'operation'='DELETE' AND h->>'tombstone'='true')))) THEN
      RAISE EXCEPTION 'Inbound catalog delete lacks its inbox action' USING ERRCODE='23514';
    END IF;
  ELSIF v_kind='RESTAURANT' AND NEW.operation='UPDATE'
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind='RESTAURANT'
        AND o.numeric_id=NEW.numeric_id AND o.revision>NEW.revision_before AND NOT o.tombstone AND NOT o.rejected)
      AND EXISTS(SELECT 1 FROM public.restaurant r WHERE r.id=NEW.numeric_id AND r.hub_id IS NULL)
      AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object o WHERE o.kind='HUB' AND o.tombstone
        AND o.object_gid=(SELECT parent_gid FROM eatwhat_sync.catalog_object r
          WHERE r.kind='RESTAURANT' AND r.numeric_id=NEW.numeric_id)) THEN
    NULL;
  ELSE
    IF v_head.operation_id IS NULL OR NOT eatwhat_sync.catalog_payload_matches_public(v_kind,NEW.numeric_id,v_head.payload) THEN
      RAISE EXCEPTION 'Managed catalog public row does not match a new immutable revision' USING ERRCODE='23514';
    END IF;
    IF v_kind IN ('RESTAURANT','FOOD','CUSTOM_TAG') AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ui
        WHERE ui.entity_type='users' AND ui.global_id=v_head.author_gid AND ui.numeric_id=CASE v_kind
          WHEN 'RESTAURANT' THEN (SELECT account_id FROM public.restaurant WHERE id=NEW.numeric_id)
          WHEN 'FOOD' THEN (SELECT account_id FROM public.foods WHERE id=NEW.numeric_id)
          ELSE (SELECT owner_id FROM public.food_tags WHERE id=NEW.numeric_id) END) THEN
      RAISE EXCEPTION 'Managed catalog author does not match public owner' USING ERRCODE='23514';
    END IF;
    IF v_kind='FOOD' AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ri
        WHERE ri.entity_type='restaurant' AND ri.global_id=v_head.parent_gid
          AND ri.numeric_id=(SELECT restaurant_id FROM public.foods WHERE id=NEW.numeric_id)) THEN
      RAISE EXCEPTION 'Managed food parent does not match immutable revision' USING ERRCODE='23514';
    END IF;
    SELECT EXISTS(SELECT 1 FROM eatwhat_sync.outbox ob
      JOIN eatwhat_identity.node_state local_node ON local_node.singleton
      CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(ob.envelope->'payloadJson')='string'
        THEN (ob.envelope->>'payloadJson')::jsonb ELSE ob.envelope->'payloadJson' END AS payload) event_payload
      WHERE ob.aggregate_type='catalog.operation' AND ob.aggregate_id=v_head.operation_id
        AND ob.event_id=v_head.operation_id AND ob.origin_site=local_node.site_id
        AND ob.envelope->>'eventType'='catalog.action'
        AND (ob.envelope->>'payloadJson') IS NOT NULL
        AND event_payload.payload->>'operationId'=v_head.operation_id::text
        AND event_payload.payload->>'kind'=v_kind
        AND event_payload.payload->>'objectGid'=v_gid::text
        AND event_payload.payload->>'contentHash'=v_head.content_hash) INTO v_event;
    IF NOT NEW.imported AND NEW.expect_outbox AND NOT v_event THEN
      RAISE EXCEPTION 'Authorized catalog write lacks matching outbox action' USING ERRCODE='23514';
    END IF;
    -- Imported projections still require their exact inbox event. A local conflict resolution
    -- is accepted only when its exact action already exists in this site's outbox (v_event).
    IF NEW.imported AND NOT v_event AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
        CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
          THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
        WHERE i.aggregate_type='catalog.operation' AND i.status IN ('PROCESSING','APPLIED')
          AND ((i.envelope->>'eventType'='catalog.action' AND e.payload->>'operationId'=v_head.operation_id::text)
            OR (i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=v_kind
              AND e.payload->>'objectGid'=v_gid::text
              AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                WHERE h->>'operationId'=v_head.operation_id::text
                  AND h->>'tombstone'='false')))) THEN
      RAISE EXCEPTION 'Inbound catalog projection lacks its inbox event' USING ERRCODE='23514';
    END IF;
  END IF;
  DELETE FROM eatwhat_sync.catalog_business_write_pending WHERE transaction_id=NEW.transaction_id
    AND backend_id=NEW.backend_id AND kind=NEW.kind AND numeric_id=NEW.numeric_id AND operation=NEW.operation;
  DELETE FROM eatwhat_sync.catalog_business_write_context WHERE transaction_id=NEW.transaction_id
    AND backend_id=NEW.backend_id AND kind=NEW.kind AND (numeric_id=NEW.numeric_id OR numeric_id=0);
  RETURN NULL;
END $$;

-- Catalog projection writes activity definitions.
-- Copied from V73__campus.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.apply_catalog_projection(
  p_kind text,p_gid uuid,p_numeric bigint,p_origin text,p_author uuid,p_parent uuid,p_operation text,p_payload jsonb
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE; entity text; local_parity integer;
  mapped_gid uuid; mapped_id bigint; mapped_origin text; v_author_id bigint; v_parent_id bigint;
  v_owner_id bigint; v_hub_id bigint; picture_keys text[]; restaurant_gid uuid; owner_gid uuid;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG','ACTIVITY')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_gid IS NULL OR p_numeric IS NULL OR p_numeric<1 OR p_numeric>9007199254740991
      OR p_origin IS NULL OR length(p_origin) NOT BETWEEN 1 AND 64
      OR jsonb_typeof(p_payload)<>'object' THEN
    RAISE EXCEPTION 'Invalid catalog projection identity' USING ERRCODE='23514';
  END IF;
  SELECT * INTO node FROM eatwhat_identity.node_state WHERE singleton;
  IF NOT FOUND THEN RAISE EXCEPTION 'Catalog projection requires an explicit site' USING ERRCODE='55000'; END IF;
  entity:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'ACTIVITY' THEN 'activities' ELSE 'food_tags' END;
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
      WHEN 'ACTIVITY' THEN DELETE FROM public.activities WHERE id=p_numeric;
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
       WHERE poi=p_payload->>'poi' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='FOOD' THEN
    restaurant_gid:=(p_payload->>'restaurantGid')::uuid;
    SELECT numeric_id INTO v_parent_id FROM eatwhat_identity.entity_identity WHERE entity_type='restaurant'
      AND global_id=restaurant_gid;
    IF v_parent_id IS NULL THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object WHERE kind='RESTAURANT'
        AND object_gid=restaurant_gid AND (tombstone OR rejected)) THEN RETURN 'TERMINAL'; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.restaurant WHERE id=v_parent_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.foods WHERE restaurant_id=v_parent_id AND name=p_payload->>'name'
       AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='ACTIVITY' THEN
    IF EXISTS(SELECT 1 FROM public.activities WHERE code=p_payload->>'code'
       AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='SYSTEM_TAG' THEN
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='system'
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  ELSIF p_kind='CUSTOM_TAG' THEN
    owner_gid:=(p_payload->>'ownerGid')::uuid;
    IF owner_gid<>p_author THEN RAISE EXCEPTION 'Custom tag owner mismatch' USING ERRCODE='23514'; END IF;
    SELECT numeric_id INTO v_owner_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=owner_gid;
    IF v_owner_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.users WHERE id=v_owner_id) THEN RETURN 'WAIT_PARENT'; END IF;
    IF EXISTS(SELECT 1 FROM public.food_tags WHERE tag_type='custom' AND food_tags.owner_id=v_owner_id
       AND normalized_name=p_payload->>'normalizedName' AND id<>p_numeric AND catalog_visible) THEN RETURN 'KEY_CONFLICT'; END IF;
  END IF;

  INSERT INTO eatwhat_consent.import_context(transaction_id,backend_id,entity_type,numeric_id,global_id,origin_site,subject_id)
    VALUES(txid_current(),pg_backend_pid(),entity,p_numeric,p_gid,p_origin,COALESCE(p_author,p_gid));
  IF p_kind='HUB' THEN
    IF EXISTS(SELECT 1 FROM public.hub WHERE id=p_numeric) THEN
      UPDATE public.hub SET name=p_payload->>'name',campus_id=(p_payload->>'campusId')::bigint,
        center=public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        gcj_lng=(p_payload->'centerGcj'->>'lng')::float8,gcj_lat=(p_payload->'centerGcj'->>'lat')::float8,
        boundary=CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.hub(id,name,center,gcj_lng,gcj_lat,boundary,campus_id) VALUES(p_numeric,p_payload->>'name',
        public.ST_SetSRID(public.ST_MakePoint((p_payload->'centerWgs'->>'lng')::float8,(p_payload->'centerWgs'->>'lat')::float8),4326),
        (p_payload->'centerGcj'->>'lng')::float8,(p_payload->'centerGcj'->>'lat')::float8,
        CASE WHEN p_payload->>'boundaryGeoJson' IS NULL THEN NULL ELSE
          public.ST_Multi(public.ST_SetSRID(public.ST_GeomFromGeoJSON(p_payload->>'boundaryGeoJson'),4326))::public.geometry(MultiPolygon,4326) END,
        (p_payload->>'campusId')::bigint);
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
        description=p_payload->>'description',price=(p_payload->>'price')::numeric,category=p_payload->>'category',picture_url=picture_keys,
        meal_times=ARRAY(SELECT jsonb_array_elements_text(p_payload->'mealTimes')),
        stage_color=p_payload->>'stageColor',
        stage_alternates=CASE WHEN p_payload ? 'stageAlternates' THEN ARRAY(SELECT jsonb_array_elements_text(p_payload->'stageAlternates')) END,
        cover_center_x=(p_payload->'coverCrop'->>'centerX')::numeric,cover_center_y=(p_payload->'coverCrop'->>'centerY')::numeric,
        cover_diameter=(p_payload->'coverCrop'->>'diameter')::numeric,
        remove_background=COALESCE((p_payload->>'removeBackground')::boolean,false)
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.foods(id,account_id,restaurant_id,name,description,price,category,picture_url,
          meal_times,stage_color,stage_alternates,cover_center_x,cover_center_y,cover_diameter,remove_background)
        VALUES(p_numeric,v_author_id,v_parent_id,p_payload->>'name',p_payload->>'description',
          (p_payload->>'price')::numeric,p_payload->>'category',picture_keys,
          ARRAY(SELECT jsonb_array_elements_text(p_payload->'mealTimes')),p_payload->>'stageColor',
          CASE WHEN p_payload ? 'stageAlternates' THEN ARRAY(SELECT jsonb_array_elements_text(p_payload->'stageAlternates')) END,
          (p_payload->'coverCrop'->>'centerX')::numeric,(p_payload->'coverCrop'->>'centerY')::numeric,
          (p_payload->'coverCrop'->>'diameter')::numeric,COALESCE((p_payload->>'removeBackground')::boolean,false));
    END IF;
  ELSIF p_kind='ACTIVITY' THEN
    IF EXISTS(SELECT 1 FROM public.activities WHERE id=p_numeric) THEN
      UPDATE public.activities SET code=p_payload->>'code',name=p_payload->>'name',description=p_payload->>'description',
        cover_url=p_payload->>'coverUrl',status=p_payload->>'status',
        starts_at=to_timestamp((p_payload->>'startsAt')::bigint/1000.0),ends_at=to_timestamp((p_payload->>'endsAt')::bigint/1000.0),
        sort=(p_payload->>'sort')::integer,require_location=(p_payload->>'requireLocation')::boolean,
        max_pictures=(p_payload->>'maxPictures')::integer,field_schema=p_payload->'fieldSchema'
        WHERE id=p_numeric;
    ELSE
      INSERT INTO public.activities(id,code,name,description,cover_url,status,starts_at,ends_at,sort,
          require_location,max_pictures,field_schema)
        VALUES(p_numeric,p_payload->>'code',p_payload->>'name',p_payload->>'description',p_payload->>'coverUrl',
          p_payload->>'status',to_timestamp((p_payload->>'startsAt')::bigint/1000.0),to_timestamp((p_payload->>'endsAt')::bigint/1000.0),
          (p_payload->>'sort')::integer,(p_payload->>'requireLocation')::boolean,(p_payload->>'maxPictures')::integer,
          p_payload->'fieldSchema');
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

-- Activity definitions are compared field by field with the immutable catalog head.
-- Copied from V73__campus.sql; only the activity additions differ.
CREATE OR REPLACE FUNCTION eatwhat_sync.catalog_payload_matches_public(p_kind text,p_id bigint,p_payload jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_user_gid uuid; v_parent_gid uuid; v_hub_gid uuid; v_owner_gid uuid; v_expected jsonb;
BEGIN
  IF p_kind='HUB' THEN
    -- Keep the exact float8 JSON representation used by CatalogStore's JDBC Double reader.
    -- Casting float8 to numeric here rounds differently from BigDecimal.valueOf(double),
    -- so valid high-precision coordinates could fail the immutable-payload check.
    SELECT jsonb_build_object('name',h.name,'centerWgs',jsonb_build_object('lng',public.ST_X(h.center),'lat',public.ST_Y(h.center)),
      'centerGcj',jsonb_build_object('lng',h.gcj_lng,'lat',h.gcj_lat),
      'boundaryGeoJson',CASE WHEN h.boundary IS NULL THEN NULL ELSE public.ST_AsGeoJSON(h.boundary) END)
      -- campusId appears only when set, so pre-V73 hub heads keep matching.
      || CASE WHEN h.campus_id IS NOT NULL THEN jsonb_build_object('campusId',h.campus_id) ELSE '{}'::jsonb END
      INTO v_expected FROM public.hub h WHERE h.id=p_id;
  ELSIF p_kind='RESTAURANT' THEN
    SELECT ii.global_id INTO v_user_gid FROM public.restaurant r LEFT JOIN eatwhat_identity.entity_identity ii
      ON ii.entity_type='users' AND ii.numeric_id=r.account_id WHERE r.id=p_id;
    SELECT hi.global_id INTO v_hub_gid FROM public.restaurant r LEFT JOIN eatwhat_identity.entity_identity hi
      ON hi.entity_type='hub' AND hi.numeric_id=r.hub_id WHERE r.id=p_id;
    SELECT jsonb_build_object('name',r.name,'address',r.address,'cityId',r.city_id,
      'locationWgs',jsonb_build_object('lng',public.ST_X(r.location::public.geometry),'lat',public.ST_Y(r.location::public.geometry)),
      'locationGcj',jsonb_build_object('lng',r.gcj_lng,'lat',r.gcj_lat),'hubGid',v_hub_gid,
      'poi',r.poi,'pictureKeys',COALESCE(to_jsonb(r.picture_url),'[]'::jsonb))
      INTO v_expected FROM public.restaurant r WHERE r.id=p_id;
    -- A deleted hub's foreign key is set NULL while the head retains its historical hub GID.
    IF v_expected IS NOT NULL AND p_payload->>'hubGid' IS NOT NULL AND v_hub_gid IS NULL
        AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object ho WHERE ho.kind='HUB'
          AND ho.object_gid=(p_payload->>'hubGid')::uuid AND (ho.tombstone OR ho.rejected)) THEN
      v_expected:=jsonb_set(v_expected,'{hubGid}',p_payload->'hubGid');
    END IF;
    IF v_expected IS NOT NULL THEN v_expected:=v_expected||jsonb_build_object('authorGid',v_user_gid); END IF;
  ELSIF p_kind='FOOD' THEN
    SELECT ri.global_id INTO v_parent_gid FROM public.foods f LEFT JOIN eatwhat_identity.entity_identity ri
      ON ri.entity_type='restaurant' AND ri.numeric_id=f.restaurant_id WHERE f.id=p_id;
    SELECT ui.global_id INTO v_user_gid FROM public.foods f LEFT JOIN eatwhat_identity.entity_identity ui
      ON ui.entity_type='users' AND ui.numeric_id=f.account_id WHERE f.id=p_id;
    SELECT jsonb_build_object('restaurantGid',v_parent_gid,'name',f.name,'description',f.description,'price',f.price,
      'category',f.category,'pictureKeys',COALESCE(to_jsonb(f.picture_url),'[]'::jsonb),'authorGid',v_user_gid)
      -- Display fields appear only when they differ from their defaults, so pre-V72 heads keep matching.
      || CASE WHEN cardinality(f.meal_times)>0 THEN jsonb_build_object('mealTimes',to_jsonb(f.meal_times)) ELSE '{}'::jsonb END
      || CASE WHEN f.stage_color IS NOT NULL THEN jsonb_build_object('stageColor',f.stage_color) ELSE '{}'::jsonb END
      || CASE WHEN f.stage_alternates IS NOT NULL THEN jsonb_build_object('stageAlternates',to_jsonb(f.stage_alternates)) ELSE '{}'::jsonb END
      || CASE WHEN f.cover_center_x IS NOT NULL THEN jsonb_build_object('coverCrop',jsonb_build_object(
           'centerX',f.cover_center_x,'centerY',f.cover_center_y,'diameter',f.cover_diameter)) ELSE '{}'::jsonb END
      || CASE WHEN f.remove_background THEN jsonb_build_object('removeBackground',true) ELSE '{}'::jsonb END
      INTO v_expected FROM public.foods f WHERE f.id=p_id;
  ELSIF p_kind='ACTIVITY' THEN
    -- Keys are always present; JSON null stands for an unset optional value.
    SELECT jsonb_build_object('code',a.code,'name',a.name,'description',a.description,'coverUrl',a.cover_url,
      'status',a.status,'startsAt',(extract(epoch FROM a.starts_at)*1000)::bigint,
      'endsAt',(extract(epoch FROM a.ends_at)*1000)::bigint,'sort',a.sort,'requireLocation',a.require_location,
      'maxPictures',a.max_pictures,'fieldSchema',a.field_schema)
      INTO v_expected FROM public.activities a WHERE a.id=p_id;
  ELSIF p_kind IN ('SYSTEM_TAG','CUSTOM_TAG') THEN
    SELECT ui.global_id INTO v_owner_gid FROM public.food_tags t LEFT JOIN eatwhat_identity.entity_identity ui
      ON ui.entity_type='users' AND ui.numeric_id=t.owner_id WHERE t.id=p_id;
    SELECT jsonb_build_object('name',t.name,'normalizedName',t.normalized_name,
      'tagType',t.tag_type,'ownerGid',v_owner_gid) INTO v_expected FROM public.food_tags t WHERE t.id=p_id;
  END IF;
  IF v_expected IS NULL THEN RETURN false; END IF;
  -- Catalog payloads omit locally derived identity fields not present in the canonical domain.
  IF p_kind='HUB' THEN
    IF v_expected IS NULL OR (v_expected-'boundaryGeoJson')<>(p_payload-'boundaryGeoJson') THEN RETURN false; END IF;
    IF p_payload->>'boundaryGeoJson' IS NULL THEN RETURN v_expected->'boundaryGeoJson'=p_payload->'boundaryGeoJson'; END IF;
    RETURN (v_expected->>'boundaryGeoJson')::jsonb=(p_payload->>'boundaryGeoJson')::jsonb;
  END IF;
  IF p_kind='RESTAURANT' THEN RETURN (v_expected-'authorGid')=p_payload; END IF;
  IF p_kind='FOOD' THEN RETURN (v_expected-'authorGid')=p_payload; END IF;
  IF p_kind='SYSTEM_TAG' THEN RETURN (v_expected-'ownerGid')=p_payload; END IF;
  RETURN v_expected=p_payload;
END $$;

-- Same per-table triggers as the existing catalog and activity tables.
CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.activities
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.activities
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();
CREATE TRIGGER identity_baseline_write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.activities
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_identity.guard_baseline_business_write();
CREATE TRIGGER catalog_public_write_guard BEFORE INSERT OR DELETE OR UPDATE ON public.activities
FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_public_write_guard();

CREATE TRIGGER guard_global_identity BEFORE INSERT OR UPDATE OF id ON public.activity_posts
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.guard_business_identity();
CREATE TRIGGER record_global_identity AFTER INSERT ON public.activity_posts
FOR EACH ROW EXECUTE FUNCTION eatwhat_identity.record_business_identity();
CREATE TRIGGER identity_baseline_write_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.activity_posts
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_identity.guard_baseline_business_write();
CREATE TRIGGER file_release_business AFTER DELETE OR UPDATE OF picture_url, deleted_at ON public.activity_posts
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.release_business_file_references();
CREATE TRIGGER zz_file_attach_business AFTER INSERT OR UPDATE OF picture_url, deleted_at ON public.activity_posts
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.attach_business_file_references();
CREATE TRIGGER zz_file_gc_projection_fence BEFORE INSERT OR UPDATE ON public.activity_posts
FOR EACH ROW EXECUTE FUNCTION eatwhat_file.guard_business_gc_fence();
