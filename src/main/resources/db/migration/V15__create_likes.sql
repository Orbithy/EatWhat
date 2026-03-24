CREATE TABLE likes (
  id          bigserial PRIMARY KEY,
  account_id  bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type varchar(32) NOT NULL CHECK(target_type IN ('food')),
  target_id   bigint      NOT NULL,
  deleted_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id, target_type, target_id)
);

CREATE INDEX idx_likes_target
  ON likes (target_type, target_id);

CREATE INDEX idx_likes_account
  ON likes (account_id, created_at DESC);

CREATE TRIGGER update_likes_updated_at BEFORE UPDATE ON likes
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE OR REPLACE FUNCTION update_foods_likes_count()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' AND NEW.deleted_at IS NULL THEN
    IF NEW.target_type = 'food' THEN
      UPDATE foods SET likes_count = likes_count + 1 WHERE id = NEW.target_id;
    END IF;
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      IF NEW.target_type = 'food' THEN
        UPDATE foods SET likes_count = GREATEST(likes_count - 1, 0) WHERE id = NEW.target_id;
      END IF;
    ELSIF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
      IF NEW.target_type = 'food' THEN
        UPDATE foods SET likes_count = likes_count + 1 WHERE id = NEW.target_id;
      END IF;
    END IF;
  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.deleted_at IS NULL AND OLD.target_type = 'food' THEN
      UPDATE foods SET likes_count = GREATEST(likes_count - 1, 0) WHERE id = OLD.target_id;
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_foods_likes_count_trigger
  AFTER INSERT OR UPDATE OR DELETE ON likes
  FOR EACH ROW EXECUTE FUNCTION update_foods_likes_count();
