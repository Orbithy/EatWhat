-- ============================================
-- V14: restaurant 表添加上传者 account_id 字段
-- ============================================

ALTER TABLE restaurant
    ADD COLUMN account_id bigint REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_restaurant_account ON restaurant (account_id);

