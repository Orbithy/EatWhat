-- V9: 给 users 表添加 banned_at 字段，用于记录封禁时间
ALTER TABLE users
    ADD COLUMN banned_at timestamptz;
