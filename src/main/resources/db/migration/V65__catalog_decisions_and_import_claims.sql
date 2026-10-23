SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_sync.catalog_decision_operation (
  operation_id uuid PRIMARY KEY,
  conflict_id uuid NOT NULL,
  kind varchar(24) NOT NULL CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  object_gid uuid,
  source_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter BETWEEN 1 AND 9007199254740991),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  candidate_operations jsonb NOT NULL CHECK(jsonb_typeof(candidate_operations)='array'),
  decision varchar(8) NOT NULL CHECK(decision IN ('KEEP','REJECT')),
  winner_operation_id uuid,
  actor_gid uuid NOT NULL,
  actor_evidence jsonb NOT NULL CHECK(jsonb_typeof(actor_evidence)='object'),
  reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(conflict_id,source_site,dot_counter),
  CHECK((decision='KEEP')=(winner_operation_id IS NOT NULL))
);
CREATE TABLE eatwhat_sync.catalog_decision_state (
  conflict_id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL,
  object_gid uuid,
  heads jsonb NOT NULL CHECK(jsonb_typeof(heads)='array'),
  seen jsonb NOT NULL CHECK(jsonb_typeof(seen)='object'),
  status varchar(16) NOT NULL CHECK(status IN ('OPEN','RESOLVED','REJECTED','CONFLICT','AUTH_REVIEW')),
  winner_operation_id uuid,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Imported HUB source IDs are natural keys without merging their allocated object identities.
ALTER TABLE eatwhat_sync.catalog_natural_key DROP CONSTRAINT catalog_natural_key_kind_check;
ALTER TABLE eatwhat_sync.catalog_natural_key ADD CONSTRAINT catalog_natural_key_kind_check CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG'));
ALTER TABLE eatwhat_sync.catalog_candidate DROP CONSTRAINT catalog_candidate_kind_check;
ALTER TABLE eatwhat_sync.catalog_candidate ADD CONSTRAINT catalog_candidate_kind_check CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG'));
CREATE TABLE eatwhat_sync.catalog_import_claim (
  operation_id uuid PRIMARY KEY,
  source_key varchar(128) NOT NULL,
  source_id varchar(256) NOT NULL,
  request_id uuid NOT NULL,
  batch_hash char(64) NOT NULL CHECK(batch_hash ~ '^[0-9a-f]{64}$'),
  object_gid uuid NOT NULL,
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 9007199254740991),
  source_site varchar(64) NOT NULL,
  actor_gid uuid NOT NULL,
  actor_evidence jsonb NOT NULL CHECK(jsonb_typeof(actor_evidence)='object'),
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX catalog_import_claim_source ON eatwhat_sync.catalog_import_claim(source_key,source_id);
CREATE INDEX catalog_import_claim_batch ON eatwhat_sync.catalog_import_claim(source_key,request_id);
CREATE TRIGGER catalog_decision_operation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_decision_operation FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_import_claim_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_import_claim FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();

-- Retain losing candidate rows and their FK dependants while releasing only their visible natural-key claim.
ALTER TABLE public.hub ADD COLUMN catalog_visible boolean NOT NULL DEFAULT true;
ALTER TABLE public.restaurant ADD COLUMN catalog_visible boolean NOT NULL DEFAULT true;
ALTER TABLE public.foods ADD COLUMN catalog_visible boolean NOT NULL DEFAULT true;
ALTER TABLE public.food_tags ADD COLUMN catalog_visible boolean NOT NULL DEFAULT true;
ALTER TABLE public.restaurant DROP CONSTRAINT restaurant_poi_key;
DROP INDEX public.idx_restaurant_poi;
CREATE UNIQUE INDEX idx_restaurant_poi ON public.restaurant(poi) WHERE poi IS NOT NULL AND catalog_visible;
ALTER TABLE public.foods DROP CONSTRAINT foods_restaurant_id_name_key;
CREATE UNIQUE INDEX foods_restaurant_id_name_visible_key ON public.foods(restaurant_id,name) WHERE catalog_visible;
DROP INDEX public.uk_food_tags_system_name;
DROP INDEX public.uk_food_tags_custom_owner_name;
CREATE UNIQUE INDEX uk_food_tags_system_name ON public.food_tags(normalized_name) WHERE tag_type='system' AND catalog_visible;
CREATE UNIQUE INDEX uk_food_tags_custom_owner_name ON public.food_tags(owner_id,normalized_name) WHERE tag_type='custom' AND catalog_visible;

CREATE TABLE eatwhat_sync.catalog_visibility_context (
  transaction_id bigint NOT NULL,
  backend_id integer NOT NULL,
  kind varchar(24) NOT NULL,
  numeric_id bigint NOT NULL,
  target_visible boolean NOT NULL,
  PRIMARY KEY(transaction_id,backend_id,kind,numeric_id)
);
CREATE FUNCTION eatwhat_sync.refresh_catalog_visibility(p_kind text,p_numeric bigint) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_visible boolean; v_table text;
BEGIN
  v_table:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'SYSTEM_TAG' THEN 'food_tags' WHEN 'CUSTOM_TAG' THEN 'food_tags' END;
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
REVOKE ALL ON FUNCTION eatwhat_sync.refresh_catalog_visibility(text,bigint) FROM PUBLIC;

CREATE TABLE eatwhat_sync.catalog_decision_projection_context (
  transaction_id bigint NOT NULL, backend_id integer NOT NULL, kind varchar(24) NOT NULL,
  numeric_id bigint NOT NULL, decision_id uuid NOT NULL, winner_operation_id uuid NOT NULL,
  PRIMARY KEY(transaction_id,backend_id,kind,numeric_id)
);
CREATE FUNCTION eatwhat_sync.catalog_decision_proven(p_decision uuid) RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_decision_operation d
   JOIN eatwhat_sync.catalog_decision_state s ON s.conflict_id=d.conflict_id
   WHERE d.operation_id=p_decision AND s.status='RESOLVED' AND s.heads ? p_decision::text
     AND s.winner_operation_id=d.winner_operation_id AND d.decision='KEEP'
     AND (EXISTS(SELECT 1 FROM eatwhat_sync.outbox e JOIN eatwhat_identity.node_state n ON n.singleton
       WHERE e.event_id=p_decision AND e.origin_site=n.site_id AND e.aggregate_type='catalog.decision'
         AND e.envelope->>'eventType'='catalog.decision.action' AND e.envelope->>'schemaVersion'='2'
         AND (e.envelope->>'payloadJson')::jsonb=d.payload)
       OR EXISTS(SELECT 1 FROM eatwhat_sync.inbox e WHERE e.event_id=p_decision
         AND e.aggregate_type='catalog.decision' AND e.status IN ('PROCESSING','APPLIED')
         AND e.envelope->>'eventType'='catalog.decision.action' AND e.envelope->>'schemaVersion'='2'
         AND (e.envelope->>'payloadJson')::jsonb=d.payload)
       OR EXISTS(SELECT 1 FROM eatwhat_sync.inbox e WHERE e.aggregate_type='catalog.decision' AND e.status IN ('PROCESSING','APPLIED')
         AND e.envelope->>'eventType'='catalog.decision.bridge' AND e.envelope->>'schemaVersion'='2'
         AND ((e.envelope->>'payloadJson')::jsonb->'decisions') @> jsonb_build_array(d.payload))))
