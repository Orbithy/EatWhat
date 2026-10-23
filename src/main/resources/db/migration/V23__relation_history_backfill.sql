SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- The local user's desired relation state survives account withdrawal projection cleanup.
-- It is keyed by global natural identity, independent from consent epochs.
CREATE TABLE eatwhat_sync.relation_intent (
  natural_id uuid PRIMARY KEY,
  kind varchar(32) NOT NULL CHECK(kind IN ('food_like','activity_food_like','activity_dinner_like','favorite','follow','tagging')),
  actor_gid uuid NOT NULL,
  target_gid uuid NOT NULL,
  owner_gid uuid NOT NULL,
  tag_gid uuid,
  desired_present boolean NOT NULL,
  intent_revision bigint NOT NULL DEFAULT 1 CHECK(intent_revision>0),
  published_revision bigint NOT NULL DEFAULT 0 CHECK(published_revision>=0 AND published_revision<=intent_revision),
  published_actor_epoch bigint,
  published_owner_epoch bigint,
  row_id bigint NOT NULL CHECK(row_id BETWEEN 1 AND 9007199254740991),
  row_gid uuid NOT NULL,
  row_origin varchar(64),
  local_intent boolean NOT NULL DEFAULT true,
  blocked_reason varchar(64),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(kind,actor_gid,target_gid,owner_gid,tag_gid),
  CHECK((kind='tagging')=(tag_gid IS NOT NULL))
);
CREATE INDEX relation_intent_actor ON eatwhat_sync.relation_intent(actor_gid,natural_id);
CREATE INDEX relation_intent_owner ON eatwhat_sync.relation_intent(owner_gid,natural_id);
CREATE INDEX relation_intent_dirty ON eatwhat_sync.relation_intent(updated_at)
  WHERE published_revision<intent_revision OR published_actor_epoch IS NULL OR published_owner_epoch IS NULL;

-- Match Java UUID.nameUUIDFromBytes exactly so pre-V23 immutable relation facts become intents in place.
CREATE FUNCTION eatwhat_sync.relation_natural_id(p_kind text,p_actor uuid,p_target uuid,p_owner uuid,p_tag uuid)
RETURNS uuid LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE h text; variant text;
BEGIN
  h:=md5(p_kind||':'||p_actor::text||':'||p_target::text||':'||p_owner::text||':'||coalesce(p_tag::text,'null'));
  variant:=CASE substr(h,17,1)
    WHEN '0' THEN '8' WHEN '4' THEN '8' WHEN '8' THEN '8' WHEN 'c' THEN '8'
    WHEN '1' THEN '9' WHEN '5' THEN '9' WHEN '9' THEN '9' WHEN 'd' THEN '9'
    WHEN '2' THEN 'a' WHEN '6' THEN 'a' WHEN 'a' THEN 'a' WHEN 'e' THEN 'a'
    ELSE 'b' END;
  RETURN (substr(h,1,12)||'3'||substr(h,14,3)||variant||substr(h,18))::uuid;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.relation_natural_id(text,uuid,uuid,uuid,uuid) FROM PUBLIC;

