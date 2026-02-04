# 数据库设计

本设计基于 PostgreSQL，邮箱字段使用 `citext` 实现大小写不敏感唯一，认证分为账号（users）、联系方式（contacts）、验证（verification）三层。
需要超级用户在数据库执行 安装插件
```postgresql
CREATE EXTENSION IF NOT EXISTS citext;
```
注意：Flyway 只负责在已存在的数据库中执行迁移脚本，不会自动创建数据库本身。
请先手动创建数据库并赋权给应用账号，例如：
```postgresql
CREATE DATABASE eatwhat;
CREATE USER eatwhat_user WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE eatwhat TO eatwhat_user;
```

## users

| 字段            | 类型                                 | 含义     |
|---------------|------------------------------------|--------|
| id            | bigserial PRIMARY KEY              | 用户ID   |
| nick_name     | varchar(32) NOT NULL UNIQUE        | 用户名    |
| password_hash | text NOT NULL                      | 密码哈希   |
| avatar        | text                               | 头像 URL |
| created_at    | timestamptz NOT NULL DEFAULT now() | 创建时间   |
| updated_at    | timestamptz NOT NULL DEFAULT now() | 更新时间   |

## Contact

| 字段         | 类型                                      | 含义   |
|------------|-----------------------------------------|------|
| id         | bigserial PRIMARY KEY                   | 记录ID |
| account_id | bigint NOT NULL REFERENCES users(id) | 账号ID |
| email      | citext UNIQUE                           | 邮箱   |
| phone      | varchar(32) UNIQUE                      | 手机号  |
| created_at | timestamptz NOT NULL DEFAULT now()      | 创建时间 |
| updated_at | timestamptz NOT NULL DEFAULT now()      | 更新时间 |

## Verifications

| 字段             | 类型                                   | 含义                      |
|----------------|--------------------------------------|-------------------------|
| id             | bigserial PRIMARY KEY                | 记录ID                    |
| account_id     | bigint NOT NULL REFERENCES users(id) | 账号ID                    |
| method         | varchar(16) NOT NULL                 | 认证方式：sso / school_email |
| verified       | boolean NOT NULL DEFAULT false       | 是否已验证                   |
| student_id     | varchar(32)                          | 学号（统一身份认证）              |
| real_name      | varchar(64)                          | 姓名（统一身份认证）              |
| verified_email | citext                               | 认证邮箱（学校邮箱）              |
| created_at     | timestamptz NOT NULL DEFAULT now()   | 创建时间                    |
| updated_at     | timestamptz NOT NULL DEFAULT now()   | 更新时间                    |

## Provinces（省份表）

| 字段         | 类型                                 | 含义   |
|------------|------------------------------------|------|
| id         | serial PRIMARY KEY                 | 省份ID |
| name       | varchar(64) NOT NULL UNIQUE        | 省份名称 |
| created_at | timestamptz NOT NULL DEFAULT now() | 创建时间 |

## Cities（城市表）

| 字段          | 类型                                    | 含义   |
|-------------|---------------------------------------|------|
| id          | serial PRIMARY KEY                    | 城市ID |
| province_id | int NOT NULL REFERENCES provinces(id) | 省份ID |
| name        | varchar(64) NOT NULL                  | 城市名称 |
| created_at  | timestamptz NOT NULL DEFAULT now()    | 创建时间 |

**约束：**
- `UNIQUE(province_id, name)` - 同一省份下城市名称唯一

**索引：**
- `idx_cities_province` ON (province_id) - 查询某省份的所有城市

## UserInfo（用户信息表）

| 字段                   | 类型                                                        | 含义             |
|----------------------|-----------------------------------------------------------|----------------|
| id                   | bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE | 用户ID（关联users表） |
| gender               | varchar(16) CHECK (gender IN ('male', 'female', 'other')) | 性别             |
| birthday             | date                                                      | 生日             |
| signature            | varchar(255)                                              | 个性签名           |
| location_province_id | int REFERENCES provinces(id)                              | IP所在省份         |
| hometown_province_id | int REFERENCES provinces(id)                              | 家乡省份           |
| hometown_city_id     | int REFERENCES cities(id)                                 | 家乡城市           |
| created_at           | timestamptz NOT NULL DEFAULT now()                        | 创建时间           |
| updated_at           | timestamptz NOT NULL DEFAULT now()                        | 更新时间           |

**索引：**
- `idx_userinfo_hometown` ON (hometown_province_id, hometown_city_id) - 按家乡查询用户
- `idx_userinfo_location` ON (location_province_id, location_city_id) - 按地区查询用户

## ActivityFoods

