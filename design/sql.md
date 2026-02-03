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

| 字段          | 类型                                   | 含义 |
|---------------|----------------------------------------|------|
| id            | bigserial PRIMARY KEY                  | 用户ID |
| nick_name     | varchar(32) NOT NULL UNIQUE            | 用户名 |
| password_hash | text NOT NULL                          | 密码哈希 |
| avatar        | text                                   | 头像 URL |
| created_at    | timestamptz NOT NULL DEFAULT now()     | 创建时间 |
| updated_at    | timestamptz NOT NULL DEFAULT now()     | 更新时间 |

## Contact

| 字段          | 类型                                   | 含义 |
|---------------|----------------------------------------|------|
| id            | bigserial PRIMARY KEY                  | 记录ID |
| account_id    | bigint NOT NULL REFERENCES accounts(id) | 账号ID |
| email         | citext UNIQUE                          | 邮箱 |
| phone         | varchar(32) UNIQUE                     | 手机号 |
| created_at    | timestamptz NOT NULL DEFAULT now()     | 创建时间 |
| updated_at    | timestamptz NOT NULL DEFAULT now()     | 更新时间 |

## Verifications

| 字段          | 类型                                   | 含义 |
|---------------|----------------------------------------|------|
| id            | bigserial PRIMARY KEY                  | 记录ID |
| account_id    | bigint NOT NULL REFERENCES accounts(id) | 账号ID |
| method        | varchar(16) NOT NULL                   | 认证方式：sso / school_email |
| verified      | boolean NOT NULL DEFAULT false         | 是否已验证 |
| student_id    | varchar(32)                            | 学号（统一身份认证） |
| real_name     | varchar(64)                            | 姓名（统一身份认证） |
| verified_email| citext                                 | 认证邮箱（学校邮箱） |
| created_at    | timestamptz NOT NULL DEFAULT now()     | 创建时间 |
| updated_at    | timestamptz NOT NULL DEFAULT now()     | 更新时间 |

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
```

## 插件

```postgresql
CREATE EXTENSION IF NOT EXISTS citext;
```
