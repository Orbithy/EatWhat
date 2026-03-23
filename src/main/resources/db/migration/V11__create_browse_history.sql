CREATE TABLE browse_history (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type   varchar(32) NOT NULL CHECK (target_type IN ('restaurant', 'food')),
  target_id     bigint      NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_browse_history_list
  ON browse_history (account_id, target_type, created_at DESC, id DESC);

CREATE INDEX idx_browse_history_dedup
  ON browse_history (account_id, target_type, target_id, created_at DESC);
