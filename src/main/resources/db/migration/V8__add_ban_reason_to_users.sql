-- V8: 给 users 表添加 ban_reason 字段，用于记录封禁原因
ALTER TABLE users
    ADD COLUMN ban_reason text;
