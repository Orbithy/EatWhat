SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
-- Only identities and version metadata are retained here; activity content remains in its owning table.
CREATE TABLE eatwhat_sync.activity_intent (
  table_name text NOT NULL CHECK(table_name IN ('activity_foods','activity_dinners')),
  object_gid uuid NOT NULL,
  numeric_id bigint NOT NULL,
  author_gid uuid NOT NULL,
  deleted_at bigint CHECK(deleted_at>0),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  published_revision bigint NOT NULL DEFAULT 0,
  published_epoch bigint NOT NULL DEFAULT 0,
  attempted_at timestamptz,
  PRIMARY KEY(table_name,object_gid), UNIQUE(table_name,numeric_id)
);
REVOKE ALL ON eatwhat_sync.activity_intent FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.apply_activity_state_created(p_table text, p_payload jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE node eatwhat_identity.node_state%ROWTYPE;
  allowed text[]; k text; numeric bigint; gid uuid; origin text; author_gid uuid; author_id bigint; existing uuid; identity_exists boolean; business_exists boolean;
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

REVOKE ALL ON FUNCTION eatwhat_sync.apply_activity_state_created(text,jsonb) FROM PUBLIC;

-- Migration-owner-only path for reviewed historical identity manifests. Business data is never rewritten.
CREATE TABLE eatwhat_sync.historical_domain_source_claim (
 entity_type text NOT NULL,numeric_id bigint NOT NULL,global_id uuid NOT NULL,
 origin_site text NOT NULL,manifest_sha256 text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(entity_type,numeric_id)
);
CREATE FUNCTION eatwhat_sync.claim_historical_domain_source(p_table text,p_id bigint,p_gid uuid,p_origin text,p_manifest_sha256 text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE present boolean; mapping eatwhat_identity.entity_identity%ROWTYPE; claim eatwhat_sync.historical_domain_source_claim%ROWTYPE;
BEGIN
 IF p_table NOT IN ('activity_foods','activity_dinners','notifications') OR p_id<1 OR p_id>1000000000
  OR p_gid IS NULL OR p_gid='00000000-0000-0000-0000-000000000000'::uuid OR p_origin IS NULL
  OR p_origin!~'^[a-z][a-z0-9-]{0,62}$' OR p_manifest_sha256!~'^[0-9a-f]{64}$' THEN
  RAISE EXCEPTION 'Invalid reviewed historical identity claim' USING ERRCODE='23514'; END IF;
 EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I WHERE id=$1)',p_table) INTO present USING p_id;
 IF NOT present THEN RAISE EXCEPTION 'Historical source row is unavailable' USING ERRCODE='23503'; END IF;
 SELECT * INTO mapping FROM eatwhat_identity.entity_identity WHERE entity_type=p_table AND numeric_id=p_id FOR UPDATE;
 IF FOUND AND (mapping.global_id<>p_gid OR mapping.origin_site IS NOT NULL AND mapping.origin_site<>p_origin) THEN RAISE EXCEPTION 'Historical identity collision' USING ERRCODE='23505'; END IF;
 SELECT * INTO claim FROM eatwhat_sync.historical_domain_source_claim WHERE entity_type=p_table AND numeric_id=p_id;
 IF FOUND THEN
  IF claim.global_id<>p_gid OR claim.origin_site<>p_origin OR claim.manifest_sha256<>p_manifest_sha256 THEN RAISE EXCEPTION 'Historical manifest collision' USING ERRCODE='23505'; END IF;
  RETURN false;
 END IF;
 INSERT INTO eatwhat_identity.entity_identity(entity_type,numeric_id,global_id,origin_site)
 VALUES(p_table,p_id,p_gid,p_origin) ON CONFLICT(entity_type,numeric_id) DO UPDATE SET origin_site=EXCLUDED.origin_site WHERE entity_identity.origin_site IS NULL;
 INSERT INTO eatwhat_sync.historical_domain_source_claim(entity_type,numeric_id,global_id,origin_site,manifest_sha256)
 VALUES(p_table,p_id,p_gid,p_origin,p_manifest_sha256);
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.claim_historical_domain_source(text,bigint,uuid,text,text) FROM PUBLIC;
REVOKE ALL ON eatwhat_sync.historical_domain_source_claim FROM PUBLIC;

-- Content-free provenance survives overseas withdrawal so an archived copy can restore the same object.
CREATE TABLE eatwhat_sync.activity_source_proof (
 table_name text NOT NULL CHECK(table_name IN ('activity_foods','activity_dinners')),
 object_gid uuid NOT NULL,author_gid uuid NOT NULL,origin_site text NOT NULL,content_sha256 text NOT NULL,
 PRIMARY KEY(table_name,object_gid)
);
REVOKE ALL ON eatwhat_sync.activity_source_proof FROM PUBLIC;
