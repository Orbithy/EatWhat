-- ============================================
-- V3: 创建活动相关表（菜品、年夜饭、点赞、通知、关注、隐私）
-- ============================================

-- ============================================
-- ActivityFoods 表（菜品分享）
-- ============================================
CREATE TABLE activity_foods (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  food_name     varchar(64) NOT NULL,
  description   text,
  province_id   int         REFERENCES provinces(id),
  city_id       int         REFERENCES cities(id),
  picture_url   text[],
  likes_count   int         NOT NULL DEFAULT 0,
  deleted_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 索引
CREATE INDEX idx_activity_foods_account ON activity_foods(account_id);
CREATE INDEX idx_activity_foods_created ON activity_foods(created_at DESC);
CREATE INDEX idx_activity_foods_location ON activity_foods(province_id, city_id);
CREATE INDEX idx_activity_foods_active ON activity_foods(deleted_at) WHERE deleted_at IS NULL;

-- 触发器
CREATE TRIGGER update_activity_foods_updated_at BEFORE UPDATE ON activity_foods
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- ActivityDinners 表（年夜饭分享）
-- ============================================
CREATE TABLE activity_dinners (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  picture_url   text[],
  description   text,
  likes_count   int         NOT NULL DEFAULT 0,
  deleted_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 索引
CREATE INDEX idx_activity_dinners_account ON activity_dinners(account_id);
CREATE INDEX idx_activity_dinners_created ON activity_dinners(created_at DESC);
CREATE INDEX idx_activity_dinners_active ON activity_dinners(deleted_at) WHERE deleted_at IS NULL;

-- 触发器
CREATE TRIGGER update_activity_dinners_updated_at BEFORE UPDATE ON activity_dinners
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- ActivityLikes 表（点赞）
-- ============================================
CREATE TABLE activity_likes (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type   varchar(32) NOT NULL CHECK(target_type IN ('food', 'dinner')),
  activity_id   bigint      NOT NULL,
  deleted_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id, target_type, activity_id)
);

CREATE INDEX idx_activity_likes_target ON activity_likes(target_type, activity_id);
CREATE INDEX idx_activity_likes_account ON activity_likes(account_id, created_at DESC);

-- 触发器
CREATE TRIGGER update_activity_likes_updated_at BEFORE UPDATE ON activity_likes
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- Notifications 表（通知）
-- ============================================
CREATE TABLE notifications (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  actor_id      bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type          varchar(32) NOT NULL CHECK(type IN ('like', 'comment', 'follow', 'system')),
  target_type   varchar(32) NOT NULL CHECK(target_type IN ('food', 'dinner', 'user')),
  target_id     bigint      NOT NULL,
  data          jsonb,
  read_at       timestamptz,
  expires_at    timestamptz NOT NULL DEFAULT (now() + interval '30 days'),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- 索引（性能关键）
CREATE INDEX idx_notifications_unread ON notifications(account_id, read_at) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_account_created ON notifications(account_id, created_at DESC);
CREATE INDEX idx_notifications_target ON notifications(target_type, target_id);
CREATE INDEX idx_notifications_expires ON notifications(expires_at) WHERE read_at IS NOT NULL;

-- ============================================
-- Follow 表（关注）
-- ============================================
CREATE TABLE follow (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_id     bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id, target_id)
);

-- 索引
CREATE INDEX idx_follow_target ON follow(target_id, created_at DESC);

-- ============================================
-- Privacy 表（隐私设置）
-- ============================================
CREATE TABLE privacy (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  following     boolean     NOT NULL DEFAULT true,
  follower      boolean     NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id)
);

-- 索引

-- ============================================
-- 触发器：自动维护点赞数缓存
-- ============================================
CREATE OR REPLACE FUNCTION update_likes_count()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' AND NEW.deleted_at IS NULL THEN
    -- 新增点赞
    IF NEW.target_type = 'food' THEN
      UPDATE activity_foods SET likes_count = likes_count + 1 WHERE id = NEW.activity_id;
    ELSIF NEW.target_type = 'dinner' THEN
      UPDATE activity_dinners SET likes_count = likes_count + 1 WHERE id = NEW.activity_id;
    END IF;
  ELSIF TG_OP = 'UPDATE' THEN
    -- 取消点赞（软删除）
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL THEN
      IF NEW.target_type = 'food' THEN
        UPDATE activity_foods SET likes_count = likes_count - 1 WHERE id = NEW.activity_id;
      ELSIF NEW.target_type = 'dinner' THEN
        UPDATE activity_dinners SET likes_count = likes_count - 1 WHERE id = NEW.activity_id;
      END IF;
    -- 恢复点赞
    ELSIF OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
      IF NEW.target_type = 'food' THEN
        UPDATE activity_foods SET likes_count = likes_count + 1 WHERE id = NEW.activity_id;
      ELSIF NEW.target_type = 'dinner' THEN
        UPDATE activity_dinners SET likes_count = likes_count + 1 WHERE id = NEW.activity_id;
      END IF;
    END IF;
  ELSIF TG_OP = 'DELETE' THEN
    -- 硬删除点赞
    IF OLD.deleted_at IS NULL THEN
      IF OLD.target_type = 'food' THEN
        UPDATE activity_foods SET likes_count = likes_count - 1 WHERE id = OLD.activity_id;
      ELSIF OLD.target_type = 'dinner' THEN
        UPDATE activity_dinners SET likes_count = likes_count - 1 WHERE id = OLD.activity_id;
      END IF;
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  ELSE
    RETURN NEW;
  END IF;
END;
$$ LANGUAGE plpgsql;

-- 绑定触发器到 activity_likes 表
CREATE TRIGGER update_likes_count_trigger
  AFTER INSERT OR UPDATE OR DELETE ON activity_likes
  FOR EACH ROW EXECUTE FUNCTION update_likes_count();
