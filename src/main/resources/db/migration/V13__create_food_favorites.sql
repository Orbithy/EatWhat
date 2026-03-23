CREATE TABLE food_favorites (
  id         bigserial PRIMARY KEY,
  account_id bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  food_id     bigint     NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (account_id, food_id)
);

CREATE INDEX idx_food_favorites_account_created
  ON food_favorites (account_id, created_at DESC, id DESC);

CREATE INDEX idx_food_favorites_food
  ON food_favorites (food_id);
