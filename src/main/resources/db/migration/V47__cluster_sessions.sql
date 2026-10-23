SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
CREATE TABLE eatwhat_cluster.login_session (
 token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 subject_id uuid NOT NULL,user_id bigint NOT NULL,source_site varchar(64) NOT NULL,source_instance uuid NOT NULL,
 consent_epoch bigint NOT NULL CHECK(consent_epoch>=0),security_hash char(64) NOT NULL,
 expires_at timestamptz NOT NULL,idle_seconds bigint NOT NULL CHECK(idle_seconds BETWEEN 1 AND 2592000),
 last_seen timestamptz NOT NULL DEFAULT clock_timestamp(),revoked boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX cluster_session_owner ON eatwhat_cluster.login_session(user_id) WHERE NOT revoked;
CREATE FUNCTION eatwhat_cluster.guard_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-'revoked'-'last_seen') IS DISTINCT FROM (to_jsonb(OLD)-'revoked'-'last_seen') OR (OLD.revoked AND NOT NEW.revoked) OR NEW.last_seen<OLD.last_seen THEN
   RAISE EXCEPTION 'cluster session identity and revocation are retained' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER cluster_session_guard BEFORE UPDATE OR DELETE ON eatwhat_cluster.login_session FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_session();
CREATE TRIGGER cluster_session_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.login_session FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON eatwhat_cluster.login_session FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_session() FROM PUBLIC;
