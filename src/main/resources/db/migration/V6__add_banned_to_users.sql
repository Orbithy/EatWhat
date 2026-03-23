-- V6: 给 users 表添加 banned 字段，用于记录用户封禁状态
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS banned boolean NOT NULL DEFAULT false;
