-- V12: 创建菜品标签定义与用户打标关系表

CREATE TABLE food_tags (
  id              bigserial PRIMARY KEY,
  name            varchar(64) NOT NULL,
  tag_type        varchar(16) NOT NULL CHECK (tag_type IN ('system', 'custom')),
  owner_id        bigint REFERENCES users(id) ON DELETE CASCADE,
  normalized_name varchar(64) NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (
    (tag_type = 'system' AND owner_id IS NULL)
    OR
    (tag_type = 'custom' AND owner_id IS NOT NULL)
  )
);

CREATE UNIQUE INDEX uk_food_tags_system_name
  ON food_tags (normalized_name)
  WHERE tag_type = 'system';

CREATE UNIQUE INDEX uk_food_tags_custom_owner_name
  ON food_tags (owner_id, normalized_name)
  WHERE tag_type = 'custom';

CREATE INDEX idx_food_tags_type_created
  ON food_tags (tag_type, created_at DESC);

CREATE TRIGGER update_food_tags_updated_at BEFORE UPDATE ON food_tags
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE food_taggings (
  id         bigserial PRIMARY KEY,
  food_id     bigint NOT NULL REFERENCES foods(id) ON DELETE CASCADE,
  tag_id      bigint NOT NULL REFERENCES food_tags(id) ON DELETE CASCADE,
  account_id  bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(food_id, tag_id, account_id)
);

CREATE INDEX idx_food_taggings_food
  ON food_taggings (food_id, created_at DESC);

CREATE INDEX idx_food_taggings_tag
  ON food_taggings (tag_id, created_at DESC);

CREATE INDEX idx_food_taggings_account
  ON food_taggings (account_id, created_at DESC);