-- Keep the latest epoch's remove-wins result as the migration seed. A missing public row alone
-- is never used to infer a REMOVE; only immutable operation history can seed an absent state.
WITH decoded AS (
  SELECT o.relation_id,o.context,o.dot_site,o.dot_counter,o.adding,
    o.payload->>'kind' AS kind,(o.payload->>'actor')::uuid AS actor_gid,
    (o.payload->>'target')::uuid AS target_gid,(o.payload->>'owner')::uuid AS owner_gid,
    nullif(o.payload->>'tag','null')::uuid AS tag_gid,
    (o.payload->>'actorEpoch')::bigint AS actor_epoch,(o.payload->>'ownerEpoch')::bigint AS owner_epoch,
    (o.payload->>'rowId')::bigint AS row_id,(o.payload->>'rowGid')::uuid AS row_gid,
    o.payload->>'rowOrigin' AS row_origin
  FROM eatwhat_sync.relation_operation o
), effective AS (
  SELECT d.*,
    EXISTS(SELECT 1 FROM decoded a WHERE a.relation_id=d.relation_id AND a.adding
      AND NOT EXISTS(SELECT 1 FROM decoded r WHERE r.relation_id=a.relation_id AND NOT r.adding
        AND coalesce((a.context->>r.dot_site)::bigint,0)<r.dot_counter)) AS present
  FROM decoded d
), latest AS (
  SELECT DISTINCT ON (kind,actor_gid,target_gid,owner_gid,tag_gid) *
  FROM effective ORDER BY kind,actor_gid,target_gid,owner_gid,tag_gid,actor_epoch DESC,owner_epoch DESC
), inspected AS (
  SELECT l.*,
    CASE l.kind
      WHEN 'food_like' THEN EXISTS(SELECT 1 FROM public.likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='food' AND r.target_id=t.numeric_id)
      WHEN 'activity_food_like' THEN EXISTS(SELECT 1 FROM public.activity_likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='activity_foods' AND t.global_id=l.target_gid
        JOIN public.activity_foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='food' AND r.activity_id=t.numeric_id)
      WHEN 'activity_dinner_like' THEN EXISTS(SELECT 1 FROM public.activity_likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='activity_dinners' AND t.global_id=l.target_gid
        JOIN public.activity_dinners f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='dinner' AND r.activity_id=t.numeric_id)
      WHEN 'favorite' THEN EXISTS(SELECT 1 FROM public.food_favorites r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.food_id=t.numeric_id)
      WHEN 'follow' THEN EXISTS(SELECT 1 FROM public.follow r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='users' AND t.global_id=l.target_gid
        WHERE r.account_id=a.numeric_id AND r.target_id=t.numeric_id AND l.owner_gid=l.target_gid)
      WHEN 'tagging' THEN EXISTS(SELECT 1 FROM public.food_taggings r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN eatwhat_identity.entity_identity g ON g.entity_type='food_tags' AND g.global_id=l.tag_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.food_id=t.numeric_id AND r.tag_id=g.numeric_id)
      ELSE false END AS public_row_exists,
    CASE l.kind
      WHEN 'food_like' THEN EXISTS(SELECT 1 FROM public.likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='food' AND r.target_id=t.numeric_id AND r.deleted_at IS NULL)
      WHEN 'activity_food_like' THEN EXISTS(SELECT 1 FROM public.activity_likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='activity_foods' AND t.global_id=l.target_gid
        JOIN public.activity_foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='food' AND r.activity_id=t.numeric_id AND r.deleted_at IS NULL)
      WHEN 'activity_dinner_like' THEN EXISTS(SELECT 1 FROM public.activity_likes r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='activity_dinners' AND t.global_id=l.target_gid
        JOIN public.activity_dinners f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.target_type='dinner' AND r.activity_id=t.numeric_id AND r.deleted_at IS NULL)
      WHEN 'favorite' THEN EXISTS(SELECT 1 FROM public.food_favorites r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.food_id=t.numeric_id)
      WHEN 'follow' THEN EXISTS(SELECT 1 FROM public.follow r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='users' AND t.global_id=l.target_gid
        WHERE r.account_id=a.numeric_id AND r.target_id=t.numeric_id AND l.owner_gid=l.target_gid)
      WHEN 'tagging' THEN EXISTS(SELECT 1 FROM public.food_taggings r
        JOIN eatwhat_identity.entity_identity a ON a.entity_type='users' AND a.global_id=l.actor_gid
        JOIN eatwhat_identity.entity_identity t ON t.entity_type='foods' AND t.global_id=l.target_gid
        JOIN eatwhat_identity.entity_identity g ON g.entity_type='food_tags' AND g.global_id=l.tag_gid
        JOIN public.foods f ON f.id=t.numeric_id
        JOIN eatwhat_identity.entity_identity o ON o.entity_type='users' AND o.global_id=l.owner_gid AND o.numeric_id=f.account_id
        WHERE r.account_id=a.numeric_id AND r.food_id=t.numeric_id AND r.tag_id=g.numeric_id)
      ELSE false END AS public_present
  FROM latest l
)
INSERT INTO eatwhat_sync.relation_intent(natural_id,kind,actor_gid,target_gid,owner_gid,tag_gid,
  desired_present,intent_revision,published_revision,row_id,row_gid,row_origin,local_intent,blocked_reason)
SELECT eatwhat_sync.relation_natural_id(kind,actor_gid,target_gid,owner_gid,tag_gid),kind,actor_gid,target_gid,
  owner_gid,tag_gid,CASE WHEN public_row_exists THEN public_present ELSE present END,1,0,row_id,row_gid,row_origin,false,
  CASE WHEN NOT public_row_exists AND present THEN 'LEGACY_HISTORY_AMBIGUOUS' ELSE NULL END
FROM inspected WHERE row_id>0 AND row_gid IS NOT NULL
ON CONFLICT(natural_id) DO NOTHING;

