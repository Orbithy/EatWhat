SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE eatwhat_cluster.replica_fact (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    site_id varchar(64) NOT NULL,
    instance_id uuid NOT NULL,
    sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes>0),
    state varchar(16) NOT NULL CHECK(state IN ('VERIFIED','DELETED')),
    verified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(file_id,revision,site_id,instance_id),
    FOREIGN KEY(file_id,revision) REFERENCES eatwhat_file.file_revision(file_id,revision)
);
CREATE TABLE eatwhat_cluster.replica_manifest (
    file_id uuid NOT NULL,
    revision integer NOT NULL,
    epoch bigint NOT NULL CHECK(epoch>0),
    generation bigint NOT NULL CHECK(generation>=0),
    required_members jsonb NOT NULL CHECK(jsonb_typeof(required_members)='array'),
    PRIMARY KEY(file_id,revision),
    FOREIGN KEY(file_id,revision) REFERENCES eatwhat_file.file_revision(file_id,revision)
);
CREATE FUNCTION eatwhat_cluster.guard_replica_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'replica facts are retained' USING ERRCODE='23514'; END IF;
    IF (NEW.file_id,NEW.revision,NEW.site_id,NEW.instance_id,NEW.sha256,NEW.size_bytes)
       IS DISTINCT FROM (OLD.file_id,OLD.revision,OLD.site_id,OLD.instance_id,OLD.sha256,OLD.size_bytes)
       OR (OLD.state='DELETED' AND NEW.state<>'DELETED') THEN
       RAISE EXCEPTION 'replica identity and deletion are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER replica_fact_guard BEFORE UPDATE OR DELETE ON eatwhat_cluster.replica_fact
FOR EACH ROW EXECUTE FUNCTION eatwhat_cluster.guard_replica_fact();
CREATE TRIGGER replica_fact_no_truncate BEFORE TRUNCATE ON eatwhat_cluster.replica_fact
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
CREATE TRIGGER replica_manifest_no_delete BEFORE DELETE OR TRUNCATE ON eatwhat_cluster.replica_manifest
FOR EACH STATEMENT EXECUTE FUNCTION eatwhat_sync.reject_event_mutation();
REVOKE ALL ON eatwhat_cluster.replica_fact,eatwhat_cluster.replica_manifest FROM PUBLIC;
REVOKE ALL ON FUNCTION eatwhat_cluster.guard_replica_fact() FROM PUBLIC;
