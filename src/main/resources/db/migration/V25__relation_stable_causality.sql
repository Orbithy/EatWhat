SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- Relation V1 vectors are scoped to a consent-epoch relation id.  These tables hold
-- epoch-independent causal state while leaving the V1 fact and payload tables intact.
CREATE TABLE eatwhat_sync.relation_causal_state (
  natural_id uuid PRIMARY KEY,
  kind varchar(32) NOT NULL CHECK(kind IN ('food_like','activity_food_like','activity_dinner_like','favorite','follow','tagging')),
  actor_gid uuid NOT NULL,
  target_gid uuid NOT NULL,
  owner_gid uuid NOT NULL,
  tag_gid uuid,
  actor_epoch bigint NOT NULL CHECK(actor_epoch>=0),
  owner_epoch bigint NOT NULL CHECK(owner_epoch>=0),
  row_id bigint NOT NULL CHECK(row_id BETWEEN 1 AND 9007199254740991),
  row_gid uuid NOT NULL,
  row_origin varchar(64),
  seen jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(seen)='object'),
  add_witnesses jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(add_witnesses)='array'),
  remove_frontier jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(remove_frontier)='array'),
  legacy_loaded boolean NOT NULL DEFAULT false,
  blocked_reason varchar(64),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((kind='tagging')=(tag_gid IS NOT NULL)),
  UNIQUE(kind,actor_gid,target_gid,owner_gid,tag_gid)
);

-- Immutable logical operations are keyed by their stable event identity.  The compact
-- state above stores only each stream's newest ADD witness and greatest REMOVE dot.
CREATE TABLE eatwhat_sync.relation_causal_fact (
  event_id uuid PRIMARY KEY,
  natural_id uuid NOT NULL REFERENCES eatwhat_sync.relation_causal_state(natural_id),
  stream_key varchar(192) NOT NULL,
  dot_counter bigint NOT NULL CHECK(dot_counter BETWEEN 1 AND 9007199254740991),
  context jsonb NOT NULL CHECK(jsonb_typeof(context)='object'),
  adding boolean NOT NULL,
  payload jsonb NOT NULL,
  legacy boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(natural_id,stream_key,dot_counter)
);
CREATE INDEX relation_causal_fact_natural ON eatwhat_sync.relation_causal_fact(natural_id,stream_key,dot_counter);
CREATE TRIGGER relation_causal_fact_immutable
  BEFORE UPDATE OR DELETE OR TRUNCATE ON eatwhat_sync.relation_causal_fact
  FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.relation_immutable();
REVOKE ALL ON eatwhat_sync.relation_causal_state, eatwhat_sync.relation_causal_fact FROM PUBLIC;