$$;
CREATE FUNCTION eatwhat_sync.apply_catalog_decision_projection(p_decision uuid) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE r eatwhat_sync.catalog_revision%ROWTYPE; result text;
BEGIN
 IF NOT eatwhat_sync.catalog_decision_proven(p_decision) THEN
   RAISE EXCEPTION 'Catalog decision lacks authenticated durable event' USING ERRCODE='42501';
 END IF;
 SELECT rv.* INTO r FROM eatwhat_sync.catalog_decision_operation d
 JOIN eatwhat_sync.catalog_revision rv ON rv.operation_id=d.winner_operation_id WHERE d.operation_id=p_decision;
 IF NOT FOUND OR r.tombstone THEN RAISE EXCEPTION 'Invalid catalog decision winner' USING ERRCODE='23514'; END IF;
 INSERT INTO eatwhat_sync.catalog_decision_projection_context
   VALUES(txid_current(),pg_backend_pid(),r.kind,r.numeric_id,p_decision,r.operation_id);
 result:=eatwhat_sync.apply_catalog_projection(r.kind,r.object_gid,r.numeric_id,r.identity_origin,r.author_gid,r.parent_gid,'UPDATE',r.payload);
 IF r.kind='HUB' AND result='APPLIED' THEN
   UPDATE eatwhat_sync.catalog_hub_source h SET object_gid=r.object_gid,numeric_id=r.numeric_id,
     content_hash=r.content_hash,updated_at=clock_timestamp()
   FROM eatwhat_sync.catalog_decision_operation d JOIN eatwhat_sync.catalog_conflict c ON c.conflict_id=d.conflict_id
   JOIN eatwhat_sync.catalog_natural_key k ON k.kind=c.kind AND k.key_hash=c.key_hash
   WHERE d.operation_id=p_decision AND c.kind='HUB' AND h.source_key=k.canonical_key->>'sourceKey'
     AND h.source_id=k.canonical_key->>'sourceId';
 END IF;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_decision_proven(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.apply_catalog_decision_projection(uuid) FROM PUBLIC;

-- Decision history can arrive before its catalog candidates during a fresh/regrant backfill.
CREATE TABLE eatwhat_sync.catalog_decision_archive (
 operation_id uuid NOT NULL, bridge_event_id uuid NOT NULL, payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(operation_id,bridge_event_id)
);
CREATE TRIGGER catalog_decision_archive_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
 ON eatwhat_sync.catalog_decision_archive FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();

-- A revoked decision remains under review even if its actor is subsequently reauthorized.
CREATE TABLE eatwhat_sync.catalog_decision_authorization_review (
 operation_id uuid PRIMARY KEY, conflict_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER catalog_decision_authorization_review_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
 ON eatwhat_sync.catalog_decision_authorization_review FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
