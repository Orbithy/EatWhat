-- V10: 给 users 表添加封禁相关字段
ALTER TABLE users
    ADD COLUMN ban_reason text,
    ADD COLUMN banned_at  timestamptz;

