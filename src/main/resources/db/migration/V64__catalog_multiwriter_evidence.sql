SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- A migration-owner gate, never a writable application session setting.
CREATE TABLE eatwhat_sync.catalog_multiwriter_config (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  enabled boolean NOT NULL DEFAULT false
);
INSERT INTO eatwhat_sync.catalog_multiwriter_config(singleton,enabled) VALUES(true,false);
ALTER TABLE eatwhat_sync.catalog_head_conflict DROP CONSTRAINT catalog_head_conflict_kind_check;
ALTER TABLE eatwhat_sync.catalog_head_conflict ADD CONSTRAINT catalog_head_conflict_kind_check
  CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG'));

-- Hiding a contested projection must not cascade deletion of its children, relations or files.
CREATE TABLE eatwhat_sync.catalog_projection_state (
  kind varchar(24) NOT NULL CHECK(kind IN ('HUB','RESTAURANT','FOOD','SYSTEM_TAG','CUSTOM_TAG')),
  object_gid uuid NOT NULL,
  status varchar(24) NOT NULL CHECK(status IN ('VISIBLE','CONFLICT','SUPPRESSED','AUTH_REVIEW')),
  decision_heads jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(decision_heads)='array'),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(kind,object_gid),
  FOREIGN KEY(kind,object_gid) REFERENCES eatwhat_sync.catalog_object(kind,object_gid) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE eatwhat_sync.catalog_actor_evidence (
  operation_id uuid PRIMARY KEY REFERENCES eatwhat_sync.catalog_revision(operation_id) DEFERRABLE INITIALLY DEFERRED,
  actor_gid uuid NOT NULL,
  evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
  evidence_hash char(64) NOT NULL CHECK(evidence_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER catalog_actor_evidence_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_actor_evidence FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();

-- Exposes only the actor already authenticated by the current transaction's guarded command.
CREATE FUNCTION eatwhat_sync.catalog_command_actor(p_kind text,p_numeric bigint) RETURNS bigint
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog STABLE AS $$
  SELECT c.actor_user_id FROM eatwhat_sync.catalog_business_write_context c
  WHERE c.transaction_id=txid_current() AND c.backend_id=pg_backend_pid()
    AND c.kind=p_kind AND c.numeric_id IN (0,p_numeric)
  ORDER BY (c.numeric_id=p_numeric) DESC LIMIT 1
$$;
REVOKE ALL ON FUNCTION eatwhat_sync.catalog_command_actor(text,bigint) FROM PUBLIC;

CREATE TABLE eatwhat_sync.catalog_authorization_review (
  event_id uuid PRIMARY KEY,
  object_gid uuid NOT NULL,
  kind varchar(24) NOT NULL,
  envelope jsonb NOT NULL CHECK(jsonb_typeof(envelope)='object'),
  reason varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER catalog_authorization_review_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
  ON eatwhat_sync.catalog_authorization_review FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.catalog_immutable();
