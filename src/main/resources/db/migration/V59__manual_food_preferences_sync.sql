SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Content stays in the existing account-owned projection, which already cascades on withdrawal.
-- This content-free winner/tombstone survives a clear or account purge so delayed events cannot win.
CREATE TABLE eatwhat_sync.preference_state (
  subject_gid uuid PRIMARY KEY,
  epoch bigint NOT NULL CHECK(epoch>=0),
  logical_clock bigint NOT NULL CHECK(logical_clock BETWEEN 1 AND 9007199254740991),
  winner_id uuid NOT NULL,
  winner_site varchar(64) NOT NULL,
  value_hash char(64) NOT NULL,
  observed_at timestamptz NOT NULL,
  is_empty boolean NOT NULL,
  local_dirty boolean NOT NULL,
  published_epoch bigint NOT NULL DEFAULT 0 CHECK(published_epoch>=0)
);
