SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE eatwhat_cluster.replay_cursor ADD COLUMN account_last_user bigint NOT NULL DEFAULT 0 CHECK(account_last_user>=0);
