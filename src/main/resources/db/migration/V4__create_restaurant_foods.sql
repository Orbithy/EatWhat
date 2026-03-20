-- ============================================
-- V4: 创建 Hub、Restaurant、Foods 表
-- ============================================

-- PostGIS 空间扩展（Hub / Restaurant 依赖）
CREATE EXTENSION IF NOT EXISTS postgis;

-- ============================================
-- Hub 表（聚合点/商场）
-- ============================================
CREATE TABLE hub (
  id          bigserial PRIMARY KEY,
  name        text                  NOT NULL,
  center      geometry(Point, 4326) NOT NULL,
  gcj_lng     double precision      NOT NULL,
  gcj_lat     double precision      NOT NULL,
  boundary    geometry(MultiPolygon, 4326),
  created_at  timestamptz           NOT NULL DEFAULT now(),
  updated_at  timestamptz           NOT NULL DEFAULT now()
);

CREATE INDEX idx_hub_center   ON hub USING GIST (center);
CREATE INDEX idx_hub_boundary ON hub USING GIST (boundary);

CREATE TRIGGER update_hub_updated_at BEFORE UPDATE ON hub
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- Restaurant 表（餐厅）
-- ============================================
CREATE TABLE restaurant (
  id          bigserial PRIMARY KEY,
  name        text                   NOT NULL,
  address     text,
  location    geography(Point, 4326) NOT NULL,
  gcj_lng     double precision       NOT NULL,
  gcj_lat     double precision       NOT NULL,
  hub_id      bigint                 REFERENCES hub(id) ON DELETE SET NULL,
  poi         text                   UNIQUE,
  created_at  timestamptz            NOT NULL DEFAULT now(),
  updated_at  timestamptz            NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_restaurant_poi      ON restaurant (poi) WHERE poi IS NOT NULL;
CREATE INDEX        idx_restaurant_location ON restaurant USING GIST (location);
CREATE INDEX        idx_restaurant_hub      ON restaurant (hub_id);

CREATE TRIGGER update_restaurant_updated_at BEFORE UPDATE ON restaurant
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- Foods 表（餐厅菜品）
-- ============================================
CREATE TABLE foods (
  id            bigserial   PRIMARY KEY,
  account_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  restaurant_id bigint      NOT NULL REFERENCES restaurant(id) ON DELETE CASCADE,
  name          varchar(64) NOT NULL,
  description   text,
  price         numeric(8,2),
  category      varchar(32) CHECK (category IN (
                  'staple','drink','snack','dessert','soup',
                  'hot_pot','grill','cold_dish','side_dish','other'
                )),
  picture_url   text[],
  likes_count   int         NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE(restaurant_id, name)
);

CREATE INDEX idx_foods_restaurant ON foods (restaurant_id);
CREATE INDEX idx_foods_account    ON foods (account_id);
CREATE INDEX idx_foods_category   ON foods (restaurant_id, category);
CREATE INDEX idx_foods_likes      ON foods (restaurant_id, likes_count DESC);

CREATE TRIGGER update_foods_updated_at BEFORE UPDATE ON foods
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

