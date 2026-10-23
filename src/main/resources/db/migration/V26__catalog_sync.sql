SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- Catalog identity is separate from author and from the site allowed to manage edits.
CREATE TABLE eatwhat_sync.catalog_object (
  kind varchar(24) NOT NULL CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  object_gid uuid NOT NULL,
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 9007199254740991),
  origin_site varchar(64) NOT NULL,
  authority_site varchar(64) NOT NULL,
  author_gid uuid,
  parent_gid uuid,
  revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
  causal_state jsonb NOT NULL DEFAULT '{"seen":{},"heads":[]}'::jsonb
    CHECK(jsonb_typeof(causal_state)='object'),
  tombstone boolean NOT NULL DEFAULT false,
  rejected boolean NOT NULL DEFAULT false,
  managed boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(kind,object_gid),
  UNIQUE(kind,numeric_id),
  CHECK(NOT (tombstone AND rejected))
);
CREATE INDEX catalog_object_author ON eatwhat_sync.catalog_object(author_gid,kind);
CREATE INDEX catalog_object_parent ON eatwhat_sync.catalog_object(parent_gid,kind) WHERE parent_gid IS NOT NULL;

-- Immutable revision evidence stores original source operation IDs and bounded causal heads.
CREATE TABLE eatwhat_sync.catalog_revision (
  operation_id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  object_gid uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>0),
  expected_revision bigint NOT NULL CHECK(expected_revision>=0),
  operation varchar(24) NOT NULL CHECK(operation IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')),
  source_site varchar(64) NOT NULL,
  authority_site varchar(64) NOT NULL,
  author_gid uuid,
  parent_gid uuid,
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 9007199254740991),
  identity_origin varchar(64) NOT NULL,
  dot_site varchar(64) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter BETWEEN 1 AND 9007199254740991),
  context jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(context)='object'),
  consent_epochs jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(consent_epochs)='array'),
  tombstone boolean NOT NULL DEFAULT false,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(payload)='object'),
  content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  resolution_proof jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(kind,object_gid) REFERENCES eatwhat_sync.catalog_object(kind,object_gid)
    DEFERRABLE INITIALLY DEFERRED,
  UNIQUE(kind,object_gid,dot_site,dot_counter),
  CHECK(NOT (tombstone AND payload<>'{}'::jsonb)),
  CHECK((operation='DELETE')=(tombstone))
);
CREATE INDEX catalog_revision_object ON eatwhat_sync.catalog_revision(kind,object_gid,revision DESC);