-- A job is a durable subject+epoch scan. Its seek cursor advances only after a candidate commits.
CREATE TABLE eatwhat_sync.relation_backfill_job (
  job_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_id uuid NOT NULL,
  user_id bigint NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  status varchar(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','WAIT','DONE','BLOCKED')),
  phase smallint NOT NULL DEFAULT 0 CHECK(phase BETWEEN 0 AND 6),
  row_cursor bigint NOT NULL DEFAULT 0 CHECK(row_cursor>=0),
  history_cursor text,
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  last_error varchar(64),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(subject_id,epoch),
  CHECK((status='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
     OR (status<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX relation_backfill_ready ON eatwhat_sync.relation_backfill_job(available_at,created_at)
  WHERE status IN ('READY','WAIT');
CREATE INDEX relation_backfill_expired ON eatwhat_sync.relation_backfill_job(lease_until,created_at)
  WHERE status='LEASED';

-- Runtime cannot create or rewrite baseline claims. The offline operator path registers only an
-- already shared V18 manifest identity and leaves entity_identity.origin_site untouched.
CREATE TABLE eatwhat_sync.relation_baseline_claim (
  entity_type varchar(32) NOT NULL CHECK(entity_type IN ('likes','activity_likes','food_favorites','follow','food_taggings')),
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 1000000000),
  global_id uuid NOT NULL,
  declared_origin varchar(64) NOT NULL,
  natural_key jsonb NOT NULL CHECK(jsonb_typeof(natural_key)='object'),
  baseline_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(entity_type,numeric_id),
  UNIQUE(global_id)
);
CREATE TRIGGER relation_baseline_claim_immutable
  BEFORE UPDATE OR DELETE OR TRUNCATE ON eatwhat_sync.relation_baseline_claim
  FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.relation_immutable();
REVOKE ALL ON eatwhat_sync.relation_baseline_claim FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.claim_relation_baseline(
  p_kind text,p_row bigint,p_gid uuid,p_actor_gid uuid,p_target_gid uuid,p_owner_gid uuid,
  p_tag_gid uuid,p_declared_origin text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE tbl text; target_table text; target_col text; k text; v_natural jsonb; baseline uuid;
  v_actor_id bigint; v_target_id bigint; v_owner_id bigint; v_tag_id bigint; identity_row eatwhat_identity.entity_identity%ROWTYPE;
  business_match boolean; expected_origin text;
BEGIN
  CASE p_kind
    WHEN 'food_like' THEN tbl:='likes';target_table:='foods';target_col:='target_id';
    WHEN 'activity_food_like' THEN tbl:='activity_likes';target_table:='activity_foods';target_col:='activity_id';
    WHEN 'activity_dinner_like' THEN tbl:='activity_likes';target_table:='activity_dinners';target_col:='activity_id';
    WHEN 'favorite' THEN tbl:='food_favorites';target_table:='foods';target_col:='food_id';
    WHEN 'follow' THEN tbl:='follow';target_table:='users';target_col:='target_id';
    WHEN 'tagging' THEN tbl:='food_taggings';target_table:='foods';target_col:='food_id';
    ELSE RAISE EXCEPTION 'Unsupported baseline relation' USING ERRCODE='23514';
  END CASE;
  IF p_row IS NULL OR p_row<1 OR p_row>1000000000 OR p_gid IS NULL
     OR p_declared_origin IS NULL OR p_declared_origin !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
     OR p_actor_gid IS NULL OR p_target_gid IS NULL OR p_owner_gid IS NULL
     OR ((p_kind='tagging')<>(p_tag_gid IS NOT NULL)) THEN
    RAISE EXCEPTION 'Invalid baseline relation claim' USING ERRCODE='23514'; END IF;
  SELECT baseline_id INTO baseline FROM eatwhat_identity.baseline WHERE singleton
    AND baseline_role IN ('AUTHORITATIVE','REPLICA');
  IF baseline IS NULL THEN
    RAISE EXCEPTION 'LEGACY_BASELINE_NOT_ACTIVATED' USING ERRCODE='23514'; END IF;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_identity.baseline_checkpoint WHERE entity_type=tbl
      AND completed AND high_watermark>=p_row) THEN
    RAISE EXCEPTION 'LEGACY_BASELINE_NOT_ACTIVATED' USING ERRCODE='23514'; END IF;
  SELECT * INTO identity_row FROM eatwhat_identity.entity_identity
    WHERE entity_type=tbl AND numeric_id=p_row FOR UPDATE;
  IF NOT FOUND OR identity_row.global_id<>p_gid OR identity_row.origin_site IS NOT NULL THEN
    RAISE EXCEPTION 'Baseline identity mismatch' USING ERRCODE='23505'; END IF;
  SELECT numeric_id INTO v_actor_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=p_actor_gid;
  SELECT numeric_id INTO v_target_id FROM eatwhat_identity.entity_identity WHERE entity_type=target_table AND global_id=p_target_gid;
  SELECT numeric_id INTO v_owner_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND global_id=p_owner_gid;
  IF v_actor_id IS NULL OR v_target_id IS NULL OR v_owner_id IS NULL THEN
    RAISE EXCEPTION 'Baseline relation dependencies are unavailable' USING ERRCODE='23503'; END IF;
  IF p_tag_gid IS NOT NULL THEN
    SELECT numeric_id INTO v_tag_id FROM eatwhat_identity.entity_identity WHERE entity_type='food_tags' AND global_id=p_tag_gid;
    IF v_tag_id IS NULL THEN RAISE EXCEPTION 'Baseline tag dependency is unavailable' USING ERRCODE='23503'; END IF;
  END IF;
  k:=p_kind||':'||v_actor_id||':'||v_target_id||':'||coalesce(v_tag_id,0);
  v_natural:=jsonb_build_object('kind',p_kind,'actor',p_actor_gid,'target',p_target_gid,
      'owner',p_owner_gid,'tag',p_tag_gid);
  IF tbl='likes' THEN
    SELECT EXISTS(SELECT 1 FROM public.likes r JOIN public.foods t ON t.id=r.target_id
      JOIN public.users o ON o.id=t.account_id WHERE r.id=p_row AND r.account_id=v_actor_id
      AND r.target_type='food' AND r.target_id=v_target_id AND o.id=v_owner_id) INTO business_match;
  ELSIF tbl='activity_likes' THEN
    SELECT EXISTS(SELECT 1 FROM public.activity_likes r JOIN public.activity_foods t ON t.id=r.activity_id
      JOIN public.users o ON o.id=t.account_id WHERE p_kind='activity_food_like' AND r.id=p_row
      AND r.account_id=v_actor_id AND r.target_type='food' AND r.activity_id=v_target_id AND o.id=v_owner_id)
    OR EXISTS(SELECT 1 FROM public.activity_likes r JOIN public.activity_dinners t ON t.id=r.activity_id
      JOIN public.users o ON o.id=t.account_id WHERE p_kind='activity_dinner_like' AND r.id=p_row
      AND r.account_id=v_actor_id AND r.target_type='dinner' AND r.activity_id=v_target_id AND o.id=v_owner_id)
      INTO business_match;
  ELSIF tbl='food_favorites' THEN
    SELECT EXISTS(SELECT 1 FROM public.food_favorites r JOIN public.foods t ON t.id=r.food_id
      WHERE r.id=p_row AND r.account_id=v_actor_id AND r.food_id=v_target_id AND t.account_id=v_owner_id)
      INTO business_match;
  ELSIF tbl='follow' THEN
    SELECT EXISTS(SELECT 1 FROM public.follow r WHERE r.id=p_row AND r.account_id=v_actor_id
      AND r.target_id=v_target_id AND r.target_id=v_owner_id AND v_actor_id<>v_owner_id) INTO business_match;
  ELSE
    SELECT EXISTS(SELECT 1 FROM public.food_taggings r JOIN public.foods t ON t.id=r.food_id
      JOIN public.food_tags g ON g.id=r.tag_id WHERE r.id=p_row AND r.account_id=v_actor_id
      AND r.food_id=v_target_id AND t.account_id=v_owner_id AND r.tag_id=v_tag_id
      AND (g.tag_type='system' OR (g.tag_type='custom' AND g.owner_id=v_actor_id))) INTO business_match;
  END IF;
  IF NOT coalesce(business_match,false) THEN
    RAISE EXCEPTION 'Baseline relation natural key mismatch' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_sync.relation_baseline_claim(entity_type,numeric_id,global_id,declared_origin,natural_key,baseline_id)
    VALUES(tbl,p_row,p_gid,p_declared_origin,v_natural,baseline) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_baseline_claim c WHERE c.entity_type=tbl
      AND c.numeric_id=p_row AND c.global_id=p_gid AND c.declared_origin=p_declared_origin
      AND c.natural_key=v_natural AND c.baseline_id=baseline) THEN
    RAISE EXCEPTION 'Baseline relation claim conflicts with prior binding' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_sync.relation_projection(relation_key,entity_type,numeric_id,global_id,origin_site)
    VALUES(k,tbl,p_row,p_gid,p_declared_origin) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_projection p WHERE p.relation_key=k AND p.entity_type=tbl
      AND p.numeric_id=p_row AND p.global_id=p_gid AND p.origin_site=p_declared_origin) THEN
    RAISE EXCEPTION 'Baseline relation projection conflicts' USING ERRCODE='23505'; END IF;
  INSERT INTO eatwhat_sync.relation_alias(entity_type,numeric_id,global_id,relation_key)
    VALUES(tbl,p_row,p_gid,k) ON CONFLICT DO NOTHING;
  IF NOT EXISTS(SELECT 1 FROM eatwhat_sync.relation_alias a WHERE a.entity_type=tbl AND a.numeric_id=p_row
      AND a.global_id=p_gid AND a.relation_key=k) THEN
    RAISE EXCEPTION 'Baseline relation alias conflicts' USING ERRCODE='23505'; END IF;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.claim_relation_baseline(text,bigint,uuid,uuid,uuid,uuid,uuid,text) FROM PUBLIC;
