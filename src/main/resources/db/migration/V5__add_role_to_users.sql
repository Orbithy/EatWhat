-- V5: 给 users 表添加 role 字段，用于区分管理员和普通用户
ALTER TABLE users
    ADD COLUMN role varchar(16) NOT NULL DEFAULT 'user'
        CHECK (role IN ('user', 'admin'));