| 字段          | 类型                                   | 含义       |
|-------------|--------------------------------------|----------|
| id          | bigserial PRIMARY KEY                | 菜品ID     |
| account_id  | bigint NOT NULL REFERENCES users(id) | 账号ID     |
| food_name   | varchar(64) NOT NULL                 | 菜品名称     |
| description | text                                 | 菜品介绍     |
| province_id | int REFERENCES provinces(id)         | 省份ID     |
| city_id     | int REFERENCES cities(id)            | 城市ID     |
| picture_url | text[]                               | 菜品图片 URL |
| likes_count | int NOT NULL DEFAULT 0               | 点赞数缓存    |
| deleted_at  | timestamptz                          | 软删除时间    |
| created_at  | timestamptz NOT NULL DEFAULT now()   | 创建时间     |
| updated_at  | timestamptz NOT NULL DEFAULT now()   | 更新时间     |

**索引：**
- `idx_activity_foods_account` ON (account_id) - 查询用户的菜品
- `idx_activity_foods_created` ON (created_at DESC) - 按时间排序
- `idx_activity_foods_location` ON (province_id, city_id) - 按地区查询
- `idx_activity_foods_active` ON (deleted_at) WHERE deleted_at IS NULL - 查询未删除记录

## ActivityLikes

| 字段          | 类型                                   | 含义          |
|-------------|--------------------------------------|-------------|
| id          | bigserial PRIMARY KEY                | 点赞ID        |
| account_id  | bigint NOT NULL REFERENCES users(id) | 账号ID        |
| target_type | varchar(32) NOT NULL                 | 目标类型        |
| activity_id | bigint NOT NULL                      | 记录ID        |
| deleted_at  | timestamptz                          | 软删除时间（取消点赞） |
| created_at  | timestamptz NOT NULL DEFAULT now()   | 创建时间        |
| updated_at  | timestamptz NOT NULL DEFAULT now()   | 更新时间        |

**约束：**
- `UNIQUE(account_id, target_type, activity_id)` - 防止重复点赞
- `CHECK(target_type IN ('food', 'dinner'))` - 限制目标类型

**索引：**
- `idx_activity_likes_unique` UNIQUE ON (account_id, target_type, activity_id) - 唯一约束索引
- `idx_activity_likes_target` ON (target_type, activity_id) - 查询某活动的所有点赞
- `idx_activity_likes_account` ON (account_id, created_at DESC) - 查询用户的点赞历史

## ActivityDinners

| 字段          | 类型                                   | 含义    |
|-------------|--------------------------------------|-------|
| id          | bigserial PRIMARY KEY                | 年夜饭ID |
| account_id  | bigint NOT NULL REFERENCES users(id) | 账号ID  |
| picture_url | text[]                               | 年夜饭图片 |
| description | text                                 | 年夜饭介绍 |
| likes_count | int NOT NULL DEFAULT 0               | 点赞数缓存 |
| deleted_at  | timestamptz                          | 软删除时间 |
| created_at  | timestamptz NOT NULL DEFAULT now()   | 创建时间  |
| updated_at  | timestamptz NOT NULL DEFAULT now()   | 更新时间  |

**索引：**
- `idx_activity_dinners_account` ON (account_id) - 查询用户的年夜饭
- `idx_activity_dinners_created` ON (created_at DESC) - 按时间排序
- `idx_activity_dinners_active` ON (deleted_at) WHERE deleted_at IS NULL - 查询未删除记录

## Notifications

| 字段          | 类型                                                        | 含义           |
|-------------|-----------------------------------------------------------|--------------|
| id          | bigserial PRIMARY KEY                                     | 通知ID         |
| account_id  | bigint NOT NULL REFERENCES users(id)                      | 接收者ID        |
| actor_id    | bigint NOT NULL REFERENCES users(id)                      | 触发者ID        |
| type        | varchar(32) NOT NULL                                      | 通知类型         |
| target_type | varchar(32) NOT NULL                                      | 目标类型         |
| target_id   | bigint NOT NULL                                           | 目标ID         |
| data        | jsonb                                                     | 额外数据         |
| read_at     | timestamptz                                               | 阅读时间(NULL未读) |
| expires_at  | timestamptz NOT NULL DEFAULT (now() + interval '30 days') | 过期时间（30天后）   |
| created_at  | timestamptz NOT NULL DEFAULT now()                        | 创建时间         |

**约束：**
- `CHECK(type IN ('like', 'comment', 'follow', 'system'))` - 限制通知类型
- `CHECK(target_type IN ('food', 'dinner', 'user'))` - 限制目标类型

**索引：**
- `idx_notifications_unread` ON (account_id, read_at) WHERE read_at IS NULL - 查询未读通知
- `idx_notifications_account_created` ON (account_id, created_at DESC) - 查询用户通知列表
- `idx_notifications_target` ON (target_type, target_id) - 查询某活动的通知
- `idx_notifications_expires` ON (expires_at) WHERE read_at IS NOT NULL - 定期清理已读过期通知

