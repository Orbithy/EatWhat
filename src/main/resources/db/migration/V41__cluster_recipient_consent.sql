SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Explicit recipient grants never expand an older two-site grant to a newly added installation.
-- A global consent withdrawal/regrant changes epoch and invalidates all earlier recipient grants.
CREATE TABLE eatwhat_cluster.recipient_grant (
    grant_id bigserial PRIMARY KEY,
    subject_id uuid NOT NULL REFERENCES eatwhat_consent.subject(subject_id),
    epoch bigint NOT NULL CHECK(epoch>0),
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(subject_id,epoch,site_id,instance_id)
);
CREATE TRIGGER recipient_grant_retained BEFORE UPDATE OR DELETE ON eatwhat_cluster.recipient_grant
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
CREATE TRIGGER recipient_grant_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.recipient_grant
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON eatwhat_cluster.recipient_grant FROM PUBLIC;
