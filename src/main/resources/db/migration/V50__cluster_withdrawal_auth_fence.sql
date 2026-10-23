SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';

-- A secondary records the withdrawal intent in the independent auth store before
-- it changes its own projection. This blocks new sessions while the authority is down.
CREATE TABLE eatwhat_cluster.session_withdraw_fence (
    subject_id uuid NOT NULL,
    user_id bigint NOT NULL,
    epoch bigint NOT NULL CHECK(epoch>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(subject_id,epoch)
);
CREATE FUNCTION eatwhat_cluster.guard_session_withdraw_fence() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
    RAISE EXCEPTION 'session withdrawal fences are immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER session_withdraw_fence_retained BEFORE UPDATE OR DELETE ON eatwhat_cluster.session_withdraw_fence
FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_session_withdraw_fence();
CREATE TRIGGER session_withdraw_fence_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.session_withdraw_fence
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON eatwhat_cluster.session_withdraw_fence FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_session_withdraw_fence() FROM PUBLIC;