## Follow

| 字段          | 类型                                   | 含义           |
|-------------|--------------------------------------|--------------|
| id          | bigserial PRIMARY KEY                | 关注ID         |
| account_id  | bigint NOT NULL REFERENCES users(id) | 账号ID         |
| target_id   | bigint NOT NULL REFERENCES users(id) | 目标ID         |
| created_at  | timestamptz NOT NULL DEFAULT now()   | 创建时间         |

**约束：**
- `UNIQUE(account_id, target_id)` - 防止重复关注

**索引：**
- `idx_follow_unique` UNIQUE ON (account_id, target_id) - 唯一约束索引
- `idx_follow_target` ON (target_id, created_at DESC) - 查询用户的粉丝列表

## Privacy

| 字段          | 类型                                   | 含义         |
|-------------|--------------------------------------|------------|
| id          | bigserial PRIMARY KEY                | 隐私ID       |
| account_id  | bigint NOT NULL REFERENCES users(id) | 账号ID       |
| following   | boolean NOT NULL DEFAULT true        | 关注列表是否对外可见 |
| follower    | boolean NOT NULL DEFAULT true        | 粉丝列表是否对外可见 |
| created_at  | timestamptz NOT NULL DEFAULT now()   | 创建时间       |

## 建表语句（PostgreSQL）

```postgresql
-- PostgreSQL 使用 citext 做邮箱大小写不敏感唯一
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
  id            bigserial PRIMARY KEY,
  nick_name     varchar(32) NOT NULL UNIQUE,
  password_hash text        NOT NULL,
  avatar        text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE contacts (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id),
  email         citext      UNIQUE,
  phone         varchar(32)  UNIQUE,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_contacts_account_id ON contacts (account_id);

CREATE TABLE verifications (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id),
  method        varchar(16) NOT NULL,
  verified      boolean     NOT NULL DEFAULT false,
  student_id    varchar(32),
  real_name     varchar(64),
  verified_email citext,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (account_id),
  CHECK (method IN ('sso', 'school_email')),
  CHECK (
    (method = 'sso' AND student_id IS NOT NULL AND real_name IS NOT NULL AND verified_email IS NULL)
    OR
    (method = 'school_email' AND verified_email IS NOT NULL AND student_id IS NULL AND real_name IS NULL)
  )
);

CREATE TABLE provinces (
  id         serial PRIMARY KEY,
  name       varchar(64) NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cities (
  id          serial PRIMARY KEY,
  province_id int         NOT NULL REFERENCES provinces(id) ON DELETE CASCADE,
  name        varchar(64) NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE(province_id, name)
);

CREATE INDEX idx_cities_province ON cities(province_id);

CREATE TABLE user_info (
  id                   bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  gender               varchar(16) CHECK (gender IN ('male', 'female', 'other')),
  birthday             date,
  signature            varchar(255),
  location_province_id int REFERENCES provinces(id),
  location_city_id     int REFERENCES cities(id),
  hometown_province_id int REFERENCES provinces(id),
  hometown_city_id     int REFERENCES cities(id),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_userinfo_hometown ON user_info(hometown_province_id, hometown_city_id);
CREATE INDEX idx_userinfo_location ON user_info(location_province_id, location_city_id);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_contacts_updated_at BEFORE UPDATE ON contacts
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_verifications_updated_at BEFORE UPDATE ON verifications
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_info_updated_at BEFORE UPDATE ON user_info
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

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

-- 索引
CREATE INDEX idx_activity_likes_target ON activity_likes(target_type, activity_id);
CREATE INDEX idx_activity_likes_account ON activity_likes(account_id, created_at DESC);

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

CREATE TRIGGER update_activity_foods_updated_at BEFORE UPDATE ON activity_foods
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_activity_likes_updated_at BEFORE UPDATE ON activity_likes
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_activity_dinners_updated_at BEFORE UPDATE ON activity_dinners
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE follow (
  id            bigserial PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_id     bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(account_id, target_id)
);

-- 索引
CREATE INDEX idx_follow_target ON follow(target_id, created_at DESC);

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

CREATE TRIGGER update_likes_count_trigger
  AFTER INSERT OR UPDATE OR DELETE ON activity_likes
  FOR EACH ROW EXECUTE FUNCTION update_likes_count();

-- ============================================
-- 定期清理过期通知的定时任务（需要 pg_cron 扩展）
-- ============================================
-- CREATE EXTENSION IF NOT EXISTS pg_cron;
-- SELECT cron.schedule('clean-expired-notifications', '0 2 * * *',
--   'DELETE FROM notifications WHERE expires_at < now() AND read_at IS NOT NULL');
```

## 插件

```postgresql
CREATE EXTENSION IF NOT EXISTS citext;
```
