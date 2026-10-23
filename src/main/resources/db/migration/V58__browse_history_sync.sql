SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- History row IDs remain local implementation details (the API returns target/time only).
-- visit_id identifies the immutable observation across replicas, independently of local allocation.
-- Nullable columns keep this additive migration compatible with the previous business binary.
ALTER TABLE public.browse_history
  ADD COLUMN sync_visit_id uuid,
  ADD COLUMN sync_origin_site varchar(64),
  ADD COLUMN sync_actor_epoch bigint NOT NULL DEFAULT 0,
  ADD COLUMN sync_owner_epoch bigint NOT NULL DEFAULT 0,
  ADD COLUMN sync_attempted_at timestamptz;

-- Indexes are built on a new empty mapping table, never on the existing history table.
-- The existing history row is removed through the normal account cascade; its mapping follows it.
CREATE TABLE eatwhat_sync.browse_visit (
  visit_id uuid PRIMARY KEY,
  history_id bigint NOT NULL UNIQUE REFERENCES public.browse_history(id) ON DELETE CASCADE,
  origin_site varchar(64) NOT NULL
);

COMMENT ON COLUMN public.browse_history.sync_visit_id IS
  'Immutable cross-site observation ID; receiving a visit never changes its original created_at';