-- Natural keys keep their exact raw/canonical value beside the digest to reject hash collisions.
CREATE TABLE eatwhat_sync.catalog_natural_key (
  kind varchar(24) NOT NULL CHECK(kind IN ('RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  key_hash char(64) NOT NULL CHECK(key_hash ~ '^[0-9a-f]{64}$'),
  canonical_key jsonb NOT NULL CHECK(jsonb_typeof(canonical_key)='object'),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(kind,key_hash)
);

CREATE TABLE eatwhat_sync.catalog_candidate (
  candidate_id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL CHECK(kind IN ('RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  key_hash char(64) NOT NULL,
  object_gid uuid NOT NULL,
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 9007199254740991),
  source_site varchar(64) NOT NULL,
  author_gid uuid,
  revision bigint NOT NULL CHECK(revision>0),
  content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  summary jsonb NOT NULL CHECK(jsonb_typeof(summary)='object'),
  status varchar(16) NOT NULL CHECK(status IN ('PENDING','KEPT','REJECTED','SUPERSEDED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(kind,key_hash,object_gid),
  FOREIGN KEY(kind,key_hash) REFERENCES eatwhat_sync.catalog_natural_key(kind,key_hash)
);
CREATE INDEX catalog_candidate_open ON eatwhat_sync.catalog_candidate(kind,key_hash,status,created_at);

CREATE TABLE eatwhat_sync.catalog_conflict (
  conflict_id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL,
  key_hash char(64) NOT NULL,
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  status varchar(16) NOT NULL CHECK(status IN ('OPEN','RESOLVED','REJECTED')),
  candidates jsonb NOT NULL CHECK(jsonb_typeof(candidates)='array'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(kind,key_hash),
  FOREIGN KEY(kind,key_hash) REFERENCES eatwhat_sync.catalog_natural_key(kind,key_hash)
);
CREATE INDEX catalog_conflict_open ON eatwhat_sync.catalog_conflict(status,updated_at);

CREATE TABLE eatwhat_sync.catalog_conflict_audit (
  audit_id uuid PRIMARY KEY,
  conflict_id uuid NOT NULL REFERENCES eatwhat_sync.catalog_conflict(conflict_id),
  conflict_revision bigint NOT NULL CHECK(conflict_revision>0),
  expected_candidates jsonb NOT NULL CHECK(jsonb_typeof(expected_candidates)='array'),
  decision varchar(8) NOT NULL CHECK(decision IN ('KEEP','REJECT')),
  winner_gid uuid,
  actor_gid uuid NOT NULL,
  reason varchar(1000) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((decision='KEEP')=(winner_gid IS NOT NULL))
);

-- Inbound resolutions have no local administrator actor; retain the exact authenticated proof instead.
CREATE TABLE eatwhat_sync.catalog_resolution_receipt (
  conflict_id uuid NOT NULL REFERENCES eatwhat_sync.catalog_conflict(conflict_id),
  decision varchar(8) NOT NULL CHECK(decision IN ('KEEP','REJECT')),
  winner_gid uuid,
  source_site varchar(64) NOT NULL,
  proof jsonb NOT NULL CHECK(jsonb_typeof(proof)='object'),
  expected_candidates jsonb NOT NULL CHECK(jsonb_typeof(expected_candidates)='array'),
  proof_hash char(64) NOT NULL CHECK(proof_hash ~ '^[0-9a-f]{64}$'),
  conflict_revision bigint NOT NULL CHECK(conflict_revision>0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((decision='KEEP')=(winner_gid IS NOT NULL)),
  PRIMARY KEY(conflict_id,proof_hash)
);

-- Same-object concurrent edits can have divergent natural keys and still require one full-head CAS.
CREATE TABLE eatwhat_sync.catalog_head_conflict (
  conflict_id uuid PRIMARY KEY,
  kind varchar(24) NOT NULL CHECK(kind='CUSTOM_TAG'),
  object_gid uuid NOT NULL,
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  status varchar(16) NOT NULL CHECK(status IN ('OPEN','RESOLVED','REJECTED')),
  candidates jsonb NOT NULL CHECK(jsonb_typeof(candidates)='array'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(kind,object_gid),
  FOREIGN KEY(kind,object_gid) REFERENCES eatwhat_sync.catalog_object(kind,object_gid)
    DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE eatwhat_sync.catalog_head_conflict_audit (
  audit_id uuid PRIMARY KEY,
  conflict_id uuid NOT NULL REFERENCES eatwhat_sync.catalog_head_conflict(conflict_id),
  conflict_revision bigint NOT NULL CHECK(conflict_revision>0),
  expected_heads jsonb NOT NULL CHECK(jsonb_typeof(expected_heads)='array'),
  decision varchar(8) NOT NULL CHECK(decision IN ('KEEP','REJECT')),
  winner_operation_id uuid,
  actor_gid uuid NOT NULL,
  reason varchar(1000) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((decision='KEEP')=(winner_operation_id IS NOT NULL))
);

CREATE TABLE eatwhat_sync.catalog_head_resolution_receipt (
  conflict_id uuid NOT NULL REFERENCES eatwhat_sync.catalog_head_conflict(conflict_id),
  decision varchar(8) NOT NULL CHECK(decision IN ('KEEP','REJECT')),
  winner_operation_id uuid,
  source_site varchar(64) NOT NULL,
  proof jsonb NOT NULL CHECK(jsonb_typeof(proof)='object'),
  expected_heads jsonb NOT NULL CHECK(jsonb_typeof(expected_heads)='array'),
  proof_hash char(64) NOT NULL CHECK(proof_hash ~ '^[0-9a-f]{64}$'),
  conflict_revision bigint NOT NULL CHECK(conflict_revision>0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((decision='KEEP')=(winner_operation_id IS NOT NULL)),
  PRIMARY KEY(conflict_id,proof_hash)
);

CREATE TABLE eatwhat_sync.catalog_import_batch (
  batch_id uuid PRIMARY KEY,
  source_key varchar(128) NOT NULL,
  request_id uuid NOT NULL,
  content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  kind varchar(16) NOT NULL CHECK(kind IN ('HUB')),
  status varchar(16) NOT NULL CHECK(status IN ('APPLYING','APPLIED','REJECTED')),
  created_count integer NOT NULL DEFAULT 0 CHECK(created_count>=0),
  updated_count integer NOT NULL DEFAULT 0 CHECK(updated_count>=0),
  result jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(result)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  UNIQUE(source_key,request_id)
);

CREATE TABLE eatwhat_sync.catalog_hub_source (
  source_key varchar(128) NOT NULL,
  source_id varchar(256) NOT NULL,
  object_gid uuid NOT NULL,
  numeric_id bigint NOT NULL CHECK(numeric_id BETWEEN 1 AND 9007199254740991),
  content_hash char(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(source_key,source_id),
  UNIQUE(object_gid),
  UNIQUE(numeric_id)
);

-- Hub assignment is a source-computed immutable result; replicas never recompute ST_Covers.
CREATE TABLE eatwhat_sync.catalog_assignment (
  operation_id uuid PRIMARY KEY REFERENCES eatwhat_sync.catalog_revision(operation_id),
  restaurant_gid uuid NOT NULL,
  expected_revision bigint NOT NULL CHECK(expected_revision>=0),
  hub_gid uuid,
  source_site varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX catalog_assignment_restaurant ON eatwhat_sync.catalog_assignment(restaurant_gid,created_at DESC);

CREATE TABLE eatwhat_sync.catalog_backfill_job (
  subject_gid uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>=0),
  scope varchar(24) NOT NULL CHECK(scope IN ('AUTHOR','PARENT','OWNER','SYSTEM')),
  cursor_kind varchar(24),
  cursor_gid uuid,
  status varchar(16) NOT NULL CHECK(status IN ('READY','LEASED','WAIT','DONE')),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  last_error varchar(64),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(subject_gid,epoch,scope),
  CHECK((status='LEASED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX catalog_backfill_ready ON eatwhat_sync.catalog_backfill_job(available_at,updated_at)
  WHERE status IN ('READY','WAIT','LEASED');

-- One durable publication receipt per object, consent subject, and peer. This is a cursor
-- watermark, not business history: it lets a completed scan reopen only when current heads
-- or any currently relevant consent epoch have changed.
CREATE TABLE eatwhat_sync.catalog_backfill_publication (
  kind varchar(24) NOT NULL CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  object_gid uuid NOT NULL,
  subject_gid uuid NOT NULL,
  peer_site varchar(64) NOT NULL,
  consent_epoch bigint NOT NULL CHECK(consent_epoch>=0),
  object_revision bigint NOT NULL CHECK(object_revision>=0),
  published_heads jsonb NOT NULL CHECK(jsonb_typeof(published_heads)='array'),
  consent_epochs jsonb NOT NULL CHECK(jsonb_typeof(consent_epochs)='array'),
  bridge_event_id uuid NOT NULL,
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(kind,object_gid,subject_gid,peer_site)
);

CREATE TABLE eatwhat_sync.catalog_managed_domain (
  kind varchar(24) PRIMARY KEY CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  managed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE SEQUENCE eatwhat_sync.catalog_local_order_seq AS bigint;
CREATE FUNCTION eatwhat_sync.next_catalog_number() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE mode text; parity bigint;
BEGIN
  SELECT n.mode INTO mode FROM eatwhat_identity.node_state n WHERE n.singleton;
  IF mode IS NULL THEN RAISE EXCEPTION 'Explicit identity site required' USING ERRCODE='55000'; END IF;
  parity:=CASE WHEN mode='CAMPUS' THEN 1 ELSE 0 END;
  RETURN 2*nextval('eatwhat_sync.catalog_local_order_seq'::regclass)-parity;
END $$;
REVOKE ALL ON FUNCTION eatwhat_sync.next_catalog_number() FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.catalog_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'Catalog evidence is immutable' USING ERRCODE='55000';
END $$;
CREATE TRIGGER catalog_revision_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_revision FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_conflict_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_conflict_audit FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_resolution_receipt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_resolution_receipt FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_head_conflict_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_head_conflict_audit FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_head_resolution_receipt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_head_resolution_receipt FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
CREATE TRIGGER catalog_assignment_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_assignment FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();

REVOKE ALL ON eatwhat_sync.catalog_object,eatwhat_sync.catalog_revision,eatwhat_sync.catalog_natural_key,
  eatwhat_sync.catalog_candidate,eatwhat_sync.catalog_conflict,eatwhat_sync.catalog_conflict_audit,
  eatwhat_sync.catalog_resolution_receipt,
  eatwhat_sync.catalog_head_conflict,eatwhat_sync.catalog_head_conflict_audit,
  eatwhat_sync.catalog_head_resolution_receipt,
  eatwhat_sync.catalog_import_batch,eatwhat_sync.catalog_assignment,eatwhat_sync.catalog_backfill_job,
  eatwhat_sync.catalog_backfill_publication,
  eatwhat_sync.catalog_managed_domain,eatwhat_sync.catalog_hub_source FROM PUBLIC;
REVOKE ALL ON SEQUENCE eatwhat_sync.catalog_local_order_seq FROM PUBLIC;

-- Catalog projection imports keep the source numeric ID and GID. The runtime cannot write
-- import_context or entity_identity; these narrowly scoped SECURITY DEFINER functions bind the
-- trigger context to this transaction and let the existing identity trigger register the row.
CREATE OR REPLACE FUNCTION eatwhat_identity.guard_business_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,eatwhat_identity,eatwhat_consent AS $$
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
  IF TG_TABLE_NAME IN ('hub','restaurant','foods','food_tags','activity_foods','activity_dinners','likes','activity_likes','follow','food_favorites','food_taggings','notifications') AND EXISTS(
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

CREATE FUNCTION eatwhat_sync.apply_catalog_projection(
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
    IF p_origin=node.site_id OR mod(p_numeric,2)=local_parity THEN
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

CREATE FUNCTION eatwhat_sync.clear_catalog_projection(p_kind text,p_numeric bigint,p_gid uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE entity text; mapped uuid; present boolean;
BEGIN
  entity:=CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' WHEN 'SYSTEM_TAG' THEN 'food_tags' WHEN 'CUSTOM_TAG' THEN 'food_tags' ELSE NULL END;
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
    ELSE DELETE FROM public.food_tags WHERE id=p_numeric;
  END CASE;
END $$;

-- Managed public rows may only be changed by a catalog command or a verified inbound
-- projection. The transaction context is stored in a private table keyed by PostgreSQL's
-- transaction and backend identifiers; session GUCs cannot mint or replay it.
CREATE TABLE eatwhat_sync.catalog_business_write_context (
  transaction_id bigint NOT NULL,
  backend_id integer NOT NULL,
  kind varchar(24) NOT NULL,
  numeric_id bigint NOT NULL,
  actor_user_id bigint NOT NULL,
  author_user_id bigint,
  parent_numeric_id bigint,
  operation varchar(24) NOT NULL,
  revision_before bigint NOT NULL,
  expect_outbox boolean NOT NULL,
  imported boolean NOT NULL DEFAULT false,
  touched boolean NOT NULL DEFAULT false,
  PRIMARY KEY(transaction_id,backend_id,kind,numeric_id,operation)
);
CREATE TABLE eatwhat_sync.catalog_withdrawal_write_context (
  transaction_id bigint NOT NULL,
  backend_id integer NOT NULL,
  kind varchar(24) NOT NULL,
  numeric_id bigint NOT NULL,
  object_gid uuid NOT NULL,
  subject_gid uuid NOT NULL,
  revision_before bigint NOT NULL,
  PRIMARY KEY(transaction_id,backend_id,kind,numeric_id)
);
CREATE TABLE eatwhat_sync.catalog_business_write_pending (
  transaction_id bigint NOT NULL,
  backend_id integer NOT NULL,
  kind varchar(24) NOT NULL,
  numeric_id bigint NOT NULL,
  operation varchar(24) NOT NULL,
  revision_before bigint NOT NULL,
  expect_outbox boolean NOT NULL,
  imported boolean NOT NULL,
  withdrawal boolean NOT NULL DEFAULT false,
  PRIMARY KEY(transaction_id,backend_id,kind,numeric_id,operation)
);
REVOKE ALL ON eatwhat_sync.catalog_business_write_context,eatwhat_sync.catalog_withdrawal_write_context,
  eatwhat_sync.catalog_business_write_pending FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.catalog_entity_type(p_kind text) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
  SELECT CASE p_kind WHEN 'HUB' THEN 'hub' WHEN 'RESTAURANT' THEN 'restaurant'
    WHEN 'FOOD' THEN 'foods' ELSE 'food_tags' END
$$;

CREATE FUNCTION eatwhat_sync.begin_catalog_business_write(
  p_kind text,p_numeric bigint,p_actor_user_id bigint,p_author_user_id bigint,
  p_parent_numeric_id bigint,p_operation text,p_expect_outbox boolean
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_entity text; v_revision bigint; v_managed boolean; v_node text;
BEGIN
  IF p_kind NOT IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')
      OR p_operation NOT IN ('CREATE','UPDATE','DELETE','HUB_ASSIGNMENT')
      OR p_actor_user_id IS NULL OR p_expect_outbox IS NULL OR (p_numeric IS NOT NULL AND p_numeric<=0) THEN
    RAISE EXCEPTION 'Invalid catalog business write context' USING ERRCODE='23514';
  END IF;
  v_entity:=eatwhat_sync.catalog_entity_type(p_kind);
  SELECT mode INTO v_node FROM eatwhat_identity.node_state WHERE singleton;
  IF v_node IS NULL THEN RAISE EXCEPTION 'Catalog write requires explicit site identity' USING ERRCODE='55000'; END IF;
  IF p_kind IN ('HUB','SYSTEM_TAG') AND (v_node<>'CAMPUS' OR NOT EXISTS(
      SELECT 1 FROM public.users WHERE id=p_actor_user_id AND role='admin')) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF p_kind IN ('RESTAURANT','FOOD') AND p_operation IN ('UPDATE','DELETE','HUB_ASSIGNMENT')
      AND (v_node<>'CAMPUS' OR NOT EXISTS(SELECT 1 FROM public.users
        WHERE id=p_actor_user_id AND role='admin')) THEN
    RAISE EXCEPTION 'Catalog management requires a campus administrator' USING ERRCODE='42501';
  END IF;
  IF (p_kind IN ('RESTAURANT','FOOD') AND p_operation='CREATE'
      OR p_kind='CUSTOM_TAG') AND p_author_user_id IS DISTINCT FROM p_actor_user_id THEN
    RAISE EXCEPTION 'Catalog personal write actor mismatch' USING ERRCODE='42501';
  END IF;
  SELECT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_managed_domain WHERE kind=p_kind)
      OR (p_numeric IS NOT NULL AND EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object
        WHERE kind=p_kind AND numeric_id=p_numeric AND managed)) INTO v_managed;
  IF NOT v_managed THEN RETURN false; END IF;
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
REVOKE ALL ON FUNCTION eatwhat_sync.begin_catalog_business_write(text,bigint,bigint,bigint,bigint,text,boolean) FROM PUBLIC;

CREATE FUNCTION eatwhat_sync.catalog_public_write_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_kind text; v_id bigint; v_operation text; v_ctx eatwhat_sync.catalog_business_write_context%ROWTYPE;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE;
  v_imported boolean:=false; v_withdrawal boolean:=false; v_count bigint;
BEGIN
  v_kind:=CASE TG_TABLE_NAME WHEN 'hub' THEN 'HUB' WHEN 'restaurant' THEN 'RESTAURANT'
    WHEN 'foods' THEN 'FOOD' WHEN 'food_tags' THEN CASE
      WHEN COALESCE(to_jsonb(NEW)->>'tag_type',to_jsonb(OLD)->>'tag_type')='system' THEN 'SYSTEM_TAG' ELSE 'CUSTOM_TAG' END END;
  v_id:=COALESCE((to_jsonb(NEW)->>'id')::bigint,(to_jsonb(OLD)->>'id')::bigint);
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
      IF TG_OP<>'INSERT' OR (v_ctx.author_user_id IS NULL AND v_kind NOT IN ('HUB','SYSTEM_TAG')) THEN
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

CREATE FUNCTION eatwhat_sync.catalog_payload_matches_public(p_kind text,p_id bigint,p_payload jsonb) RETURNS boolean
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
      INTO v_expected FROM public.foods f WHERE f.id=p_id;
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

-- A directory withdrawal may erase an EXTERNAL public projection only after the immutable
-- head is known to exist on CAMPUS. Durable receive is insufficient: inbound proof must be
-- APPLIED, while locally-originated heads require an exact APPLIED/ALREADY_APPLIED delivery.
CREATE FUNCTION eatwhat_sync.catalog_head_archive_proven(
  p_kind text,p_gid uuid,p_authority_site text,p_head jsonb
) RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_operation uuid; v_hash text; v_local_site text;
BEGIN
  v_operation:=(p_head->>'operationId')::uuid;
  v_hash:=p_head->>'contentHash';
  SELECT site_id INTO v_local_site FROM eatwhat_identity.node_state WHERE singleton;
  IF v_operation IS NULL OR v_hash IS NULL OR p_authority_site IS NULL OR v_local_site IS NULL THEN
    RETURN false;
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
      CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
        THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
      WHERE i.aggregate_type='catalog.operation' AND i.origin_site=p_authority_site AND i.status='APPLIED'
        AND ((i.envelope->>'eventType'='catalog.action' AND e.payload->>'kind'=p_kind
              AND e.payload->>'objectGid'=p_gid::text AND e.payload->>'operationId'=v_operation::text
              AND e.payload->>'contentHash'=v_hash)
          OR (i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=p_kind
              AND e.payload->>'objectGid'=p_gid::text AND EXISTS(
                SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                WHERE h->>'operationId'=v_operation::text AND h->>'contentHash'=v_hash)))) THEN
    RETURN true;
  END IF;
  IF EXISTS(SELECT 1 FROM eatwhat_sync.outbox o JOIN eatwhat_sync.delivery d ON d.event_id=o.event_id
      CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(o.envelope->'payloadJson')='string'
        THEN (o.envelope->>'payloadJson')::jsonb ELSE o.envelope->'payloadJson' END AS payload) e
      WHERE d.target_site=p_authority_site AND d.status='ACKED'
        AND d.receipt_status IN ('APPLIED','ALREADY_APPLIED')
        AND o.origin_site=v_local_site AND o.aggregate_type='catalog.operation'
        AND ((o.envelope->>'eventType'='catalog.action' AND o.event_id=v_operation
              AND e.payload->>'kind'=p_kind AND e.payload->>'objectGid'=p_gid::text
              AND e.payload->>'operationId'=v_operation::text AND e.payload->>'contentHash'=v_hash)
          OR (o.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=p_kind
              AND e.payload->>'objectGid'=p_gid::text AND EXISTS(
                SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
                WHERE h->>'operationId'=v_operation::text AND h->>'contentHash'=v_hash)))) THEN
    RETURN true;
  END IF;
  -- A later CAMPUS state bridge is explicit proof that the original immutable head remains archived.
  RETURN EXISTS(SELECT 1 FROM eatwhat_sync.inbox i
      CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(i.envelope->'payloadJson')='string'
        THEN (i.envelope->>'payloadJson')::jsonb ELSE i.envelope->'payloadJson' END AS payload) e
      WHERE i.aggregate_type='catalog.operation' AND i.origin_site=p_authority_site AND i.status='APPLIED'
        AND i.envelope->>'eventType'='catalog.state' AND e.payload->>'kind'=p_kind
        AND e.payload->>'objectGid'=p_gid::text AND EXISTS(
          SELECT 1 FROM jsonb_array_elements(e.payload->'heads') h
          WHERE h->>'operationId'=v_operation::text AND h->>'contentHash'=v_hash));
END $$;

-- Called inside ConsentStore.clearLocked, before the account purge and in its transaction.
-- It leaves every causal record intact and only removes verified EXTERNAL public projections.
CREATE FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup(p_subject uuid,p_user_id bigint)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_mode text; v_subject eatwhat_consent.subject%ROWTYPE; v_user_gid uuid; rec record;
  v_heads jsonb; v_head jsonb; v_entity text;
BEGIN
  SELECT mode INTO v_mode FROM eatwhat_identity.node_state WHERE singleton;
  IF v_mode IS DISTINCT FROM 'EXTERNAL' THEN RETURN false; END IF;
  SELECT * INTO v_subject FROM eatwhat_consent.subject
    WHERE subject_id=p_subject AND user_id=p_user_id FOR UPDATE;
  IF NOT FOUND OR v_subject.status<>'WITHDRAWN' THEN RETURN false; END IF;
  SELECT global_id INTO v_user_gid FROM eatwhat_identity.entity_identity
    WHERE entity_type='users' AND numeric_id=p_user_id;
  IF v_user_gid IS DISTINCT FROM p_subject THEN RETURN false; END IF;

  -- Untracked or identity-ambiguous business rows are never cascaded away during account cleanup.
  IF EXISTS(SELECT 1 FROM public.restaurant r WHERE r.account_id=p_user_id
      AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ri JOIN eatwhat_sync.catalog_object o
        ON o.kind='RESTAURANT' AND o.object_gid=ri.global_id AND o.numeric_id=r.id AND o.managed
        WHERE ri.entity_type='restaurant' AND ri.numeric_id=r.id))
    OR EXISTS(SELECT 1 FROM public.foods f WHERE (f.account_id=p_user_id OR EXISTS(
        SELECT 1 FROM public.restaurant p WHERE p.id=f.restaurant_id AND p.account_id=p_user_id))
      AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity fi JOIN eatwhat_sync.catalog_object o
        ON o.kind='FOOD' AND o.object_gid=fi.global_id AND o.numeric_id=f.id AND o.managed
        WHERE fi.entity_type='foods' AND fi.numeric_id=f.id))
    OR EXISTS(SELECT 1 FROM public.food_tags t WHERE t.owner_id=p_user_id AND lower(t.tag_type)='custom'
      AND NOT EXISTS(SELECT 1 FROM eatwhat_identity.entity_identity ti JOIN eatwhat_sync.catalog_object o
        ON o.kind='CUSTOM_TAG' AND o.object_gid=ti.global_id AND o.numeric_id=t.id AND o.managed
        WHERE ti.entity_type='food_tags' AND ti.numeric_id=t.id)) THEN
    RETURN false;
  END IF;

  FOR rec IN
    SELECT DISTINCT o.kind,o.numeric_id,o.object_gid,o.author_gid,o.parent_gid,o.revision,o.causal_state,o.authority_site
    FROM eatwhat_sync.catalog_object o
    WHERE o.managed AND (
      (o.kind='RESTAURANT' AND o.author_gid=p_subject AND EXISTS(SELECT 1 FROM public.restaurant r WHERE r.id=o.numeric_id))
      OR (o.kind='FOOD' AND EXISTS(SELECT 1 FROM public.foods f WHERE f.id=o.numeric_id)
          AND (o.author_gid=p_subject OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object p
            WHERE p.kind='RESTAURANT' AND p.object_gid=o.parent_gid AND p.author_gid=p_subject)))
      OR (o.kind='CUSTOM_TAG' AND o.author_gid=p_subject AND EXISTS(SELECT 1 FROM public.food_tags t
          WHERE t.id=o.numeric_id AND lower(t.tag_type)='custom')))
    ORDER BY o.kind,o.object_gid
  LOOP
    v_heads:=rec.causal_state->'heads';
    IF v_heads IS NULL OR jsonb_typeof(v_heads)<>'array' OR jsonb_array_length(v_heads)=0 THEN RETURN false; END IF;
    FOR v_head IN SELECT value FROM jsonb_array_elements(v_heads) LOOP
      IF NOT eatwhat_sync.catalog_head_archive_proven(rec.kind,rec.object_gid,rec.authority_site,v_head) THEN
        RETURN false;
      END IF;
    END LOOP;
  END LOOP;

  -- Pre-register each exact DML target, including FK children, before deleting any parent.
  INSERT INTO eatwhat_sync.catalog_withdrawal_write_context(transaction_id,backend_id,kind,numeric_id,
      object_gid,subject_gid,revision_before)
    SELECT txid_current(),pg_backend_pid(),o.kind,o.numeric_id,o.object_gid,p_subject,o.revision
    FROM eatwhat_sync.catalog_object o JOIN public.foods f ON f.id=o.numeric_id
    WHERE o.kind='FOOD' AND o.managed
      AND (o.author_gid=p_subject OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object p
        WHERE p.kind='RESTAURANT' AND p.object_gid=o.parent_gid AND p.author_gid=p_subject))
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id) DO NOTHING;
  INSERT INTO eatwhat_sync.catalog_withdrawal_write_context(transaction_id,backend_id,kind,numeric_id,
      object_gid,subject_gid,revision_before)
    SELECT txid_current(),pg_backend_pid(),o.kind,o.numeric_id,o.object_gid,p_subject,o.revision
    FROM eatwhat_sync.catalog_object o JOIN public.restaurant r ON r.id=o.numeric_id
    WHERE o.kind='RESTAURANT' AND o.managed AND o.author_gid=p_subject
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id) DO NOTHING;
  INSERT INTO eatwhat_sync.catalog_withdrawal_write_context(transaction_id,backend_id,kind,numeric_id,
      object_gid,subject_gid,revision_before)
    SELECT txid_current(),pg_backend_pid(),o.kind,o.numeric_id,o.object_gid,p_subject,o.revision
    FROM eatwhat_sync.catalog_object o JOIN public.food_tags t ON t.id=o.numeric_id
    WHERE o.kind='CUSTOM_TAG' AND o.managed AND o.author_gid=p_subject AND lower(t.tag_type)='custom'
    ON CONFLICT(transaction_id,backend_id,kind,numeric_id) DO NOTHING;

  DELETE FROM public.foods f USING eatwhat_sync.catalog_object o
    WHERE o.kind='FOOD' AND o.numeric_id=f.id AND o.managed
      AND (o.author_gid=p_subject OR EXISTS(SELECT 1 FROM eatwhat_sync.catalog_object p
        WHERE p.kind='RESTAURANT' AND p.object_gid=o.parent_gid AND p.author_gid=p_subject));
  DELETE FROM public.restaurant r USING eatwhat_sync.catalog_object o
    WHERE o.kind='RESTAURANT' AND o.numeric_id=r.id AND o.managed AND o.author_gid=p_subject;
  DELETE FROM public.food_tags t USING eatwhat_sync.catalog_object o
    WHERE o.kind='CUSTOM_TAG' AND o.numeric_id=t.id AND o.managed AND o.author_gid=p_subject
      AND lower(t.tag_type)='custom';
  RETURN true;
END $$;

CREATE FUNCTION eatwhat_sync.catalog_business_write_check() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_head eatwhat_sync.catalog_revision%ROWTYPE; v_delete_head eatwhat_sync.catalog_revision%ROWTYPE;
  v_gid uuid; v_kind text; v_event boolean;
  v_withdrawal_ctx eatwhat_sync.catalog_withdrawal_write_context%ROWTYPE; v_present boolean;
BEGIN
  v_kind:=NEW.kind;
  SELECT global_id INTO v_gid FROM eatwhat_identity.entity_identity
    WHERE entity_type=eatwhat_sync.catalog_entity_type(v_kind) AND numeric_id=NEW.numeric_id;
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

CREATE CONSTRAINT TRIGGER catalog_business_write_deferred
  AFTER INSERT ON eatwhat_sync.catalog_business_write_pending DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_business_write_check();

CREATE FUNCTION eatwhat_sync.catalog_business_context_cleanup() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  DELETE FROM eatwhat_sync.catalog_business_write_context c
    WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id
      AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id AND c.operation=NEW.operation
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_business_write_pending p
        WHERE p.transaction_id=c.transaction_id AND p.backend_id=c.backend_id
          AND p.kind=c.kind AND p.numeric_id=c.numeric_id);
  RETURN NULL;
END $$;

CREATE FUNCTION eatwhat_sync.catalog_withdrawal_context_check() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
  -- The context INSERT trigger may be queued before the DELETE guard's pending-row
  -- trigger. A matching private pending row delegates final consumption to the
  -- deferred catalog_business_write_check, which revalidates and deletes both rows.
  -- A context without that exact pending operation remains an error.
  IF EXISTS(SELECT 1 FROM eatwhat_sync.catalog_withdrawal_write_context c
      WHERE c.transaction_id=NEW.transaction_id AND c.backend_id=NEW.backend_id
        AND c.kind=NEW.kind AND c.numeric_id=NEW.numeric_id)
      AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.catalog_business_write_pending p
        WHERE p.transaction_id=NEW.transaction_id AND p.backend_id=NEW.backend_id
          AND p.kind=NEW.kind AND p.numeric_id=NEW.numeric_id
          AND p.operation='DELETE' AND p.withdrawal) THEN
    RAISE EXCEPTION 'Unconsumed catalog withdrawal context' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER catalog_business_context_cleanup_deferred
  AFTER INSERT ON eatwhat_sync.catalog_business_write_context DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_business_context_cleanup();
CREATE CONSTRAINT TRIGGER catalog_withdrawal_context_check_deferred
  AFTER INSERT ON eatwhat_sync.catalog_withdrawal_write_context DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_withdrawal_context_check();

CREATE TRIGGER catalog_public_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.hub
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_public_write_guard();
CREATE TRIGGER catalog_public_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.restaurant
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_public_write_guard();
CREATE TRIGGER catalog_public_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.foods
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_public_write_guard();
CREATE TRIGGER catalog_public_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.food_tags
  FOR EACH ROW EXECUTE FUNCTION eatwhat_sync.catalog_public_write_guard();

REVOKE ALL ON FUNCTION eatwhat_sync.apply_catalog_projection(text,uuid,bigint,text,uuid,uuid,text,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.clear_catalog_projection(text,bigint,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_public_write_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_business_write_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_business_context_cleanup() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_withdrawal_context_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_payload_matches_public(text,bigint,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_head_archive_proven(text,uuid,text,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_sync.prepare_catalog_withdrawal_cleanup(uuid,bigint) FROM PUBLIC;
